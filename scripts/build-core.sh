#!/usr/bin/env bash
#
# Builds the native half of the app for every ABI the app ships:
#
#   libaether.so            the Aether core (Rust)
#   libaether_jni.so        the JNI shim in app/src/main/cpp
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
AETHER_SRC="${AETHER_SRC:-$ROOT/../aether-src}"
AETHER_REF="${AETHER_REF:-main}"
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
[ -d "$AETHER_SRC/aether" ] || git clone --depth 1 --branch "$AETHER_REF" \
  https://github.com/CluvexStudio/Aether.git "$AETHER_SRC"
[ -d "$HEV_SRC" ] || git clone --recursive --depth 1 \
  https://github.com/heiher/hev-socks5-tunnel.git "$HEV_SRC"

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

(cd "$AETHER_SRC/aether" && cargo fetch --quiet)
BSSL_SRC="$(ls -d "$HOME"/.cargo/registry/src/*/boring-sys-*/deps/boringssl 2>/dev/null | head -1)"
[ -n "$BSSL_SRC" ] || { echo "could not find the vendored boringssl source" >&2; exit 1; }

for abi in $ABIS; do
  triple="$(triple_of "$abi")"
  cc="$(compiler_of "$abi")"
  echo "==> $abi ($triple)"
  install_std "$triple"

  if [ ! -f "$WORK/bssl-$abi/libcrypto.a" ]; then
    cmake -S "$BSSL_SRC" -B "$WORK/bssl-$abi/build" \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
      -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
      -DCMAKE_AR="$TOOLCHAIN/bin/llvm-ar" -DCMAKE_RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    cmake --build "$WORK/bssl-$abi/build" --target crypto ssl -j "$(getconf _NPROCESSORS_ONLN)"
    mkdir -p "$WORK/bssl-$abi"
    find "$WORK/bssl-$abi/build" -name 'libcrypto.a' -o -name 'libssl.a' | xargs -I{} cp {} "$WORK/bssl-$abi/"
  fi

  underscored="$(echo "$triple" | tr '-' '_')"
  upper="$(echo "$underscored" | tr '[:lower:]' '[:upper:]')"
  export CC_${underscored}="$TOOLCHAIN/bin/$cc-clang"
  export CXX_${underscored}="$TOOLCHAIN/bin/$cc-clang++"
  export AR_${underscored}="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB_${underscored}="$TOOLCHAIN/bin/llvm-ranlib"
  export CARGO_TARGET_${upper}_LINKER="$TOOLCHAIN/bin/$cc-clang"
  export CFLAGS_${underscored}="--target=$cc"
  export CXXFLAGS_${underscored}="--target=$cc"
  export BORING_BSSL_PATH="$WORK/bssl-$abi"
  export BORING_BSSL_INCLUDE_PATH="$BSSL_SRC/src/include"
  [ -d "$BORING_BSSL_INCLUDE_PATH" ] || export BORING_BSSL_INCLUDE_PATH="$BSSL_SRC/include"
  export BORING_BSSL_ASSUME_PATCHED=1
  # 16 KB pages: newer devices refuse a library laid out for 4 KB ones.
  # --no-undefined: fail the link rather than ship a .so whose symbols only
  # turn out to be missing when the phone tries to load it.
  export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--no-undefined"

  (cd "$AETHER_SRC/aether" && cargo build --release --target "$triple" --lib)
  mkdir -p "$JNILIBS/$abi"
  cp "$AETHER_SRC/aether/target/$triple/release/libaether.so" "$JNILIBS/$abi/"
  "$TOOLCHAIN/bin/$cc-clang" -shared -fPIC -O2 \
    -Wl,-z,max-page-size=16384 -Wl,--no-undefined \
    -o "$JNILIBS/$abi/libaether_jni.so" "$ROOT/app/src/main/cpp/aether_jni.c" \
    -L"$JNILIBS/$abi" -laether -llog
done

echo "==> tun2socks"
(cd "$HEV_SRC" && "$ANDROID_NDK_HOME/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk \
  APP_ABI="arm64-v8a armeabi-v7a x86_64" APP_PLATFORM="android-$API" \
  APP_LDFLAGS="-Wl,-z,max-page-size=16384" NDK_LIBS_OUT=./libs NDK_OUT=./obj >/dev/null)
for abi in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p "$JNILIBS/$abi"
  cp "$HEV_SRC/libs/$abi/libhev-socks5-tunnel.so" "$JNILIBS/$abi/"
done

echo "==> done"
find "$JNILIBS" -name '*.so' -exec ls -lh {} \; | awk '{print $5, $9}'
