#!/usr/bin/env python3
"""Capture genuine Apple backgrounds/dial/hand geometry; never ship fonts or executables."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import tempfile

from PIL import Image


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/apple-dynamic-icons"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    images = args.output / "images"
    images.mkdir(exist_ok=True)
    sources = {
        "clock": args.runtime / "Applications/ClockAngel.app/Assets.car",
        "calendar": args.runtime / "Applications/MobileCal.app/Assets.car",
    }
    records = {}
    with tempfile.TemporaryDirectory(prefix="keihome-apple-dynamic-") as temporary:
        work = Path(temporary)
        tool = work / "capture"
        subprocess.run(["clang", "-w", "-fobjc-arc", "-framework", "AppKit", "-framework", "ImageIO",
                        str(Path(__file__).with_name("render-apple-dynamic-icons.m")), "-o", str(tool)], check=True)
        for platform in ("ios", "ipados"):
            for kind, source in sources.items():
                target = work / platform / kind
                subprocess.run([str(tool), str(source), str(target), kind, platform], check=True)
                for dark in (False, True):
                    appearance = "dark" if dark else "light"
                    names = ("base", "hour", "minute", "second") if kind == "clock" else ("base",)
                    for name in names:
                        image = Image.open(target / f"{name}-{appearance}.png").convert("RGBA")
                        angle = None
                        if name != "base":
                            mask = Image.open(target / f"mask-{name}-{appearance}.png").getchannel("A")
                            values = list(mask.get_flattened_data())
                            x = sum((i % 1024 + .5 - 512) * a for i, a in enumerate(values))
                            y = sum((i // 1024 + .5 - 512) * a for i, a in enumerate(values))
                            measured = math.degrees(math.atan2(x, -y)) % 360
                            angle = {"hour": 306, "minute": 54, "second": 180}[name]
                            if abs(measured - angle) > .1:
                                raise ValueError(f"Unrecognized native {name} orientation: {measured}")
                            # Alpha comes from the original vector. RGB comes from Apple's
                            # appearance-aware layer renderer, not a synthetic recolor.
                            image.putalpha(mask)
                            image = image.rotate(angle, resample=Image.Resampling.BICUBIC)
                        temporary_image = work / "asset.webp"
                        image.save(temporary_image, "WEBP", lossless=True, exact=True, method=6)
                        sha = digest(temporary_image)
                        destination = images / f"{sha}.webp"
                        destination.write_bytes(temporary_image.read_bytes())
                        records[f"{platform}.{kind}.{name}.{appearance}"] = {
                            "path": f"images/{sha}.webp", "sha256": sha, "width": 1024, "height": 1024,
                            "bytes": destination.stat().st_size, "sourceRotationDegrees": angle,
                        }
    wanted = {record["path"] for record in records.values()}
    for path in images.glob("*.webp"):
        if str(path.relative_to(args.output)) not in wanted:
            path.unlink()
    provenance = {
        "schema": 1, "sources": {kind: {"catalog": str(path), "sha256": digest(path)}
                                 for kind, path in sources.items()},
        "notes": "Calendar uses the native background with locale-aware live text; clock uses native "
                 "dial/hand layers. Android performs no Apple private API calls. Original preserves "
                 "these native assets' own contour; no Apple font is redistributed.",
        "images": records,
    }
    (args.output / "provenance.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n")
    print(f"{len(records)} records; {len(wanted)} unique images; "
          f"{sum((args.output / path).stat().st_size for path in wanted):,} bytes")


if __name__ == "__main__":
    main()
