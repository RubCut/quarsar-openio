#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERIAL="${ADB_SERIAL:-${1:-192.168.1.64:5555}}"
APK="${APK_PATH:-$ROOT/dist/quasar-openio-debug.apk}"
PKG="quasar.rubcut.openio"

if [ ! -f "$APK" ]; then
    echo "APK not found: $APK (run ./build.sh first)" >&2
    exit 1
fi

adb_s() { adb -s "$SERIAL" "$@"; }

echo "== device =="
adb_s wait-for-device
adb_s shell 'getprop ro.product.model; getprop ro.build.version.release'
adb_s shell 'su -c id' | sed 's/^/   /'

echo "== safety: resume io.sdk before replacing the APK =="
adb_s shell 'su -c "for p in \$(pidof com.yandex.io.sdk); do kill -CONT \$p; done; echo CONT_OK"' || true

echo "== stop previous service =="
adb_s shell "am stopservice -n $PKG/.service.QuasarService" >/dev/null 2>&1 || true
adb_s shell "am force-stop $PKG" >/dev/null 2>&1 || true

echo "== install =="
adb_s install -r -d "$APK"

echo "== launch setup =="
adb_s shell "am start -n $PKG/.MainActivity --ez setup true"
sleep 6

echo "== forward and status =="
adb_s forward tcp:18080 tcp:8080 >/dev/null
echo "--- /api/status ---"
curl -sS -m 5 "http://127.0.0.1:18080/api/status" || true
echo
echo "--- service process ---"
adb_s shell "ps -A | grep -E 'quasar.rubcut.openio|com.yandex.io.sdk'" || true
echo "--- io.sdk state ---"
adb_s shell 'su -c "for p in \$(pidof com.yandex.io.sdk); do echo -n \"\$p \"; awk \"{print \\\$3}\" /proc/\$p/stat; done"' || true

echo "== done =="
echo "Web UI: http://<station-ip>:8080"
echo "Setup screen: adb -s $SERIAL shell am start -n $PKG/.MainActivity"