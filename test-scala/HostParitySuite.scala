package stratum

import stratum.cli.Cli

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

/**
 * The two-host parity gate.
 *
 * From F6 onward an independent host must reconstruct the same canonical
 * attestation bytes for the same foundation.
 */
class HostParitySuite extends munit.FunSuite:

  /**
   * Deriving every foundation includes deriving S5, which compiles the Strata
   * compiler with the reference evaluator and then runs that compiled compiler
   * on its own source. That is real work and it is meant to be.
   */
  override val munitTimeout: Duration = Duration(30, "min")

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val rustBinary: Path = root.resolve("host-rust/target/release/stratum-verify")

  /** Foundations first, then any application deployment built on the platform. */
  private def worlds: Vector[String] =
    val foundations = Vector("F0", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10", "S11", "S12")
      .map(name => s"foundations/$name")
    val applications =
      val directory = root.resolve("applications")
      if !Files.isDirectory(directory) then Vector.empty
      else
        Files.list(directory).toList.asScala.toVector
          .filter(Files.isDirectory(_))
          .map(path => s"applications/${path.getFileName}")
          .sorted
    (foundations ++ applications).filter(dir => Files.exists(root.resolve(s"$dir/digest.txt")))

  private def runRust(args: String*): (Int, Vector[String]) =
    val process = ProcessBuilder((rustBinary.toString +: args).toList*)
      .directory(root.toFile)
      .redirectErrorStream(true)
      .start()
    val output = String(process.getInputStream.readAllBytes(), "UTF-8")
    (process.waitFor(), output.linesIterator.toVector)

  private def requireRust(): Boolean =
    if Files.isExecutable(rustBinary) then true
    else if sys.env.get("STRATUM_ALLOW_MISSING_RUST").contains("1") then
      println("skipping two-host parity: STRATUM_ALLOW_MISSING_RUST=1")
      false
    else
      fail(
        s"the independent host is missing at $rustBinary. " +
          "Build it with `cargo build --release --manifest-path host-rust/Cargo.toml`, " +
          "or set STRATUM_ALLOW_MISSING_RUST=1 to skip."
      )

  test("scala attestation is canonical and stable") {
    worlds.foreach { name =>
      val first = Cli.run(root, Vector("foundation", "attest", "--dir", name))
      val second = Cli.run(root, Vector("foundation", "attest", "--dir", name))
      assertEquals(first.code, 0, s"$name: ${first.output}")
      assertEquals(first.lines, second.lines, s"$name attestation is not stable")
    }
  }

  test("rust host agrees with the scala host on attestations") {
    if requireRust() then
      worlds.foreach { name =>
        val scala = Cli.run(root, Vector("foundation", "attest", "--dir", name))
        assertEquals(scala.code, 0, s"$name: ${scala.output}")
        val (code, output) = runRust("attest", name)
        assertEquals(code, 0, s"$name: rust host failed: ${output.mkString("\n")}")
        assertEquals(output, scala.lines, s"$name: hosts disagree on the attestation")
      }
  }

  test("rust host agrees with the scala host on every derivation") {
    if requireRust() then
      worlds.foreach { name =>
        val scala = Cli.run(root, Vector("foundation", "report", "--dir", name))
        assertEquals(scala.code, 0, s"$name: ${scala.output}")
        val (code, output) = runRust("report", name)
        assertEquals(code, 0, s"$name: rust host failed: ${output.mkString("\n")}")
        assertEquals(
          output,
          scala.lines,
          s"$name: the two engines disagree on a verdict, its evidence or its resource accounting"
        )
      }
  }

  test("both hosts agree about a run neither of them has to repeat") {
    if requireRust() then
      val record = root.resolve("target/parity-run.canon")
      val rehearsed = Cli.run(
        root,
        Vector(
          "transcript", "rehearse", "runbooks/rebuild-a-foundation.transcript",
          "--record", root.relativize(record).toString
        )
      )
      assertEquals(rehearsed.code, 0, s"the runbook did not rehearse: ${rehearsed.output}")
      assert(Files.exists(record), "rehearsing recorded nothing")

      val relative = root.relativize(record).toString
      val scala = Cli.run(root, Vector("transcript", "examine", relative))
      assertEquals(scala.code, 0, s"scala host: ${scala.output}")
      val (code, output) = runRust("run", relative)
      assertEquals(code, 0, s"rust host failed: ${output.mkString("\n")}")
      assertEquals(
        output,
        scala.lines,
        "the two hosts disagree about what a run did, or about the identity of its record"
      )
  }

  test("both hosts agree about what a character is") {
    if requireRust() then
      // Nothing in the corpus reaches past the basic multilingual plane, so no
      // foundation exercises this and no digest depends on it. That is exactly
      // why it needs saying: the two hosts once disagreed here, silently, and
      // no gate could have caught it.
      //
      // A character is a Unicode scalar value. Counting UTF-16 code units
      // reports two for one character above U+10000, and slicing between them
      // produces an unpaired surrogate -- a value with no canonical encoding,
      // which the other host would be right to reject.
      val grin = String(Character.toChars(0x1f600))
      val cases = Vector(
        s"""(prim slen (q "a${grin}b"))""" -> "3",
        s"""(prim ssub (q "a${grin}b") (q 1) (q 2))""" -> s""""$grin"""",
        s"""(prim slen (prim ssub (q "$grin$grin") (q 0) (q 1)))""" -> "1"
      )

      cases.foreach { (goal, answer) =>
        val scala = Cli.run(root, Vector("derive", "--foundation", "foundations/S8", "--goal", goal))
        assertEquals(scala.code, 0, s"scala host: ${scala.output}")
        assertEquals(scala.lines, Vector(answer), s"the scala host is wrong about $goal")

        val (code, output) = runRust("derive", "foundations/S8", "--goal", goal)
        assertEquals(code, 0, s"rust host failed: ${output.mkString("\n")}")
        assert(
          output.headOption.exists(_.startsWith(s"(verdict ok $answer ")),
          s"the hosts disagree about $goal: scala said $answer, rust said ${output.headOption.getOrElse("")}"
        )
      }
  }

  test("both hosts accept and reject exactly the same bytes") {
    if requireRust() then
      val corpus = root.resolve("fixtures/canon/adversarial")
      val cases = Files.list(corpus).toList.asScala.toVector.map(_.toString).sorted
      assert(cases.nonEmpty, "the adversarial corpus is empty")
      cases.foreach { path =>
        val relative = root.relativize(Paths.get(path)).toString
        val scala = Cli.run(root, Vector("canon", "check", relative))
        assertEquals(scala.code, 0, s"$relative: ${scala.output}")
        val (code, output) = runRust("canon", relative)
        assertEquals(code, 0, s"$relative: rust host failed: ${output.mkString("\n")}")
        assertEquals(output, scala.lines, s"$relative: hosts disagree on canonicity")
      }
  }
