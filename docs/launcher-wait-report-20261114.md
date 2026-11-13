# Public 1.9.0 launcher wait report, November 14

## Evidence and scope

The reporter supplied three screenshots, without an exported LSPosed log.
The screenshots show Redmi Note 10 Pro, OS4.0.0.9.WWOMIXM, Home
RELEASE-8.01.02.9239-261013-11081915 and framework API 102 / build 7912.
These are displayed metadata, not authenticated APK or runtime identities.

The status screenshot shows the system loaded, the launcher waiting for
connection, and 112 dispatched callbacks. Callback dispatch counts do not prove
launcher reception, native capability readiness or rendered icon changes.
The resource counts in the environment page likewise describe discovered
resources; they are not counts of successful or failed hooks.

Local evidence, including copies of the screenshots and the mapping excerpt,
is under `.artifacts/research/launcher-wait-report-20261114/`.

## Exact public mapping result

The visible stack includes `m5.run(...:45)` and mapping identifier
`d0cd0e6ed128ebab92000d489691ce18f1cd3f8d324389233b394408e1df8296`.
This identifier matches the retained R8 mapping for the published
`1.9.0+g70ea216.b0394` / `109000394` exactly.

The original frame is:

```text
SystemUiThemeCompatibilityRuntime.launchOwned$lambda$0:377
```

At commit `70ea216`, line 377 invokes the supplied `action()` inside the
`KeiHomeMaterialProfile` background worker. It is a SystemUI compatibility
worker, not the launcher-native initialization worker. The synthetic class
name also contains an unrelated Compose content-capture name because R8
merged synthetic implementations; that name alone is not the failing feature.

The screenshot omits the exception header and earlier frames. This shared
worker runs multiple actions, so its final frame cannot identify which action
or operation failed. Its catch path preserves native behavior and writes a
warning through Android Log. An absence of Error entries in one log view does
not establish successful initialization.

## Launcher state is a separate diagnostic

The generic waiting label is not the separate "Launcher capability not loaded"
state. Without the expanded runtime card's Diagnostic, this report cannot
distinguish a missing native endpoint, a handshake failure, or another
unavailable transport state. A system-side status report can remain fresh
while its launcher connection is unavailable.

9239 has retained offline structural compatibility validation; see
[the compatibility report](launcher-9239-compatibility.md). This does not
establish that the reporter's launcher bytes, native payload, framework build
or ROM match those fixtures. Neither a 9239-specific resolver fix nor a
framework-build incompatibility is established by these screenshots.

## Next evidence to request

After first unlock and entry into Home, allow initialization to settle, refresh
Status and expand the runtime card. Request:

1. A screenshot including Diagnostic, launcher PID if present, and running
   module build.
2. A full LSPosed export including Info and Warning entries, not just Error.
   Include the exception header and frames preceding `m5.run`.
3. If the export lacks the native failure, a bounded logcat capture covering
   reproduction, especially launcher/native initialization and the SystemUI
   compatibility warning.

If fixture comparison becomes necessary, retain the actual launcher APK and
framework identity before changing either. Do not clear launcher data or
rewrite the icon archive as a diagnostic shortcut.

## Outcome

The public stack frame has been identified. The SystemUI warning and launcher
wait have not been shown to share a cause. No production change or device
operation was justified or performed for this screenshot-only report.

Subsequent Java initialization hardening addresses independently verified
coupling and recovery issues; see
[initialization hardening](hook-initialization-hardening-20261114.md).
It does not retrospectively establish this reporter's root cause.
