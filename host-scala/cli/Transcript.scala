package stratum.cli

import stratum.journal.Journal
import stratum.canon.{Canon, CanonText, Digest}
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Transcript-driven execution.
 *
 * A transcript is a sequence of commands and their expected canonical output.
 * It is the primary acceptance mechanism of every foundation: features are
 * proven by replaying transcripts, not by inspecting host internals.
 *
 * The same document is also runnable for effect. `check` replays it and
 * compares; `apply` runs it as one unit against real state, undoing every
 * write it made if any step fails or disagrees with what the document says
 * should be true.
 *
 * Format:
 * {{{
 *   # a comment
 *   % param node = foundations/F11
 *   $ foundation verify --dir {{node}}
 *   > verdict ok
 * }}}
 *
 * A `% param` line declares a parameter, optionally with a default. `{{name}}`
 * is replaced wherever it appears in a command. A parameter with no default
 * must be supplied with `--set name=value`, and a run refuses to start
 * without it, before it has touched anything.
 */
object Transcript:

  final case class Param(name: String, default: Option[String])
  final case class Step(command: String, expected: Vector[String], leading: Vector[String])

  /** A transcript this one is built from, and where its steps belong. */
  final case class Use(at: Int, path: String, args: Map[String, String])

  final case class Doc(
      header: Vector[String],
      steps: Vector[Step],
      footer: Vector[String],
      params: Vector[Param],
      uses: Vector[Use]
  )

  private val ParamLine = """^%\s*param\s+([A-Za-z_][A-Za-z0-9_-]*)\s*(?:=\s*(.*))?$""".r
  private val UseLine = """^%\s*use\s+(\S+)\s*(.*)$""".r

  def parse(text: String): Doc =
    val steps = mutable.ArrayBuffer.empty[Step]
    val pending = mutable.ArrayBuffer.empty[String]
    val params = mutable.ArrayBuffer.empty[Param]
    val uses = mutable.ArrayBuffer.empty[Use]
    var header = Vector.empty[String]
    var headerTaken = false
    var command: Option[String] = None
    var leading = Vector.empty[String]
    var expected = mutable.ArrayBuffer.empty[String]

    def flush(): Unit =
      command.foreach { c =>
        steps += Step(c, expected.toVector, leading)
        expected = mutable.ArrayBuffer.empty[String]
      }

    text.linesIterator.foreach { line =>
      if line.startsWith("$ ") then
        flush()
        if !headerTaken then
          header = pending.toVector
          headerTaken = true
          leading = Vector.empty
        else leading = pending.toVector
        pending.clear()
        command = Some(line.drop(2).trim)
      else if line.startsWith("> ") then expected += line.drop(2)
      else if line == ">" then expected += ""
      else
        line.trim match
          case ParamLine(name, default) =>
            params += Param(name, Option(default).map(_.trim).filter(_.nonEmpty))
          case UseLine(path, args) =>
            val bound = splitCommand(args).flatMap { pair =>
              pair.split("=", 2) match
                case Array(k, v) => Some(k -> v)
                case _           => None
            }.toMap
            uses += Use(steps.length + (if command.isDefined then 1 else 0), path, bound)
          case _ => ()
        pending += line
    }
    flush()
    if !headerTaken then header = pending.toVector
    Doc(
      header,
      steps.toVector,
      if headerTaken then pending.toVector else Vector.empty,
      params.toVector,
      uses.toVector
    )

  /** Replaces every `{{name}}` for which a binding exists. */
  def substitute(text: String, bindings: Map[String, String]): String =
    bindings.foldLeft(text) { case (acc, (name, value)) => acc.replace(s"{{$name}}", value) }

  /** The names still standing in unresolved after substitution. */
  def unresolved(text: String): Vector[String] =
    """\{\{([A-Za-z_][A-Za-z0-9_-]*)\}\}""".r.findAllMatchIn(text).map(_.group(1)).toVector

  /**
   * The first expected line the output does not account for, if any.
   *
   * Replaying a transcript compares the output exactly, because a golden that
   * tolerates difference proves nothing. Running one for effect asks a weaker
   * question: every line the document states must appear, in the order stated,
   * and anything else the command has to say is its own business. A production
   * step is asserted, not transcribed.
   */
  def unmet(expected: Vector[String], actual: Vector[String]): Option[String] =
    var from = 0
    var missing: Option[String] = None
    expected.foreach { line =>
      if missing.isEmpty then
        val at = actual.indexOf(line, from)
        if at < 0 then missing = Some(line) else from = at + 1
    }
    missing

  /** Splits a command line, honouring single and double quotes. */
  def splitCommand(line: String): Vector[String] =
    val out = mutable.ArrayBuffer.empty[String]
    val sb = new StringBuilder
    var quote: Char = 0
    var started = false
    line.foreach { c =>
      if quote != 0 then
        if c == quote then quote = 0 else sb.append(c)
      else if c == '\'' || c == '"' then
        quote = c
        started = true
      else if c.isWhitespace then
        if sb.nonEmpty || started then
          out += sb.toString
          sb.clear()
          started = false
      else sb.append(c)
    }
    if sb.nonEmpty || started then out += sb.toString
    out.toVector

  final case class StepResult(step: Step, actual: Vector[String], passed: Boolean)
  final case class FileResult(path: Path, results: Vector[StepResult]):
    def passed: Boolean = results.forall(_.passed)

  def execute(root: Path, doc: Doc): Vector[StepResult] =
    doc.steps.map { step =>
      val argv = splitCommand(step.command)
      val result =
        try Cli.run(root, argv)
        catch case e: Throwable => CommandResult(1, Vector(s"exception: ${e.getClass.getSimpleName}: ${e.getMessage}"))
      val actual =
        if result.code == 0 then result.lines
        else result.lines :+ s"exit ${result.code}"
      StepResult(step, actual, actual == step.expected)
    }

  def render(doc: Doc, results: Vector[StepResult]): String =
    val sb = new StringBuilder
    doc.header.foreach(l => sb.append(l).append('\n'))
    results.foreach { r =>
      r.step.leading.foreach(l => sb.append(l).append('\n'))
      sb.append("$ ").append(r.step.command).append('\n')
      r.actual.foreach(l => if l.isEmpty then sb.append(">\n") else sb.append("> ").append(l).append('\n'))
    }
    doc.footer.foreach(l => sb.append(l).append('\n'))
    sb.toString

  def transcriptFiles(root: Path, paths: Vector[String]): Vector[Path] =
    val targets = if paths.isEmpty then Vector("fixtures") else paths
    targets.flatMap { p =>
      val abs = root.resolve(p)
      if Files.isDirectory(abs) then
        val stream = Files.walk(abs)
        try stream.iterator().asScala.filter(f => f.toString.endsWith(".transcript")).toVector.sortBy(_.toString)
        finally stream.close()
      else if Files.exists(abs) then Vector(abs)
      else Vector.empty
    }.distinct

  def command(root: Path, args: Vector[String]): CommandResult =
    val sub = Cli.positional(args).headOption.getOrElse("run")
    if sub == "apply" || sub == "rehearse" then apply(root, args, rehearsing = sub == "rehearse")
    else if sub == "describe" then describe(root, args)
    else if sub == "examine" then examine(root, args)
    else
      val update = args.contains("--update")
      val paths = Cli.positional(args).drop(1)
      val files = transcriptFiles(root, paths)

      if files.isEmpty then CommandResult.fail("no transcripts found")
      else
        val lines = mutable.ArrayBuffer.empty[String]
        var failures = 0
        files.foreach { file =>
          val doc = parse(Files.readString(file))
          val results = execute(root, doc)
          val rel = root.relativize(file).toString
          if update then
            Journal.writeString(file, render(doc, results))
            lines += s"updated $rel ${results.length} steps"
          else
            val bad = results.filterNot(_.passed)
            if bad.isEmpty then lines += s"ok $rel ${results.length} steps"
            else
              failures += bad.length
              lines += s"FAIL $rel ${bad.length}/${results.length} steps"
              bad.foreach { b =>
                lines += s"  $$ ${b.step.command}"
                b.step.expected.foreach(l => lines += s"  - $l")
                b.actual.foreach(l => lines += s"  + $l")
              }
        }
        CommandResult(if failures == 0 then 0 else 1, lines.toVector)

  // ---------------------------------------------------------------- apply

  /** `--set name=value`, repeatable. */
  private def settings(args: Vector[String]): Map[String, String] =
    args
      .sliding(2)
      .collect { case Vector("--set", kv) if kv.contains("=") => kv.split("=", 2) match { case Array(k, v) => k -> v } }
      .toMap

  /**
   * Resolves every parameter, refusing while nothing has been touched yet.
   *
   * A supplied value wins over a default. A parameter with neither is an
   * error, and so is a `{{name}}` no parameter declares.
   */
  private def bindings(doc: Doc, supplied: Map[String, String]): Either[Vector[String], Map[String, String]] =
    val resolved = doc.params.map(p => p.name -> supplied.get(p.name).orElse(p.default)).toMap
    val missing = resolved.collect { case (name, None) => s"parameter '$name' has no value: pass --set $name=<value>" }
    val declared = doc.params.map(_.name).toSet
    val undeclared = supplied.keys.filterNot(declared.contains).map(n => s"--set $n=... names no declared parameter")
    val bound = resolved.collect { case (name, Some(value)) => name -> value }
    val dangling =
      doc.steps
        .flatMap(s => unresolved(substitute(s.command, bound)))
        .distinct
        .filterNot(declared.contains)
        .map(n => s"'{{$n}}' is not a declared parameter")
    val faults = (missing ++ undeclared ++ dangling).toVector
    if faults.nonEmpty then Left(faults) else Right(bound)

  /**
   * The whole run, in order, with everything it is built from spliced in.
   *
   * Composition is at the document and not at the step: a used transcript's
   * steps become steps of this one, so a run stays one unit with one journal
   * and one record. Running a transcript from inside a step would be a run
   * inside a run, which is the thing the journal refuses.
   *
   * A transcript is bound by its caller exactly as it is bound from the command
   * line -- arguments first, then its own defaults -- so it cannot tell the
   * difference, and a runbook that can be rehearsed alone can be used by
   * another.
   */
  private def flatten(
      root: Path,
      file: Path,
      supplied: Map[String, String],
      seen: Vector[String]
  ): Either[Vector[String], Vector[Step]] =
    val rel = root.relativize(file).toString
    if seen.contains(rel) then Left(Vector(s"'$rel' is used by itself: ${(seen :+ rel).mkString(" -> ")}"))
    else if !Files.exists(file) then Left(Vector(s"'$rel' does not exist"))
    else
      val doc = parse(Files.readString(file))
      bindings(doc, supplied) match
        case Left(faults) => Left(faults.map(f => if seen.isEmpty then f else s"$rel: $f"))
        case Right(bound) =>
          val own = doc.steps.map(s => s.copy(command = substitute(s.command, bound)))
          val faults = mutable.ArrayBuffer.empty[String]
          val out = mutable.ArrayBuffer.empty[Step]
          (0 to own.length).foreach { i =>
            doc.uses.filter(_.at == i).foreach { use =>
              val args = use.args.map { case (k, v) => k -> substitute(v, bound) }
              flatten(root, root.resolve(use.path), args, seen :+ rel) match
                case Left(e)      => faults ++= e
                case Right(steps) => out ++= steps
            }
            if i < own.length then out += own(i)
          }
          if faults.nonEmpty then Left(faults.toVector) else Right(out.toVector)

  /**
   * Runs a transcript as one unit against real state.
   *
   * Nothing is written until every parameter resolves. From then on every
   * effect is recorded, and the first step that fails or contradicts the
   * document puts all of them back. Rehearsing puts them back either way, so
   * a runbook can be proven without being performed.
   */
  private def apply(root: Path, args: Vector[String], rehearsing: Boolean): CommandResult =
    val paths = Cli.positional(args).drop(1)
    val files = transcriptFiles(root, paths)
    val recordTo = args.sliding(2).collectFirst { case Vector("--record", p) => p }

    if files.isEmpty then CommandResult.fail("no transcripts found")
    else if files.length > 1 then
      CommandResult.fail(s"apply takes one transcript, not ${files.length}: a run is one unit")
    else
      val file = files.head
      val rel = root.relativize(file).toString
      val doc = parse(Files.readString(file))
      val supplied = settings(args)

      (flatten(root, file, supplied, Vector.empty), bindings(doc, supplied)) match
        case (Left(faults), _) => CommandResult(1, s"refused $rel" +: faults.map("  " + _))
        case (_, Left(faults)) => CommandResult(1, s"refused $rel" +: faults.map("  " + _))
        case (Right(steps), Right(bound)) =>
          val commands = steps.map(s => s -> splitCommand(s.command))
          run(root, rel, doc, bound, commands, recordTo.map(root.resolve), rehearsing)

  private def run(
      root: Path,
      rel: String,
      doc: Doc,
      bound: Map[String, String],
      commands: Vector[(Step, Vector[String])],
      recordTo: Option[Path],
      rehearsing: Boolean
  ): CommandResult =
    val lines = mutable.ArrayBuffer.empty[String]
    val done = mutable.ArrayBuffer.empty[(Step, Vector[String], CommandResult)]
    var failure: Option[String] = None
    var missing: Option[String] = None

    Journal.arm()
    try
      commands.iterator.takeWhile(_ => failure.isEmpty).foreach { case (step, argv) =>
        val result =
          try Cli.run(root, argv)
          catch case e: Throwable => CommandResult(1, Vector(s"exception: ${e.getClass.getSimpleName}: ${e.getMessage}"))
        done += ((step, argv, result))
        val actual = if result.code == 0 then result.lines else result.lines :+ s"exit ${result.code}"
        if result.code != 0 then failure = Some(s"step failed: ${argv.mkString(" ")}")
        else
          unmet(step.expected, actual).foreach { line =>
            failure = Some(s"step does not show what the transcript requires: ${argv.mkString(" ")}")
            missing = Some(line)
          }
      }

      // What the run did is read before anything is put back, so an abort is
      // as reportable as a success. An agent is owed the failure as data too.
      val changed = Journal.changed()
      val observed = Journal.observations
      val outcome = if failure.isDefined then "aborted" else if rehearsing then "rehearsed" else "applied"
      val undone = if failure.isDefined || rehearsing then Journal.undo() else Vector.empty
      Journal.disarm()

      recordTo.foreach { target =>
        Journal.writeString(
          target,
          CanonText.pretty(
            record(root, rel, outcome, failure, missing, bound, done.toVector, changed, observed)
          ) + "\n"
        )
      }

      failure match
        case Some(why) =>
          lines += s"aborted $rel after ${done.length}/${commands.length} steps"
          lines += s"  $why"
          missing.foreach(l => lines += s"  required but absent: $l")
          done.lastOption.foreach { case (_, _, result) => result.lines.foreach(l => lines += s"  | $l") }
          lines += s"  undone ${undone.length} paths"
          recordTo.foreach(t => lines += s"  recorded ${root.relativize(t)}")
          CommandResult(1, lines.toVector)
        case None =>
          lines += s"$outcome $rel ${commands.length} steps"
          bound.toVector.sortBy(_._1).foreach { case (k, v) => lines += s"  $k = $v" }
          changed.foreach { case (path, before, after) =>
            val what = (before, after) match
              case (None, Some(_)) => if rehearsing then "would add" else "added"
              case (Some(_), None) => if rehearsing then "would remove" else "removed"
              case _               => if rehearsing then "would change" else "changed"
            lines += s"  $what ${root.relativize(path)}"
          }
          if rehearsing then lines += s"  undone ${undone.length} paths, nothing kept"
          recordTo.foreach(t => lines += s"  recorded ${root.relativize(t)}")
          CommandResult(0, lines.toVector)
    finally Journal.disarm()

  /**
   * What the run was, as canonical data.
   *
   * A keyed map, never a tagged node, so the host dispatches on nothing and a
   * judgment above the boundary is free to decide what this run *meant*.
   *
   * `read` is what the run learned from outside its own closure. Everything
   * else here is a consequence of the closure and those answers, which is what
   * makes the record something a second implementation can examine rather than
   * a report it has to believe.
   */
  private def record(
      root: Path,
      rel: String,
      outcome: String,
      failure: Option[String],
      missing: Option[String],
      bound: Map[String, String],
      done: Vector[(Step, Vector[String], CommandResult)],
      changed: Vector[(Path, Option[Digest], Option[Digest])],
      observed: Vector[Canon]
  ): Canon =
    Canon.map(
      Vector(
        Canon.Sym("transcript") -> Canon.str(rel),
        Canon.Sym("outcome") -> Canon.Sym(outcome),
        Canon.Sym("parameters") -> Canon.map(bound.toVector.map { case (k, v) => Canon.Sym(k) -> Canon.str(v) }*),
        Canon.Sym("steps") -> Canon.L(done.map { case (_, argv, result) =>
          Canon.map(
            Canon.Sym("command") -> Canon.str(argv.mkString(" ")),
            Canon.Sym("exit") -> Canon.nat(result.code),
            Canon.Sym("output") -> Canon.L(result.lines.map(Canon.str))
          )
        }),
        Canon.Sym("changed") -> Canon.L(changed.map { case (path, before, after) =>
          Canon.map(
            Vector(Canon.Sym("path") -> Canon.str(root.relativize(path).toString)) ++
              before.map(d => Canon.Sym("before") -> Canon.R(d)).toVector ++
              after.map(d => Canon.Sym("after") -> Canon.R(d)).toVector*
          )
        }),
        Canon.Sym("read") -> Canon.L(observed)
      ) ++
        failure.map(w => Canon.Sym("reason") -> Canon.str(w)).toVector ++
        missing.map(l => Canon.Sym("absent") -> Canon.str(l)).toVector*
    )

  /**
   * Examines a run from its record alone.
   *
   * A run happens on whichever host holds the working tree, so the other one
   * cannot repeat it. What either can do is read the record and say whether
   * the tree now agrees with what the record claims was left behind. The
   * independent host answers this identically, which is what makes a run
   * something two implementations can agree about without both performing it.
   */
  private def examine(root: Path, args: Vector[String]): CommandResult =
    Cli.positional(args).drop(1).headOption match
      case None => CommandResult.fail("usage: transcript examine <record>")
      case Some(name) =>
        val file = root.resolve(name)
        if !Files.exists(file) then CommandResult.fail(s"unreadable record $name")
        else
          CanonText.read(Files.readString(file)) match
            case Left(m) => CommandResult.fail(s"record is not canonical: $m")
            case Right(recorded) =>
              val base = Option(file.getParent).getOrElse(root)
              val entries = field(recorded, "changed").collect { case Canon.L(items) => items }.getOrElse(Vector.empty)
              var agreed = 0
              val disagreed = Vector.newBuilder[Canon]
              entries.foreach { entry =>
                (field(entry, "path"), field(entry, "after")) match
                  case (Some(Canon.S(path)), Some(Canon.R(expected))) =>
                    val target = base.resolve(path)
                    val actual =
                      if Files.isRegularFile(target) then Some(Digest.of(Files.readAllBytes(target))) else None
                    if actual.map(_.hex).contains(expected.hex) then agreed += 1
                    else disagreed += Canon.str(path)
                  case _ => ()
              }
              def count(key: String): Canon =
                Canon.nat(field(recorded, key).collect { case Canon.L(i) => i.length }.getOrElse(0))
              val value = Canon.map(
                Canon.Sym("agreed") -> Canon.nat(agreed),
                Canon.Sym("disagreed") -> Canon.L(disagreed.result()),
                Canon.Sym("outcome") -> field(recorded, "outcome").getOrElse(Canon.Sym("unstated")),
                Canon.Sym("read") -> count("read"),
                Canon.Sym("record") -> Canon.R(Canon.digest(recorded)),
                Canon.Sym("steps") -> count("steps")
              )
              CommandResult.okLines(Vector(CanonText.write(value), s"run ${Canon.digest(value).hex}"))

  /** Reads a keyed field. The host matches no tag of the system above it. */
  private def field(value: Canon, key: String): Option[Canon] = value match
    case Canon.M(entries) => entries.collectFirst { case (Canon.Sym(k), v) if k == key => v }
    case _                => None

  /**
   * What a transcript needs, as canonical data.
   *
   * A reader that is not a person needs to know what to supply before it can
   * run anything, and should not have to read the document to find out.
   */
  private def describe(root: Path, args: Vector[String]): CommandResult =
    val files = transcriptFiles(root, Cli.positional(args).drop(1))
    if files.isEmpty then CommandResult.fail("no transcripts found")
    else
      CommandResult.okLines(files.toVector.map { file =>
        val doc = parse(Files.readString(file))
        CanonText.write(
          Canon.map(
            Canon.Sym("transcript") -> Canon.str(root.relativize(file).toString),
            Canon.Sym("parameters") -> Canon.L(doc.params.map { p =>
              Canon.map(
                Vector(Canon.Sym("name") -> Canon.Sym(p.name)) ++
                  p.default.map(d => Canon.Sym("default") -> Canon.str(d)).toVector*
              )
            }),
            Canon.Sym("steps") -> Canon.L(doc.steps.map { s =>
              Canon.map(
                Canon.Sym("command") -> Canon.str(s.command),
                Canon.Sym("requires") -> Canon.L(s.expected.map(Canon.str))
              )
            }),
            Canon.Sym("uses") -> Canon.L(doc.uses.map { u =>
              Canon.map(
                Canon.Sym("transcript") -> Canon.str(u.path),
                Canon.Sym("arguments") -> Canon.map(u.args.toVector.map { case (k, v) =>
                  Canon.Sym(k) -> Canon.str(v)
                }*)
              )
            })
          )
        )
      })

