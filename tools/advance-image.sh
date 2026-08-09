#!/usr/bin/env bash
# Advances exactly one Git commit from its immutable parent materialization image.
set -euo pipefail

cd "$(dirname "$0")/.."

if (($# < 1 || $# > 2)); then
  echo "usage: tools/advance-image.sh <child-image> [parent-image]" >&2
  exit 2
fi

child_image=$1
parent_image=${2:-}

if git rev-parse HEAD^ >/dev/null 2>&1; then
  [[ -n "$parent_image" ]] || {
    echo "a non-root commit requires its parent image" >&2
    exit 1
  }
  ./tools/image.sh restore "$parent_image" "$(git rev-parse HEAD^)"
else
  [[ -z "$parent_image" ]] || {
    echo "the root commit cannot have a parent image" >&2
    exit 1
  }
  ./tools/image.sh clear
fi

./tools/regenerate-image.sh

if git rev-parse HEAD^ >/dev/null 2>&1; then
  mapfile -t worlds < <(
    git diff --name-only HEAD^ HEAD |
      grep -E '^(foundations|applications)/[^/]+/[^/]+$' |
      sed 's|/[^/]*$||' |
      sort -Vu || true
  )
else
  mapfile -t worlds < <(
    find foundations applications -mindepth 2 -maxdepth 2 -name digest.txt -printf '%h\n' 2>/dev/null |
      sort -Vu
  )
fi

if ((${#worlds[@]} > 0)); then
  ordered_worlds=()
  pending_worlds=("${worlds[@]}")
  while ((${#pending_worlds[@]} > 0)); do
    next_worlds=()
    progressed=false
    for world in "${pending_worlds[@]}"; do
      predecessor=$(sed -n 's/.*(predecessor (dir "\([^"]*\)")).*/\1/p' "$world/build.canon" | head -n 1)
      blocked=false
      for pending in "${pending_worlds[@]}"; do
        if [[ "$predecessor" == "$pending" ]]; then
          blocked=true
          break
        fi
      done
      if [[ "$blocked" == true ]]; then
        next_worlds+=("$world")
      else
        ordered_worlds+=("$world")
        progressed=true
      fi
    done
    [[ "$progressed" == true ]] || {
      echo "changed worlds contain a predecessor cycle: ${pending_worlds[*]}" >&2
      exit 1
    }
    pending_worlds=("${next_worlds[@]}")
  done
  worlds=("${ordered_worlds[@]}")

  classpath=$(sbt -batch --error 'export Runtime/fullClasspath' | grep -E 'classes|\.jar' | tail -n 1) || {
    echo "sbt did not report a runtime classpath" >&2
    exit 1
  }

  run() {
    java -Xss256m -cp "$classpath" stratum.cli.Stratum "$@"
  }

  for world in "${worlds[@]}"; do
    expected=$(cat "$world/digest.txt")
    echo "advancing $world"
    run foundation build --spec "$world/build.canon" --out "$world"
    actual=$(cat "$world/digest.txt")
    [[ "$actual" == "$expected" ]] || {
      echo "digest drift in $world: expected $expected, rebuilt $actual" >&2
      exit 1
    }
    run foundation verify --dir "$world"
    run foundation reconstruct --dir "$world"

    predecessor=$(sed -n 's/.*(predecessor (dir "\([^"]*\)")).*/\1/p' "$world/build.canon" | head -n 1)
    if [[ -n "$predecessor" ]]; then
      run foundation verify-successor --predecessor "$predecessor" --successor "$world"
      if grep -Rqs 'case Some("derive-change")' host-scala/cli; then
        change=$(sed -n 's/.*(change (file "\([^"]*\)")).*/\1/p' "$world/build.canon" | head -n 1)
        [[ -n "$change" ]] || { echo "$world has no canonical change" >&2; exit 1; }
        derivation="$(dirname "$change")/derivation.canon"
        run foundation derive-change \
          --predecessor "$predecessor" \
          --successor "$world" \
          --out "$derivation"
        run foundation derive-successor \
          --predecessor "$predecessor" \
          --derivation "$derivation" \
          --expect "$world"
      fi
    fi
  done
fi

./tools/image.sh build "$child_image" "$parent_image"
echo "image advanced to $(git rev-parse --short HEAD)"