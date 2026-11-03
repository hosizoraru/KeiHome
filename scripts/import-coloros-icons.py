#!/usr/bin/env python3
"""Convert locally extracted ColorOS redraws to KeiHome CLASSIC_ZIP files.

Requires Python 3.10+ and Pillow. No device access, APK execution or downloads.
Example:
  python scripts/import-coloros-icons.py --styles classic --art on
  python scripts/import-coloros-icons.py --styles all --art both --resource-dump resources.txt

Rules were independently implemented from single-class JADX inspection of the
framework hash below: RectangleStyle, MaterialStyle, PebbleStyle, DaylightStyle,
NightShadowStyle, MeowStyle, DefaultStyle, OplusUXIconLoaderImpl, DarkModeFilter,
OplusUXIconLoadHelper, OplusUxIconConfigParser and AdaptiveIconDrawableExtImpl.
No decompiled OEM implementation or artwork is distributed with this script.

This exports static layer CONTENT, before the device-configured OEM shape mask.
Supply --mask with an independently extracted alpha mask to bake that shape.
Without it, the manifest explicitly records that boundary. No guessed rounded
rectangle, night fallback, package alias or APK original is added.
Packages mapped to source fancy manifests are left to original-native fallback
unless --include-static-dynamic explicitly requests their frozen snapshots.
Verified Classic profiles also carry blank calendar/clock backgrounds and raw
clock parts for fixed host-owned live templates, with source-member provenance.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

try:
    from PIL import Image, ImageChops, ImageMath, UnidentifiedImageError
except ImportError:
    raise SystemExit("Pillow is required. Install it in your Python environment: pip install Pillow")


ROOT = Path(__file__).resolve().parents[1]
FRAMEWORK_SHA256 = "52c4783bc6027756b56dcb7780d838dd4b36b9726b28f4db2f0a03d49237a0ae"
FRAMEWORK_RES_SHA256 = "95c073a32364ac6eda8bad781f3fbd40aa968b750ccf2e9fecb72a9e3d467831"
DOTTED_NAME = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*\Z")
SOURCE_KEYS = ("recfg", "recbg", "rec_night", "mat", "peb", "day", "nsd", "cat")
DARK_MULTIPLIER = 214


@dataclass(frozen=True)
class Style:
    title: str
    source_key: str
    framework_class: str
    foreground_scale: float = 1.0


STYLES = {
    "classic": Style("Classic", "recfg", "RectangleStyle"),
    "material": Style("Material", "mat", "MaterialStyle", 0.8375),
    "pebble": Style("Pebble", "peb", "PebbleStyle"),
    "daylight": Style("Daylight", "day", "DaylightStyle"),
    "nightshadow": Style("Night Shadow", "nsd", "NightShadowStyle"),
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n").encode("utf-8")


def xml_bytes(root: ET.Element) -> bytes:
    ET.indent(root)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"


def parse_mapping(data: bytes) -> dict[str, list[str]]:
    # allApps.xml is provenance, not a license to redirect one package to another.
    text = data.decode("utf-8-sig")
    if "<!DOCTYPE" in text or "<!ENTITY" in text:
        raise ValueError("allApps.xml must not contain DTD/entity declarations")
    root = ET.fromstring(text)
    if root.tag != "iconinfo":
        raise ValueError("Expected iconinfo root in allApps.xml")
    mappings: dict[str, list[str]] = {}
    for item in root.findall("icon"):
        package, name = item.get("package", ""), item.get("name", "")
        if not DOTTED_NAME.fullmatch(package) or not name:
            raise ValueError("Invalid package/name in allApps.xml")
        mappings.setdefault(package, []).append(name)
    return mappings


def float32(value: float) -> float:
    return struct.unpack("f", struct.pack("f", value))[0]


def scaled_bounds(size: int, icon_scale: float, foreground_scale: float = 1.0) -> tuple[int, int]:
    # The framework uses ceil and then Java integer truncation when centering.
    extent = math.ceil(float32(float32(size * float32(icon_scale)) * float32(foreground_scale)))
    return extent, int((size - extent) / 2)


def dark_filter(image: Image.Image) -> Image.Image:
    lookup = [(value * DARK_MULTIPLIER + 127) // 255 for value in range(256)]
    red, green, blue, alpha = image.convert("RGBA").split()
    return Image.merge("RGBA", (red.point(lookup), green.point(lookup), blue.point(lookup), alpha))


def night_gradient(size: int) -> Image.Image:
    # GradientDrawable TOP_BOTTOM: #ff383838 -> #ff151515, pixel-center samples.
    rows = [int(56 + (21 - 56) * (y + 0.5) / size + 0.5) for y in range(size)]
    image = Image.new("RGBA", (1, size))
    image.putdata([(value, value, value, 255) for value in rows])
    return image.resize((size, size), Image.Resampling.NEAREST)


def png_bytes(image: Image.Image) -> bytes:
    output = io.BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


def resize_rgba(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    if image.size == size:
        return image.copy()
    red, green, blue, alpha = image.convert("RGBA").split()
    if alpha.getextrema() == (255, 255):
        return image.resize(size, Image.Resampling.BILINEAR)
    # Pillow's RGBA resize premultiplies in 8 bits, which can alter even solid
    # translucent colors. Float planes preserve precision through interpolation.
    alpha_float = alpha.convert("F")
    resized_alpha = alpha_float.resize(size, Image.Resampling.BILINEAR)
    channels = []
    for channel in (red, green, blue):
        premultiplied = ImageMath.lambda_eval(
            lambda values: values["color"] * values["alpha"] / 255.0,
            color=channel.convert("F"), alpha=alpha_float)
        resized = premultiplied.resize(size, Image.Resampling.BILINEAR)
        channels.append(ImageMath.lambda_eval(
            lambda values: values["color"] * 255.0 / (values["alpha"] + (values["alpha"] == 0)) + 0.5,
            color=resized, alpha=resized_alpha).convert("L"))
    channels.append(ImageMath.lambda_eval(lambda values: values["alpha"] + 0.5, alpha=resized_alpha).convert("L"))
    return Image.merge("RGBA", tuple(channels))


class SourceTree:
    def __init__(self, source: Path, mapping: Path, framework: Path, runtime: Path | None = None):
        self.source = source
        self.files: dict[str, dict] = {}
        self.images: dict[str, Path] = {}
        self.mapping = parse_mapping(mapping.read_bytes())
        framework_data = framework.read_bytes()
        if sha256(framework_data) != FRAMEWORK_SHA256:
            raise ValueError("Unsupported framework SHA-256; rules are pinned to the inspected ColorOS JAR")
        self.inputs = {
            "framework": {"name": framework.name, "sha256": sha256(framework_data), "bytes": len(framework_data)},
            "allApps": {"name": mapping.name, "sha256": sha256(mapping.read_bytes()), "bytes": mapping.stat().st_size},
        }
        self.packages: list[str] = []
        for directory in sorted(source.iterdir()):
            if directory.is_dir():
                if directory.is_symlink() or not DOTTED_NAME.fullmatch(directory.name):
                    raise ValueError(f"Invalid package directory: {directory.name}")
                self.packages.append(directory.name)
            elif directory.is_symlink():
                raise ValueError(f"Symlink source is unsupported: {directory.name}")
        if not self.packages:
            raise ValueError(f"No package directories in {source}")
        for path in sorted(source.rglob("*")):
            if path.is_symlink():
                raise ValueError(f"Symlink source is unsupported: {path}")
            if not path.is_file():
                continue
            key = path.relative_to(source).as_posix()
            data = path.read_bytes()
            record = {"sha256": sha256(data), "bytes": len(data)}
            if path.suffix.lower() == ".png":
                try:
                    with Image.open(io.BytesIO(data)) as image:
                        if image.width > 4096 or image.height > 4096:
                            raise ValueError(f"Source image too large: {key}")
                        record.update(format=image.format, size=list(image.size), mode=image.mode)
                        self.images[key] = path
                except (OSError, UnidentifiedImageError) as failure:
                    record["decode_error"] = str(failure)
            self.files[key] = record
        self.firmware_files, self.firmware_images = dict(self.files), dict(self.images)
        self.runtime_files, self.runtime_images = {}, {}
        if runtime is not None:
            for directory in sorted(runtime.iterdir()):
                # Cache staging directories and macOS metadata are not icon packages.
                if not directory.is_dir() or not DOTTED_NAME.fullmatch(directory.name):
                    continue
                if directory.is_symlink():
                    raise ValueError("Symlink runtime package")
                self.packages = sorted(set(self.packages) | {directory.name})
                for path in sorted(directory.glob("*.png")):
                    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*", path.stem):
                        continue
                    if path.is_symlink() or not 24 <= path.stat().st_size <= 4 * 1024 * 1024:
                        raise ValueError("Unsupported runtime icon file")
                    key = f"{directory.name}/{path.name}"
                    data = path.read_bytes()
                    with Image.open(io.BytesIO(data)) as image:
                        if image.format not in {"PNG", "JPEG", "WEBP"} or getattr(image, "is_animated", False) or max(image.size) > 4096:
                            raise ValueError("Unsupported runtime icon image")
                        self.runtime_files[key] = {"sha256": sha256(data), "bytes": len(data),
                                                   "format": image.format, "size": list(image.size),
                                                   "mode": image.mode, "origin": "runtime"}
                    self.runtime_images[key] = path
                    if len(self.runtime_files) > 50000:
                        raise ValueError("Runtime icon count limit")
            self.inputs["runtime"] = {"directory": runtime.name, "file_count": len(self.runtime_files),
                                      "inventory_sha256": sha256(json_bytes(self.runtime_files))}
        self.fancy_manifests: dict[str, list[dict]] = {}
        for archive_path in sorted(source.glob("icons-*")):
            if not archive_path.is_file():
                continue
            try:
                with zipfile.ZipFile(archive_path) as archive:
                    for entry in sorted(archive.infolist(), key=lambda item: item.filename):
                        parts = entry.filename.split("/")
                        if (len(parts) != 3 or parts[0] != "fancy_icons" or not parts[1]
                                or not re.fullmatch(r"manifest(?:_[1-9][0-9]*x[1-9][0-9]*)?\.xml", parts[2])):
                            continue
                        data = archive.read(entry)
                        self.fancy_manifests.setdefault(parts[1], []).append({
                            "archive": archive_path.name,
                            "archive_sha256": self.files[archive_path.name]["sha256"],
                            "manifest": entry.filename, "sha256": sha256(data), "bytes": len(data),
                        })
            except zipfile.BadZipFile as failure:
                raise ValueError(f"Cannot inspect source fancy manifests in {archive_path.name}: {failure}") from failure
        self.dynamic_packages = {}
        for package, names in self.mapping.items():
            fancy_names = sorted({name.removesuffix(".png") for name in names
                                  if name.removesuffix(".png") in self.fancy_manifests})
            if fancy_names:
                self.dynamic_packages[package] = fancy_names

    def select_runtime(self, presets: set[str] | None, runtime_only: bool = False):
        self.files, self.images = ({}, {}) if runtime_only else (dict(self.firmware_files), dict(self.firmware_images))
        for key, record in self.runtime_files.items():
            # findAppDrawable uses product resources first for preset apps, but
            # the downloaded cache first for third-party apps. Missing files fall through.
            if key not in self.files or key.split("/", 1)[0] not in (presets or set()):
                self.files[key], self.images[key] = record, self.runtime_images[key]

    def image(self, key: str) -> Image.Image | None:
        path = self.images.get(key)
        if path is None:
            return None
        with Image.open(path) as image:
            return image.convert("RGBA")

    def candidates(self) -> list[tuple[str, str, str]]:
        candidates = []
        for package in self.packages:
            candidates.append((package, package, ""))
            if f"{package}/ux_component.png" not in self.files:
                continue
            prefixes = set()
            for key in self.files:
                if not key.startswith(package + "/"):
                    continue
                stem = key.rsplit("/", 1)[-1].removesuffix(".png")
                for suffix in SOURCE_KEYS:
                    if stem.endswith("_" + suffix):
                        activity = stem[: -len(suffix) - 1]
                        if "." in activity and DOTTED_NAME.fullmatch(activity):
                            prefixes.add(activity)
            for activity in sorted(prefixes):
                candidates.append((f"{package}/{activity}", package, activity + "_"))
        return candidates


def appfilter(records: list[dict], mode: str) -> bytes:
    root = ET.Element("resources")
    for record in records:
        if record["modes"][mode]["status"] == "exported":
            ET.SubElement(root, "item", component=f"ComponentInfo{{{record['component']}}}", drawable=record["drawable"])
    return xml_bytes(root)


def description(style: Style, art: str, masked: bool, include_static_dynamic: bool = False) -> bytes:
    root = ET.Element("MIUI-Theme")
    ET.SubElement(root, "title").text = f"ColorOS \u00b7 {style.title} \u00b7 {'ART+' if art == 'on' else 'ART+ off'}"
    ET.SubElement(root, "author").text = "OPPO / ColorOS"
    ET.SubElement(root, "description").text = (
        "Local conversion from user supplied ColorOS files; static redraw resources only. "
        "Original source mappings retained; role aliases only from an explicitly supplied mapping file. "
        + ("User supplied alpha mask applied. " if masked else "Layer content before the device-configured OEM shape mask. ")
        + ("Static snapshots of source fancy icons explicitly requested; date/time will not update. "
           if include_static_dynamic else "Source fancy icons excluded for original-native fallback. ")
        + "Missing variants, source hashes and rendering limits are recorded in coloros-source-manifest.json."
    )
    return xml_bytes(root)


def read_presets(path: Path | None) -> tuple[set[str] | None, dict | None]:
    if path is None:
        return None, None
    data = path.read_bytes()
    names = {line.split("#", 1)[0].strip() for line in data.decode("utf-8").splitlines()}
    names.discard("")
    if any(not DOTTED_NAME.fullmatch(name) for name in names):
        raise ValueError("Preset list must contain one exact package per line")
    # isPresetApp adds this package to the resource/service-provided list.
    names.add("com.oplus.member")
    return names, {"name": path.name, "sha256": sha256(data), "bytes": len(data), "authority": "explicit_complete_list"}


def resource_arrays(path: Path | None) -> tuple[dict, dict | None]:
    if path is None:
        return {}, None
    data = path.read_bytes()
    text = data.decode("utf-8")
    arrays = {}
    for name in ("oplus_uxicon_common_style_prefix", "oplus_uxicon_common_style_config_range", "ux_preset_app_package"):
        block = re.search(r"^    resource [^\n]* array/" + name + r"(?: PUBLIC)?\n(.*?)(?=^    resource |\Z)",
                          text, re.MULTILINE | re.DOTALL)
        value = re.search(r"^      \(\) \(array\) size=(\d+)\s*\n\s*(\[.*?\])",
                          block[1] if block else "", re.MULTILINE | re.DOTALL)
        if value is None:
            raise ValueError(f"Missing default resource array: {name}")
        array = json.loads(value[2])
        if not isinstance(array, list) or len(array) != int(value[1]):
            raise ValueError(f"Invalid resource array: {name}")
        arrays[name] = array
    if arrays["oplus_uxicon_common_style_prefix"] != ["recfg", "recbg", "mat", "peb", "day", "nsd"]:
        raise ValueError("Unsupported ColorOS style prefix array")
    if arrays["oplus_uxicon_common_style_config_range"] != [2, 1, 4, 6, 7, 3]:
        raise ValueError("Unsupported ColorOS style configuration array")
    if any(not isinstance(name, str) or not DOTTED_NAME.fullmatch(name) for name in arrays["ux_preset_app_package"]):
        raise ValueError("Invalid preset package resource array")
    return arrays, {"name": path.name, "sha256": sha256(data), "bytes": len(data)}


def parse_component(value: str) -> str:
    if value.startswith("ComponentInfo{") and value.endswith("}"):
        value = value[14:-1]
    parts = value.split("/")
    if len(parts) not in (1, 2) or any(not part for part in parts):
        raise ValueError(f"Invalid component: {value}")
    if len(parts) == 2 and parts[1].startswith("."):
        parts[1] = parts[0] + parts[1]
    if any(not DOTTED_NAME.fullmatch(part) for part in parts):
        raise ValueError(f"Invalid component: {value}")
    return "/".join(parts)


def unique_json_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate alias key: {key}")
        result[key] = value
    return result


def read_aliases(path: Path | None, tree: SourceTree) -> tuple[list[tuple[str, str, str]], dict | None]:
    if path is None:
        return [], None
    data = path.read_bytes()
    aliases = json.loads(data.decode("utf-8"), object_pairs_hook=unique_json_object)
    if not isinstance(aliases, dict):
        raise ValueError("Alias JSON must map exact target components to source packages/components")
    originals = {component: (package, prefix) for component, package, prefix in tree.candidates()}
    seen = set(originals)
    result = []
    for raw_target, source in aliases.items():
        target = parse_component(raw_target)
        if target in seen:
            raise ValueError(f"Alias would replace an original or duplicate component: {target}")
        seen.add(target)
        if isinstance(source, str):
            source_component = parse_component(source)
            if source_component not in originals:
                raise ValueError(f"Alias source is absent: {source}")
            package, prefix = originals[source_component]
        elif isinstance(source, dict) and set(source) == {"package", "prefix"}:
            package, prefix = source["package"], source["prefix"]
            if not isinstance(package, str) or package not in tree.packages:
                raise ValueError(f"Alias source package is absent: {package}")
            if not isinstance(prefix, str) or (prefix and not re.fullmatch(r"[A-Za-z0-9_.$]+_", prefix)):
                raise ValueError(f"Invalid explicit source prefix: {prefix}")
            if not any(f"{package}/{prefix}{key}.png" in tree.images for key in SOURCE_KEYS):
                raise ValueError(f"Alias source prefix has no supported resources: {package}/{prefix}")
        else:
            raise ValueError("Alias source must be a package/component string or {package, prefix}")
        result.append((target, package, prefix))
    return result, {"name": path.name, "sha256": sha256(data), "bytes": len(data), "aliases": aliases}


def load_mask(path: Path | None, size: int) -> tuple[Image.Image | None, dict | None]:
    if path is None:
        return None, None
    data = path.read_bytes()
    with Image.open(io.BytesIO(data)) as source:
        if source.width > 4096 or source.height > 4096:
            raise ValueError("Mask too large")
        mask = source.getchannel("A") if "A" in source.getbands() else source.convert("L")
        mask = mask.resize((size, size), Image.Resampling.BILINEAR)
    return mask, {"name": path.name, "sha256": sha256(data), "bytes": len(data)}


def draw_layer(canvas: Image.Image, image: Image.Image, extent: int, offset: int) -> None:
    layer = resize_rgba(image, (extent, extent))
    canvas.alpha_composite(layer, (offset, offset))


def compose(
    tree: SourceTree, package: str, prefix: str, style_id: str, art: str, mode: str,
    size: int, icon_scale: float, foreground_scale: float, presets: set[str] | None,
    mask: Image.Image | None, content_scale: float,
) -> tuple[Image.Image | None, dict]:
    style = STYLES[style_id]
    base = f"{package}/{prefix}"
    classification = "unknown" if presets is None else ("preset" if package in presets else "third-party")
    if package == "com.oplus.member":
        classification = "preset"
    record: dict = {"status": "absent", "source_keys": [], "preset_classification": classification}
    rectangle_night = mode == "dark" and style_id == "classic"
    if mode == "dark" and not rectangle_night:
        record["mode_selection"] = "unchanged_day_resource_by_framework_no_dark_switch"
    if art == "off" and not rectangle_night and classification != "preset":
        return None, record | {"reason": "ART_off_requires_verified_preset_or_original_APK"}
    foreground = background = None
    background_scale = icon_scale
    foreground_ratio = foreground_scale
    if rectangle_night:
        foreground_key = base + "rec_night.png"
        foreground = tree.image(foreground_key)
        record["source_keys"] = [foreground_key] if foreground is not None else []
        if foreground is None:
            return None, record | {"reason": "missing_rec_night", "missing_keys": [foreground_key]}
        background = night_gradient(size)
        record["generated_background"] = {"type": "TOP_BOTTOM", "colors": ["#ff383838", "#ff151515"]}
        record["operation"] = "rectangle_night"
    elif style_id == "classic":
        foreground_key, background_key = base + "recfg.png", base + "recbg.png"
        foreground, background = tree.image(foreground_key), tree.image(background_key)
        record["source_keys"] = [key for key in (background_key, foreground_key) if key in tree.images]
        if foreground is None:
            return None, record | {"reason": "missing_recfg", "missing_keys": [foreground_key]}
        record["operation"] = "rectangle_layers"
        if background is None:
            record["missing_keys"] = [background_key]
            record["transparent_background"] = True
    else:
        direct_key = base + style.source_key + ".png"
        background = tree.image(direct_key)
        if background is not None:
            record["source_keys"] = [direct_key]
            record["operation"] = "style_resource_as_background"
        elif style_id in ("pebble", "material", "daylight"):
            foreground_key, background_key = base + "recfg.png", base + "recbg.png"
            foreground, background = tree.image(foreground_key), tree.image(background_key)
            record["source_keys"] = [key for key in (background_key, foreground_key) if key in tree.images]
            record["missing_keys"] = [direct_key]
            if foreground is None and (style_id == "pebble" or background is None):
                return None, record | {"reason": "missing_style_and_rectangle_fallback"}
            record["operation"] = "rectangle_fallback"
            if style_id in ("material", "daylight"):
                # buildAdaptiveIconDrawableForThirdParty(fgSize, fgSize * iconScale).
                background_scale = foreground_scale * icon_scale
        else:
            return None, record | {"reason": "missing_style_requires_original_APK_and_OEM_resources", "missing_keys": [direct_key]}
    if rectangle_night:
        foreground = dark_filter(foreground)
        background = dark_filter(background)
        record["lighting_color_filter"] = {"multiply": "#d6d6d6", "add": 0, "preserves_alpha": True}
    canvas = Image.new("RGBA", (size, size))
    if background is not None:
        extent, offset = scaled_bounds(size, background_scale)
        draw_layer(canvas, background, extent, offset)
        record["background_bounds"] = [offset, offset, offset + extent, offset + extent]
    if foreground is not None:
        extent, offset = scaled_bounds(size, background_scale, foreground_ratio)
        if (base + "recfg.png" in record["source_keys"] or rectangle_night) and f"{package}/scale_fg.png" in tree.files:
            if content_scale < 1:
                inset = foreground.copy()
                inset.paste((0, 0, 0, 0), (0, 0, *inset.size))
                width = max(1, int(foreground.width * content_scale + 0.5))
                height = max(1, int(foreground.height * content_scale + 0.5))
                inset.alpha_composite(resize_rgba(foreground, (width, height)),
                                      ((foreground.width - width) // 2, (foreground.height - height) // 2))
                foreground = inset
            record["scale_fg_content_scale"] = content_scale
        draw_layer(canvas, foreground, extent, offset)
        record["foreground_bounds"] = [offset, offset, offset + extent, offset + extent]
    if mask is not None:
        # The mask scales with icon size, independently of fallback background size.
        extent, offset = scaled_bounds(size, icon_scale)
        positioned_mask = Image.new("L", (size, size))
        positioned_mask.paste(mask.resize((extent, extent), Image.Resampling.BILINEAR), (offset, offset))
        canvas.putalpha(ImageChops.multiply(canvas.getchannel("A"), positioned_mask))
    record["status"] = "exported"
    return canvas, record


def write_zip(path: Path, files: dict[str, bytes]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", delete=False) as temporary:
        staging = Path(temporary.name)
    try:
        with zipfile.ZipFile(staging, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(files.items()):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data)
        os.replace(staging, path)
    finally:
        staging.unlink(missing_ok=True)


def dynamic_sidecars(
    tree: SourceTree, size: int, mask: Image.Image | None, safe_geometry: bool,
) -> tuple[dict[str, bytes], dict]:
    files: dict[str, bytes] = {}
    templates = {}
    clock_parts = ("clock_day_bg.png", "clock_day_hour_rectangle.png", "clock_day_minute_rectangle.png")

    def color(value: str) -> tuple[int, int, int, int]:
        if not re.fullmatch(r"#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?", value):
            raise ValueError(f"Unsupported source color: {value}")
        argb = int(value[1:], 16)
        return ((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >> 24) & 255 if len(value) == 9 else 255)

    def background(path: ET.Element) -> Image.Image:
        expected_bounds = {"left": "0", "top": "0", "right": "#icon_width", "bottom": "#icon_height",
                           "width": "#icon_width", "height": "#icon_height", "style": "fill"}
        if any(path.get(key) != value for key, value in expected_bounds.items()):
            raise ValueError("Expected a full-viewport source background Path")
        if path.get("type") == "rect":
            return Image.new("RGBA", (size, size), color(path.get("color", "")))
        if path.get("type") != "darkRect":
            raise ValueError("Unsupported source background Path")
        top, bottom = color(path.get("color", "")), color(path.get("othercolor", ""))
        image = Image.new("RGBA", (1, size))
        image.putdata([tuple(int(a + (b - a) * (y + 0.5) / size + 0.5) for a, b in zip(top, bottom))
                       for y in range(size)])
        return image.resize((size, size), Image.Resampling.NEAREST)

    for name in ("ic_launcher_calender", "ic_launcher_clock"):
        if not any(names == [name] for names in tree.dynamic_packages.values()):
            continue
        proof: dict = {"status": "absent", "source_members": [], "assets": {}}
        templates[name] = proof
        if not safe_geometry or mask is None or size > 512:
            proof["reason"] = "requires_verified_classic_mask_default_geometry_and_size_at_most_512"
            continue
        pending = {}
        pending_sources = {}
        try:
            def read(archive_name: str, member: str) -> bytes:
                if archive_name not in tree.files:
                    raise ValueError(f"Missing dynamic source archive: {archive_name}")
                with zipfile.ZipFile(tree.source / archive_name) as archive:
                    info = archive.getinfo(member)
                    if info.file_size > 4 * 1024 * 1024:
                        raise ValueError(f"Dynamic source member too large: {member}")
                    data = archive.read(info)
                proof["source_members"].append({
                    "archive": archive_name, "archive_sha256": tree.files[archive_name]["sha256"],
                    "member": member, "sha256": sha256(data), "bytes": len(data),
                })
                return data

            def manifest(archive_name: str) -> ET.Element:
                data = read(archive_name, f"fancy_icons/{name}/manifest.xml")
                text = data.decode("utf-8-sig")
                if "<!DOCTYPE" in text or "<!ENTITY" in text:
                    raise ValueError("Dynamic source manifest DTD/entity declarations are unsupported")
                root = ET.fromstring(text)
                if root.tag != "Icon" or root.get("width") != "150" or root.get("height") != "150":
                    raise ValueError("Expected source Icon with a 150-unit viewport")
                return root

            day = manifest("icons-rectangle")
            night = manifest("icons-darkrectangle")
            clock_root = f"fancy_icons/{name}/images/drawable-xxxhdpi/"
            source_colors = {}
            for mode, root in (("day", day), ("night", night)):
                paths = root.findall("Path")
                if name == "ic_launcher_clock" and mode == "day":
                    data = read("icons-rectangle", clock_root + "recbg.png")
                    with Image.open(io.BytesIO(data)) as image:
                        if image.format != "PNG" or image.width != image.height or not 1 <= image.width <= 512:
                            raise ValueError("Expected a square source clock background PNG")
                        blank = image.convert("RGBA")
                    source_colors[mode] = {"background_source": "icons-rectangle!" + clock_root + "recbg.png"}
                    pending_sources[f"{mode}.png"] = ("icons-rectangle", clock_root + "recbg.png", "masked_blank_png")
                else:
                    if len(paths) != 1:
                        raise ValueError("Expected one source blank-background Path")
                    blank = background(paths[0])
                    source_colors[mode] = {key: value for key, value in paths[0].attrib.items()
                                           if key in ("type", "color", "othercolor")}
                    pending_sources[f"{mode}.png"] = (
                        "icons-rectangle" if mode == "day" else "icons-darkrectangle",
                        f"fancy_icons/{name}/manifest.xml", "rasterized_masked_blank_Path")
                if name == "ic_launcher_calender":
                    for element, key in ((root.find("DateTime"), "weekday"), (root.find("Text"), "date")):
                        if element is not None and element.get("color"):
                            color(element.get("color"))
                            source_colors[mode][key] = element.get("color")
                blank = resize_rgba(blank, (size, size))
                blank.putalpha(ImageChops.multiply(blank.getchannel("A"), mask))
                pending[f"{mode}.png"] = png_bytes(blank)
            if name == "ic_launcher_clock":
                part_sizes = []
                for archive_name, prefix in (("icons-rectangle", ""), ("icons-darkrectangle", "night_")):
                    for leaf in clock_parts:
                        data = read(archive_name, clock_root + leaf)
                        with Image.open(io.BytesIO(data)) as image:
                            if image.format != "PNG" or image.width != image.height or not 1 <= image.width <= 512:
                                raise ValueError(f"Expected full-canvas square clock PNG: {leaf}")
                            image.load()
                            part_sizes.append(image.size)
                        pending[prefix + leaf] = data
                        pending_sources[prefix + leaf] = (archive_name, clock_root + leaf, "copy_png")
                if len(set(part_sizes)) != 1:
                    raise ValueError("Clock face and hand dimensions differ")
                proof["clock_parts"] = {
                    "day": {"archive": "icons-rectangle", "leaf_prefix": ""},
                    "night": {"archive": "icons-darkrectangle", "leaf_prefix": "night_"},
                    "treatment": "Unmodified full-canvas PNG bytes; no surrogate tint or baked dimming",
                }
            for leaf, data in pending.items():
                path = f"coloros-dynamic/{name}/{leaf}"
                files[path] = data
                source_archive, source_member, operation = pending_sources[leaf]
                source = next(row for row in proof["source_members"]
                              if row["archive"] == source_archive and row["member"] == source_member)
                with Image.open(io.BytesIO(data)) as image:
                    dimensions = list(image.size)
                proof["assets"][leaf] = {
                    "path": path, "sha256": sha256(data), "bytes": len(data), "size": dimensions,
                    "source_key": f"{source_archive}!{source_member}",
                    "source_sha256": source["sha256"], "operation": operation,
                }
            proof.update(
                status="exported", source_colors=source_colors, viewport=150,
                background_size=[size, size], mask="coloros-mask.png",
                dimming={"baked": False, "runtime_owner": "NativeColorOsDynamicIcon.dim", "rgb_multiplier": 214,
                         "rgb_divisor": 255, "preserves_alpha": True},
            )
        except (OSError, KeyError, ValueError, ET.ParseError, zipfile.BadZipFile) as failure:
            proof["reason"] = str(failure)
    return files, templates


def export_pack(tree: SourceTree, args: argparse.Namespace, style_id: str, art: str) -> tuple[Path, dict]:
    presets, preset_proof = read_presets(args.preset_packages)
    arrays, resource_proof = resource_arrays(args.resource_dump)
    if presets is None and arrays:
        presets = set(arrays["ux_preset_app_package"]) | {"com.oplus.member"}
        preset_proof = {"resource": "array/ux_preset_app_package", "dump": resource_proof,
                        "authority": "framework_resource_fallback_service_may_override"}
    tree.select_runtime(presets, getattr(args, "runtime_only", False))
    aliases, alias_proof = read_aliases(args.aliases, tree)
    mask, mask_proof = load_mask(args.mask, args.size)
    if args.mask_provenance:
        if mask_proof is None:
            raise ValueError("--mask-provenance requires --mask")
        data = args.mask_provenance.read_bytes()
        mask_proof["source_proof"] = {"name": args.mask_provenance.name, "sha256": sha256(data),
                                     "record": json.loads(data.decode("utf-8"))}
    style = STYLES[style_id]
    foreground_scale = args.foreground_scale if args.foreground_scale is not None else style.foreground_scale
    source_proof = (mask_proof or {}).get("source_proof", {}).get("record", {})
    safe_classic_profile = bool(
        style_id == "classic" and mask is not None and arrays and isinstance(source_proof, dict)
        and source_proof.get("resource") == "0x0c0400cf string/icon_mask_theme_rectangle"
        and source_proof.get("sourceViewport") == 150 and source_proof.get("sourceSha256") == FRAMEWORK_RES_SHA256)
    files: dict[str, bytes] = {}
    mask_asset = None
    if mask is not None:
        mask_image = Image.new("RGBA", mask.size, (255, 255, 255, 255))
        mask_image.putalpha(mask)
        data = png_bytes(mask_image)
        files["coloros-mask.png"] = data
        mask_asset = {"path": "coloros-mask.png", "sha256": sha256(data), "size": list(mask.size)}
    records = []
    originals = tree.candidates()
    alias_components = {component for component, _, _ in aliases}
    for component, package, prefix in originals + aliases:
        drawable = "coloros_" + sha256(component.encode("utf-8"))[:24]
        record = {
            "component": component, "package": package, "source_prefix": prefix, "drawable": drawable,
            "allApps_names": tree.mapping.get(package, []), "modes": {},
        }
        if component in alias_components:
            record["explicit_alias"] = {"target_component": component, "source_package": package, "source_prefix": prefix}
        dynamic_names = tree.dynamic_packages.get(package, [])
        if dynamic_names:
            record["dynamic_source"] = {"icon_names": dynamic_names, "static_snapshot_opt_in": args.include_static_dynamic}
            if not args.include_static_dynamic:
                record["fallback"] = "original_native"
        monochrome_key = f"{package}/{prefix}monochrome.png"
        if not dynamic_names and monochrome_key in tree.images:
            source = tree.files[monochrome_key]
            mono_size = source["size"]
            normalized_mono = mono_size[0] != mono_size[1] or max(mono_size) > 512
            mono_image = tree.image(monochrome_key)
            if normalized_mono:
                # OEM BitmapDrawable layers are drawn into square adaptive bounds.
                # Some downloaded masks are 241x240 rather than a square source file.
                mono_image = resize_rgba(mono_image, (args.size, args.size))
                mono_size = [args.size, args.size]
            # Source metadata chunks are not part of an alpha mask or our canonical
            # generated format. Decode/re-encode pixels, retaining the original hash below.
            mono_image.info.clear()
            data = png_bytes(mono_image)
            path = f"coloros-monochrome/{drawable}.png"
            files[path] = data
            record["monochrome"] = {
                "path": path, "sha256": sha256(data), "size": mono_size,
                "source_key": monochrome_key, "source_sha256": source["sha256"],
                "square_viewport_normalized": normalized_mono,
            }
        for mode, directory in (("light", "drawable"), ("dark", "drawable-night")):
            if dynamic_names and not args.include_static_dynamic:
                image, provenance = None, {
                    "status": "excluded", "reason": "dynamic_source_left_to_original_native",
                    "fallback": "original_native", "source_keys": [],
                }
            else:
                image, provenance = compose(tree, package, prefix, style_id, art, mode, args.size,
                                            args.icon_scale, foreground_scale, presets, mask, args.rec_content_scale)
                if dynamic_names:
                    provenance["static_dynamic_snapshot_opt_in"] = True
            if image is not None:
                path = f"res/{directory}/{drawable}.png"
                data = png_bytes(image)
                # Both appfilters explicitly select this artwork. Share identical bytes
                # across modes instead of duplicating every unchanged style PNG.
                shared = record["modes"].get("light", {}).get("path")
                if mode == "dark" and shared and files.get(shared) == data:
                    path = shared
                files[path] = data
                provenance.update(path=path, sha256=sha256(data), size=list(image.size))
            record["modes"][mode] = provenance
        record["supported_modes"] = [mode for mode, value in record["modes"].items() if value["status"] == "exported"]
        records.append(record)
    counts = {mode: sum(r["modes"][mode]["status"] == "exported" for r in records) for mode in ("light", "dark")}
    if not any(counts.values()):
        raise ValueError(f"{style_id} ART {art}: no exportable resources "
                         "(fancy icons require --include-static-dynamic; ART off may require --preset-packages)")
    files["appfilter.xml"] = appfilter(records, "light")
    files["res/xml/appfilter.xml"] = files["appfilter.xml"]
    # Empty night/day filters are intentional: unqualified resources must never
    # silently turn an absent mode into supported artwork in KeiHome's selector.
    files["res/xml-night/appfilter.xml"] = appfilter(records, "dark")
    files["description.xml"] = description(style, art, mask is not None, args.include_static_dynamic)
    sidecars, templates = dynamic_sidecars(tree, args.size, mask,
                                           safe_classic_profile and args.icon_scale == 1.0 and foreground_scale == 1.0)
    files.update(sidecars)
    manifest = {
        "schema_version": 1, "format": "CLASSIC_ZIP", "style": style_id, "art": art,
        "framework_style_class": "android.app.uxicons.style." + style.framework_class,
        "source_inputs": tree.inputs, "source_files": tree.files,
        "source_packages": tree.packages, "source_package_count": len(tree.packages),
        "source_keys": sorted({Path(key).stem for key in tree.files if key.endswith(".png")}),
        "allApps_packages_without_source_directory": sorted(set(tree.mapping) - set(tree.packages)),
        "preset_packages_proof": preset_proof, "mask_proof": mask_proof, "mask_asset": mask_asset,
        "aliases_proof": alias_proof, "resource_dump_proof": resource_proof,
        "resource_arrays": arrays,
        "dynamic_templates": templates,
        "dynamic_icon_policy": {
            "include_static_dynamic": args.include_static_dynamic,
            "fancy_manifests": tree.fancy_manifests,
            "mapped_source_packages": tree.dynamic_packages,
            "excluded_components": [row["component"] for row in records if row.get("fallback") == "original_native"],
            "fallback": "original_native",
        },
        "generator": {"name": Path(__file__).name, "sha256": sha256(Path(__file__).read_bytes())},
        "rendering": {
            "size": args.size, "icon_scale": args.icon_scale, "foreground_scale": foreground_scale,
            "rec_content_scale": args.rec_content_scale, "resampling": "Pillow BILINEAR with float premultiplied-alpha planes",
            "mask": "supplied_alpha" if mask is not None else "absent_content_before_OEM_shape_mask",
            "color": "source RGB values; no ICC or Display-P3 conversion",
        },
        "exported_counts": counts, "entries": records,
        "limits": [
            "Static 1x1 redraws plus blank raster sidecars for two fixed host-owned live templates; no source XML/font/script execution or enlarged layouts.",
            "Packages mapped by allApps.xml to source icons-* fancy manifests, including aliases, are excluded for original-native fallback unless --include-static-dynamic requests frozen snapshots.",
            "Current device icon configuration and the chosen runtime shape path are not inferred from static resources.",
            "Defaults use icon/max=1; material foreground/max=0.8375, other foreground/max=1. Override ratios from verified device config.",
            "Pillow rasterization is not Skia; gradient, resampling and filter rounding may differ at the last channel bit.",
            "Without --mask this is layer content before the device-configured OEM shape mask, not a complete OEM screenshot match.",
            "Preset classification is unknown without an exact service/resource list; package prefixes and allApps.xml are not classifiers.",
            "A resource preset list models the framework fallback; the live service may replace that list.",
            "art_off.png is inventoried only: the inspected framework has no art_off lookup; original-APK fallback is absent.",
            "No inferred package aliases; explicit aliases are recorded per entry and retain original source mappings.",
            "No night-to-day fallback, Monet tint or synthesized missing original APK icons.",
            "Non-rectangle styles deliberately retain identical day/night artwork, matching the framework's unchanged style lookup.",
            "monochrome.png is a layer resource, not a declared full-color style; wallpaper-dependent recoloring is unsupported.",
            "scale_fg only affects marked recfg/rec_night sources with explicit --rec-content-scale; default 1 does not infer radius/OS version.",
            "cat/MeowStyle has no position/prefix in the supplied common style arrays; outline/special classic resources are not guessed.",
        ],
    }
    expected_mask = {
        "pebble": "0x0c0400d0 string/icon_mask_theme_pebble",
        "material": "config:material-radius-8",
        "daylight": "0x0c0400cf string/icon_mask_theme_rectangle",
        "nightshadow": "0x0c0400cf string/icon_mask_theme_rectangle",
    }.get(style_id)
    if safe_classic_profile or (expected_mask and mask is not None and arrays and args.mask_provenance and
                               source_proof.get("resource") == expected_mask and
                               source_proof.get("sourceSha256") == FRAMEWORK_RES_SHA256):
        manifest["fallback"] = {"kind": "coloros-classic-v1" if style_id == "classic" else "coloros-style-v1",
                                "mask": "coloros-mask.png"}
    files["coloros-source-manifest.json"] = json_bytes(manifest)
    suffix = f"-{args.name_suffix}" if args.name_suffix else ""
    name = f"coloros-{style_id}-art-{'on' if art == 'on' else 'off'}{suffix}.zip"
    path = args.output / name
    write_zip(path, files)
    return path, manifest


def argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, default=ROOT / ".artifacts/inputs/coloros")
    parser.add_argument("--source", type=Path, help="Package tree (default: INPUT/uxicons/hdpi)")
    parser.add_argument("--runtime-source", type=Path, help="Inspected /data/oplus/uxicons cache; PNG resources only")
    parser.add_argument("--all-apps", type=Path, help="Mapping XML (default: INPUT/allApps.xml)")
    parser.add_argument("--framework", type=Path, help="Inspected JAR (default: INPUT/oplus-framework.jar)")
    parser.add_argument("--output", type=Path, default=ROOT / ".artifacts/exports/coloros")
    parser.add_argument("--name-suffix", default="", help="Optional artifact suffix to preserve an already applied ZIP")
    parser.add_argument("--styles", nargs="+", choices=(*STYLES, "all"), default=["classic"])
    parser.add_argument("--art", choices=("on", "off", "both"), default="on")
    parser.add_argument("--size", type=int, default=240, help="Square render bounds in pixels (default: 240)")
    parser.add_argument("--icon-scale", type=float, default=1.0, help="Verified iconSize/maxIconSize ratio")
    parser.add_argument("--foreground-scale", type=float, help="Verified foregroundSize/maxIconSize ratio")
    parser.add_argument("--mask", type=Path, help="Extracted OEM alpha/luminance mask; never guessed")
    parser.add_argument("--mask-provenance", type=Path, help="Optional JSON source proof for the supplied mask")
    parser.add_argument("--preset-packages", type=Path, help="Exact complete preset-app service/resource list, one package per line")
    parser.add_argument("--resource-dump", type=Path, help="aapt2 dump resources of the exact oplus-framework-res.apk")
    parser.add_argument("--aliases", type=Path, help="Explicit JSON target-component -> source-package/component (or {package, prefix})")
    parser.add_argument("--include-static-dynamic", action="store_true",
                        help="Opt into frozen snapshots for packages backed by source fancy manifests (date/time will not update)")
    parser.add_argument("--rec-content-scale", type=float, choices=(1.0, 0.89, 0.82), default=1.0,
                        help="Verified OS>=40 scale_fg ratio: 1, hyperellipse 0.89, circle 0.82")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = argument_parser()
    args = parser.parse_args(argv)
    if args.name_suffix and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", args.name_suffix):
        parser.error("--name-suffix must be a short alphanumeric/underscore/hyphen name")
    if not 16 <= args.size <= 2048:
        parser.error("--size must be between 16 and 2048")
    for name in ("icon_scale", "foreground_scale"):
        value = getattr(args, name)
        if value is not None and (not math.isfinite(value) or not 0 < value <= 2):
            parser.error(f"--{name.replace('_', '-')} must be greater than zero and at most 2")
    styles = list(STYLES) if "all" in args.styles else list(dict.fromkeys(args.styles))
    if args.mask and len(styles) != 1:
        parser.error("--mask requires exactly one style; each style needs its own verified shape")
    try:
        tree = SourceTree(args.source or args.input / "uxicons/hdpi", args.all_apps or args.input / "allApps.xml",
                          args.framework or args.input / "oplus-framework.jar", args.runtime_source)
        arts = ("on", "off") if args.art == "both" else (args.art,)
        for style_id in styles:
            for art in arts:
                path, manifest = export_pack(tree, args, style_id, art)
                print(json.dumps({"path": str(path), "sha256": sha256(path.read_bytes()),
                                  "source_packages": len(tree.packages), "exported_counts": manifest["exported_counts"],
                                  "excluded_dynamic_components": len(manifest["dynamic_icon_policy"]["excluded_components"]),
                                  "mask": manifest["rendering"]["mask"]}, sort_keys=True))
    except (OSError, ValueError, ET.ParseError, Image.DecompressionBombError) as failure:
        print(f"ColorOS conversion failed: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
