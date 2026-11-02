#!/usr/bin/env python3
"""Build a permission-free cold-start fixture. Never installs or changes a device."""
import argparse
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "os.kei.home.splashfixture"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    sdk = Path(next(line.split("=", 1)[1] for line in (ROOT / "local.properties").read_text().splitlines()
                    if line.startswith("sdk.dir=")))
    tools = sdk / "build-tools/37.0.0"
    platform = sdk / "platforms/android-37.0/android.jar"

    def write(name, text):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    write("res/drawable/glyph.xml", '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
      android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
      <path android:fillColor="#FFFFFFFF" android:pathData="M35,30 L49,30 L49,62 L73,62 L73,76 L35,76 Z"/>
    </vector>''')
    write("res/mipmap-anydpi-v26/icon.xml", '''<adaptive-icon
      xmlns:android="http://schemas.android.com/apk/res/android">
      <background android:drawable="@color/plate"/><foreground android:drawable="@drawable/glyph"/>
    </adaptive-icon>''')
    write("res/values/colors.xml", '''<resources><color name="plate">#286FD1</color>
      <color name="night_aware">#F5F5F5</color></resources>''')
    write("res/values-night/colors.xml", '''<resources><color name="night_aware">#181818</color></resources>''')
    write("res/values/bools.xml", '<resources><bool name="light_bars">true</bool></resources>')
    write("res/values-night/bools.xml", '<resources><bool name="light_bars">false</bool></resources>')
    write("res/drawable/artwork.xml", '''<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
      <item><shape><solid android:color="#FFF5F5F5"/></shape></item>
      <item android:width="48dp" android:gravity="left"><shape>
        <solid android:color="#FF286FD1"/></shape></item></layer-list>''')
    styles = {
        "Plain": '<item name="android:windowSplashScreenBackground">#FFF5F5F5</item>',
        "NightAware": '''<item name="android:windowSplashScreenBackground">@color/night_aware</item>
          <item name="android:windowBackground">@color/night_aware</item>''',
        "Brand": '''<item name="android:windowSplashScreenBackground">#FFB42139</item>
          <item name="android:windowBackground">#FFB42139</item>''',
        "CustomIcon": '''<item name="android:windowSplashScreenBackground">#FFF5F5F5</item>
          <item name="android:windowSplashScreenAnimatedIcon">@drawable/glyph</item>''',
        "Branding": '''<item name="android:windowSplashScreenBackground">#FFF5F5F5</item>
          <item name="android:windowSplashScreenBrandingImage">@drawable/glyph</item>''',
        "Artwork": '<item name="android:windowBackground">@drawable/artwork</item>',
    }
    write("res/values/styles.xml", '<resources><style name="Base" parent="android:style/Theme.Material.NoActionBar">'
          '<item name="android:windowBackground">#FFF5F5F5</item>'
          '<item name="android:windowLightStatusBar">@bool/light_bars</item>'
          '<item name="android:windowLightNavigationBar">@bool/light_bars</item>'
          '<item name="android:forceDarkAllowed">false</item></style>' +
          "".join(f'<style name="{name}" parent="Base">{body}</style>' for name, body in styles.items()) +
          '</resources>')
    # A real Activity per theme avoids relying on theme attributes unsupported by activity-alias.
    activities = "".join(f'''<activity android:name=".{name}" android:exported="true"
      android:theme="@style/{name}"/>''' for name in styles)
    activities += '<activity android:name=".Configure" android:exported="true" android:theme="@style/Base"/>'
    write("AndroidManifest.xml", f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="{PACKAGE}" android:versionCode="1">
      <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="37"/>
      <application android:label="Kei splash probe" android:icon="@mipmap/icon"
        android:allowBackup="false">{activities}</application></manifest>''')
    write("java/os/kei/home/splashfixture/Probe.java", f'''package {PACKAGE};
      public class Probe extends android.app.Activity {{
        @Override public void onCreate(android.os.Bundle state) {{
          super.onCreate(state);
          android.view.View view = new android.view.View(this);
          boolean night = (getResources().getConfiguration().uiMode & 48) == 32;
          android.util.Log.i("KeiSplashProbe", getClass().getSimpleName() + " night=" + night);
          view.setBackgroundColor(night ? 0xff181818 : 0xfff5f5f5);
          setContentView(view);
          final long ready = android.os.SystemClock.uptimeMillis() + 1600;
          view.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {{
            public boolean onPreDraw() {{
              if (android.os.SystemClock.uptimeMillis() < ready) return false;
              view.getViewTreeObserver().removeOnPreDrawListener(this);
              return true;
            }}
          }});
        }}
      }}''')
    for name in styles:
        write(f"java/os/kei/home/splashfixture/{name}.java",
              f"package {PACKAGE}; public final class {name} extends Probe {{}}")
    write("java/os/kei/home/splashfixture/Configure.java", f'''package {PACKAGE};
      public final class Configure extends android.app.Activity {{
        @Override public void onCreate(android.os.Bundle state) {{
          super.onCreate(state);
          getSystemService(android.app.UiModeManager.class).setApplicationNightMode(
            getIntent().getIntExtra("mode", android.app.UiModeManager.MODE_NIGHT_AUTO));
          finish();
        }}
      }}''')

    def run(*command):
        subprocess.run([str(part) for part in command], check=True)

    classes = root / "classes"
    classes.mkdir(exist_ok=True)
    run("javac", "-source", "17", "-target", "17", "-classpath", platform, "-d", classes,
        *sorted((root / "java").rglob("*.java")))
    run(tools / "d8", "--lib", platform, "--min-api", "33", "--output", root,
        *sorted(classes.rglob("*.class")))
    run(tools / "aapt2", "compile", "--dir", root / "res", "-o", root / "resources.zip")
    run(tools / "aapt2", "link", "-I", platform, "--manifest", root / "AndroidManifest.xml",
        root / "resources.zip", "-o", root / "unsigned.apk")
    with zipfile.ZipFile(root / "unsigned.apk", "a") as archive:
        archive.write(root / "classes.dex", "classes.dex")
    run(tools / "zipalign", "-f", "4", root / "unsigned.apk", root / "aligned.apk")
    run(tools / "apksigner", "sign", "--ks", Path.home() / ".android/debug.keystore",
        "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--out", root / "fixture.apk", root / "aligned.apk")
    print(root / "fixture.apk")


if __name__ == "__main__":
    main()
