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
    Compile / unmanagedSourceDirectories += baseDirectory.value / "repo-scala",
    Test / scalaSource := baseDirectory.value / "test-scala",
    Test / unmanagedSourceDirectories += baseDirectory.value / "repo-scala",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test,
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    run / fork := true,
    Test / fork := true,
    // The journal that makes a run undoable is one per process, and a run may
    // not nest, so suites that perform runs must not overlap.
    Test / parallelExecution := false,
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

lazy val repoTool = (project in file("stratum-repo-tool"))
  .dependsOn(root)
  .settings(
    name := "stratum-repo",
    Compile / scalaSource := baseDirectory.value.getParentFile / "repo-scala",
    Test / scalaSource := baseDirectory.value.getParentFile / "test-repo-scala",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test,
    run / fork := true,
    Test / fork := true,
    // A token-dense source file elaborates into a fold tree one level deep
    // per token; printing or comparing that tree recurses one stack frame
    // per level, same as the root project's own tests. javaOptions only
    // affects forked processes, so both run and Test must fork to use it.
    javaOptions ++= Seq("-Xss256m"),
    // Forking otherwise moves the working directory to this subproject's own
    // folder; the tool resolves paths (including the language declarations)
    // relative to the repository root, exactly as it did unforked.
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    Compile / mainClass := Some("stratum.repo.StratumRepo")
  )
