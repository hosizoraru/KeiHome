import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse
import zipfile

from PIL import Image


SCRIPT = Path(__file__).resolve().parents[1] / "probe-apple-icon-artwork.py"
SPEC = importlib.util.spec_from_file_location("apple_icon_artwork", SCRIPT)
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)
TEMPLATE = "https://is1-ssl.mzstatic.com/image/thumb/source/Placeholder.mill/{w}x{h}{c}.{f}"


def png(color):
    buffer = io.BytesIO()
    Image.new("RGBA", (256, 256), color).save(buffer, "PNG")
    return buffer.getvalue()


class AppleIconArtworkTest(unittest.TestCase):
    def test_rejects_untrusted_hosts_credentials_and_cleartext(self):
        for url in (
            "http://is1-ssl.mzstatic.com/image.png",
            "https://is1-ssl.mzstatic.com.evil.example/image.png",
            "https://apps.apple.com@evil.example/icon",
            "https://user:password@is1-ssl.mzstatic.com/icon",
            "https://@is1-ssl.mzstatic.com/icon",
            "https://is1-ssl.mzstatic.com:444/icon",
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                PROBE.validate_url(url)

    def test_redirects_are_also_restricted(self):
        with self.assertRaises(ValueError):
            PROBE.AppleRedirects().redirect_request(
                None, None, 302, "", {}, "https://other.example/icon.png")

    def test_uses_extended_metadata_and_exact_application(self):
        url = PROBE.metadata_url("6448311069", "us")
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
        self.assertEqual(query["extend"], ["iconArtwork"])
        self.assertEqual(query["platform"], ["web"])
        document = {"data": [{
            "id": "6448311069", "type": "apps", "attributes": {
                "name": "Example", "platformAttributes": {"ios": {
                    "artwork": {"url": TEMPLATE.replace("source", "old")},
                    "iconArtwork": {"url": TEMPLATE},
                }},
            },
        }]}
        self.assertEqual(PROBE.select_artwork(document, "6448311069"),
                         ("Example", "iconArtwork", TEMPLATE))
        with self.assertRaises(ValueError):
            PROBE.select_artwork(document, "1108187390")
        with self.assertRaises(ValueError):
            PROBE.select_artwork({"data": document["data"] * 2}, "6448311069")

    def test_unavailable_ios_does_not_borrow_mac_artwork(self):
        document = {"data": [{"id": "6448311069", "type": "apps", "attributes": {
            "platformAttributes": {"mac": {"iconArtwork": {"url": TEMPLATE}}},
        }}]}
        with self.assertRaises(ValueError):
            PROBE.select_artwork(document, "6448311069")

    def test_resolution_corners_and_appearance_are_independent(self):
        url = PROBE.artwork_url(TEMPLATE, 512, "ios", "dark")
        self.assertIn("512x512Icon.ApSiOS01.png", url)
        self.assertEqual(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query),
                         {"appearance": ["dark"]})
        self.assertIn("1024x1024bb.png", PROBE.artwork_url(TEMPLATE, 1024, "original", "light"))
        for size, corners, appearance in ((8192, "ios", "light"), (512, "fake", "light"),
                                           (512, "ios", "dark&extra=1")):
            with self.assertRaises(ValueError):
                PROBE.artwork_url(TEMPLATE, size, corners, appearance)

    def test_http_success_and_transparent_corners_are_not_capability_proof(self):
        light = PROBE.image_fingerprint(png((255, 255, 255, 255)), 256)
        dark = PROBE.image_fingerprint(png((0, 0, 0, 255)), 256)
        variants = {"light": dict(light), "dark": dict(dark),
                    "clear": dict(light), "tinted": {"error": "unavailable"},
                    "unsupported-control": dict(light)}
        PROBE.classify_appearances(variants)
        self.assertEqual(variants["dark"]["status"], "distinct-pixels")
        self.assertEqual(variants["clear"]["status"], "unconfirmed-identical")
        self.assertEqual(variants["tinted"]["status"], "failed")
        self.assertEqual(variants["light"]["alphaRange"], [255, 255])

    def test_unknown_parameter_control_can_reject_a_different_default(self):
        variants = {"light": {"rgbaSha256": "a"}, "clear": {"rgbaSha256": "b"},
                    "unsupported-control": {"rgbaSha256": "b"}}
        PROBE.classify_appearances(variants)
        self.assertEqual(variants["clear"]["status"], "unconfirmed-identical")

    def test_missing_baseline_cannot_confirm_an_appearance(self):
        variants = {"light": {"error": "failed"}, "dark": {"rgbaSha256": "b"}}
        PROBE.classify_appearances(variants)
        self.assertEqual(variants["dark"]["status"], "unconfirmed")

    def test_rejects_wrong_dimensions_before_decoding_pixels(self):
        with self.assertRaises(ValueError):
            PROBE.image_fingerprint(png((255, 255, 255, 255)), 512)

    def test_search_reports_namesakes_without_assigning_android_packages(self):
        body = json.dumps({"results": [
            {"kind": "software", "trackId": 123456, "trackName": "Telegram"},
            {"kind": "software", "trackId": 123457, "trackName": "Telegram X"},
            {"kind": "song", "trackId": 123458, "trackName": "Telegram"},
        ]}).encode()
        results = PROBE.search_candidates("Ｔｅｌｅｇｒａｍ", "us", lambda _: body)
        self.assertEqual([r["exactName"] for r in results], [True, False])
        self.assertTrue(all("androidPackage" not in r for r in results))

    def test_export_contains_only_static_verified_day_night_and_deduplicates_assets(self):
        light, dark = png((255, 255, 255, 255)), png((0, 0, 0, 255))
        report = {"apps": {"6448311069": {"appearances": {
            "light": {"status": "baseline"}, "dark": {"status": "distinct-pixels"},
            "clear": {"status": "distinct-pixels"},
        }}}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "icons.zip"
            PROBE.export_pack(path, report, {
                "com.example.first": "6448311069", "com.example.second": "6448311069",
            }, {"6448311069": {"light": light, "dark": dark, "clear": light}})
            with zipfile.ZipFile(path) as archive:
                images = [name for name in archive.namelist() if name.endswith(".png")]
                self.assertEqual(len(images), 2)
                self.assertTrue(any("drawable-night-nodpi" in name for name in images))
                self.assertTrue(all(archive.getinfo(name).compress_type == zipfile.ZIP_STORED
                                    for name in images))
                self.assertFalse(any("fancy_icons" in name for name in archive.namelist()))
                self.assertFalse(any("clear" in name for name in images))
                self.assertIn(b"com.example.second", archive.read("appfilter.xml"))

    def test_invalid_mapping_and_missing_images_do_not_publish_a_partial_pack(self):
        with tempfile.TemporaryDirectory() as directory:
            mapping = Path(directory) / "mapping.json"
            mapping.write_text('{"../../bad": "6448311069"}')
            with self.assertRaises(ValueError):
                PROBE.load_mapping(mapping)
            path = Path(directory) / "icons.zip"
            with self.assertRaises(ValueError):
                PROBE.export_pack(path, {"apps": {}}, {"com.example.app": "6448311069"}, {})
            self.assertFalse(path.exists())

    def test_metadata_rate_limit_stops_and_records_failure_without_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "result"
            failure = urllib.error.HTTPError("https://apps.apple.com", 429, "rate limit",
                                             {"Retry-After": "60"}, None)
            with patch("sys.argv", [str(SCRIPT), "--app-id", "6448311069",
                                    "--output", str(out)]):
                with patch.object(PROBE, "fetch", side_effect=failure) as get:
                    with self.assertRaises(SystemExit):
                        PROBE.main()
            self.assertEqual(get.call_count, 1)
            report = json.loads((out / "report.json").read_text())
            self.assertEqual(report["metadataFailure"]["httpStatus"], 429)
            self.assertEqual(report["metadataFailure"]["retryAfter"], "60")
            self.assertFalse((out / "ios-icon-pack.zip").exists())


if __name__ == "__main__":
    unittest.main()
