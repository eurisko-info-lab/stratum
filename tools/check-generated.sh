#!/usr/bin/env bash
# Checks every declared generated artifact against its committed digest.
set -euo pipefail

cd "$(dirname "$0")/.."

sha256sum -c --quiet generated.sha256
echo "generated artifacts ok"
