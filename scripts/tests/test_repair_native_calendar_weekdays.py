import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location(
    "calendar_repair", Path(__file__).parents[1] / "repair-native-calendar-weekdays.py"
)
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


def manifest():
    return b"<Icon>" + b"".join(
        f"#day_of_week-1=={i},'{prefix}calendar_bg_{i + 1}.png'".encode()
        for prefix in ("", "dark/") for i in range(7)
    ) + b'<Image srcid="#date"/></Icon>'


class CalendarRepairTest(unittest.TestCase):
    def test_all_weekdays_both_appearances_and_date_preserved(self):
        result = repair.repair_manifest(manifest())
        for prefix in ("", "dark/"):
            for native, artwork in ((1, 7), (2, 1), (3, 2), (4, 3), (5, 4), (6, 5), (7, 6)):
                self.assertIn(
                    f"#day_of_week=={native},'{prefix}calendar_bg_{artwork}.png'".encode(), result
                )
        self.assertIn(b'srcid="#date"', result)
        with self.assertRaises(ValueError):
            repair.repair_manifest(result)

    def test_ambiguous_or_incomplete_rules_rejected(self):
        for invalid in (manifest().replace(b"calendar_bg_7", b"calendar_bg_6"),
                        manifest() + b"#day_of_week-1==0,'calendar_bg_1.png'",
                        manifest().replace(b"#day_of_week-1==0", b"#day_of_week==0")):
            with self.assertRaises(ValueError):
                repair.repair_manifest(invalid)

    def test_archive_preserves_unselected_payloads_and_source(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.zip"
            output = Path(directory) / "output.zip"
            packages = ("com.android.calendar", "com.xiaomi.calendar")
            with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.comment = b"original"
                archive.writestr("fancy_icons/", b"")
                archive.writestr("res/drawable-xxhdpi/com.example.png", b"unchanged pixels")
                for package in packages:
                    root = f"fancy_icons/{package}/"
                    archive.writestr(root + "manifest.xml", manifest())
                    for prefix in ("", "dark/"):
                        for i in range(1, 8):
                            archive.writestr(root + prefix + f"calendar_bg_{i}.png", b"original")
            before = source.read_bytes()
            report = repair.repair(source, output, packages)
            self.assertEqual(source.read_bytes(), before)
            self.assertEqual(len(report["changedManifests"]), 2)
            with zipfile.ZipFile(source) as first, zipfile.ZipFile(output) as second:
                self.assertEqual(first.comment, second.comment)
                self.assertEqual(first.namelist(), second.namelist())
                for name in first.namelist():
                    if name not in report["changedManifests"]:
                        self.assertEqual(first.read(name), second.read(name))
            with self.assertRaises(ValueError):
                repair.repair(source, source, packages)
            with self.assertRaises(ValueError):
                repair.repair(source, output, packages)

    def test_failed_validation_does_not_publish_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.zip"
            output = Path(directory) / "output.zip"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("../escape", b"bad")
            with self.assertRaises(ValueError):
                repair.repair(source, output, ["com.android.calendar"])
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
