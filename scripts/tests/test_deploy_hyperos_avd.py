"""Fresh API 102 acknowledgements must survive LSPosed log rotation."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "keihome_avd_deploy", Path(__file__).resolve().parents[1] / "deploy-hyperos-avd.py"
)
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

OLD = "/data/adb/lspd/log/modules_old.log"
NEW = "/data/adb/lspd/log/modules_new.log"
ACK = "[os.kei.hyper.home] System-server icon refresh hooks installed"


class ModuleLogDeltaTest(unittest.TestCase):
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
