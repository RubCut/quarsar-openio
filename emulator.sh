#!/usr/bin/env bash
# Desktop panel emulator: runs the real OpenIO engine with a synthetic audio
# source and draws the 25x16 panel + 24-LED ring in a Swing window.
# The station is not required (it can even be switched off): everything
# renders locally. Alice / voice features are not used at all.
#
#   ./emulator.sh                       # GUI
#   ./emulator.sh --selftest TONE_440   # headless ASCII check
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/home/rubcut/.jdks/temurin-17}"

cd "$ROOT"
LAUNCHER="emulator/build/install/emulator/bin/emulator"
if [ ! -x "$LAUNCHER" ] || [ "${REBUILD:-0}" = "1" ]; then
    ./gradlew --no-daemon :emulator:installDist
fi

if [ "${1:-}" = "--selftest" ]; then
    exec "$LAUNCHER" -Djava.awt.headless=true "$@"
fi
exec "$LAUNCHER" "$@"
