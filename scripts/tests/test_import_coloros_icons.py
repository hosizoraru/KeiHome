#!/usr/bin/env python3
"""Synthetic-only ColorOS importer checks; no OEM assets or devices required."""

import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

from PIL import Image


SCRIPT = Path(__file__).resolve().parents[1] / "import-coloros-icons.py"
SPEC = importlib.util.spec_from_file_location("import_coloros_icons", SCRIPT)
IMPORTER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IMPORTER
SPEC.loader.exec_module(IMPORTER)


class ImportColorOSIconsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="keihome-coloros-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "uxicons/hdpi"
        self.source.mkdir(parents=True)
        (self.root / "oplus-framework.jar").write_bytes(b"synthetic framework fixture")
        self.expected_hash = IMPORTER.sha256((self.root / "oplus-framework.jar").read_bytes())
        self.hash_patch = patch.object(IMPORTER, "FRAMEWORK_SHA256", self.expected_hash)
        self.hash_patch.start()
        self.addCleanup(self.hash_patch.stop)
        (self.root / "allApps.xml").write_text(
            '<iconinfo><icon package="com.vendor.preset" name="shared.png"/>'
            '<icon package="com.absent.alias" name="shared.png"/></iconinfo>', encoding="utf-8")
        self.save("com.vendor.preset", "recbg", (20, 40, 60, 255))
        self.save("com.vendor.preset", "recfg", (220, 40, 60, 128))
        self.save("com.vendor.preset", "rec_night", (0, 0, 0, 0))
        self.save("com.third.party", "recfg", (90, 80, 70, 100))
        self.save("com.third.party", "art_off", (255, 0, 0, 255))
        self.activity = "com.vendor.activity.Launcher"
        self.save("com.vendor.activity", "ux_component", (0, 0, 0, 0))
        self.save("com.vendor.activity", self.activity + "_recfg", (0, 255, 0, 255))
        self.save("com.vendor.activity", self.activity + "_recbg", (0, 0, 255, 255))
        self.save("com.vendor.activity", self.activity + "_rec_night", (0, 0, 255, 255))

    def save(self, package, key, color, size=(4, 4), file_format="PNG"):
        directory = self.source / package
        directory.mkdir(exist_ok=True)
        image = Image.new("RGBA", size, color)
        if file_format == "JPEG":
            image = image.convert("RGB")
        image.save(directory / (key + ".png"), format=file_format)

    def tree(self):
        return IMPORTER.SourceTree(self.source, self.root / "allApps.xml", self.root / "oplus-framework.jar")

    def export(self, style="classic", art="on", extra=()):
        args = IMPORTER.argument_parser().parse_args(
            ["--input", str(self.root), "--output", str(self.root / "out"), "--size", "16", *extra])
        path, manifest = IMPORTER.export_pack(self.tree(), args, style, art)
        return path, manifest

    def record(self, manifest, component="com.vendor.preset"):
        return next(row for row in manifest["entries"] if row["component"] == component)

    def image(self, path, record, mode="light"):
        with zipfile.ZipFile(path) as archive:
            image = Image.open(io.BytesIO(archive.read(record["modes"][mode]["path"])))
            return image.convert("RGBA")

    def resources(self):
        path = self.root / "framework-resources.txt"
        path.write_text(
            '    resource 0x1 array/oplus_uxicon_common_style_prefix PUBLIC\n'
            '      () (array) size=6\n'
            '        ["recfg", "recbg", "mat", "peb",\n         "day", "nsd"]\n'
            '    resource 0x2 array/oplus_uxicon_common_style_config_range PUBLIC\n'
            '      () (array) size=6\n'
            '        [2, 1, 4, 6, 7, 3]\n'
            '    resource 0x3 array/ux_preset_app_package PUBLIC\n'
            '      () (array) size=1\n'
            '        ["com.vendor.preset"]\n', encoding="utf-8")
        return path

    def fancy_sources(self):
        (self.root / "allApps.xml").write_text(
            '<iconinfo><icon package="com.vendor.preset" name="ic_launcher_calender.png"/>'
            '<icon package="com.vendor.activity" name="ic_launcher_clock.png"/>'
            '<icon package="com.third.party" name="ordinary.png"/></iconinfo>', encoding="utf-8")
        archive_path = self.source / "icons-fixture"
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("fancy_icons/ic_launcher_calender/manifest.xml", '<Icon useVariableUpdater="DateTime.Hour"/>')
            archive.writestr("fancy_icons/ic_launcher_clock/manifest_2x2.xml", '<Icon useVariableUpdater="DateTime.Minute"/>')
        aliases = self.root / "aliases.json"
        aliases.write_text(json.dumps({
            "com.target.calendar/.Home": "com.vendor.preset",
            "com.target.clock/.Home": "com.vendor.activity/" + self.activity,
        }))
        return ["--aliases", str(aliases)]

    def live_sources(self, omit=(), clock_size=(16, 16)):
        extra = self.fancy_sources()
        self.save("com.third.party", "rec_night", (0, 0, 0, 0))
        bounds = ('left="0" top="0" right="#icon_width" bottom="#icon_height" '
                  'width="#icon_width" height="#icon_height" style="fill"')
        for archive_name, dark in (("icons-rectangle", False), ("icons-darkrectangle", True)):
            path = (f'<Path type="darkRect" {bounds} color="#445566" othercolor="#112233"/>' if dark else
                    f'<Path type="rect" {bounds} color="#CCDDEE"/>')
            calendar = ('<Icon width="150" height="150">' + path +
                        '<DateTime color="#123456" format="EEEE"/><Text color="' +
                        ('#FEDCBA' if dark else '#345678') + '" textExp="#date"/></Icon>')
            clock = '<Icon width="150" height="150">' + (path if dark else '') + '</Icon>'
            with zipfile.ZipFile(self.source / archive_name, "w") as archive:
                archive.writestr("fancy_icons/ic_launcher_calender/manifest.xml", calendar)
                archive.writestr("fancy_icons/ic_launcher_clock/manifest.xml", clock)
                root = "fancy_icons/ic_launcher_clock/images/drawable-xxxhdpi/"
                archive.writestr(root + "recbg.png", IMPORTER.png_bytes(
                    Image.new("RGBA", (16, 16), (20, 40, 60, 255))))
                for i, leaf in enumerate(("clock_day_bg.png", "clock_day_hour_rectangle.png",
                                          "clock_day_minute_rectangle.png")):
                    if (archive_name, leaf) not in omit:
                        archive.writestr(root + leaf, IMPORTER.png_bytes(
                            Image.new("RGBA", clock_size, (150 if dark else 25, i * 30, 80, 128))))
                archive.writestr(root + "clock_day_hour.png", IMPORTER.png_bytes(
                    Image.new("RGBA", (20, 240), "red")))
                archive.writestr("fancy_icons/ic_launcher_calender/font.ttf", b"not an exportable asset")
        alpha = Image.new("L", (16, 16), 255)
        alpha.putpixel((0, 0), 0)
        alpha.putpixel((1, 0), 128)
        mask = self.root / "live-mask.png"
        alpha.save(mask)
        proof = self.root / "live-mask-source.json"
        proof.write_text(json.dumps({"resource": "0x0c0400cf string/icon_mask_theme_rectangle",
                                     "sourceViewport": 150, "sourceSha256": IMPORTER.FRAMEWORK_RES_SHA256}))
        return [*extra, "--mask", str(mask), "--mask-provenance", str(proof),
                "--resource-dump", str(self.resources()), "--name-suffix", "profile-v2"]

    def test_live_sidecars_are_blank_masked_and_source_proven(self):
        path, manifest = self.export(extra=self.live_sources())
        self.assertEqual("coloros-classic-art-on-profile-v2.zip", path.name)
        templates = manifest["dynamic_templates"]
        with zipfile.ZipFile(path) as archive:
            paths = [name for name in archive.namelist() if name.startswith("coloros-dynamic/")]
            self.assertEqual(10, len(paths))
            self.assertTrue(all(name.endswith(".png") for name in paths))
            self.assertNotIn("monochrome", self.record(manifest))
            self.assertEqual([], self.record(manifest)["supported_modes"])
            for name, template in templates.items():
                self.assertEqual("exported", template["status"])
                self.assertFalse(template["dimming"]["baked"])
                for asset in template["assets"].values():
                    self.assertEqual(IMPORTER.sha256(archive.read(asset["path"])), asset["sha256"])
                for source in template["source_members"]:
                    with zipfile.ZipFile(self.source / source["archive"]) as original:
                        self.assertEqual(IMPORTER.sha256(original.read(source["member"])), source["sha256"])
                for mode in ("day", "night"):
                    image = Image.open(io.BytesIO(archive.read(f"coloros-dynamic/{name}/{mode}.png")))
                    self.assertEqual((16, 16), image.size)
                    self.assertEqual((0, 128, 255), tuple(image.getpixel((x, 0))[3] for x in (0, 1, 2)))
            calendar = Image.open(io.BytesIO(archive.read("coloros-dynamic/ic_launcher_calender/day.png")))
            self.assertEqual({(204, 221, 238)}, {calendar.getpixel((x, y))[:3] for x in range(16) for y in range(16)})
            night = Image.open(io.BytesIO(archive.read("coloros-dynamic/ic_launcher_calender/night.png")))
            self.assertEqual(1, len({night.getpixel((x, 8)) for x in range(16)}))
            self.assertGreater(night.getpixel((8, 0))[0], night.getpixel((8, 15))[0])
        self.assertEqual("#123456", templates["ic_launcher_calender"]["source_colors"]["day"]["weekday"])
        self.assertEqual("#FEDCBA", templates["ic_launcher_calender"]["source_colors"]["night"]["date"])

    def test_live_clock_preserves_day_and_night_full_canvas_parts(self):
        path, _ = self.export(extra=self.live_sources())
        root = "fancy_icons/ic_launcher_clock/images/drawable-xxxhdpi/"
        with zipfile.ZipFile(path) as exported:
            for source, prefix in (("icons-rectangle", ""), ("icons-darkrectangle", "night_")):
                with zipfile.ZipFile(self.source / source) as original:
                    for leaf in ("clock_day_bg.png", "clock_day_hour_rectangle.png", "clock_day_minute_rectangle.png"):
                        self.assertEqual(original.read(root + leaf),
                                         exported.read("coloros-dynamic/ic_launcher_clock/" + prefix + leaf))
            self.assertNotEqual(exported.read("coloros-dynamic/ic_launcher_clock/clock_day_bg.png"),
                                exported.read("coloros-dynamic/ic_launcher_clock/night_clock_day_bg.png"))
            self.assertFalse(any(name.endswith(("clock_day_hour.png", ".ttf")) for name in exported.namelist()))

    def test_missing_or_nonsquare_clock_part_leaves_whole_template_absent(self):
        for omit, size in (((("icons-darkrectangle", "clock_day_hour_rectangle.png"),), (16, 16)),
                           ((), (20, 16))):
            with self.subTest(omit=omit, size=size):
                path, manifest = self.export(extra=self.live_sources(omit=omit, clock_size=size))
                self.assertEqual("absent", manifest["dynamic_templates"]["ic_launcher_clock"]["status"])
                self.assertEqual({}, manifest["dynamic_templates"]["ic_launcher_clock"]["assets"])
                self.assertEqual("exported", manifest["dynamic_templates"]["ic_launcher_calender"]["status"])
                with zipfile.ZipFile(path) as archive:
                    self.assertFalse(any(name.startswith("coloros-dynamic/ic_launcher_clock/") for name in archive.namelist()))

    def test_live_sidecars_require_verified_default_classic_geometry(self):
        extra = self.live_sources()
        for style, overrides in (("pebble", ()), ("classic", ("--icon-scale", "0.9")),
                                 ("classic", ("--foreground-scale", "0.9"))):
            with self.subTest(style=style, overrides=overrides):
                path, manifest = self.export(style=style, extra=[*extra, *overrides])
                self.assertTrue(all(value["status"] == "absent" for value in manifest["dynamic_templates"].values()))
                with zipfile.ZipFile(path) as archive:
                    self.assertFalse(any(name.startswith("coloros-dynamic/") for name in archive.namelist()))

    def test_live_sidecars_are_identical_for_art_on_and_off_and_reproducible(self):
        extra = self.live_sources()
        on_path, _ = self.export(extra=extra)
        on = on_path.read_bytes()
        on_path, _ = self.export(extra=extra)
        self.assertEqual(on, on_path.read_bytes())
        off_path, _ = self.export(art="off", extra=extra)
        with zipfile.ZipFile(io.BytesIO(on)) as enabled, zipfile.ZipFile(off_path) as disabled:
            for name in enabled.namelist():
                if name.startswith("coloros-dynamic/"):
                    self.assertEqual(enabled.read(name), disabled.read(name))

    def test_day_source_over_and_metadata(self):
        path, manifest = self.export()
        record = self.record(manifest)
        self.assertEqual((120, 40, 60, 255), self.image(path, record).getpixel((8, 8)))
        self.assertEqual([16, 16], record["modes"]["light"]["size"])
        self.assertEqual(["com.vendor.preset/recbg.png", "com.vendor.preset/recfg.png"],
                         record["modes"]["light"]["source_keys"])
        with zipfile.ZipFile(path) as archive:
            self.assertIsNone(archive.testzip())
            metadata = ET.fromstring(archive.read("description.xml"))
            self.assertEqual("MIUI-Theme", metadata.tag)
            self.assertEqual("ColorOS \u00b7 Classic \u00b7 ART+", metadata.findtext("title"))
            self.assertEqual("OPPO / ColorOS", metadata.findtext("author"))
            self.assertEqual(manifest, json.loads(archive.read("coloros-source-manifest.json")))

    def test_night_gradient_and_filter(self):
        path, manifest = self.export()
        image = self.image(path, self.record(manifest), "dark")
        self.assertEqual((46, 46, 46, 255), image.getpixel((0, 0)))
        self.assertEqual((18, 18, 18, 255), image.getpixel((0, 15)))
        filtered = IMPORTER.dark_filter(Image.new("RGBA", (1, 1), (255, 100, 0, 27)))
        self.assertEqual((214, 84, 0, 27), filtered.getpixel((0, 0)))

    def test_missing_mode_has_no_day_fallback(self):
        path, manifest = self.export()
        self.assertEqual(["light"], self.record(manifest, "com.third.party")["supported_modes"])
        with zipfile.ZipFile(path) as archive:
            day = {item.get("component") for item in ET.fromstring(archive.read("res/xml/appfilter.xml"))}
            night = {item.get("component") for item in ET.fromstring(archive.read("res/xml-night/appfilter.xml"))}
            self.assertIn("ComponentInfo{com.third.party}", day)
            self.assertNotIn("ComponentInfo{com.third.party}", night)
        self.assertEqual([], self.record(manifest, "com.vendor.activity")["supported_modes"])
        self.assertEqual(["light", "dark"], self.record(manifest, "com.vendor.activity/" + self.activity)["supported_modes"])

    def test_art_off_does_not_use_art_off_file_or_guess_preset(self):
        _, manifest = self.export(art="off")
        self.assertEqual(["dark"], self.record(manifest)["supported_modes"])
        self.assertEqual([], self.record(manifest, "com.third.party")["supported_modes"])
        self.assertEqual("unknown", self.record(manifest)["modes"]["light"]["preset_classification"])
        self.assertIn("com.third.party/art_off.png", manifest["source_files"])

    def test_resource_preset_fallback_and_complete_override(self):
        resource_path = self.resources()
        _, manifest = self.export(art="off", extra=["--resource-dump", str(resource_path)])
        self.assertEqual(["light", "dark"], self.record(manifest)["supported_modes"])
        self.assertEqual([], self.record(manifest, "com.third.party")["supported_modes"])
        self.assertIn("service_may_override", manifest["preset_packages_proof"]["authority"])
        presets = self.root / "presets.txt"
        presets.write_text("com.third.party\n")
        _, overridden = self.export(art="off", extra=["--resource-dump", str(resource_path), "--preset-packages", str(presets)])
        self.assertEqual(["dark"], self.record(overridden)["supported_modes"])
        self.assertEqual(["light"], self.record(overridden, "com.third.party")["supported_modes"])

    def test_unknown_resource_prefix_fails(self):
        path = self.resources()
        path.write_text(path.read_text().replace('"recfg"', '"guess"'))
        with self.assertRaisesRegex(ValueError, "style prefix"):
            self.export(extra=["--resource-dump", str(path)])

    def test_style_direct_resource_and_fallback_bounds(self):
        self.save("com.third.party", "mat", (40, 60, 80, 255), size=(2, 3))
        path, manifest = self.export(style="material")
        direct = self.record(manifest, "com.third.party")
        self.assertEqual(["light", "dark"], direct["supported_modes"])
        self.assertEqual((40, 60, 80, 255), self.image(path, direct).getpixel((0, 0)))
        fallback = self.record(manifest)["modes"]["light"]
        self.assertEqual("rectangle_fallback", fallback["operation"])
        self.assertEqual([1, 1, 15, 15], fallback["background_bounds"])
        self.assertEqual([2, 2, 14, 14], fallback["foreground_bounds"])
        self.assertEqual("unchanged_day_resource_by_framework_no_dark_switch", direct["modes"]["dark"]["mode_selection"])
        self.assertEqual(self.image(path, direct).tobytes(), self.image(path, direct, "dark").tobytes())

    def test_nonrectangle_art_off_gating_is_identical_in_both_modes(self):
        self.save("com.vendor.preset", "nsd", (15, 15, 15, 255))
        self.save("com.third.party", "nsd", (50, 50, 50, 255))
        resource_path = self.resources()
        path, manifest = self.export(style="nightshadow", art="off", extra=["--resource-dump", str(resource_path)])
        preset = self.record(manifest)
        self.assertEqual(["light", "dark"], preset["supported_modes"])
        self.assertEqual([], self.record(manifest, "com.third.party")["supported_modes"])
        self.assertEqual(self.image(path, preset).tobytes(), self.image(path, preset, "dark").tobytes())

    def test_aliases_are_exact_and_keep_originals(self):
        alias_file = self.root / "aliases.json"
        alias_file.write_text(json.dumps({"com.target.app/.Home": "com.vendor.preset"}))
        path, manifest = self.export(extra=["--aliases", str(alias_file)])
        target = self.record(manifest, "com.target.app/com.target.app.Home")
        original = self.record(manifest)
        self.assertEqual(self.image(path, original).tobytes(), self.image(path, target).tobytes())
        self.assertEqual("com.vendor.preset", target["explicit_alias"]["source_package"])
        self.assertEqual(IMPORTER.sha256(alias_file.read_bytes()), manifest["aliases_proof"]["sha256"])
        self.assertEqual(3, manifest["source_package_count"])
        self.assertNotIn("com.absent.alias", {row["component"] for row in manifest["entries"]})

    def test_alias_dialer_prefix_is_opt_in(self):
        self.save("com.vendor.preset", "dialer_recfg", (255, 0, 0, 255))
        alias_file = self.root / "aliases.json"
        alias_file.write_text(json.dumps({"com.target.phone/.Dialer": {"package": "com.vendor.preset", "prefix": "dialer_"}}))
        path, manifest = self.export(extra=["--aliases", str(alias_file)])
        target = self.record(manifest, "com.target.phone/com.target.phone.Dialer")
        self.assertEqual((255, 0, 0, 255), self.image(path, target).getpixel((8, 8)))
        self.assertEqual(["light"], target["supported_modes"])
        self.assertEqual("dialer_", target["source_prefix"])

    def test_alias_conflicts_missing_sources_and_duplicate_json_fail(self):
        alias_file = self.root / "aliases.json"
        for data in (
            '{"com.vendor.preset": "com.third.party"}',
            '{"com.target.app/.Home": "com.absent.app"}',
            '{"com.target.app/.Home": "com.third.party", "com.target.app/.Home": "com.vendor.preset"}',
            '{"com.target.app/.Home": {"package": "com.vendor.preset", "prefix": "../"}}',
        ):
            alias_file.write_text(data)
            with self.subTest(data=data), self.assertRaises(ValueError):
                self.export(extra=["--aliases", str(alias_file)])

    def test_fancy_packages_activities_and_aliases_default_to_native(self):
        path, manifest = self.export(extra=self.fancy_sources())
        policy = manifest["dynamic_icon_policy"]
        self.assertFalse(policy["include_static_dynamic"])
        self.assertEqual(5, len(policy["excluded_components"]))
        self.assertIn("com.target.calendar/com.target.calendar.Home", policy["excluded_components"])
        self.assertIn("com.target.clock/com.target.clock.Home", policy["excluded_components"])
        proof = policy["fancy_manifests"]["ic_launcher_clock"][0]
        self.assertEqual("fancy_icons/ic_launcher_clock/manifest_2x2.xml", proof["manifest"])
        self.assertEqual(manifest["source_files"]["icons-fixture"]["sha256"], proof["archive_sha256"])
        with zipfile.ZipFile(path) as archive:
            filters = [ET.fromstring(archive.read(name)) for name in (
                "appfilter.xml", "res/xml/appfilter.xml", "res/xml-night/appfilter.xml")]
            for component in policy["excluded_components"]:
                record = self.record(manifest, component)
                self.assertEqual([], record["supported_modes"])
                self.assertEqual("original_native", record["fallback"])
                for mode in ("light", "dark"):
                    self.assertEqual("excluded", record["modes"][mode]["status"])
                    self.assertEqual("original_native", record["modes"][mode]["fallback"])
                self.assertFalse(any(record["drawable"] + ".png" in name for name in archive.namelist()))
                for root in filters:
                    self.assertNotIn(f"ComponentInfo{{{component}}}", {item.get("component") for item in root})

    def test_static_dynamic_opt_in_preserves_ordinary_coverage_and_pixels(self):
        extra = self.fancy_sources()
        path, snapshots = self.export(extra=[*extra, "--include-static-dynamic"])
        snapshot_bytes = path.read_bytes()
        path, safe = self.export(extra=extra)
        self.assertTrue(snapshots["dynamic_icon_policy"]["include_static_dynamic"])
        self.assertEqual([], snapshots["dynamic_icon_policy"]["excluded_components"])
        self.assertEqual(["light", "dark"], self.record(snapshots)["supported_modes"])
        self.assertTrue(self.record(snapshots)["dynamic_source"]["static_snapshot_opt_in"])
        ordinary = self.record(safe, "com.third.party")
        self.assertEqual(ordinary, self.record(snapshots, "com.third.party"))
        with zipfile.ZipFile(path) as current, zipfile.ZipFile(io.BytesIO(snapshot_bytes)) as opted_in:
            self.assertIn("date/time will not update", opted_in.read("description.xml").decode("utf-8"))
            for mode in ordinary["supported_modes"]:
                image_path = ordinary["modes"][mode]["path"]
                self.assertEqual(current.read(image_path), opted_in.read(image_path))

    def test_fancy_exclusion_requires_exact_all_apps_name_and_manifest(self):
        with zipfile.ZipFile(self.source / "icons-fixture", "w") as archive:
            archive.writestr("fancy_icons/ic_launcher_clock/manifest.xml", "<Icon/>")
            archive.writestr("fancy_icons/shared/image.png", "not a manifest")
            archive.writestr("fancy_icons/shared/manifest.txt", "not an XML manifest")
        with zipfile.ZipFile(self.source / "ordinary.zip", "w") as archive:
            archive.writestr("fancy_icons/shared/manifest.xml", "<Icon/>")
        _, manifest = self.export()
        self.assertEqual([], manifest["dynamic_icon_policy"]["excluded_components"])
        self.assertEqual(["light", "dark"], self.record(manifest)["supported_modes"])

    def test_corrupt_fancy_archive_is_rejected(self):
        (self.source / "icons-broken").write_bytes(b"invalid ZIP")
        with self.assertRaisesRegex(ValueError, "Cannot inspect source fancy manifests"):
            self.tree()

    def test_real_monochrome_layers_and_alias_provenance(self):
        self.save("com.vendor.preset", "monochrome", (10, 20, 30, 40))
        self.save("com.vendor.activity", self.activity + "_monochrome", (40, 30, 20, 10))
        aliases = self.root / "aliases.json"
        aliases.write_text(json.dumps({"com.target.app/.Home": "com.vendor.preset"}))
        path, manifest = self.export(extra=["--aliases", str(aliases)])
        with zipfile.ZipFile(path) as archive:
            for component, source_key in (
                ("com.vendor.preset", "com.vendor.preset/monochrome.png"),
                ("com.target.app/com.target.app.Home", "com.vendor.preset/monochrome.png"),
                ("com.vendor.activity/" + self.activity, "com.vendor.activity/" + self.activity + "_monochrome.png"),
            ):
                mono = self.record(manifest, component)["monochrome"]
                self.assertTrue(mono["path"].startswith("coloros-monochrome/"))
                self.assertEqual(source_key, mono["source_key"])
                data = archive.read(mono["path"])
                self.assertEqual((self.source / source_key).read_bytes(), data)
                self.assertEqual(IMPORTER.sha256(data), mono["sha256"])
            self.assertNotIn("monochrome", self.record(manifest, "com.third.party"))

    def test_dynamic_sources_never_export_monochrome_even_with_snapshot_opt_in(self):
        extra = self.fancy_sources()
        self.save("com.vendor.preset", "monochrome", (255, 255, 255, 255))
        self.save("com.vendor.activity", self.activity + "_monochrome", (255, 255, 255, 255))
        for opt_in in ([], ["--include-static-dynamic"]):
            _, manifest = self.export(extra=[*extra, *opt_in])
            for record in manifest["entries"]:
                if "dynamic_source" in record:
                    self.assertNotIn("monochrome", record)

    def test_classic_profile_protocol_assets_for_named_artifacts(self):
        mask = self.root / "mask.png"
        alpha = Image.new("L", (16, 16), 255)
        alpha.putpixel((0, 0), 0)
        alpha.save(mask)
        proof = self.root / "mask-source.json"
        proof.write_text(json.dumps({"resource": "0x0c0400cf string/icon_mask_theme_rectangle",
                                     "sourceViewport": 150, "sourceSha256": IMPORTER.FRAMEWORK_RES_SHA256}))
        extra = ["--mask", str(mask), "--mask-provenance", str(proof), "--resource-dump", str(self.resources()),
                 "--name-suffix", "profile-v1"]
        path, manifest = self.export(extra=extra)
        self.assertEqual("coloros-classic-art-on-profile-v1.zip", path.name)
        self.assertEqual({"kind": "coloros-classic-v1", "mask": "coloros-mask.png"}, manifest["fallback"])
        with zipfile.ZipFile(path) as archive:
            data = archive.read("coloros-mask.png")
            self.assertEqual(IMPORTER.sha256(data), manifest["mask_asset"]["sha256"])
            image = Image.open(io.BytesIO(data))
            self.assertEqual("RGBA", image.mode)
            self.assertEqual(alpha.tobytes(), image.getchannel("A").tobytes())
        _, other_style = self.export(style="pebble", extra=extra)
        self.assertNotIn("fallback", other_style)
        proof.write_text(json.dumps({"resource": "unknown", "sourceViewport": 150}))
        _, unverified = self.export(extra=extra)
        self.assertNotIn("fallback", unverified)
        self.assertEqual("coloros-mask.png", unverified["mask_asset"]["path"])

    def test_mask_and_independent_centering(self):
        mask = self.root / "mask.png"
        image = Image.new("L", (16, 16), 255)
        image.paste(0, (0, 0, 8, 16))
        image.save(mask)
        path, manifest = self.export(extra=["--mask", str(mask), "--icon-scale", "0.5", "--foreground-scale", "0.5"])
        record = self.record(manifest)
        self.assertEqual([4, 4, 12, 12], record["modes"]["light"]["background_bounds"])
        self.assertEqual([6, 6, 10, 10], record["modes"]["light"]["foreground_bounds"])
        image = self.image(path, record)
        self.assertEqual(0, image.getpixel((5, 8))[3])
        self.assertEqual(255, image.getpixel((10, 8))[3])
        self.assertEqual("supplied_alpha", manifest["rendering"]["mask"])

    def test_mask_provenance_is_retained_and_multiple_styles_are_rejected(self):
        mask = self.root / "mask.png"
        Image.new("L", (16, 16), 255).save(mask)
        proof = self.root / "mask-source.json"
        proof.write_text(json.dumps({"resource": "string/icon_mask_theme_rectangle", "sourceViewport": 150,
                                     "shape": "resource default; active customization not reproduced"}))
        _, manifest = self.export(extra=["--mask", str(mask), "--mask-provenance", str(proof)])
        self.assertEqual(150, manifest["mask_proof"]["source_proof"]["record"]["sourceViewport"])
        self.assertEqual(IMPORTER.sha256(mask.read_bytes()), manifest["mask_proof"]["sha256"])
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            IMPORTER.main(["--styles", "all", "--mask", str(mask)])

    def test_resizing_uses_alpha_weighted_color(self):
        image = Image.new("RGBA", (2, 1))
        image.putdata([(255, 0, 0, 255), (0, 255, 0, 0)])
        resized = IMPORTER.resize_rgba(image, (1, 1))
        self.assertEqual((255, 0, 0, 128), resized.getpixel((0, 0)))

    def test_misnamed_jpeg_is_decoded_and_output_is_png(self):
        self.save("com.third.party", "peb", (40, 60, 80, 255), file_format="JPEG")
        path, manifest = self.export(style="pebble")
        self.assertEqual("JPEG", manifest["source_files"]["com.third.party/peb.png"]["format"])
        with zipfile.ZipFile(path) as archive:
            data = archive.read(self.record(manifest, "com.third.party")["modes"]["light"]["path"])
            self.assertTrue(data.startswith(b"\x89PNG\r\n\x1a\n"))

    def test_source_hashes_and_reproducible_zip(self):
        first, manifest = self.export()
        data = first.read_bytes()
        second, _ = self.export()
        self.assertEqual(data, second.read_bytes())
        source = self.source / "com.vendor.preset/recfg.png"
        self.assertEqual(IMPORTER.sha256(source.read_bytes()), manifest["source_files"]["com.vendor.preset/recfg.png"]["sha256"])
        (self.root / "oplus-framework.jar").write_bytes(b"unknown framework")
        with self.assertRaisesRegex(ValueError, "Unsupported framework"):
            self.tree()

    def test_xml_entities_are_rejected(self):
        (self.root / "allApps.xml").write_text('<!DOCTYPE iconinfo [<!ENTITY ext SYSTEM "file:///tmp/example">]><iconinfo/>')
        with self.assertRaisesRegex(ValueError, "DTD/entity"):
            self.tree()


if __name__ == "__main__":
    unittest.main()
