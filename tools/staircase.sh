#!/usr/bin/env bash
# Rebuilds every foundation in order, verifies each successor with its
# predecessor, and requires the predecessor to *derive* the successor.
#
# The workflow fails if any foundation drifts from its committed golden digest,
# if a closure is incomplete, if a predecessor refuses its successor, or if a
# predecessor cannot construct the successor from the canonical change alone.
set -euo pipefail

cd "$(dirname "$0")/.."

# The loop below issues dozens of Stratum invocations. Compiling and asking
# sbt for the runtime classpath once, then invoking `java` directly for every
# command, avoids paying sbt's ~3s startup cost on each of them.
#
# The classpath is the last line that looks like one. On a cold machine the
# sbt launcher first says what it is downloading, on stdout and before any log
# level applies, and capturing that as part of the classpath produced a `java
# -cp` that could not find the Scala runtime -- which is what a fresh checkout
# does, so it failed everywhere except where it had been run before.
echo "== compiling"
classpath=$(sbt -batch --error 'export Runtime/fullClasspath' | grep -E 'classes|\.jar' | tail -n 1) || {
  echo "sbt did not report a runtime classpath" >&2
  exit 1
}

run() {
  java -Xss256m -cp "$classpath" stratum.cli.Stratum "$@"
}

previous=""
previous_name=""
# The first floor in order, then the second floor it constructs.
for dir in $(ls -d foundations/F* | sort -V; ls -d foundations/S* 2>/dev/null | sort -V); do
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

    derivation="changes/$previous_name-$name/derivation.canon"
    if [ ! -f "$derivation" ]; then
      echo "no canonical derivation for $previous_name -> $name" >&2
      exit 1
    fi
    # The predecessor constructs the successor. No successor manifest is given.
    run foundation derive-successor \
      --predecessor "$previous" \
      --derivation "$derivation" \
      --expect "$dir"
    echo "   $previous_name |- $name"
  fi
  previous="$dir"
  previous_name="$name"
done

echo "staircase ok"
