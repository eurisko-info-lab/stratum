package stratum.meta

import stratum.artifact.Cas
import stratum.canon.{Canon, CanonText, Digest, Schema, ChangeAlgebra, ChangeComposer, ChangeUpdater, DerivationGraph, DerivationLanguage, Relation, Projection}
import stratum.cap.{CapabilityHandler, CapabilityRequest}

import scala.collection.mutable

/** An explicit resource budget. Exhaustion produces a canonical verdict, never an exception. */
final case class Budget(steps: Long, depth: Int):
  def toCanon: Canon =
    Canon.node("budget", Canon.node("steps", Canon.N(BigInt(steps))), Canon.node("depth", Canon.nat(depth)))

object Budget:
  val default: Budget = Budget(2000000L, 4000)

  given Schema[Budget] with
    def encode(value: Budget): Canon = value.toCanon
    def decode(value: Canon): Either[String, Budget] = Budget.fromCanon(value)
    def refs(value: Budget): Vector[Digest] = Vector.empty

  def deriveCanon(value: Budget): Either[String, Canon] =
    Right(summon[Schema[Budget]].encode(value))

  def applyChange(current: Budget, change: Canon): Either[String, Budget] =
    summon[ChangeAlgebra[Budget]].applyChange(current, change)

  given ChangeAlgebra[Budget] with
    def delta(previous: Budget, next: Budget): Canon =
      Canon.node("budget-change", Canon.node("steps", Canon.N(BigInt(next.steps - previous.steps))), Canon.node("depth", Canon.nat(next.depth - previous.depth)))
    def applyChange(current: Budget, change: Canon): Either[String, Budget] = change match
      case Canon.Node("budget-change", Vector(Canon.Node("steps", Vector(Canon.N(steps))), Canon.Node("depth", Vector(Canon.N(depth))))) =>
        Right(Budget(current.steps + steps.toLong, current.depth + depth.toInt))
      case other => Left(s"not a budget change: ${CanonText.write(other)}")

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
  given Schema[Kernel] with
    def encode(value: Kernel): Canon = value.toCanon
    def decode(value: Canon): Either[String, Kernel] = Kernel.fromCanon(value)
    def refs(value: Kernel): Vector[Digest] = Vector.empty

  given ChangeAlgebra[Kernel] with
    def delta(previous: Kernel, next: Kernel): Canon =
      Canon.node("replace", previous.toCanon, next.toCanon)
    def applyChange(current: Kernel, change: Canon): Either[String, Kernel] = change match
      case Canon.Node("replace", Vector(_, next)) => summon[Schema[Kernel]].decode(next)
      case value if value == current.toCanon => Right(current)
      case other => Left(s"not a kernel change: ${CanonText.write(other)}")

  def deriveCanon(value: Kernel): Either[String, Canon] =
    Right(summon[Schema[Kernel]].encode(value))

  def applyChange(current: Kernel, change: Canon): Either[String, Kernel] =
    summon[ChangeAlgebra[Kernel]].applyChange(current, change)

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

  given Schema[Program] with
    def encode(value: Program): Canon =
      Canon.Node(
        "program",
        value.modules.map(name => Canon.Node("module", Vector(Canon.Sym(name)))) ++
          value.judgments.toVector.sortBy(_._1).map { case (name, judgment) =>
            Canon.Node("judgment", Vector(Canon.Sym(name), Canon.L(judgment.params.map(Canon.Sym.apply)), judgment.body))
          }
      )
    def decode(value: Canon): Either[String, Program] = value match
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
              case other => Left(s"not a program entry: ${CanonText.write(other)}")
          }
        }
      case other => Left(s"not a program: ${CanonText.write(other)}")
    def refs(value: Program): Vector[Digest] = Vector.empty

  given ChangeAlgebra[Program] with
    def delta(previous: Program, next: Program): Canon =
      Canon.node("replace", summon[Schema[Program]].encode(previous), summon[Schema[Program]].encode(next))
    def applyChange(current: Program, change: Canon): Either[String, Program] = change match
      case Canon.Node("replace", Vector(_, next)) => summon[Schema[Program]].decode(next)
      case value if value == summon[Schema[Program]].encode(current) => Right(current)
      case other => Left(s"not a program change: ${CanonText.write(other)}")

  def deriveCanon(value: Program): Either[String, Canon] =
    Right(summon[Schema[Program]].encode(value))

  def applyChange(current: Program, change: Canon): Either[String, Program] =
    summon[ChangeAlgebra[Program]].applyChange(current, change)

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

