package stratum.lsp

import Json.*
import stratum.canon.Canon

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

/**
 * A generic language server.
 *
 * Every request is answered by a judgment of the world. There are no language
 * names, no keywords, no validation rules and no formatting rules here, and
 * adding a language to the editor requires no change to this file.
 */
object Server:

  private val severities = Map("error" -> 1, "warning" -> 2, "information" -> 3, "hint" -> 4)

  private val symbolKinds = Map(
    "file" -> 1, "module" -> 2, "namespace" -> 3, "package" -> 4, "class" -> 5,
    "method" -> 6, "property" -> 7, "field" -> 8, "enum" -> 10, "interface" -> 11,
    "function" -> 12, "variable" -> 13, "constant" -> 14, "string" -> 15,
    "number" -> 16, "boolean" -> 17, "array" -> 18, "object" -> 19, "key" -> 20,
    "event" -> 24, "operator" -> 25
  )

  private val completionKinds = Map(
    "keyword" -> 14, "string" -> 6, "number" -> 12, "event" -> 6,
    "namespace" -> 9, "object" -> 7, "entry" -> 6
  )

  /** The legend the client is told about, and the order its indices refer to. */
  private val tokenTypes = Vector("keyword", "string", "number", "variable")

  final class Session(
      service: Service,
      out: OutputStream,
      source: String => Option[String] = _ => None
  ):
    private val documents = mutable.Map.empty[String, String]
    var running = true

    // ------------------------------------------------------------ transport

    private def send(message: Json): Unit =
      val payload = Json.write(message).getBytes(UTF_8)
      out.write(s"Content-Length: ${payload.length}\r\n\r\n".getBytes(UTF_8))
      out.write(payload)
      out.flush()

    private def respond(id: Json, result: Json): Unit =
      send(Json.obj("jsonrpc" -> Str("2.0"), "id" -> id, "result" -> result))

    private def notify(method: String, params: Json): Unit =
      send(Json.obj("jsonrpc" -> Str("2.0"), "method" -> Str(method), "params" -> params))

    // ------------------------------------------------------------ positions

    private def range(lines: Service.Lines, offset: Int, length: Int): Json =
      val (sl, sc) = lines.positionOf(offset)
      val (el, ec) = lines.positionOf(offset + math.max(length, 0))
      Json.obj(
        "start" -> Json.obj("line" -> int(sl), "character" -> int(sc)),
        "end" -> Json.obj("line" -> int(el), "character" -> int(ec))
      )

    private def openDocument(params: Json): Option[(String, Binding)] =
      val uri = (params / "textDocument" / "uri").str.getOrElse("")
      for
        text <- documents.get(uri)
        binding <- service.bindingForUri(uri)
      yield (text, binding)

    // ---------------------------------------------------------- diagnostics

    private def publish(uri: String): Unit =
      val reported =
        for
          text <- documents.get(uri)
          binding <- service.bindingForUri(uri)
        yield
          val lines = Service.Lines(text)
          service.diagnostics(binding.name, text) match
            case Right(found) =>
              found.map { d =>
                Json.obj(
                  "range" -> range(lines, Service.number(d, "offset"), Service.number(d, "length")),
                  "severity" -> int(severities.getOrElse(Service.string(d, "severity"), 1)),
                  "source" -> Str(service.descriptor.name),
                  "message" -> Str(Service.string(d, "message"))
                )
              }
            case Left(message) =>
              // A failed derivation is itself worth showing: an exhausted
              // budget or a missing judgment is a real fault in the world.
              Vector(
                Json.obj(
                  "range" -> range(lines, 0, 1),
                  "severity" -> int(1),
                  "source" -> Str(service.descriptor.name),
                  "message" -> Str(message)
                )
              )
      notify(
        "textDocument/publishDiagnostics",
        Json.obj("uri" -> Str(uri), "diagnostics" -> Json.arr(reported.getOrElse(Vector.empty)))
      )

    // ------------------------------------------------------------- dispatch

    def handle(message: Json): Unit =
      val method = (message / "method").str.getOrElse("")
      val params = message / "params"
      val id = message / "id"
      method match
        case "initialize"  => respond(id, initialize)
        case "initialized" => ()
        case "shutdown"    => respond(id, Null)
        case "exit"        => running = false

        case "textDocument/didOpen" =>
          val uri = (params / "textDocument" / "uri").str.getOrElse("")
          documents(uri) = (params / "textDocument" / "text").str.getOrElse("")
          publish(uri)

        case "textDocument/didChange" =>
          val uri = (params / "textDocument" / "uri").str.getOrElse("")
          (params / "contentChanges").items.lastOption.flatMap(c => (c / "text").str).foreach { t =>
            documents(uri) = t
          }
          publish(uri)

        case "textDocument/didSave" =>
          publish((params / "textDocument" / "uri").str.getOrElse(""))

        case "textDocument/didClose" =>
          val uri = (params / "textDocument" / "uri").str.getOrElse("")
          documents.remove(uri)
          notify(
            "textDocument/publishDiagnostics",
            Json.obj("uri" -> Str(uri), "diagnostics" -> Json.arr(Vector.empty))
          )

        case "textDocument/documentSymbol" =>
          val result = openDocument(params).map { (text, binding) =>
            val lines = Service.Lines(text)
            service.symbols(binding.name, text).getOrElse(Vector.empty).map { s =>
              val extent = range(lines, Service.number(s, "offset"), Service.number(s, "length"))
              Json.obj(
                "name" -> Str(Service.string(s, "label")),
                "detail" -> Str(Service.string(s, "detail")),
                "kind" -> int(symbolKinds.getOrElse(Service.string(s, "kind"), 19)),
                "range" -> extent,
                "selectionRange" -> extent
              )
            }
          }
          respond(id, Json.arr(result.getOrElse(Vector.empty)))

        case "textDocument/completion" =>
          val result = openDocument(params).map { (text, binding) =>
            service.completions(binding.name, text).getOrElse(Vector.empty).map { c =>
              Json.obj(
                "label" -> Str(Service.string(c, "label")),
                "kind" -> int(completionKinds.getOrElse(Service.string(c, "kind"), 1)),
                "detail" -> Str(Service.string(c, "detail"))
              )
            }
          }
          respond(
            id,
            Json.obj("isIncomplete" -> Bool(false), "items" -> Json.arr(result.getOrElse(Vector.empty)))
          )

        case "textDocument/formatting" =>
          val result = openDocument(params).flatMap { (text, binding) =>
            service.format(binding.name, text).toOption.flatten.map { printed =>
              val lines = Service.Lines(text)
              Vector(Json.obj("range" -> range(lines, 0, text.length), "newText" -> Str(printed)))
            }
          }
          respond(id, Json.arr(result.getOrElse(Vector.empty)))

        case "textDocument/hover" =>
          // Hover needs no lexer: every symbol already carries its own extent,
          // so the one under the cursor is found by interval containment.
          val found = openDocument(params).flatMap { (text, binding) =>
            val lines = Service.Lines(text)
            val line = (params / "position" / "line").num.map(_.toInt).getOrElse(0)
            val character = (params / "position" / "character").num.map(_.toInt).getOrElse(0)
            val offset = lines.offsetOf(line, character)
            service.symbols(binding.name, text).getOrElse(Vector.empty).find { s =>
              val at = Service.number(s, "offset")
              offset >= at && offset < at + math.max(Service.number(s, "length"), 1)
            }
          }
          respond(
            id,
            found match
              case Some(s) =>
                Json.obj(
                  "contents" -> Json.obj(
                    "kind" -> Str("markdown"),
                    "value" -> Str(
                      s"**${Service.string(s, "label")}** _${Service.string(s, "kind")}_" +
                        s"\n\n${Service.string(s, "detail")}"
                    )
                  )
                )
              case None => Null
          )

        case "workspace/executeCommand" =>
          val name = (params / "command").str.getOrElse("").split('.').last
          val uri = (params / "arguments").items.headOption.flatMap(_.str).getOrElse("")
          val answer =
            for
              text <- documents.get(uri)
              binding <- service.bindingForUri(uri)
            yield service.act(binding.name, text, name).fold(m => s"error: $m", identity)
          val message = answer.getOrElse("no open document for this command")
          notify("window/showMessage", Json.obj("type" -> int(3), "message" -> Str(message)))
          respond(id, Str(message))

        case "textDocument/semanticTokens/full" =>
          // Highlighting, live from the grammar's own token classes. The only
          // thing computed here is the protocol's delta encoding.
          val result = openDocument(params).map { (text, binding) =>
            val lines = Service.Lines(text)
            val found = service
              .tokens(binding.name, text)
              .sortBy(t => Service.number(t, "offset"))
            val data = Vector.newBuilder[Json]
            var previousLine = 0
            var previousCharacter = 0
            found.foreach { t =>
              val (line, character) = lines.positionOf(Service.number(t, "offset"))
              val deltaLine = line - previousLine
              val deltaCharacter = if deltaLine == 0 then character - previousCharacter else character
              data += int(deltaLine)
              data += int(deltaCharacter)
              data += int(Service.number(t, "length"))
              data += int(math.max(tokenTypes.indexOf(Service.string(t, "kind")), 0))
              data += int(0)
              previousLine = line
              previousCharacter = character
            }
            data.result()
          }
          respond(id, Json.obj("data" -> Json.arr(result.getOrElse(Vector.empty))))

        case "stratum/languages" =>
          // The client configures itself from this rather than from generated
          // files: comment tokens and brackets are the world's to declare.
          val ids = Service.identifiers(service.descriptor)
          respond(
            id,
            Json.arr(service.descriptor.bindings.map { b =>
              Json.obj(
                "id" -> Str(ids(b.name)),
                "lineComment" -> b.comment.map(Str.apply).getOrElse(Null)
              )
            })
          )

        case "stratum/virtualApp" =>
          val layout = service.virtualApp.bindings.headOption.flatMap(b => service.layout(b.name))
          respond(id, virtualAppJson(service.virtualApp, layout))

        case "stratum/layout" =>
          // The arrangement the profile describes, so the client places its
          // views where the deployment says rather than where it prefers.
          val arrangement =
            service.descriptor.bindings.headOption.flatMap(b => service.layout(b.name)).map { l =>
              Json.obj(
                "name" -> Str(Service.string(l, "name")),
                "workflow" -> Json.arr(
                  Service.list(Service.get(l, "workflow").getOrElse(stratum.canon.Canon.U)).map(v => Str(Service.text(v)))
                ),
                "navigation" -> Str(
                  Service.string(Service.get(l, "navigation").getOrElse(stratum.canon.Canon.U), "model")
                ),
                "navigator" -> service.descriptor.navigator
                  .map(n =>
                    Json.obj("name" -> Str(n.name), "title" -> Str(n.title), "reveal" -> Bool(n.reveal))
                  )
                  .getOrElse(Null),
                "views" -> Json.arr(
                  Service.list(Service.get(l, "views").getOrElse(stratum.canon.Canon.U)).map { v =>
                    Json.obj(
                      "name" -> Str(Service.string(v, "name")),
                      "placement" -> Str(Service.string(v, "placement")),
                      "primitive" -> Str(Service.string(v, "primitive"))
                    )
                  }
                )
              )
            }
          respond(id, arrangement.getOrElse(Null))

        case "stratum/documents" =>
          // The world's own documents, grouped as the world groups them.
          val language = service.descriptor.bindings.headOption.map(_.name).getOrElse("")
          respond(
            id,
            Json.arr(service.documents(language).map { subject =>
              Json.obj(
                "name" -> Str(Service.string(subject, "name")),
                "reports" -> Json.arr(
                  Service.list(Service.get(subject, "reports").getOrElse(stratum.canon.Canon.U)).map { r =>
                    Json.obj(
                      "name" -> Str(Service.string(r, "name")),
                      "path" -> Str(Service.string(r, "path")),
                      "findings" -> int(Service.number(r, "findings")),
                      "hazards" -> int(Service.number(r, "hazards"))
                    )
                  }
                )
              )
            })
          )

        case "stratum/source" =>
          val path = (params / "path").str.getOrElse("")
          val result = source(path).map { content =>
            Json.obj(
              "path" -> Str(path),
              "language" -> Str(service.bindingForUri(s"file:///$path").map(_.name).getOrElse("")),
              "content" -> Str(content)
            )
          }
          respond(id, result.getOrElse(Null))

        case "stratum/preview" =>
          val previewUri = (params / "uri").str.getOrElse("")
          val rendered =
            for
              text <- documents.get(previewUri)
              binding <- service.bindingForUri(previewUri)
            yield service.preview(binding.name, text).map { block =>
              Json.obj(
                "kind" -> Str(Service.string(block, "kind")),
                "text" -> Str(Service.string(block, "text"))
              )
            }
          respond(id, Json.arr(rendered.getOrElse(Vector.empty)))

        case "stratum/pdf" =>
          // The foreign surface the profile declares, produced by the world.
          val pdfUri = (params / "uri").str.getOrElse("")
          val produced =
            for
              text <- documents.get(pdfUri)
              binding <- service.bindingForUri(pdfUri)
            yield service.pdf(binding.name, text)
          respond(id, Str(produced.getOrElse("")))

        case "stratum/evaluate" =>
          // Evaluating part of a buffer is the world's business; the adapter
          // only carries the offsets across.
          val target = (params / "uri").str.getOrElse("")
          val answer =
            for
              text <- documents.get(target)
              binding <- service.bindingForUri(target)
            yield service
              .evaluate(
                binding.name,
                text,
                (params / "offset").num.map(_.toInt).getOrElse(0),
                (params / "length").num.map(_.toInt).getOrElse(0)
              )
              .fold(m => s"error: $m", identity)
          respond(id, Str(answer.getOrElse("")))

        case "stratum/views" =>
          val uri = (params / "uri").str.getOrElse("")
          val result =
            for
              text <- documents.get(uri)
              binding <- service.bindingForUri(uri)
              views <- service.views(binding.name, text).toOption
            yield views.map { (name, items) =>
              Json.obj("name" -> Str(name), "items" -> Json.arr(items.map(Str.apply)))
            }
          respond(id, Json.arr(result.getOrElse(Vector.empty)))

        case _ =>
          if !id.isNull then
            send(
              Json.obj(
                "jsonrpc" -> Str("2.0"),
                "id" -> id,
                "error" -> Json.obj("code" -> int(-32601), "message" -> Str(s"unhandled $method"))
              )
            )

    private def initialize: Json =
      Json.obj(
        "capabilities" -> Json.obj(
          "textDocumentSync" -> Json.obj(
            "openClose" -> Bool(true),
            "change" -> int(1),
            "save" -> Bool(true)
          ),
          "documentSymbolProvider" -> Bool(true),
          "documentFormattingProvider" -> Bool(true),
          "hoverProvider" -> Bool(true),
          "completionProvider" -> Json.obj("resolveProvider" -> Bool(false)),
          // The commands are deliberately not advertised. They answer about a
          // document, and a client that discovers them through the capability
          // invokes them with no document at all; a client library that
          // registers them on the server's behalf also collides with the
          // client's own registration. The method is still handled for any
          // caller that sends a document with it.
          "semanticTokensProvider" -> Json.obj(
            "legend" -> Json.obj(
              "tokenTypes" -> Json.arr(tokenTypes.map(Str.apply)),
              "tokenModifiers" -> Json.arr(Vector.empty)
            ),
            "full" -> Bool(true)
          )
        ),
        "serverInfo" -> Json.obj(
          "name" -> Str(s"stratum ${service.descriptor.name}"),
          "version" -> Str(service.world.foundationDigest.hex.take(12))
        )
      )

  /** Reads framed messages until the client says exit. */
  def serve(
      service: Service,
      in: InputStream,
      out: OutputStream,
      source: String => Option[String] = _ => None
  ): Unit =
    val session = Session(service, out, source)
    while session.running do
      readMessage(in) match
        case None => session.running = false
        case Some(body) =>
          Json.read(body) match
            case Right(message) => session.handle(message)
            case Left(_)        => ()

  private def readMessage(in: InputStream): Option[String] =
    var length = -1
    var seenHeader = false
    var done = false
    while !done do
      readLine(in) match
        case None => return None
        case Some(line) =>
          if line.isEmpty then done = seenHeader
          else
            seenHeader = true
            if line.toLowerCase.startsWith("content-length:") then
              length = line.substring(line.indexOf(':') + 1).trim.toInt
    if length < 0 then None
    else
      val buffer = new Array[Byte](length)
      var read = 0
      while read < length do
        val n = in.read(buffer, read, length - read)
        if n < 0 then return None
        read += n
      Some(String(buffer, UTF_8))

  private def readLine(in: InputStream): Option[String] =
    val sb = StringBuilder()
    var c = in.read()
    if c < 0 then None
    else
      while c >= 0 && c != '\n' do
        if c != '\r' then sb.append(c.toChar)
        c = in.read()
      Some(sb.toString)

  def virtualAppJson(app: VirtualApp, layout: Option[Canon]): Json =
    val identifiers = Service.identifiers(app)
    val workflow = layout.toVector.flatMap(l => Service.list(Service.get(l, "workflow").getOrElse(Canon.U)))
    val views = layout.toVector.flatMap(l => Service.list(Service.get(l, "views").getOrElse(Canon.U)))
    Json.obj(
      "name" -> Str(app.name),
      "title" -> Str(app.editor.getOrElse("display", app.name)),
      "layout" -> Str(layout.map(l => Service.string(l, "name")).getOrElse("plain-navigator")),
      "workflow" -> Json.arr(workflow.map(v => Str(Service.text(v)))),
      "navigation" -> Str(
        layout.map(l => Service.string(Service.get(l, "navigation").getOrElse(Canon.U), "model")).getOrElse("plain")
      ),
      "navigator" -> app.navigator
        .map(n =>
          Json.obj(
            "name" -> Str(n.name),
            "title" -> Str(n.title),
            "placement" -> Str(n.placement),
            "reveal" -> Bool(n.reveal)
          )
        )
        .getOrElse(Null),
      "views" -> Json.arr(views.map { view =>
        Json.obj(
          "name" -> Str(Service.string(view, "name")),
          "placement" -> Str(Service.string(view, "placement")),
          "primitive" -> Str(Service.string(view, "primitive"))
        )
      }),
      "languages" -> Json.arr(app.bindings.map { binding =>
        Json.obj(
          "name" -> Str(binding.name),
          "id" -> Str(identifiers(binding.name)),
          "label" -> Str(binding.label),
          "extensions" -> Json.arr(binding.extensions.map(Str.apply)),
          "lineComment" -> binding.comment.map(Str.apply).getOrElse(Null)
        )
      }),
      "commands" -> Json.arr(app.actions.map { action =>
        Json.obj("name" -> Str(action.name), "title" -> Str(action.title))
      })
    )
