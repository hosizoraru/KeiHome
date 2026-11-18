# Navigation backdrop research fixtures

These Java files are disposable research tools, outside the application build.
They do not implement a shipping compatibility feature. See
[the investigation](../../../docs/gesture-navigation-backdrop.md) for evidence
and limitations.

In the fixture, `Full artwork` anchors the full width-scaled footer to the
window bottom. Compare it with `Continue artwork`, which retains the source
origin and can still clip the final rows at the window edge. Both controls
use the same inset and independent bounds for the interactive tabs.

Use the API-37 HyperOS Phone AVD (`emulator-5580` in the recorded run) for
the capture probe. The standalone Activity can also run on an authorized
physical HyperOS device. Preserve theme, palette, app data, native rules,
and critical processes. No emulator or system-process restart is needed.

## Edge capture

`NavEdgeCapture` rejects non-`ranchu` and non-API-37 devices. It uses hidden
APIs inspected on this AVD; compatibility with other builds is unverified.
It requires an already authorized rooted AVD and an explicit expected package.
It writes only into a caller-selected `/data/local/tmp/keihome-nav-` directory.
Use a fresh output directory for each invocation.

From the repository root:

```bash
KEI_NAV_SDK=/Users/voyager/Library/Android/sdk
KEI_NAV_WORK=$(mktemp -d /tmp/keihome-nav-XXXXXX)
mkdir -p "$KEI_NAV_WORK/classes"
javac --release 17 \
  -classpath "$KEI_NAV_SDK/platforms/android-37.2/android.jar" \
  -d "$KEI_NAV_WORK/classes" scripts/research/nav-backdrop/NavEdgeCapture.java
"$KEI_NAV_SDK/build-tools/37.0.0/d8" --min-api 35 \
  --output "$KEI_NAV_WORK/capture.jar" "$KEI_NAV_WORK/classes/NavEdgeCapture.class"
adb -s emulator-5580 push "$KEI_NAV_WORK/capture.jar" \
  /data/local/tmp/keihome-nav-capture.jar
adb -s emulator-5580 shell \
  'su 0 env CLASSPATH=/data/local/tmp/keihome-nav-capture.jar app_process /system/bin NavEdgeCapture os.kei.hyper.home.navlab /data/local/tmp/keihome-nav-example 16'
adb -s emulator-5580 pull /data/local/tmp/keihome-nav-example "$KEI_NAV_WORK/"
```

The target fixture must be foreground and settled before capture. Output is
at most 32 samples, 512-by-4 pixels each, with a 250-ms delay between samples.
The final JSON report contains the stop reason and per-sample wall times;
`total_ms` includes CPU readback and PNG/file I/O, but excludes VM startup and
the intentional delay. Check the report, PNG dimensions, and process exit
together.

The probe checks task/activity ownership, display, full-window mode, keyguard,
IME, navigation visibility and display geometry. It rechecks task/activity
ownership after capture. Secure/protected policies request rejection, not
capture. The tested secure window emitted no image.

Task information cannot detect all same-activity dialogs or embedded players.
The report deliberately says `production_scene_classifier: false`. This
program is not suitable for unattended background sampling.

## Cooperative renderer

`NavLabActivity` owns its test window and requires no manifest permission.
Its modes compare an empty dark strip, a mean color, final-row continuation,
and final-row continuation with a gradual 16-column texture approximation.
**Crop + color**, **Continue artwork**, and **Full artwork** compare treatments of a
width-scaled footer. The first clips at a 52 dp artwork height and fills the
gesture inset with a solid color. The second keeps the image origin, scale and
tab controls in place and moves the artwork clip to the window edge. It uses
this treatment when the original image covers the additional inset. The third
aligns the whole image with the window bottom. It also works when the image
covers the gesture inset and only part of the tab area. The window background
shows through above the image and through transparent pixels. All three keep
the same tab bounds. Pass `--ei artworkHeightPx N` when launching the fixture
to reproduce a measured artwork clip height independently of those bounds.
Try the modes with the same tab selected, then open and dismiss the keyboard and dialog.

**Separate layer** paints the same bottom-aligned artwork into a
`SurfaceView` under the fixture's tab controls. Android exposes the surface
in the area reserved by the view. **Opaque control** keeps that same surface
attached and covers it with an opaque view. The original cropped image and
gesture fill remain visible in this control. Compare the gesture-area
pixels and tab bounds in these two modes, and confirm the surface exists in
both with `dumpsys SurfaceFlinger --list`. The layer is enabled only in
eligible gesture navigation layouts. The fixture owns the surface and its
input-free background. It needs neither a module scope nor access to another
app's window.
It also exposes IME, dialog, activity-only rotation, fullscreen, and
`FLAG_SECURE` controls. It requests native handle appearance independently.

Supply an authorized footer bitmap as `assets/footer.png` in a temporary
build directory. Do not commit artwork, application configuration, accounts,
or session data. The recorded run used a local footer asset; no fixture art
is distributed here.

Compile with the same SDK and `javac --release 17`. A minimal fixture manifest
uses:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="os.kei.hyper.home.navlab"
    android:versionCode="1" android:versionName="research">
    <uses-sdk android:minSdkVersion="37" android:targetSdkVersion="37"/>
    <application android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".NavLabActivity" android:exported="true"
            android:resizeableActivity="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Use `d8` on **all** generated fixture `.class` files, including inner classes;
link the manifest and temporary assets with `aapt2 link`; add `classes.dex`,
then sign with a disposable fixture key and `apksigner`. Check that an existing
package with this name is task-owned before updating it. Install with `adb -s
emulator-5580 install -r` and open `.NavLabActivity`. Use increasing fixture
version codes for updates.

Compare screenshots at native display size. Inspect the first navigation row
against the preceding content row, plus the handle after it settles. Record
the transition too: the captured run showed a visible delay before native
handle contrast caught up with the new light background.

The fixture's explicit dialog/security flags are cooperative inputs, not a
general classifier. Do not infer third-party themed-app acceptance from this
fixture or use console screenshots to bypass protected-window capture tests.

## Teardown

Verify the fixture package identity, then uninstall **only** the task-owned
`os.kei.hyper.home.navlab`. Remove its exact task-owned remote JAR/output paths
and temporary keys/unsigned binaries. Keep reports, accepted screenshots,
recordings, original assets and APK provenance. Return the AVD Home and verify
the original theme, palette, night mode, native rule list and critical PIDs.
Leave requested AVDs running. Do not clear another application's data.
