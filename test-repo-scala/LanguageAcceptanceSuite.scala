package stratum.repo

import stratum.canon.CanonText
import stratum.cli.Cli
import stratum.grammar.GrammarMachine0

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
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
 * A language only belongs in `completedLanguages` once it has a real
 * structural grammar; the remaining catch-all languages are covered by the
 * separate guard in StratumRepoSuite until they get their own packages.
 */
class LanguageAcceptanceSuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val projectRoot: Path =
    if Files.isDirectory(root.resolve("languages")) then root else root.getParent

  private val rustBinary: Path = projectRoot.resolve("host-rust/target/release/stratum-verify")

  private val excludedNames =
    Set(".git", ".stratum", ".bloop", ".bsp", ".metals", ".scala-build", "target", "node_modules", "__pycache__", ".lake")

  private val completedLanguages =
    Vector("json", "toml", "yaml", "html", "markdown", "properties", "transcript", "gitignore", "gitattributes", "shell", "typescript", "lean", "rust", "scala")

  private val metaPrograms = Vector(
    "languages/meta/prelude.meta",
    "languages/meta/elaborate.meta",
    "languages/grammar/elaborate.meta"
  )

  private lazy val declarations: Vector[DeclaredLanguage] =
    LanguageCatalogue.load(projectRoot).fold(error => fail(error), identity)

  private def sourceFiles(): Vector[Path] =
    val stream = Files.walk(projectRoot)
    try
      stream.iterator().asScala
        .filter(Files.isRegularFile(_))
        .filterNot(path => unix(projectRoot.relativize(path)).split("/").exists(excludedNames.contains))
        .toVector
    finally stream.close()

  private def unix(path: Path): String = path.iterator().asScala.map(_.toString).mkString("/")

  private def filesFor(language: String): Vector[Path] =
    sourceFiles().filter { file =>
      LanguageCatalogue.select(unix(projectRoot.relativize(file)), declarations).toOption.exists(_.name == language)
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

  for language <- completedLanguages do
    test(s"$language: parses every repository file and is a stable print/reparse fixpoint") {
      val declared = declarations.find(_.name == language).getOrElse(fail(s"no declared language $language"))
      val grammar = loadGrammar(declared)
      val files = filesFor(language)
      assert(files.nonEmpty, s"no repository files matched declared language $language")
      files.foreach { file =>
        val text = Files.readString(file, UTF_8)
        val parsed = GrammarMachine0.parse(grammar, text).fold(error => fail(s"$file: $error"), identity)
        val printed = GrammarMachine0.print(grammar, parsed).fold(error => fail(s"$file: print: $error"), identity)
        val reparsed = GrammarMachine0.parse(grammar, printed).fold(error => fail(s"$file: reparse: $error"), identity)
        assertEquals(reparsed, parsed, s"$file: print(parse(x)) does not reparse to a stable fixpoint")
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
          case Right(v) => fail(s"$file: malformed fixture was accepted as $v")
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
        val files = filesFor(language)
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
          assertEquals(output, CanonText.write(scalaValue), s"$file: hosts disagree on the parsed value")
        }
    }
