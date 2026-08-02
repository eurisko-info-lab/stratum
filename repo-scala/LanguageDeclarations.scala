package stratum.repo

import stratum.artifact.{Artifact, DirectoryCas}
import stratum.canon.{Canon, CanonText, Digest, Schema}
import stratum.grammar.GrammarMachine0

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final case class DeclaredLanguage(
    declaration: Canon,
    name: String,
    extensions: Vector[String],
    exactPaths: Vector[String],
    properties: Set[String],
    grammarPath: Option[String],
    metaPath: Option[String],
    testRoots: Vector[String],
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

object DeclaredLanguage:
  given Schema[DeclaredLanguage] with
    def encode(value: DeclaredLanguage): Canon =
      Canon.node(
        "declared-language",
        value.declaration,
        Canon.S(value.name),
        Canon.L(value.extensions.map(Canon.S.apply)),
        Canon.L(value.exactPaths.map(Canon.S.apply)),
        Canon.L(value.properties.toVector.sorted.map(Canon.Sym.apply)),
        value.grammarPath.map(Canon.S.apply).getOrElse(Canon.Sym("none")),
        value.metaPath.map(Canon.S.apply).getOrElse(Canon.Sym("none")),
        Canon.L(value.testRoots.map(Canon.S.apply)),
        Canon.Sym(value.reader)
      )

    def decode(value: Canon): Either[String, DeclaredLanguage] = value match
      case Canon.Node(
            "declared-language",
            Vector(
              declaration,
              Canon.S(name),
              Canon.L(extensions),
              Canon.L(exactPaths),
              Canon.L(properties),
              grammarPath,
              metaPath,
              Canon.L(testRoots),
              Canon.Sym(reader)
            )
          ) =>
        val parsedExtensions = extensions.collect { case Canon.S(value) => value }
        val parsedExactPaths = exactPaths.collect { case Canon.S(value) => value }
        val parsedProperties = properties.collect { case Canon.Sym(value) => value }.toSet
        val parsedGrammarPath = grammarPath match
          case Canon.S(path) => Some(path)
          case Canon.Sym("none") => None
          case _ => None
        val parsedMetaPath = metaPath match
          case Canon.S(path) => Some(path)
          case Canon.Sym("none") => None
          case _ => None
        val parsedTestRoots = testRoots.collect { case Canon.S(value) => value }
        Right(
          DeclaredLanguage(
            declaration,
            name,
            parsedExtensions,
            parsedExactPaths,
            parsedProperties,
            parsedGrammarPath,
            parsedMetaPath,
            parsedTestRoots,
            reader
          )
        )
      case other => Left(s"not a declared language: ${CanonText.write(other)}")

    def refs(value: DeclaredLanguage): Vector[Digest] = Vector.empty

object StructuredFile:
  given Schema[StructuredFile] with
    def encode(value: StructuredFile): Canon =
      Canon.node(
        "structured-file",
        Canon.S(value.content.hex),
        value.blob.map(d => Canon.R(d)).getOrElse(Canon.Sym("generated")),
        Canon.R(value.materializer),
        Canon.R(value.language),
        value.grammar.map(Canon.R.apply).getOrElse(Canon.Sym("native")),
        value.meta.map(Canon.R.apply).getOrElse(Canon.Sym("native")),
        Canon.R(value.syntax)
      )

    def decode(value: Canon): Either[String, StructuredFile] = value match
      case Canon.Node(
            "structured-file",
            Vector(
              Canon.S(contentHex),
              blobValue,
              Canon.R(materializer),
              Canon.R(language),
              grammarValue,
              metaValue,
              Canon.R(syntax)
            )
          ) =>
        for
          content <- Digest.fromHex(contentHex).left.map(_ => s"invalid content digest in structured file")
          blob <- blobValue match
            case Canon.R(d) => Right(Some(d))
            case Canon.Sym("generated") => Right(None)
            case _ => Left("invalid blob marker in structured file")
          grammar <- grammarValue match
            case Canon.R(d) => Right(Some(d))
            case Canon.Sym("native") => Right(None)
            case _ => Left("invalid grammar marker in structured file")
          meta <- metaValue match
            case Canon.R(d) => Right(Some(d))
            case Canon.Sym("native") => Right(None)
            case _ => Left("invalid meta marker in structured file")
        yield StructuredFile(content, blob, materializer, language, grammar, meta, syntax)
      case other => Left(s"not a structured file: ${CanonText.write(other)}")

    def refs(value: StructuredFile): Vector[Digest] =
      Vector(value.content, value.materializer, value.language, value.syntax) ++ value.blob ++ value.grammar ++ value.meta

object LanguageDeclarations:

  private def supportsGeneratedCheckout(language: DeclaredLanguage): Boolean =
    language.reader match
      case "grammar" => !language.properties.contains("blob-backed-checkout")
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

  private val NativeCanon = Canon.Sym("native-canon")
  private val Bytes = Canon.Sym("bytes")

  private final case class SourceLanguageDescriptor(
      name: String,
      extensions: Vector[String],
      exactPaths: Vector[String],
      properties: Set[String],
      relativeRoot: String,
      testRoots: Vector[String]
  )

  private final case class SourceLanguageDeclaration(
      extensions: Option[Vector[String]],
      exactPaths: Vector[String],
      properties: Set[String],
      testRoots: Vector[String]
  )

  private val SpecialDeclarations: Vector[DeclaredLanguage] = Vector(
    declaredLanguage(
      "canon",
      Vector(".canon"),
      Vector.empty,
      Set.empty,
      Some(NativeCanon),
      Some(NativeCanon),
      Vector.empty,
      "canon"
    ),
    declaredLanguage(
      "canon-adversarial",
      Vector.empty,
      Vector("fixtures/canon/adversarial/"),
      Set.empty,
      Some(Bytes),
      Some(Bytes),
      Vector.empty,
      "negative-fixture"
    ),
    declaredLanguage(
      "languages-adversarial",
      Vector.empty,
      Vector("fixtures/languages/adversarial/"),
      Set.empty,
      Some(Bytes),
      Some(Bytes),
      Vector.empty,
      "negative-fixture"
    ),
    declaredLanguage(
      "generated-meta",
      Vector(".generated.meta"),
      Vector(
        "languages/foundation/delta.meta",
        "languages/foundation/derive.meta",
        "languages/foundation/foundation.meta",
        "languages/grammar/elaborate.meta",
        "languages/lambda/lambda.bootstrap.meta",
        "languages/meta/prelude.meta",
        "languages/meta/elaborate.meta",
        "languages/meta/source.meta"
      ),
      Set.empty,
      Some(NativeCanon),
      Some(NativeCanon),
      Vector.empty,
      "canon"
    ),
    declaredLanguage(
      "generated-grammar",
      Vector(".generated.grammar"),
      Vector("languages/lambda/lambda.bootstrap.grammar"),
      Set.empty,
      Some(NativeCanon),
      Some(NativeCanon),
      Vector.empty,
      "canon"
    )
  )

  def load(source: Path): Either[String, Vector[DeclaredLanguage]] =
    (sourceLanguageDirectories(source), applicationLanguageDirectories(source)) match
      case (Right(languageDescriptors), Right(applicationDescriptors)) =>
        val descriptors = languageDescriptors ++ applicationDescriptors
        val sourceDeclarations = descriptors.map { descriptor =>
          val (grammar, meta) = declarationArtifactsFor(descriptor)
          declaredLanguage(
            descriptor.name,
            descriptor.extensions,
            descriptor.exactPaths,
            descriptor.properties,
            Some(Canon.S(grammar)),
            Some(Canon.S(meta)),
            descriptor.testRoots,
            "grammar"
          )
        }
        Right(SpecialDeclarations ++ sourceDeclarations)
      case (Left(error), _) => Left(error)
      case (_, Left(error)) => Left(error)

  private def sourceLanguageDirectories(source: Path): Either[String, Vector[SourceLanguageDescriptor]] =
    val root = source.resolve("languages")
    if !Files.isDirectory(root) then Left(s"no languages directory at $root")
    else
      val stream = Files.list(root)
      try
        val names = stream.iterator().asScala
            .filter(Files.isDirectory(_))
            .map(_.getFileName.toString)
            .filter(isDeclarableLanguageDirectory(root, _))
            .toVector
            .sorted
        val descriptors = names.map { name =>
          sourceLanguageDeclaration(root, name).map { declared =>
            SourceLanguageDescriptor(
              name,
              declared.extensions.getOrElse(Vector(s".$name")),
              declared.exactPaths,
              declared.properties,
              "languages",
              declared.testRoots
            )
          }
        }
        descriptors.collectFirst { case Left(error) => error } match
          case Some(error) => Left(error)
          case None        => Right(descriptors.collect { case Right(descriptor) => descriptor })
      finally stream.close()

  private def applicationLanguageDirectories(source: Path): Either[String, Vector[SourceLanguageDescriptor]] =
    val root = source.resolve("applications")
    if !Files.isDirectory(root) then Right(Vector.empty)
    else
      val stream = Files.list(root)
      try
        Right(
          stream.iterator().asScala
            .filter(Files.isDirectory(_))
            .map(_.getFileName.toString)
            .filter(isDeclarableApplicationDirectory(root, _))
            .toVector
            .sorted
            .map { name =>
              sourceLanguageDeclaration(root, name).fold(error => throw IllegalArgumentException(error), declaration =>
                SourceLanguageDescriptor(
                  name,
                  declaration.extensions.getOrElse(Vector(s".$name")),
                  declaration.exactPaths,
                  declaration.properties,
                  "applications",
                  declaration.testRoots
                )
              )
            }
        )
      finally stream.close()

  private def sourceLanguageDeclaration(root: Path, name: String): Either[String, SourceLanguageDeclaration] =
    val path = root.resolve(name).resolve(s"$name.declaration.canon")
    if !Files.isRegularFile(path) then Right(SourceLanguageDeclaration(None, Vector.empty, Set.empty, Vector.empty))
    else
      CanonText.read(Files.readString(path)).left.map(error => s"${path.toString}: $error").flatMap {
        case Canon.L(items) =>
          val invalid = items.collect { case other if !other.isInstanceOf[Canon.Sym] => CanonText.write(other) }
          if invalid.nonEmpty then Left(s"${path.toString}: declaration properties must be symbols")
          else Right(SourceLanguageDeclaration(None, Vector.empty, items.collect { case Canon.Sym(value) => value }.toSet, Vector.empty))
        case Canon.Node(_, Vector(Canon.L(extensions), Canon.L(exactPaths), Canon.L(properties), Canon.L(testRoots))) =>
          val badExtensions = extensions.collect { case other if !other.isInstanceOf[Canon.S] => CanonText.write(other) }
          val badExactPaths = exactPaths.collect { case other if !other.isInstanceOf[Canon.S] => CanonText.write(other) }
          val badProperties = properties.collect { case other if !other.isInstanceOf[Canon.Sym] => CanonText.write(other) }
          val badTestRoots = testRoots.collect { case other if !other.isInstanceOf[Canon.S] => CanonText.write(other) }
          if badExtensions.nonEmpty then Left(s"${path.toString}: declaration extensions must be strings")
          else if badExactPaths.nonEmpty then Left(s"${path.toString}: declaration exact paths must be strings")
          else if badProperties.nonEmpty then Left(s"${path.toString}: declaration properties must be symbols")
          else if badTestRoots.nonEmpty then Left(s"${path.toString}: declaration test roots must be strings")
          else
            val ext = extensions.collect { case Canon.S(value) => value }
            if ext.exists(!_.startsWith(".")) then Left(s"${path.toString}: each extension must start with '.'")
            else
              Right(
                SourceLanguageDeclaration(
                  Some(ext),
                  exactPaths.collect { case Canon.S(value) => value },
                  properties.collect { case Canon.Sym(value) => value }.toSet,
                  testRoots.collect { case Canon.S(value) => value }.toVector
                )
              )
        case Canon.Node(_, Vector(Canon.L(extensions), Canon.L(exactPaths), Canon.L(properties))) =>
          val badExtensions = extensions.collect { case other if !other.isInstanceOf[Canon.S] => CanonText.write(other) }
          val badExactPaths = exactPaths.collect { case other if !other.isInstanceOf[Canon.S] => CanonText.write(other) }
          val badProperties = properties.collect { case other if !other.isInstanceOf[Canon.Sym] => CanonText.write(other) }
          if badExtensions.nonEmpty then Left(s"${path.toString}: declaration extensions must be strings")
          else if badExactPaths.nonEmpty then Left(s"${path.toString}: declaration exact paths must be strings")
          else if badProperties.nonEmpty then Left(s"${path.toString}: declaration properties must be symbols")
          else
            val ext = extensions.collect { case Canon.S(value) => value }
            if ext.exists(!_.startsWith(".")) then Left(s"${path.toString}: each extension must start with '.'")
            else
              Right(
                SourceLanguageDeclaration(
                  Some(ext),
                  exactPaths.collect { case Canon.S(value) => value },
                  properties.collect { case Canon.Sym(value) => value }.toSet,
                  Vector.empty
                )
              )
        case other =>
          Left(s"${path.toString}: declaration must be either [property ...] or (LanguageDeclaration [extensions] [exactPaths] [properties]), found ${CanonText.write(other)}")
      }

  private def isDeclarableLanguageDirectory(root: Path, name: String): Boolean =
    name match
      case "meta" | "grammar" => true
      case _ =>
        val dir = root.resolve(name)
        Files.isRegularFile(dir.resolve(s"$name.grammar")) && Files.isRegularFile(dir.resolve(s"$name.meta"))

  private def isDeclarableApplicationDirectory(root: Path, name: String): Boolean =
    val dir = root.resolve(name)
    Files.isRegularFile(dir.resolve(s"$name.grammar")) && Files.isRegularFile(dir.resolve(s"$name.meta"))

  private def declarationArtifactsFor(descriptor: SourceLanguageDescriptor): (String, String) =
    descriptor.name match
      case "meta" =>
        ("languages/meta/meta.generated.grammar", "languages/meta/elaborate.meta")
      case "grammar" =>
        ("languages/grammar/grammar.generated.grammar", "languages/grammar/elaborate.meta")
      case _ =>
        (
          s"${descriptor.relativeRoot}/${descriptor.name}/${descriptor.name}.generated.grammar",
          s"${descriptor.relativeRoot}/${descriptor.name}/${descriptor.name}.generated.meta"
        )

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
          Canon.L(properties),
          grammarPath,
          metaPath,
          Canon.L(testRoots),
          Canon.Sym(reader)
        )) =>
      Right(
        DeclaredLanguage(
          declaration,
          name,
          extensions.collect { case Canon.S(value) => value },
          exactPaths.collect { case Canon.S(value) => value },
          properties.collect { case Canon.Sym(value) => value }.toSet,
          optionalPath(grammarPath),
          optionalPath(metaPath),
          testRoots.collect { case Canon.S(value) => value }.toVector,
          reader
        )
      )
    case declaration @ Canon.Node(_, Vector(
          Canon.Sym(name),
          Canon.L(extensions),
          Canon.L(exactPaths),
          Canon.L(properties),
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
          properties.collect { case Canon.Sym(value) => value }.toSet,
          optionalPath(grammarPath),
          optionalPath(metaPath),
          Vector.empty,
          reader
        )
      )
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
          Set.empty,
          optionalPath(grammarPath),
          optionalPath(metaPath),
          Vector.empty,
          reader
        )
      )
    case _ => Left(s"invalid language declaration: ${CanonText.write(value)}")

  private def optionalPath(value: Canon): Option[String] = value match
    case Canon.S(path) => Some(path)
    case _             => None

  private def declaredLanguage(
      name: String,
      extensions: Vector[String],
      exactPaths: Vector[String],
      properties: Set[String],
      grammarPath: Option[Canon],
      metaPath: Option[Canon],
      testRoots: Vector[String],
      reader: String
  ): DeclaredLanguage =
    val declaration = Canon.node(
      "LanguagePackage",
      Canon.Sym(name),
      Canon.L(extensions.map(Canon.S.apply)),
      Canon.L(exactPaths.map(Canon.S.apply)),
      Canon.L(properties.toVector.sorted.map(Canon.Sym.apply)),
      grammarPath.getOrElse(Canon.Sym("none")),
      metaPath.getOrElse(Canon.Sym("none")),
      Canon.L(testRoots.map(Canon.S.apply)),
      Canon.Sym(reader)
    )
    DeclaredLanguage(
      declaration,
      name,
      extensions,
      exactPaths,
      properties,
      grammarPath.flatMap(optionalPath),
      metaPath.flatMap(optionalPath),
      testRoots,
      reader
    )

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
