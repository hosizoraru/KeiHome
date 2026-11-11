#!/usr/bin/env python3
"""Bounded, offline DexSQL CLI verification; never executes DexKit or Android."""

import argparse
import hashlib
import json
import re
import shlex
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
INPUTS = ROOT / ".artifacts/inputs/securitycenter"
OUTPUTS = ROOT / ".artifacts/research/securitycenter-matrix-20261111/dexsql"
RESEARCH = ROOT / ".artifacts/research"
MAX_FIXTURES = 16
SOURCE = ROOT / "app/src/main/kotlin/os/kei/hyper/home/xposed/HyperOsSidebarProfile.kt"
STRING = "Ljava/lang/String;"
IMAGE = "Landroid/widget/ImageView;"
BITMAP = "Landroid/graphics/Bitmap;"
VIEW = "Landroid/view/View;"
PKG = "Landroid/content/pm/PackageInfo;"
DOCK = "Lcom/miui/dock/"
URI = ("pkg_icon://", "pkg_icon_xspace://", "pkg_work_profile://", "?userId=")
METHOD_COLUMNS = (
    "descriptor, class_descriptor, name, return_descriptor, param_count, "
    "param_descriptors, access_flags, file_id, method_idx"
)


def dump(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=True) + "\n")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def literals(values):
    values = sorted(set(values))
    if not values or len(values) > 4096:
        raise RuntimeError("SQL selector must contain 1..4096 values")
    return "(" + ",".join("'" + value.replace("'", "''") + "'" for value in values) + ")"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def single(items, role):
    require(len(items) == 1, f"{role}: expected one match, found {len(items)}: {items}")
    return items[0]


def select_inputs(root, only=None):
    apks = sorted(root.glob("*/securitycenter.apk"))
    require(1 <= len(apks) <= MAX_FIXTURES,
            f"Expected 1..{MAX_FIXTURES} archived inputs, got {len(apks)}")
    selected = [apk for apk in apks if only is None or only in apk.parent.name]
    require(selected, f"No archived input matches --only {only!r}")
    return selected


def claim_output(path, research=RESEARCH):
    path = path.resolve()
    research = research.resolve()
    require(path.is_relative_to(research) and path != research,
            "Output root must be a new directory below .artifacts/research")
    # Exclusive creation also preserves partial runs without a run.json receipt.
    path.mkdir(parents=True, exist_ok=False)
    return path


def table_rows(text):
    # CLI 0.1.1 emits ASCII tables; selected values cannot contain pipes/newlines.
    lines = [line for line in text.splitlines() if line.startswith("|")]
    if not lines:
        # Empty results may be emitted without a header by some CLI versions.
        require("0 rows" in text.lower() or "+-" in text or "++" in text,
                "Unrecognized CLI table")
        return []
    header = [part.strip() for part in lines[0][1:-1].split("|")]
    result = []
    for line in lines[1:]:
        values = [part.strip() for part in line[1:-1].split("|")]
        require(len(values) == len(header), "Malformed/truncated CLI row")
        result.append(dict(zip(header, values)))
    return result


def static(method):
    return bool(int(method["access_flags"]) & 8)


def concrete(method, is_static):
    flags = int(method["access_flags"])
    return bool(flags & 1) and bool(flags & 8) == is_static and not (flags & (256 | 1024))


def params(method):
    return method["param_descriptors"].split(",") if int(method["param_count"]) else []


def image_request(method):
    return (
        method["return_descriptor"] == "V"
        and 2 <= int(method["param_count"]) <= 6
        and params(method)[:2] == [STRING, IMAGE]
    )


def package_metadata(badging):
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
                      badging, re.MULTILINE)
    require(match is not None, "Cannot parse aapt2 package metadata")
    metadata = dict(zip(("package", "versionCode", "versionName"), match.groups()))
    require(metadata["package"] == "com.miui.securitycenter", "Wrong APK package")
    return metadata


def fixture_inventory(apks, runner):
    fixtures = []
    for apk in apks:
        provenance = json.loads((apk.parent / "provenance.json").read_text())
        size = apk.stat().st_size
        require(1 <= size <= 512 * 1024 * 1024, "APK outside production fingerprint size budget")
        digest = sha256(apk)
        require(digest == provenance["sha256"], f"SHA256 provenance mismatch: {apk}")
        require(size == provenance["bytes"], f"Size provenance mismatch: {apk}")
        identifier = apk.parent.name.split("_", 1)[-1]
        metadata = package_metadata(runner.run(
            [runner.aapt2, "dump", "badging", apk], f"inventory-{identifier}-badging"))
        fixtures.append({
            "id": identifier, "file": identifier + ".apk",
            "apk": str(apk.relative_to(ROOT)), "sha256": digest, "bytes": size, **metadata,
        })
    require(len({fixture["file"] for fixture in fixtures}) == len(fixtures),
            "Duplicate device fixture filename")
    return {"schema": 1, "fixtures": fixtures}


