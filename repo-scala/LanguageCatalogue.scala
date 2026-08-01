package stratum.repo

import stratum.artifact.{Artifact, DirectoryCas}
import stratum.canon.{Canon, CanonText, Digest}
import stratum.grammar.GrammarMachine0

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

final case class DeclaredLanguage(
    declaration: Canon,
    name: String,
    extensions: Vector[String],
    exactPaths: Vector[String],
    grammarPath: Option[String],
    metaPath: Option[String],
    reader: String
)

final case class StructuredFile(
  content: Digest,
  blob: Option[Digest],
    materializer: Digest,
    language: Digest,
    grammar: Option[Digest],
    meta: Option[Digest],
    syntax: Digest
)

object LanguageCatalogue:

  private val BlobBackedGrammarLanguages = Set("text", "shell")

  private def supportsGeneratedCheckout(language: DeclaredLanguage): Boolean =
    language.reader match
      case "grammar" => !BlobBackedGrammarLanguages.contains(language.name)
      case "canon"   => true
      case _          => false

  private def materializerId(language: DeclaredLanguage, storeBlob: Boolean): String =
    if storeBlob then "blob-v1"
    else
      language.reader match
        case "grammar"          => "grammar0-native-print-v1"
        case "canon"            => "canon-text-write-v1"
        case "negative-fixture" => "negative-fixture-bytes-v1"
        case other                => s"unsupported-$other"

  def load(source: Path): Either[String, Vector[DeclaredLanguage]] =
    val path = source.resolve("languages/catalogue.canon")
    if !Files.isRegularFile(path) then Left(s"no language catalogue at $path")
    else
      CanonText.read(Files.readString(path)).flatMap {
        case Canon.L(items) =>
          val parsed = items.map(readDeclaration)
          parsed.collectFirst { case Left(error) => error } match
            case Some(error) => Left(error)
            case None        => Right(parsed.collect { case Right(value) => value })
        case _ => Left("language catalogue must be a list")
      }

  def structure(
      source: Path,
      relative: String,
      bytes: Array[Byte],
      declarations: Vector[DeclaredLanguage],
      cas: DirectoryCas
  ): Either[String, StructuredFile] =
    select(relative, declarations).flatMap { language =>
      val blobArtifact = Artifact("blob", Canon.node("blob", Canon.Y(bytes.toVector)))
      val languageRef = cas.put(Artifact("language", language.declaration))
      val grammar = language.grammarPath.map(path => readCanon(source, path, "grammar")).sequence
      val meta = language.metaPath.map(path => readCanon(source, path, "meta program")).sequence
      for
        grammarBody <- grammar
        metaBody <- meta
        syntax <- readSyntax(language, bytes, grammarBody)
      yield
        val storeBlob = !supportsGeneratedCheckout(language)
        val blobRef = if storeBlob then Some(cas.put(blobArtifact)) else None
        val materializerRef = cas.put(Artifact("materializer", Canon.node("materializer", Canon.Sym(materializerId(language, storeBlob)))))
        val grammarRef = grammarBody.map(body => cas.put(Artifact("grammar", body)))
        val metaRef = metaBody.map(body => cas.put(Artifact("meta-program", body)))
        val syntaxRef = cas.put(
          Artifact(
            "syntax",
            Canon.node(
              "syntax",
              Canon.R(languageRef),
              grammarRef.map(Canon.R.apply).getOrElse(Canon.Sym("native")),
              syntax
            )
          )
        )
        val content = cas.put(
          Artifact(
            "structured-content",
            Canon.node(
              "structured-content",
              blobRef.map(Canon.R.apply).getOrElse(Canon.Sym("generated")),
              Canon.R(languageRef),
              grammarRef.map(Canon.R.apply).getOrElse(Canon.Sym("native")),
              metaRef.map(Canon.R.apply).getOrElse(Canon.Sym("native")),
              Canon.R(materializerRef),
              Canon.R(syntaxRef)
            )
          )
        )
        StructuredFile(content, blobRef, materializerRef, languageRef, grammarRef, metaRef, syntaxRef)
    }

  def select(path: String, declarations: Vector[DeclaredLanguage]): Either[String, DeclaredLanguage] =
    declarations.find(_.exactPaths.exists(exact => path == exact || path.startsWith(exact)))
      .orElse(declarations.find(_.extensions.exists(path.endsWith)))
      .toRight(s"no declared language for $path")

  private def readDeclaration(value: Canon): Either[String, DeclaredLanguage] = value match
    case declaration @ Canon.Node(_, Vector(
          Canon.Sym(name),
          Canon.L(extensions),
          Canon.L(exactPaths),
          grammarPath,
          metaPath,
          Canon.Sym(reader)
        )) =>
      Right(
        DeclaredLanguage(
          declaration,
          name,
          extensions.collect { case Canon.S(value) => value },
          exactPaths.collect { case Canon.S(value) => value },
          optionalPath(grammarPath),
          optionalPath(metaPath),
          reader
        )
      )
    case _ => Left(s"invalid language declaration: ${CanonText.write(value)}")

  private def optionalPath(value: Canon): Option[String] = value match
    case Canon.S(path) => Some(path)
    case _             => None

  private def readCanon(source: Path, relative: String, label: String): Either[String, Canon] =
    val path = source.resolve(relative).normalize()
    if !path.startsWith(source) || !Files.isRegularFile(path) then Left(s"missing $label $relative")
    else CanonText.read(Files.readString(path)).left.map(error => s"$relative: $error")

  private def readSyntax(
      language: DeclaredLanguage,
      bytes: Array[Byte],
      grammar: Option[Canon]
  ): Either[String, Canon] =
    language.reader match
      case "grammar" =>
        for
          grammarBody <- grammar.toRight(s"${language.name} has no grammar")
          text <- decodeUtf8(language.name, bytes)
          loaded <- GrammarMachine0.load(grammarBody)
          syntax <- GrammarMachine0.parse(loaded, text)
        yield syntax
      case "canon" =>
        Artifact.decode(bytes).map(_.toCanon).orElse {
          decodeUtf8(language.name, bytes).flatMap(CanonText.read)
        }.map(quoteReferences)
      case "negative-fixture" => Right(Canon.node("negative-fixture", Canon.Y(bytes.toVector)))
      case other => Left(s"unknown declared reader $other for ${language.name}")

  private def decodeUtf8(name: String, bytes: Array[Byte]): Either[String, String] =
    val text = String(bytes, UTF_8)
    if text.getBytes(UTF_8).sameElements(bytes) then Right(text) else Left(s"$name source is not UTF-8")

  /** A reference written in source is syntax, not an edge in this CAS closure. */
  private def quoteReferences(value: Canon): Canon = value match
    case Canon.R(digest)     => Canon.node("syntax-reference", Canon.S(digest.hex))
    case Canon.L(items)      => Canon.L(items.map(quoteReferences))
    case Canon.M(entries)    => Canon.M(entries.map((key, item) => quoteReferences(key) -> quoteReferences(item)))
    case Canon.Node(tag, xs) => Canon.Node(tag, xs.map(quoteReferences))
    case scalar              => scalar

  extension [A](value: Option[Either[String, A]])
    private def sequence: Either[String, Option[A]] = value match
      case None         => Right(None)
      case Some(result) => result.map(Some.apply)
