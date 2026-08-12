package stratum

import stratum.artifact.{Artifact, MemoryCas}
import stratum.cap.GrammarCapability
import stratum.canon.{Canon, CanonText}
import stratum.meta.{Budget, Kernel, MetaMachine0, Program}

import java.nio.file.{Files, Path, Paths}

/**
 * Phase 1 gate for the RustVM bytecode language and the Rust -> RustVM
 * compiler (features/rustvm/rustvm.meta, features/rustvm/compile.meta).
 *
 * Every case here runs through `RunSource`, the same 2-argument
 * `[grammar source]` judgment `repo-scala/StratumRepo.scala`'s `run` command
 * looks for -- so this suite is exercising the exact same entry point a
 * Stratum-managed project would use, not a special test-only shortcut. Once
 * these judgments are compiled Meta0, host-scala/host-rust agreement on every
 * verdict here is automatic via the existing generic parity machinery
 * (tools/parity.sh, HostParitySuite) once this work is promoted into a
 * foundation/application world -- this suite is the pre-promotion gate.
 */
class RustVmMachineSuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()

  private def readCanon(path: Path): Canon =
    CanonText.read(Files.readString(path)) match
      case Right(value) => value
      case Left(message) => fail(s"$path is not canonical text: $message")

  private lazy val cas = new MemoryCas()

  private lazy val grammarDigest =
    cas.put(Artifact("grammar", readCanon(root.resolve("languages/rust/rust.generated.grammar"))))

  private lazy val program: Program =
    val paths = Vector(
      "languages/meta/prelude.meta",
      "languages/meta/elaborate.meta",
      "languages/rust/rust.generated.meta",
      "features/rustvm/rustvm.generated.meta",
      "features/rustvm/compile.generated.meta"
    )
    paths.foldLeft(Program.empty) { (acc, relative) =>
      val loaded = Program.load(readCanon(root.resolve(relative)), cas) match
        case Right(value) => value
        case Left(message) => fail(s"$relative failed to load: $message")
      acc.merge(loaded)
    }

  private def runSource(source: String): Canon =
    val goal = Canon.node(
      "call",
      Canon.Sym("RunSource"),
      Canon.node("q", Canon.R(grammarDigest)),
      Canon.node("q", Canon.S(source))
    )
    val caps = new GrammarCapability(cas)
    MetaMachine0.derive(program, cas, Kernel(Set("grammar-parse", "grammar-print")), Budget(2_000_000_000L, 100_000), goal, caps)

  private def assertRunsTo(source: String, expected: BigInt): Unit =
    runSource(source) match
      case Canon.Node("verdict", Vector(Canon.Sym("ok"), Canon.N(value), _)) =>
        assertEquals(value, expected, s"RunSource($source) = $value, expected $expected")
      case other =>
        fail(s"RunSource did not succeed: ${CanonText.write(other)}")

  private def fixtureFiles: Vector[Path] =
    val dir = root.resolve("fixtures/rustvm")
    Files.list(dir).toArray.toVector.map(_.asInstanceOf[Path]).filter(_.toString.endsWith(".rs")).sortBy(_.toString)

  private val expectPattern = "expect:\\s*(-?\\d+)".r

  test("every fixtures/rustvm/*.rs fixture compiles and runs to its declared // expect: value") {
    val files = fixtureFiles
    assert(files.nonEmpty, "fixtures/rustvm should contain at least one fixture")
    files.foreach { path =>
      val text = Files.readString(path)
      val expected = expectPattern.findFirstMatchIn(text) match
        case Some(m) => BigInt(m.group(1))
        case None => fail(s"$path has no '// expect: N' comment")
      assertRunsTo(text, expected)
    }
  }

  test("RunProgram executes every opcode family directly on a hand-built Program") {
    def derive(goal: Canon): Canon =
      MetaMachine0.derive(program, cas, Kernel.pure, Budget(2_000_000L, 4000), goal, new stratum.cap.CapabilityHandler {
        def names: Set[String] = Set.empty
        def handle(req: stratum.cap.CapabilityRequest): stratum.cap.CapabilityResponse =
          throw new RuntimeException("no capabilities available in this test")
      })

    def runProgram(programCanonText: String): BigInt =
      val programCanon = CanonText.read(programCanonText).getOrElse(fail("bad program canon"))
      val goal = Canon.node("call", Canon.Sym("RunProgram"), Canon.node("q", programCanon), Canon.node("q", Canon.L(Vector.empty)), Canon.node("q", Canon.nat(1000)))
      derive(goal) match
        case Canon.Node("verdict", Vector(Canon.Sym("ok"), Canon.N(value), _)) => value
        case other => fail(s"RunProgram failed: ${CanonText.write(other)}")

    assertEquals(
      runProgram(
        "(program [(function main 0 3 [(LoadConst 0 2) (LoadConst 1 3) (IntBinOp 2 add 0 1) (LoadConst 0 4) (IntBinOp 2 mul 2 0) (Return 2)])] main)"
      ),
      BigInt(20)
    )
    assertEquals(
      runProgram(
        "(program [(function main 0 3 [(LoadConst 0 7) (LoadConst 1 9) (NewStruct 2 [0 1]) (GetField 2 2 1) (Return 2)])] main)"
      ),
      BigInt(9)
    )
    assertEquals(
      runProgram(
        "(program [(function main 0 3 [(LoadConst 0 5) (NewEnum 1 circle [0]) (TagEq 2 1 circle) (JumpIfFalse 2 6) (EnumField 0 1 0) (Jump 7) (LoadConst 0 0) (Return 0)])] main)"
      ),
      BigInt(5)
    )
    assertEquals(
      runProgram(
        "(program [(function main 0 5 [(LoadConst 0 10) (LoadConst 1 20) (LoadConst 2 30) (MakeArray 3 [0 1 2]) (LoadConst 4 1) (ArrayGet 4 3 4) (Return 4)])] main)"
      ),
      BigInt(20)
    )
    assertEquals(
      runProgram(
        "(program [(function main 0 2 [(LoadConst 0 6) (LoadConst 1 7) (Call 0 add [0 1]) (Return 0)]) (function add 2 2 [(IntBinOp 0 add 0 1) (Return 0)])] main)"
      ),
      BigInt(13)
    )
  }