def write_device_push(output, inventory):
    destination = "cache/keihome-sidebar-matrix"
    run_as = 'adb -s "$SIDEBAR_SERIAL" shell -T run-as os.kei.hyper.home '
    lines = [
        "#!/usr/bin/env bash", "set -euo pipefail",
        ': "${SIDEBAR_SERIAL:?Set SIDEBAR_SERIAL to the main-owned Android target}"',
        run_as + f"mkdir -p {destination}",
    ]
    for fixture in inventory["fixtures"]:
        lines.append(run_as + "tee " + shlex.quote(destination + "/" + fixture["file"])
                     + " < " + shlex.quote(str(ROOT / fixture["apk"])) + " > /dev/null")
    lines.append(run_as + "tee " + destination + "/device-fixtures.json"
                 + " < " + shlex.quote(str(output / "device-fixtures.json")) + " > /dev/null")
    (output / "device-push.sh").write_text("\n".join(lines) + "\n")


class Runner:
    def __init__(self, output, dexsql, aapt2, timeout):
        self.output = output
        self.dexsql = dexsql
        self.aapt2 = aapt2
        self.timeout = timeout
        self.calls = 0
        self.started = time.monotonic()
        self.total_bytes = 0
        self.log = output / "commands.jsonl"

    def run(self, argv, stem, timeout=None):
        self.calls += 1
        require(self.calls <= 110, "Per-APK subprocess budget (110) exceeded")
        remaining_seconds = 480 - (time.monotonic() - self.started)
        require(remaining_seconds > 0, "Per-APK wall budget (480s) exceeded")
        command_timeout = min(timeout or self.timeout, remaining_seconds)
        before = time.monotonic()
        record = {"argv": list(map(str, argv)), "shell": shlex.join(list(map(str, argv)))}
        try:
            proc = subprocess.run(
                list(map(str, argv)), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=command_timeout, check=False,
            )
            stdout, stderr = proc.stdout, proc.stderr
            record["returncode"] = proc.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr = error.stdout or b"", error.stderr or b""
            record["timeout"] = command_timeout
        remaining_bytes = 32 * 1024 * 1024 - self.total_bytes
        overflow = len(stdout) + len(stderr) > remaining_bytes
        stdout = stdout[:remaining_bytes]
        stderr = stderr[:remaining_bytes - len(stdout)]
        self.total_bytes += len(stdout) + len(stderr)
        record["output_truncated_by_budget"] = overflow
        (self.output / f"{stem}.stdout.txt").write_bytes(stdout)
        (self.output / f"{stem}.stderr.txt").write_bytes(stderr)
        record.update(stem=stem, elapsed_seconds=round(time.monotonic() - before, 4))
        with self.log.open("a") as handle:
            handle.write(json.dumps(record, ensure_ascii=True) + "\n")
        require(not overflow, "Per-APK evidence budget (32MiB) exceeded")
        require(record.get("returncode") == 0, f"{stem}: command failed: {record}")
        return stdout.decode("utf-8")


