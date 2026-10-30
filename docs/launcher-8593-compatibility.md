# Launcher 8593 compatibility, 2026-10-30

Result: **partially compatible** with KeiHome Release `1793349722`. Ordinary
light/dark application icons still refresh, while the current Monet native
profile rejects the new launcher image. This is a compatibility check, not a
new native adaptation.

## Installed and loaded identity

| Item | Before the user update | Updated launcher |
| --- | --- | --- |
| Version | `RELEASE-8.01.02.7722-260904-09221445-R` | `RELEASE-8.01.02.8593-260918-10241550-R` |
| versionCode | `801027722` | `801028593` |
| libapp SHA-256 | `fb8b1f91eba2d9c43875f49bbded70cca462be341fc379b9864e7c6136d6e463` | `68f62bbd0a73ae0096c27a4cb4bf1789a4452220510a05c658a3968fa38f470d` |
| ELF Build ID | `4f1bdaed62b77ed2de7d3ebc34e453ca` | `4f1bdaed7c97ae76de7d3ebc14f6a7b0` |
| Dart isolate instruction RVA | `0x866940` | `0x896940` |

The active APK is the updated `/data/app/.../com.miui.home-.../base.apk`;
`dumpsys package` also lists the older system APK as the disabled system-package
record. The latter is not the active version.

The new APK includes `libapp.so`, `libapp_launcher.so`, `libresources_frb.so` and
`libnative_widget_sdk.so`, but no `libhyper_os_flutter.so`. Process maps confirm
the engine is loaded from `/system_ext/lib64/libhyper_os_flutter.so`. Module
initialization accepted the HYOS spawner and backfilled that loaded library.
This loading path works; it does not establish that the new Dart ABI matches.

The new process reported:

```text
Two-tone hooks inactive: unsupported tint backing-file SHA-256
Monet hook disabled: unsupported Dart build id
```

The system-side synchronizer separately reported `Monet state=Unsupported`.
`SupportedMonetLauncher`, the tint profile and the selection profile currently
authenticate the previous image. No new image was allowed by changing only a
hash/version, and no experimental native hook was installed during this check.

## Device checks

Only `emulator-5580` / `KeiOS_HyperOS4_Phone` was used. The test temporarily set
`key_monochrome=0`, then exercised dark -> light -> dark on the same home page.
KeiHome and both ErinAO variants visibly changed to their matching light/dark
artwork. System-side package/shortcut refresh generations completed. This proves
the current end-to-end ordinary-icon path on the new launcher; it is not a
module-disabled comparison attributing every behavior exclusively to KeiHome.

Re-enabling `key_monochrome=1` reproduced the unsupported Monet result: black
foregrounds remain on the retained dark background, and HyperPods displays a
solid black shape instead of the module's normal-icon fallback. The initial
home-page screenshot also showed the malformed XiaoAI monochrome block. The
current native rejection therefore covers both custom two-tone rendering and
the module's resource-selection fallback. Ordinary night switching passing does
not establish either Monet feature's compatibility.

After the user-performed package update, process identities stayed unchanged
through the test:

| Process | PID | /proc start ticks |
| --- | --- | --- |
| com.miui.home | 16529 | 2061886 |
| system_server | 8814 | 2705 |
| com.android.systemui | 9673 | 3500 |

No matched fatal, ANR, watchdog-kill or SCOUT-hang log entries appeared after the
validation marker. This bounded check does not close the historical mixed-load
ANR investigation. No APK was installed or rebuilt, and hot reload was not
separately retested in this verification-only task.

The test restored its starting choices: night mode `yes`, `key_monochrome=1`,
and customization `HOME_WALLPAPER:TONAL_SPOT:0:-1611228`. The restored state still
exhibits the unsupported Monet rendering; it is not a successful adaptation.

## Adaptation inputs

The extracted new library and its bundled `.gnu_debugdata` symbols are retained
under `/tmp/keihome-launcher-8593/`. For example, the new symbol table places
`_MonochromeIconWidgetState.build` at `0x11b9850`, whereas the current tint profile
authenticates it at `0x116c438`. These names/addresses are analysis inputs only;
caller contracts, object layouts, code spans and lifetimes still require validation
before adding a new supported profile. The engine being system-provided does not
eliminate this separate per-launcher Dart compatibility boundary.

Screenshots, process identities and native/system logs are retained in
`/Users/voyager/.codex/visualizations/2026/10/30/keihome-launcher-8593/`.
