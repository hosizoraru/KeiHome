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
