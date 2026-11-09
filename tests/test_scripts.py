#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host-only tests for the OTA firewall helper using iptables mocks."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/quasar_block_ota.sh"
MARKER = "quasar-openio-ota-v1"
DOMAINS = ["download.cdn.yandex.net", "updater.mobile.yandex.net", "quasar.s3.yandex.net"]


class OtaScript(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qio-scripts-")
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        (self.bin / "id").write_text("#!/bin/sh\necho 0\n")
        (self.bin / "id").chmod(0o755)
        mock = ROOT / "tests/mock_iptables.py"
        for family in ("4", "6"):
            wrapper = self.root / f"iptables{family}"
            wrapper.write_text(f"#!/bin/sh\nexec {sys.executable} {mock} {family} \"$@\"\n")
            wrapper.chmod(0o755)
        self.state = self.root / "state.json"
        self.log = self.root / "commands.log"
        self.ipv6 = self.root / "disable_ipv6"
        self.ipv6.write_text("0\n")
        self.env = os.environ.copy()
        self.env.update({
            "PATH": f"{self.bin}:/usr/bin:/bin",
            "QUASAR_IPTABLES": str(self.root / "iptables4"),
            "QUASAR_IP6TABLES": str(self.root / "iptables6"),
            "QUASAR_IPV6_DISABLED_FILE": str(self.ipv6),
            "MOCK_STATE": str(self.state),
            "MOCK_LOG": str(self.log),
        })

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, *args, extra_env=None):
        env = self.env.copy()
        if extra_env:
            env.update(extra_env)
        return subprocess.run(["/bin/sh", str(SCRIPT), *args], env=env,
                              capture_output=True, text=True, timeout=10)

    def read_state(self):
        return json.loads(self.state.read_text()) if self.state.exists() else {
            "4": {"chains": {}, "output": []}, "6": {"chains": {}, "output": []}}

    def chains(self, family):
        return self.read_state()[family]["chains"]

    def test_preview_does_not_touch_firewall(self):
        result = self.run_script("--preview", "--uid", "10123")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("DRY RUN", result.stdout)
        for domain in DOMAINS:
            self.assertIn(domain, result.stdout)
        self.assertFalse(self.log.exists())
        self.assertFalse(self.state.exists())

    def test_apply_replace_and_remove(self):
        result = self.run_script("--apply", "--uid", "10123")
        self.assertEqual(result.returncode, 0, result.stderr)
        state = self.read_state()
        first = {}
        for family in ("4", "6"):
            chains = state[family]["chains"]
            self.assertEqual(len(chains), 1)
            chain, rules = next(iter(chains.items()))
            first[family] = chain
            self.assertTrue(chain.startswith("QIO_OTA_"))
            self.assertEqual(len(rules), len(DOMAINS) + 1)
            for domain in DOMAINS:
                self.assertIn(["-d", domain, "-m", "comment", "--comment", MARKER, "-j", "DROP"], rules)
            self.assertIn(["-m", "owner", "--uid-owner", "10123", "-m", "comment", "--comment", MARKER, "-j", "DROP"], rules)
            self.assertEqual(state[family]["output"], [["-m", "comment", "--comment", MARKER, "-j", chain]])
        result = self.run_script("--apply")
        self.assertEqual(result.returncode, 0, result.stderr)
        state = self.read_state()
        for family in ("4", "6"):
            chains = state[family]["chains"]
            self.assertEqual(len(chains), 1)
            self.assertNotIn(first[family], chains)
            self.assertEqual(len(state[family]["output"]), 1)
        result = self.run_script("--remove")
        self.assertEqual(result.returncode, 0, result.stderr)
        state = self.read_state()
        for family in ("4", "6"):
            self.assertEqual(state[family]["chains"], {})
            self.assertEqual(state[family]["output"], [])

    def test_failure_rolls_back_new_chains_and_keeps_old(self):
        self.assertEqual(self.run_script("--apply").returncode, 0)
        old = {family: next(iter(self.chains(family))) for family in ("4", "6")}
        result = self.run_script("--apply", extra_env={
            "MOCK_FAIL_FAMILY": "6", "MOCK_FAIL_SUBSTRING": "quasar.s3.yandex.net"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("rolling back", result.stderr)
        state = self.read_state()
        for family in ("4", "6"):
            self.assertEqual(list(state[family]["chains"]), [old[family]])
            self.assertEqual(state[family]["output"], [["-m", "comment", "--comment", MARKER, "-j", old[family]]])

    def test_foreign_chain_collision_is_not_flushed(self):
        foreign = {"4": {"chains": {"QIO_OTA_FOREIGN": [["-d", "192.0.2.1", "-j", "DROP"]]},
                         "output": [["-j", "QIO_OTA_FOREIGN"]]},
                   "6": {"chains": {}, "output": []}}
        self.state.write_text(json.dumps(foreign))
        before = self.state.read_text()
        self.assertNotEqual(self.run_script("--apply").returncode, 0)
        self.assertNotEqual(self.run_script("--remove").returncode, 0)
        self.assertEqual(self.state.read_text(), before)

    def test_ipv4_only_requires_verified_disabled_ipv6(self):
        self.assertNotEqual(self.run_script("--apply", "--ipv4-only").returncode, 0)
        self.ipv6.write_text("1\n")
        result = self.run_script("--apply", "--ipv4-only")
        self.assertEqual(result.returncode, 0, result.stderr)
        state = self.read_state()
        self.assertEqual(len(state["4"]["chains"]), 1)
        self.assertEqual(state["6"]["chains"], {})
        self.assertEqual(self.run_script("--remove", "--ipv4-only").returncode, 0)

    def test_uid_validation(self):
        self.assertEqual(self.run_script("--apply", "--uid", "abc").returncode, 2)
        self.assertEqual(self.run_script("--apply", "--uid", "1", "--uid", "2", "--uid", "3",
                                         "--uid", "4", "--uid", "5").returncode, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
