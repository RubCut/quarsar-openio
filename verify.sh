#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERIAL="${ADB_SERIAL:-${1:-192.168.1.64:5555}}"
PKG="quasar.rubcut.openio"
FAIL=0

adb_s() { adb -s "$SERIAL" "$@"; }

check() {
    local name="$1"
    shift
    if "$@"; then
        echo "OK   $name"
    else
        echo "FAIL $name"
        FAIL=$((FAIL + 1))
    fi
}

echo "== device =="
adb_s wait-for-device

check "package installed" adb_s shell "pm path $PKG | grep -q package:"
check "root available" bash -c "adb -s '$SERIAL' shell 'su -c id' | grep -q 'uid=0(root)'"
check "service running" adb_s shell "ps -A | grep -q $PKG"
check "service process is foreground service" bash -c "adb -s '$SERIAL' shell 'dumpsys activity services $PKG' | grep -q QuasarService"

adb_s forward tcp:18080 tcp:8080 >/dev/null
AUTOSTART_ENABLED=$(curl -sS -m 5 http://127.0.0.1:18080/api/status \
    | python3 -c 'import json,sys; print(str(json.load(sys.stdin).get("autostart",{}).get("enabled", False)).lower())' \
    2>/dev/null || echo unknown)
echo "   autostart.enabled=$AUTOSTART_ENABLED"
if [ "$AUTOSTART_ENABLED" = "true" ]; then
    check "rc installed" adb_s shell "test -f /odm/etc/init/quasar-openio.rc"
    check "launcher installed" bash -c "adb -s '$SERIAL' shell 'su -c \"test -x /odm/quasar-openio/quasar-openio-launcher\"'"
else
    check "rc removed (autostart off)" bash -c "! adb -s '$SERIAL' shell 'test -f /odm/etc/init/quasar-openio.rc'"
    check "launcher removed (autostart off)" bash -c "! adb -s '$SERIAL' shell 'su -c \"test -e /odm/quasar-openio\"'"
fi
check "/odm is read-only" bash -c "adb -s '$SERIAL' shell 'su -c \"grep -E \\\" /odm \\\" /proc/mounts\"' | grep -q ' ro,'"

echo "== io.sdk =="
adb_s shell 'su -c "for p in \$(pidof com.yandex.io.sdk); do echo -n \"\$p \"; awk \"{print \\\$3}\" /proc/\$p/stat; done"' | sed 's/^/   /' || true

echo "== framebuffer commit =="
fb_hash() {
    adb -s "$SERIAL" shell 'su -c "dd if=/dev/graphics/fb4 bs=1200 count=1 2>/dev/null | sha256sum"' 2>/dev/null
}
# Sample several times: the clock colon blinks once per second, so a single
# 1 s gap can land on the same phase.
seen=""
for _ in 1 2 3 4 5 6; do
    seen="$seen $(fb_hash)"
    sleep 0.4
done
distinct=$(printf '%s\n' $seen | sort -u | wc -l)
if [ "$distinct" -gt 1 ]; then
    echo "OK   fb4 changes over time (mmap + FBIOPAN_DISPLAY)"
else
    echo "FAIL fb4 frame is static"
    FAIL=$((FAIL + 1))
fi

echo "== HTTP =="
adb_s forward tcp:18080 tcp:8080 >/dev/null
if curl -sS -m 5 "http://127.0.0.1:18080/api/status" -o /tmp/opencode/quasar-status.json; then
    echo "OK   /api/status"
    python3 -m json.tool /tmp/opencode/quasar-status.json | sed -n '1,80p'
else
    echo "FAIL /api/status"
    FAIL=$((FAIL + 1))
fi

echo "== audio route =="
check "APP RECORD_AUDIO granted" bash -c "adb -s '$SERIAL' shell 'dumpsys package quasar.rubcut.openio' | grep -q 'RECORD_AUDIO: granted=true'"
check "io.sdk RECORD_AUDIO kept" bash -c "adb -s '$SERIAL' shell 'dumpsys package com.yandex.io.sdk' | grep -q 'RECORD_AUDIO: granted=true'"
# The Visualizer effect is opened only while the visualiser is on screen, so
# temporarily switch to oscilloscope mode and restore the original one later.
ORIGINAL_MODE=$(curl -sS -m 5 http://127.0.0.1:18080/api/status \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("mode", "clock"))' \
    2>/dev/null || echo clock)
curl -sS -m 5 -X POST http://127.0.0.1:18080/api/mode \
    -d '{"mode":"oscilloscope"}' >/dev/null 2>&1 || true
sleep 1.5
# Never call `dumpsys media.audio_flinger` here: on this firmware it crashes
# the audio HAL and audioserver cannot recover without a reboot.
if python3 - <<'PY'
import json
import struct
import time
import urllib.request

