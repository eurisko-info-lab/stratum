#!/usr/bin/env bash
# Regenerates every artifact that is elaborated from a surface source.
#
# Generated artifacts are committed so that a foundation closure is complete
# without running the generator. Each generated file is also re-checked from
# inside the foundation that uses it.
set -euo pipefail

cd "$(dirname "$0")/.."

run() {
  sbt -batch --error "runMain stratum.cli.Stratum $*"
}

META="--program languages/meta/prelude.meta --program languages/meta/elaborate.meta --program languages/grammar/elaborate.meta"
LANGUAGE_PACKAGES="rust scala shell transcript text strata"

echo "regenerating grammars"
run meta elaborate --grammar languages/grammar/grammar.generated.grammar $META \
  --judgment ElaborateGrammarSource \
  --source languages/grammar/grammar.grammar \
  --out languages/grammar/grammar.generated.grammar
run meta elaborate --grammar languages/grammar/grammar.generated.grammar $META \
  --judgment ElaborateGrammarSource \
  --source languages/meta/meta.grammar \
  --out languages/meta/meta.generated.grammar
run meta elaborate --grammar languages/grammar/grammar.generated.grammar $META \
  --judgment ElaborateGrammarSource \
  --source languages/lambda/lambda.grammar \
  --out languages/lambda/lambda.generated.grammar

for language in $LANGUAGE_PACKAGES; do
  source="languages/$language/$language.grammar"
  echo "  $source"
  run meta elaborate --grammar languages/grammar/grammar.generated.grammar $META \
    --judgment ElaborateGrammarSource \
    --source "$source" \
    --out "${source%.grammar}.generated.grammar"
done

if [ -d applications ]; then
  for source in $(find applications -name '*.grammar' ! -name '*.generated.grammar' | sort); do
    echo "  $source"
    run meta elaborate --grammar languages/grammar/grammar.generated.grammar $META \
      --judgment ElaborateGrammarSource \
      --source "$source" \
      --out "${source%.grammar}.generated.grammar"
  done
fi

echo "regenerating meta programs"
run meta elaborate --grammar languages/meta/meta.generated.grammar $META \
  --source languages/lambda/lambda.meta \
  --out languages/lambda/lambda.generated.meta

for source in $(find features applications languages/service languages/pdf -name '*.meta' ! -name '*.generated.meta' 2>/dev/null | sort); do
  echo "  $source"
  run meta elaborate --grammar languages/meta/meta.generated.grammar $META \
    --source "$source" \
    --out "${source%.meta}.generated.meta"
done

for language in $LANGUAGE_PACKAGES; do
  source="languages/$language/$language.meta"
  echo "  $source"
  run meta elaborate --grammar languages/meta/meta.generated.grammar $META \
    --source "$source" \
    --out "${source%.meta}.generated.meta"
done

run host manifest --out host/core.canon >/dev/null

echo "done"
