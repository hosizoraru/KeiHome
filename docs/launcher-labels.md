# Launcher app names

Settings > System and Hook > Home screen offers the system default, scrolling
long names, and two lines. The saved default is the system behavior. The two-line
choice uses the launcher's existing title-line and line-height calculation; an
arbitrarily long name can still exceed two lines.

The scrolling path targets ordinary `ShortcutIconWidget` application labels.
It reuses the resident launcher's `Marquee` widget, original text/style and
measured available width. Short names retain the native `Text`. Special labels
such as installation-progress widgets and folder headings keep their own widget
builders. The original launcher owns visibility, scrolling, pointer handling and
State disposal; device testing must check those actual lifecycles.

The resident widget follows the [Marquee API](https://pub.dev/documentation/marquee/latest/marquee/Marquee-class.html).
The implementation uses its actual compiled constructor in the target image;
documentation alone is not an ABI contract.

## Dynamic resolution

Runtime support is a unique graph of mapped ARM64 instruction contracts. There
is no version, APK hash or address table selecting these hooks. Following the
mapped-image and explicit ABI approach used in MiuiBackGestureHook, resolution
validates the title builder, configuration getter, reactive-value getter, native
text measurement, Marquee constructor, allocator and widget type check. It derives
call targets, the field-table slot, class IDs, stack-root positions and continuation
addresses. Both saved 7722 and 8593 images resolve, including their different
String/Style root positions and source-width fields. Unknown effects in Text
construction, ambiguous matches and branches into a patch interior reject support.

Two exact 16-byte transfers and one allocation-call branch are installed only
in a fresh image before Dart execution. The bridges replace the original native
Text-allocation call with original Dart measurement/construction when scrolling
is enabled. Both callees return to the **original allocation return PC**, keeping
the original caller stack map. During Marquee construction, a live tagged String
root temporarily roots the new widget; the original constructor owns its write
barriers, allocations and safepoints. The String root is restored from the widget
before later native calls. No Dart reference is retained in C++ or a worker.
The young-space fast path is authenticated against the original allocator;
insufficient space falls back to native Text.

## Settings and refresh

The choice is saved with API 102 remote preferences in `theme_effects`. The
existing system-server monitor sends a separate authenticated command/capability
through the native channel. A changed, current acknowledgement schedules the
existing full LauncherApps refresh; unchanged heartbeat acknowledgements do not.
Lost acknowledgements, stale generations, process changes and lease gaps require
reconciliation. The command has no Monet colors or package list and cannot
publish a Monet policy. A missing command capability preserves native behavior.

Runtime reports carry the acknowledged mode, bound to the existing launcher
identity and freshness rules. Reports from before this addition remain readable.
The settings card observes status only while it is visible and STARTED. Its
status-only queries do not invalidate the current icon-preview inventory.

Settings changes can use the resident bridges without replacing machine code.
**The first installation of these new native bridges requires a fresh launcher
image. API 102 Java reload alone cannot add them to an already running image.**
The developer AVD deployment script exposes that separate action explicitly as
`--reload-native`; normal APK installation does not perform it.

## Validation checkpoint

Default device acceptance uses only HyperOS AVD `emulator-5580`. Physical-device
acceptance is a separate milestone or release check and runs only when the user
explicitly requests it; a previous physical-device check does not authorize
testing later features on that device.

Host resolution and rejection checks pass on the actual 7722/8593 ELF fixtures.
The linked production bridges pass 132 ARM64 cases covering both heap-base forms,
all modes, native line counts, exact width boundaries, invalid measurements,
memory pressure, a preference change during measurement, and simulated moving
roots. These check registers, roots and original return PCs. They do not execute
the Dart VM or establish visible Flutter behavior. The existing highlight
resolver/bridge regression also passes on both images.
The focused JVM suite passes 56 tests; the exact-patch transactions pass 945
assertions across all three patch kinds and 4/16/64 KiB page sizes. Independent
native-command admission and expiry pass the existing ASan/UBSan harness.

The Release settings UI was installed on the AVD with API 102 Java reconciliation
and unchanged launcher, SystemUI and system-server identities. Chinese, English
and Japanese layouts and all three choices were inspected. Settings search finds
the new control, and a choice without the new resident native capability displays
the waiting status rather than claiming success. The saved choice and app locale
were restored to their defaults after these checks.

Actual launcher rendering, live switching, grid geometry and animation lifecycle
remain pending the separately confirmed fresh native load at this checkpoint.
Physical-device acceptance was not performed or requested for this feature.
