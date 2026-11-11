#!/usr/bin/env bash
#
# Builds Psiphon's console client for this machine into desktop/engine/assets,
# where the engine embeds it for region unlock.
#
# Usage: desktop/scripts/build-psiphon.sh [arm64|amd64]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${PSIPHON_SRC:-$ROOT/../psiphon-tunnel-core}"
REPO="https://github.com/Psiphon-Labs/psiphon-tunnel-core.git"
# The commit the Android and Mac builds ship.
COMMIT="${PSIPHON_COMMIT:-b35028d7fde45fc2e42e66040c31ae582989b602}"

case "$(uname -s)" in
  Darwin) GOOS=darwin; EXT="" ;;
  MINGW*|MSYS*|CYGWIN*) GOOS=windows; EXT=".exe" ;;
  *) GOOS=linux; EXT="" ;;
esac
ARCH="${1:-$(uname -m)}"
case "$ARCH" in arm64|aarch64) GOARCH=arm64 ;; *) GOARCH=amd64 ;; esac

if [ ! -d "$SRC/.git" ]; then
  git init -q "$SRC"
  git -C "$SRC" config core.autocrlf false
  git -C "$SRC" remote add origin "$REPO"
  git -C "$SRC" fetch -q --depth 1 origin "$COMMIT"
  git -C "$SRC" checkout -q FETCH_HEAD
fi

OUT="$ROOT/desktop/engine/assets/psiphon-$GOOS-$GOARCH$EXT"
mkdir -p "$(dirname "$OUT")"
# cgo stays on for macOS, whose resolver Psiphon reaches through it.
CGO=0; [ "$GOOS" = darwin ] && CGO=1
(cd "$SRC/ConsoleClient" && GOTOOLCHAIN=local GOFLAGS=-mod=vendor CGO_ENABLED=$CGO GOOS=$GOOS GOARCH=$GOARCH \
  go build -trimpath -ldflags "-s -w -buildid=" -o "$OUT" .)
echo "==> $OUT ($(du -h "$OUT" | cut -f1))"