class Analysis:
    def __init__(self, runner, apk):
        self.runner = runner
        self.apk = apk
        self.sequence = 0
        self.methods = {}
        self.calls = {}
        self.reads = {}

    def query(self, label, sql, budget=4096):
        self.sequence += 1
        stem = f"{self.sequence:02d}-{label}"
        sql = sql.rstrip().rstrip(";") + f" LIMIT {budget + 1}"
        (self.runner.output / f"{stem}.sql").write_text(sql + ";\n")
        text = self.runner.run(
            [self.runner.dexsql, "-t", "2", "query", "--input", self.apk,
             "--sql", sql, "--limit", str(budget + 1), "--timing"], stem,
        )
        rows = table_rows(text)
        require(len(rows) <= budget, f"{label}: row budget ({budget}) exceeded")
        dump(self.runner.output / f"{stem}.rows.json", rows)
        return rows

    def metadata(self, descriptors):
        missing = set(descriptors) - self.methods.keys()
        if missing:
            rows = self.query(
                "method-definitions",
                f"SELECT {METHOD_COLUMNS} FROM methods WHERE descriptor IN {literals(missing)} "
                "AND access_flags IS NOT NULL ORDER BY descriptor, file_id, method_idx",
            )
            for row in rows:
                require(row["descriptor"] not in self.methods,
                        f"Duplicate method definition: {row['descriptor']}")
                self.methods[row["descriptor"]] = row
        return [self.methods[d] for d in sorted(set(descriptors)) if d in self.methods]

    def edges(self, descriptors):
        missing = set(descriptors) - self.calls.keys()
        if missing:
            rows = self.query(
                "call-edges",
                "SELECT DISTINCT method, callee FROM method_calls "
                f"WHERE method IN {literals(missing)} ORDER BY method, callee",
            )
            self.calls.update({d: [] for d in missing})
            for row in rows:
                self.calls[row["method"]].append(row["callee"])
        return {d: self.calls[d] for d in descriptors}

    def fields(self, descriptors):
        missing = set(descriptors) - self.reads.keys()
        if missing:
            rows = self.query(
                "field-reads",
                "SELECT DISTINCT method, field, field_class, field_type FROM method_field_accesses "
                f"WHERE method IN {literals(missing)} AND using_type = 'get' ORDER BY method, field",
            )
            self.reads.update({d: [] for d in missing})
            for row in rows:
                self.reads[row["method"]].append(row)
        return {d: self.reads[d] for d in descriptors}

    def callees(self, descriptor):
        self.edges([descriptor])
        return self.metadata(self.calls[descriptor])

    def ancestors(self, start):
        chain = []
        current = start
        for _ in range(12):
            require(current not in chain, "Hierarchy cycle")
            chain.append(current)
            if current == "Ljava/lang/Object;":
                return chain
            rows = self.query(
                "class-hierarchy",
                "SELECT descriptor, superclass_descriptor, access_flags FROM classes "
                f"WHERE descriptor = {literals([current])[1:-1]} ORDER BY descriptor, file_id", 4,
            )
            current = single(rows, f"defined hierarchy class {current}")["superclass_descriptor"]
        raise RuntimeError("Superclass budget (12) exceeded")

    def analyze(self):
        seeds = self.query(
            "uri-seeds",
            f"SELECT {','.join('m.' + c.strip() for c in METHOD_COLUMNS.split(','))} "
            "FROM methods m JOIN (SELECT file_id, method_idx FROM method_strings "
            f"WHERE value IN {literals(URI[:2])} GROUP BY file_id, method_idx "
            "HAVING COUNT(DISTINCT value) = 2) s "
            "ON m.file_id = s.file_id AND m.method_idx = s.method_idx "
            "WHERE m.return_descriptor = 'V' AND m.access_flags IS NOT NULL "
            "ORDER BY m.descriptor, m.file_id, m.method_idx", 128,
        )
        self.methods.update({row["descriptor"]: row for row in seeds})
        strings = self.query(
            "seed-uri-strings",
            "SELECT DISTINCT method, value FROM method_strings "
            f"WHERE method IN {literals(row['descriptor'] for row in seeds)} "
            f"AND value IN {literals(URI)} ORDER BY method, value",
        )
        string_map = {row["descriptor"]: set() for row in seeds}
        for row in strings:
            string_map[row["method"]].add(row["value"])
        self.edges([row["descriptor"] for row in seeds])
        all_callees = {d for calls in self.calls.values() for d in calls}
        self.metadata(all_callees)

        grids = [
            row for row in seeds if not static(row) and len(params(row)) == 1
            and params(row)[0].startswith(DOCK + "allapps/")
            and any(c["class_descriptor"] == params(row)[0] and c["return_descriptor"] == PKG
                    for c in self.callees(row["descriptor"]))
        ]
        grid = single(grids, "all-app binder")
        gd = grid["descriptor"]
        grid_requests = [m for m in self.callees(gd) if image_request(m) and static(m)]
        owner = single(sorted({m["class_descriptor"] for m in grid_requests}), "request owner")
        rails = [
            row for row in seeds if row["descriptor"] != gd and not static(row)
            and len(params(row)) == 1 and set(URI).issubset(string_map[row["descriptor"]])
            and "Landroid/widget/ImageView;->setImageBitmap(Landroid/graphics/Bitmap;)V"
            in self.calls[row["descriptor"]]
            and any(m["class_descriptor"] == owner and image_request(m)
                    for m in self.callees(row["descriptor"]))
        ]
        # Final rail discrimination includes a same-class model getter consumed by Dock.
        sibling_getters = self.query(
            "rail-sibling-getters",
            f"SELECT {METHOD_COLUMNS} FROM methods WHERE class_descriptor IN "
            f"{literals(row['class_descriptor'] for row in rails)} AND param_count = 0 "
            f"AND return_descriptor NOT IN {literals(['V', STRING, BITMAP, 'Z'])} "
            "AND access_flags IS NOT NULL ORDER BY descriptor, file_id, method_idx", 128,
        )
        self.methods.update({row["descriptor"]: row for row in sibling_getters})
        getters = [row["descriptor"] for row in sibling_getters]
        self.fields(getters)
        callers = self.query(
            "dock-getter-callers",
            "SELECT DISTINCT method, callee FROM method_calls WHERE callee IN "
            f"{literals(getters)} AND method_class LIKE '{DOCK}%' ORDER BY method, callee", 128,
        )
        dock_getters = {row["callee"] for row in callers}
        matching_getters = [
            m for m in sibling_getters if m["descriptor"] in dock_getters
            and any(f["field_class"] == m["class_descriptor"]
                    and f["field_type"] == m["return_descriptor"]
                    for f in self.reads[m["descriptor"]])
        ]
        rail = single(
            [r for r in rails if any(g["class_descriptor"] == r["class_descriptor"]
                                    for g in matching_getters)], "rail binder",
        )
        rd = rail["descriptor"]
        rail_requests = [
            m for m in self.callees(rd) if m["class_descriptor"] == owner
            and image_request(m) and static(m)
        ]
        requests = {m["descriptor"]: m for m in grid_requests + rail_requests}
        require(1 <= len(requests) <= 8, "Request budget 1..8")
        natives = {}
        request_native_edges = {}
        for descriptor in sorted(requests):
            native = [m for m in self.callees(descriptor) if image_request(m) and not static(m)]
            single(sorted({m["class_descriptor"] for m in native}), "native owner per request")
            natives.update({m["descriptor"]: m for m in native})
            request_native_edges[descriptor] = [m["descriptor"] for m in native]
        native_owner = single(sorted({m["class_descriptor"] for m in natives.values()}),
                              "shared native image loader")
        cancel_candidates = self.query(
            "cancellation-candidates",
            f"SELECT {METHOD_COLUMNS} FROM methods WHERE class_descriptor = "
            f"{literals([owner])[1:-1]} AND return_descriptor = 'V' AND param_count = 1 "
            f"AND param_descriptors = '{IMAGE}' AND access_flags IS NOT NULL "
            "ORDER BY descriptor, file_id, method_idx", 16,
        )
        self.methods.update({row["descriptor"]: row for row in cancel_candidates})
        cancels = [
            m for m in cancel_candidates if static(m)
            and any(n["class_descriptor"] == native_owner and n["return_descriptor"] == "V"
                    and params(n) == [IMAGE] for n in self.callees(m["descriptor"]))
        ]
        require(len(cancels) <= 1, "Ambiguous cancellation")
        cancel = cancels[0] if cancels else None
        native_cancel = None
        if cancel:
            native_cancel = single(
                [m for m in self.callees(cancel["descriptor"])
                 if m["class_descriptor"] == native_owner and m["return_descriptor"] == "V"
                 and params(m) == [IMAGE] and not static(m)], "native cancellation",
            )

        self.fields([rd])
        reads = self.reads[rd]
        model = single([
            f for f in reads if f["field_class"] == rail["class_descriptor"]
            and any(r["field_class"] == f["field_type"] and r["field_type"] == STRING for r in reads)
            and any(r["field_class"] == f["field_type"] and r["field_type"] == "I" for r in reads)
        ], "rail model")
        package = single([f for f in reads if f["field_class"] == model["field_type"]
                          and f["field_type"] == STRING], "rail package")
        uid = single([f for f in reads if f["field_class"] == model["field_type"]
                      and f["field_type"] == "I"], "rail UID")
        image = single([f for f in reads if f["field_type"] == IMAGE
                        and f["field_class"] != rail["class_descriptor"]], "rail image")
        package_getter = single([
            m for m in self.callees(gd) if m["class_descriptor"] == params(grid)[0]
            and m["return_descriptor"] == PKG and not params(m)
        ], "grid package getter")
        image_getter = single([
            m for m in self.callees(gd) if m["return_descriptor"] == IMAGE
            and not params(m) and not static(m)
        ], "grid image getter")
        self.fields([image_getter["descriptor"]])
        grid_image = single([f for f in self.reads[image_getter["descriptor"]]
                             if f["field_type"] == IMAGE], "grid image field")
        for getter in (package_getter, image_getter):
            self.edges([getter["descriptor"]])
            require(not self.calls[getter["descriptor"]], "Grid getter invokes another method")
        model_getter = single([
            m for m in matching_getters if m["class_descriptor"] == rail["class_descriptor"]
            and m["return_descriptor"] == model["field_type"]
            and any(f["field"] == model["field"] for f in self.reads[m["descriptor"]])
        ], "Dock rail model getter")
        dock_caller = min(row["method"] for row in callers if row["callee"] == model_getter["descriptor"])
        self.metadata([dock_caller])

        holder_chain = self.ancestors(params(rail)[0])
        image_holder_chain = self.ancestors(image["field_class"])
        grid_chain = self.ancestors(grid["class_descriptor"])
        fields = self.query(
            "hierarchy-field-definitions",
            "SELECT f.descriptor, f.class_descriptor, f.type_descriptor, cf.access_flags "
            "FROM fields f JOIN class_fields cf ON f.file_id = cf.file_id AND f.field_idx = cf.field_idx "
            f"WHERE f.class_descriptor IN {literals(holder_chain + [image['field_class']])} "
            f"OR f.descriptor IN {literals(f['field'] for f in [model, package, uid, grid_image])} "
            "ORDER BY f.descriptor, f.file_id", 256,
        )
        root = single([
            f for f in fields if f["class_descriptor"] in holder_chain
            and f["type_descriptor"] == VIEW and not (int(f["access_flags"]) & 8)
        ], "rail holder root")
        selected_fields = [f["field"] for f in (model, package, uid, image, grid_image)] + [root["descriptor"]]
        for descriptor in selected_fields:
            field = single([f for f in fields if f["descriptor"] == descriptor],
                           f"defined field {descriptor}")
            require(not (int(field["access_flags"]) & 8), f"Static binding field: {descriptor}")
        require(params(rail)[0] in image_holder_chain, "Rail image declaring class incompatible with holder")
        require(image_getter["class_descriptor"] in grid_chain, "Grid image getter owner incompatible")
        require(grid_image["field_class"] in grid_chain, "Grid image field owner incompatible")
        for binder in (grid, rail):
            require(concrete(binder, False), "Binder is not public/concrete/instance")
        for getter in (package_getter, model_getter):
            require(concrete(getter, False), "Getter is not public/concrete/instance")
        require(not static(image_getter) and not (int(image_getter["access_flags"]) & 1024),
                "Grid image getter static/abstract")
        for method in requests.values():
            require(concrete(method, True), "Request is not public/concrete/static")
        for method in natives.values():
            require(concrete(method, False), "Native loader is not public/concrete/instance")
        if cancel:
            require(concrete(cancel, True) and concrete(native_cancel, False), "Cancellation modifiers")
        self.edges([model_getter["descriptor"]])
        require(not self.calls[model_getter["descriptor"]], "Rail model getter not local")
        require({f["field"] for f in self.reads[model_getter["descriptor"]]} == {model["field"]},
                "Rail model getter reads additional fields")
        require({f["field"] for f in self.reads[image_getter["descriptor"]]} == {grid_image["field"]},
                "Grid image getter reads additional fields")

        roles = {
            "grid": gd, "rail": rd, "request_owner": owner, "native_owner": native_owner,
            "package_getter": package_getter["descriptor"], "image_getter": image_getter["descriptor"],
            "model_getter": model_getter["descriptor"], "dock_caller": dock_caller,
            "rail_model": model["field"], "rail_package": package["field"], "rail_uid": uid["field"],
            "rail_image": image["field"], "holder_root": root["descriptor"], "grid_image": grid_image["field"],
            "cancel": cancel["descriptor"] if cancel else None,
            "native_cancel": native_cancel["descriptor"] if native_cancel else None,
        }
        proof = {
            "status": "SQL_SHAPE_MATCH", "seed_count": len(seeds), "grid_match_count": len(grids),
            "rail_pre_getter_count": len(rails), "rail_match_count": 1,
            "roles": roles, "requests": sorted(requests), "native_requests": sorted(natives),
            "request_native_edges": request_native_edges,
            "grid_request_edges": sorted(m["descriptor"] for m in grid_requests),
            "rail_request_edges": sorted(m["descriptor"] for m in rail_requests),
            "dock_model_edge": [dock_caller, model_getter["descriptor"]],
            "rail_bitmap_edge": [rd, "Landroid/widget/ImageView;->setImageBitmap(Landroid/graphics/Bitmap;)V"],
            "selected_field_reads": {
                rd: [f["field"] for f in (model, package, uid, image)],
                image_getter["descriptor"]: [grid_image["field"]],
                model_getter["descriptor"]: [model["field"]],
            },
            "hierarchies": {"holder": holder_chain, "image_holder": image_holder_chain, "grid": grid_chain},
            "actual_dexkit_execution": False,
        }
        # Decode the selected paths independently of SQL's reference tables.
        targets = {gd, rd, package_getter["descriptor"], image_getter["descriptor"],
                   model_getter["descriptor"], dock_caller} | set(requests) | set(natives)
        if cancel:
            targets.update([cancel["descriptor"], native_cancel["descriptor"]])
        require(len(targets) <= 24, "Decode target budget (24) exceeded")
        proof["decodes"] = []
        decoded = {}
        for index, descriptor in enumerate(sorted(targets), 1):
            method = self.methods[descriptor]
            stem = f"decode-{index:02d}"
            decoded[descriptor] = self.runner.run(
                [self.runner.dexsql, "-t", "2", "decode", "--input", self.apk,
                 "--file-id", method["file_id"], "--method", descriptor], stem,
            )
            proof["decodes"].append({"descriptor": descriptor, "file": f"{stem}.stdout.txt"})
        proof_edges = [(gd, package_getter["descriptor"]), (gd, image_getter["descriptor"]),
                       (dock_caller, model_getter["descriptor"]), tuple(proof["rail_bitmap_edge"])]
        for binder, calls in ((gd, grid_requests), (rd, rail_requests)):
            proof_edges.extend((binder, m["descriptor"]) for m in calls)
        for request, calls in request_native_edges.items():
            proof_edges.extend((request, native) for native in calls)
        if cancel:
            proof_edges.append((cancel["descriptor"], native_cancel["descriptor"]))
        for caller, callee in proof_edges:
            require(any(line.strip().startswith("invoke-") and line.strip().endswith(", " + callee)
                        for line in decoded[caller].splitlines()),
                    f"SQL/decode call mismatch: {caller} -> {callee}")
        for method, fields in proof["selected_field_reads"].items():
            for field in fields:
                require(any(line.strip().startswith(("iget", "sget"))
                            and line.strip().endswith(", " + field)
                            for line in decoded[method].splitlines()),
                        f"SQL/decode read mismatch: {method} -> {field}")
        for method, strings in ((gd, URI[:2]), (rd, URI)):
            for string in strings:
                require(any(line.strip().startswith("const-string")
                            and line.strip().endswith(", " + json.dumps(string))
                            for line in decoded[method].splitlines()),
                        f"SQL/decode string mismatch: {method} -> {string}")
        proof["sql_decode_crosscheck"] = {
            "call_edges": len(proof_edges),
            "selected_field_reads": sum(map(len, proof["selected_field_reads"].values())),
            "uri_literals": 6,
            "status": "MATCH",
        }
        dump(self.runner.output / "shape-proof.json", proof)
        return proof


