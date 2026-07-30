package stratum.lsp

import stratum.canon.{Canon, CanonText}
import stratum.cli.{Cli, LoadedFoundation}

import java.nio.file.Path
import scala.collection.mutable

final case class Binding(name: String, label: String, extensions: Vector[String], comment: Option[String])

final case class Action(name: String, title: String)

/** The world's own navigator, as distinct from a document's profile. */
final case class Navigator(name: String, title: String, placement: String, reveal: Boolean)

final case class Descriptor(
    name: String,
    editor: Map[String, String],
    placements: Map[String, String],
    navigator: Option[Navigator],
    bindings: Vector[Binding],
    actions: Vector[Action]
)

/**
 * The protocol adapter.
 *
 * It holds no knowledge of any language, and it performs no dispatch on any
 * tag of the system built above the host: everything it exchanges with a world
 * is carried in maps whose keys it reads by name. Diagnostics, their
 * positions, symbols, completions, formatting, action results and views are
 * all derived inside the foundation, under its own step budget.
 *
 * The one thing computed here is what is genuinely not semantics: turning a
 * character offset into a line and a UTF-16 character, because that is a
 * property of the wire protocol rather than of any language.
 */
final class Service(val root: Path, val worldDir: Path, val world: LoadedFoundation):

  private val cache = mutable.Map.empty[String, Canon]

  val descriptor: Descriptor =
    Cli
      .applicationField(world.application, "service")
      .collect { case Canon.R(d) => d }
      .flatMap(world.cas.get)
      .map(a => Service.readDescriptor(a.body))
      .getOrElse(Descriptor(worldDir.getFileName.toString, Map.empty, Map.empty, None, Vector.empty, Vector.empty))

  def bindingForUri(uri: String): Option[Binding] =
    val path = uri.stripPrefix("file://")
    descriptor.bindings.find(b => b.extensions.exists(path.endsWith))

  /** Runs a judgment of the world over buffer text that exists on no disk. */
  private def call(judgment: String, language: String, args: Vector[Canon]): Either[String, Canon] =
    val goal = Canon.Node(
      "call",
      Canon.Sym(judgment) +: Canon.node("grammar", Canon.Sym(language)) +: args
    )
    val key = Canon.digest(goal).hex
    cache.getOrElseUpdate(key, Cli.deriveIn(world, root, goal, world.budget)) match
      case Canon.Node("verdict", Vector(Canon.Sym("ok"), value, _)) => Right(value)
      case Canon.Node("verdict", Vector(Canon.Sym("error"), Canon.Sym(kind), Canon.S(m), _)) =>
        Left(s"$kind: $m")
      case other => Left(CanonText.write(other))

  private def buffer(t: String): Canon = Canon.node("q", Canon.S(t))

  private def records(judgment: String, language: String, args: Vector[Canon]): Either[String, Vector[Canon]] =
    call(judgment, language, args).map(Service.list)

  def diagnostics(language: String, text: String): Either[String, Vector[Canon]] =
    records("ServiceDiagnostics", language, Vector(buffer(text)))

  def symbols(language: String, text: String): Either[String, Vector[Canon]] =
    records("ServiceSymbols", language, Vector(buffer(text)))

  def completions(language: String, text: String): Either[String, Vector[Canon]] =
    records("ServiceCompletions", language, Vector(buffer(text)))

  /** The grammar's own token classes, located in the buffer. */
  def tokens(language: String, text: String): Vector[Canon] =
    records("ServiceSemanticTokens", language, Vector(buffer(text))).getOrElse(Vector.empty)

  /** The arrangement the deployed profile describes. */
  def layout(language: String): Option[Canon] =
    call("ServiceLayout", language, Vector.empty).toOption

  /** The documents the world declares, named as the world names them. */
  private def declaredDocuments: Canon =
    val entries = Cli
      .applicationField(world.application, "documents")
      .collect { case Canon.M(items) => items }
      .getOrElse(Vector.empty)
      .collect { case (Canon.Sym(name), reference @ Canon.R(_)) =>
        Canon.M(Vector(Canon.Sym("name") -> Canon.Sym(name), Canon.Sym("source") -> reference))
      }
    Canon.node("q", Canon.L(entries))

  /** What the world contains, rather than what one buffer contains. */
  def catalogue(language: String): Vector[Canon] =
    records("ServiceCatalogue", language, Vector(declaredDocuments)).getOrElse(Vector.empty)

  /** The document as an actual PDF, through the world's projection language. */
  def pdf(language: String, text: String): String =
    call("ServicePdf", language, Vector(buffer(text))) match
      case Right(Canon.S(document)) => document
      case _                        => ""

  /** The rendered document, for a preview beside the source it came from. */
  def preview(language: String, text: String): Vector[Canon] =
    records("ServicePreview", language, Vector(buffer(text))).getOrElse(Vector.empty)

  def patterns(language: String): Vector[Canon] =
    records("ServiceTokenPatterns", language, Vector.empty).getOrElse(Vector.empty)

  def comments(language: String): Vector[String] =
    call("ServiceCommentPatterns", language, Vector.empty)
      .map(v => Service.list(v).collect { case Canon.S(s) => s })
      .getOrElse(Vector.empty)

  def keywords(language: String): Vector[String] =
    call("ServiceKeywords", language, Vector.empty)
      .map(v => Service.list(v).collect { case Canon.S(s) => s })
      .getOrElse(Vector.empty)

  def format(language: String, text: String): Either[String, Option[String]] =
    call("ServiceFormat", language, Vector(buffer(text))).map {
      case Canon.Node("some", Vector(Canon.S(printed))) => Some(printed)
      case _                                            => None
    }

  def act(language: String, text: String, name: String): Either[String, String] =
    call("ServiceCommand", language, Vector(buffer(text), Canon.node("q", Canon.Sym(name)))).map {
      case Canon.Node("ok", Vector(v))     => Service.text(v)
      case Canon.Node("denied", Vector(v)) => Service.text(v)
      case other                           => CanonText.write(other)
    }

  /** The named views the deployed profile publishes, already rendered to text. */
  def views(language: String, text: String): Either[String, Vector[(String, Vector[String])]] =
    call("ServiceViews", language, Vector(buffer(text))).map {
      case Canon.M(entries) =>
        entries.collect { case (Canon.Sym(name), value) =>
          name -> Service.list(value).map(Service.text)
        }
      case _ => Vector.empty
    }

