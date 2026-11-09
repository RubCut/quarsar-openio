#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host tests for the standard plugin binary.

The plugin draws BOTH outputs itself through the production fb validator/mmap
path (emulated by libfixture): screen fb4 and ring fb5. It never talks to the
daemon draw API. The ring is touched only by animations and must stay untouched
in clock/visualizer modes.
"""
from __future__ import annotations

import argparse
import math
import os
from pathlib import Path
import signal
import struct
import subprocess
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
WARNINGS = ["-Wall", "-Wextra", "-Wpedantic", "-Wshadow", "-Wformat=2",
            "-Wconversion", "-Wsign-conversion", "-Werror"]
REQUIRED = ["music-play", "music-pause", "mic-on", "mic-off",
            "volume-up", "volume-down", "volume-ring"]
SCREEN_SENTINEL = b"\xa5" * (96 * 16)
RING_SENTINEL = b"\xa5" * 80


def wait_for(predicate, message, timeout=8.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError(message)


class PackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="qio-pack-")
        cls.root = Path(cls.temp.name)
        cls.binary = cls.root / "quasar_pack"
        cls.fixture = cls.root / "libfixture.so"
        cls.sanitize = os.environ.get("QIO_PACK_SANITIZE") == "1"
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
        subprocess.run([cc, *shared, str(ROOT / "tests/libfixture.c"), "-ldl",
                        "-o", str(cls.fixture)], check=True)
        sources = [str(p) for p in sorted((ROOT / "pack").glob("*.c"))]
        sources += [str(ROOT / "src/util.c"), str(ROOT / "src/framebuffer.c"),
                    str(ROOT / "src/json.c")]
        subprocess.run([cc, *flags, "-Iinclude", *sources, "-o", str(cls.binary)],
                       cwd=ROOT, check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.fb4 = self.root / f"{self._testMethodName}.fb4"
        self.fb5 = self.root / f"{self._testMethodName}.fb5"
        self.fb4.write_bytes(SCREEN_SENTINEL)
        self.fb5.write_bytes(RING_SENTINEL)
        self.log_path = self.root / f"{self._testMethodName}.log"

    def env(self):
        preload = f"{self.asan}:{self.fixture}" if self.asan else str(self.fixture)
        return {**os.environ,
                "LD_PRELOAD": preload,
                "QIO_TEST_FB4": str(self.fb4),
                "QIO_TEST_FB5": str(self.fb5),
                "QIO_TEST_FB4_STRIDE": "96",
                "QIO_TEST_FB5_STRIDE": "80",
                "QIO_TEST_FB4_ORDER": "rgb",
                "QIO_TEST_FB5_ORDER": "rgb",
                "ASAN_OPTIONS": "detect_leaks=1:abort_on_error=1",
                "UBSAN_OPTIONS": "halt_on_error=1:print_stacktrace=1"}

    def args(self, mode, *extra):
        return [str(self.binary), mode, "--screen", str(self.fb4), "--ring", str(self.fb5), *extra]

    def assert_clean(self, text):
        self.assertNotIn("AddressSanitizer", text)
        self.assertNotIn("runtime error:", text)

    def run_done(self, mode, *extra, timeout=15):
        with self.log_path.open("wb") as log:
            result = subprocess.run(self.args(mode, *extra), stdout=subprocess.PIPE,
                                    stderr=log, timeout=timeout, env=self.env())
        text = self.log_path.read_text(errors="replace")
        self.assert_clean(text)
        return result, text

    def assert_screen_written(self):
        data = self.fb4.read_bytes()
        self.assertEqual(len(data), len(SCREEN_SENTINEL))
        written = False
        for y in range(16):
            if data[y * 96:y * 96 + 75] != b"\xa5" * 75:
                written = True
            self.assertEqual(data[y * 96 + 75:(y + 1) * 96], b"\xa5" * 21,
                             "screen row padding must never be written")
        self.assertTrue(written, "screen mmap was not written")

    def assert_ring_untouched(self):
        self.assertEqual(self.fb5.read_bytes(), RING_SENTINEL,
                         "idle/visualizer must not open or write the ring")

    def assert_ring_layout(self, payload):
        self.assertEqual(len(payload), 80)
        self.assertEqual(payload[72:], b"\xa5" * 8, "ring row padding must never be written")

    def test_clock_writes_screen_and_never_touches_ring(self):
        result, text = self.run_done("clock", "--duration-ms", "600", "--fps", "2",
                                     "--brightness", "128")
        self.assertEqual(result.returncode, 0, text)
        self.assertEqual(result.stdout, b"", "plugin must not emit draw commands")
        self.assert_screen_written()
        self.assert_ring_untouched()

    def test_visualizer_never_touches_ring(self):
        result, text = self.run_done("visualizer", "--duration-ms", "500", "--fps", "30")
        self.assertEqual(result.returncode, 0, text)
        self.assertEqual(result.stdout, b"")
        self.assert_screen_written()
        self.assert_ring_untouched()

    def test_animation_draws_ring_itself_and_clears_on_exit(self):
        with self.log_path.open("wb") as log:
            process = subprocess.Popen(self.args("plasma", "--fps", "30", "--duration-ms", "900"),
                                       stdout=subprocess.PIPE, stderr=log, env=self.env())
            samples = []

            def sample():
                deadline = time.monotonic() + 0.85
                while time.monotonic() < deadline:
                    payload = self.fb5.read_bytes()
                    if payload != RING_SENTINEL:
                        samples.append(payload)
                    time.sleep(0.02)

            thread = threading.Thread(target=sample, daemon=True)
            thread.start()
            stdout, _ = process.communicate(timeout=15)
            thread.join(timeout=3)
        text = self.log_path.read_text(errors="replace")
        self.assert_clean(text)
        self.assertEqual(process.returncode, 0, text)
        self.assertEqual(stdout, b"", "ring must be drawn by the plugin, not relayed")
        self.assert_screen_written()
        self.assertGreaterEqual(len(samples), 3, "ring was not animated in fb5")
        for payload in samples:
            self.assert_ring_layout(payload)
        self.assertGreaterEqual(len({p[:72] for p in samples}), 2, "ring frame did not change")
        final = self.fb5.read_bytes()
        self.assert_ring_layout(final)
        self.assertEqual(final[:72], bytes(72), "animation must leave the ring dark")

    def test_required_standard_animations_use_the_ring(self):
        for mode in REQUIRED:
            with self.subTest(mode=mode):
                self.fb4.write_bytes(SCREEN_SENTINEL)
                self.fb5.write_bytes(RING_SENTINEL)
                result, text = self.run_done(mode, "--duration-ms", "300", "--fps", "30")
                self.assertEqual(result.returncode, 0, text)
                self.assertEqual(result.stdout, b"")
                self.assert_screen_written()
                payload = self.fb5.read_bytes()
                self.assert_ring_layout(payload)
                self.assertEqual(payload[:72], bytes(72), "ring not cleared after animation")

    def test_no_ring_flag_keeps_ring_device_untouched(self):
        result, text = self.run_done("rainbow", "--duration-ms", "300", "--no-ring")
        self.assertEqual(result.returncode, 0, text)
        self.assert_screen_written()
        self.assert_ring_untouched()

    def test_visualizer_consumes_pipe_pcm(self):
        read_fd, write_fd = os.pipe()
        os.set_inheritable(read_fd, True)
        stop = threading.Event()

        def feed():
            chunk = bytearray()
            for i in range(1024):
                value = int(20000 * math.sin(2 * math.pi * 220 * i / 48000))
                chunk.extend(struct.pack("<hh", value, value))
            try:
                while not stop.is_set():
                    os.write(write_fd, chunk)
                    time.sleep(0.01)
            except OSError:
                pass

        thread = threading.Thread(target=feed, daemon=True)
        thread.start()
        try:
            with self.log_path.open("wb") as log:
                process = subprocess.Popen(
                    self.args("visualizer", "--duration-ms", "800", "--fps", "30",
                              "--audio-fd", str(read_fd), "--sensitivity", "6"),
                    stdout=subprocess.PIPE, stderr=log, env=self.env(), pass_fds=(read_fd,))
                stdout, _ = process.communicate(timeout=15)
        finally:
            stop.set()
            os.close(read_fd)
            os.close(write_fd)
            thread.join(timeout=3)
        text = self.log_path.read_text(errors="replace")
        self.assert_clean(text)
        self.assertEqual(process.returncode, 0, text)
        self.assertEqual(stdout, b"")
        self.assert_screen_written()
        self.assert_ring_untouched()

    def test_sigterm_clean_shutdown_leaves_ring_dark(self):
        with self.log_path.open("wb") as log:
            process = subprocess.Popen(self.args("rainbow", "--fps", "30"),
                                       stdout=subprocess.PIPE, stderr=log, env=self.env())
            try:
                wait_for(lambda: self.fb5.read_bytes() != RING_SENTINEL,
                         "plugin did not draw the ring")
                process.send_signal(signal.SIGTERM)
                stdout, _ = process.communicate(timeout=10)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
        text = self.log_path.read_text(errors="replace")
        self.assert_clean(text)
        self.assertEqual(process.returncode, 0, text)
        self.assertEqual(stdout, b"")
        payload = self.fb5.read_bytes()
        self.assert_ring_layout(payload)
        self.assertEqual(payload[:72], bytes(72))

    def test_bad_ring_geometry_fails_closed(self):
        # A ring device reporting the wrong geometry must never be written.
        env = self.env()
        env["QIO_TEST_FB5_STRIDE"] = "16"
        with self.log_path.open("wb") as log:
            result = subprocess.run(self.args("plasma", "--duration-ms", "300"),
                                    stdout=subprocess.PIPE, stderr=log, timeout=15, env=env)
        text = self.log_path.read_text(errors="replace")
        self.assert_clean(text)
        self.assertEqual(result.returncode, 0, text)
        self.assert_ring_untouched()
        self.assertIn("refusing geometry", text)

    def test_invalid_fps_rejected(self):
        result, _ = self.run_done("clock", "--fps", "99")
        self.assertEqual(result.returncode, 2)

    def test_binary_has_no_daemon_draw_api(self):
        data = self.binary.read_bytes()
        self.assertNotIn(b"quasar.sock", data)
        self.assertNotIn(b"draw_screen", data)
        self.assertNotIn(b"draw_ring", data)
        self.assertNotIn(b"RING_CLEAR", data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    if args.sanitize:
        os.environ["QIO_PACK_SANITIZE"] = "1"
    unittest.main(verbosity=2, argv=["test_pack"])
