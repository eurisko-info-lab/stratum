package stratum

import munit.FunSuite
import stratum.canon.{Canon, CanonText}
import stratum.cli.Cli
import stratum.lsp.{Json, Server, Service}
import stratum.meta.MetaMachine0

import java.nio.file.{Files, Paths}

final class VirtualAppSuite extends FunSuite:

  test("a plain world publishes a document navigator") {
    val app = Service.plainVirtualApp("repository")

    assertEquals(app.name, "repository")
    assertEquals(app.navigator.map(_.name), Some("documents"))
    assertEquals(app.navigator.map(_.title), Some("Documents"))
    assertEquals(app.navigator.map(_.reveal), Some(true))
  }

  test("a virtual app authored in Strata crosses as a canonical map") {
    val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
    val sourcePaths = Vector(
      "strata/lib/prelude.strata",
      "strata/system/native.strata",
      "strata/system/octets.strata",
      "strata/system/artifact.strata",
      "features/strata/virtual-app.strata"
    )
    val sources = sourcePaths.map { path =>
      s"(q ${CanonText.write(Canon.S(Files.readString(root.resolve(path))))})"
    }.foldRight("(q [])") { (source, rest) => s"(prim cons $source $rest)" }
    val goal = CanonText.read(
      s"(call WriteCanonValue (call RunStrataWith (grammar strata) $sources (q \"strata.virtual-app\") (q plainVirtualApp) (q [\"repository\"])))"
    ).toOption.get
    val foundation = Cli.loadFoundation(root, root.resolve("foundations/S18")).toOption.get
    val verdict = Cli.deriveIn(foundation, root, goal, foundation.budget)
    val result = MetaMachine0.result(verdict).getOrElse(fail(CanonText.write(verdict)))
    val app = Service.readVirtualApp(result)

    assertEquals(app.name, "repository")
    assertEquals(app.navigator.map(_.title), Some("Documents"))
    assertEquals(app.navigator.map(_.placement), Some("left"))
  }

  test("a virtual app is decoded from a keyed map") {
    val body = CanonText.read("""{
      name "sample"
      placements { left "sidebar" }
      navigator { name "records" title "Records" placement left reveal #t }
      languages [{ name sample label "Sample" extensions [".sample"] }]
      commands [{ name open title "Open" }]
    }""").toOption.get
    val app = Service.readVirtualApp(body)

    assertEquals(app.name, "sample")
    assertEquals(app.placements, Map("left" -> "sidebar"))
    assertEquals(app.navigator.map(_.name), Some("records"))
    assertEquals(app.bindings.map(_.extensions), Vector(Vector(".sample")))
    assertEquals(app.actions.map(_.name), Vector("open"))
  }

  test("the virtual-app response combines app identity and profile behavior") {
    val appBody = CanonText.read("""{
      name "sample"
      editor { display "Sample App" }
      navigator { name "records" title "Records" placement left reveal #t }
      languages [{ name sample label "Sample" extensions [".sample"] comment ";" }]
      commands [{ name open title "Open" }]
    }""").toOption.get
    val layout = CanonText.read("""{
      name "sample-browser"
      workflow [edit review]
      navigation { model nested }
      views [{ name records placement left primitive tree }]
    }""").toOption
    val json = Server.virtualAppJson(Service.readVirtualApp(appBody), layout)

    assertEquals(json / "name", Json.Str("sample"))
    assertEquals(json / "title", Json.Str("Sample App"))
    assertEquals(json / "layout", Json.Str("sample-browser"))
    assertEquals((json / "navigator") / "placement", Json.Str("left"))
    assertEquals((json / "languages").items.head / "id", Json.Str("sample"))
    assertEquals((json / "commands").items.head / "name", Json.Str("open"))
  }