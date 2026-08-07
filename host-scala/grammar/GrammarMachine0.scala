package stratum.grammar

import stratum.canon.{Canon, CanonText}

import java.util.regex.Pattern
import scala.collection.mutable

/**
 * GrammarMachine0 interprets canonical grammar artifacts.
 *
 * It contains no syntax for any particular language. Tokens, categories,
 * constructors, precedence and associativity all arrive as data.
 */
object GrammarMachine0:

  final case class TokenDef(name: String, kind: String, pattern: Pattern)

  sealed trait Elem
  final case class Kw(text: String) extends Elem
  final case class Bind(field: String, target: String) extends Elem

  sealed trait Prod:
    def category: String
  final case class ProdBuild(tag: String, elems: Vector[Elem], category: String) extends Prod
  final case class ProdPass(target: String, category: String) extends Prod
  final case class ProdFold(tag: String, target: String, category: String) extends Prod
  final case class ProdParen(open: String, target: String, close: String, category: String) extends Prod

  final case class Grammar(
      name: String,
      start: String,
      tokens: Vector[TokenDef],
      skips: Vector[Pattern],
      categories: Vector[(String, Vector[Prod])],
      layout: Boolean = false
  ):
    val categoryIndex: Map[String, Int] = categories.map(_._1).zipWithIndex.toMap
    val productions: Map[String, Vector[Prod]] = categories.toMap

    val keywords: Vector[String] =
      categories
        .flatMap(_._2)
        .flatMap {
          case ProdBuild(_, elems, _)     => elems.collect { case Kw(t) => t }
          case ProdParen(o, _, c, _)      => Vector(o, c)
          case _                          => Vector.empty
        }
        .distinct
        .sortBy(k => -k.length)

    val tokenNames: Set[String] = tokens.map(_.name).toSet

    /** Categories reachable from `cat` through transparent productions. */
    val reachable: Map[String, Set[String]] =
      val direct = categories.map { (cat, prods) =>
        cat -> prods.collect {
          case ProdPass(t, _)      => t
          case ProdFold(_, t, _)   => t
          case ProdParen(_, t, _, _) => t
        }.toSet
      }.toMap
      val result = mutable.HashMap.empty[String, Set[String]]
      categories.map(_._1).foreach { cat =>
        val seen = mutable.LinkedHashSet.empty[String]
        val stack = mutable.Stack(cat)
        while stack.nonEmpty do
          val c = stack.pop()
          if !seen.contains(c) then
            seen += c
            direct.getOrElse(c, Set.empty).foreach(stack.push)
        result.put(cat, seen.toSet)
      }
      result.toMap

    /** Categories reachable without descending through a bracketing production. */
    val reachableOpen: Map[String, Set[String]] =
      val direct = categories.map { (cat, prods) =>
        cat -> prods.collect {
          case ProdPass(t, _)    => t
          case ProdFold(_, t, _) => t
        }.toSet
      }.toMap
      val result = mutable.HashMap.empty[String, Set[String]]
      categories.map(_._1).foreach { cat =>
        val seen = mutable.LinkedHashSet.empty[String]
        val stack = mutable.Stack(cat)
        while stack.nonEmpty do
          val c = stack.pop()
          if !seen.contains(c) then
            seen += c
            direct.getOrElse(c, Set.empty).foreach(stack.push)
        result.put(cat, seen.toSet)
      }
      result.toMap

    val tagProduction: Map[String, ProdBuild | ProdFold] =
      val m = mutable.LinkedHashMap.empty[String, ProdBuild | ProdFold]
      categories.foreach { (_, prods) =>
        prods.foreach {
          case p: ProdBuild => m.getOrElseUpdate(p.tag, p)
          case p: ProdFold  => m.getOrElseUpdate(p.tag, p)
          case _            => ()
        }
      }
      m.toMap

    /**
     * Whether this grammar declares a token whose own pattern can match
     * plain whitespace (YAML's `spaces`, for instance). Such a grammar never
     * lets the lexer silently skip whitespace -- every run of it becomes a
     * real, explicit token the tree captures -- so the printer must never
     * invent a separating space of its own: on reparse that invented space
     * would be lexed right back in as one more token the original tree
     * never had, breaking the fixpoint. Grammars with no such token (the
     * ordinary case) keep whitespace insignificant, so the printer's own
     * single-space join is exactly the harmless, purely-cosmetic default it
     * always was.
     */
    val explicitWhitespace: Boolean =
      tokens.exists { td =>
        val m = td.pattern.matcher(" ")
        m.lookingAt()
      }

  // ----------------------------------------------------------------- load

  def load(c: Canon): Either[String, Grammar] =
    c match
      case Canon.Node("grammar", entries) =>
        var name = ""
        var start = ""
        var layout = false
        val tokens = mutable.ArrayBuffer.empty[TokenDef]
        val skips = mutable.ArrayBuffer.empty[Pattern]
        val categories = mutable.ArrayBuffer.empty[(String, Vector[Prod])]
        var error: Option[String] = None

        entries.foreach {
          case Canon.Node("name", Vector(Canon.S(n)))    => name = n
          case Canon.Node("start", Vector(Canon.Sym(s))) => start = s
          case Canon.Node("skip", Vector(Canon.S(regex))) => skips += Pattern.compile(regex)
          // `token layout ...` is a reserved marker, not a real token: a
          // grammar opts into the off-side rule by declaring it, using
          // syntax the bootstrap grammar-of-grammars already has, so this
          // needs no change to the grammar DSL itself (which foundations
          // predating this feature have already frozen a copy of).
          case Canon.Node("token", Vector(Canon.Sym("layout"), Canon.Sym(_), Canon.S(_))) =>
            layout = true
          case Canon.Node("token", Vector(Canon.Sym(tn), Canon.Sym(kind), Canon.S(regex))) =>
            if !Set("sym", "str", "nat").contains(kind) then error = Some(s"unknown token kind $kind")
            else tokens += TokenDef(tn, kind, Pattern.compile(regex))
          case Canon.Node("category", Canon.Sym(cat) +: prods) =>
            val parsed = prods.map(loadProd(cat, _))
            parsed.collectFirst { case Left(m) => m } match
              case Some(m) => error = Some(m)
              case None    => categories += (cat -> parsed.collect { case Right(p) => p })
          case other => error = Some(s"unknown grammar entry: ${CanonText.write(other)}")
        }

        error match
          case Some(m) => Left(m)
          case None =>
            if start.isEmpty then Left("grammar has no start category")
            else Right(Grammar(name, start, tokens.toVector, skips.toVector, categories.toVector, layout))
      case other => Left(s"not a grammar: ${CanonText.write(other)}")

  private def loadProd(cat: String, c: Canon): Either[String, Prod] = c match
    case Canon.Node("prod", Vector(Canon.Sym(tag), Canon.L(elems))) =>
      val parsed = elems.map(loadElem)
      parsed.collectFirst { case Left(m) => m } match
        case Some(m) => Left(m)
        case None    => Right(ProdBuild(tag, parsed.collect { case Right(e) => e }, cat))
    case Canon.Node("pass", Vector(Canon.Sym(target))) => Right(ProdPass(target, cat))
    case Canon.Node("fold", Vector(Canon.Sym(tag), Canon.Sym(target))) => Right(ProdFold(tag, target, cat))
    case Canon.Node("paren", Vector(Canon.S(open), Canon.Sym(target), Canon.S(close))) =>
      Right(ProdParen(open, target, close, cat))
    case other => Left(s"unknown production: ${CanonText.write(other)}")

  private def loadElem(c: Canon): Either[String, Elem] = c match
    case Canon.Node("kw", Vector(Canon.S(t)))                        => Right(Kw(t))
    case Canon.Node("bind", Vector(Canon.Sym(f), Canon.Sym(target))) => Right(Bind(f, target))
    case other => Left(s"unknown grammar element: ${CanonText.write(other)}")

  // ----------------------------------------------------------------- lex

  final case class Token(kind: String, text: String, value: Canon, offset: Int)

  def lex(g: Grammar, input: String): Either[String, Vector[Token]] =
    val out = mutable.ArrayBuffer.empty[Token]
    var i = 0
    while i < input.length do
      if i == 0 && input(i) == '\uFEFF' then i += 1
      val explicitWhitespaceToken =
        input(i).isWhitespace && g.tokens.exists { token =>
          val matcher = token.pattern.matcher(input)
          matcher.region(i, input.length)
          matcher.lookingAt() && matcher.end() > i
        }
      if input(i).isWhitespace && !explicitWhitespaceToken then i += 1
      else
        var skipped = 0
        nestedBlockCommentLength(g, input, i).foreach(length => skipped = length)
        g.skips.foreach { s =>
          val m = s.matcher(input)
          m.region(i, input.length)
          if m.lookingAt() && m.end() - i > skipped then skipped = m.end() - i
        }
        if skipped > 0 then i += skipped
        else
          var bestLen = 0
          var best: Option[Token] = None
          g.tokens.foreach { td =>
            val m = td.pattern.matcher(input)
            m.region(i, input.length)
            if m.lookingAt() then
              val text = m.group()
              if text.length > bestLen then
                bestLen = text.length
                val value = td.kind match
                  case "sym" => Canon.Sym(text)
                  case "str" => Canon.S(text)
                  case "nat" => Canon.N(BigInt(text))
                best = Some(Token(td.name, text, value, i))
          }
          g.keywords.foreach { kw =>
            if kw.length >= bestLen && input.startsWith(kw, i) then
              bestLen = kw.length
              best = Some(Token("kw", kw, Canon.S(kw), i))
          }
          best match
            case Some(t) =>
              out += t
              i += bestLen
            case None => return Left(s"unexpected character '${input(i)}' at offset $i")
    Right(if g.layout then applyLayout(out.toVector, input) else out.toVector)

  private def nestedBlockCommentLength(g: Grammar, input: String, offset: Int): Option[Int] =
    def hasBlockCommentSkip: Boolean =
      g.skips.exists { pattern =>
        val sample = "/*x*/"
        val matcher = pattern.matcher(sample)
        matcher.lookingAt() && matcher.end() == sample.length
      }

    if !hasBlockCommentSkip || offset + 1 >= input.length || input(offset) != '/' || input(offset + 1) != '*' then None
    else
      var index = offset + 2
      var depth = 1
      while index + 1 < input.length && depth > 0 do
        if input(index) == '/' && input(index + 1) == '*' then
          depth += 1
          index += 2
        else if input(index) == '*' && input(index + 1) == '/' then
          depth -= 1
          index += 2
        else index += 1
      if depth == 0 then Some(index - offset) else None

  /**
   * The off-side rule, applied once as a token-stream rewrite rather than
   * threaded through the character-level lexer: whenever a real newline is
   * followed (after any blank lines) by content at a deeper column than the
   * enclosing block, splice in an INDENT; whenever it's shallower, splice in
   * one DEDENT per level given up. A grammar with `layout` can then treat
   * INDENT/DEDENT as ordinary keyword literals -- exactly like `{`/`}` -- so
   * the parser never has to compare indentation depths itself; the lexer
   * already turned the off-side rule into brackets.
   */
  private val IndentText = "INDENT"
  private val DedentText = "DEDENT"
  private val LayoutOpeners = Set("(", "[")
  private val LayoutClosers = Set(")", "]")

  private def columnOf(input: String, offset: Int): Int =
    if offset <= 0 then 0
    else
      val lastNewline = input.lastIndexOf('\n', offset - 1)
      if lastNewline < 0 then offset else offset - lastNewline - 1

  private def applyLayout(tokens: Vector[Token], input: String): Vector[Token] =
    val out = mutable.ArrayBuffer.empty[Token]
    val stack = mutable.ArrayBuffer(0)
    var bracketDepth = 0

    def indentAt(offset: Int): Unit = out += Token("kw", IndentText, Canon.S(IndentText), offset)
    def dedentAt(offset: Int): Unit = out += Token("kw", DedentText, Canon.S(DedentText), offset)

    if tokens.nonEmpty then
      val col = columnOf(input, tokens.head.offset)
      if col > stack.last then
        stack += col
        indentAt(tokens.head.offset)

    var i = 0
    while i < tokens.length do
      val t = tokens(i)
      out += t
      if t.kind == "kw" then
        if LayoutOpeners.contains(t.text) then bracketDepth += 1
        else if LayoutClosers.contains(t.text) && bracketDepth > 0 then bracketDepth -= 1
      // A token counts as ending its line if its text ends in a real
      // newline -- true for the `newline` token kind, but also for any
      // token whose own pattern absorbs a trailing newline (for example
      // Scala's line comments, which do exactly that to keep two
      // consecutive comments from merging when the printer re-joins them).
      if t.text.endsWith("\n") then
        var j = i + 1
        // Blank lines carry no structure of their own -- only the line
        // that follows them matters for the indent/dedent comparison --
        // so their newline tokens are dropped rather than passed through,
        // sparing every grammar from having to consume one blank-line
        // token per blank line just to keep matching.
        while j < tokens.length && tokens(j).kind == "newline" do
          j += 1
        if j < tokens.length && bracketDepth == 0 then
          val col = columnOf(input, tokens(j).offset)
          if col > stack.last then
            stack += col
            indentAt(tokens(j).offset)
          else
            while stack.length > 1 && col < stack.last do
              stack.remove(stack.length - 1)
              dedentAt(tokens(j).offset)
        i = j
      else
        i += 1

    while stack.length > 1 do
      stack.remove(stack.length - 1)
      dedentAt(input.length)

    out.toVector

  // --------------------------------------------------------------- parse

  def parse(g: Grammar, input: String): Either[String, Canon] =
    lex(g, input).flatMap { tokens =>
      val p = new Parser(g, tokens)
      p.parseCategory(g.start) match
        case Some(v) if p.pos == tokens.length => Right(v)
        case Some(_) => Left(s"unconsumed input at offset ${tokens(p.pos).offset}")
        case None =>
          val at = if p.furthest < tokens.length then s"offset ${tokens(p.furthest).offset}" else "end of input"
          Left(s"parse error at $at")
    }

  private final class Parser(g: Grammar, tokens: Vector[Token]):
    var pos = 0
    var furthest = 0

    // Packrat memoization: without it, a category tried at the same
    // position by several sibling alternatives that share a prefix (for
    // example Scala's several `header : headerDocumentTyped ...` block
    // forms) re-parses that whole shared prefix once per sibling, and that
    // cost multiplies with nesting depth -- deeply nested real code can
    // make an unmemoized parse take minutes. Parsing a given category from
    // a given position is a pure function of (pos, cat) here (no external
    // state affects it), so caching the outcome is behavior-preserving and
    // turns that multiplicative blowup back into linear-ish work.
    private val memo = mutable.HashMap.empty[(Int, String), Option[(Canon, Int)]]

    private def advance(): Unit =
      pos += 1
      if pos > furthest then furthest = pos

    def parseCategory(cat: String): Option[Canon] =
      val key = (pos, cat)
      memo.get(key) match
        case Some(Some((v, endPos))) => pos = endPos; return Some(v)
        case Some(None)              => return None
        case None                    => ()
      val start = pos
      val prods = g.productions.getOrElse(cat, Vector.empty)
      var i = 0
      while i < prods.length do
        val save = pos
        parseProd(prods(i)) match
          case Some(v) =>
            memo((start, cat)) = Some((v, pos))
            return Some(v)
          case None => pos = save
        i += 1
      memo((start, cat)) = None
      None

    private def parseProd(p: Prod): Option[Canon] = p match
      case ProdBuild(tag, elems, _) =>
        val bound = mutable.ArrayBuffer.empty[Canon]
        var ok = true
        var i = 0
        while ok && i < elems.length do
          elems(i) match
            case Kw(text) =>
              if pos < tokens.length && tokens(pos).kind == "kw" && tokens(pos).text == text then advance()
              else ok = false
            case Bind(_, target) =>
              parseTarget(target) match
                case Some(v) => bound += v
                case None    => ok = false
          i += 1
        if ok then Some(Canon.Node(tag, bound.toVector)) else None

      case ProdPass(target, _) => parseTarget(target)

      case ProdFold(tag, target, _) =>
        parseTarget(target).map { first =>
          var acc = first
          var go = true
          while go do
            val save = pos
            parseTarget(target) match
              case Some(next) => acc = Canon.Node(tag, Vector(acc, next))
              case None =>
                pos = save
                go = false
          acc
        }

      case ProdParen(open, target, close, _) =>
        if pos < tokens.length && tokens(pos).kind == "kw" && tokens(pos).text == open then
          advance()
          parseTarget(target).flatMap { inner =>
            if pos < tokens.length && tokens(pos).kind == "kw" && tokens(pos).text == close then
              advance()
              Some(inner)
            else None
          }
        else None

    private def parseTarget(target: String): Option[Canon] =
      if g.tokenNames.contains(target) then
        if pos < tokens.length && tokens(pos).kind == target then
          val v = tokens(pos).value
          advance()
          Some(v)
        else None
      else parseCategory(target)

  // --------------------------------------------------------------- print

  def print(g: Grammar, value: Canon): Either[String, String] =
    val out = mutable.ArrayBuffer.empty[Piece]
    try
      emit(g, value, g.start, out)
      Right(join(out.toVector, g.explicitWhitespace))
    catch case e: IllegalArgumentException => Left(e.getMessage)

  private val closers = Set(")", "]", "}", ".", ",", ";", ":")
  private val openers = Set("(", "[", "{")

  /**
   * A printed piece of text, tagged with whether it came from a literal
   * keyword in a production (a real structural delimiter the grammar wrote
   * into the .grammar file, like Brace's `{`) versus an arbitrary captured
   * token value (whatever a language's own free-form token, like `word`,
   * happened to match). The bracket/punctuation spacing rules below only
   * make sense for the former: a bound token's text can coincidentally equal
   * "{" (for example Markdown prose containing a literal brace character)
   * without meaning "structural opener", and treating it as one would
   * suppress a separating space that the source actually had -- printing
   * `{targetKey` for `{ targetKey`, which then relexes as one word.
   */
  private final case class Piece(text: String, literal: Boolean)

  /**
   * A literal newline ends its line; nothing prints a space before the next
   * token, or every following line would open with a spurious leading space
   * that a line-oriented grammar's own whitespace tokens could then capture
   * as structure (for example YAML's leading `indent`).
   */
  /**
   * INDENT/DEDENT never print as literal text: they adjust a running depth,
   * and the token right after a real newline is prefixed with that many
   * levels of indent instead of the "no space" join a newline otherwise
   * gets. Grammars that never emit these two tokens keep level at 0, where
   * `"  " * 0` is the empty string this join already produced -- so this is
   * behaviorally identical to the original for every existing grammar.
   */
  private def join(pieces: Vector[Piece], explicitWhitespace: Boolean): String =
    val sb = new StringBuilder
    var level = 0
    var prev: Option[Piece] = None
    pieces.foreach { piece =>
      val t = piece.text
      if piece.literal && t == IndentText then level += 1
      else if piece.literal && t == DedentText then level = math.max(0, level - 1)
      else
        prev match
          case None                                          => sb.append(t)
          // A closer that starts a fresh line (for example a `}` closing an
          // indented block, or a fluent call chain's leading `.`) still
          // needs its line's indentation -- only a closer joining the *same*
          // line skips the separating space, so this check must come after
          // the newline case, not before it. "Ends with" rather than
          // exact-equals, because a line comment's own text carries its
          // trailing newline (see the matching note on the layout trigger
          // above) rather than that newline being a separate token.
          case Some(p) if p.text.endsWith("\n")              => sb.append("  " * level).append(t)
          // A token that is itself entirely whitespace (for example YAML's
          // `spaces` atom, which carries its literal run of indentation or
          // separator spaces as its own text) already provides whatever
          // separation is needed on either side of it. Adding this join's
          // own separating space in front of it, or in front of whatever
          // follows it, would let two whitespace-only sources stack -- one
          // real space becomes three once reparsed, since the extra spaces
          // this join inserted get lexed right back into the token's own
          // run. So a whitespace-only token joins directly onto its
          // neighbour on either side, same as a closer/opener would.
          case Some(p) if isAllWhitespace(t) || isAllWhitespace(p.text) => sb.append(t)
          case Some(p) if (piece.literal && closers.contains(t)) || (p.literal && openers.contains(p.text)) =>
            sb.append(t)
          // A grammar with explicit whitespace tokens (see `explicitWhitespace`
          // above) never has insignificant space for this join to safely
          // invent: whatever separation two neighbouring pieces need must
          // already be represented by an atom somewhere in the tree (a
          // `Space`, a `newline`, ...). Falling through to the ordinary
          // single-space join here would add a space the tree never asked
          // for, and reparsing would lex it right back in as a real,
          // unwanted token.
          case Some(_) if explicitWhitespace                => sb.append(t)
          case Some(_)                                       => sb.append(' ').append(t)
        prev = Some(piece)
    }
    sb.toString

  private def isAllWhitespace(text: String): Boolean =
    text.nonEmpty && text.forall(_.isWhitespace)

  private def emit(g: Grammar, value: Canon, cat: String, out: mutable.ArrayBuffer[Piece]): Unit =
    value match
      case Canon.Node(tag, args) =>
        g.tagProduction.get(tag) match
          case None => throw IllegalArgumentException(s"no production prints node tag $tag")
          case Some(prod) =>
            val prodCat = prod match
              case p: ProdBuild => p.category
              case p: ProdFold  => p.category
            if g.reachableOpen.getOrElse(cat, Set.empty).contains(prodCat) then emitProd(g, prod, args, out)
            else
              findParen(g, cat) match
                case Some(ProdParen(open, target, close, _)) =>
                  out += Piece(open, literal = true)
                  emit(g, value, target, out)
                  out += Piece(close, literal = true)
                case _ =>
                  throw IllegalArgumentException(s"cannot print $tag in category $cat without a bracketing production")
      case other => out += Piece(primitiveText(other), literal = false)

  private def findParen(g: Grammar, cat: String): Option[ProdParen] =
    val reach = g.reachable.getOrElse(cat, Set(cat))
    g.categories
      .filter((c, _) => reach.contains(c))
      .flatMap(_._2)
      .collectFirst { case p: ProdParen => p }

  private def emitProd(
      g: Grammar,
      prod: ProdBuild | ProdFold,
      args: Vector[Canon],
      out: mutable.ArrayBuffer[Piece]
  ): Unit =
    prod match
      case ProdBuild(tag, elems, _) =>
        val binds = elems.collect { case b: Bind => b }
        if binds.length != args.length then
          throw IllegalArgumentException(s"node $tag has ${args.length} arguments but production binds ${binds.length}")
        var bindIndex = 0
        elems.foreach {
          case Kw(text) => out += Piece(text, literal = true)
          case Bind(_, target) =>
            val v = args(bindIndex)
            bindIndex += 1
            if g.tokenNames.contains(target) then out += Piece(primitiveText(v), literal = false)
            else emit(g, v, target, out)
        }
      case ProdFold(tag, target, cat) =>
        if args.length != 2 then throw IllegalArgumentException(s"fold node $tag must have two arguments")
        emit(g, args(0), cat, out)
        emit(g, args(1), target, out)

  private def primitiveText(c: Canon): String = c match
    case Canon.Sym(s) => s
    case Canon.S(s)   => s
    case Canon.N(n)   => n.toString
    case other        => throw IllegalArgumentException(s"cannot print primitive ${CanonText.write(other)}")
