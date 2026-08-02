package stratum.repo

import stratum.canon.{Canon, CanonText}
import stratum.cli.Cli
import stratum.grammar.GrammarMachine0
import stratum.lsp.Json

import java.nio.file.{Files, Path}

object StudioTranscriptTool:

  def main(args: Array[String]): Unit =
    val root = Path.of(System.getProperty("user.dir")).toAbsolutePath.normalize()
    run(root, args.toVector) match
      case Left(error) =>
        System.err.println(s"error: $error")
        sys.exit(1)
      case Right(text) =>
        println(text)

  def run(root: Path, args: Vector[String]): Either[String, String] =
    val opts = Cli.options(args)
    opts.get("script") match
      case None => Left("usage: --script <file> [--out <file>]")
      case Some(name) =>
        compile(root, root.resolve(name)).map { value =>
          val text = Json.write(value)
          opts.get("out").foreach { out =>
            val target = root.resolve(out)
            Option(target.getParent).foreach(Files.createDirectories(_))
            Files.writeString(target, text + "\n")
          }
          text
        }

  def compile(root: Path, script: Path): Either[String, Json] =
    for
      grammar <- loadGrammar(root)
      source <- readScript(script)
      parsed <- GrammarMachine0.parse(grammar, source).left.map(error => s"${root.relativize(script)}: $error")
      steps <- decodeScript(parsed)
    yield Json.obj(
      "meta" -> Json.obj("world" -> Json.Str(worldOf(steps).getOrElse("applications/sds"))),
      "steps" -> Json.arr(steps.filter(_./("meta").isNull))
    )

  private def loadGrammar(root: Path): Either[String, GrammarMachine0.Grammar] =
    val path = root.resolve("languages/studio/studio.generated.grammar")
    if !Files.isRegularFile(path) then Left(s"missing grammar $path")
    else
      for
        source <- Right(Files.readString(path))
        canon <- CanonText.read(source).left.map(error => s"$path: $error")
        grammar <- GrammarMachine0.load(canon).left.map(error => s"$path: $error")
      yield grammar

  private def readScript(path: Path): Either[String, String] =
    if !Files.isRegularFile(path) then Left(s"no such script: $path")
    else Right(Files.readString(path))

  private def decodeScript(value: Canon): Either[String, Vector[Json]] =
    flattenSteps(value).foldLeft[Either[String, Vector[Json]]](Right(Vector.empty)) { (acc, step) =>
      for
        built <- acc
        next <- decodeStep(step)
      yield built :+ next
    }

  private def flattenSteps(value: Canon): Vector[Canon] = value match
    case Canon.Node("StudioScript", Vector(left, right)) => flattenSteps(left) ++ flattenSteps(right)
    case other                                            => Vector(other)

  private def decodeStep(value: Canon): Either[String, Json] = value match
    case Canon.Node("StudioWorld", Vector(world)) =>
      decodeValue(world).flatMap {
        case Json.Str(name) => Right(Json.obj("meta" -> Json.obj("world" -> Json.Str(name))))
        case _              => Left(s"world must be a string, found ${CanonText.write(world)}")
      }
    case Canon.Node("StudioClearTrace", Vector()) => Right(Json.obj("clearTrace" -> Json.obj()))
    case Canon.Node(tag, Vector(payload)) if tag.startsWith("Studio") =>
      decodeValue(payload).map(value => Json.obj(stepName(tag) -> value))
    case other => Left(s"unknown studio step ${CanonText.write(other)}")

  private def stepName(tag: String): String =
    val stem = tag.stripPrefix("Studio")
    s"${stem.head.toLower}${stem.drop(1)}"

  private def worldOf(steps: Vector[Json]): Option[String] =
    steps.collectFirst { case Json.Obj(fields) =>
      fields.collectFirst { case ("meta", Json.Obj(metaFields)) =>
        metaFields.collectFirst { case ("world", Json.Str(world)) => world }
      }.flatten
    }.flatten

  private def decodeValue(value: Canon): Either[String, Json] = value match
    case Canon.Node("StudioEmptyObject", Vector()) => Right(Json.obj())
    case Canon.Node("StudioObject", Vector(members)) =>
      decodeMembers(members).map(fields => Json.Obj(fields))
    case Canon.Node("StudioEmptyArray", Vector()) => Right(Json.arr(Vector.empty))
    case Canon.Node("StudioArray", Vector(elements)) => decodeElements(elements).map(Json.arr)
    case Canon.Node("StudioString", Vector(Canon.S(raw))) => decodeString(raw).map(Json.Str.apply)
    case Canon.Node("StudioNumber", Vector(Canon.N(number))) => Right(Json.Num(BigDecimal(number)))
    case Canon.Node("StudioTrue", Vector()) => Right(Json.Bool(true))
    case Canon.Node("StudioFalse", Vector()) => Right(Json.Bool(false))
    case Canon.Node("StudioNull", Vector()) => Right(Json.Null)
    case other => Left(s"unsupported studio value ${CanonText.write(other)}")

  private def decodeMembers(value: Canon): Either[String, Vector[(String, Json)]] = value match
    case Canon.Node("StudioMoreMembers", Vector(member, rest)) =>
      for
        head <- decodeMember(member)
        tail <- decodeMembers(rest)
      yield head +: tail
    case Canon.Node("StudioLastMember", Vector(member)) => decodeMember(member).map(Vector(_))
    case other => Left(s"unsupported studio member list ${CanonText.write(other)}")

  private def decodeMember(value: Canon): Either[String, (String, Json)] = value match
    case Canon.Node("StudioMember", Vector(key, actual)) =>
      for
        decodedKey <- decodeKey(key)
        decodedValue <- decodeValue(actual)
      yield decodedKey -> decodedValue
    case other => Left(s"unsupported studio member ${CanonText.write(other)}")

  private def decodeKey(value: Canon): Either[String, String] = value match
    case Canon.Node("StudioKeyString", Vector(Canon.S(raw))) => decodeString(raw)
    case Canon.Node("StudioKeyWord", Vector(Canon.Sym(word))) => Right(word)
    case other => Left(s"unsupported studio key ${CanonText.write(other)}")

  private def decodeElements(value: Canon): Either[String, Vector[Json]] = value match
    case Canon.Node("StudioMoreElements", Vector(head, rest)) =>
      for
        first <- decodeValue(head)
        tail <- decodeElements(rest)
      yield first +: tail
    case Canon.Node("StudioLastElement", Vector(last)) => decodeValue(last).map(Vector(_))
    case other => Left(s"unsupported studio element list ${CanonText.write(other)}")

  private def decodeString(raw: String): Either[String, String] =
    if raw.startsWith("\"") then Json.read(raw).flatMap(_.str.toRight(s"malformed string literal $raw"))
    else if raw.startsWith("'") then decodeSingleQuoted(raw)
    else Left(s"unsupported string literal $raw")

  private def decodeSingleQuoted(raw: String): Either[String, String] =
    if raw.length < 2 || !raw.endsWith("'") then Left(s"malformed string literal $raw")
    else
      val body = raw.substring(1, raw.length - 1)
      val out = new StringBuilder
      var index = 0
      while index < body.length do
        val ch = body.charAt(index)
        if ch != '\\' then
          out.append(ch)
          index += 1
        else if index + 1 >= body.length then return Left(s"unterminated escape in $raw")
        else
          body.charAt(index + 1) match
            case '\\' => out.append('\\')
            case '\'' => out.append('\'')
            case 'n' => out.append('\n')
            case 'r' => out.append('\r')
            case 't' => out.append('\t')
            case other => return Left(s"unknown escape \\$other in $raw")
          index += 2
      Right(out.toString)