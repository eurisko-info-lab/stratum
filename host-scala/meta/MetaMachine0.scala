package stratum.meta

import stratum.artifact.Cas
import stratum.canon.{Canon, CanonText, Digest}
import stratum.cap.{CapabilityHandler, CapabilityRequest}

import scala.collection.mutable

/** An explicit resource budget. Exhaustion produces a canonical verdict, never an exception. */
final case class Budget(steps: Long, depth: Int):
  def toCanon: Canon =
    Canon.node("budget", Canon.node("steps", Canon.N(BigInt(steps))), Canon.node("depth", Canon.nat(depth)))

object Budget:
  val default: Budget = Budget(2000000L, 4000)

  def fromCanon(c: Canon): Either[String, Budget] = c match
    case Canon.Node("budget", args) =>
      var steps = default.steps
      var depth = default.depth
      args.foreach {
        case Canon.Node("steps", Vector(Canon.N(v))) => steps = v.toLong
        case Canon.Node("depth", Vector(Canon.N(v))) => depth = v.toInt
        case _                                       => ()
      }
      Right(Budget(steps, depth))
    case other => Left(s"not a budget: ${CanonText.write(other)}")

/** The Kernel constitution restricts which capabilities a derivation may request. */
final case class Kernel(allow: Set[String]):
  def toCanon: Canon =
    Canon.node("kernel", Canon.Node("allow", allow.toVector.sorted.map(Canon.Sym.apply)))

object Kernel:
  val pure: Kernel = Kernel(Set.empty)
  val readOnly: Kernel = Kernel(Set("cas-get", "cas-has", "hash", "digest-of-bytes", "fs-read", "fs-exists"))
  val full: Kernel = Kernel(
    Set(
      "cas-get", "cas-has", "cas-put", "hash", "digest-of-bytes", "fs-read", "fs-write", "fs-exists", "fs-list",
      "grammar-parse", "grammar-print", "sign", "verify", "public-key", "now", "random-bytes", "send", "receive",
      "open-connection", "close-connection", "pending"
    )
  )

  def fromCanon(c: Canon): Either[String, Kernel] = c match
    case Canon.Node("kernel", args) =>
      val allow = args.collect { case Canon.Node("allow", names) =>
        names.collect { case Canon.Sym(n) => n }
      }.flatten.toSet
      Right(Kernel(allow))
    case other => Left(s"not a kernel constitution: ${CanonText.write(other)}")

final case class Judgment(name: String, params: Vector[String], body: Canon)

final case class Program(judgments: Map[String, Judgment], modules: Vector[String]):
  def merge(other: Program): Program =
    Program(judgments ++ other.judgments, (modules ++ other.modules).distinct)

object Program:
  val empty: Program = Program(Map.empty, Vector.empty)

  /** Loads a program artifact, resolving `(use <ref>)` imports through the closure. */
  def load(c: Canon, cas: Cas): Either[String, Program] =
    val seen = mutable.HashSet.empty[String]
    def loop(v: Canon): Either[String, Program] = v match
      case Canon.Node("program", entries) =>
        entries.foldLeft[Either[String, Program]](Right(Program.empty)) { (acc, entry) =>
          acc.flatMap { prog =>
            entry match
              case Canon.Node("module", Vector(Canon.Sym(name))) =>
                Right(prog.copy(modules = prog.modules :+ name))
              case Canon.Node("judgment", Vector(Canon.Sym(name), Canon.L(params), body)) =>
                val ps = params.collect { case Canon.Sym(p) => p }
                if ps.length != params.length then Left(s"judgment $name has a non-symbol parameter")
                else Right(prog.copy(judgments = prog.judgments + (name -> Judgment(name, ps, body))))
              case Canon.Node("use", Vector(Canon.R(d))) =>
                if seen.contains(d.hex) then Right(prog)
                else
                  seen += d.hex
                  cas.get(d) match
                    case None    => Left(s"missing imported program artifact ${d.hex}")
                    case Some(a) => loop(a.body).map(imported => imported.merge(prog))
              case other => Left(s"unknown program entry: ${CanonText.write(other)}")
          }
        }
      case other => Left(s"not a meta program: ${CanonText.write(other)}")
    loop(c)

