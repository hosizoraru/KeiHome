#!/usr/bin/env python3
"""Ask the inspected ColorOS system downloader for its icon catalog without installing apps.

Only native artwork requests and icon-cache/catalog reads are made on the explicit source.
Capture the resulting files with capture-coloros-runtime-icons.py afterwards.
"""
import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FRAMEWORK_SHA256 = "52c4783bc6027756b56dcb7780d838dd4b36b9726b28f4db2f0a03d49237a0ae"
DOWNLOADER_SHA256 = "30f540a0facbec13fee28ad896e7516d58cb4be9149f63e14f4a8182224e9dc1"
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")


def catalog(whitelist, database):
    root = ET.fromstring(whitelist)
    white = {(row.text or "").strip() for row in root.findall("package_name")} - {""}
    with closing(sqlite3.connect(database)) as db:
        rows = db.execute("SELECT package_name,status,aid_status FROM icon").fetchall()
    known = {row[0] for row in rows}
    requested = sorted(name for name in white | known if PACKAGE.fullmatch(name))
    if not requested or len(requested) > 5000:
        raise ValueError("Unexpected native icon catalog size")
    return requested, {"whitelistVersion": root.findtext("version"),
                       "whitelistPackages": sorted(white), "databasePackages": sorted(known),
                       "excludedInternalNames": sorted((white | known) - set(requested)),
                       "nativeStates": {name: {"status": status, "state": state}
                                        for name, status, state in rows if PACKAGE.fullmatch(name)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--report", type=Path, default=ROOT / ".artifacts/inputs/coloros/cloud-fetch.json")
    parser.add_argument("--audit-only", action="store_true", help="Read catalog and cache without requesting downloads")
    parser.add_argument("--settle-seconds", type=int, default=30)
    args = parser.parse_args()
    if not 0 <= args.settle_seconds <= 300:
        parser.error("--settle-seconds must be 0..300")

    def root(command):
        return subprocess.check_output(["adb", "-s", args.serial, "shell", "-T", "su -c " + shlex.quote(command)], timeout=45)

    if root("sha256sum /system/framework/oplus-framework.jar").decode().split()[0] != FRAMEWORK_SHA256:
        raise ValueError("Source framework differs from the inspected downloader contract")
    if root("sha256sum /system_ext/app/UXDesign/UXDesign.apk").decode().split()[0] != DOWNLOADER_SHA256:
        raise ValueError("Source downloader differs from the inspected native service contract")
    whitelist = root("cat /data/oplus/uxicons/morph_icons_whitelist.xml")
    def read_catalog():
        with tempfile.TemporaryDirectory(prefix="keihome-icon-catalog-") as scratch:
            database = Path(scratch) / "icon.db"
            database.write_bytes(root("cat /data/user/0/com.oplus.uxdesign/databases/icon.db"))
            return catalog(whitelist, database)
    packages, report = read_catalog()
    report.update({"schema": 1, "serial": args.serial, "capturedAt": datetime.now(timezone.utc).isoformat(),
                   "frameworkSha256": FRAMEWORK_SHA256, "downloaderSha256": DOWNLOADER_SHA256,
                   "whitelistSha256": hashlib.sha256(whitelist).hexdigest(),
                   "packages": packages, "auditOnly": args.audit_only})
    errors = []
    if not args.audit_only:
        for offset in range(0, len(packages), 50):
            batch = packages[offset:offset + 50]
            command = ("am startservice -n com.oplus.uxdesign/.icon.service.LaunchIconService "
                       "--esal android.intent.extra.PACKAGES " + shlex.quote(",".join(batch)) + " --ei icon_theme 2")
            try:
                result = root(command).decode()
                if "Error:" in result or "Exception" in result:
                    raise RuntimeError(result[:512])
            except (subprocess.SubprocessError, RuntimeError) as failure:
                errors.append({"packages": batch, "error": str(failure)[:512]})
            print(f"Requested {offset + len(batch)}/{len(packages)}", flush=True)
            time.sleep(0.3)
        # Native workers own HTTP, integrity checks, extraction and retries.
        for _ in range(args.settle_seconds):
            time.sleep(1)
        # Report the post-download native states, rather than the initial request catalog.
        _, refreshed = read_catalog()
        report.update(refreshed)
    files = root("find /data/oplus/uxicons -mindepth 2 -maxdepth 2 -type f -name '*.png'").decode().splitlines()
    cached = {}
    for path in files:
        relative = Path(path).relative_to("/data/oplus/uxicons")
        if len(relative.parts) == 2 and PACKAGE.fullmatch(relative.parts[0]):
            cached.setdefault(relative.parts[0], []).append(relative.parts[1])
    report.update({"requestErrors": errors, "cachedFiles": {key: sorted(value) for key, value in sorted(cached.items())},
                   "noCachedArtwork": sorted(set(packages) - set(cached))})
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Catalog: {len(packages)} packages; cached artwork: {len(cached)}; without cached artwork: {len(report['noCachedArtwork'])}")
    print("Report:", args.report)


if __name__ == "__main__":
    main()
