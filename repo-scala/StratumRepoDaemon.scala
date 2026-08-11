package stratum.repo

import stratum.lsp.Json
import stratum.lsp.Json.*
import stratum.cli.{CommandResult, Transcript}
import stratum.journal.Journal

import java.nio.file.Path
import scala.collection.mutable
import scala.io.StdIn
import scala.util.control.NonFatal

/** JSON-lines transport for repeated repository commands in one JVM. */
object StratumRepoDaemon:

  def main(args: Array[String]): Unit =
    val root = args.headOption.map(Path.of(_)).getOrElse(Path.of(System.getProperty("user.dir")))
      .toAbsolutePath.normalize()
    val transcript = TranscriptRuntime(root)
    Iterator.continually(StdIn.readLine()).takeWhile(_ != null).foreach { line =>
      val response =
        Json.read(line) match
          case Left(error) => failure(s"invalid request: $error")
          case Right(request) =>
            try
              (request / "method").str match
                case Some("transcript-step") =>
                  transcript.step(request)
                case Some("transcript-api") =>
                  Json.obj("code" -> Json.int(0), "description" -> Json.Str(transcript.description))
                case Some(other) => failure(s"unknown daemon method: $other")
                case None => failure("daemon method is required")
            catch case NonFatal(error) => failure(s"repository command failed: ${error.getMessage}")
      println(Json.write(response))
      Console.flush()
    }

  private final case class Session(project: Path, repository: Path)

  private[repo] final class TranscriptRuntime(root: Path):
    private val sessions = mutable.LinkedHashMap.empty[String, Session]
    private var current: Option[String] = None

    val description: String =
      """Execute one command from an adaptive Stratum transcript. Commands:
        |repo create <tag> <project>                -> session <tag> project <project> repository <project>/.stratum
        |repo open <tag> <project>                  -> session <tag> project <project> repository <project>/.stratum
        |repo use <tag>
        |repo close <tag>
        |repo list
        |language list                              (lists installed project languages)
        |language guide <name>                      (returns installed language agent guidance)
        |language add <name>                         -> added language <name>
        |language modify <name> <grammar|meta>       (source text is the step input)
        |source list                                (lists project source paths)
        |source show <path>                         (returns exact source content)
        |source add <path>                           (source text is the step input; -> added <path>)
        |source modify <path>                        (source text is the step input; -> modified <path>)
        |source copy <from> <to>                     -> copied <from> <to>
        |source remove <path>
        |source check <path>                         -> valid <path>
        |test <path>                                 (validates declared syntax and structure; -> valid <path>)
        |run <path>                                  (runs the language's declared source evaluator; -> result <text>)
        |commit <message>                            -> branch main
        |status                                      (output is dynamic; an empty expectation accepts success)
        |verify                                      -> valid branch main
        |log
        |branches
        |artifact search <text>
        |All arguments shown in angle brackets belong on the command line. The separate step input is
        |used only by `language modify`, `source add`, and `source modify`; never put command arguments
        |in input. Expected values are exact complete output lines. Use [] when success itself is the
        |expectation and the output is dynamic. Example: command `repo open demo tmp`, no input,
        |expected `[\"session demo project tmp repository tmp/.stratum\"]`.
        |Every step is transactional. Its effects are retained only when the command succeeds and
        |all expected output lines occur in order. Otherwise the daemon undoes the step so the model
        |can vary that same command safely.""".stripMargin

    def step(request: Json): Json =
      val command = (request / "command").str.getOrElse("").trim
      val input = (request / "input").str
      val expectedJson = (request / "expected").items
      val expected = expectedJson.flatMap(_.str)
      if command.isEmpty then return failure("transcript command is required")
      if expected.length != expectedJson.length then return failure("expected lines must be strings")

      Journal.arm()
      try
        val (commandResult, commitState) = dispatch(command, input)
        val actual =
          if commandResult.code == 0 then commandResult.lines
          else commandResult.lines :+ s"exit ${commandResult.code}"
        val missing =
          if commandResult.code == 0 then Transcript.unmet(expected, actual)
          else None
        if commandResult.code != 0 || missing.nonEmpty then
          val undone = Journal.undo()
          Journal.disarm()
          val lines = Vector.newBuilder[String]
          lines += s"transcript step failed: $command"
          if commandResult.code != 0 then lines += s"command exited ${commandResult.code}"
          missing.foreach(line => lines += s"expected line absent: $line")
          expected.foreach(line => lines += s"expected: $line")
          actual.foreach(line => lines += s"actual: $line")
          lines += s"undone ${undone.length} paths"
          Json.obj("code" -> Json.int(1), "lines" -> Json.arr(lines.result().map(Json.Str.apply)))
        else
          commitState()
          Journal.disarm()
          result(commandResult)
      catch
        case NonFatal(error) =>
          val undone = Journal.undo()
          Journal.disarm()
          failure(s"transcript step failed: ${error.getMessage}; undone ${undone.length} paths")
      finally Journal.disarm()

    private def dispatch(command: String, input: Option[String]): (CommandResult, () => Unit) =
      val args = Transcript.splitCommand(command)
      val noState = () => ()
      args match
        case Vector("repo", action, tag, projectText) if action == "create" || action == "open" =>
          if sessions.contains(tag) then return CommandResult.fail(s"session tag is already open: $tag") -> noState
          if !tag.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") then return CommandResult.fail(s"invalid session tag: $tag") -> noState
          resolveProject(projectText) match
            case Left(error) => CommandResult.fail(error) -> noState
            case Right(project) =>
              val repository = project.resolve(".stratum")
              if sessions.values.exists(_.repository == repository) then
                CommandResult.fail(s"repository already has an open session: ${relative(repository)}") -> noState
              else
                val operation = if action == "create" then "create" else "inspect"
                val opened = StratumRepo.run(root, Vector(operation, "--dir", repository.toString))
                val output =
                  if opened.code == 0 then CommandResult.ok(s"session $tag project ${relative(project)} repository ${relative(repository)}")
                  else opened
                output -> (() => { sessions.put(tag, Session(project, repository)); current = Some(tag) })
        case Vector("repo", "use", tag) =>
          if sessions.contains(tag) then CommandResult.ok(s"using session $tag") -> (() => current = Some(tag))
          else CommandResult.fail(s"session is not open: $tag") -> noState
        case Vector("repo", "close", tag) =>
          if sessions.contains(tag) then
            CommandResult.ok(s"closed session $tag") -> (() => { sessions.remove(tag); if current.contains(tag) then current = None })
          else CommandResult.fail(s"session is not open: $tag") -> noState
        case Vector("repo", "list") =>
          val lines = sessions.toVector.map { case (tag, session) =>
            s"session $tag project ${relative(session.project)} repository ${relative(session.repository)}"
          }
          CommandResult.okLines(if lines.isEmpty then Vector("no open sessions") else lines) -> noState
        case _ =>
          activeSession() match
            case Left(error) => CommandResult.fail(error) -> noState
            case Right(session) => repositoryCommand(session, args, input) -> noState

    private def repositoryCommand(session: Session, args: Vector[String], input: Option[String]): CommandResult =
      val project = session.project.toString
      val repository = session.repository.toString
      val base = Vector("--dir", repository)
      def source(command: String, path: String, text: Option[String] = None): CommandResult =
        val initial = Vector(command) ++ base ++ Vector(
          "--source", project,
          "--declaration-root", root.toString,
          "--path", path
        )
        val full = text.fold(initial)(value => initial ++ Vector("--text", value))
        StratumRepo.run(root, full)

      args match
        case Vector("language", "list") =>
          StratumRepo.run(root, Vector("list-languages") ++ base ++ Vector("--source", project))
        case Vector("language", "guide", name) =>
          source("show-source", s"languages/$name/$name.agent.md")
        case Vector("language", "add", name) =>
          StratumRepo.run(root, Vector("add-language") ++ base ++ Vector("--project", project, "--name", name))
        case Vector("language", "modify", name, kind) if kind == "grammar" || kind == "meta" =>
          input match
            case None => CommandResult.fail("language modify requires step input")
            case Some(text) => source("put-source", s"languages/$name/$name.$kind", Some(text))
        case Vector("source", action, path) if action == "add" || action == "modify" =>
          input match
            case None => CommandResult.fail(s"source $action requires step input")
            case Some(text) => source("put-source", path, Some(text))
        case Vector("source", "copy", from, to) =>
          StratumRepo.run(root, Vector("copy-source") ++ base ++ Vector(
            "--source", project,
            "--declaration-root", root.toString,
            "--from", from,
            "--path", to
          ))
        case Vector("source", "list") =>
          StratumRepo.run(root, Vector("list-sources") ++ base ++ Vector("--source", project))
        case Vector("source", "show", path) => source("show-source", path)
        case Vector("source", "remove", path) => source("remove-source", path)
        case Vector("source", "check", path) => source("check-source", path)
        case Vector("test", path) => source("check-source", path)
        case Vector("run", path) => source("run-source", path)
        case "commit" +: message =>
          StratumRepo.run(root, Vector("record") ++ base ++ Vector(
            "--source", project,
            "--declaration-root", root.toString,
            "--message", message.mkString(" ")
          ))
        case Vector(action) if Set("status", "log", "verify", "branches").contains(action) =>
          val extra = if action == "status" then Vector("--source", project, "--declaration-root", root.toString) else Vector.empty
          StratumRepo.run(root, Vector(action) ++ base ++ extra)
        case "artifact" +: "search" +: query if query.nonEmpty =>
          StratumRepo.run(root, Vector("search-artifacts") ++ base ++ Vector("--query", query.mkString(" ")))
        case _ => CommandResult.fail(s"unknown transcript command: ${args.mkString(" ")}")

    private def activeSession(): Either[String, Session] =
      current.flatMap(sessions.get).toRight("no current session; run 'repo create', 'repo open', or 'repo use'")

    private def resolveProject(value: String): Either[String, Path] =
      val project = root.resolve(value).normalize()
      if project != root && !project.startsWith(root) then Left(s"project escapes configured checkout: $value")
      else Right(project)

    private def relative(path: Path): String = root.relativize(path).toString match
      case "" => "."
      case value => value

  private def result(command: stratum.cli.CommandResult): Json =
    Json.obj(
      "code" -> Json.int(command.code),
      "lines" -> Json.arr(command.lines.map(Json.Str.apply))
    )

  private def failure(message: String): Json =
    Json.obj("code" -> Json.int(1), "lines" -> Json.arr(Vector(Json.Str(s"error: $message"))))