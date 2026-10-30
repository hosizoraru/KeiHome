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

## State and disclosure

The Hero summarizes API 102 framework loading reports. Its release name and
version code come from XposedService, separately from the API and module version.
Green means both reported targets match the installed module generation; amber
covers missing reports, reloads and outdated generations. Connection/read errors,
missing system scope and either target's explicit failure use the error color.
Text and symbols accompany every color.

Runtime and Monet details expand independently and keep their state across tab
changes. Their collapsed summaries and app capability counts fit together on
the tested phone screen. Text can increase the card height; the decorative
symbol does not determine layout height or cover the text column.

The native Flutter launcher can work without appearing in the framework's Java
target list. That missing report stays visible. Palette availability and a
successful native visual test are not substituted for a current framework report.

## Verification

Release `1793373931`: Chinese, English, Japanese, light/dark appearance,
runtime/Monet expansion, tab-return state retention and refresh checked on
`emulator-5580`. Nine focused JVM checks cover framework identity, stale reports,
failure severity and relative-time boundaries. Final screenshots and receipts
are in the `keihome-dynamic-launcher` evidence directory documented in
`launcher-8593-compatibility.md`.
