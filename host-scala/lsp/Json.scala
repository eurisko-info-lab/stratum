package stratum.lsp

import scala.collection.mutable

/**
 * A minimal JSON value, parser and printer.
 *
 * The host takes no dependencies, so the editor protocol gets its own codec.
 * This is transport only: it carries no knowledge of any language and no
 * knowledge of canon.
 */
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: BigDecimal)
  case Str(value: String)
  case Arr(items: Vector[Json])
  case Obj(fields: Vector[(String, Json)])

object Json:

  def int(n: Long): Json = Num(BigDecimal(n))

  def obj(fields: (String, Json)*): Json = Obj(fields.toVector)

  def arr(items: Seq[Json]): Json = Arr(items.toVector)

  extension (j: Json)
    def field(name: String): Option[Json] = j match
      case Obj(fields) => fields.collectFirst { case (k, v) if k == name => v }
      case _           => None

    def / (name: String): Json = j.field(name).getOrElse(Null)

    def str: Option[String] = j match
      case Str(s) => Some(s)
      case _      => None

    def num: Option[BigDecimal] = j match
      case Num(n) => Some(n)
      case _      => None

    def items: Vector[Json] = j match
      case Arr(v) => v
      case _      => Vector.empty

    def isNull: Boolean = j == Null

  // ------------------------------------------------------------- printing

  def write(j: Json): String =
    val sb = StringBuilder()
    print(j, sb)
    sb.toString

  /**
   * The same value, laid out over lines. A manifest generated into the tree is
   * read by people and reviewed in diffs, so it is written the way it would be
   * written by hand: two spaces a level, one member or element to a line, and
   * an empty array or object kept on its own line rather than split.
   */
  def pretty(j: Json): String =
    val sb = StringBuilder()
    prettyPrint(j, 0, sb)
    sb.toString

  private def prettyPrint(j: Json, depth: Int, sb: StringBuilder): Unit =
    def pad(n: Int): Unit = sb.append("  " * n)
    j match
      case Arr(items) if items.isEmpty => sb.append("[]")
      case Obj(fields) if fields.isEmpty => sb.append("{}")
      case Arr(items) =>
        sb.append("[\n")
        var first = true
        items.foreach { i =>
          if !first then sb.append(",\n")
          first = false
          pad(depth + 1)
          prettyPrint(i, depth + 1, sb)
        }
        sb.append('\n')
        pad(depth)
        sb.append(']')
      case Obj(fields) =>
        sb.append("{\n")
        var first = true
        fields.foreach { (k, v) =>
          if !first then sb.append(",\n")
          first = false
          pad(depth + 1)
          quote(k, sb)
          sb.append(": ")
          prettyPrint(v, depth + 1, sb)
        }
        sb.append('\n')
        pad(depth)
        sb.append('}')
      case scalar => print(scalar, sb)

  private def print(j: Json, sb: StringBuilder): Unit = j match
    case Null       => sb.append("null")
    case Bool(b)    => sb.append(if b then "true" else "false")
    case Num(n)     => sb.append(if n.isValidLong then n.toLong.toString else n.toString)
    case Str(s)     => quote(s, sb)
    case Arr(items) =>
      sb.append('[')
      var first = true
      items.foreach { i =>
        if !first then sb.append(',')
        first = false
        print(i, sb)
      }
      sb.append(']')
    case Obj(fields) =>
      sb.append('{')
      var first = true
      fields.foreach { (k, v) =>
        if !first then sb.append(',')
        first = false
        quote(k, sb)
        sb.append(':')
        print(v, sb)
      }
      sb.append('}')

  private def quote(s: String, sb: StringBuilder): Unit =
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case _ =>
          if c < 0x20 then sb.append("\\u%04x".format(c.toInt))
          else sb.append(c)
      i += 1
    sb.append('"')

  // -------------------------------------------------------------- parsing

  def read(text: String): Either[String, Json] =
    val p = Parser(text)
    try
      p.skipWhitespace()
      val v = p.value()
      p.skipWhitespace()
      if p.pos != text.length then Left(s"trailing input at ${p.pos}") else Right(v)
    catch case e: IllegalArgumentException => Left(e.getMessage)

  private final class Parser(text: String):
    var pos = 0

    private def fail(message: String): Nothing =
      throw IllegalArgumentException(s"$message at $pos")

    def skipWhitespace(): Unit =
      while pos < text.length && text.charAt(pos).isWhitespace do pos += 1

    private def expect(c: Char): Unit =
      if pos < text.length && text.charAt(pos) == c then pos += 1
      else fail(s"expected $c")

    def value(): Json =
      skipWhitespace()
      if pos >= text.length then fail("unexpected end of input")
      else
        text.charAt(pos) match
          case '{' => obj()
          case '[' => array()
          case '"' => Json.Str(string())
          case 't' => literal("true", Json.Bool(true))
          case 'f' => literal("false", Json.Bool(false))
          case 'n' => literal("null", Json.Null)
          case _   => number()

    private def literal(word: String, v: Json): Json =
      if text.regionMatches(pos, word, 0, word.length) then
        pos += word.length
        v
      else fail(s"expected $word")

    private def obj(): Json =
      expect('{')
      val fields = mutable.ArrayBuffer.empty[(String, Json)]
      skipWhitespace()
      if pos < text.length && text.charAt(pos) == '}' then pos += 1
      else
        var more = true
        while more do
          skipWhitespace()
          val k = string()
          skipWhitespace()
          expect(':')
          fields += (k -> value())
          skipWhitespace()
          if pos < text.length && text.charAt(pos) == ',' then pos += 1
          else
            expect('}')
            more = false
      Json.Obj(fields.toVector)

    private def array(): Json =
      expect('[')
      val items = mutable.ArrayBuffer.empty[Json]
      skipWhitespace()
      if pos < text.length && text.charAt(pos) == ']' then pos += 1
      else
        var more = true
        while more do
          items += value()
          skipWhitespace()
          if pos < text.length && text.charAt(pos) == ',' then pos += 1
          else
            expect(']')
            more = false
      Json.Arr(items.toVector)

    private def string(): String =
      expect('"')
      val sb = StringBuilder()
      var done = false
      while !done do
        if pos >= text.length then fail("unterminated string")
        val c = text.charAt(pos)
        pos += 1
        if c == '"' then done = true
        else if c == '\\' then
          if pos >= text.length then fail("unterminated escape")
          val e = text.charAt(pos)
          pos += 1
          e match
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'b'  => sb.append('\b')
            case 'f'  => sb.append('\f')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u' =>
              if pos + 4 > text.length then fail("truncated unicode escape")
              val hex = text.substring(pos, pos + 4)
              pos += 4
              sb.append(Integer.parseInt(hex, 16).toChar)
            case other => fail(s"unknown escape $other")
        else sb.append(c)
      sb.toString

    private def number(): Json =
      val start = pos
      if pos < text.length && (text.charAt(pos) == '-' || text.charAt(pos) == '+') then pos += 1
      while pos < text.length && (text.charAt(pos).isDigit || "eE+-.".contains(text.charAt(pos))) do pos += 1
      if pos == start then fail("expected a value")
      else
        try Json.Num(BigDecimal(text.substring(start, pos)))
        catch case _: NumberFormatException => fail("malformed number")
