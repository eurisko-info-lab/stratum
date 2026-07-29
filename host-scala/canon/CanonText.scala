package stratum.canon

import scala.collection.mutable

/**
 * Canonical text form of Canon values.
 *
 * The text form is a transport for authoring and for deterministic transcripts.
 * The binary form remains the authoritative identity.
 */
object CanonText:

  final case class ParseError(message: String, line: Int) extends RuntimeException(s"$message (line $line)")

  // ------------------------------------------------------------------ read

  def read(input: String): Either[String, Canon] =
    try
      val lexer = new Lexer(input)
      val value = parseValue(lexer)
      lexer.skipTrivia()
      if !lexer.atEnd then Left(s"trailing input at line ${lexer.line}")
      else Right(value)
    catch case e: ParseError => Left(e.getMessage)

  def readAll(input: String): Either[String, Vector[Canon]] =
    try
      val lexer = new Lexer(input)
      val buf = mutable.ArrayBuffer.empty[Canon]
      lexer.skipTrivia()
      while !lexer.atEnd do
        buf += parseValue(lexer)
        lexer.skipTrivia()
      Right(buf.toVector)
    catch case e: ParseError => Left(e.getMessage)

  private final class Lexer(val src: String):
    var pos = 0
    var line = 1

    def atEnd: Boolean = pos >= src.length

    def peek: Char = if atEnd then '\u0000' else src(pos)

    def advance(): Char =
      val c = src(pos)
      pos += 1
      if c == '\n' then line += 1
      c

    def skipTrivia(): Unit =
      var go = true
      while go do
        go = false
        while !atEnd && peek.isWhitespace do
          advance(); go = true
        if !atEnd && peek == ';' then
          while !atEnd && peek != '\n' do advance()
          go = true

    def fail(msg: String): Nothing = throw ParseError(msg, line)

  private val delimiters = Set('(', ')', '[', ']', '{', '}', '"', ';')

  private def parseValue(lx: Lexer): Canon =
    lx.skipTrivia()
    if lx.atEnd then lx.fail("unexpected end of input")
    lx.peek match
      case '(' => parseNode(lx)
      case '[' => parseList(lx)
      case '{' => parseMap(lx)
      case '"' => Canon.S(parseString(lx))
      case '#' => parseHash(lx)
      case ')' | ']' | '}' => lx.fail(s"unexpected '${lx.peek}'")
      case _   => parseAtom(lx)

  private def parseNode(lx: Lexer): Canon =
    lx.advance()
    lx.skipTrivia()
    if lx.atEnd then lx.fail("unterminated node")
    val tag = parseValue(lx) match
      case Canon.Sym(s) => s
      case other        => lx.fail(s"node tag must be a symbol, found ${write(other)}")
    val args = mutable.ArrayBuffer.empty[Canon]
    lx.skipTrivia()
    while !lx.atEnd && lx.peek != ')' do
      args += parseValue(lx)
      lx.skipTrivia()
    if lx.atEnd then lx.fail("unterminated node")
    lx.advance()
    Canon.Node(tag, args.toVector)

  private def parseList(lx: Lexer): Canon =
    lx.advance()
    val items = mutable.ArrayBuffer.empty[Canon]
    lx.skipTrivia()
    while !lx.atEnd && lx.peek != ']' do
      items += parseValue(lx)
      lx.skipTrivia()
    if lx.atEnd then lx.fail("unterminated list")
    lx.advance()
    Canon.L(items.toVector)

  private def parseMap(lx: Lexer): Canon =
    lx.advance()
    val entries = mutable.ArrayBuffer.empty[(Canon, Canon)]
    lx.skipTrivia()
    while !lx.atEnd && lx.peek != '}' do
      val k = parseValue(lx)
      lx.skipTrivia()
      if lx.atEnd || lx.peek == '}' then lx.fail("map entry missing value")
      val v = parseValue(lx)
      entries += (k -> v)
      lx.skipTrivia()
    if lx.atEnd then lx.fail("unterminated map")
    lx.advance()
    val keys = entries.map(_._1)
    if keys.distinct.length != keys.length then lx.fail("duplicate map key")
    Canon.M(entries.toVector.sortWith((a, b) => Canon.compare(a._1, b._1) < 0))

  private def parseString(lx: Lexer): String =
    lx.advance()
    val sb = new StringBuilder
    var done = false
    while !done do
      if lx.atEnd then lx.fail("unterminated string")
      val c = lx.advance()
      if c == '"' then done = true
      else if c == '\\' then
        if lx.atEnd then lx.fail("unterminated escape")
        lx.advance() match
          case 'n'  => sb.append('\n')
          case 't'  => sb.append('\t')
          case 'r'  => sb.append('\r')
          case '\\' => sb.append('\\')
          case '"'  => sb.append('"')
          case 'u' =>
            val hex = (0 until 4).map(_ => lx.advance()).mkString
            sb.append(Integer.parseInt(hex, 16).toChar)
          case other => lx.fail(s"unknown escape \\$other")
      else sb.append(c)
    sb.toString

  private def parseHash(lx: Lexer): Canon =
    lx.advance()
    val token = readToken(lx)
    if token == "unit" then Canon.U
    else if token == "t" then Canon.B(true)
    else if token == "f" then Canon.B(false)
    else if token.startsWith("d") then
      Digest.fromHex(token.drop(1)) match
        case Right(d) => Canon.R(d)
        case Left(m)  => lx.fail(m)
    else if token.startsWith("x") then
      val hex = token.drop(1)
      if hex.length % 2 != 0 then lx.fail("odd-length byte literal")
      Canon.Y(hex.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toVector)
    else lx.fail(s"unknown # literal: #$token")

  private def readToken(lx: Lexer): String =
    val sb = new StringBuilder
    while !lx.atEnd && !lx.peek.isWhitespace && !delimiters.contains(lx.peek) do sb.append(lx.advance())
    sb.toString

  private def parseAtom(lx: Lexer): Canon =
    val token = readToken(lx)
    if token.isEmpty then lx.fail("empty token")
    if token.forall(_.isDigit) then Canon.N(BigInt(token))
    else if (token.startsWith("-") || token.startsWith("+")) && token.length > 1 && token.drop(1).forall(_.isDigit) then
      Canon.Z(BigInt(if token.startsWith("+") then token.drop(1) else token))
    else Canon.Sym(token)

  // ----------------------------------------------------------------- write

  def write(c: Canon): String =
    val sb = new StringBuilder
    writeTo(sb, c)
    sb.toString

  private def writeTo(sb: StringBuilder, c: Canon): Unit = c match
    case Canon.U       => sb.append("#unit")
    case Canon.B(v)    => sb.append(if v then "#t" else "#f")
    case Canon.N(v)    => sb.append(v.toString)
    case Canon.Z(v)    => sb.append(if v >= 0 then s"+$v" else v.toString)
    case Canon.Y(v)    => sb.append("#x").append(v.map(b => f"${b & 0xff}%02x").mkString)
    case Canon.S(v)    => writeString(sb, v)
    case Canon.Sym(v)  => sb.append(v)
    case Canon.R(d)    => sb.append(d.toString)
    case Canon.L(items) =>
      sb.append('[')
      items.zipWithIndex.foreach { (item, i) =>
        if i > 0 then sb.append(' ')
        writeTo(sb, item)
      }
      sb.append(']')
    case Canon.M(entries) =>
      sb.append('{')
      entries.zipWithIndex.foreach { case ((k, v), i) =>
        if i > 0 then sb.append(' ')
        writeTo(sb, k)
        sb.append(' ')
        writeTo(sb, v)
      }
      sb.append('}')
    case Canon.Node(tag, args) =>
      sb.append('(').append(tag)
      args.foreach { a =>
        sb.append(' ')
        writeTo(sb, a)
      }
      sb.append(')')

  private def writeString(sb: StringBuilder, s: String): Unit =
    sb.append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\t' => sb.append("\\t")
      case '\r' => sb.append("\\r")
      case c    => sb.append(c)
    }
    sb.append('"')

  /** Indented rendering used when writing authored artifacts to disk. */
  def pretty(c: Canon, indent: Int = 0): String =
    val pad = "  " * indent
    c match
      case Canon.Node(tag, args) if args.exists(isCompound) =>
        val head = s"$pad($tag"
        val body = args.map(a => pretty(a, indent + 1)).mkString("\n")
        s"$head\n$body)"
      case Canon.L(items) if items.exists(isCompound) =>
        val body = items.map(a => pretty(a, indent + 1)).mkString("\n")
        s"$pad[\n$body]"
      case other => pad + write(other)

  private def isCompound(c: Canon): Boolean = c match
    case Canon.Node(_, args) => args.nonEmpty
    case Canon.L(items)      => items.nonEmpty
    case Canon.M(entries)    => entries.nonEmpty
    case _                   => false
