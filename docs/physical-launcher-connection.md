# Physical launcher connection diagnosis

## Reproduction and cause

The two November 4 LSPosed exports and a live Xiaomi 17 Pro reproduce the
yellow "waiting for launcher connection" status with KeiHome
`1.1.0+g376b3bc.b0123` (`101000123`), Home 8602, OS4.0.6.0 and
LSPosed API 102 / 7939.

This was not a disconnected launcher. Expanding the live runtime card produced
the authenticated diagnostic `native Monet capability unavailable`. The native
module was loaded, the highlighter capability was available, and the broker
could exchange a greeting. Completed package callbacks did not establish that
the separate Monet hooks were installed.

The pulled module APK matches its original release SHA256
`a306d53258662ac1388666678bf463bfb0b12effd311574cbd6b10fa493b0140`.
The physical launcher's `libapp.so` matches the retained 8602 fixture:
`5030b84065cfbacd2db0a92f3af284a9bf71d592c05ba06b9292b7d004849610`.

Running unmodified production resolvers from the installed module's source
commit against that exact library reproduces:

| Contract | Installed source | Current source |
| --- | --- | --- |
| Tint | Accepted | Accepted |
| Monochrome selection | Rejected: missing/ambiguous selection instruction/call graph | Accepted |
| Material/highlight | Accepted | Accepted |

Both host resolver runs use ASan/UBSan. These are source-contract checks,
separate from live hook installation. The current selection adaptation was
already implemented in `2331c72`; see [the native matrix](launcher-native-matrix.md).
No system-app version gate, package-specific exception or relaxed hook guard
was added for this phone.

## Status correction

An authenticated missing-Monet-capability diagnostic now has its own
"Launcher capability not loaded" state, including a localized explanation in
English, Simplified Chinese and Japanese. The original diagnostic remains
available for log feedback. Legacy reports retain the same wire format and
diagnostic, so an older resident payload is recognized after a Java update.

This state remains a warning. It cannot promote readiness, reuse a previous
launcher identity, hide a query failure or survive report expiry. An unrelated
endpoint failure still uses the connection-wait state. The native greeting's
Monet capability requirement remains intact.

Replacing the APK hot-reloads the Java owners through API 102; it cannot replace
machine code already resident in the Flutter process. One launcher cold start
activates the updated native payload. Ordinary setting changes do not require
that development activation.

## Physical acceptance

The first updated Release deliberately left the old native payload resident.
Java hot reload preserved Launcher `6696 / start 2725`, system_server
`4290 / start 1847` and SystemUI `6672 / start 2723`. The app correctly displayed
"Launcher capability not loaded", rather than falsely showing ready.

A standard stop/start of only `com.miui.home` then loaded the updated payload.
The root HYOS spawner remained PID 5475; system_server and SystemUI retained
their identities. New Launcher `17445 / start 2919161` logged successful dynamic
Monet, label and Dock installation. Its authenticated resident ELF Build ID
matches the tested payload, and the installed capability flags are all one.
The app displayed "Module ready" and "Launcher connected".

Subsequent status refreshes, page re-entry and changing native label mode from
system default to two lines and back preserved all three process identities.
The actual native mode changed with the settings; the original label choice
was restored. The user's layout, icon theme and automatic night mode were
retained. This checkpoint exercises launcher cold start, not a whole-phone
reboot.

Evidence is under `.artifacts/research/physical-connect-20261105/`:

- `resolver-audit/evidence.json`: exact old/current sources, commands and contracts.
- `before-status-expanded.png`: original live failure and diagnostic.
- `after-java-expanded.png`: correct warning with the older native payload.
- `native-cold-start.log`, `after-native-state.json`: live native installation.
- `after-native-status.png`: ready status on the physical phone.
- `acceptance.json`: final release identity, tests, deployment and continuity checks.

The focused JVM suite has 62 passing tests covering dashboard presentation,
legacy/current report decoding, expiry and authenticated capability admission.
Release/R8 assembly and lint also pass. Native code is unchanged by the status
correction; the five-launcher native verification remains separately documented.