final case class DerivationState(program: Program, kernel: Kernel, budget: Budget, evidence: Evidence):
  def toCanon: Canon =
    Canon.node(
      "derivation-state",
      Canon.node("program", summon[Schema[Program]].encode(program)),
      Canon.node("kernel", summon[Schema[Kernel]].encode(kernel)),
      Canon.node("budget", summon[Schema[Budget]].encode(budget)),
      Canon.node("evidence", summon[Schema[Evidence]].encode(evidence))
    )

  def machineView: String =
    s"steps=${evidence.steps}; calls=${evidence.calls.toVector.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(",")}; caps=${evidence.capabilities.toVector.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(",")}; budget=${budget.steps}/${budget.depth}; modules=${program.modules.mkString(",")}" 

  def summary: String =
    s"steps=${evidence.steps}; budget=${budget.steps}/${budget.depth}; modules=${program.modules.mkString(",")}; calls=${evidence.calls.size}; caps=${evidence.capabilities.size}"

  def summaryCanon: Canon =
    Canon.node(
      "summary",
      Canon.node("steps", Canon.N(BigInt(evidence.steps))),
      Canon.node("budget", Canon.node("steps", Canon.N(BigInt(budget.steps))), Canon.node("depth", Canon.N(BigInt(budget.depth)))),
      Canon.node("modules", Canon.L(program.modules.map(Canon.S.apply))),
      Canon.node("calls", Canon.N(BigInt(evidence.calls.size))),
      Canon.node("caps", Canon.N(BigInt(evidence.capabilities.size)))
    )

object DerivationState:
  given Schema[DerivationState] with
    def encode(value: DerivationState): Canon = value.toCanon
    def decode(value: Canon): Either[String, DerivationState] = value match
      case Canon.Node(
            "derivation-state",
            Vector(
              Canon.Node("program", Vector(programCanon)),
              Canon.Node("kernel", Vector(kernelCanon)),
              Canon.Node("budget", Vector(budgetCanon)),
              Canon.Node("evidence", Vector(evidenceCanon))
            )
          ) =>
        for
          program <- summon[Schema[Program]].decode(programCanon)
          kernel <- summon[Schema[Kernel]].decode(kernelCanon)
          budget <- summon[Schema[Budget]].decode(budgetCanon)
          evidence <- summon[Schema[Evidence]].decode(evidenceCanon)
        yield DerivationState(program, kernel, budget, evidence)
      case other => Left(s"not a derivation state: ${CanonText.write(other)}")
    def refs(value: DerivationState): Vector[Digest] = Vector.empty

  given ChangeAlgebra[DerivationState] with
    def delta(previous: DerivationState, next: DerivationState): Canon =
      val changes = Vector(
        ("program", previous.program, next.program, summon[ChangeAlgebra[Program]].delta(previous.program, next.program)),
        ("kernel", previous.kernel, next.kernel, summon[ChangeAlgebra[Kernel]].delta(previous.kernel, next.kernel)),
        ("budget", previous.budget, next.budget, summon[ChangeAlgebra[Budget]].delta(previous.budget, next.budget)),
        ("evidence", previous.evidence, next.evidence, summon[ChangeAlgebra[Evidence]].delta(previous.evidence, next.evidence))
      ).collect {
        case (name, prev, nextValue, change) if prev != nextValue => Canon.node(name, change)
      }
      if changes.isEmpty then Canon.Sym("noop") else Canon.node("state-change", changes*)
    def applyChange(current: DerivationState, change: Canon): Either[String, DerivationState] = change match
      case Canon.Node("replace", Vector(_, next)) => summon[Schema[DerivationState]].decode(next)
      case Canon.Node("state-change", entries) =>
        entries.foldLeft[Either[String, DerivationState]](Right(current)) { (acc, entry) =>
          acc.flatMap { state =>
            entry match
              case Canon.Node("program", Vector(programChange)) =>
                summon[ChangeAlgebra[Program]].applyChange(state.program, programChange).map(updatedProgram => state.copy(program = updatedProgram))
              case Canon.Node("budget", Vector(budgetChange)) =>
                summon[ChangeAlgebra[Budget]].applyChange(state.budget, budgetChange).map(updatedBudget => state.copy(budget = updatedBudget))
              case Canon.Node("kernel", Vector(kernelChange)) =>
                summon[ChangeAlgebra[Kernel]].applyChange(state.kernel, kernelChange).map(updatedKernel => state.copy(kernel = updatedKernel))
              case Canon.Node("evidence", Vector(evidenceChange)) =>
                summon[ChangeAlgebra[Evidence]].applyChange(state.evidence, evidenceChange).map(updatedEvidence => state.copy(evidence = updatedEvidence))
              case other => Left(s"not a derivation-state field change: ${CanonText.write(other)}")
          }
        }
      case value if value == summon[Schema[DerivationState]].encode(current) => Right(current)
      case other => Left(s"not a derivation-state change: ${CanonText.write(other)}")

  given ChangeComposer[DerivationState] with
    def compose(changes: Vector[Canon]): Either[String, Canon] = changes match
      case Vector() => Right(Canon.Sym("noop"))
      case entries =>
        val normalized = entries.flatMap {
          case Canon.Node("compose", nested) => nested
          case Canon.Node("state-change", inner) => inner
          case entry => Vector(entry)
        }
        Right(Canon.Node("state-change", normalized))

  given ChangeUpdater[DerivationState] with
    def update(current: DerivationState, change: Canon): Either[String, DerivationState] = change match
      case Canon.Node("compose", steps) =>
        steps.foldLeft[Either[String, DerivationState]](Right(current)) { (acc, step) =>
          acc.flatMap(value => summon[ChangeAlgebra[DerivationState]].applyChange(value, step))
        }
      case other => summon[ChangeAlgebra[DerivationState]].applyChange(current, other)

  def deriveCanon(value: DerivationState): Either[String, Canon] =
    Right(summon[Schema[DerivationState]].encode(value))

  def applyChange(current: DerivationState, change: Canon): Either[String, DerivationState] =
    summon[ChangeAlgebra[DerivationState]].applyChange(current, change)

