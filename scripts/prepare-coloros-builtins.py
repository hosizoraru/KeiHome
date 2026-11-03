#!/usr/bin/env python3
"""Generate optional local APK assets from the inspected, user-owned ColorOS inputs.

Run with: uv run --with pillow --with resvg-py python scripts/prepare-coloros-builtins.py
OEM artwork and generated assets stay under ignored .artifacts/.
"""
import importlib.util
import io
import json
from pathlib import Path
import re
import sys

import resvg_py
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("coloros_import", ROOT / "scripts/import-coloros-icons.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


def main():
    inputs = ROOT / ".artifacts/inputs/coloros"
    dump = ROOT / ".artifacts/research/coloros/framework-resources.txt"
    generated = ROOT / ".artifacts/generated/assets/builtin-icon-packs"
    scratch = ROOT / ".artifacts/generated/coloros-masks"
    scratch.mkdir(parents=True, exist_ok=True)
    generated.mkdir(parents=True, exist_ok=True)
    runtime = inputs / "runtime-uxicons"
    tree = module.SourceTree(inputs / "uxicons/hdpi", inputs / "allApps.xml", inputs / "oplus-framework.jar",
                             runtime if runtime.is_dir() else None)
    text = dump.read_text()
    for style in module.STYLES:
        resource = "icon_mask_theme_pebble" if style == "pebble" else "icon_mask_theme_rectangle"
        match = re.search(r"resource (0x[0-9a-f]+) string/" + resource + r' PUBLIC\n\s+\(\) "([^"]+)"', text)
        if not match:
            raise ValueError("Missing inspected shape resource " + resource)
        shape = f'<path fill="white" d="{match[2]}"/>'
        if style == "material":
            # ConfigParser's default radius is 8 in a 150-unit viewport; custom G2 is separate.
            shape = '<rect width="150" height="150" rx="8" fill="white"/>'
        svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 150 150">{shape}</svg>'
        raw = resvg_py.svg_to_bytes(svg_string=svg, width=240, height=240)
        mask = scratch / f"{style}.png"
        Image.open(io.BytesIO(raw)).save(mask)
        proof = scratch / f"{style}.json"
        proof.write_text(json.dumps({"resource": "config:material-radius-8" if style == "material"
                                    else f"{match[1]} string/{resource}", "sourceViewport": 150,
                                    "sourceSha256": module.FRAMEWORK_RES_SHA256}))
        for art in ("on", "off"):
            args = module.argument_parser().parse_args([
                "--styles", style, "--art", art, "--aliases", str(ROOT / "docs/coloros-hyperos-aliases.json"),
                "--mask", str(mask), "--mask-provenance", str(proof), "--resource-dump", str(dump),
                "--output", str(generated),
            ])
            path, manifest = module.export_pack(tree, args, style, art)
            print(path.name, manifest["exported_counts"], flush=True)
            if style == "classic" and art == "on" and runtime.is_dir():
                # A hidden, cache-only layer for opt-in fallback of external packs.
                # Firmware redraws retain their higher priority in normal ColorOS packs.
                args.runtime_only = True
                args.output = scratch / "runtime-fallback"
                args.aliases = None
                fallback, fallback_manifest = module.export_pack(tree, args, style, art)
                destination = generated / "coloros-runtime-fallback.zip"
                destination.write_bytes(fallback.read_bytes())
                print(destination.name, fallback_manifest["exported_counts"], flush=True)


if __name__ == "__main__":
    main()
