#!/usr/bin/env bash
#
# Builds Xray-core for this machine into desktop/engine/assets. It carries the
# public V2Ray configs of the V2Ray protocol (VLESS, VMess, Trojan,
# Shadowsocks over TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP, TLS and REALITY).
#
# Usage: desktop/scripts/build-xray.sh <rust target triple>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET="${1:?pass the rust target triple}"
VERSION="${XRAY_VERSION:-v1.260327.0}"
WORK="${WORK:-$ROOT/.native-build/xray-desktop}"
ASSETS="$ROOT/desktop/engine/assets"

case "$TARGET" in
  aarch64-apple-darwin) GOOS=darwin; GOARCH=arm64; EXT="" ;;
  x86_64-apple-darwin) GOOS=darwin; GOARCH=amd64; EXT="" ;;
  x86_64-pc-windows-msvc) GOOS=windows; GOARCH=amd64; EXT=".exe" ;;
  *) echo "no xray build for $TARGET" >&2; exit 1 ;;
esac
mkdir -p "$WORK" "$ASSETS"

export GOTOOLCHAIN=local GOFLAGS=-modcacherw GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}" GOSUMDB=off
SRC="$(go mod download -json "github.com/xtls/xray-core@$VERSION" | awk -F'"' '/"Dir"/{print $4}')"
rm -rf "$WORK/src" && cp -R "$SRC" "$WORK/src" && chmod -R u+w "$WORK/src"
(cd "$WORK/src" && CGO_ENABLED=0 GOOS=$GOOS GOARCH=$GOARCH \
  go build -trimpath -buildvcs=false -ldflags="-s -w -buildid=" \
  -o "$ASSETS/xray-$GOOS-$GOARCH$EXT" ./main)
echo "   $(du -h "$ASSETS/xray-$GOOS-$GOARCH$EXT" | cut -f1)  xray-$GOOS-$GOARCH$EXT"
