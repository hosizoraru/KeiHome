#!/usr/bin/env python3
"""Build permission-free icon/RRO fixtures; never installs or changes a device."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "os.kei.home.resourcefixture"
OVERLAY = PACKAGE + ".overlay"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sdk = Path(next(line.split("=", 1)[1] for line in (ROOT / "local.properties").read_text().splitlines()
                    if line.startswith("sdk.dir=")))
    tools = sdk / "build-tools/37.0.0"
    platform = sdk / "platforms/android-37.0/android.jar"

    def write(root, name, text):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def vector(path):
        return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
          android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
          <path android:fillColor="#FFFFFFFF" android:pathData="{path}"/>
        </vector>'''

    glyph = "M35,30 L49,30 L49,62 L73,62 L73,76 L35,76 Z"
    for overlay in (False, True):
        root = args.output / ("overlay" if overlay else "base")
        package = OVERLAY if overlay else PACKAGE
        write(root, "res/drawable/mono.xml", vector(glyph if overlay else "M0,0 L0,0 Z"))
        if overlay:
            write(root, "res/values/colors.xml", '<resources><color name="plate">#FF286FD1</color></resources>')
            body = f'''<overlay android:targetPackage="{PACKAGE}" android:targetName="IconProbe"
                android:isStatic="false" android:priority="1"/>
                <application android:hasCode="false" android:label="Kei icon overlay"/>'''
        else:
            write(root, "res/drawable/glyph.xml", vector(glyph))
            write(root, "res/values/colors.xml", '<resources><color name="plate">#FFC63D48</color></resources>')
            write(root, "res/mipmap-anydpi-v26/icon.xml", '''<adaptive-icon
              xmlns:android="http://schemas.android.com/apk/res/android">
              <background android:drawable="@color/plate"/>
              <foreground android:drawable="@drawable/glyph"/>
              <monochrome android:drawable="@drawable/mono"/>
            </adaptive-icon>''')
            write(root, "res/values/overlayable.xml", '''<resources><overlayable name="IconProbe">
              <policy type="public"><item type="drawable" name="mono"/>
              <item type="color" name="plate"/></policy></overlayable></resources>''')
            body = '''<application android:hasCode="false" android:label="Kei icon probe" android:icon="@mipmap/icon">
              <activity android:name="android.app.Activity" android:exported="true">
                <intent-filter><action android:name="android.intent.action.MAIN"/>
                  <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
              </activity></application>'''
        write(root, "AndroidManifest.xml", f'''<manifest
          xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1">
          <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="37"/>{body}</manifest>''')

        def run(*command):
            subprocess.run([str(part) for part in command], check=True)

        run(tools / "aapt2", "compile", "--dir", root / "res", "-o", root / "resources.zip")
        run(tools / "aapt2", "link", "-I", platform, "--manifest", root / "AndroidManifest.xml",
            root / "resources.zip", "-o", root / "unsigned.apk")
        run(tools / "zipalign", "-f", "4", root / "unsigned.apk", root / "aligned.apk")
        run(tools / "apksigner", "sign", "--ks", Path.home() / ".android/debug.keystore",
            "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
            "--out", root / "fixture.apk", root / "aligned.apk")
        print(root / "fixture.apk")


if __name__ == "__main__":
    main()
