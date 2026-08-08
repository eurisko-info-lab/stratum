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

workers=${STRATUM_STAIRCASE_WORKERS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mapfile -t all_foundations < <(ls -d foundations/F* | sort -V; ls -d foundations/S* 2>/dev/null | sort -V)
foundations=("${all_foundations[@]}")
if [[ -n "${STRATUM_STAIRCASE_SHARD:-}" ]]; then
  IFS=/ read -r shard_index shard_count <<< "$STRATUM_STAIRCASE_SHARD"
  if [[ ! "$shard_index" =~ ^[0-9]+$ || ! "$shard_count" =~ ^[1-9][0-9]*$ || "$shard_index" -ge "$shard_count" ]]; then
    echo "STRATUM_STAIRCASE_SHARD must be INDEX/COUNT with INDEX < COUNT" >&2
    exit 1
  fi
  foundations=()
  for ((i = shard_index; i < ${#all_foundations[@]}; i += shard_count)); do
    foundations+=("${all_foundations[i]}")
  done
fi

build_world() {
  local dir=$1 name golden rebuilt
  name=$(basename "$dir")
  golden=$(cat "$dir/digest.txt")

  run foundation build --spec "$dir/build.canon" --out "$dir" > "$work/$name.build"

  rebuilt=$(cat "$dir/digest.txt")
  if [[ "$golden" != "$rebuilt" ]]; then
    echo "digest drift in $name: committed $golden, rebuilt $rebuilt" >&2
    return 1
  fi
}

verify_world() {
  set -euo pipefail
  local dir=$1 name
  name=$(basename "$dir")
  {
    run foundation verify --dir "$dir"
    run foundation reconstruct --dir "$dir"
  } > "$work/$name.verify"
}

verify_transition() {
  set -euo pipefail
  local pair=$1 previous dir previous_name name derivation
  IFS='|' read -r previous dir <<< "$pair"
  previous_name=$(basename "$previous")
  name=$(basename "$dir")
  derivation="changes/$previous_name-$name/derivation.canon"

  if [[ ! -f "$derivation" ]]; then
      echo "no canonical derivation for $previous_name -> $name" >&2
      return 1
  fi

  {
    run foundation verify-successor --predecessor "$previous" --successor "$dir"
    run foundation derive-successor \
      --predecessor "$previous" \
      --derivation "$derivation" \
      --expect "$dir"
  } > "$work/$name.transition"
}

export -f run build_world verify_world verify_transition
export classpath work

for dir in "${foundations[@]}"; do
  build_world "$dir"
done
printf '%s\n' "${foundations[@]}" | xargs -r -n 1 -P "$workers" bash -c 'verify_world "$1"' _

transitions=()
for dir in "${foundations[@]}"; do
  for ((i = 1; i < ${#all_foundations[@]}; i++)); do
    if [[ "${all_foundations[i]}" == "$dir" ]]; then
      transitions+=("${all_foundations[i - 1]}|$dir")
      break
    fi
  done
done
if ((${#transitions[@]} > 0)); then
  printf '%s\n' "${transitions[@]}" | xargs -r -n 1 -P "$workers" bash -c 'verify_transition "$1"' _
fi

for dir in "${foundations[@]}"; do
  name=$(basename "$dir")
  echo "== $name"
  cat "$work/$name.build" "$work/$name.verify"
  for pair in "${transitions[@]}"; do
    IFS='|' read -r previous current <<< "$pair"
    if [[ "$current" != "$dir" ]]; then
      continue
    fi
    cat "$work/$name.transition"
    echo "   $(basename "$previous") |- $name"
  done
done

echo "staircase ok"
