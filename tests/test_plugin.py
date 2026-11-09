#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Supervisor tests: openio only launches plugin binaries and manages buttons/music.

The plugin owns both framebuffers. These tests assert that the supervisor passes
the screen/ring device paths and mode arguments, and that it NEVER writes fb5
itself while a plugin is loaded.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
WARNINGS = ["-Wall", "-Wextra", "-Wpedantic", "-Wshadow", "-Wformat=2",
            "-Wconversion", "-Wsign-conversion", "-Werror"]
REQUIRED = "music-play,music-pause,mic-on,mic-off,volume-up,volume-down,volume-ring"

MOCK_C = r'''
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
int main(int argc, char **argv)
{
    if (argc < 2) return 2;
    const char *mode = argv[1];
    const char *log = getenv("QIO_MOCK_LOG");
    FILE *f = log ? fopen(log, "a") : NULL;
    if (f) {
        fprintf(f, "%s\n", mode);
        fprintf(f, "argv");
        for (int i = 2; i < argc; ++i) fprintf(f, " %s", argv[i]);
        fprintf(f, "\n");
        fprintf(f, "fd200 %s\n", fcntl(200, F_GETFD) < 0 ? "closed" : "open");
        fclose(f);
    }
    if (strcmp(mode, "clock") == 0) {
        char line[256];
        while (fgets(line, sizeof(line), stdin)) {
            f = log ? fopen(log, "a") : NULL;
            if (f) { fprintf(f, "stdin %s", line); fclose(f); }
        }
        return 0;
    }
    if (strcmp(mode, "visualizer") == 0) {
        char line[256];
        while (fgets(line, sizeof(line), stdin)) {}
        return 0;
    }
    unsigned long ms = 350;
    for (int i = 2; i + 1 < argc; ++i)
        if (strcmp(argv[i], "--duration-ms") == 0) ms = strtoul(argv[i + 1], NULL, 10);
    struct timespec ts = {(time_t)(ms / 1000), (long)(ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
    return 0;
}
'''


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_for(predicate, message, timeout=8.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError(message)


def command(path, obj):
    with socket.socket(socket.AF_UNIX) as sock:
        sock.settimeout(4)
        sock.connect(str(path))
        sock.sendall(json.dumps(obj).encode() + b"\n")
        data = b""
        while not data.endswith(b"\n"):
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
    return json.loads(data)


def input_event(kind, code, value):
    return struct.pack("@llHHi", 0, 0, kind, code, value)


class PluginTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="qio-plugin-")
        cls.root = Path(cls.temp.name)
        cls.sanitize = os.environ.get("QIO_PLUGIN_SANITIZE") == "1"
        cc = os.environ.get("CC", "cc")
        flags = ["-std=c11", "-O1", "-g", *WARNINGS]
        shared = ["-std=c11", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-shared", "-fPIC"]
        if cls.sanitize:
            flags += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
            shared += ["-fsanitize=address,undefined"]
            cls.asan = subprocess.run([cc, "-print-file-name=libasan.so"], capture_output=True,
                                      text=True, check=True).stdout.strip()
        else:
            cls.asan = None
        cls.fixture = cls.root / "libfixture.so"
        subprocess.run([cc, *shared, str(ROOT / "tests/libfixture.c"), "-ldl",
                        "-o", str(cls.fixture)], check=True)
        cls.mock = cls.root / "mock_plugin"
        mock_c = cls.root / "mock_plugin.c"
        mock_c.write_text(MOCK_C)
        subprocess.run([cc, "-std=c11", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
                        str(mock_c), "-o", str(cls.mock)], check=True)
        cls.mock.chmod(0o755)
        cls.binary = cls.root / "quasar_openio"
        paths = {name: cls.root / name for name in
                 ["fb4", "fb5", "panel", "rotary", "remote", "daemon.lock", "sdk.journal", "proc"]}
        cls.paths = paths
        paths["fb4"].write_bytes(b"\xa5" * (96 * 16))
        paths["fb5"].write_bytes(b"\xa5" * 80)
        for name in ["panel", "rotary", "remote"]:
            os.mkfifo(paths[name])
        (paths["proc"] / "sys/kernel/random").mkdir(parents=True)
        (paths["proc"] / "sys/kernel/random/boot_id").write_text(str(uuid.uuid4()) + "\n")
        defines = [f"-DQIO_SCREEN_PATH=\"{paths['fb4']}\"", f"-DQIO_RING_PATH=\"{paths['fb5']}\"",
                   f"-DQIO_PANEL_PATH=\"{paths['panel']}\"", f"-DQIO_ROTARY_PATH=\"{paths['rotary']}\"",
                   f"-DQIO_REMOTE_PATH=\"{paths['remote']}\"", f"-DQIO_LOCK_PATH=\"{paths['daemon.lock']}\"",
                   f"-DQIO_SDK_JOURNAL_PATH=\"{paths['sdk.journal']}\"", f"-DQIO_PROC_ROOT=\"{paths['proc']}\"",
                   f"-DQIO_SOCKET_PATH=\"{cls.root / 'default.sock'}\"",
                   f"-DQIO_RUNTIME_DIR=\"{cls.root}\""]
        sources = [str(p) for p in sorted((ROOT / "src").glob("*.c"))]
        subprocess.run([cc, *flags, *defines, "-Iinclude", *sources, "-o", str(cls.binary)],
                       cwd=ROOT, check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.dir = self.root / self._testMethodName
        self.dir.mkdir(mode=0o700)
        self.plugin_dir = self.dir / "standard"
        self.plugin_dir.mkdir(mode=0o700)
        self.binary_path = self.plugin_dir / "mock_plugin"
        # A hard link would be rejected; copy the small mock executable.
        self.binary_path.write_bytes(self.mock.read_bytes())
        self.binary_path.chmod(0o755)
        (self.plugin_dir / "plugin.conf").write_text(
            "format=quasar-plugin-v1\nname=standard\nbinary=mock_plugin\n"
            "idle=clock\nmusic=visualizer\nanimations=" + REQUIRED + "\n")
        (self.plugin_dir / "plugin.conf").chmod(0o644)
        self.fb4 = self.paths["fb4"]
        self.fb5 = self.paths["fb5"]
        self.fb4.write_bytes(b"\xa5" * (96 * 16))
        self.fb5.write_bytes(b"\xa5" * 80)
        self.writers = {name: os.open(self.paths[name], os.O_RDWR | os.O_NONBLOCK)
                        for name in ["panel", "rotary", "remote"]}
        self.mock_log = self.dir / "mock.log"
        self.socket_path = self.dir / "q.sock"
        self.token_path = self.dir / "audio.token"
        self.log_path = self.dir / "daemon.log"
        self.log = self.log_path.open("wb")
        preload = f"{self.asan}:{self.fixture}" if self.asan else str(self.fixture)
        env = {**os.environ,
               "LD_PRELOAD": preload,
               "QIO_TEST_FB4": str(self.fb4), "QIO_TEST_FB5": str(self.fb5),
               "QIO_TEST_FB4_STRIDE": "96", "QIO_TEST_FB5_STRIDE": "80",
               "QIO_TEST_FB4_ORDER": "bgr", "QIO_TEST_FB5_ORDER": "rgb",
               "QIO_TEST_PANEL": str(self.paths["panel"]),
               "QIO_TEST_ROTARY": str(self.paths["rotary"]),
               "QIO_TEST_REMOTE": str(self.paths["remote"]),
               "QIO_MOCK_LOG": str(self.mock_log),
               "ASAN_OPTIONS": "detect_leaks=1:abort_on_error=1",
               "UBSAN_OPTIONS": "halt_on_error=1:print_stacktrace=1"}
        self.pcm_port = free_port()
        self.http_port = free_port()
        args = [str(self.binary), "--socket", str(self.socket_path),
                "--plugin-dir", str(self.plugin_dir),
                "--audio-token-file", str(self.token_path),
                "--audio-port", str(self.pcm_port), "--http-port", str(self.http_port)]
        # A descriptor above the old 3..127 sweep must not reach the plugin.
        self.high_fd = os.open(os.devnull, os.O_RDONLY)
        if self.high_fd != 200:
            os.dup2(self.high_fd, 200)
            os.close(self.high_fd)
            self.high_fd = 200
        os.set_inheritable(200, True)
        self.process = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=self.log,
                                        env=env, pass_fds=(200,))
        wait_for(lambda: self.socket_path.exists() and
                 "IPC listening" in self.log_path.read_text(errors="replace"), "daemon startup")
        wait_for(lambda: self.status()["plugin"]["child_mode"] == "clock", "idle clock launch")
        self.token = self.token_path.read_text().strip()

    def tearDown(self):
        if self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
        self.process.wait(timeout=8)
        self.log.close()
        for fd in self.writers.values():
            os.close(fd)
        os.close(200)
        text = self.log_path.read_text(errors="replace")
        self.assertNotIn("AddressSanitizer", text)
        self.assertNotIn("runtime error:", text)
        self.assertEqual(self.process.returncode, 0, text)

    def status(self):
        return command(self.socket_path, {"action": "status"})

    def mock_lines(self):
        return self.mock_log.read_text().splitlines() if self.mock_log.exists() else []

    def test_idle_launch_contract(self):
        status = self.status()["plugin"]
        self.assertTrue(status["loaded"])
        self.assertEqual(status["child_mode"], "clock")
        self.assertGreater(status["child_pid"], 0)
        wait_for(lambda: "clock" in self.mock_lines(), "idle plugin logged its launch")
        lines = self.mock_lines()
        self.assertIn("fd200 closed", lines, "inherited high descriptor leaked to plugin")
        argv = next(line for line in lines if line.startswith("argv "))
        self.assertIn(f"--screen {self.fb4}", argv)
        self.assertIn(f"--ring {self.fb5}", argv)
        self.assertIn("--no-ring", argv, "idle must be launched with the ring disabled")
        # The supervisor never draws: both framebuffers stay untouched by openio.
        self.assertEqual(self.fb5.read_bytes(), b"\xa5" * 80)
        self.assertEqual(self.fb4.read_bytes(), b"\xa5" * (96 * 16))
        self.assertEqual(command(self.socket_path, {"action": "draw_screen",
                                                    "data": base64.b64encode(bytes(1200)).decode()}),
                         {"ok": False, "error": "plugin_owns_output"})
        self.assertEqual(command(self.socket_path, {"action": "draw_ring",
                                                    "data": base64.b64encode(bytes(72)).decode()}),
                         {"ok": False, "error": "plugin_owns_output"})

    def test_animation_launch_gets_ring_device(self):
        reply = command(self.socket_path, {"action": "plugin_animation", "data": "volume-up"})
        self.assertEqual(reply, {"ok": True})
        wait_for(lambda: "volume-up" in self.mock_lines(), "volume-up launch")
        lines = self.mock_lines()
        index = lines.index("volume-up")
        wait_for(lambda: len(self.mock_lines()) > index + 1, "animation argv logged")
        argv = self.mock_lines()[index + 1]
        self.assertTrue(argv.startswith("argv "))
        self.assertIn(f"--ring {self.fb5}", argv)
        self.assertNotIn("--no-ring", argv, "animations must be allowed to use the ring")
        self.assertIn("--duration-ms 900", argv)
        wait_for(lambda: self.status()["plugin"]["child_mode"] == "clock", "return to clock")
        # The plugin owns fb5: the supervisor must not have written a single byte.
        self.assertEqual(self.fb5.read_bytes(), b"\xa5" * 80)
        self.assertEqual(self.status()["ring"]["frames"], 0)
        self.assertEqual(self.status()["ring"]["mapped"], False)

    def test_music_play_visualizer_pause_stop(self):
        self.assertEqual(command(self.socket_path, {"action": "music_state", "data": "playing"}),
                         {"ok": True})
        wait_for(lambda: "music-play" in self.mock_lines(), "music-play intro")
        wait_for(lambda: self.status()["plugin"]["child_mode"] == "visualizer", "visualizer after intro")
        self.assertEqual(self.status()["plugin"]["music_state"], "playing")
        self.assertEqual(command(self.socket_path, {"action": "music_state", "data": "paused"}),
                         {"ok": True})
        wait_for(lambda: "music-pause" in self.mock_lines(), "music-pause animation")
        wait_for(lambda: self.status()["plugin"]["child_mode"] == "clock", "clock after pause")
        self.assertEqual(command(self.socket_path, {"action": "music_state", "data": "stopped"}),
                         {"ok": True})
        self.assertEqual(command(self.socket_path, {"action": "music_state", "data": "bad"}),
                         {"ok": False, "error": "invalid_music_state"})
        self.assertEqual(command(self.socket_path, {"action": "plugin_animation", "data": "bad"}),
                         {"ok": False, "error": "invalid_animation"})

    def test_auto_music_from_pcm_and_silence_timeout(self):
        with socket.create_connection(("127.0.0.1", self.pcm_port), timeout=4) as sock:
            chunk = bytearray()
            for i in range(4096):
                chunk.extend(struct.pack("<hh", 12000, 12000))
            sock.sendall(f"AUTH {self.token}\n".encode())
            sock.sendall(chunk)
            wait_for(lambda: self.status()["plugin"]["music_state"] == "playing", "auto playing")
            wait_for(lambda: self.status()["plugin"]["child_mode"] == "visualizer", "auto visualizer")
        wait_for(lambda: self.status()["plugin"]["music_state"] == "stopped", "auto stopped", timeout=6)
        wait_for(lambda: self.status()["plugin"]["child_mode"] == "clock", "clock after silence")

    def test_buttons_forward_and_trigger_standard_overlays(self):
        # A non-overlay button must reach the running plugin over stdin.
        os.write(self.writers["remote"], input_event(1, 207, 1) + input_event(0, 0, 0))
        wait_for(lambda: any(line.startswith("stdin button ") and "play_pause" in line
                             for line in self.mock_lines()), "remote button forwarded")
        # Volume/mic buttons launch the mandatory standard overlays.
        os.write(self.writers["rotary"], input_event(1, 104, 1) + input_event(0, 0, 0))
        wait_for(lambda: "volume-up" in self.mock_lines(), "volume-up overlay")
        wait_for(lambda: self.status()["plugin"]["child_mode"] == "clock", "clock after volume-up")
        os.write(self.writers["rotary"], input_event(1, 109, 1) + input_event(0, 0, 0))
        wait_for(lambda: "volume-down" in self.mock_lines(), "volume-down overlay")
        wait_for(lambda: self.status()["plugin"]["child_mode"] == "clock", "clock after volume-down")
        os.write(self.writers["panel"], input_event(5, 14, 0) + input_event(0, 0, 0))
        wait_for(lambda: "mic-on" in self.mock_lines(), "mic-on overlay")
        os.write(self.writers["panel"], input_event(5, 14, 1) + input_event(0, 0, 0))
        wait_for(lambda: "mic-off" in self.mock_lines(), "mic-off overlay")

    def test_repeated_animation_restarts_child_with_full_duration(self):
        self.assertEqual(command(self.socket_path, {"action": "plugin_animation", "data": "volume-up"}),
                         {"ok": True})
        wait_for(lambda: "volume-up" in self.mock_lines(), "first volume-up launch")
        first_pid = self.status()["plugin"]["child_pid"]
        launches = self.status()["plugin"]["launches"]
        self.assertEqual(command(self.socket_path, {"action": "plugin_animation", "data": "volume-up"}),
                         {"ok": True})
        wait_for(lambda: self.mock_lines().count("volume-up") >= 2, "repeated animation relaunched")
        wait_for(lambda: self.status()["plugin"]["launches"] > launches, "restart counted")
        second_pid = self.status()["plugin"]["child_pid"]
        self.assertNotEqual(first_pid, second_pid)
        argv = [line for line in self.mock_lines() if line.startswith("argv ")][-1]
        self.assertIn("--duration-ms 900", argv)
        wait_for(lambda: self.status()["plugin"]["child_mode"] == "clock", "clock after repeated overlay")

    def test_plugin_privilege_options_require_pair(self):
        for args in (["--plugin-uid", "65534"], ["--plugin-gid", "65534"],
                     ["--plugin-uid", "0", "--plugin-gid", "65534"]):
            result = subprocess.run([str(self.binary), *args], capture_output=True, timeout=3)
            self.assertEqual(result.returncode, 2)

    def test_shutdown_reaps_child(self):
        pid = self.status()["plugin"]["child_pid"]
        self.assertGreater(pid, 0)
        self.process.send_signal(signal.SIGTERM)
        self.process.wait(timeout=8)
        wait_for(lambda: not Path(f"/proc/{pid}").exists(), "child reaped")
        self.assertEqual(self.process.returncode, 0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    if args.sanitize:
        os.environ["QIO_PLUGIN_SANITIZE"] = "1"
    unittest.main(verbosity=2, argv=["test_plugin"])