base = "http://127.0.0.1:18080"
# The capture source taps the playback mix through the Android Visualizer
# effect (session 0), so silence is expected until something is playing.
# Drive a short tone through the normal output path to make the check
# deterministic. The effect reports at most 20 callbacks/s, ~371 samples
# each, so 700 ms yields roughly 5 000 samples rather than 11 200.
request = urllib.request.Request(
    base + "/api/tone",
    data=json.dumps({"frequency": 1000, "ms": 1500}).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
urllib.request.urlopen(request, timeout=5).read()
time.sleep(0.25)

data = urllib.request.urlopen(base + "/api/mic.pcm?ms=700", timeout=20).read()
samples = struct.unpack("<%dh" % (len(data) // 2), data[: len(data) // 2 * 2])
peak = max(abs(v) for v in samples) if samples else 0
assert len(samples) >= 2000, len(samples)
assert peak > 0, peak
PY
then
    echo "OK   /api/mic.pcm captures playback (non-zero PCM)"
else
    echo "FAIL /api/mic.pcm returns only zeros"
    FAIL=$((FAIL + 1))
fi

echo "== render settings =="
if python3 - <<'PY'
import json
import urllib.request

base = "http://127.0.0.1:18080"
current = json.load(urllib.request.urlopen(base + "/api/render", timeout=5))
payload = {key: current[key] for key in (
    "ring_color", "ring_enabled", "ring_spectrum", "scope_fps",
    "scope_sensitivity", "scope_smooth", "scope_band", "ring_origin",
)}
request = urllib.request.Request(
    base + "/api/render",
    data=json.dumps(payload).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
result = json.load(urllib.request.urlopen(request, timeout=5))
assert result.get("ok") is True, result
assert str(result.get("ring_color", "")).upper() == str(current.get("ring_color", "")).upper(), (current, result)
assert bool(result.get("ring_enabled")) == bool(current.get("ring_enabled")), (current, result)
assert result.get("ring_spectrum") is False, result
assert str(result.get("ring_direction")) == "clockwise", result
assert int(result.get("scope_fps", -1)) == int(current.get("scope_fps", -2)), (current, result)
assert abs(float(result.get("scope_sensitivity", -1)) - float(current.get("scope_sensitivity", -2))) < 1e-6, (current, result)
assert int(result.get("scope_smooth", -1)) == int(current.get("scope_smooth", -2)), (current, result)
assert str(result.get("scope_band")) == str(current.get("scope_band")), (current, result)
assert int(result.get("ring_origin", -1)) == int(current.get("ring_origin", -2)), (current, result)
assert str(result.get("scope_style")) == "wave", result
PY
then
    echo "OK   /api/render round-trip"
else
    echo "FAIL /api/render round-trip"
    FAIL=$((FAIL + 1))
fi

echo "== mods =="
if python3 - <<'PY'
import json
import time
import urllib.request

base = "http://127.0.0.1:18080"


def get(path):
    return json.load(urllib.request.urlopen(base + path, timeout=20))


def post(path, payload):
    request = urllib.request.Request(
        base + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    return json.load(urllib.request.urlopen(request, timeout=10))


data = get("/api/mods")
mods = data["mods"]
assert data["count"] >= 2, data["count"]
assert sum(len(mod["items"]) for mod in mods) >= 200

items = [item for mod in mods for item in mod["items"]]
by_kind = {}
for item in items:
    by_kind.setdefault(item["kind"], []).append(item)
assert len(by_kind.get("screen", [])) >= 100, len(by_kind.get("screen", []))
assert len(by_kind.get("ring", [])) >= 100, len(by_kind.get("ring", []))
assert {"screen", "ring", "script", "service"} <= set(by_kind), sorted(by_kind)
for item in by_kind["screen"]:
    assert item.get("slot") in ("clock", "visualizer", "animation"), item

status = get("/api/status")
assert "auto" in status and "mods" in status, sorted(status)
assert "scope_band" in status["render"] and "ring_origin" in status["render"]
assert status["mods"]["count"] >= 2, status["mods"]
assert status["mods"]["by_kind"]["ring"] >= 100, status["mods"]

ring_feature = get("/api/status")["features"]["ring"]
if not ring_feature:
    assert post("/api/features", {"ring": True})["ok"] is True
ring = by_kind["ring"][0]
result = post("/api/mods/activate", {"item": ring["key"], "loop": True})
assert result["ok"] is True, result
assert result["kind"] == "ring", result
time.sleep(0.4)
assert get("/api/status")["ring"]["has_pattern"] is True
assert post("/api/ring/pattern", {})["ok"] is True
assert get("/api/status")["ring"]["has_pattern"] is False
if not ring_feature:
    assert post("/api/features", {"ring": False})["ok"] is True

visualizer = next(item for item in by_kind["screen"] if item["slot"] == "visualizer")
fields = {field["key"]: field["value"] for field in visualizer.get("config", [])}
if "band" in fields:
    original = fields["band"]
    fields["band"] = "mid"
    result = post("/api/mods/config", {"provider": visualizer["key"], "values": fields})
    assert result["ok"] is True, result
    assert result["config"]["band"] == "mid", result
    fields["band"] = original
    post("/api/mods/config", {"provider": visualizer["key"], "values": fields})
PY
then
    echo "OK   /api/mods: mods/items by kind+slot, ring activation, config editing, status block"
else
    echo "FAIL /api/mods"
    FAIL=$((FAIL + 1))
fi

echo "== original assets (clock / animations / spectrum) =="
if python3 - <<'PY'
import json
import time
import urllib.request

base = "http://127.0.0.1:18080"


def get(path):
    return json.load(urllib.request.urlopen(base + path, timeout=5))


def post(path, payload):
    request = urllib.request.Request(
        base + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    return json.load(urllib.request.urlopen(request, timeout=5))


status = get("/api/status")
render = status["render"]
original_size = render.get("clock_size", "large")

catalog = get("/api/animations")
assert catalog.get("count", 0) >= 100, catalog.get("count")
clips = {clip for group in catalog["groups"] for clip in group["clips"]}
assert "video.gif" in clips, sorted(clips)[:5]

# The stock clips are anti-aliased: a thresholded renderer shows only two
# values here, so more than 20 distinct colours proves grey levels survive.
post("/api/animations", {"id": "video.gif", "loop": True})
time.sleep(0.35)
raw = urllib.request.urlopen(base + "/api/frame.rgb", timeout=5).read()
assert len(raw) == 25 * 16 * 3, len(raw)
distinct = len({raw[i:i + 3] for i in range(0, len(raw), 3)})
assert distinct > 20, distinct
post("/api/animations/stop", {})

for size in ("middle", "small", "large"):
    result = post("/api/clock", {"size": size})
    assert result.get("clock_size") == size, result
post("/api/clock", {"size": original_size})

assert isinstance(render.get("ring_spectrum"), bool), render
assert render.get("ring_spectrum") is False, render
assert render.get("scope_style") == "wave", render
assert int(render.get("scope_smooth", -1)) >= 0, render
assert str(render.get("ring_direction")) == "clockwise", render
status_auto = status.get("auto", {})
assert isinstance(status_auto.get("playing"), bool), status_auto
general = get("/api/general")
assert isinstance(general.get("mic_passthrough"), bool), general
PY
then
    echo "OK   105 stock clips, grey shades preserved, clock sizes, single wave visualiser"
else
    echo "FAIL original asset checks"
    FAIL=$((FAIL + 1))
fi

echo "== autostart toggle =="
if python3 - "$AUTOSTART_ENABLED" <<'PY'
import json
import sys
import urllib.request

base = "http://127.0.0.1:18080"
enabled = sys.argv[1] == "true"
request = urllib.request.Request(
    base + "/api/autostart",
    data=json.dumps({"enabled": enabled}).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
result = json.load(urllib.request.urlopen(request, timeout=10))
assert result.get("ok") is True, result
assert bool(result.get("enabled")) is enabled, result
assert bool(result.get("installed")) is enabled, result
PY
then
    echo "OK   /api/autostart round-trip (enabled=$AUTOSTART_ENABLED)"
else
    echo "FAIL /api/autostart round-trip"
    FAIL=$((FAIL + 1))
fi

echo "== web UI =="
if curl -sS -m 5 http://127.0.0.1:18080/ | grep -q "Quasar OpenIO" &&
   curl -sS -m 5 http://127.0.0.1:18080/ | grep -q "Моды и предметы" &&
   curl -sS -m 5 http://127.0.0.1:18080/ | grep -q 'id="scopeSmooth"' &&
   curl -sS -m 5 http://127.0.0.1:18080/ | grep -q 'id="micPassthrough"' &&
   curl -sS -m 5 http://127.0.0.1:18080/app.js | grep -q "/api/mods" &&
   curl -sS -m 5 http://127.0.0.1:18080/app.js | grep -q "renderMods" &&
   curl -sS -m 5 http://127.0.0.1:18080/app.js | grep -q "scope_smooth" &&
   curl -sS -m 5 http://127.0.0.1:18080/style.css | grep -q -- "--primary" &&
   curl -sS -m 5 http://127.0.0.1:18080/style.css | grep -q "max-width: 640px"; then
    echo "OK   web UI served (:8080, simpler tabs, phone layout)"
else
    echo "FAIL web UI"
    FAIL=$((FAIL + 1))
fi

echo "== events (last 20) =="
adb_s shell "logcat -d -s QuasarService QuasarNative QuasarHttp QuasarIpcTcp QuasarVisualizer QuasarAudioOut | tail -20" || true

if [ -n "${ORIGINAL_MODE:-}" ]; then
    curl -sS -m 5 -X POST http://127.0.0.1:18080/api/mode \
        -d "{\"mode\":\"$ORIGINAL_MODE\"}" >/dev/null 2>&1 || true
    echo "   mode restored to $ORIGINAL_MODE"
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "VERIFY OK"
else
    echo "VERIFY FAILED: $FAIL check(s)"
    exit 1
fi