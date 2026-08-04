package stratum

import stratum.cli.Transcript

import java.nio.file.{Files, Path, Paths}

/** Replays every transcript in the repository. This is the primary functional test. */
class TranscriptSuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()

  Transcript.transcriptFiles(root, Vector("fixtures")).foreach { file =>
    val rel = root.relativize(file).toString
    test(s"transcript $rel") {
      val doc = Transcript.parse(Files.readString(file))
      assert(doc.steps.nonEmpty, s"$rel contains no steps")
      val results = Transcript.execute(root, doc)
      val failures = results.filterNot(_.passed)
      if failures.nonEmpty then
        val report = failures
          .map { f =>
            val expected = f.step.expected.map(l => s"  - $l").mkString("\n")
            val actual = f.actual.map(l => s"  + $l").mkString("\n")
            s"$$ ${f.step.command}\n$expected\n$actual"
          }
          .mkString("\n\n")
        fail(s"$rel: ${failures.length}/${results.length} steps differ\n$report")
    }
  }
