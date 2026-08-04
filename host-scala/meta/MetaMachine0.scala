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
    summon[ChangeAlgebra[Budget]].patch(current, change)

  given ChangeAlgebra[Budget] with
    def delta(previous: Budget, next: Budget): Canon =
      Canon.node("budget-change", Canon.node("steps", Canon.N(BigInt(next.steps - previous.steps))), Canon.node("depth", Canon.nat(next.depth - previous.depth)))
    def patch(current: Budget, change: Canon): Either[String, Budget] = change match
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
    def patch(current: Kernel, change: Canon): Either[String, Kernel] = change match
      case Canon.Node("replace", Vector(_, next)) => summon[Schema[Kernel]].decode(next)
      case value if value == current.toCanon => Right(current)
      case other => Left(s"not a kernel change: ${CanonText.write(other)}")

  def deriveCanon(value: Kernel): Either[String, Canon] =
    Right(summon[Schema[Kernel]].encode(value))

  def applyChange(current: Kernel, change: Canon): Either[String, Kernel] =
    summon[ChangeAlgebra[Kernel]].patch(current, change)

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
    def patch(current: Program, change: Canon): Either[String, Program] = change match
      case Canon.Node("replace", Vector(_, next)) => summon[Schema[Program]].decode(next)
      case value if value == summon[Schema[Program]].encode(current) => Right(current)
      case other => Left(s"not a program change: ${CanonText.write(other)}")

  def deriveCanon(value: Program): Either[String, Canon] =
    Right(summon[Schema[Program]].encode(value))

  def applyChange(current: Program, change: Canon): Either[String, Program] =
    summon[ChangeAlgebra[Program]].patch(current, change)

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
    def patch(current: DerivationState, change: Canon): Either[String, DerivationState] = change match
      case Canon.Node("replace", Vector(_, next)) => summon[Schema[DerivationState]].decode(next)
      case Canon.Node("state-change", entries) =>
        entries.foldLeft[Either[String, DerivationState]](Right(current)) { (acc, entry) =>
          acc.flatMap { state =>
            entry match
              case Canon.Node("program", Vector(programChange)) =>
                summon[ChangeAlgebra[Program]].patch(state.program, programChange).map(updatedProgram => state.copy(program = updatedProgram))
              case Canon.Node("budget", Vector(budgetChange)) =>
                summon[ChangeAlgebra[Budget]].patch(state.budget, budgetChange).map(updatedBudget => state.copy(budget = updatedBudget))
              case Canon.Node("kernel", Vector(kernelChange)) =>
                summon[ChangeAlgebra[Kernel]].patch(state.kernel, kernelChange).map(updatedKernel => state.copy(kernel = updatedKernel))
              case Canon.Node("evidence", Vector(evidenceChange)) =>
                summon[ChangeAlgebra[Evidence]].patch(state.evidence, evidenceChange).map(updatedEvidence => state.copy(evidence = updatedEvidence))
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
          acc.flatMap(value => summon[ChangeAlgebra[DerivationState]].patch(value, step))
        }
      case other => summon[ChangeAlgebra[DerivationState]].patch(current, other)

  def deriveCanon(value: DerivationState): Either[String, Canon] =
    Right(summon[Schema[DerivationState]].encode(value))

  def applyChange(current: DerivationState, change: Canon): Either[String, DerivationState] =
    summon[ChangeAlgebra[DerivationState]].patch(current, change)

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
    def patch(current: Evidence, change: Canon): Either[String, Evidence] = change match
      case Canon.Node("replace", Vector(_, next)) => summon[Schema[Evidence]].decode(next)
      case value if value == current.toCanon => Right(current)
      case other => Left(s"not an evidence change: ${CanonText.write(other)}")

  def deriveCanon(value: Evidence): Either[String, Canon] =
    Right(summon[Schema[Evidence]].encode(value))

  def applyChange(current: Evidence, change: Canon): Either[String, Evidence] =
    summon[ChangeAlgebra[Evidence]].patch(current, change)

object MetaMachine0:

  final case class MetaFail(kind: String, message: String) extends RuntimeException(message)

  final case class MachineFrame(state: DerivationState, summary: String):
    def toCanon: Canon =
      Canon.node("machine-frame", Canon.node("state", state.toCanon), Canon.node("state-summary", Canon.S(summary)))

  object MachineFrame:
    def fromCanon(value: Canon, fallback: DerivationState): MachineFrame = value match
      case Canon.Node("machine-frame", Vector(Canon.Node("state", Vector(stateCanon)), Canon.Node("state-summary", Vector(Canon.S(summary))))) =>
        summon[Schema[DerivationState]].decode(stateCanon).toOption match
          case Some(state) => MachineFrame(state, summary)
          case None        => MachineFrame(fallback, summary)
      case other => MachineFrame(fallback, fallback.summary)

  final case class SemanticSnapshot(state: DerivationState, summary: String, machineView: String):
    def toCanon: Canon =
      Canon.node("semantic-snapshot", Canon.node("state", state.toCanon), Canon.node("summary", Canon.S(summary)), Canon.node("machine-view", Canon.S(machineView)))

  object SemanticSnapshot:
    def fromState(state: DerivationState): SemanticSnapshot = SemanticSnapshot(state, state.summary, state.machineView)

    def fromCanon(value: Canon, fallback: DerivationState): SemanticSnapshot = value match
      case Canon.Node("semantic-snapshot", Vector(Canon.Node("state", Vector(stateCanon)), Canon.Node("summary", Vector(Canon.S(summary))), Canon.Node("machine-view", Vector(Canon.S(machineView))))) =>
        summon[Schema[DerivationState]].decode(stateCanon).toOption match
          case Some(state) => SemanticSnapshot(state, summary, machineView)
          case None        => SemanticSnapshot.fromState(fallback)
      case other => SemanticSnapshot.fromState(fallback)

  final case class EvaluationContext(env: Map[String, Canon], depth: Int, frame: MachineFrame, snapshot: SemanticSnapshot):
    def toCanon: Canon =
      Canon.node("eval-context", Canon.node("env", Canon.M(env.toVector.sortBy(_._1).map((k, v) => Canon.Sym(k) -> v))), Canon.node("depth", Canon.N(BigInt(depth))), frame.toCanon, snapshot.toCanon)

  object EvaluationContext:
    def fromCanon(value: Canon, fallback: DerivationState): EvaluationContext = value match
      case Canon.Node("eval-context", Vector(Canon.Node("env", Vector(Canon.M(entries))), Canon.Node("depth", Vector(Canon.N(depth))), frameCanon, snapshotCanon)) =>
        val env = entries.collect { case (Canon.Sym(k), v) => k -> v }.toMap
        val frame = MachineFrame.fromCanon(frameCanon, fallback)
        val snapshot = SemanticSnapshot.fromCanon(snapshotCanon, fallback)
        EvaluationContext(env, depth.toInt, frame, snapshot)
      case other => EvaluationContext(Map.empty, 0, machineFrame(fallback), SemanticSnapshot.fromState(fallback))

  final case class EvaluationResult(
      expr: Canon,
      value: Canon,
      env: Map[String, Canon],
      depth: Int,
      before: DerivationState,
      after: DerivationState,
      frame: MachineFrame,
      snapshot: SemanticSnapshot,
      parent: Option[EvaluationResult] = None,
      parentIndex: Option[Int] = None
  ):
    def context: EvaluationContext = EvaluationContext(env, depth, frame, snapshot)
    def traceValue: Canon = Canon.node("eval", expr, value, frame.toCanon)
    def traceEntry(change: Canon): TraceEntry = TraceEntry("eval", traceValue, before, after, change, frame)
    def semanticStep: SemanticTraceStep = SemanticTraceStep("eval", traceValue, before, after, frame, None, Some(context))
    def stack: Vector[EvaluationResult] = parent match
      case Some(p) => p.stack :+ this
      case None    => Vector(this)
    def summary: String = s"${CanonText.write(expr)} => ${CanonText.write(value)} [depth=$depth]"
    def toCanon: Canon =
      Canon.node(
        "eval-result",
        Canon.Sym("eval"),
        expr,
        value,
        Canon.N(BigInt(depth)),
        frame.toCanon,
        context.toCanon,
        before.toCanon,
        after.toCanon,
        parentIndex match
          case Some(index) => Canon.N(BigInt(index))
          case None        => Canon.Sym("root")
      )

  final case class EvaluationTree(result: EvaluationResult, children: Vector[EvaluationTree] = Vector.empty):
    def flatten: Vector[EvaluationResult] = Vector(result) ++ children.flatMap(_.flatten)
    def lines: Vector[String] =
      def render(node: EvaluationTree, prefix: String): Vector[String] =
        val label = s"eval-tree: ${CanonText.write(node.result.expr)} => ${CanonText.write(node.result.value)} [depth=${node.result.depth}]"
        val children = node.children.flatMap(child => render(child, prefix + "  "))
        label +: children
      render(this, "")
    def toCanon: Canon =
      Canon.node(
        "eval-tree",
        Canon.node(
          "result",
          result.expr,
          result.value,
          Canon.N(BigInt(result.depth)),
          result.frame.toCanon,
          result.context.toCanon,
          result.before.toCanon,
          result.after.toCanon,
          result.parentIndex match
            case Some(index) => Canon.N(BigInt(index))
            case None        => Canon.Sym("root")
        ),
        Canon.L(children.map(_.toCanon))
      )

  object EvaluationTree:
    def fromCanon(value: Canon, fallbackState: DerivationState): Option[EvaluationTree] = value match
      case Canon.Node("eval-tree", Vector(Canon.Node("result", args), Canon.L(children))) =>
        val result = args match
          case Vector(expr, valueCanon, depthCanon, frameCanon, contextCanon, beforeCanon, afterCanon, parentCanon) =>
            val frame = MachineFrame.fromCanon(frameCanon, fallbackState)
            val context = EvaluationContext.fromCanon(contextCanon, fallbackState)
            val before = summon[Schema[DerivationState]].decode(beforeCanon).getOrElse(fallbackState)
            val after = summon[Schema[DerivationState]].decode(afterCanon).getOrElse(fallbackState)
            val parentIndex = parentCanon match
              case Canon.N(n) => Some(n.toInt)
              case Canon.Sym("root") => None
              case _ => None
            val depthValue = depthCanon match
              case Canon.N(n) => n.toInt
              case _ => context.depth
            EvaluationResult(expr, valueCanon, context.env, depthValue, before, after, frame, context.snapshot, None, parentIndex)
          case _ =>
            EvaluationResult(Canon.Sym("noop"), Canon.Sym("noop"), Map.empty, 0, fallbackState, fallbackState, machineFrame(fallbackState), SemanticSnapshot.fromState(fallbackState))
        val childTrees = children.flatMap(tree => EvaluationTree.fromCanon(tree, fallbackState).toVector)
        Some(EvaluationTree(result, childTrees))
      case _ => None

  final case class EvaluationSemantics(stack: Vector[EvaluationResult], tree: EvaluationTree):
    def lines: Vector[String] = stack.map(result => s"eval-stack: ${result.summary}") ++ tree.lines

  object EvaluationSemantics:
    def fromCanon(value: Canon, fallbackState: DerivationState): Option[EvaluationSemantics] = value match
      case Canon.Node("eval-semantics", Vector(Canon.L(stackItems), treeCanon)) =>
        val stack = stackItems.flatMap { item =>
          item match
            case Canon.Node("eval-result", Vector(Canon.Sym("eval"), expr, value, depth, frameCanon, contextCanon, beforeCanon, afterCanon, parentCanon)) =>
              val frame = MachineFrame.fromCanon(frameCanon, fallbackState)
              val context = EvaluationContext.fromCanon(contextCanon, frame.state)
              val before = summon[Schema[DerivationState]].decode(beforeCanon).getOrElse(frame.state)
              val after = summon[Schema[DerivationState]].decode(afterCanon).getOrElse(frame.state)
              val depthValue = depth match
                case Canon.N(n) => n.toInt
                case _          => context.depth
              val parentIndex = parentCanon match
                case Canon.N(n) => Some(n.toInt)
                case Canon.Sym("root") => None
                case _ => None
              Some(EvaluationResult(expr, value, context.env, depthValue, before, after, frame, context.snapshot, None, parentIndex))
            case _ => None
        }
        val tree = EvaluationTree.fromCanon(treeCanon, fallbackState)
        tree.map(EvaluationSemantics(stack, _))
      case _ => None

  final case class JudgmentStep(name: String, args: Vector[Canon], state: DerivationState, frame: MachineFrame):
    def toCanon: Canon =
      Canon.node("judgment-step", Canon.Sym(name), Canon.L(args), state.toCanon, frame.toCanon)

  object JudgmentStep:
    def fromCanon(value: Canon, fallback: DerivationState): JudgmentStep = value match
      case Canon.Node("judgment-step", Vector(Canon.Sym(name), Canon.L(args), stateCanon, frameCanon)) =>
        summon[Schema[DerivationState]].decode(stateCanon).toOption match
          case Some(state) => JudgmentStep(name, args, state, MachineFrame.fromCanon(frameCanon, state))
          case None        => JudgmentStep(name, args, fallback, machineFrame(fallback))
      case other => JudgmentStep("unknown", Vector.empty, fallback, machineFrame(fallback))

  final case class TraceEntry(kind: String, value: Canon, before: DerivationState, after: DerivationState, change: Canon, frame: MachineFrame)

  final case class SemanticTraceStep(
      kind: String,
      value: Canon,
      before: DerivationState,
      after: DerivationState,
      frame: MachineFrame,
      judgment: Option[JudgmentStep] = None,
      evaluation: Option[EvaluationContext] = None
  )

  private def machineFrame(state: DerivationState): MachineFrame = MachineFrame(state, state.summary)

  private def semanticStep(entry: TraceEntry): SemanticTraceStep =
    val judgment = entry.kind match
      case "judgment" =>
        entry.value match
          case Canon.Node("judgment", Vector(Canon.Sym(name), Canon.L(args))) =>
            Some(JudgmentStep(name, args, entry.after, entry.frame))
          case _ => None
      case _ => None
    val evaluation = entry.kind match
      case "eval" =>
        entry.value match
          case Canon.Node("eval", Vector(_, _, Canon.Node("machine-frame", Vector(Canon.Node("env", Vector(Canon.M(entries))), Canon.Node("depth", Vector(Canon.N(depth))), _, _)))) =>
            val env = entries.collect { case (Canon.Sym(k), v) => k -> v }.toMap
            Some(EvaluationContext(env, depth.toInt, entry.frame, SemanticSnapshot.fromState(entry.after)))
          case Canon.Node("eval", Vector(_, _, _)) =>
            Some(EvaluationContext(Map.empty, 0, entry.frame, SemanticSnapshot.fromState(entry.after)))
          case _ => None
      case _ => None
    SemanticTraceStep(entry.kind, entry.value, entry.before, entry.after, entry.frame, judgment, evaluation)

  private def traceEntryCanon(entry: TraceEntry): Canon =
    Canon.node("trace-entry", Canon.Sym(entry.kind), entry.value, entry.before.toCanon, entry.after.toCanon, entry.change, entry.frame.toCanon)

  private def evalResultCanon(result: EvaluationResult): Canon =
    Canon.node(
      "eval-result",
      Canon.Sym("eval"),
      result.expr,
      result.value,
      Canon.N(BigInt(result.depth)),
      result.frame.toCanon,
      result.context.toCanon,
      result.before.toCanon,
      result.after.toCanon,
      result.parentIndex match
        case Some(index) => Canon.N(BigInt(index))
        case None        => Canon.Sym("root")
    )

  def ok(value: Canon, evidence: Evidence): Canon =
    ok(value, DerivationState(Program.empty, Kernel.pure, Budget.default, evidence))

  def ok(value: Canon, state: DerivationState): Canon =
    Canon.node("verdict", Canon.Sym("ok"), value, state.toCanon, Canon.S(state.summary))

  def ok(value: Canon, state: DerivationState, trace: Vector[Canon]): Canon =
    okWithTrace(value, state, trace.map(step => TraceEntry("step", step, state, state, Canon.Sym("noop"), machineFrame(state))))

  def okWithTrace(value: Canon, state: DerivationState, trace: Vector[TraceEntry]): Canon =
    Canon.node("verdict", Canon.Sym("ok"), value, state.toCanon, Canon.S(state.summary), Canon.L(trace.map(traceEntryCanon)))

  def okWithSemantics(value: Canon, state: DerivationState, trace: Vector[TraceEntry], stack: Vector[EvaluationResult]): Canon =
    Canon.node(
      "verdict",
      Canon.Sym("ok"),
      value,
      state.toCanon,
      Canon.S(state.summary),
      Canon.L(trace.map(traceEntryCanon)),
      Canon.L(stack.map(evalResultCanon))
    )

  def error(kind: String, message: String, evidence: Evidence): Canon =
    error(kind, message, DerivationState(Program.empty, Kernel.pure, Budget.default, evidence))

  def error(kind: String, message: String, state: DerivationState): Canon =
    Canon.node("verdict", Canon.Sym("error"), Canon.Sym(kind), Canon.S(message), state.toCanon, Canon.S(state.summary))

  def error(kind: String, message: String, state: DerivationState, trace: Vector[Canon]): Canon =
    errorWithTrace(kind, message, state, trace.map(step => TraceEntry("step", step, state, state, Canon.Sym("noop"), machineFrame(state))))

  def errorWithTrace(kind: String, message: String, state: DerivationState, trace: Vector[TraceEntry]): Canon =
    Canon.node("verdict", Canon.Sym("error"), Canon.Sym(kind), Canon.S(message), state.toCanon, Canon.S(state.summary), Canon.L(trace.map(traceEntryCanon)))

  def errorWithSemantics(kind: String, message: String, state: DerivationState, trace: Vector[TraceEntry], stack: Vector[EvaluationResult]): Canon =
    Canon.node(
      "verdict",
      Canon.Sym("error"),
      Canon.Sym(kind),
      Canon.S(message),
      state.toCanon,
      Canon.S(state.summary),
      Canon.L(trace.map(traceEntryCanon)),
      Canon.L(stack.map(evalResultCanon))
    )

  def isOk(verdict: Canon): Boolean = verdict match
    case Canon.Node("verdict", Canon.Sym("ok") +: _) => true
    case _                                           => false

  def result(verdict: Canon): Option[Canon] = verdict match
    case Canon.Node("verdict", args) if args.nonEmpty && args.head == Canon.Sym("ok") =>
      args.drop(1).headOption
    case _ => None

  def state(verdict: Canon): Option[DerivationState] = verdict match
    case Canon.Node("verdict", args) if args.nonEmpty && args.head == Canon.Sym("ok") && args.length >= 3 =>
      summon[Schema[DerivationState]].decode(args(2)).toOption
    case Canon.Node("verdict", args) if args.nonEmpty && args.head == Canon.Sym("error") && args.length >= 4 =>
      summon[Schema[DerivationState]].decode(args(3)).toOption
    case _ => None

  def trace(verdict: Canon): Option[Vector[Canon]] = verdict match
    case Canon.Node("verdict", args) if args.nonEmpty && args.length >= 4 =>
      val tracePayload = if args.length >= 5 && args(4) == Canon.L(Vector.empty) then None else if args.length >= 5 then Some(args(4)) else None
      val traceCanon = tracePayload.orElse(args.lastOption)
      traceCanon match
        case Some(Canon.L(items)) =>
          val entries = items.foldLeft[Either[String, Vector[Canon]]](Right(Vector.empty)) { (acc, item) =>
            acc.flatMap { values =>
              item match
                case Canon.Node("trace-entry", args) =>
                  Right(values :+ args.drop(1).headOption.getOrElse(item))
                case other => Right(values :+ other)
            }
          }
          entries.toOption
        case _ => None
    case _ => None

  def traceEntries(verdict: Canon): Option[Vector[TraceEntry]] =
    val fallbackState = state(verdict).getOrElse(DerivationState(Program.empty, Kernel.pure, Budget.default, Evidence(0L, Map.empty, Map.empty)))
    verdict match
      case Canon.Node("verdict", args) if args.nonEmpty && args.length >= 4 =>
        val payload = args.findLast {
          case Canon.L(items) => items.exists {
              case Canon.Node("trace-entry", _) => true
              case _ => false
            }
          case _ => false
        }
        payload match
          case Some(Canon.L(items)) =>
            val parsed = items.foldLeft[Either[String, Vector[TraceEntry]]](Right(Vector.empty)) { (acc, item) =>
              acc.flatMap { entries =>
                item match
                  case Canon.Node("trace-entry", childArgs) if childArgs.nonEmpty =>
                    val kind = childArgs.head match
                      case Canon.Sym(value) => value
                      case other            => CanonText.write(other)
                    val value = childArgs.lift(1).getOrElse(Canon.Sym("unknown"))
                    val beforeCanon = childArgs.lift(2)
                    val afterCanon = childArgs.lift(3)
                    val changeCanon = childArgs.lift(4).getOrElse(Canon.Sym("noop"))
                    val frameCanon = childArgs.lift(5)
                    val before = beforeCanon.flatMap(c => summon[Schema[DerivationState]].decode(c).toOption).getOrElse(fallbackState)
                    val after = afterCanon.flatMap(c => summon[Schema[DerivationState]].decode(c).toOption).getOrElse(before)
                    val frame = frameCanon match
                      case Some(canon) => MachineFrame.fromCanon(canon, after)
                      case None        => machineFrame(after)
                    Right(entries :+ TraceEntry(kind, value, before, after, changeCanon, frame))
                  case other => Right(entries :+ TraceEntry("step", other, fallbackState, fallbackState, Canon.Sym("noop"), machineFrame(fallbackState)))
              }
            }
            parsed.toOption
          case _ => None
      case _ => None

  def traceLines(verdict: Canon): Vector[String] =
    traceEntries(verdict).getOrElse(Vector.empty).map { entry =>
      val renderedValue = entry.value match
        case Canon.Node("eval", args) if args.length >= 3 =>
          val expr = args(0)
          val result = args(1)
          val frameSuffix = s" [frame: ${entry.frame.summary}]"
          s"${CanonText.write(expr)} => ${CanonText.write(result)}$frameSuffix"
        case other => CanonText.write(other)
      val changeSuffix =
        if entry.change == Canon.Sym("noop") then ""
        else s" [change: ${CanonText.write(entry.change)}]"
      val stateSuffix = s" [state: ${entry.before.machineView} -> ${entry.after.machineView}]"
      s"${entry.kind}: $renderedValue$changeSuffix$stateSuffix"
    }

  def traceSemantics(verdict: Canon): Option[Vector[SemanticTraceStep]] =
    traceEntries(verdict).map(_.map(semanticStep))

  def evaluationStack(verdict: Canon): Option[Vector[EvaluationResult]] =
    verdict match
      case Canon.Node("verdict", args) if args.nonEmpty && args.length >= 4 =>
        val stackPayload = if args.length >= 6 then Some(args(5)) else None
        stackPayload.orElse(args.lastOption) match
          case Some(Canon.L(items)) =>
            val steps = items.foldLeft[Either[String, Vector[EvaluationResult]]](Right(Vector.empty)) { (acc, item) =>
              acc.flatMap { results =>
                item match
                  case Canon.Node("eval-result", Vector(Canon.Sym("eval"), expr, value, depth, frameCanon, contextCanon, beforeCanon, afterCanon, parentCanon)) =>
                    val fallbackState = DerivationState(Program.empty, Kernel.pure, Budget.default, Evidence(0L, Map.empty, Map.empty))
                    val frame = MachineFrame.fromCanon(frameCanon, fallbackState)
                    val context = EvaluationContext.fromCanon(contextCanon, frame.state)
                    val before = summon[Schema[DerivationState]].decode(beforeCanon).getOrElse(frame.state)
                    val after = summon[Schema[DerivationState]].decode(afterCanon).getOrElse(frame.state)
                    val depthValue = depth match
                      case Canon.N(n) => n.toInt
                      case _          => context.depth
                    val parentIndex = parentCanon match
                      case Canon.N(n) => Some(n.toInt)
                      case Canon.Sym("root") => None
                      case _ => None
                    val result = EvaluationResult(
                      expr,
                      value,
                      context.env,
                      depthValue,
                      before,
                      after,
                      frame,
                      context.snapshot,
                      None,
                      parentIndex
                    )
                    Right(results :+ result)
                  case Canon.Node("eval-result", Vector(Canon.Sym("eval"), expr, value, depth, frameCanon, contextCanon, beforeCanon, afterCanon)) =>
                    val fallbackState = DerivationState(Program.empty, Kernel.pure, Budget.default, Evidence(0L, Map.empty, Map.empty))
                    val frame = MachineFrame.fromCanon(frameCanon, fallbackState)
                    val context = EvaluationContext.fromCanon(contextCanon, frame.state)
                    val before = summon[Schema[DerivationState]].decode(beforeCanon).getOrElse(frame.state)
                    val after = summon[Schema[DerivationState]].decode(afterCanon).getOrElse(frame.state)
                    val depthValue = depth match
                      case Canon.N(n) => n.toInt
                      case _          => context.depth
                    val result = EvaluationResult(
                      expr,
                      value,
                      context.env,
                      depthValue,
                      before,
                      after,
                      frame,
                      context.snapshot,
                      None,
                      None
                    )
                    Right(results :+ result)
                  case Canon.Node("eval-result", Vector(Canon.Sym("eval"), expr, value, depth, frameCanon, contextCanon)) =>
                    val fallbackState = DerivationState(Program.empty, Kernel.pure, Budget.default, Evidence(0L, Map.empty, Map.empty))
                    val frame = MachineFrame.fromCanon(frameCanon, fallbackState)
                    val context = EvaluationContext.fromCanon(contextCanon, frame.state)
                    val depthValue = depth match
                      case Canon.N(n) => n.toInt
                      case _          => context.depth
                    val result = EvaluationResult(
                      expr,
                      value,
                      context.env,
                      depthValue,
                      frame.state,
                      frame.state,
                      frame,
                      context.snapshot,
                      None,
                      None
                    )
                    Right(results :+ result)
                  case Canon.Node("eval-result", Vector(Canon.Sym("eval"), expr, value, depth, frameCanon)) =>
                    val fallbackState = DerivationState(Program.empty, Kernel.pure, Budget.default, Evidence(0L, Map.empty, Map.empty))
                    val frame = MachineFrame.fromCanon(frameCanon, fallbackState)
                    val context = EvaluationContext(Map.empty, 0, frame, SemanticSnapshot.fromState(frame.state))
                    val depthValue = depth match
                      case Canon.N(n) => n.toInt
                      case _          => context.depth
                    val result = EvaluationResult(
                      expr,
                      value,
                      context.env,
                      depthValue,
                      frame.state,
                      frame.state,
                      frame,
                      context.snapshot,
                      None,
                      None
                    )
                    Right(results :+ result)
                  case _ => Right(results)
              }
            }
            steps.toOption
          case _ => None
      case _ => None

  def evaluationTree(verdict: Canon): Option[EvaluationTree] =
    evaluationStack(verdict).flatMap { stack =>
      val childrenByParent = stack.zipWithIndex.flatMap { case (result, index) =>
        result.parentIndex.toVector.map(parentIndex => parentIndex -> index)
      }.groupMap(_._1)(_._2)
      def build(index: Int): EvaluationTree =
        val result = stack(index)
        val childIndices = childrenByParent.getOrElse(index, Vector.empty).sorted
        EvaluationTree(result, childIndices.map(build))
      val roots = stack.indices.filter(i => stack(i).parentIndex.isEmpty).toVector
      roots.headOption.map(build).orElse(stack.headOption.map(result => EvaluationTree(result, Vector.empty)))
    }

  def evaluationSemantics(verdict: Canon): Option[EvaluationSemantics] =
    for
      stack <- evaluationStack(verdict)
      tree <- evaluationTree(verdict)
    yield EvaluationSemantics(stack, tree)

  def evaluationTreeLines(verdict: Canon): Vector[String] =
    evaluationTree(verdict).toVector.flatMap(_.lines)

  def replayState(current: DerivationState, next: DerivationState): Either[String, DerivationState] =
    summon[ChangeUpdater[DerivationState]].update(current, summon[ChangeAlgebra[DerivationState]].delta(current, next))

  def replayTrace(current: DerivationState, trace: Vector[TraceEntry]): Either[String, DerivationState] =
    trace.foldLeft[Either[String, DerivationState]](Right(current)) { (acc, entry) =>
      acc.flatMap { state =>
        if entry.change == Canon.Sym("noop") then Right(state)
        else summon[ChangeUpdater[DerivationState]].update(state, entry.change)
      }
    }

  def replayTraceToState(current: DerivationState, trace: Vector[TraceEntry]): Either[String, DerivationState] =
    trace.foldLeft[Either[String, DerivationState]](Right(current)) { (acc, entry) =>
      acc.flatMap { state =>
        val fromBefore = if state == entry.before then Right(state) else Left(s"trace entry expected state $state but found ${entry.before}")
        fromBefore.flatMap { _ =>
          if entry.change == Canon.Sym("noop") then Right(entry.after)
          else summon[ChangeUpdater[DerivationState]].update(state, entry.change).map(_.copy())
        }
      }
    }

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
      capabilities: CapabilityHandler,
      includeSemantics: Boolean = true
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
      val trace = engine.traceEntries
      val derived = language.run(state)
      derived match
        case Right(canon) =>
          if includeSemantics then okWithSemantics(value, state, trace, engine.evaluationStack)
          else ok(value, state)
        case Left(message) =>
          if includeSemantics then errorWithSemantics("derivation-graph", message, state, trace, engine.evaluationStack)
          else error("derivation-graph", message, state)
    catch
      case f: MetaFail          =>
        val state = engine.stateSnapshot
        if includeSemantics then errorWithSemantics(f.kind, f.message, state, engine.traceEntries, engine.evaluationStack)
        else error(f.kind, f.message, state)
      case _: StackOverflowError =>
        val state = engine.stateSnapshot
        if includeSemantics then errorWithSemantics("depth-exhausted", "derivation exceeded host stack", state, engine.traceEntries, engine.evaluationStack)
        else error("depth-exhausted", "derivation exceeded host stack", state)

  private final class Engine(program: Program, kernel: Kernel, budget: Budget, capabilities: CapabilityHandler):
    private var steps: Long = 0
    private val calls = mutable.LinkedHashMap.empty[String, Long]
    private val caps = mutable.LinkedHashMap.empty[String, Long]
    private val transitions = mutable.ArrayBuffer.empty[TraceEntry]
    private var state: DerivationState = DerivationState(program, kernel, budget, Evidence(0L, Map.empty, Map.empty))

    def evidence: Evidence = Evidence(steps, calls.toMap, caps.toMap)
    def stateSnapshot: DerivationState = state
    def trace: Vector[Canon] = transitions.toVector.map(entry => Canon.node("trace-entry", Canon.Sym(entry.kind), entry.value, entry.before.toCanon, entry.after.toCanon))
    def traceEntries: Vector[TraceEntry] = transitions.toVector
    def evaluationStack: Vector[EvaluationResult] = evalStack

    private def syncState(): Unit =
      val next = DerivationState(program, kernel, budget, evidence)
      val delta = summon[ChangeAlgebra[DerivationState]].delta(state, next)
      transitions.append(TraceEntry("state-change", delta, state, next, delta, machineFrame(next)))
      state = replayState(state, next).getOrElse(state)

    private def tick(): Unit =
      steps += 1
      syncState()
      if steps > budget.steps then throw MetaFail("resource-exhausted", s"budget of ${budget.steps} steps exhausted")

    private def fail(kind: String, msg: String): Nothing = throw MetaFail(kind, msg)

    private var evalStack: Vector[EvaluationResult] = Vector.empty

    def eval(expr: Canon, env: Map[String, Canon], depth: Int): Canon =
      tick()
      if depth > budget.depth then fail("depth-exhausted", s"depth budget ${budget.depth} exceeded")
      val beforeState = stateSnapshot
      val result = expr match
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
          val previousState = stateSnapshot
          val transition = Canon.node("judgment", Canon.Sym(name), Canon.L(args))
          syncState()
          val change = summon[ChangeAlgebra[DerivationState]].delta(previousState, stateSnapshot)
          transitions.append(TraceEntry("judgment", transition, previousState, stateSnapshot, change, machineFrame(stateSnapshot)))
          eval(j.body, j.params.zip(args).toMap, depth + 1)

        case Canon.Node("match", scrutinee +: cases) =>
          val v = eval(scrutinee, env, depth + 1)
          matchCases(v, cases, env, depth)

        case Canon.Node("prim", Canon.Sym(name) +: argExprs) =>
          Prims.apply(name, argExprs.map(eval(_, env, depth + 1)), fail)

        case Canon.Node("cap", Canon.Sym(name) +: argExprs) =>
          if !kernel.allow.contains(name) then fail("capability-denied", s"capability $name is not constituted")
          caps.updateWith(name)(c => Some(c.getOrElse(0L) + 1))
          val args = argExprs.map(eval(_, env, depth + 1))
          val previousState = stateSnapshot
          syncState()
          val change = summon[ChangeAlgebra[DerivationState]].delta(previousState, stateSnapshot)
          transitions.append(TraceEntry("capability", Canon.node("cap", Canon.Sym(name), Canon.L(args)), previousState, stateSnapshot, change, machineFrame(stateSnapshot)))
          val response = capabilities.handle(CapabilityRequest(name, args))
          response.toCanon

        case Canon.Node("fail", Vector(Canon.Sym(kind), message)) =>
          eval(message, env, depth + 1) match
            case Canon.S(m) => fail(kind, m)
            case other      => fail(kind, CanonText.write(other))

        case other => fail("bad-expression", s"not an expression: ${CanonText.write(other)}")
      val afterState = stateSnapshot
      val change = summon[ChangeAlgebra[DerivationState]].delta(beforeState, afterState)
      val frame = Canon.node(
        "machine-frame",
        Canon.node("env", Canon.M(env.toVector.sortBy(_._1).map((k, v) => Canon.Sym(k) -> v))),
        Canon.node("depth", Canon.N(BigInt(depth))),
        Canon.node("state", state.toCanon),
        Canon.node("state-summary", Canon.S(state.summary))
      )
      val context = Canon.node(
        "eval",
        expr,
        result,
        frame
      )
      val parentResult = evalStack.lastOption
      val parentIndex = parentResult.map(_ => evalStack.length - 1)
      val evaluationResult = EvaluationResult(
        expr,
        result,
        env,
        depth,
        beforeState,
        afterState,
        machineFrame(afterState),
        SemanticSnapshot.fromState(afterState),
        parentResult,
        parentIndex
      )
      evalStack = evalStack :+ evaluationResult
      transitions.append(TraceEntry("eval", context, beforeState, afterState, change, machineFrame(afterState)))
      result

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
