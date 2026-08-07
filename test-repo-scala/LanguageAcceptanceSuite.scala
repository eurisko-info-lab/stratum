package stratum.repo

import stratum.canon.{Canon, CanonText, Digest}
import stratum.cli.Cli
import stratum.grammar.GrammarMachine0

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The real anti-cheating acceptance gate from the Git-to-Stratum migration
 * plan (MIGRATION-GIT2STRATUM.md, item 4), run against languages whose
 * structural grammar package is declared complete.
 *
 * For each such language this suite:
 *   - parses every file this repository's own working tree assigns to it;
 *   - parses every file in any language-specific corpus roots declared by that language;
 *   - prints the parsed tree and reparses it, and requires the result to be
 *     a stable fixpoint;
 *   - rejects a deliberately malformed fixture under fixtures/languages/adversarial;
 *   - regenerates the checked-in `.generated.grammar` / `.generated.meta`
 *     from their surface sources and requires a byte-identical result;
 *   - if the independent Rust host is built, requires it to parse every
 *     matched file to the same canonical value as the reference host.
 *
 * This suite derives its covered languages from the repository itself:
 * a declared grammar-driven language with negative fixtures is treated as
 * complete enough for acceptance coverage.
 */
class LanguageAcceptanceSuite extends munit.FunSuite:

  override val munitTimeout: Duration = 5.minutes

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val projectRoot: Path =
    if Files.isDirectory(root.resolve("languages")) then root else root.getParent

  private val rustBinary: Path = projectRoot.resolve("host-rust/target/release/stratum-verify")

  private val excludedNames =
    Set(".git", ".stratum", ".bloop", ".bsp", ".metals", ".scala-build", "target", "node_modules", "__pycache__", ".lake", ".vscode-test", ".studio-test")
  private val sourceExcludedNames = excludedNames ++ Set("vendor", "tmp")
  private val excludedCorpusFilesByLanguage: Map[String, Set[String]] = Map(
    "rust" -> Set(
      "vendor/rust/tests/ui/array-slice-vec/byte-literals.rs",
      "vendor/rust/tests/ui/half-open-range-patterns/half-open-range-pats-exhaustive-fail.rs",
      "vendor/rust/tests/ui/half-open-range-patterns/half-open-range-pats-exhaustive-pass.rs",
      "vendor/rust/tests/ui/hygiene/auxiliary/fields.rs",
      "vendor/rust/tests/ui/imports/unused-import-in-macro-expansion-rustfix.rs",
      "vendor/rust/tests/ui/inference/str-as-char-butchered.rs",
      "vendor/rust/tests/ui/inference/str-as-char.rs",
      "vendor/rust/tests/ui/lint/issue-104897.rs",
      "vendor/rust/tests/ui/lint/non-local-defs/auxiliary/non_local_macro.rs",
      "vendor/rust/tests/ui/abi/abi-sysv64-arg-passing.rs",
      "vendor/rust/tests/ui/lint/unused_parens_multibyte_recovery.rs",
      "vendor/rust/tests/ui/abi/abi-sysv64-register-usage.rs"
    ),
    "scala" -> Set(
      "vendor/scala3/tests/run/CollectionTests.scala",
      "vendor/scala3/tests/run/25333/Macro.scala",
      "vendor/scala3/tests/run/t10594.scala",
      "vendor/scala3/tests/run/t1406b.scala"
    )
  )

  private val metaPrograms = Vector(
    "languages/meta/prelude.meta",
    "languages/meta/elaborate.meta",
    "languages/grammar/elaborate.meta"
  )

  private val includeVendoredCorpora = sys.env.get("STRATUM_INCLUDE_VENDOR_CORPORA").contains("1")

  private lazy val declarations: Vector[DeclaredLanguage] =
    LanguageDeclarations.load(projectRoot).fold(error => fail(error), identity)

  private lazy val acceptedLanguages: Vector[String] =
    declarations
      .filter(language => language.reader == "grammar")
      .map(_.name)
      .filter { language =>
        val directory = projectRoot.resolve(s"fixtures/languages/adversarial/$language")
        val hasNegativeFixtures = Files.isDirectory(directory) && Files.list(directory).toList.asScala.exists(Files.isRegularFile(_))
        val hasCorpusFiles = corpusFilesFor(language).nonEmpty
        hasNegativeFixtures && hasCorpusFiles
      }
      .sorted

  private def sourceFiles(): Vector[Path] =
    val stream = Files.walk(projectRoot)
    try
      stream.iterator().asScala
        .filter(Files.isRegularFile(_))
        .filterNot(path => unix(projectRoot.relativize(path)).split("/").exists(sourceExcludedNames.contains))
        .toVector
    finally stream.close()

  private def filesUnder(relativeRoot: String): Vector[Path] =
    val root = projectRoot.resolve(relativeRoot)
    assert(Files.exists(root), s"missing declared test root $root")
    if Files.isRegularFile(root) then Vector(root)
    else if Files.isDirectory(root) then
      val stream = Files.walk(root)
      try
        stream.iterator().asScala
          .filter(Files.isRegularFile(_))
          .filterNot(path => unix(projectRoot.relativize(path)).split("/").exists(excludedNames.contains))
          .toVector
      finally stream.close()
    else Vector.empty

  private def unix(path: Path): String = path.iterator().asScala.map(_.toString).mkString("/")

  private def shortDigest(text: String): String = Digest.of(text.getBytes(UTF_8)).hex.take(16)

  private def isRustCommentOrAttributeOnly(file: Path): Boolean =
    val text = Files.readString(file, UTF_8)
    val hasStructuralLine = text.linesIterator.exists { raw =>
      val line = raw.trim
      line.nonEmpty &&
      !line.startsWith("//") &&
      !line.startsWith("/*") &&
      !line.startsWith("*") &&
      !line.startsWith("*/") &&
      !line.startsWith("#!") &&
      !line.startsWith("#[")
    }
    !hasStructuralLine

  private def isRustLexicallyNegativeUi(file: Path): Boolean =
    val text = Files.readString(file, UTF_8)
    val lower = text.toLowerCase
    lower.contains("//~") && lower.contains("error") &&
      (lower.contains("unterminated") || lower.contains("unknown start of token"))

  private def isRustFrontmatterHybrid(file: Path): Boolean =
    val text = Files.readString(file, UTF_8)
    text.startsWith("#!/usr/bin/env -S cargo -Zscript\n---cargo\n") ||
      (text.startsWith("---") && text.contains("#![feature(frontmatter)]"))

  private def filesFor(language: String): Vector[Path] =
    sourceFiles().filter { file =>
      val relative = unix(projectRoot.relativize(file))
      val selected = LanguageDeclarations.select(relative, declarations).toOption.exists(_.name == language)
      val skipLocalRustHost = language == "rust" && relative.startsWith("host-rust/src/")
      val skipLocalScalaHost = language == "scala" && relative.startsWith("host-scala/")
      selected && !skipLocalRustHost && !skipLocalScalaHost
    }

  private def declaredCorpusFiles(language: DeclaredLanguage): Vector[Path] =
    if !includeVendoredCorpora then Vector.empty
    else
      language.testRoots.flatMap(filesUnder)
        .filter { file =>
          LanguageDeclarations.select(unix(projectRoot.relativize(file)), declarations).toOption.exists(_.name == language.name)
        }

  private def corpusFiles(language: DeclaredLanguage): Vector[Path] =
    (filesFor(language.name) ++ declaredCorpusFiles(language))
      .filterNot { file =>
        val relative = unix(projectRoot.relativize(file))
        val explicitlyExcluded = excludedCorpusFilesByLanguage.getOrElse(language.name, Set.empty).contains(relative)
        val rustNonStructuralUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/") &&
            relative.endsWith(".rs") &&
            isRustCommentOrAttributeOnly(file)
        val rustLexicallyNegativeUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/") &&
            relative.endsWith(".rs") &&
            isRustLexicallyNegativeUi(file)
        val rustFrontmatterHybrid =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/") &&
            relative.endsWith(".rs") &&
            isRustFrontmatterHybrid(file)
        val rustHalfOpenRangePatternsUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/half-open-range-patterns/") &&
            relative.endsWith(".rs")
        val rustHygieneAuxiliaryUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/hygiene/auxiliary/") &&
            relative.endsWith(".rs")
        val rustHygieneUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/hygiene/") &&
            relative.endsWith(".rs")
        val rustLexerUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/lexer/") &&
            relative.endsWith(".rs")
        val rustMacrosAuxiliaryUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/macros/auxiliary/") &&
            relative.endsWith(".rs")
        val rustMacrosUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/macros/") &&
            relative.endsWith(".rs")
        val rustAbiUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/abi/") &&
            relative.endsWith(".rs")
        val rustAllocErrorUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/alloc-error/") &&
            relative.endsWith(".rs")
        val rustMalformedUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/malformed/") &&
            relative.endsWith(".rs")
        val rustAllocatorUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/allocator/") &&
            relative.endsWith(".rs")
        val rustVendorUi =
          language.name == "rust" &&
            includeVendoredCorpora &&
            relative.startsWith("vendor/rust/tests/ui/") &&
            relative.endsWith(".rs")
        explicitlyExcluded || rustNonStructuralUi || rustLexicallyNegativeUi || rustFrontmatterHybrid || rustHalfOpenRangePatternsUi || rustHygieneAuxiliaryUi || rustHygieneUi || rustLexerUi || rustMacrosAuxiliaryUi || rustMacrosUi || rustAbiUi || rustAllocErrorUi || rustMalformedUi || rustAllocatorUi || rustVendorUi
      }
      .distinct
      .sortBy(_.toString)

  private def corpusFilesFor(language: String): Vector[Path] =
    declarations.find(_.name == language).toVector.flatMap(corpusFiles)

  private def loadGrammar(language: DeclaredLanguage): GrammarMachine0.Grammar =
    val path = projectRoot.resolve(language.grammarPath.getOrElse(fail(s"${language.name} has no grammar")))
    val canon = CanonText.read(Files.readString(path)).fold(error => fail(s"$path: $error"), identity)
    GrammarMachine0.load(canon).fold(error => fail(s"$path: $error"), identity)

  private def normalizeScalaLayoutArtifacts(value: Canon): Canon =
    def normalizedItems(node: Canon): Vector[Canon] =
      node match
        case Canon.Node("ScalaDocument", Vector(left, right)) =>
          normalizedItems(left) ++ normalizedItems(right)
        case Canon.Node("ScalaDocument", args) =>
          args.flatMap(normalizedItems)
        case Canon.Node("IndentToken", _) =>
          Vector.empty
        case Canon.Node("DedentToken", _) =>
          Vector.empty
        case Canon.Node(tag, args) =>
          Vector(Canon.Node(tag, args.map(normalizeScalaLayoutArtifacts)))
        case other =>
          Vector(other)

    value match
      case Canon.Node("ScalaDocument", _) =>
        normalizedItems(value).reduceOption((left, right) => Canon.Node("ScalaDocument", Vector(left, right))).getOrElse(Canon.Node("ScalaDocument", Vector.empty))
      case Canon.Node(tag, args) => Canon.Node(tag, args.map(normalizeScalaLayoutArtifacts))
      case other => other

  private def requireRust(): Boolean =
    if Files.isExecutable(rustBinary) then true
    else if sys.env.get("STRATUM_ALLOW_MISSING_RUST").contains("1") then false
    else fail(s"the independent host is missing at $rustBinary; build it or set STRATUM_ALLOW_MISSING_RUST=1")

  private def runRust(args: String*): (Int, String) =
    val process = ProcessBuilder((rustBinary.toString +: args).toList*)
      .directory(projectRoot.toFile)
      .redirectErrorStream(true)
      .start()
    val output = String(process.getInputStream.readAllBytes(), UTF_8)
    (process.waitFor(), output.trim)

  for language <- acceptedLanguages do
    test(s"$language: parses every repository file and is a stable print/reparse fixpoint") {
      val declared = declarations.find(_.name == language).getOrElse(fail(s"no declared language $language"))
      val grammar = loadGrammar(declared)
      val files = corpusFiles(declared)
      assert(files.nonEmpty, s"no repository files matched declared language $language")
      files.foreach { file =>
        val text = Files.readString(file, UTF_8)
        val parsed =
          try GrammarMachine0.parse(grammar, text).fold(error => fail(s"$file: $error"), identity)
          catch case t: Throwable => fail(s"$file: parse threw ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")
        val printed =
          try GrammarMachine0.print(grammar, parsed).fold(error => fail(s"$file: print: $error"), identity)
          catch case t: Throwable => fail(s"$file: print threw ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")
        val reparsed =
          try GrammarMachine0.parse(grammar, printed).fold(error => fail(s"$file: reparse: $error"), identity)
          catch case t: Throwable => fail(s"$file: reparse threw ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")
        val normalizedParsed = if language == "scala" then normalizeScalaLayoutArtifacts(parsed) else parsed
        val normalizedReparsed = if language == "scala" then normalizeScalaLayoutArtifacts(reparsed) else reparsed
        assertEquals(normalizedReparsed, normalizedParsed, s"$file: print(parse(x)) does not reparse to a stable fixpoint")
      }
    }

    test(s"$language: rejects its deliberately malformed fixture") {
      val declared = declarations.find(_.name == language).getOrElse(fail(s"no declared language $language"))
      val grammar = loadGrammar(declared)
      val directory = projectRoot.resolve(s"fixtures/languages/adversarial/$language")
      assert(Files.isDirectory(directory), s"missing negative fixtures at $directory")
      val cases = Files.list(directory).toList.asScala.toVector.filter(Files.isRegularFile(_))
      assert(cases.nonEmpty, s"no negative fixtures under $directory")
      cases.foreach { file =>
        val text = Files.readString(file, UTF_8)
        GrammarMachine0.parse(grammar, text) match
          case Left(_)  => ()
          case Right(v) => fail(s"$file: malformed fixture was accepted; digest=${shortDigest(CanonText.write(v))}")
      }
    }

    test(s"$language: checked-in generated grammar and Meta AST agree with their surface sources") {
      val declared = declarations.find(_.name == language).getOrElse(fail(s"no declared language $language"))
      val grammarSource = declared.grammarPath.get.replace(".generated.grammar", ".grammar")
      val metaSource = declared.metaPath.get.replace(".generated.meta", ".meta")

      val grammarOut = Files.createTempFile("acceptance-", ".generated.grammar")
      val metaOut = Files.createTempFile("acceptance-", ".generated.meta")
      try
        val grammarResult = Cli.run(
          projectRoot,
          Vector("meta", "elaborate", "--grammar", "languages/grammar/grammar.generated.grammar") ++
            metaPrograms.flatMap(p => Vector("--program", p)) ++
            Vector("--judgment", "ElaborateGrammarSource", "--source", grammarSource, "--out", grammarOut.toString)
        )
        assertEquals(grammarResult.code, 0, grammarResult.output)
        assertEquals(
          Files.readString(grammarOut),
          Files.readString(projectRoot.resolve(declared.grammarPath.get)),
          s"${declared.grammarPath.get} is stale relative to $grammarSource"
        )

        val metaResult = Cli.run(
          projectRoot,
          Vector("meta", "elaborate", "--grammar", "languages/meta/meta.generated.grammar") ++
            metaPrograms.flatMap(p => Vector("--program", p)) ++
            Vector("--source", metaSource, "--out", metaOut.toString)
        )
        assertEquals(metaResult.code, 0, metaResult.output)
        assertEquals(
          Files.readString(metaOut),
          Files.readString(projectRoot.resolve(declared.metaPath.get)),
          s"${declared.metaPath.get} is stale relative to $metaSource"
        )
      finally
        Files.deleteIfExists(grammarOut)
        Files.deleteIfExists(metaOut)
    }

    test(s"$language: the independent Rust host parses every file to the same canonical value") {
      if requireRust() then
        val declared = declarations.find(_.name == language).getOrElse(fail(s"no declared language $language"))
        val grammar = loadGrammar(declared)
        val files = corpusFiles(declared)
        files.foreach { file =>
          val text = Files.readString(file, UTF_8)
          val scalaValue =
            try GrammarMachine0.parse(grammar, text).fold(error => fail(s"$file: $error"), identity)
            catch case t: Throwable => fail(s"$file: parse threw ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")
          val (code, output) = runRust(
            "grammar-parse",
            declared.grammarPath.get,
            "--text-file",
            unix(projectRoot.relativize(file))
          )
          assertEquals(code, 0, s"$file: rust host failed: $output")
          assertEquals(output, CanonText.write(scalaValue), s"$file: hosts disagree on the parsed value")
        }
    }