object Service:

  def load(root: Path, dir: Path): Either[String, Service] =
    Cli.loadFoundation(root, dir).map(f => Service(root, dir, f))

  /** The editor identifier for each binding, derived from what it is called. */
  def identifiers(descriptor: Descriptor): Map[String, String] =
    val taken = scala.collection.mutable.Set.empty[String]
    descriptor.bindings.map { b =>
      val base = if b.label.isEmpty then b.name else b.label.toLowerCase.replace(' ', '-')
      val id = if taken.contains(base) then b.name else base
      taken += id
      b.name -> id
    }.toMap

  def list(c: Canon): Vector[Canon] = c match
    case Canon.L(items) => items
    case _              => Vector.empty

  def text(c: Canon): String = c match
    case Canon.S(s)   => s
    case Canon.Sym(s) => s
    case Canon.N(n)   => n.toString
    case other        => CanonText.write(other)

  /** Reads a keyed field. The adapter never matches a tag of the world. */
  def get(c: Canon, key: String): Option[Canon] = c match
    case Canon.M(entries) => entries.collectFirst { case (Canon.Sym(k), v) if k == key => v }
    case _                => None

  def string(c: Canon, key: String, fallback: String = ""): String =
    get(c, key).map(text).getOrElse(fallback)

  def flag(c: Canon, key: String): Boolean =
    get(c, key) match
      case Some(Canon.B(value)) => value
      case _                    => false

  def number(c: Canon, key: String): Int =
    get(c, key) match
      case Some(Canon.N(n)) => n.toInt
      case _                => 0

  def strings(c: Canon, key: String): Vector[String] =
    get(c, key).map(list).getOrElse(Vector.empty).collect { case Canon.S(s) => s }

  def readDescriptor(body: Canon): Descriptor =
    val bindings = get(body, "languages").map(list).getOrElse(Vector.empty).map { entry =>
      Binding(
        name = string(entry, "name"),
        label = string(entry, "label"),
        extensions = strings(entry, "extensions"),
        comment = get(entry, "comment").map(text)
      )
    }
    val actions = get(body, "commands").map(list).getOrElse(Vector.empty).map { entry =>
      Action(name = string(entry, "name"), title = string(entry, "title"))
    }
    val editor = get(body, "editor") match
      case Some(Canon.M(entries)) =>
        entries.collect { case (Canon.Sym(k), v) => k -> text(v) }.toMap
      case _ => Map.empty[String, String]
    val placements = get(body, "placements") match
      case Some(Canon.M(entries)) =>
        entries.collect { case (Canon.Sym(k), v) => k -> text(v) }.toMap
      case _ => Map.empty[String, String]
    val navigator = get(body, "navigator").map { n =>
      Navigator(
        string(n, "name", "catalogue"),
        string(n, "title", "Catalogue"),
        string(n, "placement", "left"),
        flag(n, "reveal")
      )
    }
    Descriptor(
      string(body, "name", "stratum"),
      editor,
      placements,
      navigator,
      bindings.filter(_.name.nonEmpty),
      actions
    )

  /** Line starts, so a character offset can be reported as line and character. */
  final class Lines(text: String):
    private val starts: Vector[Int] =
      val b = Vector.newBuilder[Int]
      b += 0
      var i = 0
      while i < text.length do
        if text.charAt(i) == '\n' then b += i + 1
        i += 1
      b.result()

    def positionOf(offset: Int): (Int, Int) =
      val clamped = math.max(0, math.min(offset, text.length))
      var lo = 0
      var hi = starts.length - 1
      while lo < hi do
        val mid = (lo + hi + 1) / 2
        if starts(mid) <= clamped then lo = mid else hi = mid - 1
      (lo, clamped - starts(lo))

    def offsetOf(line: Int, character: Int): Int =
      if line < 0 then 0
      else if line >= starts.length then text.length
      else math.min(starts(line) + math.max(0, character), text.length)
