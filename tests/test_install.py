#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host-only tests for the read-only ELF verifier and /odm file transaction."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "scripts/verify_aarch64.sh"
INSTALL = ROOT / "scripts/install_odm.sh"


def elf64(machine: int = 183, etype: int = 3, phdrs=(), dynamic: bytes = b"") -> bytes:
    phoff = 64
    phentsize = 56
    header = struct.pack(
        "<16sHHIQQQIHHHHHH",
        b"\x7fELF\x02\x01\x01" + bytes(9),
        etype, machine, 1, 0, phoff, 0, 0, 64, phentsize, len(phdrs), 0, 0, 0,
    )
    body = b"".join(struct.pack("<IIQQQQQQ", *p) for p in phdrs)
    return header + body + dynamic


def load(offset=0, filesz=0):
    return (1, 5, offset, 0, 0, filesz, filesz, 0x1000)


def dynamic_phdr(offset, size):
    return (2, 6, offset, 0, 0, size, size, 8)


def interp_phdr():
    return (3, 4, 0, 0, 0, 0, 0, 1)


class VerifyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qio-verify-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def run_verify(self, data: bytes, expected: str | None = None):
        path = self.root / "candidate"
        path.write_bytes(data)
        digest = hashlib.sha256(data).hexdigest() if expected is None else expected
        return subprocess.run(["sh", str(VERIFY), str(path), digest],
                              capture_output=True, text=True, timeout=10)

    def test_valid_aarch64_static_pie(self):
        dyn = struct.pack("<QQ", 27, 0) + struct.pack("<QQ", 0, 0)  # DT_RELAENT, DT_NULL
        offset = 64 + 56 * 3
        data = elf64(phdrs=[load(), dynamic_phdr(offset, len(dyn)),
                            (0x6474E551, 6, 0, 0, 0, 0, 0, 16)], dynamic=dyn)
        result = self.run_verify(data)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("verified AArch64 static PIE", result.stdout)

    def test_wrong_architecture_rejected(self):
        result = self.run_verify(elf64(machine=62, phdrs=[load()]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not AArch64", result.stderr)

    def test_non_pie_rejected(self):
        result = self.run_verify(elf64(etype=2, phdrs=[load()]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not ET_DYN", result.stderr)

    def test_interp_rejected(self):
        result = self.run_verify(elf64(phdrs=[load(), interp_phdr()]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("PT_INTERP", result.stderr)

    def test_dt_needed_rejected(self):
        dyn = struct.pack("<QQ", 1, 0) + struct.pack("<QQ", 0, 0)
        offset = 64 + 56 * 2
        result = self.run_verify(elf64(phdrs=[load(), dynamic_phdr(offset, len(dyn))], dynamic=dyn))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("DT_NEEDED", result.stderr)

    def test_hash_mismatch_rejected(self):
        result = self.run_verify(elf64(phdrs=[load()]), expected="0" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA-256 mismatch", result.stderr)

    def test_truncated_rejected(self):
        result = self.run_verify(b"\x7fELF")
        self.assertNotEqual(result.returncode, 0)


class InstallTransactionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qio-install-")
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        (self.bin / "chcon").write_text("#!/bin/sh\nexit 0\n")
        (self.bin / "chcon").chmod(0o755)
        self.odm = self.root / "odm"
        self.plugin = self.odm / "lib/quasar/plugins/standard"
        self.sources = {}
        for name in ("quasar_openio", "quasar_pack", "plugin.conf", "quasar_openio.rc"):
            path = self.root / name
            path.write_bytes(elf64(phdrs=[load()]) if name.startswith("quasar_") else f"{name}\n".encode())
            self.sources[name] = path
        self.env = os.environ.copy()
        self.env.update({"PATH": f"{self.bin}:/usr/bin:/bin", "QUASAR_INSTALL_LIB_ONLY": "1"})

    def tearDown(self):
        self.temp.cleanup()

    def shell(self, body: str, extra_env=None):
        env = self.env.copy()
        if extra_env:
            env.update(extra_env)
        script = f'''
set -eu
. "{INSTALL}"
ODM_ROOT="{self.odm}"
PLUGIN_DIR="{self.plugin}"
BINARY="{self.sources['quasar_openio']}"
PACK_BINARY="{self.sources['quasar_pack']}"
PLUGIN_CONF="{self.sources['plugin.conf']}"
RC_SOURCE="{self.sources['quasar_openio.rc']}"
BINARY_SHA256=$(sha256sum "$BINARY" | cut -d' ' -f1)
PACK_SHA256=$(sha256sum "$PACK_BINARY" | cut -d' ' -f1)
CONF_SHA256=$(sha256sum "$PLUGIN_CONF" | cut -d' ' -f1)
RC_SHA256=$(sha256sum "$RC_SOURCE" | cut -d' ' -f1)
{body}
'''
        return subprocess.run(["sh", "-c", script], env=env, capture_output=True, text=True, timeout=15)

    def targets(self):
        return [self.odm / "bin/quasar_openio", self.plugin / "quasar_pack",
                self.plugin / "plugin.conf", self.odm / "etc/init/quasar_openio.rc"]

    def test_commit_removes_staging_and_journal(self):
        result = self.shell("prepare_dirs\nstage_files\ncommit_files\necho OK")
        self.assertEqual(result.returncode, 0, result.stderr)
        for target, name in zip(self.targets(), self.sources):
            self.assertEqual(target.read_bytes(), self.sources[name].read_bytes())
        self.assertFalse((self.odm / "lib/quasar/.install-journal").exists())
        self.assertEqual(list(self.odm.rglob("*.new.*")), [])
        self.assertEqual(list(self.odm.rglob("*.backup.*")), [])

    def test_failure_rolls_back_previous_files(self):
        for target in self.targets():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("old\n")
        fail_mv = self.bin / "mv"
        fail_mv.write_text('''#!/bin/sh
for arg in "$@"; do
    case "$arg" in */.plugin.conf.new.*) exit 1 ;; esac
done
exec /bin/mv "$@"
''')
        fail_mv.chmod(0o755)
        result = self.shell("prepare_dirs\nstage_files\ncommit_files\necho SHOULD_NOT_COMMIT")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("SHOULD_NOT_COMMIT", result.stdout)
        for target in self.targets():
            self.assertEqual(target.read_text(), "old\n")
        self.assertEqual(list(self.odm.rglob("*.new.*")), [])
        self.assertEqual(list(self.odm.rglob("*.backup.*")), [])
        self.assertFalse((self.odm / "lib/quasar/.install-journal").exists())

    def test_interrupted_journal_refuses_new_apply(self):
        journal = self.odm / "lib/quasar/.install-journal"
        journal.parent.mkdir(parents=True)
        journal.write_text("interrupted\n")
        result = self.shell("prepare_dirs")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("interrupted install journal", result.stderr)
        self.assertTrue(journal.exists())

    def test_symlink_target_refused(self):
        target = self.odm / "bin/quasar_openio"
        target.parent.mkdir(parents=True)
        target.symlink_to(self.sources["quasar_openio"])
        result = self.shell("prepare_dirs")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("symlinked install target", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
