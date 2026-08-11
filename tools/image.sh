#!/usr/bin/env bash
# Builds and restores content-addressed materialization images.
set -euo pipefail

cd "$(dirname "$0")/.."

usage() {
  cat >&2 <<'EOF'
usage:
  tools/image.sh clear
  tools/image.sh capture <output-dir> [parent-root]
  tools/image.sh materialize <image-root>
  tools/image.sh build <image> [parent-image]
  tools/image.sh restore <image> [expected-commit]
EOF
  exit 2
}

materialized_paths() {
  local roots=()
  local root
  for root in applications changes features foundations host languages; do
    if [[ -d "$root" ]]; then
      roots+=("$root")
    fi
  done
  if ((${#roots[@]} == 0)); then
    return
  fi
  find "${roots[@]}" -type f \
    \( -name '*.generated.grammar' \
    -o -name '*.generated.meta' \
    -o -path '*/closure/*.canon' \
    -o -name foundation.canon \
    -o -name evidence.canon \
    -o -name derivation.canon \
    -o -path 'host/core.canon' \) \
    -print | sort
}

clear_materialized() {
  local path
  materialized_paths |
  while IFS= read -r path; do
    rm -f "$path"
  done
}

capture() {
  local output=$1
  local parent=${2:-}
  local objects="$output/stratum/objects"
  local index="$output/stratum/index.sha256"
  local path digest

  rm -rf "$output"
  mkdir -p "$objects"
  : > "$index"

  materialized_paths |
  while IFS= read -r path; do
    digest=$(sha256sum "$path" | cut -d' ' -f1)
    printf '%s  %s\n' "$digest" "$path" >> "$index"
    if [[ ! -f "$objects/$digest" && ! -f "$parent/objects/$digest" ]]; then
      cp "$path" "$objects/$digest"
    fi
  done

  {
    echo 'format 1'
    printf 'commit %s\n' "$(git rev-parse HEAD)"
    if git rev-parse HEAD^ >/dev/null 2>&1; then
      printf 'parent %s\n' "$(git rev-parse HEAD^)"
    else
      echo 'parent root'
    fi
  } > "$output/stratum/metadata"
}

materialize() {
  local root=$1
  local index="$root/index.sha256"
  local digest path object actual

  [[ -f "$index" ]] || { echo "image has no path index" >&2; exit 1; }
  clear_materialized

  cat "$index" |
  while read -r digest path; do
    case "$path" in
      /* | ../* | */../* | */..) echo "unsafe image path: $path" >&2; exit 1 ;;
    esac
    object="$root/objects/$digest"
    [[ -f "$object" ]] || { echo "image is missing object $digest for $path" >&2; exit 1; }
    actual=$(sha256sum "$object" | cut -d' ' -f1)
    [[ "$actual" == "$digest" ]] || { echo "image object $digest is corrupt" >&2; exit 1; }
    mkdir -p "$(dirname "$path")"
    cp "$object" "$path"
  done
}

extract_image() {
  local image=$1
  local output=$2
  local container
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker pull "$image" >/dev/null
  fi
  container=$(docker create "$image" /unused)
  trap 'docker rm -f "$container" >/dev/null 2>&1 || true' RETURN
  mkdir -p "$output"
  docker cp "$container:/stratum/." "$output"
  docker rm "$container" >/dev/null
  trap - RETURN
}

build_image() {
  local image=$1
  local parent=${2:-}
  local work parent_root from
  work=$(mktemp -d)
  trap 'rm -rf "$work"' RETURN

  parent_root="$work/parent"
  from=scratch
  if [[ -n "$parent" ]]; then
    case "$parent" in
      *[!A-Za-z0-9._/@:-]*) echo "unsafe parent image: $parent" >&2; exit 1 ;;
    esac
    extract_image "$parent" "$parent_root"
    from=$parent
  fi

  capture "$work/layer" "$parent_root"
  {
    printf 'FROM %s\n' "$from"
    echo 'COPY layer/ /'
    printf 'LABEL org.opencontainers.image.revision="%s"\n' "$(git rev-parse HEAD)"
  } > "$work/Dockerfile"
  docker build -q -t "$image" "$work" >/dev/null
  trap - RETURN
  rm -rf "$work"
}

restore_image() {
  local image=$1
  local expected_commit=${2:-}
  local work
  work=$(mktemp -d)
  trap 'rm -rf "$work"' RETURN
  extract_image "$image" "$work/stratum"
  if [[ -n "$expected_commit" ]]; then
    actual_commit=$(awk '$1 == "commit" { print $2 }' "$work/stratum/metadata")
    [[ "$actual_commit" == "$expected_commit" ]] || {
      echo "image revision mismatch: expected $expected_commit, found $actual_commit" >&2
      exit 1
    }
  fi
  materialize "$work/stratum"
  trap - RETURN
  rm -rf "$work"
}

case "${1:-}" in
  clear)
    (($# == 1)) || usage
    clear_materialized
    ;;
  capture)
    (($# == 2 || $# == 3)) || usage
    capture "$2" "${3:-}"
    ;;
  materialize)
    (($# == 2)) || usage
    materialize "$2"
    ;;
  build)
    (($# == 2 || $# == 3)) || usage
    build_image "$2" "${3:-}"
    ;;
  restore)
    (($# == 2 || $# == 3)) || usage
    restore_image "$2" "${3:-}"
    ;;
  *) usage ;;
esac
