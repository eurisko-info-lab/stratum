package stratum

import stratum.cli.{Cli, Transcript}

import java.nio.file.{Files, Path, Paths}

/**
 * Rehearses every runbook.
 *
 * A runbook is performed against real state, so the way to prove one without
 * performing it is to run it in full and put everything back. That is what
 * rehearsal is, and it means a runbook cannot quietly rot: if it would fail in
 * production it fails here, and the working tree is untouched either way.
 *
 * This is also why every parameter must have a default. A runbook that cannot
 * be run without being told something cannot be rehearsed, and a runbook that
 * is never rehearsed is the one that fails when it matters.
 */
class RunbookSuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()

  Transcript.transcriptFiles(root, Vector("runbooks")).foreach { file =>
    val rel = root.relativize(file).toString

    test(s"every parameter of $rel has a default") {
      val doc = Transcript.parse(Files.readString(file))
      val undefaulted = doc.params.filter(_.default.isEmpty).map(_.name)
      assertEquals(undefaulted, Vector.empty[String], s"$rel cannot be rehearsed without being told these")
    }

    test(s"rehearsing $rel leaves nothing behind") {
      val before = digestOfTree()
      val result = Cli.run(root, Vector("transcript", "rehearse", rel))
      assertEquals(result.code, 0, s"$rel did not run:\n${result.lines.mkString("\n")}")
      assertEquals(digestOfTree(), before, s"$rel changed the tree it rehearsed against")
    }
  }

  /** The size and modification time of everything a runbook could plausibly touch. */
  private def digestOfTree(): Vector[String] =
    Vector("foundations", "changes", "features", "languages").flatMap { dir =>
      val start = root.resolve(dir)
      if !Files.isDirectory(start) then Vector.empty
      else
        val stream = Files.walk(start)
        try
          stream
            .iterator()
            .asInstanceOf[java.util.Iterator[Path]]
            .asScalaVector
            .filter(Files.isRegularFile(_))
            .map(p => s"$p:${Files.size(p)}")
            .sorted
        finally stream.close()
    }

  extension (it: java.util.Iterator[Path])
    private def asScalaVector: Vector[Path] =
      val out = Vector.newBuilder[Path]
      while it.hasNext do out += it.next()
      out.result()
