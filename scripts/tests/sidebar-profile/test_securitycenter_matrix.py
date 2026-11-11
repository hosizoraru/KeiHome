"""Focused runner checks; staging uses a local recorder without Android or DexKit."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import securitycenter_matrix as matrix


class MatrixInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def archive(self, identifier):
        apk = self.root / identifier / "securitycenter.apk"
        apk.parent.mkdir()
        apk.write_bytes(b"fixture")
        return apk

    def test_added_eighth_fixture_and_focused_selection(self):
        for index in range(8):
            self.archive(f"fixture-{index}")
        self.assertEqual(8, len(matrix.select_inputs(self.root)))
        self.assertEqual(["fixture-7"],
                         [apk.parent.name for apk in matrix.select_inputs(self.root, "fixture-7")])

    def test_maximum_and_excess_are_bounded_before_filtering(self):
        for index in range(matrix.MAX_FIXTURES):
            self.archive(f"fixture-{index}")
        self.assertEqual(matrix.MAX_FIXTURES, len(matrix.select_inputs(self.root)))
        self.archive("excess")
        with self.assertRaisesRegex(RuntimeError, "Expected 1..16"):
            matrix.select_inputs(self.root, "excess")

    def test_empty_or_unmatched_inputs_fail(self):
        with self.assertRaises(RuntimeError):
            matrix.select_inputs(self.root)
        self.archive("fixture")
        with self.assertRaisesRegex(RuntimeError, "No archived input"):
            matrix.select_inputs(self.root, "missing")

    def test_existing_or_partial_output_is_never_overwritten(self):
        output = matrix.claim_output(self.root / "run", self.root)
        receipt = output / "partial-proof.txt"
        receipt.write_bytes(b"preserve exact evidence")
        with self.assertRaises(FileExistsError):
            matrix.claim_output(output, self.root)
        self.assertEqual(b"preserve exact evidence", receipt.read_bytes())
        empty = self.root / "empty"
        empty.mkdir()
        with self.assertRaises(FileExistsError):
            matrix.claim_output(empty, self.root)

    def test_output_cannot_escape_research_even_through_symlink(self):
        research = self.root / "research"
        research.mkdir()
        outside = self.root / "outside"
        outside.mkdir()
        (research / "link").symlink_to(outside, target_is_directory=True)
        for output in (research, research / "../escape", research / "link/run"):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                matrix.claim_output(output, research)

    def test_device_inventory_uses_bytes_provenance_and_actual_badging(self):
        apk = self.archive("fixture")
        provenance = {"sha256": matrix.sha256(apk), "bytes": apk.stat().st_size}
        receipt = apk.parent / "provenance.json"
        receipt.write_text(json.dumps(provenance))

        class Runner:
            aapt2 = "not-executed"

            def run(self, argv, stem):
                return ("package: name='com.miui.securitycenter' "
                        "versionCode='123' versionName='from-badging'\n")

        with patch.object(matrix, "ROOT", self.root):
            inventory = matrix.fixture_inventory([apk], Runner())
            fixture = inventory["fixtures"][0]
            self.assertEqual("fixture.apk", fixture["file"])
            self.assertEqual(provenance["sha256"], fixture["sha256"])
            self.assertEqual("from-badging", fixture["versionName"])
            self.assertEqual("123", fixture["versionCode"])
            apk.write_bytes(b"changed")
            with self.assertRaisesRegex(RuntimeError, "SHA256 provenance mismatch"):
                matrix.fixture_inventory([apk], Runner())

    def test_wrong_package_badging_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Wrong APK package"):
            matrix.package_metadata("package: name='other.package' versionCode='1' versionName='1'")

    def stage_with_local_stub(self, fail_at=""):
        host = self.root / "host ' $(touch unexpected)"
        host.mkdir()
        output = self.root / "evidence"
        output.mkdir()
        fixtures = []
        payloads = {}
        for index in range(8):
            name = f"fixture-{index}.apk"
            apk = host / name
            apk.write_bytes(bytes(range(256)) + bytes([index]))
            fixtures.append({"file": name, "apk": str(apk.relative_to(self.root))})
            payloads[name] = apk.read_bytes()
        inventory = {"schema": 1, "fixtures": fixtures}
        manifest = output / "device-fixtures.json"
        manifest.write_text(json.dumps(inventory))
        payloads[manifest.name] = manifest.read_bytes()
        with patch.object(matrix, "ROOT", self.root):
            matrix.write_device_push(output, inventory)

        # A local recorder stands in for adb; no device command or connection occurs.
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        recorder = fake_bin / "adb"
        recorder.write_text(f"#!{sys.executable}\n" + """
import json
import os
import sys
args = sys.argv[1:]
data = sys.stdin.buffer.read() if args[6] == "tee" else b""
with open(os.environ["SIDEBAR_TEST_LOG"], "a") as stream:
    stream.write(json.dumps({"args": args, "stdin_hex": data.hex()}) + "\\n")
if args[6] == "tee":
    if args[7].endswith(os.environ["SIDEBAR_TEST_FAIL_AT"]) and os.environ["SIDEBAR_TEST_FAIL_AT"]:
        sys.exit(17)
    sys.stdout.buffer.write(data)
""")
        recorder.chmod(0o755)
        ledger = self.root / "calls.jsonl"
        env = dict(os.environ, PATH=str(fake_bin), SIDEBAR_SERIAL="test serial",
                   SIDEBAR_TEST_LOG=str(ledger), SIDEBAR_TEST_FAIL_AT=fail_at)
        result = subprocess.run([shutil.which("bash"), str(output / "device-push.sh")],
                                cwd=self.root, env=env, input=b"", capture_output=True, timeout=10)
        calls = [json.loads(line) for line in ledger.read_text().splitlines()]
        self.assertFalse((self.root / "unexpected").exists())
        self.assertEqual(b"", result.stdout, "tee must not print binary APK bytes")
        for call in calls:
            self.assertEqual(["-s", "test serial", "shell", "-T", "run-as", "os.kei.hyper.home"],
                             call["args"][:6])
        self.assertEqual(["mkdir", "-p", "cache/keihome-sidebar-matrix"], calls[0]["args"][6:])
        for call in calls[1:]:
            self.assertEqual("tee", call["args"][6])
            destination = Path(call["args"][7])
            self.assertEqual(Path("cache/keihome-sidebar-matrix"), destination.parent)
            self.assertEqual(payloads[destination.name], bytes.fromhex(call["stdin_hex"]))
        return result, calls

    def test_private_cache_staging_streams_exact_eight_apks_and_inventory(self):
        result, calls = self.stage_with_local_stub()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(10, len(calls), "One mkdir, eight APK streams and one inventory stream")
        self.assertTrue(calls[-1]["args"][-1].endswith("/device-fixtures.json"))

    def test_private_cache_staging_stops_after_stream_failure(self):
        result, calls = self.stage_with_local_stub("fixture-1.apk")
        self.assertEqual(17, result.returncode)
        self.assertEqual(3, len(calls), "No later fixture or inventory is staged after a failure")


if __name__ == "__main__":
    unittest.main()
