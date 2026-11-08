"""Fresh API 102 acknowledgements must survive LSPosed log rotation."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location(
    "keihome_avd_deploy", Path(__file__).resolve().parents[1] / "deploy-hyperos-avd.py"
)
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

OLD = "/data/adb/lspd/log/modules_old.log"
NEW = "/data/adb/lspd/log/modules_new.log"
ACK = "[os.kei.hyper.home] System-server icon refresh hooks installed"


class ModuleLogDeltaTest(unittest.TestCase):
    def test_process_discovery_reads_full_names_only_for_matching_comm_candidates(self):
        calls = []

        def root(command, timeout=45):
            calls.append(command)
            if command.startswith("for f in /proc/"):
                return "18\n17"
            if command.endswith("/cmdline"):
                return "com.android.systemui\0"
            pid = int(command.split("/proc/")[1].split("/")[0])
            return f"{pid} (ndroid.systemui) S 1 " + "0 " * 17 + str(pid * 10)

        with patch.object(deploy, "root", side_effect=root), patch.object(deploy, "adb") as adb:
            self.assertEqual({"pid": 17, "parent": 1, "start": 170},
                             deploy.process("com.android.systemui"))
            adb.assert_not_called()
        self.assertIn("ndroid.systemui", calls[0])
        self.assertTrue(all("/17/" in call or "/18/" in call for call in calls[1:]))

    def test_truncated_comm_collision_cannot_identify_another_app_as_systemui(self):
        def root(command, timeout=45):
            if command.startswith("for f in /proc/"):
                return "19"
            if command.endswith("/cmdline"):
                return "other.ndroid.systemui\0"
            raise AssertionError("A colliding comm must not reach the identity read")

        with patch.object(deploy, "root", side_effect=root), patch.object(deploy.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "Expected a live"):
                deploy.process("com.android.systemui")

    def delta(self, before, replies):
        def respond(command):
            self.assertIn(command, replies, command)
            return replies[command]

        with patch.object(deploy, "root", side_effect=respond):
            return deploy.module_log_delta(before)

    def test_reads_only_appended_bytes(self):
        result = self.delta({OLD: (1, 100)}, {
            "stat -c '%n %i %s' /data/adb/lspd/log/modules_*.log": f"{OLD} 1 180",
            f"tail -c +101 {OLD}": ACK,
        })
        self.assertEqual(ACK, result)

    def test_rotation_includes_new_stream(self):
        result = self.delta({OLD: (1, 100)}, {
            "stat -c '%n %i %s' /data/adb/lspd/log/modules_*.log": f"{OLD} 1 110\n{NEW} 2 80",
            f"tail -c +101 {OLD}": "old stream closed",
            f"tail -c +1 {NEW}": ACK,
        })
        self.assertIn(ACK, result)

    def test_unchanged_logs_cannot_replay_an_old_acknowledgement(self):
        self.assertEqual("", self.delta({OLD: (1, 100)}, {
            "stat -c '%n %i %s' /data/adb/lspd/log/modules_*.log": f"{OLD} 1 100",
        }))

    def test_replaced_or_truncated_stream_restarts_at_first_byte(self):
        for inode, size in ((2, 180), (1, 50)):
            with self.subTest(inode=inode, size=size):
                self.assertEqual(ACK, self.delta({OLD: (1, 100)}, {
                    "stat -c '%n %i %s' /data/adb/lspd/log/modules_*.log": f"{OLD} {inode} {size}",
                    f"tail -c +1 {OLD}": ACK,
                }))


if __name__ == "__main__":
    unittest.main()
