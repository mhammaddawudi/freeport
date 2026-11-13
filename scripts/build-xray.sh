#!/usr/bin/env bash
#
# Builds Xray-core for every ABI the app ships, as libxray.so: an executable
# named like a library so Android extracts it where it may be run. It carries
# the public V2Ray configs of the Configs protocol (VLESS, VMess, Trojan,
# Shadowsocks over TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP, TLS and REALITY).
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk scripts/build-xray.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
XRAY_VERSION="${XRAY_VERSION:-v1.260327.0}"
API="${ANDROID_API:-26}"
WORK="${WORK:-$ROOT/.native-build/xray}"
JNILIBS="$ROOT/app/src/main/jniLibs"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an Android NDK (r26 or newer)}"
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
NDK="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$NDK" ] || { echo "no NDK toolchain at $NDK" >&2; exit 1; }
mkdir -p "$WORK"
REMAP="-ffile-prefix-map=$HOME=/build -fdebug-prefix-map=$HOME=/build"

export GOTOOLCHAIN=local GOFLAGS=-modcacherw GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}" GOSUMDB=off
SRC="$(go mod download -json "github.com/xtls/xray-core@$XRAY_VERSION" | awk -F'"' '/"Dir"/{print $4}')"
rm -rf "$WORK/src" && cp -R "$SRC" "$WORK/src" && chmod -R u+w "$WORK/src"

for spec in ${XRAY_SPECS:-arm64:arm64-v8a:aarch64-linux-android arm:armeabi-v7a:armv7a-linux-androideabi amd64:x86_64:x86_64-linux-android}; do
  IFS=: read -r arch abi cc <<<"$spec"
  echo "==> xray $XRAY_VERSION for $abi"
  mkdir -p "$JNILIBS/$abi"
  (cd "$WORK/src" && env $([ "$arch" = arm ] && echo GOARM=7) CGO_ENABLED=1 GOOS=android GOARCH="$arch" \
    CC="$NDK/$cc$API-clang" CGO_CFLAGS="$REMAP" \
    go build -trimpath -buildvcs=false -buildmode=pie \
      -ldflags="-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
      -o "$JNILIBS/$abi/libxray.so" ./main)
done
