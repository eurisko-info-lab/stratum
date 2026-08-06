#!/usr/bin/env bash
# Clean room reconstruction.
#
# Copies nothing but `digest.txt`, `closure/` and the independent host
# executable into an empty directory, then runs every authoritative check from
# there. No build spec, no source tree, no working copy.
set -euo pipefail

cd "$(dirname "$0")/.."
repository=$PWD

RUST_HOST=host-rust/target/release/stratum-verify
if [ ! -x "$RUST_HOST" ]; then
  echo "the independent host is not built" >&2
  exit 1
fi

room=$(mktemp -d)
trap 'rm -rf "$room"' EXIT

worlds() {
  ls -d foundations/F* | sort -V
  ls -d foundations/S* 2>/dev/null | sort -V
  ls -d applications/*/ 2>/dev/null | sed 's|/$||'
}

cp "$RUST_HOST" "$room/stratum-verify"

for dir in $(worlds); do
  name=$(basename "$dir")
  mkdir -p "$room/$name/closure"
  cp "$dir/digest.txt" "$room/$name/digest.txt"
  cp "$dir"/closure/*.canon "$room/$name/closure/"
done

cd "$room"
for name in $(ls -d */ | sed 's|/$||'); do
  ./stratum-verify attest "$name" > "$name.attestation"
  ./stratum-verify report "$name" > "$name.report"
  echo "  $name $(tail -n 1 "$name.attestation")"
  echo "  $name $(tail -n 1 "$name.report")"
done

# The clean room results must equal the results computed in the repository.
cd "$repository"
for dir in $(worlds); do
  name=$(basename "$dir")
  "$RUST_HOST" attest "$dir" > "$room/$name.repository-attestation"
  "$RUST_HOST" report "$dir" > "$room/$name.repository-report"
  diff -u "$room/$name.attestation" "$room/$name.repository-attestation" >/dev/null || {
    echo "clean room attestation differs for $name" >&2; exit 1; }
  diff -u "$room/$name.report" "$room/$name.repository-report" >/dev/null || {
    echo "clean room derivation differs for $name" >&2; exit 1; }
done

echo "clean room reconstruction ok"
