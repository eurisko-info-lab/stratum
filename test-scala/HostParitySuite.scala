package stratum

import stratum.cli.{Cli, CommandResult}

import java.nio.file.{Files, Path, Paths}

/**
 * The two-host parity gate.
 *
 * From F6 onward an independent host must reconstruct the same canonical
 * attestation bytes for the same foundation.
 */
class HostParitySuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val rustBinary: Path = root.resolve("host-rust/target/release/stratum-verify")

  private def foundations: Vector[String] =
    Vector("F6", "F7", "F8", "F9", "F10", "F11").filter(n => Files.exists(root.resolve(s"foundations/$n/digest.txt")))

  test("scala attestation is canonical and stable") {
    foundations.foreach { name =>
      val first = Cli.run(root, Vector("foundation", "attest", "--dir", s"foundations/$name"))
      val second = Cli.run(root, Vector("foundation", "attest", "--dir", s"foundations/$name"))
      assertEquals(first.code, 0, s"$name: ${first.output}")
      assertEquals(first.lines, second.lines, s"$name attestation is not stable")
    }
  }

  test("rust host agrees with the scala host") {
    if !Files.isExecutable(rustBinary) then
      println(
        s"skipping two-host parity: build the independent host with `cd host-rust && cargo build --release`"
      )
    else
      foundations.foreach { name =>
        val scala = Cli.run(root, Vector("foundation", "attest", "--dir", s"foundations/$name"))
        assertEquals(scala.code, 0, s"$name: ${scala.output}")
        val process = ProcessBuilder(rustBinary.toString, "attest", s"foundations/$name")
          .directory(root.toFile)
          .redirectErrorStream(true)
          .start()
        val output = String(process.getInputStream.readAllBytes(), "UTF-8").trim
        val code = process.waitFor()
        assertEquals(code, 0, s"$name: rust host failed: $output")
        assertEquals(output.linesIterator.toVector, scala.lines, s"$name: hosts disagree")
      }
  }
