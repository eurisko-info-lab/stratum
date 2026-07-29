#!/usr/bin/env bash
# Rebuilds every foundation in order and verifies each successor with its
# predecessor.
#
# The workflow fails if any foundation drifts from its committed golden digest,
# if a closure is incomplete, or if a predecessor refuses its successor.
set -euo pipefail

cd "$(dirname "$0")/.."

run() {
  sbt -batch --error "runMain stratum.cli.Stratum $*"
}

previous=""
for dir in $(ls -d foundations/F* | sort -V); do
  name=$(basename "$dir")
  golden=$(cat "$dir/digest.txt")

  echo "== $name"
  run foundation build --spec "$dir/build.canon" --out "$dir"

  rebuilt=$(cat "$dir/digest.txt")
  if [ "$golden" != "$rebuilt" ]; then
    echo "digest drift in $name: committed $golden, rebuilt $rebuilt" >&2
    exit 1
  fi

  run foundation verify --dir "$dir"
  run foundation reconstruct --dir "$dir"

  if [ -n "$previous" ]; then
    run foundation verify-successor --predecessor "$previous" --successor "$dir"
  fi
  previous="$dir"
done

echo "staircase ok"
