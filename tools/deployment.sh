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

platform=$(ls -d foundations/F* foundations/S* 2>/dev/null | sort -V | tail -n 1)
platform_name=$(basename "$platform")

declare -A deployed

deploy() {
  local dir="$1"
  if [ "${deployed[$dir]:-}" = done ]; then
    return
  fi

  local predecessor="$platform"
  if [ -f "$dir/predecessor.txt" ]; then
    predecessor=$(cat "$dir/predecessor.txt")
    deploy "$predecessor"
  fi

  local name
  local predecessor_name
  local golden
  local rebuilt
  local derivation
  name=$(basename "$dir")
  predecessor_name=$(basename "$predecessor")
  golden=$(cat "$dir/digest.txt")

  echo "== $name on $predecessor_name"
  run foundation build --spec "$dir/build.canon" --out "$dir"

  rebuilt=$(cat "$dir/digest.txt")
  if [ "$golden" != "$rebuilt" ]; then
    echo "digest drift in $name: expected $golden, rebuilt $rebuilt" >&2
    exit 1
  fi

  run foundation verify --dir "$dir"
  run foundation reconstruct --dir "$dir"
  run foundation verify-successor --predecessor "$predecessor" --successor "$dir"

  derivation="changes/$predecessor_name-$name/derivation.canon"
  run foundation derive-change \
    --predecessor "$predecessor" \
    --successor "$dir" \
    --out "$derivation"
  # The platform constructs the deployment. No deployment manifest is given.
  run foundation derive-successor --predecessor "$predecessor" --derivation "$derivation" --expect "$dir"

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
  fi

  echo "   $predecessor_name |- $name"
  deployed[$dir]=done
}

for dir in applications/*/; do
  deploy "${dir%/}"
done

echo "deployments ok"
