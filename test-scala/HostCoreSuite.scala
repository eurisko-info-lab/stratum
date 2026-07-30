package stratum

import stratum.artifact.Artifact
import stratum.canon.{Canon, CanonText, Digest}
import stratum.cli.{Cli, HostCore}

import java.nio.file.{Files, Path, Paths}

/**
 * The host core freeze gate.
 *
 * `NativeBoundarySuite` keeps the host feature agnostic. This suite pins its
 * *identity*: the canonical tags, the Meta0 forms and primitive set, the
 * Grammar0 forms, the evidence shape and the verdict forms. Every foundation
 * from F1 onward references exactly this identity, so widening the fixed
 * calculus is a deliberate, reviewed act rather than an accident.
 */
class HostCoreSuite extends munit.FunSuite:

  private val root: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val committedPath: Path = root.resolve("host/core.canon")

  private def committedManifest: Canon =
    CanonText.read(Files.readString(committedPath)) match
      case Right(value) => value
      case Left(message) => fail(s"host/core.canon is not canonical text: $message")

  private def committedReference: Digest =
    Artifact("data", committedManifest).digest

  private def foundations: Vector[String] =
    Vector("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11")

  test("the committed host core manifest matches the live host") {
    assertEquals(
      CanonText.write(committedManifest),
      CanonText.write(HostCore.manifest),
      "regenerate with `stratum host manifest --out host/core.canon` and review the change"
    )
  }

  test("the primitive set is sorted and free of duplicates") {
    assertEquals(HostCore.primitives, HostCore.primitives.sorted)
    assertEquals(HostCore.primitives.distinct.length, HostCore.primitives.length)
  }

  test("every foundation from F1 onward references the same host core") {
    val expected = committedReference
    foundations.foreach { name =>
      Cli.loadFoundation(root, root.resolve(s"foundations/$name")) match
        case Left(message) => fail(s"$name: $message")
        case Right(foundation) =>
          val declared = foundation.application match
            case Canon.Node(_, args) =>
              args.collectFirst { case Canon.Node("host", Vector(Canon.R(reference))) => reference }
            case _ => None
          assertEquals(
            declared,
            Some(expected),
            s"$name does not reference the frozen host core ${expected.hex}"
          )
    }
  }

  test("F0 predates the freeze") {
    Cli.loadFoundation(root, root.resolve("foundations/F0")) match
      case Left(message) => fail(s"F0: $message")
      case Right(foundation) =>
        val declared = foundation.application match
          case Canon.Node(_, args) => args.collectFirst { case Canon.Node("host", _) => true }
          case _                   => None
        assertEquals(declared, None, "the freeze is published by F1, not F0")
  }
