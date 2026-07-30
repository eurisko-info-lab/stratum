import java.nio.file.Files

ThisBuild / scalaVersion := "3.6.3"
ThisBuild / version := sys.env.getOrElse("STRATUM_VERSION", "0.1.0-SNAPSHOT")
ThisBuild / organization := "info.eurisko"
ThisBuild / organizationName := "Eurisko Information Lab"
ThisBuild / description := "A self-validating semantic staircase and generic language platform"
ThisBuild / homepage := Some(url("https://github.com/eurisko-info-lab/stratum"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/eurisko-info-lab/stratum"),
    "scm:git:https://github.com/eurisko-info-lab/stratum.git"
  )
)

lazy val distribution = taskKey[File]("Build a portable Stratum command-line distribution")

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
    Compile / mainClass := Some("stratum.cli.Stratum"),
    distribution := {
      val log = streams.value.log
      val release = version.value
      val base = target.value / s"stratum-$release"
      val archive = target.value / s"stratum-$release.zip"
      val appJar = (Compile / packageBin).value
      val jars = appJar +: (Runtime / dependencyClasspath).value.map(_.data).filter(_.isFile)
      val lib = base / "lib"
      val bin = base / "bin"

      IO.delete(base)
      IO.createDirectory(lib)
      IO.createDirectory(bin)
      jars.foreach(jar => IO.copyFile(jar, lib / jar.getName, preserveLastModified = true))

      val unixLauncher = bin / "stratum"
      IO.write(
        unixLauncher,
        """#!/usr/bin/env sh
          |set -eu
          |STRATUM_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
          |exec java -Xss256m -cp "$STRATUM_DIR/lib/*" stratum.cli.Stratum "$@"
          |""".stripMargin
      )
      Files.setPosixFilePermissions(
        unixLauncher.toPath,
        java.util.Set.of(
          java.nio.file.attribute.PosixFilePermission.OWNER_READ,
          java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
          java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
          java.nio.file.attribute.PosixFilePermission.GROUP_READ,
          java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
          java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
          java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
        )
      )
      IO.write(
        bin / "stratum.bat",
        """@echo off
          |java -Xss256m -cp "%~dp0..\lib\*" stratum.cli.Stratum %*
          |""".stripMargin
      )
      IO.write(base / "README.txt", s"Stratum $release\n\nRequires Java 17 or newer. Run `sh bin/stratum` (Unix) or `bin\\stratum.bat` (Windows).\n")

      IO.delete(archive)
      IO.zip(Path.allSubpaths(base), archive, Some(0L))
      log.info(s"Built ${archive.getAbsolutePath}")
      archive
    }
  )
