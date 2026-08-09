#!/usr/bin/env bash
# Regenerates every disposable language artifact and checks its exact bytes.
set -euo pipefail

cd "$(dirname "$0")/.."

./tools/regenerate.sh

actual=$(mktemp)
trap 'rm -f "$actual"' EXIT

find applications features foundations languages -type f \
  \( -name '*.generated.meta' -o -name '*.generated.grammar' \) \
  -print | LC_ALL=C sort | while IFS= read -r file; do
    sha256sum "$file"
  done > "$actual"
sha256sum host/core.canon >> "$actual"

diff -u generated.sha256 "$actual"
echo "generated artifacts ok"
