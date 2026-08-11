package stratum.repo

import stratum.artifact.{Artifact, Closure, DirectoryCas, MemoryCas}
import stratum.cap.Capabilities
import stratum.canon.{Canon, CanonText, Digest}
import stratum.journal.Journal
import stratum.cli.{Cli, CommandResult}
import stratum.grammar.GrammarMachine0
import stratum.meta.{Budget, Kernel, MetaMachine0, Program}

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

  private final case class PatchChange(
      path: String,
      before: Option[Digest],
      after: Option[Digest],
      afterEntry: Option[Digest] = None
  )

  private final case class RequestedChange(path: String, before: Option[Digest], remove: Boolean)

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
          case "create" => create(dir)
          case "init"   => init(dir)
          case "inspect" => inspect(dir)
          case "branch" => createBranch(dir, opts.getOrElse("name", ""), opts.getOrElse("from", "main"))
          case "branches" => branches(dir)
          case "profiles" => profiles()
          case "checkout" => checkout(dir, opts.getOrElse("branch", "main"), resolve(root, opts.getOrElse("out", "")))
          case "add-language" =>
            addLanguage(root, dir, resolve(root, opts.getOrElse("project", ".")), opts.getOrElse("name", ""))
          case "put-source" =>
            putSource(
              dir,
              resolve(root, opts.getOrElse("source", ".")),
              opts.get("declaration-root").map(resolve(root, _)).getOrElse(root),
              opts.getOrElse("path", ""),
              opts.getOrElse("text", "")
            )
          case "copy-source" =>
            copySource(
              dir,
              resolve(root, opts.getOrElse("source", ".")),
              opts.get("declaration-root").map(resolve(root, _)).getOrElse(root),
              opts.getOrElse("from", ""),
              opts.getOrElse("path", "")
            )
          case "remove-source" =>
            removeSource(dir, resolve(root, opts.getOrElse("source", ".")), opts.getOrElse("path", ""))
          case "check-source" =>
            checkSource(
              dir,
              resolve(root, opts.getOrElse("source", ".")),
              opts.get("declaration-root").map(resolve(root, _)).getOrElse(root),
              opts.getOrElse("path", "")
            )
          case "run-source" =>
            runSource(
              dir,
              resolve(root, opts.getOrElse("source", ".")),
              opts.get("declaration-root").map(resolve(root, _)).getOrElse(root),
              opts.getOrElse("path", "")
            )
          case "list-languages" => listLanguages(dir, resolve(root, opts.getOrElse("source", ".")))
          case "list-sources" => listSources(dir, resolve(root, opts.getOrElse("source", ".")))
          case "show-source" =>
            showSource(dir, resolve(root, opts.getOrElse("source", ".")), opts.getOrElse("path", ""))
          case "search-artifacts" => searchArtifacts(dir, opts.getOrElse("query", ""))
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
          case "record-change" =>
            val source = resolve(root, opts.getOrElse("source", "."))
            recordChange(
              dir,
              opts.getOrElse("branch", "main"),
              source,
              opts.get("declaration-root").map(resolve(root, _)).getOrElse(source),
              resolve(root, opts.getOrElse("change", "")),
              opts.getOrElse("message", "record graph change"),
              opts.get("materialization-profile")
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
          case "verify-head" => verifyHead(dir, opts.getOrElse("branch", "main"))
          case other    => CommandResult.fail(s"unknown repository command $other")

  private def resolve(root: Path, value: String): Path =
    root.resolve(value).toAbsolutePath.normalize()

  private def create(dir: Path): CommandResult =
    if Files.exists(dir) then CommandResult.fail(s"repository already exists: $dir")
    else init(dir)

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

  private def inspect(dir: Path): CommandResult =
    open(dir).fold(CommandResult.fail, _ => CommandResult.ok(s"repository $dir", s"format $Format"))

  private def addLanguage(root: Path, dir: Path, project: Path, name: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(_) if !name.matches("[A-Za-z][A-Za-z0-9_-]*") => CommandResult.fail(s"invalid language name: $name")
      case Right(_) =>
        val source = root.resolve("languages").resolve(name)
        val target = project.resolve("languages").resolve(name)
        if !Files.isDirectory(source) then CommandResult.fail(s"unknown built-in language: $name")
        else if Files.exists(target) then CommandResult.fail(s"language already exists in project: $name")
        else
          val stream = Files.list(source)
          try
            val files = stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector.sortBy(_.getFileName.toString)
            if files.isEmpty then CommandResult.fail(s"built-in language has no files: $name")
            else
              Journal.createDirectories(target)
              files.foreach(file => Journal.copy(file, target.resolve(file.getFileName)))
              CommandResult.okLines(Vector(s"added language $name") ++ files.map(file => s"languages/$name/${file.getFileName}"))
          finally stream.close()

  private def sourcePath(source: Path, relative: String): Either[String, Path] =
    val normalized = source.resolve(relative).normalize()
    if relative.isEmpty then Left("source path is required")
    else if normalized == source || !normalized.startsWith(source) then Left(s"source path escapes project: $relative")
    else if normalized.startsWith(source.resolve(".stratum")) then Left(s"source path enters repository storage: $relative")
    else Right(normalized)

  private def listLanguages(dir: Path, source: Path): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(_) =>
        val languages = sourceFiles(source.resolve("languages"), dir)
          .flatMap(path => Option(source.resolve("languages").relativize(path).getName(0)).map(_.toString))
          .distinct
          .sorted
        CommandResult.okLines(if languages.isEmpty then Vector("no languages") else languages.map(name => s"language $name"))

  private def listSources(dir: Path, source: Path): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(_) =>
        val paths = sourceFiles(source, dir).map(path => unix(source.relativize(path)))
        CommandResult.okLines(if paths.isEmpty then Vector("no sources") else paths.map(path => s"source $path"))

  private def showSource(dir: Path, source: Path, relative: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(_) =>
        sourcePath(source, relative) match
          case Left(error) => CommandResult.fail(error)
          case Right(path) if !Files.isRegularFile(path) => CommandResult.fail(s"source file does not exist: $relative")
          case Right(path) => CommandResult.okLines(Vector(s"source $relative", Files.readString(path, UTF_8)))

  private def putSource(
      dir: Path,
      source: Path,
      declarationRoot: Path,
      relative: String,
      text: String
  ): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) =>
        sourcePath(source, relative) match
          case Left(error) => CommandResult.fail(error)
          case Right(path) =>
            val declarations = LanguageDeclarations.load(declarationRoot) match
              case Left(error) => return CommandResult.fail(error)
              case Right(value) => value
            val language = LanguageDeclarations.select(relative, declarations) match
              case Left(error) => return CommandResult.fail(error)
              case Right(value) => value
            LanguageDeclarations.structure(declarationRoot, relative, text.getBytes(UTF_8), declarations, cas) match
              case Left(error) => CommandResult.fail(s"$relative: $error")
              case Right(structured) =>
                val operation = if Files.exists(path) then "modified" else "added"
                Option(path.getParent).foreach(Journal.createDirectories(_))
                Journal.write(path, text.getBytes(UTF_8))
                CommandResult.ok(
                  s"$operation $relative",
                  s"language ${language.name}",
                  s"content ${structured.content.hex}"
                )

  private def removeSource(dir: Path, source: Path, relative: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(_) =>
        sourcePath(source, relative) match
          case Left(error) => CommandResult.fail(error)
          case Right(path) if !Files.isRegularFile(path) => CommandResult.fail(s"source file does not exist: $relative")
          case Right(path) =>
            Journal.delete(path)
            CommandResult.ok(s"removed $relative")

  private def copySource(
      dir: Path,
      source: Path,
      declarationRoot: Path,
      from: String,
      to: String
  ): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(_) =>
        sourcePath(source, from) match
          case Left(error) => CommandResult.fail(error)
          case Right(path) if !Files.isRegularFile(path) => CommandResult.fail(s"source file does not exist: $from")
          case Right(path) if from == to => CommandResult.fail("source copy paths must differ")
          case Right(path) =>
            val copied = putSource(dir, source, declarationRoot, to, Files.readString(path, UTF_8))
            if copied.code != 0 then copied
            else CommandResult.okLines(Vector(s"copied $from $to") ++ copied.lines.drop(1))

  private def checkSource(dir: Path, source: Path, declarationRoot: Path, relative: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) =>
        sourcePath(source, relative) match
          case Left(error) => CommandResult.fail(error)
          case Right(path) if !Files.isRegularFile(path) => CommandResult.fail(s"source file does not exist: $relative")
          case Right(path) =>
            val declarations = LanguageDeclarations.load(declarationRoot) match
              case Left(error) => return CommandResult.fail(error)
              case Right(value) => value
            val language = LanguageDeclarations.select(relative, declarations) match
              case Left(error) => return CommandResult.fail(error)
              case Right(value) => value
            LanguageDeclarations.structure(declarationRoot, relative, Files.readAllBytes(path), declarations, cas) match
              case Left(error) => CommandResult.fail(s"$relative: $error")
              case Right(structured) => CommandResult.ok(
                s"valid $relative",
                s"language ${language.name}",
                s"content ${structured.content.hex}"
              )

  private def runSource(dir: Path, source: Path, declarationRoot: Path, relative: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(_) =>
        sourcePath(source, relative) match
          case Left(error) => CommandResult.fail(error)
          case Right(path) if !Files.isRegularFile(path) => CommandResult.fail(s"source file does not exist: $relative")
          case Right(path) =>
            val declarations = LanguageDeclarations.load(declarationRoot) match
              case Left(error) => return CommandResult.fail(error)
              case Right(value) => value
            val language = LanguageDeclarations.select(relative, declarations) match
              case Left(error) => return CommandResult.fail(error)
              case Right(value) => value
            (language.grammarPath, language.metaPath) match
              case (None, _) => CommandResult.fail(s"${language.name} has no grammar")
              case (_, None) => CommandResult.fail(s"${language.name} has no Meta program")
              case (Some(grammarPath), Some(metaPath)) =>
                val preludePath = declarationRoot.resolve("languages/meta/prelude.meta")
                val grammarFile = declarationRoot.resolve(grammarPath)
                val metaFile = declarationRoot.resolve(metaPath)
                if !Files.isRegularFile(preludePath) then CommandResult.fail("missing generic Meta prelude")
                else if !Files.isRegularFile(grammarFile) then CommandResult.fail(s"missing grammar $grammarPath")
                else if !Files.isRegularFile(metaFile) then CommandResult.fail(s"missing Meta program $metaPath")
                else
                  val loaded = for
                    prelude <- CanonText.read(Files.readString(preludePath)).left.map(error => s"languages/meta/prelude.meta: $error")
                    grammar <- CanonText.read(Files.readString(grammarFile)).left.map(error => s"$grammarPath: $error")
                    meta <- CanonText.read(Files.readString(metaFile)).left.map(error => s"$metaPath: $error")
                  yield (prelude, grammar, meta)
                  loaded match
                    case Left(error) => CommandResult.fail(error)
                    case Right((prelude, grammar, meta)) =>
                      val cas = MemoryCas()
                      val preludeRef = cas.put(Artifact("meta-program", prelude))
                      val metaRef = cas.put(Artifact("meta-program", meta))
                      val grammarRef = cas.put(Artifact("grammar", grammar))
                      val rootProgram = Canon.node(
                        "program",
                        Canon.node("use", Canon.R(preludeRef)),
                        Canon.node("use", Canon.R(metaRef))
                      )
                      Program.load(rootProgram, cas) match
                        case Left(error) => CommandResult.fail(error)
                        case Right(program) =>
                          Vector("EvaluateSource", "NormalizeToText", "RunSource")
                            .find(name => program.judgments.get(name).exists(_.params.length == 2)) match
                            case None => CommandResult.fail(s"language ${language.name} declares no source evaluator")
                            case Some(evaluator) =>
                              val goal = Canon.node(
                                "call",
                                Canon.Sym(evaluator),
                                Canon.node("q", Canon.R(grammarRef)),
                                Canon.node("q", Canon.S(Files.readString(path)))
                              )
                              val verdict = MetaMachine0.derive(
                                program,
                                cas,
                                Kernel(Set("grammar-parse", "grammar-print")),
                                Budget(200000000L, 20000),
                                goal,
                                Capabilities.standard(cas, declarationRoot, s"run:$relative")
                              )
                              MetaMachine0.result(verdict) match
                                case Some(Canon.S(result)) => CommandResult.ok(s"result $result", s"language ${language.name}", s"evaluator $evaluator")
                                case Some(result) => CommandResult.ok(s"result ${CanonText.write(result)}", s"language ${language.name}", s"evaluator $evaluator")
                                case None =>
                                  MetaMachine0.failure(verdict) match
                                    case Some((kind, message)) => CommandResult.fail(s"$kind: $message")
                                    case None => CommandResult.fail(CanonText.write(verdict))

  private def searchArtifacts(dir: Path, query: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) if query.trim.isEmpty => CommandResult.fail("artifact search query is required")
      case Right(cas) =>
        val wanted = query.toLowerCase
        val matches = cas.digests.flatMap { digest =>
          cas.get(digest).flatMap { artifact =>
            val body = CanonText.write(artifact.body)
            val searchable = s"${digest.hex} ${artifact.kind} $body".toLowerCase
            Option.when(searchable.contains(wanted))(s"${digest.hex} ${artifact.kind} $body")
          }
        }.sortBy(identity).take(50)
        if matches.isEmpty then CommandResult.ok(s"no artifacts matching $query")
        else CommandResult.okLines(matches)

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

  private def recordChange(
      dir: Path,
      branch: String,
      source: Path,
      declarationRoot: Path,
      changeFile: Path,
      message: String,
      requestedProfile: Option[String]
  ): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) =>
        val previous = readRef(dir, branch) match
          case None => return CommandResult.fail(s"branch $branch has no parent; import genesis with record")
          case Some(value) => value
        val previousTree = blockTree(cas, previous) match
          case Left(error) => return CommandResult.fail(error)
          case Right(value) => value
        val persistentTree = ensurePersistentTree(cas, previousTree) match
          case Left(error) => return CommandResult.fail(error)
          case Right(value) => value
        val changes = readRequestedChanges(changeFile) match
          case Left(error) => return CommandResult.fail(error)
          case Right(value) => value
        val declarations = LanguageDeclarations.load(declarationRoot) match
          case Left(error) => return CommandResult.fail(error)
          case Right(value) => value
        val changedEntries = mutable.Map.empty[String, Option[TreeEntry]]
        val patchChanges = Vector.newBuilder[Canon]

        var changeIndex = 0
        while changeIndex < changes.length do
          val change = changes(changeIndex)
          val current = persistentTreeEntry(cas, persistentTree, change.path) match
            case Left(error) => return CommandResult.fail(error)
            case Right(value) => value
          (change.before, current.map(_.content), change.remove) match
            case (None, None, false) => ()
            case (None, Some(_), false) => return CommandResult.fail(s"add expects missing path ${change.path}")
            case (Some(expected), Some(found), _) if expected != found =>
              return CommandResult.fail(s"change expected ${expected.hex} at ${change.path} but found ${found.hex}")
            case (Some(_), None, _) => return CommandResult.fail(s"change expects existing path ${change.path}")
            case (None, _, true) => return CommandResult.fail(s"remove requires the expected content digest at ${change.path}")
            case _ => ()

          if change.remove then
            val before = current match
              case None => return CommandResult.fail(s"remove expects existing path ${change.path}")
              case Some(value) => value
            changedEntries.update(change.path, None)
            patchChanges += Canon.node("remove", Canon.S(change.path), Canon.S(before.content.hex))
          else
            val path = source.resolve(change.path).normalize()
            if !path.startsWith(source) then return CommandResult.fail(s"unsafe changed path ${change.path}")
            if !Files.isRegularFile(path) then return CommandResult.fail(s"changed path is not a file: ${change.path}")
            val structured = LanguageDeclarations.structure(
              declarationRoot,
              change.path,
              Files.readAllBytes(path),
              declarations,
              cas
            ) match
              case Left(error) => return CommandResult.fail(s"${change.path}: $error")
              case Right(value) => value
            current match
              case None =>
                val entry = treeEntry(change.path, structured)
                val entryDigest = cas.put(Artifact("tree-entry", treeEntryCanon(entry)))
                changedEntries.update(change.path, Some(entry))
                patchChanges += Canon.node("add", Canon.S(change.path), Canon.S(structured.content.hex), Canon.R(entryDigest))
              case Some(before) if before.content == structured.content => ()
              case Some(before) =>
                val entry = treeEntry(change.path, structured)
                val entryDigest = cas.put(Artifact("tree-entry", treeEntryCanon(entry)))
                changedEntries.update(change.path, Some(entry))
                patchChanges += Canon.node(
                  "replace",
                  Canon.S(change.path),
                  Canon.S(before.content.hex),
                  Canon.S(structured.content.hex),
                  Canon.R(entryDigest)
                )
          changeIndex += 1

        val emittedChanges = patchChanges.result()
        if emittedChanges.isEmpty then return CommandResult.fail("graph change does not alter the parent tree")
        val treeDigest = updatePersistentTree(cas, persistentTree, changedEntries.toMap) match
          case Left(error) => return CommandResult.fail(error)
          case Right(value) => value
        val selectedProfile = requestedProfile match
          case Some(name) => resolveMaterializationProfile(name)
          case None => readBlock(cas, previous).flatMap { case (_, _, _, digest) => readMaterializationProfile(cas, digest) }
        val profile = selectedProfile match
          case Left(error) => return CommandResult.fail(error)
          case Right(value) => value
        val profileDigest = cas.put(materializationProfileArtifact(profile))
        validateTreeEntryInvariants(cas, treeDigest, Some(profileDigest), changedEntries.collect {
          case (path, Some(entry)) => path -> entry
        }.toMap) match
          case Left(error) => return CommandResult.fail(error)
          case Right(_) => ()
        val patchDigest = cas.put(
          Artifact(
            "patch",
            Canon.node("patch", Canon.R(previous), Canon.R(treeDigest), Canon.S(message), Canon.L(emittedChanges))
          )
        )
        val height = blockHeight(cas, previous) match
          case Left(error) => return CommandResult.fail(error)
          case Right(value) => value + 1L
        val blockDigest = cas.put(
          Artifact(
            "block",
            Canon.node("block", Canon.N(BigInt(height)), Canon.R(previous), Canon.R(patchDigest), Canon.R(profileDigest))
          )
        )
        val target = refPath(dir, branch) match
          case Left(error) => return CommandResult.fail(error)
          case Right(value) => value
        Journal.writeString(target, blockDigest.hex + "\n")
        if branch == "main" then Journal.writeString(dir.resolve("HEAD"), "ref: refs/main\n")
        CommandResult.ok(
          s"branch $branch",
          s"changes ${emittedChanges.length}",
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

  private def verifyHead(dir: Path, branch: String): CommandResult =
    open(dir) match
      case Left(error) => CommandResult.fail(error)
      case Right(cas) =>
        val head = readRef(dir, branch) match
          case None => return CommandResult.fail(s"no such branch: $branch")
          case Some(value) => value
        readBlock(cas, head) match
          case Left(error) => CommandResult.fail(error)
          case Right((height, predecessor, patchDigest, profileDigest)) =>
            (readMaterializationProfile(cas, profileDigest), readPatch(cas, patchDigest)) match
              case (Left(error), _) => CommandResult.fail(error)
              case (_, Left(error)) => CommandResult.fail(error)
              case (Right(_), Right((patchPredecessor, treeDigest, changes))) =>
                if patchPredecessor != predecessor then
                  CommandResult.fail(s"block ${head.hex} predecessor does not match patch ${patchDigest.hex}")
                else
                  val persistent = predecessor.flatMap(parent => blockTree(cas, parent).toOption).flatMap { parentTree =>
                    ensurePersistentTree(cas, parentTree).toOption
                  }
                  if persistent.nonEmpty && changes.forall(change => change.after.isEmpty || change.afterEntry.nonEmpty) then
                    verifyPersistentTransition(cas, persistent.get, treeDigest, profileDigest, changes) match
                      case Left(error) => CommandResult.fail(s"invalid patch ${patchDigest.hex}: $error")
                      case Right(_) => CommandResult.ok(s"valid head $branch", s"height $height", s"head ${head.hex}")
                  else
                    val before = predecessor match
                      case None => Right(Map.empty[String, Digest])
                      case Some(parent) => blockTree(cas, parent).flatMap(treeEntries(cas, _)).map(_.view.mapValues(_.content).toMap)
                    val after = treeEntries(cas, treeDigest)
                    (before, after) match
                      case (Left(error), _) => CommandResult.fail(error)
                      case (_, Left(error)) => CommandResult.fail(error)
                      case (Right(beforeMap), Right(afterEntries)) =>
                        validateTreeEntryInvariants(cas, treeDigest, profileDigest, afterEntries) match
                          case Left(error) => CommandResult.fail(error)
                          case Right(_) =>
                            applyPatch(beforeMap, changes) match
                              case Left(error) => CommandResult.fail(s"invalid patch ${patchDigest.hex}: $error")
                              case Right(rebuilt) if rebuilt != afterEntries.view.mapValues(_.content).toMap =>
                                CommandResult.fail(s"patch ${patchDigest.hex} does not derive tree ${treeDigest.hex}")
                              case Right(_) => CommandResult.ok(s"valid head $branch", s"height $height", s"head ${head.hex}")

  private def verifyPersistentTransition(
      cas: DirectoryCas,
      beforeTree: Digest,
      afterTree: Digest,
      profileDigest: Option[Digest],
      changes: Vector[PatchChange]
  ): Either[String, Unit] =
    val updates = mutable.Map.empty[String, Option[TreeEntry]]
    var index = 0
    while index < changes.length do
      val change = changes(index)
      val current = persistentTreeEntry(cas, beforeTree, change.path).flatMap { entry =>
        if entry.map(_.content) == change.before then Right(entry)
        else Left(s"change at ${change.path} expected ${change.before.map(_.hex).getOrElse("missing")}")
      }
      current match
        case Left(error) => return Left(error)
        case Right(_) => ()
      change.after match
        case None => updates.update(change.path, None)
        case Some(expectedContent) =>
          val entryDigest = change.afterEntry match
            case None => return Left(s"change at ${change.path} has no successor entry")
            case Some(value) => value
          val entry = readTreeEntryArtifact(cas, entryDigest) match
            case Left(error) => return Left(error)
            case Right(value) => value
          if entry.path != change.path || entry.content != expectedContent then
            return Left(s"successor entry ${entryDigest.hex} does not match ${change.path}")
          updates.update(change.path, Some(entry))
      index += 1
    validateTreeEntryInvariants(cas, afterTree, profileDigest, updates.collect {
      case (path, Some(entry)) => path -> entry
    }.toMap).flatMap { _ =>
      updatePersistentTree(cas, beforeTree, updates.toMap).flatMap { derived =>
        if derived == afterTree then Right(())
        else Left(s"changes derive tree ${derived.hex}, not ${afterTree.hex}")
      }
    }

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

  private def readRequestedChanges(path: Path): Either[String, Vector[RequestedChange]] =
    if !Files.isRegularFile(path) then Left(s"change manifest does not exist: $path")
    else
      CanonText.read(Files.readString(path)).left.map(error => s"$path: $error").flatMap {
        case Canon.Node("repository-change", operations) =>
          val parsed = operations.map {
            case Canon.Node("add", Vector(Canon.S(changedPath))) =>
              Right(RequestedChange(changedPath, None, remove = false))
            case Canon.Node("replace", Vector(Canon.S(changedPath), Canon.S(beforeHex))) =>
              Digest.fromHex(beforeHex)
                .map(digest => RequestedChange(changedPath, Some(digest), remove = false))
                .left.map(_ => s"invalid expected digest for $changedPath")
            case Canon.Node("remove", Vector(Canon.S(changedPath), Canon.S(beforeHex))) =>
              Digest.fromHex(beforeHex)
                .map(digest => RequestedChange(changedPath, Some(digest), remove = true))
                .left.map(_ => s"invalid expected digest for $changedPath")
            case other => Left(s"invalid repository change operation: ${CanonText.write(other)}")
          }
          parsed.collectFirst { case Left(error) => error } match
            case Some(error) => Left(error)
            case None =>
              val changes = parsed.collect { case Right(change) => change }
              val duplicate = changes.groupBy(_.path).collectFirst { case (changedPath, values) if values.lengthCompare(1) > 0 => changedPath }
              duplicate.toLeft(changes).left.map(changedPath => s"duplicate changed path $changedPath")
        case other => Left(s"not a repository-change manifest: ${CanonText.write(other)}")
      }

  private def ensurePersistentTree(cas: DirectoryCas, digest: Digest): Either[String, Digest] =
    cas.get(digest) match
      case Some(Artifact("tree", Canon.Node("tree-map", Vector(Canon.L(_))))) => Right(digest)
      case Some(Artifact("tree", Canon.Node("tree", Vector(Canon.L(_))))) =>
        treeEntries(cas, digest).map(entries => persistentTreeArtifact(cas, entries.values.toVector))
      case _ => Left(s"invalid tree ${digest.hex}")

  private def persistentTreeArtifact(cas: DirectoryCas, entries: Vector[TreeEntry]): Digest =
    val buckets = entries.groupBy(entry => treeBucket(entry.path)).toVector.sortBy(_._1).map { (key, values) =>
      val digest = cas.put(treeBucketArtifact(values))
      Canon.node("bucket", Canon.Sym(key), Canon.R(digest))
    }
    cas.put(Artifact("tree", Canon.node("tree-map", Canon.L(buckets))))

  private def updatePersistentTree(
      cas: DirectoryCas,
      root: Digest,
      changes: Map[String, Option[TreeEntry]]
  ): Either[String, Digest] =
    persistentTreeIndex(cas, root).flatMap { originalIndex =>
      val index = mutable.Map.from(originalIndex)
      val grouped = changes.toVector.groupBy((path, _) => treeBucket(path))
      grouped.toVector.sortBy(_._1).foldLeft[Either[String, Unit]](Right(())) { case (result, (key, bucketChanges)) =>
        result.flatMap { _ =>
          val existing = index.get(key) match
            case None => Right(Map.empty[String, TreeEntry])
            case Some(bucketDigest) => treeBucketEntries(cas, bucketDigest)
          existing.map { values =>
            val updated = mutable.Map.from(values)
            bucketChanges.sortBy(_._1).foreach {
              case (path, Some(entry)) => updated.update(path, entry)
              case (path, None) => updated.remove(path)
            }
            if updated.isEmpty then index.remove(key)
            else index.update(key, cas.put(treeBucketArtifact(updated.values.toVector)))
          }
        }
      }.map { _ =>
        val buckets = index.toVector.sortBy(_._1).map { (key, digest) =>
          Canon.node("bucket", Canon.Sym(key), Canon.R(digest))
        }
        cas.put(Artifact("tree", Canon.node("tree-map", Canon.L(buckets))))
      }
    }

  private def persistentTreeEntry(cas: DirectoryCas, root: Digest, path: String): Either[String, Option[TreeEntry]] =
    persistentTreeIndex(cas, root).flatMap { index =>
      index.get(treeBucket(path)) match
        case None => Right(None)
        case Some(bucket) => treeBucketEntries(cas, bucket).map(_.get(path))
    }

  private def persistentTreeIndex(cas: DirectoryCas, root: Digest): Either[String, Map[String, Digest]] =
    cas.get(root) match
      case Some(Artifact("tree", Canon.Node("tree-map", Vector(Canon.L(buckets))))) =>
        val parsed = buckets.map {
          case Canon.Node("bucket", Vector(Canon.Sym(key), Canon.R(digest))) if key.matches("[0-9a-f]{2}") => Right(key -> digest)
          case _ => Left(s"invalid bucket in tree ${root.hex}")
        }
        parsed.collectFirst { case Left(error) => error } match
          case Some(error) => Left(error)
          case None =>
            val values = parsed.collect { case Right(value) => value }
            if values.map(_._1).distinct.length != values.length then Left(s"duplicate bucket in tree ${root.hex}")
            else Right(values.toMap)
      case _ => Left(s"tree ${root.hex} is not persistent")

  private def treeBucket(path: String): String =
    Digest.of(path.getBytes(UTF_8)).hex.take(2)

  private def treeBucketArtifact(entries: Iterable[TreeEntry]): Artifact =
    Artifact("tree-bucket", Canon.node("tree-bucket", Canon.L(entries.toVector.sortBy(_.path).map(treeEntryCanon))))

  private def treeBucketEntries(cas: DirectoryCas, digest: Digest): Either[String, Map[String, TreeEntry]] =
    cas.get(digest) match
      case Some(Artifact("tree-bucket", Canon.Node("tree-bucket", Vector(Canon.L(entries))))) =>
        val parsed = entries.map(parseTreeEntry(digest, _))
        parsed.collectFirst { case Left(error) => error } match
          case Some(error) => Left(error)
          case None => Right(parsed.collect { case Right(entry) => entry }.toMap)
      case _ => Left(s"invalid tree bucket ${digest.hex}")

  private def treeEntry(path: String, file: StructuredFile): TreeEntry =
    TreeEntry(path, file.content, file.blob, Some(file.materializer), file.language, file.grammar, file.meta, file.syntax)

  private def treeEntryCanon(entry: TreeEntry): Canon =
    Canon.node(
      "entry",
      Canon.S(entry.path),
      Canon.S(entry.content.hex),
      entry.blob.map(Canon.R.apply).getOrElse(Canon.Sym("generated")),
      entry.materializer.map(Canon.R.apply).getOrElse(Canon.Sym("legacy")),
      Canon.R(entry.language),
      entry.grammar.map(Canon.R.apply).getOrElse(Canon.Sym("native")),
      entry.meta.map(Canon.R.apply).getOrElse(Canon.Sym("native")),
      Canon.R(entry.syntax)
    )

  private def readTreeEntryArtifact(cas: DirectoryCas, digest: Digest): Either[String, TreeEntry] =
    cas.get(digest) match
      case Some(Artifact("tree-entry", body)) => parseTreeEntry(digest, body).map(_._2)
      case _ => Left(s"invalid tree entry ${digest.hex}")

  private def parsePatchChange(patchDigest: Digest, value: Canon): Either[String, PatchChange] = value match
    case Canon.Node("add", Vector(Canon.S(path), Canon.S(afterHex), Canon.R(entry))) =>
      Digest.fromHex(afterHex)
        .map(after => PatchChange(path, None, Some(after), Some(entry)))
        .left.map(_ => s"invalid add digest in patch ${patchDigest.hex}")
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
    case Canon.Node("replace", Vector(Canon.S(path), Canon.S(beforeHex), Canon.S(afterHex), Canon.R(entry))) =>
      for
        before <- Digest.fromHex(beforeHex).left.map(_ => s"invalid replace old digest in patch ${patchDigest.hex}")
        after <- Digest.fromHex(afterHex).left.map(_ => s"invalid replace new digest in patch ${patchDigest.hex}")
      yield PatchChange(path, Some(before), Some(after), Some(entry))
    case _ => Left(s"invalid change in patch ${patchDigest.hex}")

  private def applyPatch(before: Map[String, Digest], changes: Vector[PatchChange]): Either[String, Map[String, Digest]] =
    val updated = mutable.Map.from(before)
    var index = 0
    while index < changes.length do
      val change = changes(index)
      change match
        case PatchChange(path, None, Some(after), _) =>
          if updated.contains(path) then return Left(s"add expects missing path $path")
          updated.update(path, after)
        case PatchChange(path, Some(expected), None, _) =>
          updated.get(path) match
            case Some(found) if found == expected => updated.remove(path)
            case Some(found) => return Left(s"remove expected ${expected.hex} at $path but found ${found.hex}")
            case None => return Left(s"remove expects existing path $path")
        case PatchChange(path, Some(expected), Some(after), _) =>
          updated.get(path) match
            case Some(found) if found == expected => updated.update(path, after)
            case Some(found) => return Left(s"replace expected ${expected.hex} at $path but found ${found.hex}")
            case None => return Left(s"replace expects existing path $path")
        case PatchChange(path, None, None, _) =>
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
      case Some(Artifact("tree", Canon.Node("tree-map", Vector(Canon.L(_))))) =>
        persistentTreeIndex(cas, digest).flatMap { index =>
          index.toVector.sortBy(_._1).foldLeft[Either[String, Map[String, TreeEntry]]](Right(Map.empty)) {
            case (result, (_, bucket)) =>
              for
                accumulated <- result
                entries <- treeBucketEntries(cas, bucket)
              yield accumulated ++ entries
          }
        }
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
      case Some(Artifact("language", Canon.Node(_, Vector(Canon.Sym(name), _, _, Canon.L(_), _, _, Canon.L(_), Canon.Sym(reader))))) =>
        Right(LanguageInfo(name, reader))
      case Some(Artifact("language", Canon.Node(_, Vector(Canon.Sym(name), _, _, Canon.L(_), _, _, Canon.Sym(reader))))) =>
        Right(LanguageInfo(name, reader))
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
    if !Files.isDirectory(source) then return Vector.empty
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
