package stratum.repo

import stratum.artifact.{Artifact, DirectoryCas}
import stratum.canon.{Canon, Digest}
import stratum.cli.CommandResult

import java.nio.file.{Files, Path, Paths}

class StratumRepoSuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val projectRoot: Path =
    if Files.isDirectory(root.resolve("languages")) then root else root.getParent

  test("declared structured languages cannot disguise whole files as text") {
    val plainText = Set("text")
    val declarations = LanguageCatalogue.load(projectRoot).fold(error => fail(error), identity)
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
    Files.writeString(source.resolve("alpha.txt"), "one\n")

    assertEquals(run("init", repository, source).code, 0)
    val first = run("record", repository, source, "--message", "genesis")
    assertEquals(first.code, 0, first.output)
    assert(first.lines.contains("height 1"))
    assertEquals(run("verify", repository, source).code, 0)
    assertEquals(run("status", repository, source).output, "clean 1 files")

    Files.writeString(source.resolve("alpha.txt"), "two\n")
    Files.writeString(source.resolve("beta.txt"), "three\n")
    assertEquals(run("status", repository, source).output, "changed 2 files")
    val second = run("record", repository, source, "--message", "second")
    assertEquals(second.code, 0, second.output)
    assert(second.lines.contains("height 2"))
    assertEquals(run("verify", repository, source).output.linesIterator.next(), "valid branch main")
    assertEquals(run("log", repository, source).lines.map(_.split(" ", 3).last), Vector("second", "genesis"))
  }

  test("named branches share history and then advance independently") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("value.txt"), "main\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "genesis").code, 0)
    assertEquals(run("branch", repository, source, "--name", "featured/example", "--from", "main").code, 0)
    Files.writeString(source.resolve("value.txt"), "feature\n")
    assertEquals(run("record", repository, source, "--branch", "featured/example", "--message", "feature").code, 0)
    assertEquals(run("verify", repository, source, "--branch", "featured/example").lines(1), "chain 2 blocks")
    assertEquals(run("verify", repository, source).lines(1), "chain 1 blocks")
    assertEquals(run("branches", repository, source).lines.map(_.split(" ").head), Vector("featured/example", "main"))
    val checkout = temp.resolve("checkout")
    assertEquals(run("checkout", repository, source, "--branch", "featured/example", "--out", checkout.toString).code, 0)
    assertEquals(Files.readString(checkout.resolve("value.txt")), "feature\n")
  }

  test("recording identical content does not extend the chain") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("only.txt"), "same\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source).code, 0)
    assertEquals(run("record", repository, source).output, "error: working tree is already recorded")
    assertEquals(run("log", repository, source).lines.length, 1)
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
    Files.writeString(source.resolve("alpha.txt"), "one\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "genesis").code, 0)
    Files.writeString(source.resolve("alpha.txt"), "two\n")
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
        Canon.L(Vector(Canon.node("replace", Canon.S("alpha.txt"), Canon.S(zero), Canon.S(zero))))
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
    Files.writeString(source.resolve("alpha.txt"), "one\n")
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    assertEquals(run("init", repository, source).code, 0)
    assertEquals(run("record", repository, source, "--message", "materializers").code, 0)

    val cas = DirectoryCas(repository.resolve("objects"))
    val head = readRef(repository, "main").getOrElse(fail("missing main head"))
    val (_, patchDigest, _) = readBlockSummary(cas, head)
    val treeDigest = readPatchTree(cas, patchDigest)
    val treeEntries = readTreeEntries(cas, treeDigest)

    val textId = readMaterializerId(cas, treeEntries.getOrElse("alpha.txt", fail("missing alpha.txt")))
    assertEquals(textId, "blob-v1")

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

  test("the project catalogue binds source through declared Grammar and Meta artifacts") {
    val temp = Files.createTempDirectory("stratum-repository-test-")
    val source = temp.resolve("source")
    val repository = temp.resolve("chain")
    Files.createDirectories(source)
    Files.writeString(source.resolve("Good.scala"), "object Good { def value = 1 }\n")
    Files.writeString(source.resolve("good.rs"), "fn value() -> i32 { 1 }\n")
    Files.writeString(source.resolve("Reference.canon"), "#d" + "00" * 32 + "\n")
    val declarations = LanguageCatalogue.load(projectRoot).fold(error => fail(error), identity)
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
        "--catalogue-root", projectRoot.toString
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

  private def readTreeEntries(cas: DirectoryCas, digest: Digest): Map[String, Canon] =
    cas.get(digest) match
      case Some(Artifact("tree", Canon.Node("tree", Vector(Canon.L(entries))))) =>
        entries.collect {
          case entry @ Canon.Node("entry", Vector(Canon.S(path), _, _, _, _, _, _, _)) => path -> entry
          case entry @ Canon.Node("entry", Vector(Canon.S(path), _, _, _, _, _, _))    => path -> entry
          case entry @ Canon.Node("entry", Vector(Canon.S(path), _, _, _, _, _))       => path -> entry
        }.toMap
      case _ => fail(s"invalid tree ${digest.hex}")

  private def readMaterializerId(cas: DirectoryCas, entry: Canon): String =
    entry match
      case Canon.Node("entry", Vector(_, _, _, Canon.R(materializer), _, _, _, _)) =>
        cas.get(materializer) match
          case Some(Artifact("materializer", Canon.Node("materializer", Vector(Canon.Sym(id))))) => id
          case _ => fail(s"invalid materializer ${materializer.hex}")
      case _ => fail("entry does not carry explicit materializer identity")
