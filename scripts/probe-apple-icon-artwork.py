#!/usr/bin/env python3
"""Research Apple's public icon artwork; optionally export a static day/night ZIP.

Requires Pillow. This is an explicit, desktop-side research tool, not a background
scanner or an enabled source in the Android app.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

from PIL import Image


MAX_RESPONSE = 8 * 1024 * 1024
RESOLUTIONS = (256, 512, 1024)
BASE_APPEARANCES = ("light", "dark")


def validate_url(url):
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ""
    if (parsed.scheme != "https" or parsed.username is not None or parsed.password is not None
            or parsed.port not in (None, 443)
            or not (host in ("apps.apple.com", "itunes.apple.com")
                    or re.fullmatch(r"is[0-9]+-ssl\.mzstatic\.com", host))):
        raise ValueError("Only public Apple storefront and artwork HTTPS URLs are allowed")


class AppleRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url):
    validate_url(url)
    request = urllib.request.Request(url, headers={
        "User-Agent": "KeiHome-IconResearch/1.0",
        "Accept-Language": "en-US,en;q=0.9",
    })
    with urllib.request.build_opener(AppleRedirects()).open(request, timeout=25) as response:
        body = response.read(MAX_RESPONSE + 1)
    if len(body) > MAX_RESPONSE:
        raise ValueError("Apple response exceeds the research size limit")
    return body


def normalized_name(name):
    return "".join(c for c in unicodedata.normalize("NFKC", name).casefold() if c.isalnum())


def search_candidates(term, country, get=fetch):
    url = "https://itunes.apple.com/search?" + urllib.parse.urlencode({
        "term": term, "country": country, "entity": "software", "limit": 12,
    })
    response = json.loads(get(url))
    return [{
        "appStoreId": item["trackId"],
        "name": item["trackName"],
        "bundleId": item.get("bundleId"),
        "publisher": item.get("sellerName") or item.get("artistName"),
        "exactName": normalized_name(term) == normalized_name(item["trackName"]),
    } for item in response.get("results", [])
        if item.get("kind") == "software" and item.get("trackId") and item.get("trackName")]


def metadata_url(app_id, country):
    if not re.fullmatch(r"[0-9]{6,12}", str(app_id)):
        raise ValueError("Expected an App Store application ID")
    if not re.fullmatch(r"[a-z]{2}", country):
        raise ValueError("Expected a two-letter storefront")
    query = urllib.parse.urlencode({
        "platform": "web", "additionalPlatforms": "iphone", "extend": "iconArtwork",
        "fields[apps]": "name,artwork,iconArtwork", "l": "en-US",
    }, safe=",[]")
    return f"https://apps.apple.com/api/apps/v1/catalog/{country}/apps/{app_id}?{query}"


def select_artwork(document, app_id):
    matches = [item for item in document.get("data", [])
               if item.get("id") == str(app_id) and item.get("type") == "apps"]
    if len(matches) != 1:
        raise ValueError("The storefront did not return exactly the requested application")
    attrs = matches[0].get("attributes", {})
    ios = attrs.get("platformAttributes", {}).get("ios", {})
    for field in ("iconArtwork", "artwork"):
        artwork = ios.get(field)
        if isinstance(artwork, dict) and isinstance(artwork.get("url"), str):
            return attrs.get("name", str(app_id)), field, artwork["url"]
    raise ValueError("No iOS artwork URL in the storefront response")


def artwork_url(template, resolution, corners, appearance):
    if resolution not in RESOLUTIONS or corners not in ("ios", "original"):
        raise ValueError("Unsupported image size or corner treatment")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,40}", appearance):
        raise ValueError("Invalid appearance probe")
    if any(token not in template for token in ("{w}", "{h}", "{c}", "{f}")):
        raise ValueError("Unexpected Apple artwork template")
    url = template.replace("{w}", str(resolution)).replace("{h}", str(resolution))
    url = url.replace("{c}", "Icon.ApSiOS01" if corners == "ios" else "bb").replace("{f}", "png")
    parsed = urllib.parse.urlsplit(url)
    query = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    query = [(key, value) for key, value in query if key != "appearance"]
    query.append(("appearance", appearance))
    url = urllib.parse.urlunsplit(parsed._replace(query=urllib.parse.urlencode(query)))
    validate_url(url)
    if not re.fullmatch(r"is[0-9]+-ssl\.mzstatic\.com", urllib.parse.urlsplit(url).hostname or ""):
        raise ValueError("Artwork must be served by the Apple image CDN")
    return url


def image_fingerprint(body, resolution):
    with Image.open(io.BytesIO(body)) as image:
        if image.format != "PNG" or image.size != (resolution, resolution):
            raise ValueError("Expected a PNG with exactly the requested dimensions")
        rgba = image.convert("RGBA")
        low, high = rgba.getchannel("A").getextrema()
        return {
            "bytes": len(body),
            "sha256": hashlib.sha256(body).hexdigest(),
            "rgbaSha256": hashlib.sha256(rgba.tobytes()).hexdigest(),
            "alphaRange": [low, high],
        }


def classify_appearances(results):
    """A successful HTTP response alone does not prove a requested appearance."""
    light = results.get("light", {}).get("rgbaSha256")
    control = results.get("unsupported-control", {}).get("rgbaSha256")
    for appearance, result in results.items():
        fingerprint = result.get("rgbaSha256")
        if not fingerprint:
            result["status"] = "failed"
        elif appearance == "light":
            result["status"] = "baseline"
        elif appearance == "unsupported-control":
            result["status"] = "control"
        elif not light:
            result["status"] = "unconfirmed"
        elif fingerprint == light or fingerprint == control:
            result["status"] = "unconfirmed-identical"
        else:
            result["status"] = "distinct-pixels"


def load_mapping(path):
    mapping = json.loads(Path(path).read_text())
    if not isinstance(mapping, dict) or not mapping:
        raise ValueError("Mapping must be a nonempty object: Android package -> confirmed App Store ID")
    for package, app_id in mapping.items():
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", package):
            raise ValueError("Invalid Android package in mapping")
        if isinstance(app_id, bool) or not re.fullmatch(r"[0-9]{6,12}", str(app_id)):
            raise ValueError("Invalid App Store ID in mapping")
    return {package: str(app_id) for package, app_id in mapping.items()}


def export_pack(path, report, mapping, images):
    resources = ET.Element("resources")
    description = ET.Element("MIUI-Theme")
    ET.SubElement(description, "title").text = "iOS artwork research"
    ET.SubElement(description, "author").text = "Original app publishers / Apple artwork service"
    ET.SubElement(description, "description").text = (
        "Explicitly confirmed Android-to-App-Store mappings. Static source artwork; "
        "no MAML, simulated glass overlay, or live wallpaper refraction."
    )
    files = {}
    for package, app_id in sorted(mapping.items()):
        appearances = report["apps"].get(app_id, {}).get("appearances", {})
        if "light" not in images.get(app_id, {}):
            raise ValueError(f"No validated light artwork for {app_id}; pack not written")
        drawable = "ios_" + app_id
        ET.SubElement(resources, "item", component=f"ComponentInfo{{{package}}}", drawable=drawable)
        files[f"res/drawable-nodpi/{drawable}.png"] = images[app_id]["light"]
        if appearances.get("dark", {}).get("status") == "distinct-pixels":
            files[f"res/drawable-night-nodpi/{drawable}.png"] = images[app_id]["dark"]
    files["appfilter.xml"] = ET.tostring(resources, encoding="utf-8", xml_declaration=True)
    files["description.xml"] = ET.tostring(description, encoding="utf-8", xml_declaration=True)
    files["ios-source-manifest.json"] = json.dumps({
        "schema": 1, "mapping": mapping, "research": report,
    }, ensure_ascii=False, indent=2).encode()
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        with zipfile.ZipFile(temporary, "w") as archive:
            for name, body in sorted(files.items()):
                archive.writestr(name, body, compress_type=(
                    zipfile.ZIP_STORED if name.endswith(".png") else zipfile.ZIP_DEFLATED))
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-id", action="append", default=[])
    parser.add_argument("--mapping", type=Path, help="User-confirmed Android package -> App Store ID JSON")
    parser.add_argument("--metadata-dir", type=Path, help="Reuse previously saved ID-metadata.json responses")
    parser.add_argument("--search", help="Find candidates only; never automatically assigns a namesake")
    parser.add_argument("--country", default="us")
    parser.add_argument("--resolution", type=int, choices=RESOLUTIONS, default=512)
    parser.add_argument("--corners", choices=("ios", "original"), default="ios")
    parser.add_argument("--probe", action="append", default=[], help="Experimental appearance parameter")
    parser.add_argument("--output", type=Path, required=True, help="New research output directory")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z]{2}", args.country):
        parser.error("country must be a lowercase two-letter storefront")
    mapping = load_mapping(args.mapping) if args.mapping else {}
    app_ids = list(dict.fromkeys([*map(str, args.app_id), *mapping.values()]))
    if len(app_ids) > 12 or (not app_ids and not args.search):
        parser.error("Select 1-12 IDs, a mapping, or a search term")
    if args.output.exists():
        parser.error("output directory already exists; choose a new directory to preserve evidence")
    for app_id in app_ids:
        metadata_url(app_id, args.country)
    args.output.mkdir(parents=True)
    report = {
        "schema": 1, "country": args.country, "resolution": args.resolution,
        "corners": args.corners, "apps": {}, "createdAtUnix": int(time.time()),
        "limitations": [
            "Distinct pixels are evidence of different output, not proof of a realtime material.",
            "Unconfirmed-identical may mean an unsupported parameter or identical app variants.",
            "Transparent icon corners do not prove a transparent/glass icon body.",
            "Experimental probes are never exported as supported icon-pack variants.",
        ],
    }
    if args.search:
        report["candidates"] = search_candidates(args.search, args.country)
        print(json.dumps(report["candidates"], ensure_ascii=False, indent=2))
    images = {}
    appearances = list(dict.fromkeys([*BASE_APPEARANCES, *args.probe, "unsupported-control"]))
    report_path = args.output / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    last_metadata = 0.0
    for app_id in app_ids:
        url = metadata_url(app_id, args.country)
        try:
            if args.metadata_dir:
                body = (args.metadata_dir / f"{app_id}-metadata.json").read_bytes()
                if len(body) > MAX_RESPONSE:
                    raise ValueError("Cached response exceeds the research size limit")
            else:
                delay = 3.5 - (time.monotonic() - last_metadata)
                if delay > 0:
                    time.sleep(delay)
                last_metadata = time.monotonic()
                body = fetch(url)
            name, source, template = select_artwork(json.loads(body), app_id)
        except Exception as failure:
            report["metadataFailure"] = {"appStoreId": app_id, "error": str(failure)}
            if isinstance(failure, urllib.error.HTTPError):
                report["metadataFailure"]["httpStatus"] = failure.code
                report["metadataFailure"]["retryAfter"] = failure.headers.get("Retry-After")
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            raise SystemExit("Metadata unavailable; stopped without retrying or exporting a partial pack. "
                             f"See {report_path}") from None
        (args.output / f"{app_id}-metadata.json").write_bytes(body)
        item = {"name": name, "source": source, "metadataUrl": url, "appearances": {}}
        item["metadataOrigin"] = "saved-response" if args.metadata_dir else "network"
        report["apps"][app_id] = item
        images[app_id] = {}
        for appearance in appearances:
            result = {}
            item["appearances"][appearance] = result
            try:
                image_url = artwork_url(template, args.resolution, args.corners, appearance)
                image = fetch(image_url)
                result.update(image_fingerprint(image, args.resolution))
                result["url"] = image_url
                filename = f"{app_id}-{appearance}.png"
                (args.output / filename).write_bytes(image)
                result["file"] = filename
                images[app_id][appearance] = image
            except Exception as failure:
                result["error"] = str(failure)
        classify_appearances(item["appearances"])
        print(app_id, name, {k: v["status"] for k, v in item["appearances"].items()})
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    if mapping:
        export_pack(args.output / "ios-icon-pack.zip", report, mapping, images)


if __name__ == "__main__":
    main()
