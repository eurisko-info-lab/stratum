#!/usr/bin/env bash
# Regenerates only declared outputs absent from, or changed since, the parent image.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f generated.sha256 ]]; then
  if [[ -x tools/regenerate.sh ]]; then
    for grammar in \
      languages/grammar1/grammar1 \
      languages/meta1/meta1 \
      languages/grammar/grammar \
      languages/meta/meta; do
      if [[ -f "$grammar.bootstrap.grammar" ]]; then
        cp "$grammar.bootstrap.grammar" "$grammar.generated.grammar"
      fi
    done
    ./tools/regenerate.sh
  fi
  exit 0
fi

run() {
  sbt -batch --error "runMain stratum.cli.Stratum $*"
}

matches() {
  local expected=$1 target=$2
  [[ -f "$target" ]] && [[ "$(sha256sum "$target" | cut -d' ' -f1)" == "$expected" ]]
}

META="--program languages/meta/prelude.meta --program languages/meta/elaborate.meta --program languages/grammar/elaborate.meta"

cat generated.sha256 |
while read -r expected target; do
  [[ "$target" == *.generated.grammar ]] || continue
  if matches "$expected" "$target"; then
    continue
  fi
  case "$target" in
    languages/grammar/grammar.generated.grammar)
      cp languages/grammar/grammar.bootstrap.grammar "$target"
      ;;
    languages/meta/meta.generated.grammar)
      cp languages/meta/meta.bootstrap.grammar "$target"
      ;;
    *)
      source=${target%.generated.grammar}.grammar
      echo "regenerating $target"
      run meta elaborate --grammar languages/grammar/grammar.generated.grammar $META \
        --judgment ElaborateGrammarSource \
        --source "$source" \
        --out "$target"
      ;;
  esac
done

cat generated.sha256 |
while read -r expected target; do
  [[ "$target" == *.generated.meta ]] || continue
  if matches "$expected" "$target"; then
    continue
  fi
  source=${target%.generated.meta}.meta
  echo "regenerating $target"
  run meta elaborate --grammar languages/meta/meta.generated.grammar $META \
    --source "$source" \
    --out "$target"
done

expected=$(awk '$2 == "host/core.canon" { print $1 }' generated.sha256)
if [[ -n "$expected" ]] && ! matches "$expected" host/core.canon; then
  echo "regenerating host/core.canon"
  run host manifest --out host/core.canon >/dev/null
fi

sha256sum -c --quiet generated.sha256
echo "generated image inputs ok"
