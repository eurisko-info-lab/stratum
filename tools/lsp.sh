#!/usr/bin/env bash
# Starts the generic language server for a world.
#
# stdout belongs to the protocol, so the classpath is resolved once and cached,
# and the server is started with java directly rather than through a build tool.
set -euo pipefail

cd "$(dirname "$0")/.."

cache=".stratum/classpath"
mkdir -p .stratum

stale=1
if [ -f "$cache" ]; then
  stale=0
  if [ -n "$(find host-scala -name '*.scala' -newer "$cache" -print -quit)" ]; then
    stale=1
  fi
fi

if [ "$stale" -eq 1 ]; then
  sbt -batch compile >/dev/null 2>&1
  sbt -batch "export Compile/fullClasspath" 2>/dev/null |
    grep -E 'classes|\.jar' | tail -n 1 > "$cache"
fi

exec java -cp "$(cat "$cache")" stratum.cli.Stratum lsp serve "$@"
