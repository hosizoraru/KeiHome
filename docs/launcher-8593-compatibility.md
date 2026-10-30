# Launcher 8593 compatibility, 2026-10-30

Initial result: **partially compatible** with KeiHome Release `1793349722`. Ordinary
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


## Dynamic adaptation and device validation

KeiHome Release `1793371529` replaces the static tint/gate profiles and Java
SHA allowlist with mapped-code resolution. The same APK was tested against the
user's saved 8593 and 7722 launcher packages. No per-version RVA lookup or whole
image SHA allowlist selects the hooks. Both profiles were derived from their
actual executable instructions, pool relationships, field-table slots and
allocation class IDs. Apache-2.0 attribution to MiuiBackGestureHook is retained.

The runtime resolved:

| Derived value | 7722 | 8593 |
| --- | --- | --- |
| Tint entry RVA | `0x8f3178` | `0x93fc4c` |
| Monochrome selection RVA | `0xebf5fc` | `0xf07de8` |
| Foreground constant PP slot | `0xa7b18` | `0xab0c0` |
| Controller field-table slot | `0x2910` | `0x29e8` |
| Controller / RxInt / RxBool CID | `4004 / 3826 / 3830` | `4147 / 3969 / 3973` |

These are observed outputs, not production address constants. The resolver
normalizes relocation operands while retaining the stack, registers, object
fields, String representation and local control-flow contracts needed by the
bridges. Selection's directly reachable call graph and the optional async support
gate are also validated. A future change outside these code families can still
require review; two verified versions are not a claim about every future build.

The first native upgrade used the authorized development spawner replacement.
Installing the two launcher APKs also replaced the launcher, as expected for a
package update. Those deployment transitions are excluded from continuity
acceptance. **Within each dark -> light -> dark test, all three process identities
remained unchanged:**

| Test | Launcher PID / start ticks | system_server | SystemUI |
| --- | --- | --- | --- |
| 8593 | `23316 / 252797` | `8818 / 2458` | `9628 / 3192` |
| 7722 | `20155 / 302908` | `8818 / 2458` | `9628 / 3192` |

Actual screenshots show KeiHome and both ErinAO variants using light foregrounds
on dark backgrounds, then dark foregrounds on light backgrounds, and returning
correctly. HyperPods retains its normal blue artwork in both modes; XiaoAI's
first-page black block also returns to its normal artwork. The normal built-in
icons keep their theme-owned appearance. No fatal/ANR/watchdog-kill/SCOUT-hang
entries matched after either test marker. This remains a bounded functional
check and does not close the earlier mixed-load ANR investigation.

MiuiBackGestureHook was enabled throughout this device comparison. Both native
payloads were mapped simultaneously, and the reference module continued logging
Home surface updates. No native hook conflict was observed in these flows.
After reinstalling 8593, the launcher again resolved the new slots/CIDs, with
PID `26151`, start ticks `357758`; system_server and SystemUI stayed unchanged.
The original wallpaper and saved Monet customization
were preserved; the test ends in night mode with themed icons enabled.

Relevant host checks: selection resolution and ABI checks pass on both real
ELFs (77 checks each); the gate has 40 checks per fixture; tint resolution has
17 focused rejection cases per fixture and its 69 ARM64 machine-code cases run
with each image's derived layout. The unchanged selection bridge also passes
35 path cases and native register/root-lifetime assertions. These host checks
are separate from actual Dart VM/visual acceptance above.

Local evidence: `/tmp/keihome-launcher-8593/adaptation/`, including the deployment
receipt, native resolution logs, process identities and before/after screenshots.

## Final UI build and automatic reload

Release `1793373931` includes the status Hero redesign, following the intermediate
UI build `1793373096`. Both installations strictly increased the version code and
used normal API 102 Java reload. Their native payload is byte-identical to the
two-launcher adaptation build; no additional native deployment restart was used.
The final reload and status-page light/dark check preserved launcher
`26151 / 357758`, system_server `8818 / 2458` and SystemUI `9628 / 3192`.
No matching crash/hang entries appeared after that check's log marker.

The final Release build, R8/lint and nine focused status-dashboard JVM tests pass.
Manual checks covered Chinese, English and Japanese, both UI appearances,
runtime/Monet expansion, retained expansion after switching tabs, and status-page
refresh. All four collapsed cards fit on the AVD screen. The framework's actual
identity is `LSPosed 2.2.1-log (7936)`, API 102. Its Java target list does not
report this native launcher, so the UI truthfully remains partly loaded; native
Monet readiness was validated separately by the logs and icon screenshots above.

The AVD is left on 8593, night mode `yes`, with app language following the system.
The two temporary `/data/local/tmp/keihome-launcher-*.apk` copies were removed;
the user's original Documents archives remain intact.

Permanent screenshots, reload receipts, process records and the final APK:
`/Users/voyager/.codex/visualizations/2026/10/30/keihome-dynamic-launcher/`.
