#!/usr/bin/env bash
#
# Builds the native half of the app for every ABI the app ships:
#
#   libwarp.so            the Warp core (Rust)
#   libwarp_jni.so        the JNI shim in app/src/main/cpp
#   libhev-socks5-tunnel.so the tun2socks that feeds the core's proxy
#
# Usage:
#   ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/26.1.10909125 scripts/build-core.sh
#
# Needs rustup, cmake 3.20+, git and an NDK (r26+).
#
# BoringSSL is built on its own rather than through boring-sys's build script.
# On a macOS host that script picks up the platform's compiler defaults and
# produces a library for the host, or archives in a format lld silently
# ignores, and the result is a .so that only fails when the phone loads it.
# Building it directly against the NDK's toolchain file avoids all of that.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE_SRC="${CORE_SRC:-$ROOT/../warp-src}"
CORE_REF="${CORE_REF:-main}"
# The upstream core this one is built from (AGPL-3.0, see NOTICE.md).
CORE_REPO="${CORE_REPO:-https://github.com/CluvexStudio/Aether.git}"
HEV_SRC="${HEV_SRC:-$ROOT/../hev-socks5-tunnel}"
API="${ANDROID_API:-26}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
WORK="${WORK:-$ROOT/.native-build}"
JNILIBS="$ROOT/app/src/main/jniLibs"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an Android NDK (r26 or newer)}"
command -v cmake >/dev/null || { echo "cmake is required" >&2; exit 1; }

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
[ -d "$TOOLCHAIN" ] || { echo "no NDK toolchain at $TOOLCHAIN" >&2; exit 1; }

mkdir -p "$WORK" "$JNILIBS"

echo "==> sources"
[ -d "$CORE_SRC" ] || git clone --depth 1 --branch "$CORE_REF" "$CORE_REPO" "$CORE_SRC"
[ -d "$HEV_SRC" ] || git clone --recursive --depth 1 \
  https://github.com/heiher/hev-socks5-tunnel.git "$HEV_SRC"

# The crate is the folder whose Cargo.toml builds a cdylib.
CRATE="$(dirname "$(grep -l 'cdylib' "$CORE_SRC"/*/Cargo.toml | head -1)")"
[ -d "$CRATE/src" ] || { echo "no core crate under $CORE_SRC" >&2; exit 1; }

echo "==> warp core"
"$ROOT/scripts/rebrand-core.sh" "$CRATE"
# Freeport's changes to the core, written against the renamed sources.
for patch in "$ROOT"/scripts/core-patches/*.patch; do
  if patch -p2 -d "$CRATE" -N --dry-run -s < "$patch" >/dev/null 2>&1; then
    patch -p2 -d "$CRATE" -N -s < "$patch"
  elif patch -p2 -d "$CRATE" -R --dry-run -s < "$patch" >/dev/null 2>&1; then
    echo "   $(basename "$patch") is already in"
  else
    echo "$(basename "$patch") does not apply to this core" >&2; exit 1
  fi
done

# Each ABI needs its own BoringSSL, core and shim.
#
# Two naming traps live here. cc-rs guesses "arm-linux-androideabi-clang" for
# 32-bit ARM, but the NDK ships "armv7a-linux-androideabiAPI-clang", so the
# tools are named explicitly. And rust-std tarballs live under the release
# date, not the commit date rustc reports, so the date is probed rather than
# computed.
triple_of () {
  case "$1" in
    arm64-v8a)    echo aarch64-linux-android ;;
    armeabi-v7a)  echo armv7-linux-androideabi ;;
    x86_64)       echo x86_64-linux-android ;;
    *) echo "unknown ABI: $1" >&2; exit 1 ;;
  esac
}

compiler_of () {
  case "$1" in
    arm64-v8a)    echo "aarch64-linux-android$API" ;;
    armeabi-v7a)  echo "armv7a-linux-androideabi$API" ;;
    x86_64)       echo "x86_64-linux-android$API" ;;
  esac
}

install_std () {
  local triple="$1" ver commit day url=""
  rustup target list --installed | grep -qx "$triple" && return 0
  rustup target add "$triple" && return 0
  ver=$(rustc --version | awk '{print $2}')
  commit=$(rustc --version --verbose | awk '/^commit-date/{print $2}')
  for offset in 0 1 2 3 4 5 6 7; do
    day=$(date -j -v+${offset}d -f "%Y-%m-%d" "$commit" "+%Y-%m-%d" 2>/dev/null) || continue
    if curl -fsS -o /dev/null -r 0-100 --max-time 20 \
       "https://static.rust-lang.org/dist/$day/rust-std-$ver-$triple.tar.xz" 2>/dev/null; then
      url="https://static.rust-lang.org/dist/$day/rust-std-$ver-$triple.tar.xz"
      break
    fi
  done
  [ -n "$url" ] || { echo "no rust-std published for $triple" >&2; return 1; }
  curl -fL -C - --retry 10 --retry-all-errors -o "$WORK/std-$triple.tar.xz" "$url"
  rm -rf "$WORK/std-$triple" && mkdir -p "$WORK/std-$triple"
  tar xJf "$WORK/std-$triple.tar.xz" -C "$WORK/std-$triple"
  cp -R "$(find "$WORK/std-$triple" -type d -name "$triple" -path '*rustlib*' | head -1)" \
     "$(rustc --print sysroot)/lib/rustlib/"
}

(cd "$CRATE" && cargo fetch --quiet)
# The boring-sys the core's lockfile names, not whichever one the registry
# happens to list first: an older one's headers do not match the boring crate.
BSSL_VERSION="$(awk '/^name = "boring-sys"/{getline; gsub(/"/, "", $3); print $3; exit}' "$CRATE/Cargo.lock")"
BSSL_SRC="$(ls -d "$HOME"/.cargo/registry/src/*/boring-sys-"$BSSL_VERSION"/deps/boringssl 2>/dev/null | head -1)"
[ -n "$BSSL_SRC" ] || { echo "could not find the boringssl source of boring-sys $BSSL_VERSION" >&2; exit 1; }

