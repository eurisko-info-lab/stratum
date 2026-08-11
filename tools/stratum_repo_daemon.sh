#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

cache=".stratum/repo-classpath"
mkdir -p .stratum

stale=1
if [ -f "$cache" ]; then
  stale=0
  if [ -n "$(find host-scala repo-scala -name '*.scala' -newer "$cache" -print -quit)" ] ||
     [ build.sbt -nt "$cache" ]; then
    stale=1
  fi
fi

if [ "$stale" -eq 1 ]; then
  sbt -batch compile >/dev/null 2>&1
  sbt -batch "export Compile/fullClasspath" 2>/dev/null |
    grep -E 'classes|\.jar' | tail -n 1 > "$cache"
fi

exec java -Xss256m -cp "$(cat "$cache")" stratum.repo.StratumRepoDaemon "$PWD"