def report(output, results, source_hash, source_changed):
    lines = [
        "# Offline SecurityCenter DexSQL matrix",
        "",
        f"Generated: {datetime.now(timezone.utc).isoformat()}",
        "",
        f"Production source snapshot SHA256: `{source_hash}` (`production-contract.kt.txt`).",
        f"Source changed during run: `{source_changed}`. The snapshot is the contract inspected here.",
        "",
        "This is independent SQL/DEX shape evidence from DexSQL CLI, not an actual DexKit test. "
        "No APK was installed or executed. Runtime reflection, Android boot-class flags, resource lookup "
        "(`icon_view`), cache serialization/restoration, class-loader behavior and device behavior remain "
        "for the parent task. No production files, Gradle, ADB, network or commits are involved.",
        "",
        "## Results",
        "",
        "| versionName | versionCode | SHA256 prefix | URI seeds | grid / rail | requests | result |",
        "|---|---:|---|---:|---|---:|---|",
    ]
    for result in results:
        proof = result.get("proof", {})
        lines.append(
            f"| {result.get('versionName', '?')} | {result.get('versionCode', '?')} | "
            f"`{result['sha256'][:12]}` | {proof.get('seed_count', '?')} | "
            f"{proof.get('grid_match_count', '?')} / {proof.get('rail_match_count', '?')} | "
            f"{len(proof.get('requests', []))} | {result['status']} |"
        )
    version_groups = {}
    for result in results:
        version_groups.setdefault(result.get("versionCode"), []).append(result)
    for code, group in version_groups.items():
        if code and len({r["sha256"] for r in group}) > 1:
            lines += [
                "",
                f"Metadata collision: versionCode `{code}` belongs to distinct contents: "
                + ", ".join(f"`{r['versionName']}` (`{r['sha256'][:12]}`)" for r in group)
                + ". A versionCode-only key cannot distinguish these archives.",
            ]
    lines += [
        "",
        "## Verification boundaries",
        "",
        "- Each APK's bytes and SHA256 are checked against its existing provenance.json; "
        "aapt2 supplies package/versionCode/versionName rather than trusting archive filenames.",
        "- Seed query requires both exact URI literals on a void method, capped at 128; "
        "one instance allapps binder must invoke its parameter owner's PackageInfo getter.",
        "- Rail must use all four literals, call ImageView.setImageBitmap and the grid's request owner; "
        "a same-class field-reading model getter must have a named com.miui.dock caller.",
        "- Static request signatures are void(String, ImageView, ...), with 2..6 parameters and 1..8 "
        "distinct requests. Every request must directly invoke an instance request on one shared native owner.",
        "- Optional static cancellation must directly reach that same native owner's instance "
        "void(ImageView). Selected APK-defined methods' public/static/abstract/native flags are checked.",
        "- Rail model/package/UID/image fields and inherited instance View holder root are unique; "
        "grid image getter/field inheritance and rail holder assignability are checked from APK class metadata. "
        "Grid package/image and rail model getters have no method calls.",
        "- All raw calls/reads are retained in query output. shape-proof.json records the selected proof "
        "edges, as production record() does; omitted calls/reads are not asserted absent. Framework "
        "ImageView.setImageBitmap is an external reference and cannot be reflectively validated offline.",
        "- Descriptor definitions are reconciled across DEX files; duplicate definitions, row truncation, "
        "missing roles, tool errors and timeouts fail the APK. Errors never count as absence.",
        "- Selected SQL call edges, field reads and six URI literals are cross-checked against decoded "
        "instructions. Direct static call edges do not assert that every branch executes in one invocation.",
        "",
        "## Renaming and exact relations",
        "",
        "| versionName | grid owner | rail owner | rail model | image-holder owner |",
        "|---|---|---|---|---|",
    ]
    for result in results:
        roles = result.get("proof", {}).get("roles")
        if roles:
            lines.append(
                f"| {result['versionName']} | `{roles['grid'].split('->')[0]}` | "
                f"`{roles['rail'].split('->')[0]}` | `{roles['rail_model'].split(':', 1)[1]}` | "
                f"`{roles['rail_image'].split('->')[0]}` |"
            )
    lines += [
        "",
        "Only the selected archives were checked in this run. Exact descriptors for every row follow; "
        "role selection uses relationships, not obfuscated names, versions or hash admission. "
        "Historical results are retained in their original output roots.",
        "The device-fixtures.json inventory verifies bytes/provenance and package metadata for "
        "all archives, including unselected archives; it is not structural or runtime acceptance.",
    ]
    previous = None
    for result in results:
        lines += ["", f"### {result.get('versionName', result['id'])}", "",
                  f"Evidence: `{result['id']}/`; full SHA256: `{result['sha256']}`.", ""]
        proof = result.get("proof")
        if not proof:
            lines += [f"Failure: `{result.get('error', 'unknown')}`."]
            continue
        roles = proof["roles"]
        lines += ["| role | exact descriptor |", "|---|---|"]
        lines += [f"| {role} | `{descriptor}` |" for role, descriptor in roles.items()]
        if previous:
            changed = [key for key in roles if roles[key] != previous[key]]
            lines += ["", "Changed roles versus preceding matrix row: "
                      + (", ".join(f"`{key}`" for key in changed) if changed else "none") + "."]
        previous = roles
        lines += ["", "Direct loader edges:", ""]
        for binder_role in ("grid", "rail"):
            for request in proof[f"{binder_role}_request_edges"]:
                for native in proof["request_native_edges"][request]:
                    lines += [f"- `{roles[binder_role]}` -> `{request}` -> `{native}`"]
        lines += ["", f"Dock getter edge: `{roles['dock_caller']}` -> `{roles['model_getter']}`.",
                  "", f"Cached Bitmap edge: `{roles['rail']}` -> "
                  "`Landroid/widget/ImageView;->setImageBitmap(Landroid/graphics/Bitmap;)V`.",
                  "", "Exact selected reads, hierarchy chains and method-to-smali filenames are in "
                  "`shape-proof.json`; numbered SQL, raw CLI stdout/stderr and parsed rows sit alongside it."]
    lines += [
        "", "## Reproduction and bounds", "",
        "See scripts/tests/sidebar-profile/README.md. Every CLI argv and shell-rendered command, "
        "exit status and duration is recorded in each commands.jsonl; numbered .sql files preserve exact SQL. "
        f"Two DexSQL workers, {MAX_FIXTURES} APKs maximum, one APK at a time, "
        "110 subprocesses/480 seconds per APK, "
        "45 seconds per subprocess by default, 32 MiB retained stdout/stderr per APK, 4096 rows per general "
        "query, 128 seeds/getters/callers, hierarchy depth 12 and 24 decoded methods are hard bounds. "
        "Limits are enforced as failures, not silent partial success.",
        "",
    ]
    (output / "REPORT.md").write_text("\n".join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dexsql", default=shutil.which("dexsql"))
    parser.add_argument("--aapt2", default=str(Path.home() / "Library/Android/sdk/build-tools/37.0.0/aapt2"))
    parser.add_argument("--timeout", type=int, default=45, choices=range(1, 61))
    parser.add_argument("--only", help="Optional archive directory substring, still under fixed input root")
    parser.add_argument("--output-root", type=Path, default=OUTPUTS,
                        help="New directory below .artifacts/research; existing paths are refused")
    args = parser.parse_args()
    require(args.dexsql is not None, "dexsql not found")
    try:
        inventory_apks = select_inputs(INPUTS)
        apks = select_inputs(INPUTS, args.only)
        output_root = claim_output(args.output_root)
    except (RuntimeError, OSError) as error:
        parser.error(str(error))
    source = SOURCE.read_bytes()
    source_hash = hashlib.sha256(source).hexdigest()
    (output_root / "production-contract.kt.txt").write_bytes(source)
    run = {
        "started": datetime.now(timezone.utc).isoformat(), "argv": sys.argv,
        "source_path": str(SOURCE.relative_to(ROOT)), "source_sha256": source_hash,
        "runner_sha256": sha256(Path(__file__)),
        "output_root": str(output_root.relative_to(ROOT)),
        "selected_inputs": [str(apk.relative_to(ROOT)) for apk in apks],
        "bounds": {"apks": MAX_FIXTURES, "threads": 2, "queries_rows": 4096, "seeds": 128,
                   "subprocesses_per_apk": 110, "seconds_per_apk": 480,
                   "seconds_per_subprocess": args.timeout, "evidence_bytes_per_apk": 33554432},
    }
    dump(output_root / "run.json", run)
    tool_runner = Runner(output_root, args.dexsql, args.aapt2, args.timeout)
    tool_runner.run([args.dexsql, "--version"], "dexsql-version")
    tool_runner.run([args.dexsql, "tables", "--input", apks[0]], "dexsql-schema")
    for command in ("query", "decode"):
        tool_runner.run([args.dexsql, command, "--help"], f"dexsql-{command}-help")
    tool_runner.run([args.aapt2, "version"], "aapt2-version")
    inventory = fixture_inventory(inventory_apks, tool_runner)
    dump(output_root / "device-fixtures.json", inventory)
    write_device_push(output_root, inventory)
    run["inventory_count"] = len(inventory["fixtures"])
    fixture_by_path = {fixture["apk"]: fixture for fixture in inventory["fixtures"]}
    results = []
    for apk in apks:
        identifier = apk.parent.name.split("_", 1)[-1]
        output = output_root / identifier
        output.mkdir(exist_ok=False)
        result = {**fixture_by_path[str(apk.relative_to(ROOT))], "status": "FAIL"}
        runner = Runner(output, args.dexsql, args.aapt2, args.timeout)
        try:
            provenance = json.loads((apk.parent / "provenance.json").read_text())
            dump(output / "input-provenance.json", provenance)
            result["proof"] = Analysis(runner, apk).analyze()
            result["status"] = result["proof"]["status"]
        except Exception as error:
            result["error"] = f"{type(error).__name__}: {error}"
        result["subprocess_count"] = runner.calls
        result["elapsed_seconds"] = round(time.monotonic() - runner.started, 3)
        dump(output / "result.json", result)
        results.append(result)
        print(f"{identifier}: {result['status']} {result.get('error', '')}", flush=True)
    run.update(finished=datetime.now(timezone.utc).isoformat(),
               source_changed_during_run=sha256(SOURCE) != source_hash,
               passed=sum(result["status"] == "SQL_SHAPE_MATCH" for result in results),
               failed=sum(result["status"] != "SQL_SHAPE_MATCH" for result in results))
    dump(output_root / "run.json", run)
    dump(output_root / "matrix.json", results)
    report(output_root, results, source_hash, run["source_changed_during_run"])
    return 1 if run["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
