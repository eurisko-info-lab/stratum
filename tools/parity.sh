#!/usr/bin/env bash
# Compares the two hosts over the adversarial canonical encoding corpus, over
# every foundation's derivation report and over every attestation.
#
# Each host writes its own file, so the comparison never depends on build tool
# logging. The corpus fixes what "canonical" means: both hosts must accept and
# reject exactly the same bytes, and must agree on every verdict, including its
# evidence and its deterministic resource accounting.
set -euo pipefail

cd "$(dirname "$0")/.."

RUST_HOST=host-rust/target/release/stratum-verify

if [ ! -x "$RUST_HOST" ]; then
  echo "the independent host is not built: run 'cargo build --release --manifest-path host-rust/Cargo.toml'" >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Foundations first, then any application deployment built on the platform.
worlds() {
  ls -d foundations/F* | sort -V
  ls -d foundations/S* 2>/dev/null | sort -V
  ls -d applications/*/ 2>/dev/null | sed 's|/$||'
}

compare() {
  if ! diff -u "$work/rust.txt" "$work/scala.txt" >/dev/null; then
    echo "hosts disagree on $1" >&2
    diff -u "$work/rust.txt" "$work/scala.txt" >&2 || true
    exit 1
  fi
}

echo "== host core identity"
"$RUST_HOST" host-manifest --out "$work/rust.txt" >/dev/null
sbt -batch --error "runMain stratum.cli.Stratum host manifest --report $work/scala.txt" >/dev/null
compare "the host core manifest"
echo "  $(tail -n 1 "$work/rust.txt")"

echo "== canonical encoding corpus"
for case in fixtures/canon/adversarial/*.canon; do
  name=$(basename "$case")
  "$RUST_HOST" canon "$case" --out "$work/rust.txt" >/dev/null
  sbt -batch --error "runMain stratum.cli.Stratum canon check $case --out $work/scala.txt" >/dev/null
  compare "$name"
  echo "  $name $(head -n 1 "$work/rust.txt" | cut -c1-40)"
done

echo "== derivation reports"
for dir in $(worlds); do
  name=$(basename "$dir")
  "$RUST_HOST" report "$dir" --out "$work/rust.txt" >/dev/null
  sbt -batch --error "runMain stratum.cli.Stratum foundation report --dir $dir --out $work/scala.txt" >/dev/null
  compare "the derivation report for $name"
  echo "  $name $(tail -n 1 "$work/rust.txt")"
done

echo "== attestations"
for dir in $(worlds); do
  name=$(basename "$dir")
  "$RUST_HOST" attest "$dir" --out "$work/rust.txt" >/dev/null
  sbt -batch --error "runMain stratum.cli.Stratum foundation attest --dir $dir --out $work/scala.txt" >/dev/null
  compare "the attestation for $name"
  echo "  $name $(tail -n 1 "$work/rust.txt")"
done

echo "two host parity ok"
