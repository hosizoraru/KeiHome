# Status dashboard

The Hero uses the visual hierarchy shared by MiuiBackGestureHook, KernelSU and
the user's LSPosed screenshots: a short state title, framework release, API
level and a large status symbol clipped at the card's lower-right corner.
The implementation uses MIUIX Card, Text, Icon and Tilt feedback. It does not
change the existing top bar or liquid-glass navigation.

Design references:

- [MiuiBackGestureHook settings screen](https://github.com/wxxsfxyzm/MiuiBackGestureHook/blob/main/app/src/main/java/dev/codex/miuibackgesturehook/activity/PredictiveBackSettingsActivity.kt)
- [KernelSU MIUIX home](https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/ui/screen/home/HomeMiuix.kt)

These UI references informed the composition; no KernelSU source was copied.

## State and detail sheets

First activation has an independent, persistent
[device-restart reminder](first-activation.md). While unconfirmed, the hero and
runtime details prioritize this guidance over automatic-loading or per-scope
restart advice. The reminder links to the existing setup guide; completing or
skipping that guide does not hide it.

The Hero combines API 102 system-module loading with a separate native endpoint
observation. Its framework release name and version code still come from
XposedService, separately from the API and module version. Native readiness does
not depend on a Java target entry for the Flutter launcher. Text and symbols
accompany every color, and unavailable, outdated and failed observations remain
distinct from readiness.

Runtime and Monet cards keep compact status pills on the dashboard. Tapping
either card opens an OS4 Glass bottom sheet without expanding or scrolling the
page. Sheets are transient: Back, outside dismissal and page recreation close
them. No extra polling is started by opening a sheet; existing lifecycle and
freshness observers still update both the card and the open sheet.

The runtime sheet begins with module readiness, the app version prefix,
framework identity/API, and system/native status pills for screenshot context.
Diagnostic text precedes compact PID, reporting-generation and installed-version
pills; system scope and Java target reports remain secondary details. Recent
callback counts describe completed dispatch, not observed icon rendering.

The Monet sheet retains separate icon-switch and palette-readiness pills,
last-read time, source, style and available family/shade counts. The app version
is included for screenshot context. Read failures hide old successful palette
values. An explicit Monet settings action waits for the native sheet to finish
closing before navigating, and cannot navigate twice. Merely opening or
dismissing either sheet does not change settings.

The sheet itself provides the surface: the overview has no nested card fill.
Overview pills, details, notes and the settings action share the native content
insets. A thin divider separates the overview from compact detail rows. Values
wrap without ellipsis; narrow content widths and font scales above 1.3 use
stacked labels and values, while diagnostic text always uses a stacked layout.

Focused device group: `python3 scripts/checks.py ui-status-sheets --serial
emulator-5580 --run`. Coverage includes unchanged dashboard geometry, actual
elapsed-time expiry while open, read failure/recovery, aligned sheet content,
long values at 1.6 font scale and one-time navigation.

## Native observation

Like [MiuiBackGestureHook's runtime status](https://github.com/wxxsfxyzm/MiuiBackGestureHook/blob/main/app/src/main/java/dev/codex/miuibackgesturehook/NativeHookStatusUiState.kt),
the app distinguishes framework connectivity from a native response. A new UUID
request travels through API 102 remote preferences in its own group. The system
owner handles it on the existing Monet worker and calls the app's unexported
`RuntimeStatusProvider`. The provider additionally checks the Binder sender's
system UID. An in-memory StateFlow retains the reply even if it arrives before
the request commit returns. The app validates the nonce, user and elapsed-clock
freshness. The six-second UI deadline includes submission; at most one owned I/O
operation can remain outstanding if a platform Binder call does not respond to
cancellation. Status queries introduce no system Settings notifications and do
not apply palettes or schedule package refreshes.

The read-only native greeting is authenticated by the existing Binder broker,
which validates launcher UID, exact process identity and start ticks. That native
server is created only after tint/selection resolution and all four hooks have
been installed. A greeting can already be buffered briefly, so this proves
endpoint readiness; it is not a main-thread responsiveness test. It also does
not certify the separate capability gate, enabled policy, rendered pixels or
the native binary revision. The reporting version belongs to the Java owner.

Only the visible status page periodically requests new observations. Its prior
fresh result remains visible while checking, expires after 30 seconds, and is
replaced by an explicit failed observation when a new probe cannot authenticate
the native endpoint. Query cleanup and system-worker retirement participate in
the existing API 102 lifecycle.

## Verification

Release `1793373931`: Chinese, English, Japanese, light/dark appearance,
runtime/Monet expansion, tab-return state retention and refresh checked on
`emulator-5580`. Nine focused JVM checks cover framework identity, stale reports,
failure severity and relative-time boundaries. Final screenshots and receipts
are in the `keihome-dynamic-launcher` evidence directory documented in
`launcher-8593-compatibility.md`.

### Native-aware status, October 31

Release `1793379907` replaces the Java-only readiness decision and the
intermediate `1793377829` status transport. Both installations increased
versionCode and used API 102 Java hot reload without requesting a native reload.
The final deployment retained launcher `1202 / 978696`, system_server
`8818 / 2458` and SystemUI `9628 / 3192` throughout installation and reload.
The native payload did not change in this status work.

Seven protocol tests, fourteen native/framework presentation tests and nine
existing dashboard tests passed. After changing only the reply transport to the
private provider, the seven protocol tests and Release/R8/required lint passed
again. Device checks confirmed that the system owner can deliver replies and an
ordinary shell caller is rejected by the unexported provider.

On launcher 8593, the Hero now shows ready with the actual framework identity
`LSPosed 2.2.1-log (7936)`, API 102, despite the absent launcher Java report.
Expanded details show the current authenticated launcher PID and reporting
module version. The prior candidate also verified three successive fresh
observations without changing palette settings or dispatch counts, cessation of
polling while the Settings page was visible, fresh status after returning,
retained expansion, and Chinese/English/Japanese layouts. The final transport
was checked on the same UI; it keeps replies in app memory instead of global
Settings. After recovery, expanded details matched launcher PID `25951` and
reporting build `1793379907`; manual refresh showed 45 apps, four night-resource
matches and seventeen Monet-resource matches. The status-only navigation and
refresh checks preserved all three recovered process identities. These resource
counts are detection results, not end-to-end icon rendering receipts.

### Remaining launcher lifecycle failure

The broader switch test did **not** pass. After disabling and re-enabling Monet,
then changing night mode, launcher 8593 logged `Cannot clone a disposed image`
from `Image.clone` through `RawImage.updateRenderObject`, followed by
SafeRescueParty errors and process exit. The first candidate changed launcher
`26151 / 357758` to `1202 / 978696`. The private-provider candidate reproduced
the error at `2026-10-31 01:12:48 +08`; launcher `1202` exited, with intermediate
PID `25635` and recovered PID `25951 / 1121724`. System_server and SystemUI kept
their identities, but SystemUI also emitted `APP_SCOUT_HANG` reports.

Moving status replies away from Settings removes an unnecessary observer
interaction; it does not fix or establish the cause of this image lifetime
failure. Successful endpoint reconnection and a green status card do not turn
the failed theme-switch test into a continuity pass. No exception suppression,
launcher data clearing, or deliberate process restart was used for this test.
The recovered AVD displayed its normal desktop; night mode was restored to
`yes`, Monet to `1`, and app language to follow the system.

The final APK, status screenshots, deployment receipts and focused failure
evidence are retained under:
`/Users/voyager/.codex/visualizations/2026/10/31/keihome-native-status/`.
Full temporary logs remain under `/tmp/keihome-runtime-status/`.

### Physical capability failure, November 5

An authenticated greeting without the Monet capability now displays a distinct
"Launcher capability not loaded" warning and recovery guidance. Connection
waiting, query failure and expiry remain separate, and readiness still requires
the actual native capability. Legacy reports are recognized without changing
their wire format. See [the physical diagnosis](physical-launcher-connection.md)
for the old Home 8602 selection-contract rejection, current successful dynamic
resolution, one-time native activation and subsequent process-stable acceptance.

## Receipts during an appearance change

The launcher control worker handles navigation preference receipts, scoped
desktop appearance callbacks and framework-manager broadcasts. The material
and status monitor continues to read settings and native replies. Icon sweeps
still own the completion receipt for refreshed artwork. Import results and
Bilibili artwork drawing continue to report from their respective operations.

On 2026-11-23, signed Release b0736 (`114000736`) was installed over b0734
on Xiaomi 17 Pro (`pandora`, HyperOS `OS4.0.8.0.XBLCNXM`, Home `801030308`).
The installed APK SHA-256 matched the candidate, and all three packaged native
libraries matched b0734. The build included the pending local MIUIX UI changes.
The new Java runtime installed its system-server hooks, and the dashboard
subsequently showed the current native connection. During package replacement,
one status publication encountered an unavailable provider. The first settled
dashboard showed a query timeout. Reopening only KeiHome restored status
queries; this run leaves first-open continuity across replacement unverified.

Each appearance change started a `RESOURCE_CHANGE` sweep across 202 packages.
The following results were observed before the corresponding generation's
completion log. UI timings are upper bounds from the hierarchy samples.

| Operation | Observed result | Sweep duration |
| --- | --- | ---: |
| Match bottom bar color, light to dark and dark to light | Matching preference fingerprints observed within 0.37 s and 0.68 s of the taps. The status card confirmed the new switch state in both directions. | 44.55 s in each direction |
| Dock glass switch | System receipt in 0.36 s, scoped Home callback in 0.46 s, settings confirmed within 3.12 s. Captured Home with the glass turned off. | 44.54 s |
| Search bar material | Scoped Home callback in 0.20 s, new choice confirmed within 5.22 s. | 44.57 s |
| App name display | Scoped Home callback in 1.75 s, new choice confirmed within 5.21 s. | Same generation as search |
| Dashboard manual refresh | Fresh status timestamp and ready state observed within 5.20 s. | 44.54 s |
| Open LSPosed logs | Activity start requested in 0.09 s. Shell warning reproduced the existing [cold-launch limitation](framework-manager.md#known-cold-launch-limitation). | 44.57 s |

The search and label checks cover setting acknowledgement and callback delivery.
The manager check covers dispatch timing. Import, Bilibili drawing and
manager warm launch were outside this run. Launcher, SystemUI and
system_server retained their PIDs throughout, and the applied icon-pack hash
remained the same. The original light mode, navigation switch, Dock switch,
search material and label mode were restored.

The Release build and lint completed with zero lint errors. The JVM suite
passed 2,123 tests with 8 skipped.

The signed APK, build and install records, screenshots, hierarchy dumps and
generation timelines are under
`.artifacts/research/runtime-receipts-20261123/`. The [navigation evidence](gesture-navigation-backdrop.md#status-acknowledgement-during-an-icon-refresh)
also retains the per-direction b0736 hierarchy dumps.
