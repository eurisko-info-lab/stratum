#!/usr/bin/env bash
# Compares the native rust-vm crate against the Meta-level RustVM reference
# interpreter (features/rustvm/rustvm.meta, run through the Scala host) over
# every fixture in fixtures/rustvm/*.rs.
#
# This is deliberately a separate script from tools/parity.sh, not an
# extension of it: parity.sh is hardwired to exactly two binaries (host-rust,
# host-scala) with a fixed command surface, and has no generic N-way
# abstraction to plug a third, non-Meta0 implementation into. rust-vm sits
# entirely outside MetaMachine0, so its comparison is a plain result diff
# over one compiled artifact per fixture, not a canonical-verdict-digest
# parity claim the way host-rust/host-scala's agreement is.
set -euo pipefail

cd "$(dirname "$0")/.."

RUST_VM=rust-vm/target/release/stratum-rust-vm

if [ ! -x "$RUST_VM" ]; then
  echo "rust-vm is not built: run 'cargo build --release --manifest-path rust-vm/Cargo.toml'" >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

classpath=$(sbt -batch --error 'export Runtime/fullClasspath' | grep -E 'classes|\.jar' | tail -n 1) || {
  echo "sbt did not report a runtime classpath" >&2
  exit 1
}

scala_host() {
  java -Xss256m -cp "$classpath" stratum.cli.Stratum "$@"
}

META="--program languages/meta/prelude.meta --program languages/meta/elaborate.meta --program languages/grammar/elaborate.meta --program languages/rust/rust.generated.meta --program features/rustvm/rustvm.generated.meta --program features/rustvm/compile.generated.meta"

fail=0

for fixture in fixtures/rustvm/*.rs; do
  name=$(basename "$fixture" .rs)
  echo "checking $name"

  bytecode="$work/$name.bytecode.canon"
  scala_host meta elaborate --grammar languages/rust/rust.generated.grammar $META \
    --judgment CompileRustSource --source "$fixture" --out "$bytecode" >/dev/null

  meta_result="$work/$name.meta-result.canon"
  scala_host meta elaborate --grammar languages/rust/rust.generated.grammar $META \
    --judgment RunSource --source "$fixture" --out "$meta_result" >/dev/null

  # --out writes just the derived value (MetaMachine0.result, already
  # unwrapped from its verdict), preceded by one "; Generated from ..."
  # comment line -- not the full verdict form.
  meta_value=$(tail -n +2 "$meta_result")
  native_value=$("$RUST_VM" run "$bytecode")

  if [ "$meta_value" != "$native_value" ]; then
    echo "  MISMATCH: meta=$meta_value native=$native_value" >&2
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "rustvm-check FAILED" >&2
  exit 1
fi

echo "native rust-vm agrees with the Meta reference on every fixture"
