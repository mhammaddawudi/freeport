#!/usr/bin/env bash
#
# Builds Tor for every ABI the app ships:
#
#   libarti.so       Arti, the Tor Project's Rust client
#   libsnowflake.so  the Snowflake pluggable transport Arti starts
#
# Both are executables, named like libraries so Android extracts them into
# the app's library folder, where they may be run.
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk scripts/build-tor.sh
#
# Needs rustup with the Android targets, Go 1.25+, and an NDK (r26+).
#
# Where crates.io's index or Go's module proxy are filtered, the script
# falls back to crates.io's index on GitHub and to fetching modules straight
# from their repositories.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_VERSION="${ARTI_VERSION:-2.8.0}"
SNOWFLAKE_VERSION="${SNOWFLAKE_VERSION:-v2.15.1}"
API="${ANDROID_API:-26}"
WORK="${WORK:-$ROOT/.native-build/tor}"
JNILIBS="$ROOT/app/src/main/jniLibs"
# ONLY=arti or ONLY=snowflake rebuilds just one of the two.
ONLY="${ONLY:-all}"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an Android NDK (r26 or newer)}"
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
NDK="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$NDK" ] || { echo "no NDK toolchain at $NDK" >&2; exit 1; }
mkdir -p "$WORK"

# Paths of the build machine would otherwise be written into the binaries.
REMAP="-ffile-prefix-map=$HOME=/build -fdebug-prefix-map=$HOME=/build"

if [ "$ONLY" != arti ]; then
echo "==> snowflake $SNOWFLAKE_VERSION"
export GOTOOLCHAIN=local GOFLAGS=-modcacherw GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}" GOSUMDB=off
MODULE=gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2
SRC="$(go mod download -json "$MODULE@$SNOWFLAKE_VERSION" | awk -F'"' '/"Dir"/{print $4}')"
rm -rf "$WORK/snowflake" && cp -R "$SRC" "$WORK/snowflake" && chmod -R u+w "$WORK/snowflake"
(cd "$WORK/snowflake" && go mod download)

for spec in arm64:arm64-v8a:aarch64-linux-android arm:armeabi-v7a:armv7a-linux-androideabi amd64:x86_64:x86_64-linux-android; do
  IFS=: read -r arch abi cc <<<"$spec"
  mkdir -p "$JNILIBS/$abi"
  # checklinkname: a WebRTC dependency reaches into the net package's
  # internals. 16 KB pages: newer phones refuse anything laid out for 4 KB.
  (cd "$WORK/snowflake" && env $([ "$arch" = arm ] && echo GOARM=7) CGO_ENABLED=1 GOOS=android GOARCH="$arch" \
    CC="$NDK/$cc$API-clang" CGO_CFLAGS="$REMAP" \
    go build -trimpath -buildmode=pie \
      -ldflags="-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
      -o "$JNILIBS/$abi/libsnowflake.so" ./client)
done
fi
[ "$ONLY" = snowflake ] && exit 0

echo "==> arti $ARTI_VERSION"
GH_INDEX=()
if [ -z "${CARGO_NET_OFFLINE:-}" ] && ! curl -fsS -m 15 -o /dev/null https://index.crates.io/config.json; then
  echo "   crates.io's index is not reachable, using its copy on GitHub"
  GH_INDEX=(--config 'source.crates-io.replace-with="ghidx"'
            --config 'source.ghidx.registry="https://github.com/rust-lang/crates.io-index"')
  export CARGO_NET_GIT_FETCH_WITH_CLI=true
fi
export CARGO_NET_RETRY=10
# Tor is the last resort and limited by the network, not the CPU, so arti is
# built for size: roughly half the bytes of the default release profile.
export CARGO_PROFILE_RELEASE_OPT_LEVEL=s CARGO_PROFILE_RELEASE_LTO=fat \
  CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1 CARGO_PROFILE_RELEASE_PANIC=abort

for spec in ${ARTI_SPECS:-aarch64-linux-android:arm64-v8a:aarch64-linux-android armv7-linux-androideabi:armeabi-v7a:armv7a-linux-androideabi x86_64-linux-android:x86_64:x86_64-linux-android}; do
  IFS=: read -r triple abi cc <<<"$spec"
  rustup target list --installed | grep -qx "$triple" || rustup target add "$triple"
  u="$(echo "$triple" | tr '-' '_')"; U="$(echo "$u" | tr '[:lower:]' '[:upper:]')"
  export CC_$u="$NDK/$cc$API-clang" AR_$u="$NDK/llvm-ar" CFLAGS_$u="$REMAP"
  export CARGO_TARGET_${U}_LINKER="$NDK/$cc$API-clang"
  export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 --remap-path-prefix=$HOME=/build -C strip=symbols"
  # rustls rather than native-tls: nothing to link against on Android.
  cargo install ${GH_INDEX[@]+"${GH_INDEX[@]}"} --force arti --version "$ARTI_VERSION" --locked \
    --root "$WORK/arti-$abi" --target "$triple" \
    --no-default-features --features "tokio,rustls,compression,bridge-client,pt-client,static-sqlite"
  cp "$WORK/arti-$abi/bin/arti" "$JNILIBS/$abi/libarti.so"
done

echo "==> local paths left in the binaries (should all be 0)"
for lib in "$JNILIBS"/*/libarti.so "$JNILIBS"/*/libsnowflake.so; do
  printf '%6s  %s\n' "$(strings "$lib" | grep -c "$HOME" || true)" "${lib#$ROOT/}"
done
