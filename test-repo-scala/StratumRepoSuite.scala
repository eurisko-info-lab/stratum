package stratum.repo

import stratum.artifact.{Artifact, DirectoryCas}
import stratum.canon.{Canon, Digest}
import stratum.cli.CommandResult
import stratum.lsp.Json

import java.nio.file.{Files, Path, Paths}

class StratumRepoSuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val projectRoot: Path =
    if Files.isDirectory(root.resolve("languages")) then root else root.getParent

  test("declared structured languages cannot disguise whole files as text") {
    val plainText = Set("text")
    val declarations = LanguageDeclarations.load(projectRoot).fold(error => fail(error), identity)
    declarations.filterNot(language => plainText.contains(language.name) || language.reader != "grammar").foreach { language =>
      val grammarPath = language.grammarPath.getOrElse(fail(s"${language.name} has no grammar"))
      val metaPath = language.metaPath.getOrElse(fail(s"${language.name} has no Meta AST"))
      val sourceGrammar = grammarPath.replace(".generated.grammar", ".grammar")
      val sourceMeta = metaPath.replace(".generated.meta", ".meta")
      val grammar = Files.readString(projectRoot.resolve(sourceGrammar))
      val meta = Files.readString(projectRoot.resolve(sourceMeta))
      assert(
        !grammar.contains("token body str \"[^\\\\r]+\""),
        s"${language.name} consumes the complete file through a catch-all token"
      )
      assert(
        !meta.matches("(?s).*record\\s+\\w*File\\s+\\[text\\].*"),
        s"${language.name} disguises the complete source as a one-field AST"
      )
    }
  }

  test("a working tree becomes a verified append-only content-addressed chain") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")

    assertEquals(run("init", repository, source).code, 0)
    val first = run("record", repository, source, "--message", "genesis")
    assertEquals(first.code, 0, first.output)
    assert(first.lines.contains("height 1"))
    assertEquals(run("verify", repository, source).code, 0)
    assertEquals(run("status", repository, source).output, "clean 1 files")

    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 2 }\n")
    Files.writeString(source.resolve("good.rs"), "fn value() -> i32 { 3 }\n")
    assertEquals(run("status", repository, source).output, "changed 2 files")
    val second = run("record", repository, source, "--message", "second")
    assertEquals(second.code, 0, second.output)
    assert(second.lines.contains("height 2"))
    assertEquals(run("verify", repository, source).output.linesIterator.next(), "valid branch main")
    assertEquals(run("log", repository, source).lines.map(_.split(" ", 3).last), Vector("second", "genesis"))
  }

  test("strict create and inspect define repository session entry") {
    val temp = Files.createTempDirectory("stratum-repository-session-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    val missing = temp.resolve("missing")
    Files.createDirectories(source)

    assertEquals(run("inspect", missing, source).code, 1)
    val created = run("create", repository, source)
    assertEquals(created.code, 0, created.output)
    assertEquals(run("inspect", repository, source).output.linesIterator.next(), s"repository $repository")
    assertEquals(run("create", repository, source).output, s"error: repository already exists: $repository")

    val empty = temp.resolve("empty")
    Files.createDirectories(empty)
    assertEquals(run("create", empty, source).output, s"error: repository already exists: $empty")
  }

  test("a built-in language is added only through the repository command") {
    val temp = Files.createTempDirectory("stratum-repository-language-test-")
    val project = temp.resolve("project")
    val repository = project.resolve(".stratum")
    Files.createDirectories(project)
    assertEquals(
      StratumRepo.run(
        projectRoot,
        Vector("add-language", "--dir", repository.toString, "--project", project.toString, "--name", "lambda")
      ).output,
      s"error: not a Stratum repository: $repository"
    )
    assertEquals(run("init", repository, project).code, 0)

    val added = StratumRepo.run(
      projectRoot,
      Vector("add-language", "--dir", repository.toString, "--project", project.toString, "--name", "lambda")
    )
    assertEquals(added.code, 0, added.output)
    val expected = Vector(
      "fibonacci.lambda",
      "lambda.agent.md",
      "lambda.bootstrap.grammar",
      "lambda.bootstrap.meta",
      "lambda.generated.grammar",
      "lambda.generated.meta",
      "lambda.grammar",
      "lambda.meta"
    )
    assertEquals(added.lines.drop(1), expected.map(name => s"languages/lambda/$name"))
    assertEquals(
      expected.map(name => Files.readString(project.resolve("languages/lambda").resolve(name))),
      expected.map(name => Files.readString(projectRoot.resolve("languages/lambda").resolve(name)))
    )
    assertEquals(
      StratumRepo.run(
        projectRoot,
        Vector("add-language", "--dir", repository.toString, "--project", project.toString, "--name", "lambda")
      ).output,
      "error: language already exists in project: lambda"
    )
    assertEquals(
      StratumRepo.run(
        projectRoot,
        Vector("add-language", "--dir", repository.toString, "--project", project.toString, "--name", "missing")
      ).output,
      "error: unknown built-in language: missing"
    )
  }

  test("source commands validate, modify, remove, and search repository artifacts") {
    val temp = Files.createTempDirectory("stratum-repository-source-test-")
    val project = temp.resolve("project")
    val repository = project.resolve(".stratum")
    Files.createDirectories(project)
    assertEquals(run("init", repository, project).code, 0)

    def source(command: String, path: String, text: Option[String] = None): CommandResult =
      val args = Vector(
        command,
        "--dir", repository.toString,
        "--source", project.toString,
        "--declaration-root", projectRoot.toString,
        "--path", path
      ) ++ text.toVector.flatMap(value => Vector("--text", value))
      StratumRepo.run(root, args)

    val added = source("put-source", "Example.scala", Some("object Example { def value = 1 }\n"))
    assertEquals(added.lines.take(2), Vector("added Example.scala", "language scala"))
    assertEquals(source("check-source", "Example.scala").lines.take(2), Vector("valid Example.scala", "language scala"))
    assertEquals(source("put-source", "Example.scala", Some("object Example { def value = 2 }\n")).lines.head, "modified Example.scala")
    assertEquals(run("record", repository, project, "--message", "searchable source").code, 0)

    val search = StratumRepo.run(root, Vector("search-artifacts", "--dir", repository.toString, "--query", "searchable source"))
    assertEquals(search.code, 0, search.output)
    assert(search.output.contains("searchable source"))
    assertEquals(source("remove-source", "Example.scala").output, "removed Example.scala")
    assert(!Files.exists(project.resolve("Example.scala")))
  }

  test("a declared Meta evaluator runs source without language-specific host code") {
    val temp = Files.createTempDirectory("stratum-repository-run-source-test-")
    val project = temp.resolve("project")
    val repository = project.resolve(".stratum")
    Files.createDirectories(project)
    Files.writeString(project.resolve("identity.lambda"), "(\\x. x) a\n")
    assertEquals(run("init", repository, project).code, 0)

    val result = StratumRepo.run(
      root,
      Vector(
        "run-source",
        "--dir", repository.toString,
        "--source", project.toString,
        "--declaration-root", projectRoot.toString,
        "--path", "identity.lambda"
      )
    )
    assertEquals(result.code, 0, result.output)
    assertEquals(result.lines, Vector("result a", "language lambda", "evaluator NormalizeToText"))
  }

  test("transcript mismatch rolls back effects and does not advance session state") {
    val temp = Files.createTempDirectory("stratum-transcript-runtime-test-")
    Files.createSymbolicLink(temp.resolve("languages"), projectRoot.resolve("languages"))
    val project = temp.resolve("project")
    Files.createDirectories(project)
    val runtime = StratumRepoDaemon.TranscriptRuntime(temp)

    def step(command: String, expected: Vector[String], input: Option[String] = None): Json =
      val fields = Vector(
        "command" -> Json.Str(command),
        "expected" -> Json.arr(expected.map(Json.Str.apply))
      ) ++ input.toVector.map(value => "input" -> Json.Str(value))
      runtime.step(Json.obj(fields*))

    val rejectedCreate = step("repo create test project", Vector("impossible output"))
    assertEquals((rejectedCreate / "code").num, Some(BigDecimal(1)))
    assert(!Files.exists(project.resolve(".stratum")))

    val created = step(
      "repo create test project",
      Vector("session test project project repository project/.stratum")
    )
    assertEquals((created / "code").num, Some(BigDecimal(0)))

    val rejectedSource = step("source add Probe.scala", Vector("impossible output"), Some("object Probe\n"))
    assertEquals((rejectedSource / "code").num, Some(BigDecimal(1)))
    assert(!Files.exists(project.resolve("Probe.scala")))
    assertEquals((step("status", Vector.empty) / "code").num, Some(BigDecimal(0)))

    assertEquals((step("language add lambda", Vector("added language lambda")) / "code").num, Some(BigDecimal(0)))
    val languages = step("language list", Vector.empty)
    assertEquals((languages / "code").num, Some(BigDecimal(0)), clues(languages))
    assert((languages / "lines").items.flatMap(_.str).contains("language lambda"))
    val guide = step("language guide lambda", Vector.empty)
    assertEquals((guide / "code").num, Some(BigDecimal(0)))
    assert((guide / "lines").items.flatMap(_.str).exists(_.contains("pure untyped lambda calculus")))
    val copied = step(
      "source copy languages/lambda/fibonacci.lambda fibonacci.lambda",
      Vector("copied languages/lambda/fibonacci.lambda fibonacci.lambda")
    )
    assertEquals((copied / "code").num, Some(BigDecimal(0)), clues(copied))
    assertEquals(
      Files.readString(project.resolve("fibonacci.lambda")),
      Files.readString(project.resolve("languages/lambda/fibonacci.lambda"))
    )
    val fibonacci = Files.readString(project.resolve("fibonacci.lambda")).trim
    def churchNumeral(value: Int): String = "\\f. \\x. " + "f (" * value + "x" + ")" * value
    def expectedResult(value: Int): String =
      val body = if value == 0 then "v1" else "v0 (" * (value - 1) + "v0 v1" + ")" * (value - 1)
      s"result \\ v0. \\ v1. $body"

    Vector(0 -> 0, 1 -> 1, 10 -> 55).foreach { case (argument, result) =>
      val path = s"fibonacci-$argument.lambda"
      val application = s"($fibonacci) (${churchNumeral(argument)})\n"
      val addedApplication = step(s"source add $path", Vector(s"added $path"), Some(application))
      assertEquals((addedApplication / "code").num, Some(BigDecimal(0)), clues(addedApplication))
      assertEquals((step(s"source check $path", Vector(s"valid $path")) / "code").num, Some(BigDecimal(0)))
      val fibonacciResult = step(s"run $path", Vector.empty)
      assertEquals((fibonacciResult / "code").num, Some(BigDecimal(0)), clues(fibonacciResult))
      assertEquals((fibonacciResult / "lines").items.flatMap(_.str).headOption, Some(expectedResult(result)))
    }

    assertEquals((step("source add identity.lambda", Vector("added identity.lambda"), Some("(\\x. x) a\n")) / "code").num, Some(BigDecimal(0)))
    val sources = step("source list", Vector.empty)
    assertEquals((sources / "code").num, Some(BigDecimal(0)))
    assert((sources / "lines").items.flatMap(_.str).contains("source identity.lambda"))
    val shown = step("source show identity.lambda", Vector.empty)
    assertEquals((shown / "code").num, Some(BigDecimal(0)))
    assertEquals((shown / "lines").items.flatMap(_.str), Vector("source identity.lambda", "(\\x. x) a\n"))
    val evaluated = step("run identity.lambda", Vector("result a"))
    assertEquals((evaluated / "code").num, Some(BigDecimal(0)))
    assert((evaluated / "lines").items.flatMap(_.str).contains("evaluator NormalizeToText"))
  }

  test("named branches share history and then advance independently") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "genesis").code, 0)
    assertEquals(run("branch", repository, source, "--name", "featured/example", "--from", "main").code, 0)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 2 }\n")
    assertEquals(run("record", repository, source, "--branch", "featured/example", "--message", "feature").code, 0)
    assertEquals(run("verify", repository, source, "--branch", "featured/example").lines(1), "chain 2 blocks")
    assertEquals(run("verify", repository, source).lines(1), "chain 1 blocks")
    assertEquals(run("branches", repository, source).lines.map(_.split(" ").head), Vector("featured/example", "main"))
    val checkout = temp.resolve("checkout")
    assertEquals(run("checkout", repository, source, "--branch", "featured/example", "--out", checkout.toString).code, 0)
    assert(Files.readString(checkout.resolve("Good.scala")).contains("def value = 2"))
  }

  test("recording identical content does not extend the chain") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source).code, 0)
    assertEquals(run("record", repository, source).output, "error: working tree is already recorded")
    assertEquals(run("log", repository, source).lines.length, 1)
  }

  test("record-change structures only declared graph changes") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    val change = temp.resolve("change.canon")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "genesis").code, 0)

    val cas = DirectoryCas(repository.resolve("objects"))
    val head = readRef(repository, "main").getOrElse(fail("missing main head"))
    val (_, patchDigest, _) = readBlockSummary(cas, head)
    val tree = readTreeEntries(cas, readPatchTree(cas, patchDigest))
    val before = readEntryContent(tree.getOrElse("Good.scala", fail("missing Good.scala")))

    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 2 }\n")
    Files.writeString(source.resolve("Unrelated.xyz"), "not declared and not part of the delta\n")
    Files.writeString(
      change,
      s"(repository-change (replace \"Good.scala\" \"${before.hex}\"))\n"
    )

    val recorded = run("record-change", repository, source, "--change", change.toString, "--message", "one node")
    assertEquals(recorded.code, 0, recorded.output)
    assert(recorded.lines.contains("changes 1"))
    assertEquals(run("verify-head", repository, source).output.linesIterator.next(), "valid head main")
    assertEquals(run("log", repository, source).lines.map(_.split(" ", 3).last), Vector("one node", "genesis"))

    val checkout = temp.resolve("checkout")
    assertEquals(run("checkout", repository, source, "--out", checkout.toString).code, 0)
    assert(Files.readString(checkout.resolve("Good.scala")).contains("def value = 2"))
    assert(!Files.exists(checkout.resolve("Unrelated.xyz")))
  }

  test("record-change adds and removes nodes while rejecting ambiguous parent claims") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    val change = temp.resolve("change.canon")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "genesis").code, 0)

    Files.writeString(source.resolve("Added.scala"), "object Added\n")
    Files.writeString(change, "(repository-change (add \"Added.scala\"))\n")
    assertEquals(run("record-change", repository, source, "--change", change.toString).code, 0)

    val cas = DirectoryCas(repository.resolve("objects"))
    val addedHead = readRef(repository, "main").getOrElse(fail("missing add head"))
    val (_, addedPatch, _) = readBlockSummary(cas, addedHead)
    val entries = readTreeEntries(cas, readPatchTree(cas, addedPatch))
    val addedContent = readEntryContent(entries.getOrElse("Added.scala", fail("missing added entry")))

    Files.writeString(
      change,
      s"(repository-change (remove \"Added.scala\" \"${addedContent.hex}\"))\n"
    )
    assertEquals(run("record-change", repository, source, "--change", change.toString).code, 0)
    val checkout = temp.resolve("checkout")
    assertEquals(run("checkout", repository, source, "--out", checkout.toString).code, 0)
    assert(!Files.exists(checkout.resolve("Added.scala")))

    Files.writeString(change, s"(repository-change (replace \"Good.scala\" \"${"0" * 64}\"))\n")
    val stale = run("record-change", repository, source, "--change", change.toString)
    assertEquals(stale.code, 1)
    assert(stale.output.contains("change expected"))

    val goodContent = readEntryContent(readTreeEntries(cas, currentTree(cas, repository))("Good.scala"))
    Files.writeString(
      change,
      s"(repository-change (replace \"Good.scala\" \"${goodContent.hex}\") (replace \"Good.scala\" \"${goodContent.hex}\"))\n"
    )
    val duplicate = run("record-change", repository, source, "--change", change.toString)
    assertEquals(duplicate.code, 1)
    assert(duplicate.output.contains("duplicate changed path"))
  }

  test("consecutive graph changes share untouched tree buckets") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    val change = temp.resolve("change.canon")
    val firstPath = "First.scala"
    val secondPath = Iterator.from(1).map(index => s"Second$index.scala")
      .find(path => treeBucket(path) != treeBucket(firstPath)).get
    Files.createDirectories(source)
    Files.writeString(source.resolve(firstPath), "object First { def value = 1 }\n")
    Files.writeString(source.resolve(secondPath), "object Second { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "genesis").code, 0)

    val cas = DirectoryCas(repository.resolve("objects"))
    val firstBefore = readEntryContent(readTreeEntries(cas, currentTree(cas, repository))(firstPath))
    Files.writeString(source.resolve(firstPath), "object First { def value = 2 }\n")
    Files.writeString(change, s"(repository-change (replace \"$firstPath\" \"${firstBefore.hex}\"))\n")
    assertEquals(run("record-change", repository, source, "--change", change.toString).code, 0)
    val firstTree = currentTree(cas, repository)
    val firstIndex = readTreeIndex(cas, firstTree)

    val secondBefore = readEntryContent(readTreeEntries(cas, firstTree)(secondPath))
    Files.writeString(source.resolve(secondPath), "object Second { def value = 2 }\n")
    Files.writeString(change, s"(repository-change (replace \"$secondPath\" \"${secondBefore.hex}\"))\n")
    assertEquals(run("record-change", repository, source, "--change", change.toString).code, 0)
    val secondTree = currentTree(cas, repository)
    val secondIndex = readTreeIndex(cas, secondTree)

    assertNotEquals(firstTree, secondTree)
    assertEquals(secondIndex(treeBucket(firstPath)), firstIndex(treeBucket(firstPath)))
    assertNotEquals(secondIndex(treeBucket(secondPath)), firstIndex(treeBucket(secondPath)))
    assertEquals(run("verify-head", repository, source).code, 0)
  }

  test("record rejects unknown materialization profiles") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    val result = run("record", repository, source, "--materialization-profile", "materialization-profile-v999")
    assertEquals(result.code, 1)
    assert(result.output.contains("unknown materialization profile"))
  }

  test("record can migrate profile with unchanged tree") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    val first = run("record", repository, source, "--message", "v1")
    assertEquals(first.code, 0, first.output)
    val migrated = run(
      "record",
      repository,
      source,
      "--message", "v2",
      "--materialization-profile", "materialization-profile-v2-generated-only"
    )
    assertEquals(migrated.code, 0, migrated.output)
    assert(migrated.lines.contains("changes 0"))
    assert(migrated.lines.contains("profile materialization-profile-v2-generated-only"))
    assertEquals(run("verify", repository, source).code, 0)
    assertEquals(run("log", repository, source).lines.length, 2)
  }

  test("verify fails when a patch cannot derive its declared tree") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "genesis").code, 0)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 2 }\n")
    assertEquals(run("record", repository, source, "--message", "second").code, 0)

    val cas = DirectoryCas(repository.resolve("objects"))
    val head = readRef(repository, "main").getOrElse(fail("missing main head"))
    val (height, patchDigest, _) = readBlockSummary(cas, head)
    val tree = readPatchTree(cas, patchDigest)

    val zero = "0" * 64
    val badPatch = Artifact(
      "patch",
      Canon.node(
        "patch",
        Canon.R(head),
        Canon.R(tree),
        Canon.S("tampered"),
        Canon.L(Vector(Canon.node("replace", Canon.S("Good.scala"), Canon.S(zero), Canon.S(zero))))
      )
    )
    val badPatchDigest = cas.put(badPatch)
    val badBlock = Artifact(
      "block",
      Canon.node("block", Canon.N(BigInt(height + 1L)), Canon.R(head), Canon.R(badPatchDigest))
    )
    val badBlockDigest = cas.put(badBlock)
    Files.writeString(repository.resolve("refs/main"), badBlockDigest.hex + "\n")

    val result = run("verify", repository, source)
    assertEquals(result.code, 1)
    assert(result.output.contains("invalid patch") || result.output.contains("does not derive"))
  }

  test("recorded entries bind explicit materializer identities") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Reference.canon"), "#d" + "00" * 32 + "\n")
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "materializers").code, 0)

    val cas = DirectoryCas(repository.resolve("objects"))
    val head = readRef(repository, "main").getOrElse(fail("missing main head"))
    val (_, patchDigest, _) = readBlockSummary(cas, head)
    val treeDigest = readPatchTree(cas, patchDigest)
    val treeEntries = readTreeEntries(cas, treeDigest)

    val canonId = readMaterializerId(cas, treeEntries.getOrElse("Reference.canon", fail("missing Reference.canon")))
    assertEquals(canonId, "canon-text-write-v1")

    val scalaId = readMaterializerId(cas, treeEntries.getOrElse("Good.scala", fail("missing Good.scala")))
    assertEquals(scalaId, "grammar0-native-print-v1")
  }

  test("verify fails when a tree entry binds an incompatible materializer") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "materializer-mismatch").code, 0)

    val cas = DirectoryCas(repository.resolve("objects"))
    val head = readRef(repository, "main").getOrElse(fail("missing main head"))
    val (height, patchDigest, _) = readBlockSummary(cas, head)
    val treeDigest = readPatchTree(cas, patchDigest)
    val badMaterializer = cas.put(Artifact("materializer", Canon.node("materializer", Canon.Sym("canon-text-write-v1"))))

    val badTree = cas.get(treeDigest) match
      case Some(Artifact("tree", Canon.Node("tree", Vector(Canon.L(entries))))) =>
        val rewritten = entries.map {
          case Canon.Node("entry", Vector(Canon.S(path), content, blob, _, language, grammar, meta, syntax)) if path == "Good.scala" =>
            Canon.node("entry", Canon.S(path), content, blob, Canon.R(badMaterializer), language, grammar, meta, syntax)
          case other => other
        }
        cas.put(Artifact("tree", Canon.node("tree", Canon.L(rewritten))))
      case _ => fail(s"invalid tree ${treeDigest.hex}")

    val badPatch = Artifact(
      "patch",
      Canon.node(
        "patch",
        Canon.R(head),
        Canon.R(badTree),
        Canon.S("tampered materializer"),
        Canon.L(Vector.empty)
      )
    )
    val badPatchDigest = cas.put(badPatch)
    val badBlock = Artifact(
      "block",
      Canon.node("block", Canon.N(BigInt(height + 1L)), Canon.R(head), Canon.R(badPatchDigest))
    )
    val badBlockDigest = cas.put(badBlock)
    Files.writeString(repository.resolve("refs/main"), badBlockDigest.hex + "\n")

    val result = run("verify", repository, source)
    assertEquals(result.code, 1)
    assert(result.output.contains("materializer"))
  }

  test("verify fails when block profile disallows an entry materializer") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "profile-mismatch").code, 0)

    val cas = DirectoryCas(repository.resolve("objects"))
    val head = readRef(repository, "main").getOrElse(fail("missing main head"))
    val (height, patchDigest, _) = readBlockSummary(cas, head)
    val treeDigest = readPatchTree(cas, patchDigest)

    val restrictiveProfile = cas.put(
      Artifact(
        "materialization-profile",
        Canon.node("materialization-profile", Canon.Sym("restricted"), Canon.L(Vector(Canon.Sym("blob-v1"))))
      )
    )
    val badPatch = Artifact(
      "patch",
      Canon.node(
        "patch",
        Canon.R(head),
        Canon.R(treeDigest),
        Canon.S("profile disallow"),
        Canon.L(Vector.empty)
      )
    )
    val badPatchDigest = cas.put(badPatch)
    val badBlock = Artifact(
      "block",
      Canon.node("block", Canon.N(BigInt(height + 1L)), Canon.R(head), Canon.R(badPatchDigest), Canon.R(restrictiveProfile))
    )
    val badBlockDigest = cas.put(badBlock)
    Files.writeString(repository.resolve("refs/main"), badBlockDigest.hex + "\n")

    val result = run("verify", repository, source)
    assertEquals(result.code, 1)
    assert(
      result.output.contains("not allowed by profile") ||
      result.output.contains("materialization profile") ||
      result.output.contains("unknown materialization profile")
    )
  }

  test("the project declarations bind source through declared Grammar and Meta artifacts") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    Files.writeString(source.resolve("good.rs"), "fn value() -> i32 { 1 }\n")
    Files.writeString(source.resolve("Reference.canon"), "#d" + "00" * 32 + "\n")
    val declarations = LanguageDeclarations.load(projectRoot).fold(error => fail(error), identity)
    val scala = declarations.find(_.name == "scala").getOrElse(fail("missing Scala language"))
    val rust = declarations.find(_.name == "rust").getOrElse(fail("missing Rust language"))
    assertEquals(scala.grammarPath, Some("languages/scala/scala.generated.grammar"))
    assertEquals(scala.metaPath, Some("languages/scala/scala.generated.meta"))
    assertEquals(rust.grammarPath, Some("languages/rust/rust.generated.grammar"))
    assertEquals(rust.metaPath, Some("languages/rust/rust.generated.meta"))
    assertNotEquals(scala.grammarPath, rust.grammarPath)
    assertNotEquals(scala.metaPath, rust.metaPath)
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source).code, 0)
    assertEquals(run("verify", repository, source).code, 0)
    Files.writeString(source.resolve("Unknown.xyz"), "opaque\n")
    assert(run("record", repository, source).output.contains("no declared language"))
  }

  private def run(command: String, repository: Path, source: Path, extra: String*): CommandResult =
    StratumRepo.run(
      root,
      Vector(
        command,
        "--dir", repository.toString,
        "--source", source.toString,
        "--declaration-root", projectRoot.toString
      ) ++ extra
    )

  private def readRef(repository: Path, branch: String): Option[Digest] =
    val ref = repository.resolve("refs").resolve(branch)
    if !Files.isRegularFile(ref) then None else Digest.fromHex(Files.readString(ref).trim).toOption

  private def readBlockSummary(cas: DirectoryCas, digest: Digest): (Long, Digest, Option[Digest]) =
    cas.get(digest) match
      case Some(Artifact("block", Canon.Node("block", Vector(Canon.N(height), _, Canon.R(patch), Canon.R(profile))))) if height.isValidLong =>
        (height.longValue, patch, Some(profile))
      case Some(Artifact("block", Canon.Node("block", Vector(Canon.N(height), _, Canon.R(patch))))) if height.isValidLong =>
        (height.longValue, patch, None)
      case _ => fail(s"invalid block ${digest.hex}")

  private def readPatchTree(cas: DirectoryCas, digest: Digest): Digest =
    cas.get(digest) match
      case Some(Artifact("patch", Canon.Node("patch", Vector(_, Canon.R(tree), _, _)))) => tree
      case _ => fail(s"invalid patch ${digest.hex}")

  private def currentTree(cas: DirectoryCas, repository: Path): Digest =
    val head = readRef(repository, "main").getOrElse(fail("missing main head"))
    val (_, patch, _) = readBlockSummary(cas, head)
    readPatchTree(cas, patch)

  private def treeBucket(path: String): String =
    Digest.of(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hex.take(2)

  private def readTreeIndex(cas: DirectoryCas, digest: Digest): Map[String, Digest] =
    cas.get(digest) match
      case Some(Artifact("tree", Canon.Node("tree-map", Vector(Canon.L(buckets))))) =>
        buckets.collect {
          case Canon.Node("bucket", Vector(Canon.Sym(key), Canon.R(bucket))) => key -> bucket
        }.toMap
      case _ => fail(s"tree ${digest.hex} is not persistent")

  private def readTreeEntries(cas: DirectoryCas, digest: Digest): Map[String, Canon] =
    cas.get(digest) match
      case Some(Artifact("tree", Canon.Node("tree", Vector(Canon.L(entries))))) =>
        entries.collect {
          case entry @ Canon.Node("entry", Vector(Canon.S(path), _, _, _, _, _, _, _)) => path -> entry
          case entry @ Canon.Node("entry", Vector(Canon.S(path), _, _, _, _, _, _))    => path -> entry
          case entry @ Canon.Node("entry", Vector(Canon.S(path), _, _, _, _, _))       => path -> entry
        }.toMap
      case Some(Artifact("tree", Canon.Node("tree-map", Vector(Canon.L(buckets))))) =>
        buckets.flatMap {
          case Canon.Node("bucket", Vector(_, Canon.R(bucket))) =>
            cas.get(bucket) match
              case Some(Artifact("tree-bucket", Canon.Node("tree-bucket", Vector(Canon.L(entries))))) => entries
              case _ => fail(s"invalid tree bucket ${bucket.hex}")
          case _ => fail(s"invalid bucket in tree ${digest.hex}")
        }.collect {
          case entry @ Canon.Node("entry", Vector(Canon.S(path), _*)) => path -> entry
        }.toMap
      case _ => fail(s"invalid tree ${digest.hex}")

  private def readMaterializerId(cas: DirectoryCas, entry: Canon): String =
    entry match
      case Canon.Node("entry", Vector(_, _, _, Canon.R(materializer), _, _, _, _)) =>
        cas.get(materializer) match
          case Some(Artifact("materializer", Canon.Node("materializer", Vector(Canon.Sym(id))))) => id
          case _ => fail(s"invalid materializer ${materializer.hex}")
      case _ => fail("entry does not carry explicit materializer identity")

  private def readEntryContent(entry: Canon): Digest =
    entry match
      case Canon.Node("entry", Vector(_, Canon.S(hex), _*)) =>
        Digest.fromHex(hex).fold(error => fail(error), identity)
      case _ => fail("entry does not carry structured content identity")
