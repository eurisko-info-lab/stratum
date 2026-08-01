package stratum.repo

import stratum.artifact.{Artifact, Closure, DirectoryCas}
import stratum.canon.{Canon, CanonText, Digest}
import stratum.journal.Journal
import stratum.cli.{Cli, CommandResult}
import stratum.grammar.GrammarMachine0

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * The concrete, deliberately small bridge from a filesystem into Stratum's
 * content-addressed repository and append-only publication chain.
 *
 * Files are blobs, a checkout is a canonical tree, a semantic patch records
 * the path-to-blob additions/replacements, and a block anchors that patch to
 * its predecessor. Mutable branch names contain only the current block
 * identity; history and file content are immutable artifacts.
 */
object StratumRepo:

  private final case class LanguageInfo(name: String, reader: String)

  private final case class PatchChange(path: String, before: Option[Digest], after: Option[Digest])

  private final case class MaterializationProfile(name: String, allowed: Set[String])

  private final case class TreeEntry(
      path: String,
      content: Digest,
      blob: Option[Digest],
      materializer: Option[Digest],
      language: Digest,
      grammar: Option[Digest],
      meta: Option[Digest],
      syntax: Digest
  )

  private val Format = "stratum-repository-v1"
  private val MaterializationProfileV1 = MaterializationProfile(
    "materialization-profile-v1",
    Set("blob-v1", "grammar0-native-print-v1", "canon-text-write-v1", "negative-fixture-bytes-v1")
  )
  private val MaterializationProfileV2GeneratedOnly = MaterializationProfile(
    "materialization-profile-v2-generated-only",
    Set("grammar0-native-print-v1", "canon-text-write-v1", "negative-fixture-bytes-v1")
  )
  private val MaterializationProfiles = Vector(MaterializationProfileV1, MaterializationProfileV2GeneratedOnly)
  private val MaterializationProfilesByName = MaterializationProfiles.map(profile => profile.name -> profile).toMap
  private val ExcludedNames =
    Set(".git", ".stratum", ".bloop", ".bsp", ".metals", ".scala-build", "target", "node_modules", "__pycache__", ".lake")

  def main(args: Array[String]): Unit =
    val root = Path.of(System.getProperty("user.dir")).toAbsolutePath.normalize()
    val result = run(root, args.toVector)
    if result.lines.nonEmpty then println(result.output)
    if result.code != 0 then sys.exit(result.code)

  def run(root: Path, args: Vector[String]): CommandResult =
    val opts = Cli.options(args)
    val sub = Cli.positional(args).headOption.getOrElse("")
    opts.get("dir") match
      case None => CommandResult.fail("repository commands require --dir <dir>")
      case Some(name) =>
        val dir = resolve(root, name)
        sub match
          case "init"   => init(dir)
          case "branch" => createBranch(dir, opts.getOrElse("name", ""), opts.getOrElse("from", "main"))
          case "branches" => branches(dir)
          case "profiles" => profiles()
          case "checkout" => checkout(dir, opts.getOrElse("branch", "main"), resolve(root, opts.getOrElse("out", "")))
          case "record" =>
            val source = resolve(root, opts.getOrElse("source", "."))
            record(
              dir,
              opts.getOrElse("branch", "main"),
              source,
              opts.get("declaration-root").map(resolve(root, _)).getOrElse(source),
              opts.getOrElse("message", "record working tree"),
              opts.getOrElse("materialization-profile", MaterializationProfileV1.name)
            )
          case "status" =>
            status(
              dir,
              opts.getOrElse("branch", "main"),
              resolve(root, opts.getOrElse("source", ".")),
              opts.get("declaration-root").map(resolve(root, _)).getOrElse(resolve(root, opts.getOrElse("source", ".")))
            )
          case "log"    => log(dir, opts.getOrElse("branch", "main"))
          case "verify" => verify(dir, opts.getOrElse("branch", "main"))
          case other    => CommandResult.fail(s"unknown repository command $other")

  private def resolve(root: Path, value: String): Path =
    root.resolve(value).toAbsolutePath.normalize()

  private def init(dir: Path): CommandResult =
    if Files.exists(dir) && (!Files.isDirectory(dir) || list(dir).nonEmpty) then
      CommandResult.fail(s"repository directory is not empty: $dir")
    else
      Journal.createDirectories(dir)
      Journal.createDirectories(dir.resolve("objects"))
      Journal.createDirectories(dir.resolve("refs"))
      Journal.writeString(dir.resolve("format"), Format + "\n")
      CommandResult.ok(s"initialized $dir", s"format $Format")

  private def open(dir: Path): Either[String, DirectoryCas] =
    val format = dir.resolve("format")
    if !Files.isRegularFile(format) then Left(s"not a Stratum repository: $dir")
    else if Files.readString(format).trim != Format then Left(s"unsupported repository format in $dir")
    else Right(DirectoryCas(dir.resolve("objects")))

  private def createBranch(dir: Path, name: String, from: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(_) =>
        refPath(dir, name) match
          case Left(error) => CommandResult.fail(error)
          case Right(target) if Files.exists(target) => CommandResult.fail(s"branch already exists: $name")
          case Right(target) =>
            readRef(dir, from) match
              case None => CommandResult.fail(s"no such source branch: $from")
              case Some(head) =>
                Journal.writeString(target, head.hex + "\n")
                CommandResult.ok(s"branch $name", s"from $from", s"head ${head.hex}")

  private def branches(dir: Path): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(_) =>
        val refs = dir.resolve("refs")
        val stream = Files.walk(refs)
        try
          CommandResult.okLines(
            stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector
              .sortBy(_.toString)
              .map(path => s"${unix(refs.relativize(path))} ${Files.readString(path).trim}")
          )
        finally stream.close()

  private def profiles(): CommandResult =
    CommandResult.okLines(
      MaterializationProfiles.map(profile =>
        s"${profile.name} [${profile.allowed.toVector.sorted.mkString(", ")}]"
      )
    )

  private def record(
      dir: Path,
      branch: String,
      source: Path,
        declarationRoot: Path,
      message: String,
      profileName: String
  ): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) =>
        val profile = resolveMaterializationProfile(profileName) match
          case Left(error) => return CommandResult.fail(error)
          case Right(value) => value
        if !Files.isDirectory(source) then CommandResult.fail(s"source directory does not exist: $source")
        else
          val files = sourceFiles(source, dir)
          val declarations = LanguageDeclarations.load(declarationRoot) match
            case Left(error) => return CommandResult.fail(error)
            case Right(value) => value
          val structured = files.map { file =>
            val relative = unix(source.relativize(file))
            LanguageDeclarations.structure(declarationRoot, relative, Files.readAllBytes(file), declarations, cas)
              .map(relative -> _)
              .left.map(error => s"$relative: $error")
          }
          structured.collectFirst { case Left(error) => error } match
            case Some(error) => return CommandResult.fail(error)
            case None => ()
          val entries = structured.collect { case Right(entry) => entry }
          val tree = Artifact(
            "tree",
            Canon.node(
              "tree",
              Canon.L(entries.map { (path, file) =>
                Canon.node(
                  "entry",
                  Canon.S(path),
                  Canon.S(file.content.hex),
                  file.blob.map(Canon.R.apply).getOrElse(Canon.Sym("generated")),
                  Canon.R(file.materializer),
                  Canon.R(file.language),
                  file.grammar.map(Canon.R.apply).getOrElse(Canon.Sym("native")),
                  file.meta.map(Canon.R.apply).getOrElse(Canon.Sym("native")),
                  Canon.R(file.syntax)
                )
              })
            )
          )
          val treeDigest = cas.put(tree)
          val previous = readRef(dir, branch)
          val previousTree = previous.flatMap(blockTree(cas, _).toOption)
          val profileDigest = cas.put(materializationProfileArtifact(profile))
          treeEntries(cas, treeDigest) match
            case Left(error) => return CommandResult.fail(error)
            case Right(recordedEntries) =>
              validateTreeEntryInvariants(cas, treeDigest, Some(profileDigest), recordedEntries) match
                case Left(error) => return CommandResult.fail(error)
                case Right(_) => ()
          val previousProfileName = previous.flatMap(digest => readBlock(cas, digest).toOption).flatMap {
            case (_, _, _, profileDigest) => readMaterializationProfile(cas, profileDigest).toOption.map(_.name)
          }
          if previousTree.contains(treeDigest) && previousProfileName.contains(profile.name) then
            CommandResult.fail("working tree is already recorded")
          else
            val before = previousTree.flatMap(treeEntries(cas, _).toOption).getOrElse(Map.empty).view.mapValues(_.content).toMap
            val after = entries.map((path, file) => path -> file.content).toMap
            val changes = (before.keySet ++ after.keySet).toVector.sorted.flatMap { path =>
              (before.get(path), after.get(path)) match
                case (None, Some(now))                        => Some(Canon.node("add", Canon.S(path), Canon.S(now.hex)))
                case (Some(was), None)                        => Some(Canon.node("remove", Canon.S(path), Canon.S(was.hex)))
                case (Some(was), Some(now)) if was != now     => Some(Canon.node("replace", Canon.S(path), Canon.S(was.hex), Canon.S(now.hex)))
                case _                                       => None
            }
            val patch = Artifact(
              "patch",
              Canon.node(
                "patch",
                previous.map(Canon.R.apply).getOrElse(Canon.Sym("genesis")),
                Canon.R(treeDigest),
                Canon.S(message),
                Canon.L(changes)
              )
            )
            val patchDigest = cas.put(patch)
            val height = previous.flatMap(blockHeight(cas, _).toOption).getOrElse(0L) + 1L
            val block = Artifact(
              "block",
              Canon.node(
                "block",
                Canon.N(BigInt(height)),
                previous.map(Canon.R.apply).getOrElse(Canon.Sym("genesis")),
                Canon.R(patchDigest),
                Canon.R(profileDigest)
              )
            )
            val blockDigest = cas.put(block)
            val target = refPath(dir, branch) match
              case Left(error) => return CommandResult.fail(error)
              case Right(path) => path
            Journal.writeString(target, blockDigest.hex + "\n")
            if branch == "main" then Journal.writeString(dir.resolve("HEAD"), "ref: refs/main\n")
            CommandResult.ok(
              s"branch $branch",
              s"recorded ${files.length} files",
              s"profile ${profile.name}",
              s"changes ${changes.length}",
              s"tree ${treeDigest.hex}",
              s"patch ${patchDigest.hex}",
              s"block ${blockDigest.hex}",
              s"height $height"
            )

  private def status(dir: Path, branch: String, source: Path, declarationRoot: Path): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) =>
        readRef(dir, branch) match
          case None => CommandResult.ok("unborn main")
          case Some(head) =>
            blockTree(cas, head).flatMap(treeEntries(cas, _)) match
              case Left(error) => CommandResult.fail(error)
              case Right(recorded) =>
                val declarations = LanguageDeclarations.load(declarationRoot) match
                  case Left(error) => return CommandResult.fail(error)
                  case Right(value) => value
                val current = sourceFiles(source, dir).map { file =>
                  val relative = unix(source.relativize(file))
                  LanguageDeclarations.structure(declarationRoot, relative, Files.readAllBytes(file), declarations, cas)
                    .map(relative -> _.content)
                    .left.map(error => s"$relative: $error")
                }
                current.collectFirst { case Left(error) => error } match
                  case Some(error) => CommandResult.fail(error)
                  case None =>
                    val currentMap = current.collect { case Right(entry) => entry }.toMap
                    val changed = (recorded.keySet ++ currentMap.keySet).count(path => recorded.get(path).map(_.content) != currentMap.get(path))
                    if changed == 0 then CommandResult.ok(s"clean ${recorded.size} files")
                    else CommandResult.ok(s"changed $changed files")

  private def checkout(dir: Path, branch: String, out: Path): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) =>
        if Files.exists(out) && (!Files.isDirectory(out) || list(out).nonEmpty) then
          CommandResult.fail(s"checkout directory is not empty: $out")
        else
          readRef(dir, branch) match
            case None => CommandResult.fail(s"no such branch: $branch")
            case Some(head) =>
              blockTree(cas, head).flatMap(treeEntries(cas, _)) match
                case Left(error) => CommandResult.fail(error)
                case Right(entries) =>
                  Journal.createDirectories(out)
                  val ordered = entries.values.toVector.sortBy(_.path)
                  var index = 0
                  while index < ordered.length do
                    val entry = ordered(index)
                    materialize(entry, cas) match
                      case Left(error) => return CommandResult.fail(error)
                      case Right(bytes) =>
                        val target = out.resolve(entry.path).normalize()
                        if !target.startsWith(out) then return CommandResult.fail(s"unsafe checkout path: ${entry.path}")
                        Journal.write(target, bytes)
                        if entry.path.endsWith(".sh") then target.toFile.setExecutable(true, false)
                    index += 1
                  CommandResult.ok(s"checked out $branch", s"files ${entries.size}", s"head ${head.hex}", s"out $out")

  private def verify(dir: Path, branch: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) =>
        readRef(dir, branch) match
          case None => CommandResult.ok("valid empty chain")
          case Some(head) =>
            val seen = mutable.HashSet.empty[Digest]
            var current: Option[Digest] = Some(head)
            var expectedHeight: Option[Long] = None
            while current.nonEmpty do
              val digest = current.get
              if seen.contains(digest) then return CommandResult.fail(s"chain cycle at ${digest.hex}")
              seen += digest
              Closure.traverse(cas, digest) match
                case Left(missing) => return CommandResult.fail(s"missing object ${missing.hex}")
                case Right(_)      => ()
              readBlock(cas, digest) match
                case Left(error) => return CommandResult.fail(error)
                case Right((height, predecessor, patchDigest, profileDigest)) =>
                  val profile = readMaterializationProfile(cas, profileDigest)
                  profile match
                    case Left(error) => return CommandResult.fail(error)
                    case Right(_)    => ()
                  readPatch(cas, patchDigest) match
                    case Left(error) => return CommandResult.fail(error)
                    case Right((patchPredecessor, treeDigest, changes)) =>
                      if patchPredecessor != predecessor then
                        return CommandResult.fail(s"block ${digest.hex} predecessor does not match patch ${patchDigest.hex}")
                      val before = predecessor match
                        case None => Right(Map.empty[String, Digest])
                        case Some(previousBlock) =>
                          blockTree(cas, previousBlock)
                            .flatMap(treeEntries(cas, _))
                            .map(entries => entries.view.mapValues(_.content).toMap)
                      val after = treeEntries(cas, treeDigest)
                      (before, after) match
                        case (Left(error), _) => return CommandResult.fail(error)
                        case (_, Left(error)) => return CommandResult.fail(error)
                        case (Right(beforeMap), Right(afterEntries)) =>
                          validateTreeEntryInvariants(cas, treeDigest, profileDigest, afterEntries) match
                            case Left(error) => return CommandResult.fail(error)
                            case Right(_)    => ()
                          val afterMap = afterEntries.view.mapValues(_.content).toMap
                          applyPatch(beforeMap, changes) match
                            case Left(error) => return CommandResult.fail(s"invalid patch ${patchDigest.hex}: $error")
                            case Right(rebuilt) if rebuilt != afterMap =>
                              val path = (rebuilt.keySet ++ afterMap.keySet).toVector.sorted
                                .find(p => rebuilt.get(p) != afterMap.get(p))
                                .getOrElse("<unknown>")
                              return CommandResult.fail(
                                s"patch ${patchDigest.hex} does not derive tree ${treeDigest.hex} at $path"
                              )
                            case Right(_) => ()
                  expectedHeight match
                    case Some(expected) if height != expected =>
                      return CommandResult.fail(s"height $height follows height ${expected + 1}")
                    case _ => ()
                  expectedHeight = Some(height - 1)
                  current = predecessor
            if expectedHeight.exists(_ != 0) then CommandResult.fail("chain does not end at height 1")
            else CommandResult.ok(s"valid branch $branch", s"chain ${seen.size} blocks", s"head ${head.hex}")

  private def validateTreeEntryInvariants(
      cas: DirectoryCas,
      treeDigest: Digest,
      profileDigest: Option[Digest],
      entries: Map[String, TreeEntry]
  ): Either[String, Unit] =
    val profile = readMaterializationProfile(cas, profileDigest)
    entries.values.toVector.foldLeft[Either[String, Unit]](profile.map(_ => ())) { (acc, entry) =>
      acc.flatMap { _ =>
        for
          language <- readLanguageInfo(cas, entry.language)
          materializer <- readMaterializerId(cas, entry)
          allowed <- profile
          _ <-
            if allowed.allowed.contains(materializer) then Right(())
            else Left(s"tree ${treeDigest.hex} ${entry.path}: materializer $materializer is not allowed by profile ${profileDigest.map(_.hex).getOrElse("legacy")}")
          _ <- materializer match
            case "blob-v1" =>
              if entry.blob.isEmpty then Left(s"tree ${treeDigest.hex} ${entry.path}: blob materializer requires blob")
              else Right(())
            case "grammar0-native-print-v1" =>
              if language.reader != "grammar" then Left(s"tree ${treeDigest.hex} ${entry.path}: grammar materializer mismatches reader ${language.reader}")
              else if entry.grammar.isEmpty then Left(s"tree ${treeDigest.hex} ${entry.path}: grammar materializer requires grammar")
              else if entry.blob.nonEmpty then Left(s"tree ${treeDigest.hex} ${entry.path}: grammar materializer must not keep blob")
              else Right(())
            case "canon-text-write-v1" =>
              if language.reader != "canon" then Left(s"tree ${treeDigest.hex} ${entry.path}: canon materializer mismatches reader ${language.reader}")
              else if entry.blob.nonEmpty then Left(s"tree ${treeDigest.hex} ${entry.path}: canon materializer must not keep blob")
              else Right(())
            case "negative-fixture-bytes-v1" =>
              if language.reader != "negative-fixture" then Left(s"tree ${treeDigest.hex} ${entry.path}: negative-fixture materializer mismatches reader ${language.reader}")
              else if entry.blob.nonEmpty then Left(s"tree ${treeDigest.hex} ${entry.path}: negative-fixture materializer must not keep blob")
              else Right(())
            case other => Left(s"tree ${treeDigest.hex} ${entry.path}: unsupported materializer $other")
        yield ()
      }
    }

  private def log(dir: Path, branch: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) =>
        val lines = Vector.newBuilder[String]
        var current = readRef(dir, branch)
        while current.nonEmpty do
          val digest = current.get
          readBlock(cas, digest) match
            case Left(error) => return CommandResult.fail(error)
            case Right((height, predecessor, patchDigest, _)) =>
              val message = cas.get(patchDigest).collect {
                case Artifact("patch", Canon.Node("patch", Vector(_, _, Canon.S(text), _))) => text
              }.getOrElse("<invalid patch>")
              lines += s"$height ${digest.hex} $message"
              current = predecessor
        CommandResult.okLines(lines.result())

  private def readRef(dir: Path, branch: String): Option[Digest] =
    refPath(dir, branch).toOption.filter(Files.isRegularFile(_)).flatMap(path => Digest.fromHex(Files.readString(path).trim).toOption)

  private def refPath(dir: Path, branch: String): Either[String, Path] =
    if branch.isEmpty || branch.startsWith("/") || branch.endsWith("/") ||
       branch.split("/").exists(part => part.isEmpty || part == "." || part == ".." || !part.matches("[A-Za-z0-9._-]+"))
    then Left(s"invalid branch name: $branch")
    else
      val path = dir.resolve("refs").resolve(branch).normalize()
      if !path.startsWith(dir.resolve("refs")) then Left(s"invalid branch name: $branch") else Right(path)

  private def readBlock(cas: DirectoryCas, digest: Digest): Either[String, (Long, Option[Digest], Digest, Option[Digest])] =
    cas.get(digest) match
      case Some(Artifact("block", Canon.Node("block", Vector(Canon.N(height), predecessor, Canon.R(patch), Canon.R(profileDigest))))) if height.isValidLong =>
        predecessor match
          case Canon.Sym("genesis") => Right((height.longValue, None, patch, Some(profileDigest)))
          case Canon.R(previous)     => Right((height.longValue, Some(previous), patch, Some(profileDigest)))
          case _                    => Left(s"invalid predecessor in block ${digest.hex}")
      case Some(Artifact("block", Canon.Node("block", Vector(Canon.N(height), predecessor, Canon.R(patch))))) if height.isValidLong =>
        predecessor match
          case Canon.Sym("genesis") => Right((height.longValue, None, patch, None))
          case Canon.R(previous)     => Right((height.longValue, Some(previous), patch, None))
          case _                    => Left(s"invalid predecessor in block ${digest.hex}")
      case _ => Left(s"invalid block ${digest.hex}")

  private def blockHeight(cas: DirectoryCas, digest: Digest): Either[String, Long] =
    readBlock(cas, digest).map(_._1)

  private def blockTree(cas: DirectoryCas, digest: Digest): Either[String, Digest] =
    readBlock(cas, digest).flatMap { case (_, _, patch, _) =>
      cas.get(patch) match
        case Some(Artifact("patch", Canon.Node("patch", Vector(_, Canon.R(tree), Canon.S(_), Canon.L(_))))) => Right(tree)
        case _ => Left(s"invalid patch ${patch.hex}")
    }

  private def resolveMaterializationProfile(name: String): Either[String, MaterializationProfile] =
    MaterializationProfilesByName.get(name).toRight(s"unknown materialization profile $name")

  private def materializationProfileArtifact(profile: MaterializationProfile): Artifact =
    Artifact(
      "materialization-profile",
      Canon.node(
        "materialization-profile",
        Canon.Sym(profile.name),
        Canon.L(profile.allowed.toVector.sorted.map(Canon.Sym.apply))
      )
    )

  private def readMaterializationProfile(cas: DirectoryCas, digest: Option[Digest]): Either[String, MaterializationProfile] =
    digest match
      case None => Right(MaterializationProfileV1)
      case Some(d) =>
        cas.get(d) match
          case Some(Artifact("materialization-profile", Canon.Node("materialization-profile", Vector(Canon.Sym(name), Canon.L(values))))) =>
            values.foldLeft[Either[String, Set[String]]](Right(Set.empty)) { (acc, value) =>
              (acc, value) match
                case (Right(current), Canon.Sym(id)) => Right(current + id)
                case _                               => Left(s"invalid materialization profile ${d.hex}")
            }.flatMap { parsedAllowed =>
              resolveMaterializationProfile(name).flatMap { expected =>
                if expected.allowed == parsedAllowed then Right(expected)
                else Left(s"materialization profile $name in ${d.hex} does not match its canonical definition")
              }
            }
          case _ => Left(s"missing materialization profile ${d.hex}")

  private def readPatch(cas: DirectoryCas, digest: Digest): Either[String, (Option[Digest], Digest, Vector[PatchChange])] =
    cas.get(digest) match
      case Some(Artifact("patch", Canon.Node("patch", Vector(predecessor, Canon.R(tree), Canon.S(_), Canon.L(changes))))) =>
        val previous = predecessor match
          case Canon.Sym("genesis") => Right(None)
          case Canon.R(value)         => Right(Some(value))
          case _                      => Left(s"invalid predecessor in patch ${digest.hex}")
        val parsed = changes.map(parsePatchChange(digest, _))
        parsed.collectFirst { case Left(error) => error } match
          case Some(error) => Left(error)
          case None        => previous.map(value => (value, tree, parsed.collect { case Right(change) => change }))
      case _ => Left(s"invalid patch ${digest.hex}")

  private def parsePatchChange(patchDigest: Digest, value: Canon): Either[String, PatchChange] = value match
    case Canon.Node("add", Vector(Canon.S(path), Canon.S(afterHex))) =>
      Digest.fromHex(afterHex)
        .map(after => PatchChange(path, None, Some(after)))
        .left.map(_ => s"invalid add digest in patch ${patchDigest.hex}")
    case Canon.Node("remove", Vector(Canon.S(path), Canon.S(beforeHex))) =>
      Digest.fromHex(beforeHex)
        .map(before => PatchChange(path, Some(before), None))
        .left.map(_ => s"invalid remove digest in patch ${patchDigest.hex}")
    case Canon.Node("replace", Vector(Canon.S(path), Canon.S(beforeHex), Canon.S(afterHex))) =>
      for
        before <- Digest.fromHex(beforeHex).left.map(_ => s"invalid replace old digest in patch ${patchDigest.hex}")
        after <- Digest.fromHex(afterHex).left.map(_ => s"invalid replace new digest in patch ${patchDigest.hex}")
      yield PatchChange(path, Some(before), Some(after))
    case _ => Left(s"invalid change in patch ${patchDigest.hex}")

  private def applyPatch(before: Map[String, Digest], changes: Vector[PatchChange]): Either[String, Map[String, Digest]] =
    val updated = mutable.Map.from(before)
    var index = 0
    while index < changes.length do
      val change = changes(index)
      change match
        case PatchChange(path, None, Some(after)) =>
          if updated.contains(path) then return Left(s"add expects missing path $path")
          updated.update(path, after)
        case PatchChange(path, Some(expected), None) =>
          updated.get(path) match
            case Some(found) if found == expected => updated.remove(path)
            case Some(found) => return Left(s"remove expected ${expected.hex} at $path but found ${found.hex}")
            case None => return Left(s"remove expects existing path $path")
        case PatchChange(path, Some(expected), Some(after)) =>
          updated.get(path) match
            case Some(found) if found == expected => updated.update(path, after)
            case Some(found) => return Left(s"replace expected ${expected.hex} at $path but found ${found.hex}")
            case None => return Left(s"replace expects existing path $path")
        case PatchChange(path, None, None) =>
          return Left(s"change at $path has neither before nor after")
      index += 1
    Right(updated.toMap)

  private def treeEntries(cas: DirectoryCas, digest: Digest): Either[String, Map[String, TreeEntry]] =
    cas.get(digest) match
      case Some(Artifact("tree", Canon.Node("tree", Vector(Canon.L(entries))))) =>
        val parsed = entries.map(parseTreeEntry(digest, _))
        parsed.collectFirst { case Left(error) => error } match
          case Some(error) => Left(error)
          case None        => Right(parsed.collect { case Right(entry) => entry }.toMap)
      case _ => Left(s"invalid tree ${digest.hex}")

  private def parseTreeEntry(treeDigest: Digest, value: Canon): Either[String, (String, TreeEntry)] =
    value match
      case Canon.Node("entry", Vector(Canon.S(path), Canon.S(contentHex), blobValue, Canon.R(materializer), Canon.R(language), grammarValue, metaValue, Canon.R(syntax))) =>
        for
          content <- Digest.fromHex(contentHex).left.map(_ => s"invalid content digest in tree ${treeDigest.hex}")
          blob <- parseOptionalDigest(blobValue, "generated", s"invalid blob marker in tree ${treeDigest.hex}")
          grammar <- parseOptionalDigest(grammarValue, "native", s"invalid grammar entry in tree ${treeDigest.hex}")
          meta <- parseOptionalDigest(metaValue, "native", s"invalid meta entry in tree ${treeDigest.hex}")
        yield path -> TreeEntry(path, content, blob, Some(materializer), language, grammar, meta, syntax)
      case Canon.Node("entry", Vector(Canon.S(path), Canon.S(contentHex), blobValue, Canon.R(language), grammarValue, metaValue, Canon.R(syntax))) =>
        for
          content <- Digest.fromHex(contentHex).left.map(_ => s"invalid content digest in tree ${treeDigest.hex}")
          blob <- parseOptionalDigest(blobValue, "generated", s"invalid blob marker in tree ${treeDigest.hex}")
          grammar <- parseOptionalDigest(grammarValue, "native", s"invalid grammar entry in tree ${treeDigest.hex}")
          meta <- parseOptionalDigest(metaValue, "native", s"invalid meta entry in tree ${treeDigest.hex}")
        yield path -> TreeEntry(path, content, blob, None, language, grammar, meta, syntax)
      case Canon.Node("entry", Vector(Canon.S(path), Canon.R(blob), Canon.R(language), grammarValue, metaValue, Canon.R(syntax))) =>
        val legacyContent = Artifact(
          "structured-content",
          Canon.node(
            "structured-content",
            Canon.R(blob),
            Canon.R(language),
            grammarValue,
            metaValue,
            Canon.R(syntax)
          )
        ).digest
        for
          grammar <- parseOptionalDigest(grammarValue, "native", s"invalid grammar entry in tree ${treeDigest.hex}")
          meta <- parseOptionalDigest(metaValue, "native", s"invalid meta entry in tree ${treeDigest.hex}")
        yield path -> TreeEntry(path, legacyContent, Some(blob), None, language, grammar, meta, syntax)
      case _ => Left(s"invalid entry in tree ${treeDigest.hex}")

  private def parseOptionalDigest(value: Canon, absent: String, error: String): Either[String, Option[Digest]] =
    value match
      case Canon.R(d)                      => Right(Some(d))
      case Canon.Sym(tag) if tag == absent => Right(None)
      case _                               => Left(error)

  private def materialize(entry: TreeEntry, cas: DirectoryCas): Either[String, Array[Byte]] =
    for
      language <- readLanguageInfo(cas, entry.language)
      materializer <- readMaterializerId(cas, entry).left.map(error => s"${entry.path}: $error")
      syntaxValue <- cas.get(entry.syntax) match
        case Some(Artifact("syntax", Canon.Node("syntax", Vector(_, _, syntax)))) => Right(syntax)
        case _ => Left(s"${entry.path}: missing syntax ${entry.syntax.hex}")
      bytes <- materializer match
        case "blob-v1" =>
          entry.blob match
            case Some(digest) =>
              cas.get(digest) match
                case Some(Artifact("blob", Canon.Node("blob", Vector(Canon.Y(bytes))))) => Right(bytes.toArray)
                case _ => Left(s"${entry.path}: invalid blob ${digest.hex}")
            case None => Left(s"${entry.path}: blob materializer requires a blob")
        case "grammar0-native-print-v1" =>
          if language.reader != "grammar" then Left(s"${entry.path}: grammar materializer mismatches reader ${language.reader}")
          else
            for
              grammarDigest <- entry.grammar.toRight(s"${entry.path}: generated grammar entry has no grammar")
              grammarBody <- cas.get(grammarDigest) match
                case Some(Artifact("grammar", body)) => Right(body)
                case _ => Left(s"${entry.path}: missing grammar ${grammarDigest.hex}")
              grammar <- GrammarMachine0.load(grammarBody).left.map(error => s"${entry.path}: $error")
              text <- GrammarMachine0.print(grammar, syntaxValue).left.map(error => s"${entry.path}: $error")
            yield text.getBytes(UTF_8)
        case "canon-text-write-v1" =>
          if language.reader != "canon" then Left(s"${entry.path}: canon materializer mismatches reader ${language.reader}")
          else
            val surface = CanonText.write(unquoteReferences(syntaxValue))
            Right(surface.getBytes(UTF_8))
        case "negative-fixture-bytes-v1" =>
          if language.reader != "negative-fixture" then Left(s"${entry.path}: negative-fixture materializer mismatches reader ${language.reader}")
          else
            syntaxValue match
              case Canon.Node("negative-fixture", Vector(Canon.Y(bytes))) => Right(bytes.toArray)
              case _ => Left(s"${entry.path}: generated negative-fixture syntax is invalid")
        case other => Left(s"${entry.path}: unsupported materializer $other")
    yield bytes

  private def readMaterializerId(cas: DirectoryCas, entry: TreeEntry): Either[String, String] =
    entry.materializer match
      case Some(digest) =>
        cas.get(digest) match
          case Some(Artifact("materializer", Canon.Node("materializer", Vector(Canon.Sym(id))))) => Right(id)
          case _ => Left(s"invalid materializer ${digest.hex}")
      case None =>
        if entry.blob.nonEmpty then Right("blob-v1")
        else
          readLanguageInfo(cas, entry.language).map(_.reader).flatMap {
            case "grammar"          => Right("grammar0-native-print-v1")
            case "canon"            => Right("canon-text-write-v1")
            case "negative-fixture" => Right("negative-fixture-bytes-v1")
            case other                => Left(s"unsupported generated reader $other")
          }

  private def readLanguageInfo(cas: DirectoryCas, digest: Digest): Either[String, LanguageInfo] =
    cas.get(digest) match
      case Some(Artifact("language", Canon.Node(_, Vector(Canon.Sym(name), _, _, _, _, Canon.Sym(reader))))) =>
        Right(LanguageInfo(name, reader))
      case _ => Left(s"invalid language ${digest.hex}")

  private def unquoteReferences(value: Canon): Canon = value match
    case Canon.Node("syntax-reference", Vector(Canon.S(hex))) =>
      Digest.fromHex(hex).toOption.map(Canon.R.apply).getOrElse(value)
    case Canon.L(items)      => Canon.L(items.map(unquoteReferences))
    case Canon.M(entries)    => Canon.M(entries.map((key, item) => unquoteReferences(key) -> unquoteReferences(item)))
    case Canon.Node(tag, xs) => Canon.Node(tag, xs.map(unquoteReferences))
    case scalar              => scalar

  private def sourceFiles(source: Path, repository: Path): Vector[Path] =
    val stream = Files.walk(source)
    try
      stream.iterator().asScala
        .filter(Files.isRegularFile(_))
        .filterNot(path => path.startsWith(repository))
        .filterNot { path =>
          source.relativize(path).iterator().asScala.exists(part => ExcludedNames.contains(part.toString))
        }
        .toVector
        .sortBy(path => unix(source.relativize(path)))
    finally stream.close()

  private def unix(path: Path): String = path.iterator().asScala.map(_.toString).mkString("/")

  private def list(dir: Path): Vector[Path] =
    val stream = Files.list(dir)
    try stream.iterator().asScala.toVector finally stream.close()
