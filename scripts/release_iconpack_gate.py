#!/usr/bin/env python3
"""Explicit, resumable Phone-AVD release checks; never manages emulator lifecycle."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import zipfile
from xml.etree import ElementTree as ET

PACKAGE = "os.kei.hyper.home"
BASE = f"/data/user/0/{PACKAGE}"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
ROOT = Path(__file__).resolve().parents[1]
MONET_CLASSES = [
    f"{PACKAGE}.iconpack.InstalledMonetPackDeviceTest",
    f"{PACKAGE}.iconpack.NativeMamlMonetDeviceTest",
    f"{PACKAGE}.data.MonetPaletteReaderTest",
    f"{PACKAGE}.ui.main.MonetColorEditorTest",
    f"{PACKAGE}.ui.main.MonetStatusContentTest",
]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def validate_matrix(report, minimum=1):
    packs = report.get("packs", [])
    require(len(packs) >= minimum, "Inventory shrank below the explicit minimum")
    require(report.get("finishedAt") is not None and report.get("inventoryComplete") is True and
            report.get("userPreferencesUnchanged") is True and
            report.get("candidateCount") == len(packs), "Import report is incomplete")
    require(not report.get("scanFailures") and not report.get("inventoryIssues") and
            all(row.get("status") != "failed" for row in report.get("inventories", [])),
            "Inventory discovery failed")
    require(all(p.get("status") == "passed" and p.get("localId") and
                (p.get("convertedReady") is True or p.get("format") == "MIUI_MTZ") for p in packs), "An import is not approved")
    require(len({p["sourceId"] for p in packs}) == len(packs), "Duplicate matrix source IDs")
    return packs


def validate_instrumentation(log):
    result = re.search(r"\bOK \((\d+) tests?\)", log)
    require(result is not None and int(result[1]) > 0, "Instrumentation did not pass")
    require(not re.search(r"INSTRUMENTATION_STATUS_CODE:\s*-[234]", log),
            "Instrumentation contains a failure, assumption skip or ignored test")
    require("FAILURES!!!" not in log and "INSTRUMENTATION_FAILED" not in log,
            "Instrumentation aborted")
    return int(result[1])


def source_fingerprint(root=ROOT):
    # Release identity/copy changes do not alter the code exercised by these checks.
    paths = [
        root / "app/src/main/kotlin", root / "app/src/main/cpp",
        root / "app/src/androidTest/kotlin", root / "build-logic/src/main",
        root / "app/src/main/AndroidManifest.xml", root / "gradle/libs.versions.toml",
        root / "scripts/release_iconpack_gate.py",
    ]
    files = set()
    for path in paths:
        files.update(path.rglob("*") if path.is_dir() else [path])
    digest = hashlib.sha256()
    for path in sorted(p for p in files if p.is_file() and p.name != "ReleaseNotesCatalog.kt"):
        digest.update(str(path.relative_to(root)).encode() + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def validate_monet(report, fingerprint, matrix_digest):
    require(report.get("passed") is True and report.get("restored") is True and
            report.get("tests", 0) > 0, "Monet gate has not completed with restoration")
    require(report.get("sourceFingerprint") == fingerprint and
            report.get("matrixSha256") == matrix_digest, "Monet evidence is stale")
    require(all(re.fullmatch(r"[0-9a-f]{64}", report.get("runtime", {}).get(key, ""))
                for key in ("apkSha256", "testApkSha256", "nativeSha256")),
            "Monet evidence has no verified installed runtime identity")


class Gate:
    def __init__(self, args):
        self.args = args
        self.folder = args.evidence.resolve()
        self.folder.mkdir(parents=True, exist_ok=True)
        self.adb = ["adb", "-s", args.serial]

    def command(self, args, timeout=180):
        return subprocess.check_output(args, timeout=timeout)

    def shell(self, args, root=True):
        text = shlex.join(args)
        if root:
            text = "su -c " + shlex.quote(text)
        return self.command(self.adb + ["shell", "-n", text]).decode().strip()

    def read(self, path):
        return self.command(self.adb + ["exec-out", "su", "-c", f"cat {shlex.quote(path)}"])

    def custom_preferences(self):
        path = f"{BASE}/shared_prefs/custom_icons_v1.xml"
        if self.shell(["sh", "-c", f"if test -f {shlex.quote(path)}; then echo yes; fi"]) != "yes":
            return b""
        return self.read(path)

    def save(self, name, value):
        (self.folder / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")

    def runtime_identity(self):
        def apk_bytes(package):
            paths = self.shell(["pm", "path", package]).splitlines()
            base = [path.removeprefix("package:") for path in paths if path.endswith("/base.apk")]
            require(len(base) == 1, f"Cannot identify installed {package} APK")
            return self.read(base[0])
        app = apk_bytes(PACKAGE)
        with zipfile.ZipFile(io.BytesIO(app)) as archive:
            native = archive.read("lib/arm64-v8a/libkeihome_hyos_lsp.so")
        return {"apkSha256": hashlib.sha256(app).hexdigest(),
                "testApkSha256": hashlib.sha256(apk_bytes(PACKAGE + ".test")).hexdigest(),
                "nativeSha256": hashlib.sha256(native).hexdigest()}

    def health(self):
        require(self.args.serial == "emulator-5580",
                "This gate is scoped to the explicitly authorized HyperOS Phone AVD")
        self.command([sys.executable, str(ROOT / "scripts/hyperos_avd_health.py"),
                      "--evidence", str(self.folder / "health"),
                      "--capture-backend", "console"])

    def snapshot(self):
        require(not (self.folder / "baseline.json").exists(),
                "Baseline already exists; use another evidence directory for a new release")
        archive = self.read("/data/system/theme/icons")
        digest = hashlib.sha256(archive).hexdigest()
        native = "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data/content/icons"
        files = self.shell(["find", native, "-name", "*.mrc", "-exec", "sha256sum", "{}", ";"])
        matches = [line.split()[-1] for line in files.splitlines() if line.split()[0] == digest]
        require(matches, "Original theme is not restorable from the native library")
        (self.folder / "original-icons.zip").write_bytes(archive)
        prefs = self.custom_preferences()
        (self.folder / "original-custom-icons.xml").write_bytes(prefs)
        selections = ET.fromstring(prefs).find("string[@name='selections']") if prefs else None
        entries = json.loads(selections.text) if selections is not None else []
        witness = {}
        for entry in entries:
            data = self.read(f"{BASE}/files/custom-icons/{entry['token']}.zip")
            with zipfile.ZipFile(io.BytesIO(data)) as source:
                for name in source.namelist():
                    if name.endswith(".png"):
                        witness[name.replace("custom.icon", entry["package"])] = \
                            hashlib.sha256(source.read(name)).hexdigest()
        self.save("baseline.json", {"sha256": digest, "localId": Path(matches[0]).stem,
                  "night": self.shell(["cmd", "uimode", "night"]),
                  "customIconsSha256": hashlib.sha256(prefs).hexdigest()})
        self.save("custom-resource-witness.json", witness)

    def instrument(self, name, classes, extras=None):
        args = self.adb + ["shell", "am", "instrument", "-w", "-r",
                          "-e", "class", ",".join(classes)]
        for key, value in (extras or {}).items():
            args += ["-e", key, value]
        with (self.folder / f"{name}.log").open("wb") as output:
            result = subprocess.run(args + [RUNNER], stdout=output,
                                    stderr=subprocess.STDOUT, timeout=2400)
        require(result.returncode == 0, f"{name} command failed")
        count = validate_instrumentation((self.folder / f"{name}.log").read_text())
        return count

    def imports(self):
        self.instrument("imports", [f"{PACKAGE}.iconpack.NativeIconPackImportMatrixDeviceTest"],
                        {"realImportMatrix": "1"})
        data = self.read(f"{BASE}/cache/large-pack-matrix.json")
        (self.folder / "matrix.json").write_bytes(data)
        packs = validate_matrix(json.loads(data), self.args.minimum_packs)
        self.save("imports-pass.json", {"count": len(packs), "passed": True})

    def tree(self, name):
        path = "/data/local/tmp/keihome-release-gate.xml"
        for _ in range(5):
            self.shell(["rm", "-f", path])
            message = self.shell(["uiautomator", "dump", path])
            if "ERROR" not in message:
                try:
                    data = self.read(path)
                    tree = ET.fromstring(data)
                    require(next(tree.iter("node"), None) is not None, "Empty accessibility tree")
                    (self.folder / f"{name}.xml").write_bytes(data)
                    return tree
                except (subprocess.CalledProcessError, ET.ParseError):
                    pass
            time.sleep(.75)
        raise RuntimeError(f"{name}: no fresh accessibility tree")

    def screenshot(self, name):
        with tempfile.TemporaryDirectory(prefix="keihome-release-screen-") as directory:
            self.command(self.adb + ["emu", "screenrecord", "screenshot", directory])
            files = list(Path(directory).glob("*.png"))
            require(len(files) == 1, "Console screenshot is missing")
            (self.folder / f"{name}.png").write_bytes(files[0].read_bytes())

    def apply_one(self, row, name, restoring=False):
        uri = f"ViewLocalResource://view.local.resource#{row['localId']}"
        self.shell(["input", "-d", "0", "keyevent", "3"])
        self.shell(["am", "start", "-f", "0x10008000", "-n",
                    "com.android.thememanager/com.android.thememanager.module.detail.view.ThemeDetailActivity",
                    "-d", uri, "--es", "REQUEST_RESOURCE_CODE", "icons"])
        button = None
        for attempt in range(12):
            tree = self.tree(f"{name}-details-{attempt}")
            for node in tree.iter("node"):
                if node.get("text") == "应用" and node.get("enabled") == "true":
                    button = node
                    break
            if button is not None:
                break
            time.sleep(1)
        require(button is not None, f"{name}: native Apply action unavailable")
        bounds = list(map(int, re.findall(r"\d+", button.get("bounds", ""))))
        x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
        self.shell(["input", "-d", "0", "swipe", str(x), str(y), str(x), str(y), "120"])
        expected = row.get("sha256") if restoring else self.shell([
            "sha256sum", "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/"
            f".data/content/icons/{row['localId']}.mrc"]).split()[0]
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            actual = self.shell(["sha256sum", "/data/system/theme/icons"]).split()[0]
            if actual == expected:
                break
            time.sleep(.5)
        require(actual == expected, f"{name}: native icon archive did not become active")
        archive = self.read("/data/system/theme/icons")
        with zipfile.ZipFile(io.BytesIO(archive)) as icons:
            if not restoring:
                marker = icons.read("keihome-build-source.txt").decode().splitlines()[0]
                require(marker == row["preparedSourceId"], f"{name}: active source mismatch")
                witness = json.loads((self.folder / "custom-resource-witness.json").read_text())
                for path, digest in witness.items():
                    require(hashlib.sha256(icons.read(path)).hexdigest() == digest,
                            f"{name}: custom resource changed: {path}")
        self.shell(["am", "start", "-W", "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.HOME",
                    "-n", "com.miui.home/.launcher.Launcher"])
        time.sleep(2)
        for _ in range(5):
            home = self.shell(["dumpsys", "activity", "activities"], root=False)
            if re.search(r"topResumedActivity=.*com\.miui\.home/\.launcher\.Launcher", home):
                break
            self.shell(["input", "-d", "0", "keyevent", "3"])
            time.sleep(1)
        (self.folder / f"{name}-home-activity.txt").write_text(home)
        require(re.search(r"topResumedActivity=.*com\.miui\.home/\.launcher\.Launcher", home),
                f"{name}: launcher did not render")
        self.screenshot(f"{name}-home")
        return {"sourceId": row.get("sourceId"), "localId": row["localId"],
                "sha256": actual, "status": "passed"}

    def restore(self):
        baseline = json.loads((self.folder / "baseline.json").read_text())
        result = self.apply_one(baseline, "restored", restoring=True)
        require(self.shell(["cmd", "uimode", "night"]) == baseline["night"], "Night mode changed")
        require(hashlib.sha256(self.custom_preferences()).hexdigest() ==
                baseline["customIconsSha256"], "User custom choices changed")
        self.save("restoration.json", result)

    def monet(self):
        (self.folder / "monet-pass.json").unlink(missing_ok=True)
        require(self.args.monet_mtz, "Supply the existing real Monet MTZ fixture with --monet-mtz")
        packs = validate_matrix(json.loads((self.folder / "matrix.json").read_text()),
                                self.args.minimum_packs)
        fixture = f"{BASE}/cache/keihome-monet-source.mtz"
        require(self.args.monet_mtz != fixture, "Supply the retained source, not the temporary staging file")
        fingerprint = source_fingerprint()
        runtime = self.runtime_identity()
        matrix_digest = hashlib.sha256((self.folder / "matrix.json").read_bytes()).hexdigest()
        try:
            monet_packs = [p for p in packs if p["displayName"] in
                           ("Arcticons Material You", "Lawnicons")]
            require(len(monet_packs) == 2, "Required real Monet APK fixtures are missing")
            self.apply_one(monet_packs[0], "monet-initial")
            self.shell(["cp", "-p", self.args.monet_mtz, fixture])
            count = self.instrument("monet-device", MONET_CLASSES,
                {"installedMonet": "true", "monetMtzPath": fixture})
            for pack in [p for p in packs if p["displayName"] in
                         ("Arcticons Material You", "Lawnicons")]:
                self.apply_one(pack, "monet-" + pack["sourceId"][:12])
                destination = self.folder / ("monet-runtime-" + pack["sourceId"][:12])
                destination.mkdir(exist_ok=True)
                names = [f"{night}-{seed}-{kind}.png" for night in ("no", "yes")
                         for seed in ("blue", "pink") for kind in ("icon", "home")]
                names += ["disabled-before-icon.png", "disabled-after-icon.png", "result.json"]
                try:
                    count += self.instrument("monet-runtime-" + pack["sourceId"][:12],
                        [f"{PACKAGE}.data.MonetRuntimeReleaseGateTest"], {"realMonetRelease": "1"})
                except Exception:
                    try:
                        remote = f"{BASE}/cache/monet-release-runtime"
                        available = self.shell(["ls", remote]).splitlines()
                        for name in names:
                            if name in available:
                                (destination / name).write_bytes(self.read(f"{remote}/{name}"))
                    except Exception as error:
                        (destination / "capture-error.txt").write_text(str(error))
                    raise
                for name in names:
                    (destination / name).write_bytes(self.read(f"{BASE}/cache/monet-release-runtime/{name}"))
            required = {"Arcticons Material You", "Lawnicons"}
            require(required.issubset({p["displayName"] for p in packs}),
                    "Required real Monet APK fixtures are missing")
        finally:
            try:
                self.shell(["rm", "-f", fixture])
            finally:
                self.restore()
        require(fingerprint == source_fingerprint() and runtime == self.runtime_identity(),
                "Monet source or installed runtime changed during acceptance")
        self.save("monet-pass.json", {"tests": count, "passed": True, "restored": True,
                  "sourceFingerprint": fingerprint, "runtime": runtime, "matrixSha256": matrix_digest})

    def apply(self):
        require(self.args.apk and self.args.apk.is_file(), "Supply the signed R8 release APK")
        manifest = json.loads((self.args.apk.parent / "release.json").read_text())
        require(manifest["apkSha256"] == hashlib.sha256(self.args.apk.read_bytes()).hexdigest() and
                (self.args.apk.parent / "mapping.txt").is_file(), "R8 archive identity is not verified")
        require(manifest["source"] == self.command(["git", "-C", str(ROOT), "rev-parse", "HEAD"])
                .decode().strip(), "R8 release source differs from the current checkout")
        packs = validate_matrix(json.loads((self.folder / "matrix.json").read_text()),
                                self.args.minimum_packs)
        require((self.folder / "monet-pass.json").is_file(), "Monet gate has not passed")
        validate_monet(json.loads((self.folder / "monet-pass.json").read_text()), source_fingerprint(),
                       hashlib.sha256((self.folder / "matrix.json").read_bytes()).hexdigest())
        require(json.loads((self.folder / "monet-pass.json").read_text())["runtime"]["nativeSha256"] ==
                manifest["nativePackagedSha256"], "Monet-tested native payload differs from the R8 release")
        subprocess.run([sys.executable, str(ROOT / "scripts/deploy-hyperos-avd.py"),
            "--apk", str(self.args.apk.resolve()), "--require-all-reloads",
            "--evidence", str(self.folder / "release-deployment")], check=True)
        result = {"packs": [], "passed": False, "apkSha256": hashlib.sha256(self.args.apk.read_bytes()).hexdigest()}
        try:
            for index, pack in enumerate(packs, 1):
                result["packs"].append(self.apply_one(pack, f"apply-{index:02d}"))
                self.save("application-progress.json", result)
            result["allApplicationsPassed"] = True
        finally:
            try:
                self.restore()
                result["restored"] = True
                result["passed"] = result.get("allApplicationsPassed") is True
            finally:
                self.save("application.json", result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--phase", choices=["snapshot", "imports", "monet", "apply"], required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--minimum-packs", type=int, default=21)
    parser.add_argument("--monet-mtz", help="Existing on-device real Monet MTZ source")
    parser.add_argument("--apk", type=Path, help="Signed R8 release APK, for the apply phase")
    args = parser.parse_args()
    gate = Gate(args)
    gate.health()
    getattr(gate, args.phase)()
    print(f"PASS: {args.phase}; evidence={gate.folder}")


if __name__ == "__main__":
    main()
