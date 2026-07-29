package stratum.cli

import stratum.artifact.*
import stratum.canon.{Canon, CanonText, Digest}
import stratum.cap.{Capabilities, CapabilityHandler}
import stratum.grammar.GrammarMachine0
import stratum.meta.*

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

final case class CommandResult(code: Int, lines: Vector[String]):
  def output: String = lines.mkString("\n")

object CommandResult:
  def ok(lines: String*): CommandResult = CommandResult(0, lines.toVector)
  def okLines(lines: Vector[String]): CommandResult = CommandResult(0, lines)
  def fail(message: String): CommandResult = CommandResult(1, Vector(s"error: $message"))

/** A loaded foundation: its manifest, application, program and closure. */
final case class LoadedFoundation(
    dir: Path,
    foundation: Canon,
    foundationDigest: Digest,
    application: Canon,
    applicationDigest: Digest,
    program: Program,
    cas: Cas,
    kernel: Kernel,
    budget: Budget,
    grammars: Map[String, Canon],
    grammarRefs: Map[String, Digest],
    entries: Map[String, Canon],
    checks: Vector[Canon]
)

object Cli:

  private def field(c: Canon, tag: String): Option[Canon] = c match
    case Canon.Node(_, args) =>
      args.collectFirst { case Canon.Node(t, Vector(v)) if t == tag => v }
    case _ => None

  private def mapField(c: Canon, tag: String): Map[String, Canon] =
    field(c, tag) match
      case Some(Canon.M(entries)) =>
        entries.collect { case (Canon.Sym(k), v) => k -> v }.toMap
      case _ => Map.empty

  // ------------------------------------------------------------- loading

  def loadFoundation(root: Path, dir: Path): Either[String, LoadedFoundation] =
    val closureDir = dir.resolve("closure")
    if !Files.isDirectory(closureDir) then Left(s"no closure directory at $closureDir")
    else
      val cas = DirectoryCas(closureDir)
      val foundationFile = dir.resolve("foundation.canon")
      if !Files.exists(foundationFile) then Left(s"no foundation manifest at $foundationFile")
      else
        Artifact.decode(Files.readAllBytes(foundationFile)) match
          case Left(m) => Left(m)
          case Right(foundationArtifact) =>
            val foundation = foundationArtifact.body
            field(foundation, "application") match
              case Some(Canon.R(appRef)) =>
                cas.get(appRef) match
                  case None => Left(s"application artifact ${appRef.hex} missing from closure")
                  case Some(appArtifact) =>
                    val app = appArtifact.body
                    val kernel = field(app, "kernel").flatMap(Kernel.fromCanon(_).toOption).getOrElse(Kernel.readOnly)
                    val budget = field(app, "resources").flatMap(Budget.fromCanon(_).toOption).getOrElse(Budget.default)
                    field(app, "meta") match
                      case Some(Canon.R(metaRef)) =>
                        cas.get(metaRef) match
                          case None => Left(s"meta program artifact ${metaRef.hex} missing from closure")
                          case Some(metaArtifact) =>
                            Program.load(metaArtifact.body, cas).map { program =>
                              val grammars = mapField(app, "grammars").flatMap {
                                case (k, Canon.R(r)) => cas.get(r).map(a => k -> a.body)
                                case _               => None
                              }
                              val grammarRefs = mapField(app, "grammars").collect { case (k, Canon.R(r)) => k -> r }
                              val entries = mapField(app, "entries")
                              val checks = field(app, "checks") match
                                case Some(Canon.R(r)) =>
                                  cas.get(r).map(_.body).collect { case Canon.L(items) => items }.getOrElse(Vector.empty)
                                case Some(Canon.L(items)) => items
                                case _                    => Vector.empty
                              val parsedChecks = checks
                              LoadedFoundation(
                                dir,
                                foundation,
                                foundationArtifact.digest,
                                app,
                                appArtifact.digest,
                                program,
                                cas,
                                kernel,
                                budget,
                                grammars,
                                grammarRefs,
                                entries,
                                parsedChecks
                              )
                            }
                      case _ => Left("application manifest has no meta program reference")
              case _ => Left("foundation manifest has no application reference")

  private def capabilitiesFor(cas: Cas, root: Path, seed: String): CapabilityHandler =
    Capabilities.standard(cas, root, seed)

  private def summarize(verdict: Canon): String = verdict match
    case Canon.Node("verdict", Vector(Canon.Sym("ok"), value, _)) => CanonText.write(value)
    case Canon.Node("verdict", Vector(Canon.Sym("error"), Canon.Sym(kind), Canon.S(msg), _)) =>
      s"error $kind ${CanonText.write(Canon.S(msg))}"
    case other => CanonText.write(other)

  // ------------------------------------------------------------ dispatch

  def run(root: Path, argv: Vector[String]): CommandResult =
    argv.headOption match
      case None                => CommandResult.ok(usage*)
      case Some("help")        => CommandResult.ok(usage*)
      case Some("host")        => hostCommand(argv.drop(1))
      case Some("canon")       => canonCommand(root, argv.drop(1))
      case Some("grammar")     => grammarCommand(root, argv.drop(1))
      case Some("derive")      => deriveCommand(root, argv.drop(1))
      case Some("meta")        => metaCommand(root, argv.drop(1))
      case Some("foundation")  => FoundationCommands.run(root, argv.drop(1))
      case Some("transcript")  => Transcript.command(root, argv.drop(1))
      case Some(other)         => CommandResult.fail(s"unknown command $other")

  val usage: Vector[String] = Vector(
    "stratum host info",
    "stratum canon read <file>",
    "stratum canon digest <file>",
    "stratum grammar parse --foundation <dir> --grammar <name> --text <source>",
    "stratum grammar print --foundation <dir> --grammar <name> --value <canon>",
    "stratum derive --foundation <dir> --goal <expr> [--budget <steps>] [--evidence]",
    "stratum foundation build --spec <file> --out <dir>",
    "stratum foundation verify --dir <dir>",
    "stratum foundation verify-successor --predecessor <dir> --successor <dir>",
    "stratum foundation reconstruct --dir <dir>",
    "stratum transcript run <paths...> [--update]"
  )

  def options(args: Vector[String]): Map[String, String] =
    val m = mutable.LinkedHashMap.empty[String, String]
    var i = 0
    while i < args.length do
      val a = args(i)
      if a.startsWith("--") then
        val key = a.drop(2)
        if i + 1 < args.length && !args(i + 1).startsWith("--") then
          m.put(key, args(i + 1))
          i += 2
        else
          m.put(key, "true")
          i += 1
      else
        m.put(s"_$i", a)
        i += 1
    m.toMap

  def positional(args: Vector[String]): Vector[String] =
    val out = mutable.ArrayBuffer.empty[String]
    var i = 0
    while i < args.length do
      val a = args(i)
      if a.startsWith("--") then
        if i + 1 < args.length && !args(i + 1).startsWith("--") then i += 2 else i += 1
      else
        out += a
        i += 1
    out.toVector

  // ------------------------------------------------------------ commands

  private def hostCommand(args: Vector[String]): CommandResult =
    args.headOption match
      case Some("info") | None =>
        CommandResult.ok(
          "host StratumHost0",
          "canon sha-256",
          "machines meta0 grammar0",
          s"capabilities ${Kernel.full.allow.toVector.sorted.mkString(" ")}"
        )
      case Some(other) => CommandResult.fail(s"unknown host command $other")

  private def readCanonFile(root: Path, path: String): Either[String, Canon] =
    val p = root.resolve(path)
    if !Files.exists(p) then Left(s"no such file: $path")
    else if path.endsWith(".canon") && !isText(p) then
      Artifact.decode(Files.readAllBytes(p)).map(_.toCanon)
    else CanonText.read(Files.readString(p))

  private def isText(p: Path): Boolean =
    val bytes = Files.readAllBytes(p)
    bytes.nonEmpty && bytes.take(1).head == '('.toByte && bytes.exists(_ == ' '.toByte) &&
      bytes.forall(b => b >= 9)

  private def canonCommand(root: Path, args: Vector[String]): CommandResult =
    val pos = positional(args)
    (pos.headOption, pos.drop(1).headOption) match
      case (Some("read"), Some(file)) =>
        readCanonFile(root, file) match
          case Left(m)  => CommandResult.fail(m)
          case Right(c) => CommandResult.ok(CanonText.write(c))
      case (Some("digest"), Some(file)) =>
        readCanonFile(root, file) match
          case Left(m)  => CommandResult.fail(m)
          case Right(c) => CommandResult.ok(Canon.digest(c).toString)
      case _ => CommandResult.fail("usage: canon read|digest <file>")

  private def grammarCommand(root: Path, args: Vector[String]): CommandResult =
    val opts = options(args)
    val sub = positional(args).headOption.getOrElse("")
    val foundationDir = opts.get("foundation").map(root.resolve)
    val grammarName = opts.getOrElse("grammar", "")

    foundationDir match
      case None => CommandResult.fail("grammar commands require --foundation <dir>")
      case Some(dir) =>
        loadFoundation(root, dir) match
          case Left(m) => CommandResult.fail(m)
          case Right(f) =>
            f.grammars.get(grammarName) match
              case None => CommandResult.fail(s"unknown grammar $grammarName")
              case Some(gc) =>
                GrammarMachine0.load(gc) match
                  case Left(m) => CommandResult.fail(m)
                  case Right(g) =>
                    sub match
                      case "parse" =>
                        GrammarMachine0.parse(g, opts.getOrElse("text", "")) match
                          case Left(m)  => CommandResult.fail(m)
                          case Right(v) => CommandResult.ok(CanonText.write(v))
                      case "print" =>
                        CanonText.read(opts.getOrElse("value", "")) match
                          case Left(m) => CommandResult.fail(m)
                          case Right(v) =>
                            GrammarMachine0.print(g, v) match
                              case Left(m)  => CommandResult.fail(m)
                              case Right(s) => CommandResult.ok(s)
                      case "roundtrip" =>
                        GrammarMachine0.parse(g, opts.getOrElse("text", "")) match
                          case Left(m) => CommandResult.fail(m)
                          case Right(v) =>
                            GrammarMachine0.print(g, v).flatMap(s => GrammarMachine0.parse(g, s).map(v2 => (s, v2))) match
                              case Left(m) => CommandResult.fail(m)
                              case Right((s, v2)) =>
                                if v == v2 then CommandResult.ok(s, "roundtrip ok")
                                else CommandResult(1, Vector(s, "roundtrip mismatch", CanonText.write(v2)))
                      case other => CommandResult.fail(s"unknown grammar command $other")

  def deriveIn(f: LoadedFoundation, root: Path, goal: Canon, budget: Budget): Canon =
    val caps = capabilitiesFor(f.cas, root, f.foundationDigest.hex)
    MetaMachine0.derive(f.program, f.cas, f.kernel, budget, resolveNames(f, root, goal), caps)

  /**
   * Resolves closure references by name inside a goal:
   * `(grammar <name>)`, `(file <path>)` and `(source <path>)` become quoted digests.
   */
  def resolveNames(f: LoadedFoundation, root: Path, goal: Canon): Canon = goal match
    case Canon.Node("grammar", Vector(Canon.Sym(name))) =>
      f.grammarRefs.get(name).map(d => Canon.node("q", Canon.R(d))).getOrElse(goal)
    case Canon.Node("file", Vector(Canon.S(path))) =>
      val p = root.resolve(path)
      if !Files.exists(p) then goal
      else
        val bytes = Files.readAllBytes(p)
        Artifact.decode(bytes) match
          case Right(a) => Canon.node("q", Canon.R(a.digest))
          case Left(_) =>
            CanonText.read(String(bytes, "UTF-8")) match
              case Left(_) => goal
              case Right(value) =>
                Canon.node("q", Canon.R(Artifact(FoundationCommands.kindOf(value), value).digest))
    case Canon.Node("source", Vector(Canon.S(path))) =>
      val p = root.resolve(path)
      if !Files.exists(p) then goal
      else
        val body = Canon.node(
          "source",
          Canon.node("path", Canon.S(path)),
          Canon.node("text", Canon.S(Files.readString(p)))
        )
        Canon.node("q", Canon.R(Artifact("source", body).digest))
    case Canon.Node(tag, args) => Canon.Node(tag, args.map(resolveNames(f, root, _)))
    case Canon.L(items)        => Canon.L(items.map(resolveNames(f, root, _)))
    case other                 => other

  private def deriveCommand(root: Path, args: Vector[String]): CommandResult =
    val opts = options(args)
    opts.get("foundation") match
      case None => CommandResult.fail("derive requires --foundation <dir>")
      case Some(dirName) =>
        loadFoundation(root, root.resolve(dirName)) match
          case Left(m) => CommandResult.fail(m)
          case Right(f) =>
            val goalText = opts.get("goal").orElse(opts.get("entry").map(e => s"(v $e)")).getOrElse("")
            val goalCanon =
              opts.get("entry") match
                case Some(name) =>
                  f.entries.get(name).toRight(s"unknown entry $name")
                case None => CanonText.read(goalText)
            goalCanon match
              case Left(m) => CommandResult.fail(m)
              case Right(goal) =>
                val budget = opts.get("budget").map(s => Budget(s.toLong, f.budget.depth)).getOrElse(f.budget)
                val verdict = deriveIn(f, root, goal, budget)
                if opts.contains("evidence") then CommandResult.ok(CanonText.write(verdict))
                else
                  val line = summarize(verdict)
                  CommandResult(if MetaMachine0.isOk(verdict) then 0 else 1, Vector(line))

  /**
   * Elaborates a surface source through a judgment of an ad-hoc program.
   *
   * This is the generic "apply the change using the predecessor" step: it needs
   * only a grammar artifact, some program artifacts and a source file, so a
   * foundation can be constructed from surface sources without circularity.
   */
  private def metaCommand(root: Path, args: Vector[String]): CommandResult =
    val opts = options(args)
    val sub = positional(args).headOption.getOrElse("")
    if sub != "elaborate" then CommandResult.fail(s"unknown meta command $sub")
    else
      val cas = MemoryCas()
      val programFiles = args.zipWithIndex.collect { case ("--program", i) if i + 1 < args.length => args(i + 1) }
      val grammarFile = opts.getOrElse("grammar", "")
      val sourceFile = opts.getOrElse("source", "")
      val judgment = opts.getOrElse("judgment", "ElaborateSource")

      def readText(p: String): Either[String, String] =
        val f = root.resolve(p)
        if Files.exists(f) then Right(Files.readString(f)) else Left(s"no such file: $p")

      val loaded = for
        grammarText <- readText(grammarFile)
        grammarCanon <- CanonText.read(grammarText)
        sourceText <- readText(sourceFile)
        programs <- programFiles.foldLeft[Either[String, Vector[Canon]]](Right(Vector.empty)) { (acc, p) =>
          acc.flatMap(vs => readText(p).flatMap(CanonText.read).map(vs :+ _))
        }
      yield (grammarCanon, sourceText, programs)

      loaded match
        case Left(m) => CommandResult.fail(m)
        case Right((grammarCanon, sourceText, programs)) =>
          val grammarRef = cas.put(Artifact("grammar", grammarCanon))
          val refs = programs.map(p => cas.put(Artifact("meta-program", p)))
          val rootProgram = Canon.Node("program", refs.map(r => Canon.node("use", Canon.R(r))))
          Program.load(rootProgram, cas) match
            case Left(m) => CommandResult.fail(m)
            case Right(program) =>
              val goal = Canon.node(
                "call",
                Canon.Sym(judgment),
                Canon.node("q", Canon.R(grammarRef)),
                Canon.node("q", Canon.S(sourceText))
              )
              val caps = capabilitiesFor(cas, root, "elaborate")
              val verdict = MetaMachine0.derive(program, cas, Kernel.full, Budget(20000000L, 20000), goal, caps)
              MetaMachine0.result(verdict) match
                case None => CommandResult(1, Vector(summarize(verdict)))
                case Some(value) =>
                  opts.get("out") match
                    case None => CommandResult.ok(CanonText.pretty(value))
                    case Some(outPath) =>
                      val out = root.resolve(outPath)
                      Option(out.getParent).foreach(Files.createDirectories(_))
                      val header = s"; Generated from $sourceFile by $judgment. Do not edit by hand.\n"
                      Files.writeString(out, header + CanonText.pretty(value) + "\n")
                      CommandResult.ok(
                        s"elaborated $sourceFile",
                        s"judgment $judgment",
                        s"digest ${Canon.digest(value).hex}",
                        s"wrote $outPath"
                      )
