package stratum

import stratum.cli.Transcript

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.Duration

/** Replays every transcript in the repository. This is the primary functional test. */
class TranscriptSuite extends munit.FunSuite:

  // A second-floor transcript replays `foundation verify`, which re-runs every
  // check in that foundation. S9 elaborates forty-three Meta programs through
  // an interpreter running on an interpreter and takes minutes, not seconds.
  // The default thirty seconds was a limit on nothing in particular.
  override val munitTimeout: Duration = Duration(20, "min")

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val MaxFailuresInReport = 5
  private val MaxLinesPerSide = 6
  private val MaxLineLength = 240

  private def compactLines(lines: Vector[String], prefix: String): Vector[String] =
    val shown = lines.take(MaxLinesPerSide).map { line =>
      val clipped = if line.length <= MaxLineLength then line else line.take(MaxLineLength) + "..."
      s"  $prefix $clipped"
    }
    if lines.length > MaxLinesPerSide then shown :+ s"  $prefix ... (${lines.length - MaxLinesPerSide} more lines)" else shown

  Transcript.transcriptFiles(root, Vector("fixtures")).foreach { file =>
    val rel = root.relativize(file).toString
    test(s"transcript $rel") {
      val doc = Transcript.parse(Files.readString(file))
      assert(doc.steps.nonEmpty, s"$rel contains no steps")
      val results = Transcript.execute(root, doc)
      val failures = results.filterNot(_.passed)
      if failures.nonEmpty then
        val shownFailures = failures.take(MaxFailuresInReport)
        val report = shownFailures
          .map { f =>
            val expected = compactLines(f.step.expected, "-").mkString("\n")
            val actual = compactLines(f.actual, "+").mkString("\n")
            s"$$ ${f.step.command}\n$expected\n$actual"
          }
          .mkString("\n\n")
        val omitted = failures.length - shownFailures.length
        val suffix = if omitted > 0 then s"\n... omitted $omitted additional failing steps" else ""
        fail(s"$rel: ${failures.length}/${results.length} steps differ\n$report$suffix")
    }
  }
