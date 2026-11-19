#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/home/rubcut/pd-work/AndroidTools/toolchain/sdk}"
NDK="${ANDROID_NDK_ROOT:-/home/rubcut/android-ndk-r26d}"
JAVA_HOME="${JAVA_HOME:-/home/rubcut/.jdks/temurin-17}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang"
LAUNCHER="$ROOT/app/src/main/assets/launcher/quasar-openio-launcher"
APK_OUT="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
DIST="$ROOT/dist"

export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export ANDROID_NDK_ROOT="$NDK"
export JAVA_HOME

echo "== launcher =="
mkdir -p "$(dirname "$LAUNCHER")"
"$CC" -O2 -fPIE -pie -Wall -Wextra -Werror \
    "$ROOT/app/src/main/cpp/quasar_launcher.c" -o "$LAUNCHER"
chmod 0755 "$LAUNCHER"
file "$LAUNCHER" | sed 's/^/   /'
sha256sum "$LAUNCHER"

echo "== domain tests + debug APK =="
cd "$ROOT"
./gradlew --no-daemon :domain:test :app:assembleDebug

echo "== dist =="
mkdir -p "$DIST"
cp -f "$APK_OUT" "$DIST/quasar-openio-debug.apk"
cp -f "$LAUNCHER" "$DIST/quasar-openio-launcher"
( cd "$DIST" && sha256sum quasar-openio-debug.apk quasar-openio-launcher | tee SHA256SUMS )
echo "APK: $DIST/quasar-openio-debug.apk"