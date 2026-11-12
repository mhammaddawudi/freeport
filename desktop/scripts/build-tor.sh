#!/usr/bin/env bash
#
# Builds Tor for this machine into desktop/engine/assets, the same pair the
# Android app ships:
#
#   arti       Arti, the Tor Project's Rust client
#   snowflake  the Snowflake pluggable transport Arti starts
#
# Usage: desktop/scripts/build-tor.sh <rust target triple>
#   e.g. aarch64-apple-darwin, x86_64-apple-darwin, x86_64-pc-windows-msvc
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET="${1:?pass the rust target triple}"
ARTI_VERSION="${ARTI_VERSION:-2.8.0}"
SNOWFLAKE_VERSION="${SNOWFLAKE_VERSION:-v2.15.1}"
WORK="${WORK:-$ROOT/.native-build/tor-desktop}"
ASSETS="$ROOT/desktop/engine/assets"

case "$TARGET" in
  aarch64-apple-darwin) GOOS=darwin; GOARCH=arm64; EXT="" ;;
  x86_64-apple-darwin) GOOS=darwin; GOARCH=amd64; EXT="" ;;
  x86_64-pc-windows-msvc) GOOS=windows; GOARCH=amd64; EXT=".exe" ;;
  *) echo "no tor build for $TARGET" >&2; exit 1 ;;
esac
mkdir -p "$WORK" "$ASSETS"

echo "==> snowflake $SNOWFLAKE_VERSION"
export GOTOOLCHAIN=local GOFLAGS=-modcacherw GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}" GOSUMDB=off
MODULE=gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2
SRC="$(go mod download -json "$MODULE@$SNOWFLAKE_VERSION" | awk -F'"' '/"Dir"/{print $4}')"
rm -rf "$WORK/snowflake" && cp -R "$SRC" "$WORK/snowflake" && chmod -R u+w "$WORK/snowflake"
(cd "$WORK/snowflake" && CGO_ENABLED=0 GOOS=$GOOS GOARCH=$GOARCH \
  go build -trimpath -ldflags="-s -w -buildid= -checklinkname=0" -o "$ASSETS/snowflake-$GOOS-$GOARCH$EXT" ./client)

echo "==> arti $ARTI_VERSION"
rustup target list --installed | grep -qx "$TARGET" || rustup target add "$TARGET"
export CARGO_NET_RETRY=10
export CARGO_PROFILE_RELEASE_OPT_LEVEL=s CARGO_PROFILE_RELEASE_LTO=fat \
  CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1 CARGO_PROFILE_RELEASE_PANIC=abort
export RUSTFLAGS="--remap-path-prefix=$HOME=/build -C strip=symbols"
cargo install --force arti --version "$ARTI_VERSION" --locked --root "$WORK/arti-$TARGET" --target "$TARGET" \
  --no-default-features --features "tokio,rustls,compression,bridge-client,pt-client,static-sqlite"
cp "$WORK/arti-$TARGET/bin/arti$EXT" "$ASSETS/arti-$GOOS-$GOARCH$EXT"

for file in "$ASSETS/arti-$GOOS-$GOARCH$EXT" "$ASSETS/snowflake-$GOOS-$GOARCH$EXT"; do
  echo "   $(du -h "$file" | cut -f1)  $(basename "$file")"
done
