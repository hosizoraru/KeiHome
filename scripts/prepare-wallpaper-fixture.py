#!/usr/bin/env python3
"""Build/install a temporary wallpaper switcher on the HyperOS test AVD only."""
import argparse
import importlib.util
from pathlib import Path
import re
import subprocess
import time
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location(
        "deploy", Path(__file__).with_name("deploy-hyperos-avd.py"))
    deploy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(deploy)
    if "KeiOS_HyperOS4_Phone" not in deploy.adb("emu", "avd", "name").splitlines():
        raise RuntimeError("Not the authorized HyperOS AVD")
    if deploy.adb("shell", "am", "get-current-user") != "0":
        raise RuntimeError("Fixture supports the AVD owner only")
    info = deploy.root("abx2xml /data/system/users/0/wallpaper_info.xml -")
    components = re.findall(r'component="([^"]+)"', info)
    if any(not component.endswith("ImageWallpaper") for component in components):
        raise RuntimeError("Use a static wallpaper for this fixture")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    assets = out / "assets"
    assets.mkdir(exist_ok=True)
    for device_name, asset_name in (("wallpaper_orig", "original"), ("wallpaper_lock_orig", "lock")):
        device_path = f"/data/system/users/0/{device_name}"
        exists = deploy.root(f"if test -f {device_path}; then echo yes; fi") == "yes"
        target = assets / asset_name
        if exists:
            target.write_bytes(subprocess.check_output(
                ["adb", "-s", deploy.SERIAL, "exec-out", "su", "-c", f"cat {device_path}"]))
        else:
            target.unlink(missing_ok=True)
    if not (assets / "original").is_file():
        raise RuntimeError("No original static wallpaper to restore")
    (out / "wallpaper_info.xml").write_text(info)
    sdk = Path(next(line.split("=", 1)[1] for line in
                    (deploy.ROOT / "local.properties").read_text().splitlines()
                    if line.startswith("sdk.dir=")))
    build_tools = sdk / "build-tools/37.0.0"
    platform = sdk / "platforms/android-37.0/android.jar"
    package = "os.kei.home.wallpaper.fixture"
    installed = deploy.adb("shell", "dumpsys", "package", package)
    previous = re.search(r"versionCode=(\d+)", installed)
    version = max(int(time.time()), int(previous[1]) + 1 if previous else 1)
    manifest = out / "AndroidManifest.xml"
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="{package}" android:versionCode="{version}" android:versionName="qa">
      <uses-sdk android:minSdkVersion="37" android:targetSdkVersion="37"/>
      <uses-permission android:name="android.permission.SET_WALLPAPER"/>
      <application android:label="KeiHome wallpaper fixture" android:theme="@android:style/Theme.Translucent.NoTitleBar">
        <activity android:name=".Main" android:exported="true" android:excludeFromRecents="true"
            android:configChanges="uiMode|orientation|screenSize|screenLayout|smallestScreenSize|colorMode"/>
      </application>
    </manifest>''')
    classes = out / "classes"
    dex = out / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)

    def run(*args):
        subprocess.run([str(arg) for arg in args], check=True)

    run("javac", "--release", "17", "-classpath", platform,
        "-d", classes, Path(__file__).parent / "wallpaper-fixture/Main.java")
    run(build_tools / "d8", "--lib", platform, "--min-api", "37", "--output", dex,
        *classes.rglob("*.class"))
    unsigned = out / "unsigned.apk"
    run(build_tools / "aapt2", "link", "-I", platform, "--manifest", manifest,
        "-A", assets, "-o", unsigned)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(dex / "classes.dex", "classes.dex")
    aligned = out / "aligned.apk"
    run(build_tools / "zipalign", "-f", "4", unsigned, aligned)
    apk = out / "fixture.apk"
    run(build_tools / "apksigner", "sign", "--ks", Path.home() / ".android/debug.keystore",
        "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", apk, aligned)
    print(deploy.adb("install", "-r", "--no-streaming", str(apk)))
    print(f"Fixture versionCode={version}; backup in {assets}")


if __name__ == "__main__":
    main()