# Build folders and the cargo registry would otherwise be written into the
# libraries as /Users/<name>/..., which names the person who built them.
REMAP="-ffile-prefix-map=$HOME=/build -fdebug-prefix-map=$HOME=/build -fmacro-prefix-map=$HOME=/build"

for abi in $ABIS; do
  triple="$(triple_of "$abi")"
  cc="$(compiler_of "$abi")"
  echo "==> $abi ($triple)"
  install_std "$triple"

  BSSL_OUT="$WORK/bssl-$BSSL_VERSION-$abi"
  if [ ! -f "$BSSL_OUT/libcrypto.a" ]; then
    cmake -S "$BSSL_SRC" -B "$BSSL_OUT/build" \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
      -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
      -DCMAKE_AR="$TOOLCHAIN/bin/llvm-ar" -DCMAKE_RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
      -DCMAKE_C_FLAGS="$REMAP" -DCMAKE_CXX_FLAGS="$REMAP" -DCMAKE_ASM_FLAGS="$REMAP"
    cmake --build "$BSSL_OUT/build" --target crypto ssl -j "$(getconf _NPROCESSORS_ONLN)"
    find "$BSSL_OUT/build" -name 'libcrypto.a' -o -name 'libssl.a' | xargs -I{} cp {} "$BSSL_OUT/"
  fi

  underscored="$(echo "$triple" | tr '-' '_')"
  upper="$(echo "$underscored" | tr '[:lower:]' '[:upper:]')"
  export CC_${underscored}="$TOOLCHAIN/bin/$cc-clang"
  export CXX_${underscored}="$TOOLCHAIN/bin/$cc-clang++"
  export AR_${underscored}="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB_${underscored}="$TOOLCHAIN/bin/llvm-ranlib"
  export CARGO_TARGET_${upper}_LINKER="$TOOLCHAIN/bin/$cc-clang"
  export CFLAGS_${underscored}="--target=$cc $REMAP"
  export CXXFLAGS_${underscored}="--target=$cc $REMAP"
  export BORING_BSSL_PATH="$BSSL_OUT"
  export BORING_BSSL_INCLUDE_PATH="$BSSL_SRC/src/include"
  [ -d "$BORING_BSSL_INCLUDE_PATH" ] || export BORING_BSSL_INCLUDE_PATH="$BSSL_SRC/include"
  export BORING_BSSL_ASSUME_PATCHED=1
  # boring-sys 5 links the shared libc++ on Android unless told otherwise,
  # and the apk does not ship libc++_shared.so: the core would fail to load.
  export BORING_BSSL_RUST_CPPLIB_${underscored}="static:-bundle=c++"
  # 16 KB pages: newer devices refuse a library laid out for 4 KB ones.
  # --no-undefined: fail the link rather than ship a .so whose symbols only
  # turn out to be missing when the phone tries to load it.
  export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--no-undefined --remap-path-prefix=$HOME=/build --remap-path-prefix=$CORE_SRC=/build/core"

  (cd "$CRATE" && cargo build --release --target "$triple" --lib)
  needed="$("$TOOLCHAIN/bin/llvm-readelf" -d "$CRATE/target/$triple/release/libwarp.so" | awk '/NEEDED/{print $NF}')"
  if grep -q 'libc++_shared' <<<"$needed"; then
    echo "libwarp.so for $abi still needs libc++_shared.so; the static libc++ link failed" >&2
    exit 1
  fi
  mkdir -p "$JNILIBS/$abi"
  cp "$CRATE/target/$triple/release/libwarp.so" "$JNILIBS/$abi/"
  "$TOOLCHAIN/bin/$cc-clang" -shared -fPIC -O2 $REMAP \
    -Wl,-z,max-page-size=16384 -Wl,--no-undefined \
    -o "$JNILIBS/$abi/libwarp_jni.so" "$ROOT/app/src/main/cpp/warp_jni.c" \
    -L"$JNILIBS/$abi" -lwarp -llog
done

echo "==> tun2socks"
(cd "$HEV_SRC" && "$ANDROID_NDK_HOME/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk \
  APP_ABI="arm64-v8a armeabi-v7a x86_64" APP_PLATFORM="android-$API" \
  APP_CFLAGS="$REMAP" APP_LDFLAGS="-Wl,-z,max-page-size=16384" NDK_LIBS_OUT=./libs NDK_OUT=./obj >/dev/null)
for abi in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p "$JNILIBS/$abi"
  cp "$HEV_SRC/libs/$abi/libhev-socks5-tunnel.so" "$JNILIBS/$abi/"
done

echo "==> local paths left in the libraries (should all be 0)"
for lib in "$JNILIBS"/*/libwarp.so "$JNILIBS"/*/libwarp_jni.so "$JNILIBS"/*/libhev-socks5-tunnel.so; do
  printf '%6s  %s\n' "$(strings "$lib" | grep -c "$HOME" || true)" "${lib#$ROOT/}"
done

echo "==> done"
find "$JNILIBS" -name '*.so' -exec ls -lh {} \; | awk '{print $5, $9}'
