#!/usr/bin/env bash
# Clean room reconstruction.
#
# Copies nothing but `digest.txt`, `closure/` and the independent host
# executable into an empty directory, then runs every authoritative check from
# there. No build spec, no source tree, no working copy.
set -euo pipefail

cd "$(dirname "$0")/.."
repository=$PWD

RUST_HOST="$repository/host-rust/target/release/stratum-verify"
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

# Every world's clean-room and repository verification is independent of
# every other world's, and within a world the four stratum-verify calls (room
# attest, room report, repository attest, repository report) are independent
# of each other too -- `report` alone takes over a minute on the largest
# foundations, so running all of this one call at a time left most of the
# machine idle. Everything below runs concurrently instead.
verify_world() {
  local dir=$1 name
  name=$(basename "$dir")
  mkdir -p "$room/$name/closure"
  cp "$dir/digest.txt" "$room/$name/digest.txt"
  cp "$dir"/closure/*.canon "$room/$name/closure/"

  local pids=()
  (cd "$room" && ./stratum-verify attest "$name" > "$name.attestation") & pids+=($!)
  (cd "$room" && ./stratum-verify report "$name" > "$name.report") & pids+=($!)
  "$RUST_HOST" attest "$dir" > "$room/$name.repository-attestation" & pids+=($!)
  "$RUST_HOST" report "$dir" > "$room/$name.repository-report" & pids+=($!)

  local failed=0 p
  for p in "${pids[@]}"; do
    wait "$p" || failed=1
  done
  if [ "$failed" -ne 0 ]; then
    echo "stratum-verify failed for $name" >&2
    return 1
  fi

  echo "  $name $(tail -n 1 "$room/$name.attestation")"
  echo "  $name $(tail -n 1 "$room/$name.report")"

  # The clean room results must equal the results computed in the repository.
  diff -u "$room/$name.attestation" "$room/$name.repository-attestation" >/dev/null || {
    echo "clean room attestation differs for $name" >&2; return 1; }
  diff -u "$room/$name.report" "$room/$name.repository-report" >/dev/null || {
    echo "clean room derivation differs for $name" >&2; return 1; }
}

pids=()
names=()
for dir in $(worlds); do
  verify_world "$dir" &
  pids+=($!)
  names+=("$(basename "$dir")")
done

status=0
for i in "${!pids[@]}"; do
  wait "${pids[$i]}" || { echo "verification failed for ${names[$i]}" >&2; status=1; }
done

if [ "$status" -eq 0 ]; then
  echo "clean room reconstruction ok"
fi
exit "$status"