object Evidence:
  given Schema[Evidence] with
    def encode(value: Evidence): Canon = value.toCanon
    def decode(value: Canon): Either[String, Evidence] = value match
      case Canon.Node(
            "evidence",
            Vector(
              Canon.Node("steps", Vector(Canon.N(steps))),
              Canon.Node("calls", Vector(Canon.M(calls))),
              Canon.Node("capabilities", Vector(Canon.M(caps)))
            )
          ) =>
        Right(
          Evidence(
            steps.toLong,
            calls.collect { case (Canon.Sym(k), Canon.N(v)) => k -> v.toLong }.toMap,
            caps.collect { case (Canon.Sym(k), Canon.N(v)) => k -> v.toLong }.toMap
          )
        )
      case other => Left(s"not evidence: ${CanonText.write(other)}")
    def refs(value: Evidence): Vector[Digest] = Vector.empty

  given ChangeAlgebra[Evidence] with
    def delta(previous: Evidence, next: Evidence): Canon =
      Canon.node("replace", previous.toCanon, next.toCanon)
    def applyChange(current: Evidence, change: Canon): Either[String, Evidence] = change match
      case Canon.Node("replace", Vector(_, next)) => summon[Schema[Evidence]].decode(next)
      case value if value == current.toCanon => Right(current)
      case other => Left(s"not an evidence change: ${CanonText.write(other)}")

  def deriveCanon(value: Evidence): Either[String, Canon] =
    Right(summon[Schema[Evidence]].encode(value))

  def applyChange(current: Evidence, change: Canon): Either[String, Evidence] =
    summon[ChangeAlgebra[Evidence]].applyChange(current, change)

