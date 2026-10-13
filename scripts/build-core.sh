#!/usr/bin/env bash
#
# Builds the native half of the app for arm64 Android:
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
WORK="${WORK:-$ROOT/.native-build}"
JNILIBS="$ROOT/app/src/main/jniLibs"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an Android NDK (r26 or newer)}"
command -v cmake >/dev/null || { echo "cmake is required" >&2; exit 1; }

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
[ -d "$TOOLCHAIN" ] || { echo "no NDK toolchain at $TOOLCHAIN" >&2; exit 1; }

mkdir -p "$WORK" "$JNILIBS/arm64-v8a"

echo "==> sources"
[ -d "$AETHER_SRC/aether" ] || git clone --depth 1 --branch "$AETHER_REF" \
  https://github.com/CluvexStudio/Aether.git "$AETHER_SRC"
[ -d "$HEV_SRC" ] || git clone --recursive --depth 1 \
  https://github.com/heiher/hev-socks5-tunnel.git "$HEV_SRC"

echo "==> rust target"
rustup target add aarch64-linux-android

echo "==> boringssl"
# The source is vendored in the boring-sys crate, so fetch the crates first.
(cd "$AETHER_SRC/aether" && cargo fetch --quiet)
BSSL_SRC="$(ls -d "$HOME"/.cargo/registry/src/*/boring-sys-*/deps/boringssl 2>/dev/null | head -1)"
[ -n "$BSSL_SRC" ] || { echo "could not find the vendored boringssl source" >&2; exit 1; }
if [ ! -f "$WORK/bssl/libcrypto.a" ]; then
  cmake -S "$BSSL_SRC" -B "$WORK/bssl/build" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_AR="$TOOLCHAIN/bin/llvm-ar" -DCMAKE_RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  cmake --build "$WORK/bssl/build" --target crypto ssl -j "$(getconf _NPROCESSORS_ONLN)"
  mkdir -p "$WORK/bssl"
  find "$WORK/bssl/build" -name 'libcrypto.a' -o -name 'libssl.a' | xargs -I{} cp {} "$WORK/bssl/"
fi

echo "==> core"
export ANDROID_NDK_HOME ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export PATH="$TOOLCHAIN/bin:$PATH"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export RANLIB_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ranlib"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android$API-clang"
export CFLAGS_aarch64_linux_android="--target=aarch64-linux-android$API"
export CXXFLAGS_aarch64_linux_android="--target=aarch64-linux-android$API"
export BORING_BSSL_PATH="$WORK/bssl"
export BORING_BSSL_INCLUDE_PATH="$BSSL_SRC/src/include"
[ -d "$BORING_BSSL_INCLUDE_PATH" ] || export BORING_BSSL_INCLUDE_PATH="$BSSL_SRC/include"
export BORING_BSSL_ASSUME_PATCHED=1
# 16 KB pages: newer devices refuse a library laid out for 4 KB ones.
# --no-undefined: fail the link instead of shipping a .so that cannot resolve
# its symbols, which otherwise only shows up as a dlopen error on the phone.
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--no-undefined"
(cd "$AETHER_SRC/aether" && cargo build --release --target aarch64-linux-android --lib)
cp "$AETHER_SRC/aether/target/aarch64-linux-android/release/libaether.so" "$JNILIBS/arm64-v8a/"

echo "==> jni shim"
"$TOOLCHAIN/bin/aarch64-linux-android$API-clang" -shared -fPIC -O2 \
  -Wl,-z,max-page-size=16384 -Wl,--no-undefined \
  -o "$JNILIBS/arm64-v8a/libaether_jni.so" "$ROOT/app/src/main/cpp/aether_jni.c" \
  -L"$JNILIBS/arm64-v8a" -laether -llog

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
