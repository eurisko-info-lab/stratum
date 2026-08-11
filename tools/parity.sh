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

classpath=$(sbt -batch --error 'export Runtime/fullClasspath' | grep -E 'classes|\.jar' | tail -n 1) || {
  echo "sbt did not report a runtime classpath" >&2
  exit 1
}

scala_host() {
  java -Xss256m -cp "$classpath" stratum.cli.Stratum "$@"
}

workers=${STRATUM_PARITY_WORKERS:-2}

# Foundations first, then any application deployment built on the platform.
worlds() {
  ls -d foundations/F* | sort -V
  ls -d foundations/S* 2>/dev/null | sort -V
  ls -d foundations/R* 2>/dev/null | sort -V
  ls -d applications/*/ 2>/dev/null | sed 's|/$||'
}

compare() {
  local label=$1 rust=$2 scala=$3
  if ! diff -u "$rust" "$scala" >/dev/null; then
    echo "hosts disagree on $label" >&2
    diff -u "$rust" "$scala" >&2 || true
    return 1
  fi
}

echo "== host core identity"
"$RUST_HOST" host-manifest --out "$work/rust.txt" >/dev/null
scala_host host manifest --report "$work/scala.txt" >/dev/null
compare "the host core manifest" "$work/rust.txt" "$work/scala.txt"
echo "  $(tail -n 1 "$work/rust.txt")"

echo "== canonical encoding corpus"
for case in fixtures/canon/adversarial/*.canon; do
  name=$(basename "$case")
  "$RUST_HOST" canon "$case" --out "$work/rust.txt" >/dev/null
  scala_host canon check "$case" --out "$work/scala.txt" >/dev/null
  compare "$name" "$work/rust.txt" "$work/scala.txt"
  echo "  $name $(head -n 1 "$work/rust.txt" | cut -c1-40)"
done

compare_world() {
  set -euo pipefail
  local dir=$1 name rust_report scala_report rust_attestation scala_attestation
  name=$(basename "$dir")
  rust_report="$work/$name.rust-report"
  scala_report="$work/$name.scala-report"
  rust_attestation="$work/$name.rust-attestation"
  scala_attestation="$work/$name.scala-attestation"

  "$RUST_HOST" report "$dir" --out "$rust_report" >/dev/null &
  local rust_report_pid=$!
  scala_host foundation report --dir "$dir" --out "$scala_report" >/dev/null &
  local scala_report_pid=$!
  "$RUST_HOST" attest "$dir" --out "$rust_attestation" >/dev/null &
  local rust_attestation_pid=$!
  scala_host foundation attest --dir "$dir" --out "$scala_attestation" >/dev/null &
  local scala_attestation_pid=$!

  wait "$rust_report_pid"
  wait "$scala_report_pid"
  wait "$rust_attestation_pid"
  wait "$scala_attestation_pid"
  compare "the derivation report for $name" "$rust_report" "$scala_report"
  compare "the attestation for $name" "$rust_attestation" "$scala_attestation"
  printf '%s\t%s\t%s\n' "$name" "$(tail -n 1 "$rust_report")" "$(tail -n 1 "$rust_attestation")" > "$work/$name.summary"
}

export -f compare compare_world scala_host
export RUST_HOST work classpath

echo "== derivation reports and attestations"
mapfile -t world_list < <(worlds)
if [[ -n "${STRATUM_PARITY_SHARD:-}" ]]; then
  IFS=/ read -r shard_index shard_count <<< "$STRATUM_PARITY_SHARD"
  if [[ ! "$shard_index" =~ ^[0-9]+$ || ! "$shard_count" =~ ^[1-9][0-9]*$ || "$shard_index" -ge "$shard_count" ]]; then
    echo "STRATUM_PARITY_SHARD must be INDEX/COUNT with INDEX < COUNT" >&2
    exit 1
  fi
  sharded_worlds=()
  i=$shard_index
  while [[ "$i" -lt "${#world_list[@]}" ]]; do
    sharded_worlds+=("${world_list[i]}")
    i=$((i + shard_count))
  done
  world_list=("${sharded_worlds[@]}")
fi
printf '%s\n' "${world_list[@]}" | xargs -r -n 1 -P "$workers" bash -c 'compare_world "$1"' _
for dir in "${world_list[@]}"; do
  name=$(basename "$dir")
  IFS=$'\t' read -r _ report attestation < "$work/$name.summary"
  echo "  $name $report"
  echo "  $name $attestation"
done

echo "two host parity ok"
