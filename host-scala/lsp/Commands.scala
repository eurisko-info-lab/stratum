package stratum.lsp

import Json.*
import stratum.cli.CommandResult

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * The editor commands.
 *
 * `serve` speaks the protocol over stdio, `languages` reports what a world
 * publishes, and `replay` drives a scripted session. Generating a manifest for
 * any particular editor is that editor's business, not the platform's, so it
 * lives with the client rather than here.
 */
object Commands:

  def run(root: Path, args: Vector[String]): CommandResult =
    val opts = options(args)
    args.headOption match
      case Some("serve")     => serve(root, opts)
      case Some("languages") => languages(root, opts)
      case Some("replay")    => replay(root, opts)
      case other             => CommandResult.fail(s"unknown lsp command ${other.getOrElse("")}")

  private def options(args: Vector[String]): Map[String, String] =
    args
      .sliding(2)
      .collect { case Vector(k, v) if k.startsWith("--") => k.drop(2) -> v }
      .toMap

  private def world(root: Path, opts: Map[String, String]): Either[String, Service] =
    opts.get("world") match
      case None      => Left("usage: lsp <command> --world <dir>")
      case Some(dir) => Service.load(root, root.resolve(dir))

  // ------------------------------------------------------------------ serve

  private def serve(root: Path, opts: Map[String, String]): CommandResult =
    world(root, opts) match
      case Left(m) => CommandResult.fail(m)
      case Right(service) =>
        // stdout belongs to the protocol from here on.
        Server.serve(service, System.in, System.out)
        CommandResult(0, Vector.empty)

  private def languages(root: Path, opts: Map[String, String]): CommandResult =
    world(root, opts) match
      case Left(m) => CommandResult.fail(m)
      case Right(service) =>
        val lines = service.descriptor.bindings.map { b =>
          s"  ${b.name} ${b.label} ${b.extensions.mkString(" ")} ${service.keywords(b.name).length} keywords"
        }
        CommandResult.okLines(s"service ${service.descriptor.name}" +: lines)

  // ----------------------------------------------------------------- replay

  /**
   * Replays a scripted editing session and reports everything the server sent
   * back, so an editing session is a transcript like any other derivation.
   */
  private def replay(root: Path, opts: Map[String, String]): CommandResult =
    world(root, opts) match
      case Left(m) => CommandResult.fail(m)
      case Right(service) =>
        opts.get("script") match
          case None => CommandResult.fail("usage: lsp replay --world <dir> --script <file>")
          case Some(scriptName) =>
            val script = root.resolve(scriptName)
            if !Files.exists(script) then CommandResult.fail(s"no such script: $scriptName")
            else
              val captured = java.io.ByteArrayOutputStream()
              val session = Server.Session(service, captured)
              val failures = scala.collection.mutable.ArrayBuffer.empty[String]
              Files
                .readAllLines(script)
                .toArray(Array.empty[String])
                .filter(l => l.trim.nonEmpty && !l.trim.startsWith("#"))
                .foreach { line =>
                  Json.read(line) match
                    case Right(message) => session.handle(message)
                    case Left(m)        => failures += s"malformed script line: $m"
                }
              val lines = frames(captured.toByteArray).map { payload =>
                Json.read(payload) match
                  case Right(message) =>
                    (message / "method").str match
                      case Some(method) => s"$method ${Json.write(message / "params")}"
                      case None         => s"result ${Json.write(message / "id")} ${Json.write(message / "result")}"
                  case Left(m) => s"unreadable response: $m"
              }
              CommandResult(if failures.isEmpty then 0 else 1, lines ++ failures.toVector)

  /** Content-Length framing is counted in bytes, so it is split on bytes. */
  private def frames(data: Array[Byte]): Vector[String] =
    val separator = "\r\n\r\n".getBytes(UTF_8)
    val out = Vector.newBuilder[String]
    var i = 0
    var scanning = true
    while scanning do
      val headerEnd = indexOf(data, separator, i)
      if headerEnd < 0 then scanning = false
      else
        val header = String(data, i, headerEnd - i, UTF_8)
        val length = header.linesIterator
          .find(_.toLowerCase.startsWith("content-length:"))
          .map(_.dropWhile(_ != ':').drop(1).trim.toInt)
          .getOrElse(0)
        val start = headerEnd + separator.length
        out += String(data, start, length, UTF_8)
        i = start + length
        scanning = i < data.length
    out.result()

  private def indexOf(data: Array[Byte], pattern: Array[Byte], from: Int): Int =
    var i = math.max(from, 0)
    var found = -1
    while found < 0 && i + pattern.length <= data.length do
      var j = 0
      while j < pattern.length && data(i + j) == pattern(j) do j += 1
      if j == pattern.length then found = i else i += 1
    found
