ThisBuild / scalaVersion := "3.6.3"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "info.eurisko"

lazy val root = (project in file("."))
  .settings(
    name := "stratum",
    Compile / scalaSource := baseDirectory.value / "host-scala",
    Test / scalaSource := baseDirectory.value / "test-scala",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test,
    scalacOptions ++= Seq("-deprecation", "-feature"),
    run / fork := true,
    Test / fork := true,
    javaOptions ++= Seq("-Xss256m"),
    Compile / mainClass := Some("stratum.cli.Stratum")
  )
