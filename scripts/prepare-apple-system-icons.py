#!/usr/bin/env python3
"""Reproduce the small offline Apple system-artwork set using local, licensed Apple installations."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ios-settings-catalog", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/apple-system-icons"))
    parser.add_argument("--music-snapshot", type=Path)
    parser.add_argument("--music-original-snapshot", type=Path)
    parser.add_argument("--camera-snapshot", type=Path)
    parser.add_argument("--only", nargs="+", help="Refresh selected roles; retain hash-verified other assets.")
    args = parser.parse_args()
    ios_apps = args.ios_settings_catalog.parent.parent
    args.output.mkdir(parents=True, exist_ok=True)
    specs = [
        ("appstore", Path("/System/Applications/App Store.app/Contents/Resources/Assets.car"),
         "AppIcon", "macos-source", ["ios", "macos"]),
        ("settings", args.ios_settings_catalog, "Settings", "ios-source", ["ios"]),
        ("settings", Path("/System/Applications/System Settings.app/Contents/Resources/Assets.car"),
         "Settings", "macos-source", ["macos"]),
        ("music", Path("/System/Applications/Music.app/Contents/Resources/Assets.car"),
         "AppIcon", "macos-source", ["macos"]),
        ("safari", Path("/Applications/Safari.app/Contents/Resources/Assets.car"),
         "AppIconUpdated", "macos-source", ["macos"]),
        ("finder", Path("/System/Library/CoreServices/Finder.app/Contents/Resources/Assets.car"),
         "Finder", "macos-source", ["macos"]),
        ("siri", Path("/System/Applications/Siri.app/Contents/Resources/Assets.car"),
         "AppIcon", "macos-source", ["macos"]),
        ("siri", ios_apps / "SiriApp.app/Assets.car", "AppIcon", "ios-source", ["ios"]),
        ("wallet", ios_apps / "Passbook.app/Assets.car", "AppIcon", "ios-source", ["ios"]),
        ("passwords", ios_apps / "Passwords.app/Assets.car", "AppIcon", "ios-source", ["ios"]),
        ("feedback", Path("/System/Library/CoreServices/Applications/Feedback Assistant.app/Contents/Resources/Assets.car"),
         "AppIcon", "macos-source", ["ios", "macos"]),
    ]
    applications = args.ios_settings_catalog.parent.parent
    for role, bundle, name in [("photos", "Photos", "AppIcon"),
                                ("safari", "MobileSafari", "AppIconUpdated"),
                                ("files", "Files", "AppIcon")]:
        catalog = applications / f"{bundle}.app/Assets.car"
        if catalog.is_file():
            specs.append((role, catalog, name, "ios-source", ["ios"]))
    records = []
    if args.only:
        previous = json.loads((args.output / "provenance.json").read_text())
        records = [record for record in previous["images"] if record["role"] not in args.only]
        for record in records:
            if sha(args.output / record["file"]) != record["sha256"]:
                raise ValueError(f"Retained artwork checksum mismatch: {record['file']}")
    with tempfile.TemporaryDirectory(prefix="keihome-apple-system-") as directory:
        temp = Path(directory)
        tool = temp / "render"
        subprocess.run(["clang", "-fobjc-arc", "-framework", "Foundation", "-framework", "AppKit",
                        "-framework", "ImageIO", str(Path(__file__).with_name("render-apple-system-icons.m")),
                        "-o", str(tool)], check=True)
        for role, catalog, name, source, platforms in specs:
            if args.only and role not in args.only:
                continue
            catalog_hash = sha(catalog)
            for platform in platforms:
                subprocess.run([str(tool), str(catalog), name, platform, str(temp), role, source], check=True)
                for appearance in ["light", "dark"]:
                    base = f"{role}-{platform}-{appearance}"
                    png = temp / f"{base}.png"
                    out = args.output / f"{base}.webp"
                    subprocess.run(["cwebp", "-quiet", "-lossless", "-exact", "-z", "6",
                                    str(png), "-o", str(out)], check=True)
                    records.append(dict(file=out.name, role=role, platform=platform, appearance=appearance,
                        sourcePlatform=source, sourceCatalogSha256=catalog_hash, sourceName=name,
                        nativeAppearance=1 if appearance == "dark" else 0,
                        sourcePngSha256=sha(png), sha256=sha(out), bytes=out.stat().st_size, resolution=1024))
        for role, app_id, corners, snapshot in [
            ("music", "1108187390", "rounded", args.music_snapshot),
            ("music", "1108187390", "original", args.music_original_snapshot),
            ("camera", "1584216193", "rounded", args.camera_snapshot),
        ]:
            if args.only and role not in args.only:
                continue
            if snapshot is None:
                continue
            report = json.loads((snapshot / "report.json").read_text())
            assert report["resolution"] == 1024 and app_id in report["apps"]
            if role == "camera":
                assert report["corners"] == "rounded" and report["independentDark"] is True
            for appearance in ["light", "dark"]:
                png = snapshot / f"{app_id}-{appearance}.png"
                out = args.output / f"{role}-ios{'-original' if corners == 'original' else ''}-{appearance}.webp"
                subprocess.run(["cwebp", "-quiet", "-lossless", "-exact", "-z", "6",
                                str(png), "-o", str(out)], check=True)
                records.append(dict(file=out.name, role=role, platform="ios", appearance=appearance,
                    corners=corners, sourcePlatform="Apple App Store CDN", appId=app_id,
                    sourceReportSha256=sha(snapshot / "report.json"), sourcePngSha256=sha(png),
                    sha256=sha(out), bytes=out.stat().st_size, resolution=1024))
    manifest = dict(schema=2, attribution="Artwork copyright Apple Inc. No Apple affiliation.",
        encoding="Static 8-bit sRGB target; WebP lossless with exact transparent pixels.",
        appStoreIOS="Apple's macOS App Store layered artwork rendered with the native iOS presentation.",
        siriAndFeedbackIOS="Siri uses the iOS SiriApp catalog; Feedback uses Apple's macOS catalog with native iOS presentation.",
        images=records)
    (args.output / "provenance.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"{len(records)} renders, {sum(x['bytes'] for x in records):,} bytes")


if __name__ == "__main__":
    main()
