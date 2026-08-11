#!/usr/bin/env bash
# Regenerates every artifact that is elaborated from a surface source.
#
# Generated artifacts are disposable working-tree outputs. The two bootstrap
# grammars are tracked inputs; everything else is elaborated from surface source.
set -euo pipefail

cd "$(dirname "$0")/.."

run() {
  sbt -batch --error "runMain stratum.cli.Stratum $*"
}

META="--program languages/meta/prelude.meta --program languages/meta/elaborate.meta --program languages/grammar/elaborate.meta"

cp languages/grammar/grammar.bootstrap.grammar languages/grammar/grammar.generated.grammar
cp languages/meta/meta.bootstrap.grammar languages/meta/meta.generated.grammar

echo "regenerating grammars"
awk '$2 ~ /\.generated\.grammar$/ { print $2 }' generated.sha256 |
while IFS= read -r target; do
  case "$target" in
    languages/grammar/grammar.generated.grammar | languages/meta/meta.generated.grammar)
      continue
      ;;
  esac
  source=${target%.generated.grammar}.grammar
  echo "  $source"
  run meta elaborate --grammar languages/grammar/grammar.generated.grammar $META \
    --judgment ElaborateGrammarSource \
    --source "$source" \
    --out "$target"
done

echo "regenerating meta programs"
awk '$2 ~ /\.generated\.meta$/ { print $2 }' generated.sha256 |
while IFS= read -r target; do
  source=${target%.generated.meta}.meta
  echo "  $source"
  run meta elaborate --grammar languages/meta/meta.generated.grammar $META \
    --source "$source" \
    --out "$target"
done

run host manifest --out host/core.canon >/dev/null

run host manifest --out host/core.canon >/dev/null

echo "done"
