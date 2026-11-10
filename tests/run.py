#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host-only tests. No real device node is opened and no real process is signalled."""
from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import select
import signal
import socket
import struct
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
WARNINGS = ["-Wall", "-Wextra", "-Wpedantic", "-Wshadow", "-Wformat=2",
            "-Wconversion", "-Wsign-conversion", "-Werror"]


def wait_for(predicate, message: str, timeout: float = 5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(0.01)
    raise AssertionError(message)


def read_line(sock: socket.socket) -> dict:
    data = bytearray()
    while b"\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            raise AssertionError(f"EOF before JSON response: {data!r}")
        data.extend(chunk)
        if len(data) > 16384:
            raise AssertionError("oversized response")
    return json.loads(data.split(b"\n", 1)[0])


def command(path: str, value: dict) -> dict:
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(3)
        sock.connect(path)
        sock.sendall(json.dumps(value).encode() + b"\n")
        with sock.makefile("rb") as stream:
            for line in stream:
                response = json.loads(line)
                if "event" not in response:
                    return response
        raise AssertionError("EOF without command response")


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def http_header(sock: socket.socket) -> tuple[bytes, bytes]:
    data = bytearray()
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            raise AssertionError("HTTP EOF before headers")
        data.extend(chunk)
        assert len(data) < 8192
    header, body = bytes(data).split(b"\r\n\r\n", 1)
    return header, body


def read_exact(sock: socket.socket, count: int, initial: bytes = b"") -> bytes:
    data = bytearray(initial)
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise AssertionError(f"HTTP EOF: wanted {count}, got {len(data)}")
        data.extend(chunk)
    return bytes(data)


def http_get(port: int, path: str, rcvbuf: int | None = None):
    sock = socket.socket()
    sock.settimeout(3)
    if rcvbuf is not None:
        # Must be set before connect so the advertised window is small. Some
        # host-only loopback stacks drop an unpaced 256 KiB server burst.
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
    sock.connect(("127.0.0.1", port))
    sock.sendall(f"GET {path} HTTP/1.1\r\nHost: localhost\r\n\r\n".encode())
    return sock, *http_header(sock)


def input_event(kind: int, code: int, value: int) -> bytes:
    return struct.pack("@llHHi", 0, 0, kind, code, value)


def cpu_ticks(pid: int) -> int:
    text = Path(f"/proc/{pid}/stat").read_text()
    fields = text[text.rfind(")") + 2:].split()
    return int(fields[11]) + int(fields[12])


class Daemon:
    def __init__(self, binary: Path, fixture: Path, env: dict, socket_path: str,
                 log_path: Path, audio: bool = True, dry: bool = False):
        self.socket_path = socket_path
        self.log_path = log_path
        self.log = log_path.open("wb")
        args = [str(binary), "--socket", socket_path, "--no-plugin"]
        self.pcm_port = free_port()
        self.http_port = free_port()
        if audio:
            args += ["--audio-port", str(self.pcm_port), "--http-port", str(self.http_port)]
        else:
            args += ["--no-audio"]
        if dry:
            args += ["--dry-run"]
        environment = os.environ.copy()
        environment.update(env)
        environment.setdefault("LD_PRELOAD", str(fixture))
        environment["ASAN_OPTIONS"] = "detect_leaks=1:abort_on_error=1"
        environment["UBSAN_OPTIONS"] = "halt_on_error=1:print_stacktrace=1"
        self.process = subprocess.Popen(args, stdout=subprocess.DEVNULL,
                                        stderr=self.log, env=environment)

    def wait_ready(self):
        wait_for(lambda: Path(self.socket_path).exists() and
                 "IPC listening" in self.log_path.read_text(errors="replace"), "daemon startup")
        assert self.process.poll() is None, self.log_path.read_text(errors="replace")

    def stop(self, kill: bool = False):
        if self.process.poll() is None:
            self.process.send_signal(signal.SIGKILL if kill else signal.SIGTERM)
        self.process.wait(timeout=8)
        self.log.close()
        assert "AddressSanitizer" not in self.log_path.read_text(errors="replace")
        assert "runtime error:" not in self.log_path.read_text(errors="replace")
        if not kill:
            assert self.process.returncode == 0, self.log_path.read_text(errors="replace")


def integration(binary: Path, fixture: Path, root: Path, sanitize: bool = False):
    asan = subprocess.run([os.environ.get("CC", "cc"), "-print-file-name=libasan.so"],
                          capture_output=True, text=True, check=True).stdout.strip() if sanitize else None
    preload = f"{asan}:{fixture}" if asan else str(fixture)
    paths = {name: root / name for name in
             ["fb4", "fb5", "panel", "rotary", "remote", "daemon.lock", "sdk.journal", "signals"]}
    paths["fb4"].write_bytes(b"\xa5" * (96 * 16))
    paths["fb5"].write_bytes(b"\xa5" * 80)
    for name in ["panel", "rotary", "remote"]:
        os.mkfifo(paths[name])
    writers = {name: os.open(paths[name], os.O_RDWR | os.O_NONBLOCK)
               for name in ["panel", "rotary", "remote"]}
    proc = root / "proc"
    process_dir = proc / "424242"
    process_dir.mkdir(parents=True)
    (proc / "sys/kernel/random").mkdir(parents=True)
    boot_id = str(uuid.uuid4())
    (proc / "sys/kernel/random/boot_id").write_text(boot_id + "\n")
    cmdline = process_dir / "cmdline"
    cmdline.write_bytes(b"com.yandex.io.sdk\x00--service\x00")
    fields = ["424242", "(com.yandex.io.sdk)", "S"] + ["0"] * 50
    fields[21] = "987654321"
    stat_path = process_dir / "stat"
    stat_path.write_text(" ".join(fields))
    env = {
        "QIO_TEST_FB4": str(paths["fb4"]), "QIO_TEST_FB5": str(paths["fb5"]),
        "QIO_TEST_FB4_STRIDE": "96", "QIO_TEST_FB5_STRIDE": "80",
        "QIO_TEST_FB4_ORDER": "bgr", "QIO_TEST_PANEL": str(paths["panel"]),
        "QIO_TEST_ROTARY": str(paths["rotary"]), "QIO_TEST_REMOTE": str(paths["remote"]),
        "QIO_TEST_SIGNAL_LOG": str(paths["signals"]), "QIO_TEST_PROC_STAT": str(stat_path),
        "LD_PRELOAD": preload,
    }
    socket_path = str(root / "q.sock")
    daemon = Daemon(binary, fixture, env, socket_path, root / "daemon.log")
    try:
        daemon.wait_ready()
        assert Path(socket_path).stat().st_mode & 0o777 == 0o600
        duplicate = subprocess.run([str(binary), "--socket", socket_path, "--no-audio"],
                                   env={**os.environ, **env, "LD_PRELOAD": env["LD_PRELOAD"]},
                                   capture_output=True, timeout=3)
        assert duplicate.returncode == 1 and b"lock busy" in duplicate.stderr
        assert command(socket_path, {"action": "status"})["ok"] is True
        duplicate_sdk = proc / "424243"
        duplicate_sdk.mkdir()
        (duplicate_sdk / "cmdline").write_bytes(b"com.yandex.io.sdk\x00")
        duplicate_fields = fields.copy()
        duplicate_fields[0] = "424243"
        duplicate_fields[21] = "987654322"
        (duplicate_sdk / "stat").write_text(" ".join(duplicate_fields))
        assert command(socket_path, {"action": "status"})["sdk"]["state"] == "ambiguous"
        (duplicate_sdk / "cmdline").unlink()
        (duplicate_sdk / "stat").unlink()
        duplicate_sdk.rmdir()
        assert command(socket_path, {"action": "wat"})["error"] == "unknown_action"
        assert command(socket_path, {"action": "status", "command": "reboot"})["error"] == "invalid_request"
        # Every complete line already read from the socket must be dispatched;
        # a ninth short command used to wait forever for a tenth write.
        with socket.socket(socket.AF_UNIX) as sock:
            sock.settimeout(3)
            sock.connect(socket_path)
            sock.sendall(b'{"action":"status"}\n' * 9)
            with sock.makefile("rb") as stream:
                replies = [json.loads(stream.readline()) for _ in range(9)]
            assert all(reply["ok"] for reply in replies)
        with socket.socket(socket.AF_UNIX) as sock:
            sock.settimeout(3)
            sock.connect(socket_path)
            sock.sendall(b"x" * 5000)
            assert read_line(sock)["error"] == "request_too_long"

        # SIGSTOP/CONT are captured by the fixture; PID identity is rechecked.
        assert command(socket_path, {"action": "pause_sdk"})["ok"]
        wait_for(lambda: paths["signals"].exists() and paths["signals"].read_text() == "424242 19\n", "STOP")
        assert paths["sdk.journal"].exists()
        status = command(socket_path, {"action": "status"})
        assert status["sdk"]["state"] == "stopped" and status["sdk"]["owned_stop"]
        assert command(socket_path, {"action": "pause_sdk"})["error"] == "sdk_already_stopped"
        assert command(socket_path, {"action": "resume_sdk"})["ok"]
        assert paths["signals"].read_text().splitlines() == ["424242 19", "424242 18"]
        assert not paths["sdk.journal"].exists()
        assert command(socket_path, {"action": "pause_sdk"})["ok"]
        cmdline.write_bytes(b"some.other.process\x00")
        assert command(socket_path, {"action": "resume_sdk"})["error"] == "sdk_identity_changed"
        assert paths["signals"].read_text().count("18") == 1
        cmdline.write_bytes(b"com.yandex.io.sdk\x00")
        assert command(socket_path, {"action": "resume_sdk"})["ok"]

        # Actual mmap writes through the production validator; padding untouched.
        frame = bytes(i % 256 for i in range(1200))
        reply = command(socket_path, {"action": "draw_screen", "data": base64.b64encode(frame).decode()})
        assert reply == {"ok": True, "queued": True}
        expected = bytearray(b"\xa5" * (96 * 16))
        for y in range(16):
            for x in range(25):
                source = (y * 25 + x) * 3
                expected[y * 96 + x * 3:y * 96 + x * 3 + 3] = frame[source:source + 3][::-1]
        wait_for(lambda: paths["fb4"].read_bytes() == bytes(expected), "screen mmap/stride/BGR")
        ring = bytes(range(72))
        assert command(socket_path, {"action": "draw_ring", "data": base64.b64encode(ring).decode()})["ok"]
        wait_for(lambda: paths["fb5"].read_bytes() == ring + b"\xa5" * 8, "ring mmap/padding")
        before = paths["fb4"].read_bytes()
        assert command(socket_path, {"action": "draw_screen", "data": "!"})["error"] == "invalid_frame_data"
        assert command(socket_path, {"action": "draw_ring", "data": base64.b64encode(b"short").decode()})["error"] == "invalid_frame_data"
        assert paths["fb4"].read_bytes() == before
        final = b"\x10\x20\x30" * 400
        burst = (base64.b64encode(b"\x01\x02\x03" * 400).decode() + "\n")
        with socket.socket(socket.AF_UNIX) as sock:
            sock.settimeout(5)
            sock.connect(socket_path)
            payload = b"".join((json.dumps({"action": "draw_screen", "data": burst.strip()}) + "\n").encode()
                               for _ in range(99))
            payload += (json.dumps({"action": "draw_screen", "data": base64.b64encode(final).decode()}) + "\n").encode()
            sock.sendall(payload)
            with sock.makefile("rb") as responses:
                for _ in range(100):
                    assert json.loads(responses.readline())["ok"]
        expected = bytearray(b"\xa5" * (96 * 16))
        for y in range(16):
            for x in range(25):
                expected[y * 96 + x * 3:y * 96 + x * 3 + 3] = b"\x30\x20\x10"
        wait_for(lambda: paths["fb4"].read_bytes() == bytes(expected), "latest-frame-wins")

        # Non-exclusive input observation, including switch and rotary releases.
        subscriber = socket.socket(socket.AF_UNIX)
        subscriber.settimeout(3)
        subscriber.connect(socket_path)
        os.write(writers["panel"], input_event(1, 582, 1) + input_event(1, 582, 0) +
                 input_event(5, 14, 1) + input_event(5, 14, 0))
        os.write(writers["rotary"], input_event(1, 104, 1) + input_event(1, 104, 0))
        os.write(writers["remote"], input_event(1, 115, 1) + input_event(1, 115, 0))
        with subscriber.makefile("rb") as stream:
            events = [json.loads(stream.readline())["event"] for _ in range(7)]
        assert events == ["button_alice_pressed", "button_alice_released",
                          "microphone_mute_on", "microphone_mute_off", "volume_up",
                          "volume_up", "volume_up_released"], events
        subscriber.close()

        # A streaming client cannot reserve the only consumer slot forever
        # while no PCM producer exists.
        idle_consumer, headers, _ = http_get(daemon.http_port, "/audio.pcm")
        assert b"200 OK" in headers
        idle_consumer.settimeout(2)
        assert idle_consumer.recv(1) == b""
        idle_consumer.close()

        # PCM -> bounded ring -> HTTP WAV, including frame-aligned overflow.
        pcm = socket.create_connection(("127.0.0.1", daemon.pcm_port), timeout=3)
        pattern = bytes(range(256)) * 16  # 4096 bytes, complete S16LE stereo frames
        pcm.sendall(pattern)
        consumer, headers, extra = http_get(daemon.http_port, "/audio.wav")
        assert b"200 OK" in headers and b"audio/wav" in headers, headers
        wav = read_exact(consumer, 44, extra)
        assert wav[:4] == b"RIFF" and wav[8:12] == b"WAVE" and wav[36:40] == b"data"
        assert struct.unpack_from("<I", wav, 24)[0] == 48000
        assert struct.unpack_from("<H", wav, 22)[0] == 2
        assert read_exact(consumer, len(pattern), extra[44:]) == pattern
        consumer.close()
        health, headers, body = http_get(daemon.http_port, "/health")
        length = int(next(line.split(b":", 1)[1] for line in headers.split(b"\r\n")
                          if line.lower().startswith(b"content-length:")))
        health_json = json.loads(read_exact(health, length, body))
        assert health_json["producer_connected"] is True
        health.close()
        extra_producer = socket.create_connection(("127.0.0.1", daemon.pcm_port), timeout=3)
        wait_for(lambda: command(socket_path, {"action": "status"})["audio"]["rejected_producers"] >= 1,
                 "second producer rejection")
        extra_producer.close()
        total = bytes(range(256)) * 1600  # 409600 bytes > 262144-byte ring
        # Real PCM producers write small buffers. Pacing the upload also keeps
        # host-only loopback buffers from dropping a single large burst.
        for offset in range(0, len(total), 4096):
            pcm.sendall(total[offset:offset + 4096])
            time.sleep(0.005)
        status = wait_for(lambda: (lambda s: s if s["audio"]["dropped_bytes"] > 0 and
                                   # Wait until the whole producer burst has been
                                   # ingested so the ring snapshot cannot change
                                   # while the HTTP consumer is reading it.
                                   s["audio"]["accepted_bytes"] >= 4096 + len(total) else None)(
            command(socket_path, {"action": "status"})), "bounded audio overflow")
        assert status["audio"]["buffered_bytes"] <= 262144
        assert status["audio"]["buffered_bytes"] % 4 == 0
        consumer, headers, extra = http_get(daemon.http_port, "/audio.wav", rcvbuf=8192)
        buffered = status["audio"]["buffered_bytes"]
        body = read_exact(consumer, buffered, extra[44:])
        assert body == total[-buffered:]
        pcm.close()
        assert consumer.recv(1) == b""
        consumer.close()
        denied, headers, _ = http_get(daemon.http_port, "/missing")
        assert b"404" in headers
        denied.close()

        # A slow subscriber is disconnected instead of accumulating allocations.
        slow = socket.socket(socket.AF_UNIX)
        slow.connect(socket_path)
        storm = input_event(1, 582, 1) * 4096
        view = memoryview(storm)
        while view:
            ready = select.select([], [writers["panel"]], [], 3)[1]
            assert ready, "input FIFO write timeout"
            written = os.write(writers["panel"], view)
            view = view[written:]
        wait_for(lambda: command(socket_path, {"action": "status"})["lost_events"] > 0,
                 "slow subscriber backpressure", timeout=8)
        slow.settimeout(6)
        received = 0
        while True:
            chunk = slow.recv(65536)
            if not chunk:
                break
            received += len(chunk)
        assert received > 0  # bounded prefix/kernel buffer, then EOF
        slow.close()
        assert command(socket_path, {"action": "status"})["lost_events"] > 0
        ticks = cpu_ticks(daemon.process.pid)
        time.sleep(0.6)
        assert cpu_ticks(daemon.process.pid) - ticks <= 4, "idle CPU spin"
        daemon.stop()
        assert not Path(socket_path).exists()

        # Crash recovery resumes only the recorded PID/start time in the same boot.
        daemon = Daemon(binary, fixture, env, socket_path, root / "recovery.log")
        daemon.wait_ready()
        assert command(socket_path, {"action": "pause_sdk"})["ok"]
        previous = paths["signals"].read_text().count("18")
        daemon.stop(kill=True)
        assert paths["sdk.journal"].exists()
        daemon = Daemon(binary, fixture, env, socket_path, root / "recovered.log")
        daemon.wait_ready()
        assert paths["signals"].read_text().count("18") == previous + 1
        assert not paths["sdk.journal"].exists()
        assert command(socket_path, {"action": "status"})["sdk"]["recovered"]
        daemon.stop()

        # Explicit dry-run never writes the framebuffer or signals the SDK.
        before_fb = paths["fb4"].read_bytes()
        before_signals = paths["signals"].read_text()
        daemon = Daemon(binary, fixture, env, socket_path, root / "dry.log", audio=False, dry=True)
        daemon.wait_ready()
        assert command(socket_path, {"action": "pause_sdk"})["dry_run"]
        assert command(socket_path, {"action": "draw_screen", "data": base64.b64encode(frame).decode()})["ok"]
        time.sleep(0.05)
        assert paths["fb4"].read_bytes() == before_fb
        assert paths["signals"].read_text() == before_signals
        daemon.stop()
    finally:
        if daemon.process.poll() is None:
            daemon.process.kill()
            daemon.process.wait(timeout=3)
        if not daemon.log.closed:
            daemon.log.close()
        for fd in writers.values():
            os.close(fd)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    cc = os.environ.get("CC", "cc")
    build = ROOT / "build" / ("sanitize" if args.sanitize else "test")
    build.mkdir(parents=True, exist_ok=True)
    flags = ["-std=c11", "-O1", "-g", *WARNINGS]
    if args.sanitize:
        flags += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
    shared_flags = ["-std=c11", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
                    "-shared", "-fPIC"]
    if args.sanitize:
        shared_flags += ["-fsanitize=address,undefined"]
    subprocess.run([cc, *shared_flags, str(ROOT / "tests/libfixture.c"), "-ldl",
                    "-o", str(build / "libfixture.so")], check=True)
    subprocess.run([cc, *flags, "-Iinclude", "tests/test_core.c", "src/json.c",
                    "src/framebuffer.c", "src/util.c", "-o", str(build / "test_core")],
                   cwd=ROOT, check=True)
    subprocess.run([str(build / "test_core")], check=True)
    for script in (ROOT / "scripts").glob("*.sh"):
        subprocess.run(["sh", "-n", str(script)], check=True)
    with tempfile.TemporaryDirectory(prefix="qio-", dir="/tmp") as temporary:
        root = Path(temporary)
        defines = {
            "QIO_PANEL_PATH": root / "panel", "QIO_ROTARY_PATH": root / "rotary",
            "QIO_REMOTE_PATH": root / "remote", "QIO_SCREEN_PATH": root / "fb4",
            "QIO_RING_PATH": root / "fb5", "QIO_PROC_ROOT": root / "proc",
            "QIO_SDK_JOURNAL_PATH": root / "sdk.journal", "QIO_LOCK_PATH": root / "daemon.lock",
        }
        binary = build / "quasar_openio"
        subprocess.run([cc, *flags, "-Iinclude", *[str(p) for p in sorted((ROOT / "src").glob("*.c"))],
                        *[f"-D{key}={json.dumps(str(value))}" for key, value in defines.items()],
                        "-DQIO_HTTP_TIMEOUT_MS=300",
                        "-o", str(binary)], cwd=ROOT, check=True)
        try:
            integration(binary, build / "libfixture.so", root, args.sanitize)
        except Exception:
            print((root / "daemon.log").read_text(errors="replace"))
            for name in ["recovery.log", "recovered.log", "dry.log"]:
                if (root / name).exists():
                    print(name, (root / name).read_text(errors="replace"))
            print("fb4 prefix:", (root / "fb4").read_bytes()[:192].hex())
            if (root / "sdk.journal").exists():
                print("journal:", (root / "sdk.journal").read_bytes())
            raise
    import re
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", (ROOT / "src/framebuffer.c").read_text(), flags=re.S)
    assert "write(" not in source and "FBIOPUT" not in source and "msync(" not in source
    for path in (ROOT / "src").glob("*.c"):
        text = re.sub(r"/\*.*?\*/|//[^\n]*", "", path.read_text(), flags=re.S)
        assert "system(" not in text and "popen(" not in text
    print("integration: IPC, mmap stride/layout, input, SDK identity/recovery, audio bounds, shutdown OK")
    print("sanitizer run OK" if args.sanitize else "normal run OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
