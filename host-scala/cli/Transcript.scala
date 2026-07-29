package stratum.cli

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Transcript-driven functional testing.
 *
 * A transcript is a sequence of commands and their expected canonical output.
 * It is the primary acceptance mechanism of every foundation: features are
 * proven by replaying transcripts, not by inspecting host internals.
 *
 * Format:
 * {{{
 *   # a comment
 *   $ host info
 *   > host StratumHost0
 * }}}
 */
object Transcript:

  final case class Step(command: String, expected: Vector[String], leading: Vector[String])
  final case class Doc(header: Vector[String], steps: Vector[Step], footer: Vector[String])

  def parse(text: String): Doc =
    val steps = mutable.ArrayBuffer.empty[Step]
    val pending = mutable.ArrayBuffer.empty[String]
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
      else pending += line
    }
    flush()
    if !headerTaken then header = pending.toVector
    Doc(header, steps.toVector, if headerTaken then pending.toVector else Vector.empty)

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
          Files.writeString(file, render(doc, results))
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
