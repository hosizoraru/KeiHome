#!/usr/bin/env python3
"""Build resource-only install/update fixtures. Does not install or replace any application."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", choices=("com.heytap.health", "os.kei.home.installfixture"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sdk = Path(next(line.split("=", 1)[1] for line in (ROOT / "local.properties").read_text().splitlines()
                    if line.startswith("sdk.dir=")))
    tools, platform = sdk / "build-tools/37.0.0", sdk / "platforms/android-37.0/android.jar"
    for version, color in ((1, "#FFC63D48"), (2, "#FF286FD1")):
        out = args.output / str(version)
        resources = out / "res/drawable"
        resources.mkdir(parents=True, exist_ok=True)
        (resources / "icon.xml").write_text(f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
          <path android:fillColor="{color}" android:pathData="M0,0h108v108h-108z"/>
          <path android:fillColor="#FFFFFFFF" android:pathData="M44,22h20v22h22v20h-22v22h-20v-22h-22v-20h22z"/>
        </vector>''')
        manifest = out / "AndroidManifest.xml"
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="{args.package}" android:versionCode="{version}" android:versionName="fixture-{version}">
          <uses-sdk android:minSdkVersion="37" android:targetSdkVersion="37"/>
          <application android:label="KeiHome fixture {version}" android:icon="@drawable/icon"
            android:hasCode="false">
            <activity android:name="android.app.Activity" android:exported="true">
              <intent-filter><action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
            </activity>
          </application>
        </manifest>''')
        def run(*command):
            subprocess.run([str(part) for part in command], check=True)
        compiled, unsigned, aligned, signed = [out / name for name in
                                               ("resources.zip", "unsigned.apk", "aligned.apk", "fixture.apk")]
        run(tools / "aapt2", "compile", "--dir", out / "res", "-o", compiled)
        run(tools / "aapt2", "link", "-I", platform, "--manifest", manifest, "-R", compiled, "-o", unsigned)
        run(tools / "zipalign", "-f", "4", unsigned, aligned)
        run(tools / "apksigner", "sign", "--ks", Path.home() / ".android/debug.keystore",
            "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
            "--out", signed, aligned)
        print(signed)


if __name__ == "__main__":
    main()
