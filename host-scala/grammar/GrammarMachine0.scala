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
      categories: Vector[(String, Vector[Prod])]
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

  // ----------------------------------------------------------------- load

  def load(c: Canon): Either[String, Grammar] =
    c match
      case Canon.Node("grammar", entries) =>
        var name = ""
        var start = ""
        val tokens = mutable.ArrayBuffer.empty[TokenDef]
        val skips = mutable.ArrayBuffer.empty[Pattern]
        val categories = mutable.ArrayBuffer.empty[(String, Vector[Prod])]
        var error: Option[String] = None

        entries.foreach {
          case Canon.Node("name", Vector(Canon.S(n)))    => name = n
          case Canon.Node("start", Vector(Canon.Sym(s))) => start = s
          case Canon.Node("skip", Vector(Canon.S(regex))) => skips += Pattern.compile(regex)
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
            else Right(Grammar(name, start, tokens.toVector, skips.toVector, categories.toVector))
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
      if input(i).isWhitespace then i += 1
      else
        var skipped = 0
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
    Right(out.toVector)

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

    private def advance(): Unit =
      pos += 1
      if pos > furthest then furthest = pos

    def parseCategory(cat: String): Option[Canon] =
      val prods = g.productions.getOrElse(cat, Vector.empty)
      var i = 0
      while i < prods.length do
        val save = pos
        parseProd(prods(i)) match
          case Some(v) => return Some(v)
          case None    => pos = save
        i += 1
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
    val out = mutable.ArrayBuffer.empty[String]
    try
      emit(g, value, g.start, out)
      Right(join(out.toVector))
    catch case e: IllegalArgumentException => Left(e.getMessage)

  private val closers = Set(")", "]", "}", ".", ",", ";")
  private val openers = Set("(", "[", "{")

  private def join(tokens: Vector[String]): String =
    val sb = new StringBuilder
    tokens.zipWithIndex.foreach { (t, i) =>
      if i == 0 then sb.append(t)
      else
        val prev = tokens(i - 1)
        if closers.contains(t) || openers.contains(prev) then sb.append(t)
        else sb.append(' ').append(t)
    }
    sb.toString

  private def emit(g: Grammar, value: Canon, cat: String, out: mutable.ArrayBuffer[String]): Unit =
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
                  out += open
                  emit(g, value, target, out)
                  out += close
                case _ =>
                  throw IllegalArgumentException(s"cannot print $tag in category $cat without a bracketing production")
      case other => out += primitiveText(other)

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
      out: mutable.ArrayBuffer[String]
  ): Unit =
    prod match
      case ProdBuild(tag, elems, _) =>
        val binds = elems.collect { case b: Bind => b }
        if binds.length != args.length then
          throw IllegalArgumentException(s"node $tag has ${args.length} arguments but production binds ${binds.length}")
        var bindIndex = 0
        elems.foreach {
          case Kw(text) => out += text
          case Bind(_, target) =>
            val v = args(bindIndex)
            bindIndex += 1
            if g.tokenNames.contains(target) then out += primitiveText(v)
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
