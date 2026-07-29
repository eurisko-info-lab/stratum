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

META="--program languages/meta/prelude.meta --program languages/meta1/elaborate.meta --program languages/grammar1/elaborate.meta"

echo "regenerating grammars"
run meta elaborate --grammar languages/grammar1/grammar1.generated.grammar $META \
  --judgment ElaborateGrammarSource \
  --source languages/lambda/lambda.grammar \
  --out languages/lambda/lambda.generated.grammar

echo "regenerating meta programs"
run meta elaborate --grammar languages/meta1/meta1.generated.grammar $META \
  --source languages/lambda/lambda.meta \
  --out languages/lambda/lambda.generated.meta

for source in $(find features -name '*.meta' ! -name '*.generated.meta' | sort); do
  echo "  $source"
  run meta elaborate --grammar languages/meta1/meta1.generated.grammar $META \
    --source "$source" \
    --out "${source%.meta}.generated.meta"
done

echo "done"