final case class Evidence(steps: Long, calls: Map[String, Long], capabilities: Map[String, Long]):
  def toCanon: Canon =
    def counts(m: Map[String, Long]): Canon =
      Canon.M(m.toVector.sortBy(_._1).map((k, v) => Canon.Sym(k) -> Canon.N(BigInt(v))))
    Canon.node(
      "evidence",
      Canon.node("steps", Canon.N(BigInt(steps))),
      Canon.node("calls", counts(calls)),
      Canon.node("capabilities", counts(capabilities))
    )

object MetaMachine0:

  final case class MetaFail(kind: String, message: String) extends RuntimeException(message)

  def ok(value: Canon, evidence: Evidence): Canon =
    Canon.node("verdict", Canon.Sym("ok"), value, evidence.toCanon)

  def error(kind: String, message: String, evidence: Evidence): Canon =
    Canon.node("verdict", Canon.Sym("error"), Canon.Sym(kind), Canon.S(message), evidence.toCanon)

  def isOk(verdict: Canon): Boolean = verdict match
    case Canon.Node("verdict", Canon.Sym("ok") +: _) => true
    case _                                           => false

  def result(verdict: Canon): Option[Canon] = verdict match
    case Canon.Node("verdict", Vector(Canon.Sym("ok"), v, _)) => Some(v)
    case _                                                    => None

  /**
   * derive(P, Sigma, K, B, G) = V
   *
   * Deterministic for fixed inputs, including evidence and resource accounting.
   */
  def derive(
      program: Program,
      sigma: Cas,
      kernel: Kernel,
      budget: Budget,
      goal: Canon,
      capabilities: CapabilityHandler
  ): Canon =
    val engine = new Engine(program, kernel, budget, capabilities)
    try
      val value = engine.eval(goal, Map.empty, 0)
      ok(value, engine.evidence)
    catch
      case f: MetaFail          => error(f.kind, f.message, engine.evidence)
      case _: StackOverflowError => error("depth-exhausted", "derivation exceeded host stack", engine.evidence)

  private final class Engine(program: Program, kernel: Kernel, budget: Budget, capabilities: CapabilityHandler):
    private var steps: Long = 0
    private val calls = mutable.LinkedHashMap.empty[String, Long]
    private val caps = mutable.LinkedHashMap.empty[String, Long]

    def evidence: Evidence = Evidence(steps, calls.toMap, caps.toMap)

    private def tick(): Unit =
      steps += 1
      if steps > budget.steps then throw MetaFail("resource-exhausted", s"budget of ${budget.steps} steps exhausted")

    private def fail(kind: String, msg: String): Nothing = throw MetaFail(kind, msg)

    def eval(expr: Canon, env: Map[String, Canon], depth: Int): Canon =
      tick()
      if depth > budget.depth then fail("depth-exhausted", s"depth budget ${budget.depth} exceeded")
      expr match
        case Canon.Node("q", Vector(v)) => v

        case Canon.Node("v", Vector(Canon.Sym(name))) =>
          env.getOrElse(name, fail("unbound-variable", s"unbound variable $name"))

        case Canon.Node("mk", Canon.Sym(tag) +: rest) =>
          Canon.Node(tag, rest.map(eval(_, env, depth + 1)))

        case Canon.Node("lst", items) =>
          Canon.L(items.map(eval(_, env, depth + 1)))

        case Canon.Node("mp", pairs) =>
          if pairs.length % 2 != 0 then fail("bad-expression", "map literal needs an even number of elements")
          val entries = pairs.grouped(2).map { p => eval(p(0), env, depth + 1) -> eval(p(1), env, depth + 1) }.toVector
          Canon.M(entries.distinctBy(_._1).sortWith((a, b) => Canon.compare(a._1, b._1) < 0))

        case Canon.Node("if", Vector(c, t, e)) =>
          eval(c, env, depth + 1) match
            case Canon.B(true)  => eval(t, env, depth + 1)
            case Canon.B(false) => eval(e, env, depth + 1)
            case other          => fail("type-error", s"if condition is not a boolean: ${CanonText.write(other)}")

        case Canon.Node("let", Vector(Canon.Sym(name), value, body)) =>
          val v = eval(value, env, depth + 1)
          eval(body, env + (name -> v), depth + 1)

        case Canon.Node("call", Canon.Sym(name) +: argExprs) =>
          val j = program.judgments.getOrElse(name, fail("unknown-judgment", s"unknown judgment $name"))
          if j.params.length != argExprs.length then
            fail("arity-error", s"judgment $name expects ${j.params.length} arguments, got ${argExprs.length}")
          val args = argExprs.map(eval(_, env, depth + 1))
          calls.updateWith(name)(c => Some(c.getOrElse(0L) + 1))
          eval(j.body, j.params.zip(args).toMap, depth + 1)

        case Canon.Node("match", scrutinee +: cases) =>
          val v = eval(scrutinee, env, depth + 1)
          matchCases(v, cases, env, depth)

        case Canon.Node("prim", Canon.Sym(name) +: argExprs) =>
          Prims.apply(name, argExprs.map(eval(_, env, depth + 1)), fail)

        case Canon.Node("cap", Canon.Sym(name) +: argExprs) =>
          if !kernel.allow.contains(name) then fail("capability-denied", s"capability $name is not constituted")
          caps.updateWith(name)(c => Some(c.getOrElse(0L) + 1))
          val response = capabilities.handle(CapabilityRequest(name, argExprs.map(eval(_, env, depth + 1))))
          response.toCanon

        case Canon.Node("fail", Vector(Canon.Sym(kind), message)) =>
          eval(message, env, depth + 1) match
            case Canon.S(m) => fail(kind, m)
            case other      => fail(kind, CanonText.write(other))

        case other => fail("bad-expression", s"not an expression: ${CanonText.write(other)}")

    private def matchCases(value: Canon, cases: Vector[Canon], env: Map[String, Canon], depth: Int): Canon =
      var i = 0
      while i < cases.length do
        cases(i) match
          case Canon.Node("case", Vector(pattern, body)) =>
            matchPattern(pattern, value, env) match
              case Some(bound) => return eval(body, bound, depth + 1)
              case None        => ()
          case other => fail("bad-expression", s"not a match case: ${CanonText.write(other)}")
        i += 1
      fail("no-match", s"no case matched ${CanonText.write(value)}")

    private def matchPattern(pattern: Canon, value: Canon, env: Map[String, Canon]): Option[Map[String, Canon]] =
      tick()
      pattern match
        case Canon.Sym("_")                     => Some(env)
        case Canon.Node("pv", Vector(Canon.Sym(n))) => Some(env + (n -> value))
        case Canon.Node("pq", Vector(expected))     => if expected == value then Some(env) else None
        case Canon.Node("pm", Canon.Sym(tag) +: subs) =>
          value match
            case Canon.Node(t, args) if t == tag && args.length == subs.length =>
              matchAll(subs, args, env)
            case _ => None
        case Canon.Node("pnode", Vector(tagPat, argsPat)) =>
          value match
            case Canon.Node(t, args) =>
              matchPattern(tagPat, Canon.Sym(t), env).flatMap(e => matchPattern(argsPat, Canon.L(args), e))
            case _ => None
        case Canon.Node("pl", subs) =>
          value match
            case Canon.L(items) if items.length == subs.length => matchAll(subs, items, env)
            case _                                             => None
        case Canon.Node("pcons", Vector(h, t)) =>
          value match
            case Canon.L(items) if items.nonEmpty =>
              matchPattern(h, items.head, env).flatMap(e => matchPattern(t, Canon.L(items.tail), e))
            case _ => None
        case Canon.Node("pnil", Vector()) =>
          value match
            case Canon.L(items) if items.isEmpty => Some(env)
            case _                               => None
        case other => fail("bad-pattern", s"not a pattern: ${CanonText.write(other)}")

    private def matchAll(
        patterns: Vector[Canon],
        values: Vector[Canon],
        env: Map[String, Canon]
    ): Option[Map[String, Canon]] =
      var e = env
      var i = 0
      while i < patterns.length do
        matchPattern(patterns(i), values(i), e) match
          case Some(next) => e = next
          case None       => return None
        i += 1
      Some(e)
