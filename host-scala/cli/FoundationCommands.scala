package stratum.cli

import stratum.artifact.*
import stratum.canon.{Canon, CanonText, Digest}
import stratum.meta.*

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Foundation construction and verification.
 *
 * The host builds a foundation from a declarative spec, verifies a foundation
 * from `digest + closure`, and lets a predecessor foundation verify a successor
 * through the predecessor's own Meta program.
 */
object FoundationCommands:

  def run(root: Path, args: Vector[String]): CommandResult =
    Cli.positional(args).headOption match
      case Some("build")             => build(root, Cli.options(args))
      case Some("verify")            => verify(root, Cli.options(args))
      case Some("verify-successor")  => verifySuccessor(root, Cli.options(args))
      case Some("reconstruct")       => reconstruct(root, Cli.options(args))
      case Some("attest")            => attest(root, Cli.options(args))
      case Some("closure")           => closure(root, Cli.options(args))
      case other                     => CommandResult.fail(s"unknown foundation command ${other.getOrElse("")}")

  def kindOf(c: Canon): String = c match
    case Canon.Node("program", _)     => "meta-program"
    case Canon.Node("grammar", _)     => "grammar"
    case Canon.Node("application", _) => "application"
    case Canon.Node("foundation", _)  => "foundation"
    case Canon.Node("schema", _)      => "schema"
    case Canon.Node("profile", _)     => "profile"
    case Canon.Node("change", _)      => "change"
    case Canon.Node("source", _)      => "source"
    case _                            => "data"

  // ------------------------------------------------------------- building

  private def resolveTemplate(root: Path, cas: Cas, c: Canon): Either[String, Canon] = c match
    case Canon.Node("file", Vector(Canon.S(path))) =>
      readCanonSource(root, path).map { value =>
        Canon.R(cas.put(Artifact(kindOf(value), value)))
      }
    case Canon.Node("include", Vector(Canon.S(path))) =>
      readCanonSource(root, path).flatMap(resolveTemplate(root, cas, _))
    case Canon.Node("text-file", Vector(Canon.S(path))) =>
      val p = root.resolve(path)
      if !Files.exists(p) then Left(s"no such file: $path")
      else
        val body = Canon.node("source", Canon.node("path", Canon.S(path)), Canon.node("text", Canon.S(Files.readString(p))))
        Right(Canon.R(cas.put(Artifact("source", body))))
    case Canon.Node("program-of", Vector(Canon.L(items))) =>
      val resolved = items.map(resolveTemplate(root, cas, _))
      resolved.collectFirst { case Left(m) => m } match
        case Some(m) => Left(m)
        case None =>
          val uses = resolved.collect { case Right(r) => Canon.node("use", r) }
          val program = Canon.Node("program", uses)
          Right(Canon.R(cas.put(Artifact("meta-program", program))))
    case Canon.Node("foundation-of", Vector(Canon.S(dir))) =>
      foundationDigest(root, root.resolve(dir)).map(Canon.R.apply)
    case Canon.Node(tag, args) =>
      val resolved = args.map(resolveTemplate(root, cas, _))
      resolved.collectFirst { case Left(m) => m } match
        case Some(m) => Left(m)
        case None    => Right(Canon.Node(tag, resolved.collect { case Right(v) => v }))
    case Canon.L(items) =>
      val resolved = items.map(resolveTemplate(root, cas, _))
      resolved.collectFirst { case Left(m) => m } match
        case Some(m) => Left(m)
        case None    => Right(Canon.L(resolved.collect { case Right(v) => v }))
    case Canon.M(entries) =>
      val resolved = entries.map { (k, v) => resolveTemplate(root, cas, v).map(rv => k -> rv) }
      resolved.collectFirst { case Left(m) => m } match
        case Some(m) => Left(m)
        case None    => Right(Canon.M(resolved.collect { case Right(kv) => kv }))
    case other => Right(other)

  private def readCanonSource(root: Path, path: String): Either[String, Canon] =
    val p = root.resolve(path)
    if !Files.exists(p) then Left(s"no such file: $path")
    else CanonText.read(Files.readString(p))

  private def foundationDigest(root: Path, dir: Path): Either[String, Digest] =
    val f = dir.resolve("foundation.canon")
    if !Files.exists(f) then Left(s"no foundation at $dir")
    else Artifact.decode(Files.readAllBytes(f)).map(_.digest)

  private def field(c: Canon, tag: String): Option[Canon] = c match
    case Canon.Node(_, args) => args.collectFirst { case Canon.Node(t, Vector(v)) if t == tag => v }
    case _                   => None

  def build(root: Path, opts: Map[String, String]): CommandResult =
    (opts.get("spec"), opts.get("out")) match
      case (Some(specPath), Some(outPath)) =>
        readCanonSource(root, specPath) match
          case Left(m) => CommandResult.fail(m)
          case Right(spec) =>
            val outDir = root.resolve(outPath)
            val closureDir = outDir.resolve("closure")
            if Files.exists(closureDir) then deleteRecursively(closureDir)
            Files.createDirectories(closureDir)
            val cas = DirectoryCas(closureDir)

            val name = field(spec, "name").collect { case Canon.S(s) => s }.getOrElse("unnamed")
            val bootstrap = field(spec, "bootstrap").collect { case Canon.S(s) => s }.getOrElse("StratumHost0/1")

            // Inherit the predecessor closure so the successor is self-contained.
            val predecessor: Either[String, Canon] = field(spec, "predecessor") match
              case Some(Canon.Node("none", _)) | None => Right(Canon.Node("none", Vector.empty))
              case Some(Canon.Node("dir", Vector(Canon.S(dir)))) =>
                val predDir = root.resolve(dir)
                foundationDigest(root, predDir).flatMap { d =>
                  val predCas = DirectoryCas(predDir.resolve("closure"))
                  predCas.digests.foreach(pd => predCas.get(pd).foreach(cas.put))
                  Files.copy(
                    predDir.resolve("foundation.canon"),
                    closureDir.resolve(s"${d.hex}.canon"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                  )
                  Right(Canon.Node("some", Vector(Canon.R(d))))
                }
              case Some(other) => Left(s"unknown predecessor form: ${CanonText.write(other)}")

            predecessor.flatMap { pred =>
              field(spec, "change") match
                case Some(Canon.Node("none", _)) | None =>
                  Right((pred, Canon.Node("none", Vector.empty)))
                case Some(other) => resolveTemplate(root, cas, other).map(c => (pred, c))
            } match
              case Left(m) => CommandResult.fail(m)
              case Right((pred, change)) =>
                field(spec, "application") match
                  case None => CommandResult.fail("build spec has no application")
                  case Some(appTemplate) =>
                    resolveTemplate(root, cas, appTemplate) match
                      case Left(m) => CommandResult.fail(m)
                      case Right(app) =>
                        val appArtifact = Artifact("application", app)
                        val appDigest = cas.put(appArtifact)
                        val foundation = Canon.node(
                          "foundation",
                          Canon.node("name", Canon.S(name)),
                          Canon.node("bootstrap", Canon.S(bootstrap)),
                          Canon.node("application", Canon.R(appDigest)),
                          Canon.node("predecessor", pred),
                          Canon.node("change", change)
                        )
                        val foundationArtifact = Artifact("foundation", foundation)
                        val foundationDigest = cas.put(foundationArtifact)

                        Files.write(outDir.resolve("foundation.canon"), foundationArtifact.bytes)
                        Files.write(outDir.resolve("application.canon"), appArtifact.bytes)
                        Files.writeString(outDir.resolve("digest.txt"), foundationDigest.hex + "\n")

                        Cli.loadFoundation(root, outDir) match
                          case Left(m) => CommandResult.fail(m)
                          case Right(f) =>
                            val results = runChecks(root, f)
                            val verdict =
                              if results.forall(_._2 == "ok") then Canon.node("verdict", Canon.Sym("ok"))
                              else Canon.node("verdict", Canon.Sym("failed"))
                            Files.write(
                              outDir.resolve("verdict.canon"),
                              Artifact("verdict", verdict).bytes
                            )
                            Files.write(
                              outDir.resolve("evidence.canon"),
                              Artifact(
                                "evidence",
                                Canon.Node(
                                  "evidence",
                                  results.map((n, r) => Canon.node("check", Canon.Sym(n), Canon.Sym(r)))
                                )
                              ).bytes
                            )
                            val failed = results.filter(_._2 != "ok")
                            val lines = Vector(
                              s"built $name",
                              s"foundation ${foundationDigest.hex}",
                              s"application ${appDigest.hex}",
                              s"closure ${cas.digests.length} artifacts",
                              s"checks ${results.length} ${if failed.isEmpty then "ok" else "failed"}"
                            ) ++ failed.map((n, r) => s"check $n $r")
                            CommandResult(if failed.isEmpty then 0 else 1, lines)
      case _ => CommandResult.fail("usage: foundation build --spec <file> --out <dir>")

  private def deleteRecursively(p: Path): Unit =
    if Files.exists(p) then
      val stream = Files.walk(p)
      try
        stream.sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.delete)
      finally stream.close()

  // ------------------------------------------------------------ verifying

  /** Runs the application's declared checks. Returns `(name, "ok" | reason)`. */
  def runChecks(root: Path, f: LoadedFoundation): Vector[(String, String)] =
    f.checks.map { check =>
      check match
        case Canon.Node("check", Canon.Sym(name) +: goal +: expected +: rest) =>
          val budget = rest.headOption.flatMap(Budget.fromCanon(_).toOption).getOrElse(f.budget)
          val verdict = Cli.deriveIn(f, root, goal, budget)
          val outcome = (expected, verdict) match
            case (Canon.Node("value", Vector(exp)), Canon.Node("verdict", Vector(Canon.Sym("ok"), got, _))) =>
              if exp == got then "ok" else s"expected ${CanonText.write(exp)} got ${CanonText.write(got)}"
            case (Canon.Node("error", Vector(Canon.Sym(kind))), Canon.Node("verdict", Vector(Canon.Sym("error"), Canon.Sym(k), _, _))) =>
              if kind == k then "ok" else s"expected error $kind got error $k"
            case (Canon.Node("value", Vector(exp)), Canon.Node("verdict", Vector(Canon.Sym("error"), Canon.Sym(k), Canon.S(m), _))) =>
              s"expected ${CanonText.write(exp)} got error $k $m"
            case (Canon.Node("error", Vector(Canon.Sym(kind))), Canon.Node("verdict", Vector(Canon.Sym("ok"), got, _))) =>
              s"expected error $kind got ${CanonText.write(got)}"
            case _ => "malformed check"
          (name, outcome)
        case other => (CanonText.write(other), "malformed check")
    }

  private def verify(root: Path, opts: Map[String, String]): CommandResult =
    opts.get("dir") match
      case None => CommandResult.fail("usage: foundation verify --dir <dir>")
      case Some(dir) =>
        val d = root.resolve(dir)
        Cli.loadFoundation(root, d) match
          case Left(m) => CommandResult.fail(m)
          case Right(f) =>
            Closure.traverse(f.cas, f.foundationDigest) match
              case Left(missing) => CommandResult.fail(s"closure incomplete, missing ${missing.hex}")
              case Right(digests) =>
                val name = field(f.foundation, "name").collect { case Canon.S(s) => s }.getOrElse("unnamed")
                val results = runChecks(root, f)
                val failed = results.filter(_._2 != "ok")
                val lines = Vector(
                  s"foundation $name",
                  s"digest ${f.foundationDigest.hex}",
                  s"closure ${digests.length} artifacts"
                ) ++ results.map((n, r) => s"check $n $r") :+
                  (if failed.isEmpty then "verdict ok" else "verdict failed")
                CommandResult(if failed.isEmpty then 0 else 1, lines)

  private def reconstruct(root: Path, opts: Map[String, String]): CommandResult =
    opts.get("dir") match
      case None => CommandResult.fail("usage: foundation reconstruct --dir <dir>")
      case Some(dir) =>
        val d = root.resolve(dir)
        val digestFile = d.resolve("digest.txt")
        if !Files.exists(digestFile) then CommandResult.fail(s"no digest.txt in $dir")
        else
          Digest.fromHex(Files.readString(digestFile).trim) match
            case Left(m) => CommandResult.fail(m)
            case Right(expected) =>
              val cas = DirectoryCas(d.resolve("closure"))
              cas.get(expected) match
                case None => CommandResult.fail(s"foundation ${expected.hex} not present in closure")
                case Some(a) =>
                  Closure.traverse(cas, expected) match
                    case Left(missing) => CommandResult.fail(s"closure incomplete, missing ${missing.hex}")
                    case Right(digests) =>
                      Cli.loadFoundation(root, d) match
                        case Left(m) => CommandResult.fail(m)
                        case Right(f) =>
                          val name = field(f.foundation, "name").collect { case Canon.S(s) => s }.getOrElse("unnamed")
                          val results = runChecks(root, f)
                          val failed = results.filter(_._2 != "ok")
                          val stable = a.digest == expected && f.foundationDigest == expected
                          val lines = Vector(
                            s"reconstructed $name",
                            s"digest ${expected.hex}",
                            s"stable ${if stable then "yes" else "no"}",
                            s"closure ${digests.length} artifacts",
                            s"checks ${results.length} ${if failed.isEmpty then "ok" else "failed"}"
                          ) ++ failed.map((n, r) => s"check $n $r")
                          CommandResult(if failed.isEmpty && stable then 0 else 1, lines)

  /**
   * A canonical attestation computed from `digest + closure` alone.
   *
   * Every independent implementation of the bootstrap interface must produce
   * these exact bytes for the same foundation.
   */
  private def attest(root: Path, opts: Map[String, String]): CommandResult =
    opts.get("dir") match
      case None => CommandResult.fail("usage: foundation attest --dir <dir>")
      case Some(dir) =>
        val d = root.resolve(dir)
        val digestFile = d.resolve("digest.txt")
        if !Files.exists(digestFile) then CommandResult.fail(s"no digest.txt in $dir")
        else
          Digest.fromHex(Files.readString(digestFile).trim) match
            case Left(m) => CommandResult.fail(m)
            case Right(rootDigest) =>
              val cas = DirectoryCas(d.resolve("closure"))
              Closure.traverse(cas, rootDigest) match
                case Left(missing) => CommandResult.fail(s"missing artifact ${missing.hex}")
                case Right(digests) =>
                  val kinds = digests.flatMap(cas.get).map(_.kind).groupBy(identity).view.mapValues(_.length).toVector.sortBy(_._1)
                  val result = for
                    foundationArtifact <- cas.get(rootDigest).toRight("root artifact missing")
                    _ <- Either.cond(foundationArtifact.kind == "foundation", (), "root artifact is not a foundation")
                    name <- field(foundationArtifact.body, "name").collect { case Canon.S(s) => s }
                      .toRight("foundation has no name")
                    applicationRef <- field(foundationArtifact.body, "application").collect { case Canon.R(r) => r }
                      .toRight("foundation has no application reference")
                    applicationArtifact <- cas.get(applicationRef).toRight("application artifact missing")
                    _ <- Either.cond(applicationArtifact.kind == "application", (), "application artifact has the wrong kind")
                    metaRef <- field(applicationArtifact.body, "meta").collect { case Canon.R(r) => r }
                      .toRight("application has no meta program reference")
                    metaArtifact <- cas.get(metaRef).toRight("meta program artifact missing")
                    _ <- Either.cond(metaArtifact.kind == "meta-program", (), "meta program artifact has the wrong kind")
                  yield Canon.node(
                    "attestation",
                    Canon.node("name", Canon.S(name)),
                    Canon.node("foundation", Canon.R(rootDigest)),
                    Canon.node("application", Canon.R(applicationRef)),
                    Canon.node("meta", Canon.R(metaRef)),
                    Canon.node("closure", Canon.N(BigInt(digests.length))),
                    Canon.node("kinds", Canon.M(kinds.map((k, n) => Canon.Sym(k) -> Canon.N(BigInt(n))))),
                    Canon.node("complete", Canon.B(true))
                  )
                  result match
                    case Left(m) => CommandResult.fail(m)
                    case Right(attestation) =>
                      CommandResult.ok(
                        CanonText.write(attestation),
                        s"attestation ${Canon.digest(attestation).hex}"
                      )

  private def closure(root: Path, opts: Map[String, String]): CommandResult =
    opts.get("dir") match
      case None => CommandResult.fail("usage: foundation closure --dir <dir>")
      case Some(dir) =>
        Cli.loadFoundation(root, root.resolve(dir)) match
          case Left(m) => CommandResult.fail(m)
          case Right(f) =>
            Closure.traverse(f.cas, f.foundationDigest) match
              case Left(missing) => CommandResult.fail(s"closure incomplete, missing ${missing.hex}")
              case Right(digests) =>
                val kinds = digests.flatMap(d => f.cas.get(d).map(_.kind))
                val summary = kinds.groupBy(identity).view.mapValues(_.length).toVector.sortBy(_._1)
                CommandResult.okLines(
                  Vector(s"closure ${digests.length} artifacts") ++ summary.map((k, n) => s"$k $n")
                )

  /** The predecessor verifies the successor through its own `VerifyFoundation` judgment. */
  private def verifySuccessor(root: Path, opts: Map[String, String]): CommandResult =
    (opts.get("predecessor"), opts.get("successor")) match
      case (Some(p), Some(s)) =>
        val predDir = root.resolve(p)
        val succDir = root.resolve(s)
        (Cli.loadFoundation(root, predDir), Cli.loadFoundation(root, succDir)) match
          case (Left(m), _) => CommandResult.fail(s"predecessor: $m")
          case (_, Left(m)) => CommandResult.fail(s"successor: $m")
          case (Right(pred), Right(succ)) =>
            val predName = field(pred.foundation, "name").collect { case Canon.S(x) => x }.getOrElse("?")
            val succName = field(succ.foundation, "name").collect { case Canon.S(x) => x }.getOrElse("?")
            if !pred.program.judgments.contains("VerifyFoundation") then
              CommandResult.fail(s"$predName does not define VerifyFoundation")
            else
              val merged = LayeredCas(succ.cas, Vector(pred.cas))
              val caps = stratum.cap.Capabilities.standard(merged, root, pred.foundationDigest.hex)
              val goal = Canon.node(
                "call",
                Canon.Sym("VerifyFoundation"),
                Canon.node("q", succ.foundation),
                Canon.node("q", Canon.R(pred.foundationDigest))
              )
              val verdict = MetaMachine0.derive(pred.program, merged, pred.kernel, pred.budget, goal, caps)
              val resultLine = verdict match
                case Canon.Node("verdict", Vector(Canon.Sym("ok"), v, _)) => CanonText.write(v)
                case Canon.Node("verdict", Vector(Canon.Sym("error"), Canon.Sym(k), Canon.S(m), _)) => s"error $k $m"
                case other                                                => CanonText.write(other)
              val valid = resultLine.startsWith("(valid")
              CommandResult(
                if valid then 0 else 1,
                Vector(s"predecessor $predName", s"successor $succName", s"result $resultLine")
              )
      case _ => CommandResult.fail("usage: foundation verify-successor --predecessor <dir> --successor <dir>")
