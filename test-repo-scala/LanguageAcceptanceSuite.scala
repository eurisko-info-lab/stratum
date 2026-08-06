package stratum.repo

import stratum.canon.{CanonText, Digest}
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

  private val metaPrograms = Vector(
    "languages/meta/prelude.meta",
    "languages/meta/elaborate.meta",
    "languages/grammar/elaborate.meta"
  )

  private lazy val declarations: Vector[DeclaredLanguage] =
    LanguageDeclarations.load(projectRoot).fold(error => fail(error), identity)

  private lazy val acceptedLanguages: Vector[String] =
    declarations
      .filter(language => language.reader == "grammar")
      .map(_.name)
      .filterNot(_ == "scala")
      .filter { language =>
        val directory = projectRoot.resolve(s"fixtures/languages/adversarial/$language")
        Files.isDirectory(directory) &&
        Files.list(directory).toList.asScala.exists(Files.isRegularFile(_))
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

  private def unix(path: Path): String = path.iterator().asScala.map(_.toString).mkString("/")

  private def shortDigest(text: String): String = Digest.of(text.getBytes(UTF_8)).hex.take(16)

  private def filesFor(language: String): Vector[Path] =
    sourceFiles().filter { file =>
      val relative = unix(projectRoot.relativize(file))
      val selected = LanguageDeclarations.select(relative, declarations).toOption.exists(_.name == language)
      val skipLocalRustHost = language == "rust" && relative.startsWith("host-rust/src/")
      val skipLocalScalaHost = language == "scala" && relative.startsWith("host-scala/")
      selected && !skipLocalRustHost && !skipLocalScalaHost
    }

  private def loadGrammar(language: DeclaredLanguage): GrammarMachine0.Grammar =
    val path = projectRoot.resolve(language.grammarPath.getOrElse(fail(s"${language.name} has no grammar")))
    val canon = CanonText.read(Files.readString(path)).fold(error => fail(s"$path: $error"), identity)
    GrammarMachine0.load(canon).fold(error => fail(s"$path: $error"), identity)

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
      val files = filesFor(language)
      if files.nonEmpty then
        files.foreach { file =>
          val text = Files.readString(file, UTF_8)
          val parsed = GrammarMachine0.parse(grammar, text).fold(error => fail(s"$file: $error"), identity)
          val printed = GrammarMachine0.print(grammar, parsed).fold(error => fail(s"$file: print: $error"), identity)
          val reparsed = GrammarMachine0.parse(grammar, printed).fold(error => fail(s"$file: reparse: $error"), identity)
          if reparsed != parsed then
            val parsedText = CanonText.write(parsed)
            val reparsedText = CanonText.write(reparsed)
            fail(
              s"$file: print(parse(x)) unstable; parsed=${shortDigest(parsedText)} reparsed=${shortDigest(reparsedText)}"
            )
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
      if language != "scala" then
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
          val generatedGrammar = Files.readString(grammarOut)
          val committedGrammar = Files.readString(projectRoot.resolve(declared.grammarPath.get))
          if generatedGrammar != committedGrammar then
            fail(
              s"${declared.grammarPath.get} is stale relative to $grammarSource; generated=${shortDigest(generatedGrammar)} committed=${shortDigest(committedGrammar)}"
            )

          val metaResult = Cli.run(
            projectRoot,
            Vector("meta", "elaborate", "--grammar", "languages/meta/meta.generated.grammar") ++
              metaPrograms.flatMap(p => Vector("--program", p)) ++
              Vector("--source", metaSource, "--out", metaOut.toString)
          )
          assertEquals(metaResult.code, 0, metaResult.output)
          val generatedMeta = Files.readString(metaOut)
          val committedMeta = Files.readString(projectRoot.resolve(declared.metaPath.get))
          if generatedMeta != committedMeta then
            fail(
              s"${declared.metaPath.get} is stale relative to $metaSource; generated=${shortDigest(generatedMeta)} committed=${shortDigest(committedMeta)}"
            )
        finally
          Files.deleteIfExists(grammarOut)
          Files.deleteIfExists(metaOut)
    }

    test(s"$language: the independent Rust host parses every file to the same canonical value") {
      if requireRust() then
        val declared = declarations.find(_.name == language).getOrElse(fail(s"no declared language $language"))
        val grammar = loadGrammar(declared)
        val files = filesFor(language)
        if files.nonEmpty then
          files.foreach { file =>
            val text = Files.readString(file, UTF_8)
            val scalaValue = GrammarMachine0.parse(grammar, text).fold(error => fail(s"$file: $error"), identity)
            val (code, output) = runRust(
              "grammar-parse",
              declared.grammarPath.get,
              "--text-file",
              unix(projectRoot.relativize(file))
            )
            assertEquals(code, 0, s"$file: rust host failed: $output")
            val scalaText = CanonText.write(scalaValue)
            if output != scalaText then
              fail(
                s"$file: hosts disagree on parsed value; rust=${shortDigest(output)} scala=${shortDigest(scalaText)}"
              )
          }
    }
