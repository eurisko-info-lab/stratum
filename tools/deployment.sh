#!/usr/bin/env bash
# Builds and gates every application deployment on the finished platform.
#
# A deployment is not a step of the bootstrap staircase. It is an application
# branched from the last foundation: it reuses the platform's Meta, Grammar,
# change calculus, repository, toolchain, governance, ledger, agreement,
# retention and Studio runtime, and adds only its own language, profiles and
# documents.
set -euo pipefail

cd "$(dirname "$0")/.."

run() {
  sbt -batch --error "runMain stratum.cli.Stratum $*"
}

platform=$(ls -d foundations/F* | sort -V | tail -n 1)
platform_name=$(basename "$platform")

for dir in $(ls -d applications/*/ 2>/dev/null | sed 's|/$||'); do
  name=$(basename "$dir")
  golden=$(cat "$dir/digest.txt")

  echo "== $name on $platform_name"
  run foundation build --spec "$dir/build.canon" --out "$dir"

  rebuilt=$(cat "$dir/digest.txt")
  if [ "$golden" != "$rebuilt" ]; then
    echo "digest drift in $name: committed $golden, rebuilt $rebuilt" >&2
    exit 1
  fi

  run foundation verify --dir "$dir"
  run foundation reconstruct --dir "$dir"
  run foundation verify-successor --predecessor "$platform" --successor "$dir"

  derivation="changes/$platform_name-$name/derivation.canon"
  if [ ! -f "$derivation" ]; then
    echo "no canonical derivation for $platform_name -> $name" >&2
    exit 1
  fi
  # The platform constructs the deployment. No deployment manifest is given.
  run foundation derive-successor --predecessor "$platform" --derivation "$derivation" --expect "$dir"

  # A world that publishes a service must be able to serve it.
  if [ -f "$dir/service.canon" ]; then
    # The transcripts drive the server in process, so the launcher is the one
    # part of the editor they cannot reach. It shipped broken once; now a real
    # process has to answer a real request before the gate passes.
    request='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
    if ! printf 'Content-Length: %d\r\n\r\n%s' "${#request}" "$request" |
      ./tools/lsp.sh --world "$dir" | grep -q '"capabilities"'; then
      echo "the language server launcher does not answer initialize" >&2
      exit 1
    fi
    echo "   $name language server answers"

    # The client is checked out here, so it is regenerated from the world and
    # the drift gate catches a client left behind by the languages it edits.
    if [ -d studio/vscode ]; then
      run lsp package --world "$dir" --out studio/vscode
      echo "   $name editor regenerated"
    fi
  fi

  echo "   $platform_name |- $name"
done

echo "deployments ok"
