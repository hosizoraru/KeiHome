"""Synthetic archive checks; no device or OEM artwork required."""
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

from PIL import Image

SPEC = importlib.util.spec_from_file_location("capture_coloros", Path(__file__).resolve().parents[1] / "capture-coloros-runtime-icons.py")
CAPTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPTURE)


class CaptureColorOsRuntimeIconsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        image = io.BytesIO()
        Image.new("RGBA", (4, 4), (8, 16, 24, 255)).save(image, format="PNG")
        self.png = image.getvalue()

    def archive(self, members):
        archive = self.root / "source.tar.gz"
        with tarfile.open(archive, "w:gz") as target:
            for name, data, kind in members:
                member = tarfile.TarInfo(name)
                member.type = kind
                member.size = len(data) if kind == tarfile.REGTYPE else 0
                member.linkname = "/not-an-icon"
                target.addfile(member, io.BytesIO(data) if member.size else None)
        return archive

    def test_captures_night_art_and_ignores_staging_and_metadata(self):
        members = [("./com.future.app/rec_night.png", self.png, tarfile.REGTYPE),
                   ("./com.future.app/monochrome.png", self.png, tarfile.REGTYPE),
                   ("./temp_u0/com.staging.app/rec_night.png", b"staging", tarfile.REGTYPE),
                   ("./com.future.app/metadata.xml", b"metadata", tarfile.REGTYPE)]
        stage = self.root / "stage"
        files = CAPTURE.unpack(self.archive(members), stage)
        self.assertEqual(set(files), {"com.future.app/rec_night.png", "com.future.app/monochrome.png"})
        self.assertEqual((stage / "com.future.app/rec_night.png").read_bytes(), self.png)
        self.assertFalse((stage / "temp_u0").exists())

    def test_rejects_traversal_links_duplicates_and_invalid_art(self):
        good = ("./com.future.app/rec_night.png", self.png, tarfile.REGTYPE)
        for members in [[("../outside.png", self.png, tarfile.REGTYPE)],
                        [(good[0], b"", tarfile.SYMTYPE)], [good, good],
                        [(good[0], b"not a PNG" * 4, tarfile.REGTYPE)], []]:
            with self.subTest(members=[m[0] for m in members]):
                with self.assertRaises((ValueError, OSError)):
                    CAPTURE.unpack(self.archive(members), self.root / "rejected")

    def test_failed_publication_restores_previous_snapshot(self):
        previous = self.root / "runtime"
        previous.mkdir()
        (previous / "old.png").write_bytes(self.png)
        with self.assertRaises(FileNotFoundError):
            CAPTURE.publish(self.root / "missing-stage", previous, "fixture")
        self.assertEqual((previous / "old.png").read_bytes(), self.png)
        self.assertFalse((self.root / "runtime.previous-fixture").exists())

    def test_publication_preserves_backup(self):
        previous = self.root / "runtime"
        previous.mkdir()
        (previous / "old.png").write_bytes(self.png)
        stage = self.root / "stage"
        stage.mkdir()
        (stage / "new.png").write_bytes(self.png)
        backup = CAPTURE.publish(stage, previous, "fixture")
        self.assertEqual((backup / "old.png").read_bytes(), self.png)
        self.assertEqual((previous / "new.png").read_bytes(), self.png)


if __name__ == "__main__":
    unittest.main()
