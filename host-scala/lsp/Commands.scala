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
      case Some("script")    => script(root, opts)
      case Some("package")   => generate(root, opts)
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

  private def script(root: Path, opts: Map[String, String]): CommandResult =
    opts.get("script") match
      case None => CommandResult.fail("usage: lsp script --script <file> [--out <file>]")
      case Some(name) =>
        StudioScript.compile(root, root.resolve(name)) match
          case Left(error) => CommandResult.fail(error)
          case Right(value) =>
            val text = Json.write(value)
            opts.get("out").foreach(out => write(root.resolve(out), text))
            CommandResult.ok(text)

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


  // -------------------------------------------------------------- packaging

  private def generate(root: Path, opts: Map[String, String]): CommandResult =
    world(root, opts) match
      case Left(m) => CommandResult.fail(m)
      case Right(service) =>
        opts.get("out") match
          case None => CommandResult.fail("usage: lsp package --world <dir> --out <dir>")
          case Some(outName) =>
            val out = root.resolve(outName)
            Files.createDirectories(out)
            val ids = Service.identifiers(service.descriptor)
            val written = service.descriptor.bindings.map { binding =>
              s"  ${ids(binding.name)} ${binding.extensions.mkString(" ")}"
            }
            write(out.resolve("package.json"), Json.pretty(manifest(service, ids, opts.getOrElse("world", ""))))
            CommandResult.okLines(
              (s"packaged ${service.descriptor.name} into $outName" +: written) :+
                s"  ${service.descriptor.actions.length} commands"
            )

  private def write(path: Path, text: String): Unit =
    Files.write(path, (text + "\n").getBytes(UTF_8))

  /** One view for each the profile declares, in the region it declares. */
  private def viewsFor(service: Service): (Json, Json) =
    val declared = service.descriptor.bindings.headOption
      .flatMap(b => service.layout(b.name))
      .map(l => Service.get(l, "views").map(Service.list).getOrElse(Vector.empty))
      .getOrElse(Vector.empty)
    val fromProfile = declared.map { view =>
      val region = Service.string(view, "placement")
      val location = service.descriptor.placements.getOrElse(region, "activitybar")
      (location, Service.string(view, "name"), Service.string(view, "primitive"), region)
    }
    // A region the client renders beside the document is not a tree, so it
    // contributes no view; the client opens it as a document of its own.
    val navigator = service.descriptor.navigator.toVector.map { n =>
      val location = service.descriptor.placements.getOrElse(n.placement, "activitybar")
      (location, n.name, "tree", n.placement)
    }
    val located = (navigator ++ fromProfile).filterNot(_._1 == "beside")
    val title = service.descriptor.editor.getOrElse("view", "Stratum")
    val containers = located.map(_._1).distinct.sorted
    val viewsContainers = Json.Obj(containers.map { location =>
      location -> Json.arr(
        Vector(
          Json.obj(
            "id" -> Str(s"stratum-$location"),
            "title" -> Str(title),
            "icon" -> Str("$(symbol-structure)")
          )
        )
      )
    })
    val views = Json.Obj(containers.map { location =>
      s"stratum-$location" -> Json.arr(located.filter(_._1 == location).map { (_, name, primitive, region) =>
        val title = service.descriptor.navigator.filter(_.name == name).map(_.title).getOrElse(name)
        Json.obj(
          "id" -> Str(s"stratum.view.$name"),
          "name" -> Str(title),
          "contextualTitle" -> Str(s"$name ($primitive, $region)")
        )
      })
    })
    (viewsContainers, views)

  /** The client manifest, generated from the descriptor the world publishes. */
  private def manifest(service: Service, ids: Map[String, String], worldDir: String): Json =
    val editor = service.descriptor.editor
    def declared(key: String, fallback: String): String = editor.getOrElse(key, fallback)

    val languages = service.descriptor.bindings.map { b =>
      val id = ids(b.name)
      Json.obj(
        "id" -> Str(id),
        "aliases" -> Json.arr(Vector(Str(b.label))),
        "extensions" -> Json.arr(b.extensions.map(Str.apply))
      )
    }
    val evaluate = Json.obj(
      "command" -> Str("stratum.evaluate"),
      "title" -> Str("Stratum: evaluate selection"),
      "category" -> Str(declared("category", "Stratum"))
    )
    val commands = evaluate +: service.descriptor.actions.map { a =>
      Json.obj(
        "command" -> Str(s"stratum.${a.name}"),
        "title" -> Str(a.title),
        "category" -> Str(declared("category", "Stratum"))
      )
    }
    val (viewsContainers, views) = viewsFor(service)
    Json.obj(
      "name" -> Str(declared("identifier", "stratum-client")),
      "displayName" -> Str(declared("display", "Stratum")),
      "description" -> Str(declared("description", "")),
      "version" -> Str(declared("version", "0.1.0")),
      "license" -> Str(declared("license", "UNLICENSED")),
      "publisher" -> Str(declared("publisher", "stratum")),
      "engines" -> Json.obj("vscode" -> Str("^1.85.0")),
      "categories" -> Json.arr(Vector(Str("Programming Languages"))),
      "activationEvents" -> Json.arr(service.descriptor.bindings.map(b => Str(s"onLanguage:${ids(b.name)}"))),
      "main" -> Str("./out/extension.js"),
      "contributes" -> Json.obj(
        "languages" -> Json.arr(languages),
        "commands" -> Json.arr(commands),
        "keybindings" -> Json.arr(
          Vector(
            Json.obj(
              "command" -> Str("stratum.evaluate"),
              "key" -> Str("ctrl+shift+d"),
              "when" -> Str("editorTextFocus")
            )
          )
        ),
        "viewsContainers" -> viewsContainers,
        "views" -> views,
        "configuration" -> Json.obj(
          "title" -> Str(declared("category", "Stratum")),
          "properties" -> Json.obj(
            "stratum.world" -> Json.obj(
              "type" -> Str("string"),
              "default" -> Str(worldDir),
              "description" -> Str("The world directory whose service answers editor requests.")
            ),
            "stratum.server" -> Json.obj(
              "type" -> Str("string"),
              "default" -> Str("./tools/lsp.sh"),
              "description" -> Str("The command that starts the language server.")
            )
          )
        )
      ),
      "scripts" -> Json.obj(
        "compile" -> Str("tsc -p ."),
        "watch" -> Str("tsc -watch -p ."),
        "package" -> Str("vsce package")
      ),
      "dependencies" -> Json.obj("vscode-languageclient" -> Str("^9.0.1")),
      "devDependencies" -> Json.obj(
        "@types/node" -> Str("^20.11.0"),
        "@types/vscode" -> Str("^1.85.0"),
        "typescript" -> Str("^5.4.0")
      )
    )