object MetaMachine0:

  final case class MetaFail(kind: String, message: String) extends RuntimeException(message)

  /**
   * The canonical verdict: `(verdict ok <value> <evidence>)`.
   *
   * The shape is constitutional. The independent host derives the same bytes,
   * and `foundation report` digests exactly this value, so the two hosts agree
   * only while both keep to it.
   *
   * Diagnostic material is deliberately absent. A trace of the derivation grows
   * with the number of steps, and each entry would carry a whole program-sized
   * state, so embedding it here made verdicts too large to encode at all.
   */
  def ok(value: Canon, evidence: Evidence): Canon =
    Canon.node("verdict", Canon.Sym("ok"), value, evidence.toCanon)

  /** The canonical verdict: `(verdict error <kind> <message> <evidence>)`. */
  def error(kind: String, message: String, evidence: Evidence): Canon =
    Canon.node("verdict", Canon.Sym("error"), Canon.Sym(kind), Canon.S(message), evidence.toCanon)

  def isOk(verdict: Canon): Boolean = verdict match
    case Canon.Node("verdict", Canon.Sym("ok") +: _) => true
    case _                                           => false

  def result(verdict: Canon): Option[Canon] = verdict match
    case Canon.Node("verdict", args) if args.nonEmpty && args.head == Canon.Sym("ok") =>
      args.drop(1).headOption
    case _ => None

  /**
   * Extracts `(kind, message)` from an error verdict.
   *
   * Like [[result]], this reads by position rather than by exact arity, so a
   * consumer cannot be broken by a change in the trailing evidence field.
   */
  def failure(verdict: Canon): Option[(String, String)] = verdict match
    case Canon.Node("verdict", args) if args.length >= 3 && args.head == Canon.Sym("error") =>
      (args(1), args(2)) match
        case (Canon.Sym(kind), Canon.S(message)) => Some((kind, message))
        case _                                   => None
    case _ => None

  def replayState(current: DerivationState, next: DerivationState): Either[String, DerivationState] =
    summon[ChangeUpdater[DerivationState]].update(current, summon[ChangeAlgebra[DerivationState]].delta(current, next))

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
    val graph = DerivationGraph.instance[DerivationState](
      summon[Schema[DerivationState]],
      new Relation[DerivationState, Canon] {
        def derive(input: DerivationState): Either[String, Canon] = Right(summon[Schema[DerivationState]].encode(input))
      },
      new Projection[DerivationState, Canon] {
        def project(value: DerivationState): Either[String, Canon] = Right(summon[Schema[DerivationState]].encode(value))
      },
      summon[ChangeAlgebra[DerivationState]]
    )
    val language = DerivationLanguage.instance(graph)
    val engine = new Engine(program, kernel, budget, capabilities)
    try
      val value = engine.eval(goal, Map.empty, 0)
      val state = engine.stateSnapshot
      language.run(state) match
        case Right(_)      => ok(value, state.evidence)
        case Left(message) => error("derivation-graph", message, state.evidence)
    catch
      case f: MetaFail =>
        error(f.kind, f.message, engine.stateSnapshot.evidence)
      case _: StackOverflowError =>
        error("depth-exhausted", "derivation exceeded host stack", engine.stateSnapshot.evidence)

  private final class Engine(program: Program, kernel: Kernel, budget: Budget, capabilities: CapabilityHandler):
    private var steps: Long = 0
    private val calls = mutable.LinkedHashMap.empty[String, Long]
    private val caps = mutable.LinkedHashMap.empty[String, Long]

    def evidence: Evidence = Evidence(steps, calls.toMap, caps.toMap)

    /**
     * The state is rebuilt on demand rather than kept in a field.
     *
     * It embeds the whole program, so materialising one per step — as the
     * retired trace did — costs memory proportional to steps times program
     * size. Only the final state is ever needed.
     */
    def stateSnapshot: DerivationState = DerivationState(program, kernel, budget, evidence)

    private def tick(): Unit =
      steps += 1
      if steps > budget.steps then throw MetaFail("resource-exhausted", s"budget of ${budget.steps} steps exhausted")

    private def fail(kind: String, msg: String): Nothing = throw MetaFail(kind, msg)

    def eval(expr: Canon, env: Map[String, Canon], depth: Int): Canon =
      tick()
      if depth > budget.depth then fail("depth-exhausted", s"depth budget ${budget.depth} exceeded")
      // The form is decided by one switch on the tag rather than by trying the
      // twelve shapes in turn. `call`, `match` and `prim` are the three the
      // interpreter spends its life in, and they were the last three tried.
      expr match
        case Canon.Node(tag, args) =>
          tag match
            case "q" =>
              args match
                case Vector(v) => v
                case _         => notAnExpression(expr)

            case "v" =>
              args match
                case Vector(Canon.Sym(name)) =>
                  env.getOrElse(name, fail("unbound-variable", s"unbound variable $name"))
                case _ => notAnExpression(expr)

            case "mk" =>
              args match
                case Canon.Sym(nodeTag) +: rest => Canon.Node(nodeTag, rest.map(eval(_, env, depth + 1)))
                case _                          => notAnExpression(expr)

            case "lst" => Canon.L(args.map(eval(_, env, depth + 1)))

            case "mp" =>
              if args.length % 2 != 0 then fail("bad-expression", "map literal needs an even number of elements")
              val entries = args.grouped(2).map { p => eval(p(0), env, depth + 1) -> eval(p(1), env, depth + 1) }.toVector
              Canon.M(entries.distinctBy(_._1).sortWith((a, b) => Canon.compare(a._1, b._1) < 0))

            case "if" =>
              args match
                case Vector(c, t, e) =>
                  eval(c, env, depth + 1) match
                    case Canon.B(true)  => eval(t, env, depth + 1)
                    case Canon.B(false) => eval(e, env, depth + 1)
                    case other          => fail("type-error", s"if condition is not a boolean: ${CanonText.write(other)}")
                case _ => notAnExpression(expr)

            case "let" =>
              args match
                case Vector(Canon.Sym(name), value, body) =>
                  val v = eval(value, env, depth + 1)
                  eval(body, env + (name -> v), depth + 1)
                case _ => notAnExpression(expr)

            case "call" =>
              args match
                case Canon.Sym(name) +: argExprs =>
                  val j = program.judgments.getOrElse(name, fail("unknown-judgment", s"unknown judgment $name"))
                  if j.params.length != argExprs.length then
                    fail("arity-error", s"judgment $name expects ${j.params.length} arguments, got ${argExprs.length}")
                  var frame = Map.empty[String, Canon]
                  var i = 0
                  while i < argExprs.length do
                    frame = frame.updated(j.params(i), eval(argExprs(i), env, depth + 1))
                    i += 1
                  calls.put(name, calls.getOrElse(name, 0L) + 1L)
                  eval(j.body, frame, depth + 1)
                case _ => notAnExpression(expr)

            case "match" =>
              args match
                case scrutinee +: cases =>
                  val v = eval(scrutinee, env, depth + 1)
                  matchCases(v, cases, env, depth)
                case _ => notAnExpression(expr)

            case "prim" =>
              args match
                case Canon.Sym(name) +: argExprs =>
                  Prims.apply(name, argExprs.map(eval(_, env, depth + 1)), fail)
                case _ => notAnExpression(expr)

            case "cap" =>
              args match
                case Canon.Sym(name) +: argExprs =>
                  if !kernel.allow.contains(name) then fail("capability-denied", s"capability $name is not constituted")
                  caps.put(name, caps.getOrElse(name, 0L) + 1L)
                  val evaluated = argExprs.map(eval(_, env, depth + 1))
                  capabilities.handle(CapabilityRequest(name, evaluated)).toCanon
                case _ => notAnExpression(expr)

            case "fail" =>
              args match
                case Vector(Canon.Sym(kind), message) =>
                  eval(message, env, depth + 1) match
                    case Canon.S(m) => fail(kind, m)
                    case other      => fail(kind, CanonText.write(other))
                case _ => notAnExpression(expr)

            case _ => notAnExpression(expr)

        case other => notAnExpression(other)

    private def notAnExpression(value: Canon): Nothing =
      fail("bad-expression", s"not an expression: ${CanonText.write(value)}")

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
        case Canon.Sym("_") => Some(env)
        case Canon.Node(tag, args) =>
          def badPattern: Nothing = fail("bad-pattern", s"not a pattern: ${CanonText.write(pattern)}")
          tag match
            case "pv" =>
              args match
                case Vector(Canon.Sym(n)) => Some(env + (n -> value))
                case _                    => badPattern
            case "pq" =>
              args match
                case Vector(expected) => if expected == value then Some(env) else None
                case _                => badPattern
            case "pm" =>
              args match
                case Canon.Sym(expected) +: subs =>
                  value match
                    case Canon.Node(t, values) if t == expected && values.length == subs.length =>
                      matchAll(subs, values, env)
                    case _ => None
                case _ => badPattern
            case "pnode" =>
              args match
                case Vector(tagPat, argsPat) =>
                  value match
                    case Canon.Node(t, values) =>
                      matchPattern(tagPat, Canon.Sym(t), env).flatMap(e => matchPattern(argsPat, Canon.L(values), e))
                    case _ => None
                case _ => badPattern
            case "pl" =>
              value match
                case Canon.L(items) if items.length == args.length => matchAll(args, items, env)
                case _                                             => None
            case "pcons" =>
              args match
                case Vector(h, t) =>
                  value match
                    case Canon.L(items) if items.nonEmpty =>
                      matchPattern(h, items.head, env).flatMap(e => matchPattern(t, Canon.L(items.tail), e))
                    case _ => None
                case _ => badPattern
            case "pnil" =>
              if args.nonEmpty then badPattern
              value match
                case Canon.L(items) if items.isEmpty => Some(env)
                case _                               => None
            case _ => badPattern
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
