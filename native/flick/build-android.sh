#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
NDK_PATH="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_PATH" ]]; then
    SDK_PATH="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    NDK_PATH="$SDK_PATH/ndk/28.2.13676358"
fi
if [[ ! -d "$NDK_PATH/toolchains/llvm" ]]; then
    echo "Android NDK unavailable; set ANDROID_NDK_HOME to NDK 28.2 or compatible." >&2
    exit 1
fi
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android26-clang"
export CXX_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android26-clang++"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
cargo build --locked --release --target aarch64-linux-android
mkdir -p ../../app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/libnextcloud_flick.so ../../app/src/main/jniLibs/arm64-v8a/
cp "$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" ../../app/src/main/jniLibs/arm64-v8a/
