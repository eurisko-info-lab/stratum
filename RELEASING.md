# Releasing Stratum

Stratum releases are portable JVM distributions attached to GitHub releases.
They require Java 17 or newer and include launchers for Unix and Windows.

## Before the first public release

Choose and add a `LICENSE` file. The repository does not currently grant a
software license, so others may inspect it but do not have general permission
to use, modify or redistribute it.

## Prepare a release

1. Ensure `main` is clean and the `staircase` workflow passes.
2. Build and inspect the archive locally:

   ```bash
   STRATUM_VERSION=0.1.0 sbt -batch test distribution
   unzip -l target/stratum-0.1.0.zip
   ```

3. Update user-facing documentation for any changed commands or requirements.
4. Tag the exact commit and push the tag:

   ```bash
   git tag -a v0.1.0 -m "Stratum 0.1.0"
   git push origin v0.1.0
   ```

The `release` workflow validates the tag, reruns the Scala test suite, builds
`stratum-<version>.zip`, writes its SHA-256 checksum, and creates a GitHub
release with generated notes. If the workflow fails, correct the cause and use
a new version tag; do not move a published tag.

The foundation tags (`foundation/F0` through `foundation/F11`) describe the
semantic staircase and are not product release tags.
