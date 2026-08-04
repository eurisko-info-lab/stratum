package stratum

import stratum.artifact.{Artifact, MemoryCas}
import stratum.canon.{Canon, CanonCodec, CanonText, Digest, References, Schema, SchemaLaws, ChangeAlgebra, ChangeAlgebraLaws, ChangeComposer, ChangeUpdater, Relation, RelationLaws, Projection, DerivationGraph, DerivationLanguage}
import stratum.cli.{Cli, HostCore}
import stratum.cap.CapabilityHandler
import stratum.meta.{Budget, DerivationState, Evidence, Kernel, Program, Judgment, MetaMachine0}
import stratum.repo.{DeclaredLanguage, StructuredFile}

import java.nio.file.{Files, Path, Paths}

final case class DerivedValue(label: String, count: Int)

/**
 * The host core freeze gate.
 *
 * `NativeBoundarySuite` keeps the host feature agnostic. This suite pins its
 * *identity*: the canonical tags, the Meta0 forms and primitive set, the
 * Grammar0 forms, the evidence shape and the verdict forms. Every foundation
 * from F1 onward references exactly this identity, so widening the fixed
 * calculus is a deliberate, reviewed act rather than an accident.
 */
class HostCoreSuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val committedPath: Path = root.resolve("host/core.canon")

  private def committedManifest: Canon =
    CanonText.read(Files.readString(committedPath)) match
      case Right(value) => value
      case Left(message) => fail(s"host/core.canon is not canonical text: $message")

  private def committedReference: Digest =
    Artifact("data", committedManifest).digest

  private def foundations: Vector[String] =
    Vector("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11")

  test("the committed host core manifest matches the live host") {
    assertEquals(
      CanonText.write(committedManifest),
      CanonText.write(HostCore.manifest),
      "regenerate with `stratum host manifest --out host/core.canon` and review the change"
    )
  }

  test("the primitive set is sorted and free of duplicates") {
    assertEquals(HostCore.primitives, HostCore.primitives.sorted)
    assertEquals(HostCore.primitives.distinct.length, HostCore.primitives.length)
  }

  test("every foundation from F1 onward references the same host core") {
    val expected = committedReference
    foundations.foreach { name =>
      Cli.loadFoundation(root, root.resolve(s"foundations/$name")) match
        case Left(message) => fail(s"$name: $message")
        case Right(foundation) =>
          val declared = foundation.application match
            case Canon.Node(_, args) =>
              args.collectFirst { case Canon.Node("host", Vector(Canon.R(reference))) => reference }
            case _ => None
          assertEquals(
            declared,
            Some(expected),
            s"$name does not reference the frozen host core ${expected.hex}"
          )
    }
  }

  test("scala derivation can synthesize schema and codec for product values") {
    val value = DerivedValue("alpha", 7)
    val schema = summon[Schema[DerivedValue]]
    val codec = summon[CanonCodec[DerivedValue]]
    assertEquals(schema.decode(schema.encode(value)), Right(value), "derived schema should round-trip product values")
    assertEquals(codec.decode(codec.encode(value)), Right(value), "derived codec should round-trip product values")
  }

  test("F0 predates the freeze") {
    Cli.loadFoundation(root, root.resolve("foundations/F0")) match
      case Left(message) => fail(s"F0: $message")
      case Right(foundation) =>
        val declared = foundation.application match
          case Canon.Node(_, args) => args.collectFirst { case Canon.Node("host", _) => true }
          case _                   => None
        assertEquals(declared, None, "the freeze is published by F1, not F0")
  }

  test("core host values expose schema-driven codecs and reference traversal") {
    val budgetCodec = summon[CanonCodec[Budget]]
    val budgetSchema = summon[Schema[Budget]]
    val budgetLaws = summon[SchemaLaws[Budget]]
    val budgetChange = summon[ChangeAlgebra[Budget]]
    val budget = Budget(1234L, 8)
    assertEquals(budgetCodec.decode(budgetCodec.encode(budget)), Right(budget))
    assertEquals(budgetSchema.decode(budgetSchema.encode(budget)), Right(budget))
    assertEquals(budgetLaws.roundTrip(budget), Right(()), "budget round-trip law")
    assertEquals(budgetLaws.referenceTraversal(budget), Right(()), "budget reference law")
    val budgetChangeLaws = summon[ChangeAlgebraLaws[Budget]]
    val budgetChangeComposer = summon[ChangeComposer[Budget]]
    val budgetChangeUpdater = summon[ChangeUpdater[Budget]]
    val genericChange = summon[ChangeAlgebra[Long]]
    val genericChangeComposer = summon[ChangeComposer[Long]]
    val genericChangeUpdater = summon[ChangeUpdater[Long]]
    val budgetRelation = summon[Relation[Budget, Canon]]
    val budgetRelationLaws = summon[RelationLaws[Budget, Canon]]
    val budgetProjection = summon[Projection[Budget, Canon]]
    val budgetGraph = summon[DerivationGraph[Budget, Canon]]
    val budgetLanguage = summon[DerivationLanguage[Budget, Canon]]
    val budgetDelta = budgetChange.delta(Budget(1234L, 8), Budget(2345L, 9))
    assertEquals(budgetChange.patch(Budget(1234L, 8), budgetDelta), Right(Budget(2345L, 9)), "budget change algebra")
    assertEquals(budgetChangeLaws.deltaPatchRoundTrip(Budget(1234L, 8), Budget(2345L, 9)), Right(()), "budget change law")
    assertEquals(genericChange.delta(7L, 11L), Canon.node("replace", Canon.N(BigInt(7L)), Canon.N(BigInt(11L))), "generic change delta")
    assertEquals(genericChange.patch(7L, genericChange.delta(7L, 11L)), Right(11L), "generic change patch")
    val composite = Canon.Node("compose", Vector(genericChange.delta(1L, 3L), genericChange.delta(3L, 8L)))
    assertEquals(genericChangeComposer.compose(Vector(genericChange.delta(1L, 3L), genericChange.delta(3L, 8L))), Right(composite), "generic change composition")
    assertEquals(genericChangeUpdater.update(1L, composite), Right(8L), "generic change replay")
    assertEquals(budgetChangeComposer.compose(Vector(budgetDelta)), Right(budgetDelta), "budget change composition")
    assertEquals(budgetChangeUpdater.update(Budget(1234L, 8), budgetDelta), Right(Budget(2345L, 9)), "budget change update")
    assertEquals(budgetRelation.derive(budget), Right(budgetSchema.encode(budget)), "budget relation derivation")
    assertEquals(budgetRelationLaws.derivePreservesSchema(budget), Right(()), "budget relation law")
    assertEquals(budgetProjection.project(budget), Right(budgetSchema.encode(budget)), "budget projection")
    assertEquals(Budget.deriveCanon(budget), Right(budgetSchema.encode(budget)), "budget derivation helper")
    assertEquals(Budget.applyChange(Budget(1234L, 8), budgetDelta), Right(Budget(2345L, 9)), "budget change helper")
    assertEquals(budgetGraph.relation.derive(budget), Right(budgetSchema.encode(budget)), "budget graph relation")

    val container = Vector("alpha", "beta")
    val containerSchema = summon[Schema[Vector[String]]]
    val containerChange = summon[ChangeAlgebra[Vector[String]]]
    val containerDelta = containerChange.delta(Vector("alpha"), Vector("alpha", "beta"))
    assertEquals(containerSchema.decode(containerSchema.encode(container)), Right(container), "container schema round-trip")
    assertEquals(containerChange.patch(Vector("alpha"), containerDelta), Right(container), "container change patch")
    assertEquals(containerChange.delta(Vector("alpha"), Vector("alpha")), Canon.node("replace", containerSchema.encode(Vector("alpha")), containerSchema.encode(Vector("alpha"))), "container change delta")

    val mapValue = Map("alpha" -> 1, "beta" -> 2)
    val mapSchema = summon[Schema[Map[String, Int]]]
    val mapChange = summon[ChangeAlgebra[Map[String, Int]]]
    val mapDelta = mapChange.delta(Map("alpha" -> 1), mapValue)
    assertEquals(mapSchema.decode(mapSchema.encode(mapValue)), Right(mapValue), "map schema round-trip")
    assertEquals(mapChange.patch(Map("alpha" -> 1), mapDelta), Right(mapValue), "map change patch")

    val setValue = Set("alpha", "beta")
    val setSchema = summon[Schema[Set[String]]]
    assertEquals(setSchema.decode(setSchema.encode(setValue)), Right(setValue), "set schema round-trip")

    val eitherValue = Right(7)
    val eitherSchema = summon[Schema[Either[String, Int]]]
    val eitherChange = summon[ChangeAlgebra[Either[String, Int]]]
    val eitherDelta = eitherChange.delta(Left("old"), eitherValue)
    assertEquals(eitherSchema.decode(eitherSchema.encode(eitherValue)), Right(eitherValue), "either schema round-trip")
    assertEquals(eitherChange.patch(Left("old"), eitherDelta), Right(eitherValue), "either change patch")
    assertEquals(budgetGraph.projection.project(budget), Right(budgetSchema.encode(budget)), "budget graph projection")
    assertEquals(budgetGraph.change.patch(Budget(1234L, 8), budgetDelta), Right(Budget(2345L, 9)), "budget graph change")
    assertEquals(budgetLanguage.run(budget), Right(budgetSchema.encode(budget)), "budget derivation language")

    val compositeGraph = DerivationGraph.instance[
      Budget
    ](
      summon[Schema[Budget]],
      new Relation[Budget, Canon] {
        def derive(input: Budget): Either[String, Canon] = Right(summon[Schema[Budget]].encode(input))
      },
      new Projection[Budget, Canon] {
        def project(value: Budget): Either[String, Canon] = Right(summon[Schema[Budget]].encode(value))
      },
      summon[ChangeAlgebra[Budget]]
    )
    val compositeLanguage = DerivationLanguage.instance(compositeGraph)
    val compositeResult = compositeLanguage.run(Budget(1234L, 8))
    assertEquals(compositeResult, Right(budgetSchema.encode(budget)), "composite derivation language")

    val artifact = Artifact("demo", Canon.node("ref", Canon.R(Digest.of(Array[Byte](1, 2, 3)))))
    val artifactCodec = summon[CanonCodec[Artifact]]
    assertEquals(artifactCodec.decode(artifactCodec.encode(artifact)), Right(artifact))
    assertEquals(summon[References[Artifact]].refs(artifact), Vector(Digest.of(Array[Byte](1, 2, 3))))

    val kernelCodec = summon[CanonCodec[Kernel]]
    val kernel = Kernel(Set("cas-get", "hash"))
    assertEquals(kernelCodec.decode(kernelCodec.encode(kernel)), Right(kernel))
    assertEquals(Kernel.deriveCanon(kernel), Right(kernelCodec.encode(kernel)))
    assertEquals(Kernel.applyChange(kernel, kernelCodec.encode(kernel)), Right(kernel))

    val program = Program(Map("demo" -> Judgment("demo", Vector("x"), Canon.Sym("ok"))), Vector("mod"))
    val programCodec = summon[CanonCodec[Program]]
    assertEquals(programCodec.decode(programCodec.encode(program)), Right(program))
    assertEquals(Program.deriveCanon(program), Right(programCodec.encode(program)))
    assertEquals(Program.applyChange(program, programCodec.encode(program)), Right(program))

    val state = DerivationState(program, kernel, Budget(9L, 2), Evidence(7L, Map("hash" -> 3L), Map("fs-read" -> 2L)))
    val stateCodec = summon[CanonCodec[DerivationState]]
    assertEquals(stateCodec.decode(stateCodec.encode(state)), Right(state))
    assertEquals(DerivationState.deriveCanon(state), Right(stateCodec.encode(state)))
    assertEquals(DerivationState.applyChange(state, stateCodec.encode(state)), Right(state))
    val nextProgram = Program(Map("demo" -> Judgment("demo", Vector("x"), Canon.Sym("ok")), "next" -> Judgment("next", Vector.empty, Canon.Sym("done"))), Vector("mod", "next"))
    val stateChange = Canon.node(
      "state-change",
      Canon.node("program", summon[ChangeAlgebra[Program]].delta(program, nextProgram)),
      Canon.node("budget", summon[ChangeAlgebra[Budget]].delta(Budget(9L, 2), Budget(12L, 4)))
    )
    assertEquals(summon[ChangeAlgebra[DerivationState]].patch(state, stateChange), Right(state.copy(program = nextProgram, budget = Budget(12L, 4))), "derivation state partial patch")
    val composedChange = summon[ChangeComposer[DerivationState]].compose(Vector(
      Canon.node("program", summon[ChangeAlgebra[Program]].delta(program, nextProgram)),
      Canon.node("budget", summon[ChangeAlgebra[Budget]].delta(Budget(9L, 2), Budget(12L, 4)))
    ))
    assertEquals(composedChange, Right(Canon.node("state-change", Canon.node("program", summon[ChangeAlgebra[Program]].delta(program, nextProgram)), Canon.node("budget", summon[ChangeAlgebra[Budget]].delta(Budget(9L, 2), Budget(12L, 4))))), "derivation state composition")
    assertEquals(summon[ChangeUpdater[DerivationState]].update(state, composedChange.getOrElse(Canon.Sym("noop"))), Right(state.copy(program = nextProgram, budget = Budget(12L, 4))), "derivation state update replay")
    val derivedState = state.copy(evidence = Evidence(11L, Map("hash" -> 4L), Map("fs-read" -> 3L)))
    val stateDelta = summon[ChangeAlgebra[DerivationState]].delta(state, derivedState)
    assertEquals(stateDelta, Canon.node("state-change", Canon.node("evidence", summon[ChangeAlgebra[Evidence]].delta(state.evidence, derivedState.evidence))), "derivation state delta")
    assertEquals(summon[ChangeUpdater[DerivationState]].update(state, stateDelta), Right(derivedState), "derivation state delta replay")
    assertEquals(MetaMachine0.replayState(state, derivedState), Right(derivedState), "meta-machine state replay")
    val verdict = MetaMachine0.ok(Canon.S("done"), state)
    val tracedVerdict = MetaMachine0.ok(Canon.S("done"), state, Vector(Canon.S("step-1")))
    assertEquals(MetaMachine0.result(verdict), Some(Canon.S("done")))
    assertEquals(MetaMachine0.state(verdict), Some(state))
    assertEquals(MetaMachine0.result(tracedVerdict), Some(Canon.S("done")))
    assertEquals(MetaMachine0.state(tracedVerdict), Some(state))
    assertEquals(MetaMachine0.trace(tracedVerdict), Some(Vector(Canon.S("step-1"))))
    assertEquals(MetaMachine0.traceEntries(tracedVerdict), Some(Vector(MetaMachine0.TraceEntry("step", Canon.S("step-1"), state, state, Canon.Sym("noop"), MetaMachine0.MachineFrame(state, state.summary)))))
    assertEquals(MetaMachine0.traceLines(tracedVerdict).head, "step: \"step-1\" [state: steps=7; calls=hash=3; caps=fs-read=2; budget=9/2; modules=mod -> steps=7; calls=hash=3; caps=fs-read=2; budget=9/2; modules=mod]")
    assertEquals(MetaMachine0.replayTrace(state, Vector(MetaMachine0.TraceEntry("step", Canon.S("step-1"), state, state, Canon.Sym("noop"), MetaMachine0.MachineFrame(state, state.summary)))), Right(state))

    val derivedVerdict = MetaMachine0.derive(
      Program(Map("demo" -> Judgment("demo", Vector.empty, Canon.Node("let", Vector(Canon.Sym("x"), Canon.Node("q", Vector(Canon.S("done"))), Canon.Node("q", Vector(Canon.Sym("x"))))))), Vector("mod")),
      new MemoryCas(),
      Kernel.pure,
      Budget(100L, 5),
      Canon.Node("call", Vector(Canon.Sym("demo"))),
      CapabilityHandler.empty
    )
    val derivedTraceEntries = MetaMachine0.traceEntries(derivedVerdict).getOrElse(Vector.empty)
    assertEquals(derivedTraceEntries.nonEmpty, true, "derived verdict should expose evaluation trace entries")
    assertEquals(derivedTraceEntries.exists(entry => entry.kind == "eval"), true, "evaluation should record explicit step transitions")
    assertEquals(derivedTraceEntries.exists(entry => entry.kind == "eval" && (entry.value match
      case Canon.Node("eval", Vector(_, _, _)) => true
      case _ => false)), true, "eval trace entries should capture both the expression and its result")
    assertEquals(derivedTraceEntries.exists(entry => entry.kind == "judgment" && entry.change != Canon.Sym("noop")), true, "judgment transitions should carry concrete change payloads")
    assertEquals(derivedTraceEntries.exists(entry => entry.kind == "judgment" && entry.frame.summary.contains("budget=")), true, "judgment transitions should carry a semantic frame")
    assertEquals(MetaMachine0.traceLines(derivedVerdict).exists(_.contains("change")), true, "trace lines should surface concrete change payloads")
    assertEquals(MetaMachine0.traceLines(derivedVerdict).exists(_.contains("=>")), true, "eval trace lines should render expression-to-result transitions")
    assertEquals(derivedTraceEntries.exists(entry => entry.kind == "eval" && (entry.value match
      case Canon.Node("eval", Vector(_, _, Canon.Node("machine-frame", frameFields))) =>
        frameFields.exists {
          case Canon.Node("state-summary", _) => true
          case Canon.Node("state", _) => true
          case _ => false
        }
      case _ => false)), true, "eval trace entries should expose a machine frame with state and summary")
    assertEquals(derivedTraceEntries.forall(entry => entry.frame.summary.nonEmpty), true, "trace entries should carry a machine-frame payload")
    assertEquals(MetaMachine0.traceLines(derivedVerdict).exists(_.contains("frame:")), true, "trace lines should surface the structured machine frame")
    assertEquals(derivedTraceEntries.exists(entry => entry.frame.summary.contains("budget=")), true, "machine frames should expose a reusable semantic summary")
    assertEquals(derivedTraceEntries.exists(entry => entry.kind == "eval" && entry.frame.summary.contains("modules=")), true, "eval entries should carry a semantic context summary")
    assertEquals(derivedTraceEntries.exists(entry => entry.kind == "eval" && entry.frame.summary.contains("budget=")), true, "eval entries should carry a semantic snapshot summary")
    val semanticTrace = MetaMachine0.traceSemantics(derivedVerdict).getOrElse(Vector.empty)
    assertEquals(semanticTrace.nonEmpty, true, "derived verdicts should expose a unified semantic trace view")
    assertEquals(semanticTrace.exists(step => step.kind == "judgment" && step.judgment.exists(_.name == "demo")), true, "semantic trace should connect judgment steps to their semantic payload")
    assertEquals(semanticTrace.exists(step => step.kind == "eval" && step.evaluation.exists(_.depth > 0)), true, "eval semantics should preserve the runtime evaluation depth")
    val evaluationStack = MetaMachine0.evaluationStack(derivedVerdict).getOrElse(Vector.empty)
    assertEquals(evaluationStack.nonEmpty, true, "derived verdicts should expose their semantic evaluation stack")
    assertEquals(evaluationStack.exists(_.env.nonEmpty), true, "semantic evaluation stack should preserve runtime environment bindings")
    val evaluationSemantics = MetaMachine0.evaluationSemantics(derivedVerdict).getOrElse(MetaMachine0.EvaluationSemantics(Vector.empty, MetaMachine0.EvaluationTree(MetaMachine0.EvaluationResult(Canon.Sym("noop"), Canon.Sym("noop"), Map.empty, 0, state, state, MetaMachine0.MachineFrame(state, state.summary), MetaMachine0.SemanticSnapshot.fromState(state)))))
    assertEquals(evaluationSemantics.stack.nonEmpty, true, "evaluation semantics should expose the evaluation stack")
    assertEquals(evaluationSemantics.tree.children.nonEmpty, true, "evaluation semantics should expose the evaluation tree")
    val evaluationTree = MetaMachine0.evaluationTree(derivedVerdict).getOrElse(MetaMachine0.EvaluationTree(MetaMachine0.EvaluationResult(Canon.Sym("noop"), Canon.Sym("noop"), Map.empty, 0, state, state, MetaMachine0.MachineFrame(state, state.summary), MetaMachine0.SemanticSnapshot.fromState(state))))
    assertEquals(evaluationTree.children.nonEmpty, true, "evaluation trees should expose nested child evaluations")
    assertEquals(MetaMachine0.evaluationTreeLines(derivedVerdict).exists(_.contains("eval-tree")), true, "evaluation trees should render as inspectable semantic lines")
    assertEquals(evaluationStack.headOption.exists(_.toCanon match
      case Canon.Node("eval-result", _) => true
      case _ => false), true, "evaluation results should expose a canonical semantic value")
    assertEquals(
      evaluationTree.toCanon match
        case Canon.Node("eval-tree", _) => true
        case _ => false,
      true,
      "evaluation trees should be canonicalizable for meta-programming"
    )
    val roundTripTree = MetaMachine0.EvaluationTree.fromCanon(evaluationTree.toCanon, state)
    assertEquals(roundTripTree.exists(_.flatten.size == evaluationTree.flatten.size), true, "evaluation trees should round-trip through canon for meta-programming")
    assertEquals(MetaMachine0.state(derivedVerdict).exists(_.machineView.contains("modules=mod")), true, "derived verdicts should expose a compact semantic state summary")
    assertEquals(MetaMachine0.state(derivedVerdict).exists(_.summary.contains("budget=")), true, "derived verdicts should expose a reusable semantic summary")
    assertEquals(MetaMachine0.result(derivedVerdict).exists(_ => true), true, "derived verdicts should still expose their result value")

    val evidence = Evidence(7L, Map("hash" -> 3L), Map("fs-read" -> 2L))
    val evidenceCodec = summon[CanonCodec[Evidence]]
    assertEquals(evidenceCodec.decode(evidenceCodec.encode(evidence)), Right(evidence))
    assertEquals(Evidence.deriveCanon(evidence), Right(evidenceCodec.encode(evidence)))
    assertEquals(Evidence.applyChange(evidence, evidenceCodec.encode(evidence)), Right(evidence))

    val declared = DeclaredLanguage(
      Canon.node("LanguagePackage", Canon.Sym("demo"), Canon.L(Vector(Canon.S(".demo"))), Canon.L(Vector.empty), Canon.L(Vector.empty), Canon.Sym("none"), Canon.Sym("none"), Canon.L(Vector.empty), Canon.Sym("grammar")),
      "demo",
      Vector(".demo"),
      Vector.empty,
      Set.empty,
      None,
      None,
      "grammar",
      Vector.empty
    )
    val declaredSchema = summon[Schema[DeclaredLanguage]]
    val declaredLaws = summon[SchemaLaws[DeclaredLanguage]]
    assertEquals(declaredSchema.decode(declaredSchema.encode(declared)), Right(declared), "declared language round-trip")
    assertEquals(declaredLaws.roundTrip(declared), Right(()), "declared language law")

    val structured = StructuredFile(
      Digest.of(Array[Byte](9, 8, 7)),
      Some(Digest.of(Array[Byte](1, 2, 3))),
      Digest.of(Array[Byte](4, 5, 6)),
      Digest.of(Array[Byte](7, 8, 9)),
      Some(Digest.of(Array[Byte](10, 11, 12))),
      Some(Digest.of(Array[Byte](13, 14, 15))),
      Digest.of(Array[Byte](16, 17, 18))
    )
    val structuredSchema = summon[Schema[StructuredFile]]
    val structuredLaws = summon[SchemaLaws[StructuredFile]]
    assertEquals(structuredSchema.decode(structuredSchema.encode(structured)), Right(structured), "structured file round-trip")
    assertEquals(structuredLaws.roundTrip(structured), Right(()), "structured file law")
  }
