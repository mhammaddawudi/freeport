#!/usr/bin/env bash
#
# Builds sing-box for this machine into desktop/engine/assets. Freeport runs
# it only as its TUN helper: it takes the system's (or chosen apps') traffic
# off a virtual interface and hands it to Freeport's own SOCKS port.
#
# Usage: desktop/scripts/build-singbox.sh <rust target triple>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET="${1:?pass the rust target triple}"
VERSION="${SINGBOX_VERSION:-v1.13.21}"
WORK="${WORK:-$ROOT/.native-build/singbox}"
ASSETS="$ROOT/desktop/engine/assets"

case "$TARGET" in
  aarch64-apple-darwin) GOOS=darwin; GOARCH=arm64; EXT="" ;;
  x86_64-apple-darwin) GOOS=darwin; GOARCH=amd64; EXT="" ;;
  x86_64-pc-windows-msvc) GOOS=windows; GOARCH=amd64; EXT=".exe" ;;
  *) echo "no sing-box build for $TARGET" >&2; exit 1 ;;
esac
mkdir -p "$WORK" "$ASSETS"

export GOTOOLCHAIN=local GOFLAGS=-modcacherw GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}" GOSUMDB=off
SRC="$(go mod download -json "github.com/sagernet/sing-box@$VERSION" | awk -F'"' '/"Dir"/{print $4}')"
rm -rf "$WORK/src" && cp -R "$SRC" "$WORK/src" && chmod -R u+w "$WORK/src"
(cd "$WORK/src" && CGO_ENABLED=0 GOOS=$GOOS GOARCH=$GOARCH \
  go build -trimpath -tags "with_gvisor" -ldflags="-s -w -buildid= -checklinkname=0" \
  -o "$ASSETS/singbox-$GOOS-$GOARCH$EXT" ./cmd/sing-box)
echo "   $(du -h "$ASSETS/singbox-$GOOS-$GOARCH$EXT" | cut -f1)  singbox-$GOOS-$GOARCH$EXT"
