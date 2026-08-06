package stratum

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * The native-code freeze gate.
 *
 * After F1 the bootstrap host may not learn about any feature of the system
 * built above it. This test rejects feature-specific vocabulary and semantic
 * dispatch inside the host sources.
 */
class NativeBoundarySuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()

  private val forbidden = Vector(
    "lambda", "debruijn", "de bruijn", "beta reduc",
    "patch", "pijul", "repository", "merge conflict",
    "blockchain", "ledger", "block chain", "transaction",
    "quorum", "byzantine", "federation", "replica", "finality",
    "retention", "archive",
    "smalltalk", "sds", "studio", "panel", "inspector",
    "typecheck", "compiler"
  )

  private def hostSources: Vector[Path] =
    val dir = root.resolve("host-scala")
    val stream = Files.walk(dir)
    try stream.iterator().asScala.filter(_.toString.endsWith(".scala")).toVector.sortBy(_.toString)
    finally stream.close()

  test("host sources exist") {
    assert(hostSources.nonEmpty, "no host sources found")
  }

  test("host contains no feature-specific vocabulary") {
    val violations = hostSources.flatMap { p =>
      val text = Files.readString(p).toLowerCase
      forbidden.filter(w => s"\\b${java.util.regex.Pattern.quote(w)}\\b".r.findFirstIn(text).isDefined).map { w =>
        s"${root.relativize(p)} mentions '$w'"
      }
    }
    assertEquals(violations, Vector.empty[String], "the bootstrap host must stay feature agnostic")
  }

  test("host performs no semantic dispatch on feature tags") {
    // The host may only match on its own fixed vocabulary.
    val allowedTags = Set(
      "artifact", "program", "judgment", "module", "use", "grammar", "name", "start", "token", "category",
      "prod", "pass", "fold", "paren", "kw", "bind", "q", "v", "mk", "lst", "mp", "if", "let", "call",
      "match", "case", "prim", "cap", "fail", "pv", "pq", "pm", "pnode", "pl", "pcons", "pnil", "verdict",
      "skip",
      "evidence", "steps", "calls", "capabilities", "budget", "depth", "kernel", "allow", "ok", "denied",
      "request", "build", "application", "foundation", "predecessor", "change", "file", "include",
      "text-file", "program-of", "foundation-of", "source", "path", "text", "none", "some", "check",
      "value", "error", "entry", "bootstrap", "meta", "grammars", "entries", "checks", "resources",
      "schema", "profile", "data", "verdict-error", "empty", "message", "dir", "derived",
      "host-core", "derivation-report", "attestation", "canon",
      // The generic change algebra. These tags describe how any schema-carrying
      // value changes, so they say nothing about the features built above.
      "replace", "compose", "option", "either", "state-change", "budget-change"
    )
    val pattern = """Canon\.Node\("([a-zA-Z0-9\-]+)"""".r
    val violations = hostSources.flatMap { p =>
      val text = Files.readString(p)
      pattern.findAllMatchIn(text).map(_.group(1)).filterNot(allowedTags.contains).map { tag =>
        s"${root.relativize(p)} dispatches on feature tag '$tag'"
      }
    }
    assertEquals(violations.distinct, Vector.empty[String])
  }

  test("no networking or persistence libraries are linked into the host") {
    val bannedImports = Vector("java.net", "java.sql", "scala.io.Source.fromURL", "javax.net")
    val violations = hostSources.flatMap { p =>
      val text = Files.readString(p)
      bannedImports.filter(text.contains).map(i => s"${root.relativize(p)} imports $i")
    }
    assertEquals(violations, Vector.empty[String])
  }
