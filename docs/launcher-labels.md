# Launcher app names

Settings > System and Hook > Home screen offers the system default, scrolling
long names, and two lines. The saved default is the system behavior. The two-line
choice uses the launcher's existing title-line and line-height calculation; an
arbitrarily long name can still exceed two lines.

The scrolling path targets ordinary `ShortcutIconWidget` application labels.
It reuses the resident launcher's `Marquee` widget, original text/style and
measured available width. Short names retain the native `Text`. The decision
checks both native title envelopes; intermediate widths use the original
`getPrefixAssetName` to account for the installation/update dot. A 112-byte
candidate can be discarded in this intermediate case, without creating a
Marquee State or controller. Measurement and prefix queries run at title build,
not on animation frames. Invalid widths preserve native Text. Special labels
such as installation-progress widgets and folder headings keep their own widget
builders. The original launcher owns visibility, scrolling, pointer handling and
State disposal. No additional timer, JNI call or C++ work runs per frame.

When scrolling is selected and both native fading fractions are zero, the
original Marquee ListView is returned without its ineffective fading wrapper.
This removes the extra ShaderMask, gradient and scroll-listener subtree. It
preserves the native ListView's clipping and scroll controller. Nonzero or
invalid fractions, an absent widget and the other modes use the original callee.
The guard is a leaf bridge at the original call site and retains its return PC;
it neither allocates nor retains a Dart reference. It reduces the widget tree;
the AVD measurements do not establish a significant raster-CPU improvement.

Cached desktop pages need an additional lifecycle boundary: the original
Marquee alone continues ticking after a page leaves the viewport. The complete
`CellLayout` page is wrapped in the launcher's resident `TickerMode`, using its
reactive `_isScreenVisibleForGrid` predicate. This preserves its overview and
dual-page rules and costs a 32-byte wrapper per page build. Visible weather and
clock widgets retain their normal refreshes. Device checks must still establish
actual pause/resume and rendering, beyond host emulation.

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
addresses, prefix helper, page construction, native visibility and fade graphs. Both
saved 7722 and 8593 images resolve, including their different
String/Style root positions and source-width fields. Unknown effects in Text
construction, ambiguous matches and branches into a patch interior reject support.

Two exact 16-byte transfers, three call branches and one page-return branch are
installed only in a fresh image before Dart execution. The bridges replace the original native
Text-allocation call with original Dart measurement/construction when scrolling
is enabled. Both callees return to the **original allocation return PC**, keeping
the original caller stack map. During Marquee construction, a live tagged String
root temporarily roots the new widget; the original constructor owns its write
barriers, allocations and safepoints. The String root is restored from the widget
before later native calls. No Dart reference is retained in C++ or a worker.
The young-space fast paths are authenticated against the original allocators.
Insufficient space preserves native Text or the original initialized page Stack
as appropriate.

For the optional prefix call, the candidate's text field retains the String
while the original live root retains the candidate; Style and the original
ShortcutIconWidget remain in existing live roots. The page bridge completes all
11 original Stack initialization instructions **before any additional Dart
call**. Its original child-list root temporarily owns TickerMode, whose temporary
key retains the list and whose child retains the initialized Stack. After the
native visibility call it restores the list root and publishes a null key.
Post-call wrapper stores contain only canonical bool/null references. These
orders preserve the native young-allocation/write-barrier contract across GC.

## Settings and refresh

The choice is saved with API 102 remote preferences in `theme_effects`. The
existing system-server monitor sends a separate authenticated command/capability
through the native channel. A changed, current acknowledgement schedules the
existing refresh generation machinery. A label-only ACK sends one ordinary
package callback for KeiHome, skipping app/shortcut enumeration, archive reads
and icon inventory/probe work. The original item-change call site then invokes
the resident font/title invalidation, clearing cached title widgets and notifying
already mounted title IDs. Genuine package/resource/night/palette work retains
its pending work and pacing. Unchanged heartbeat acknowledgements do not refresh.
Lost acknowledgements, stale generations, process changes and lease gaps require
reconciliation. The command has no Monet colors or package list and cannot
publish a Monet policy. A missing command capability preserves native behavior.

Runtime reports carry the acknowledged mode, bound to the existing launcher
identity and freshness rules. Reports from before this addition remain readable.
The settings card observes status only while it is visible and STARTED. Its
status-only receipts have a separate StateFlow, including failure/freshness
state, so they cannot replace a complete icon-theme report or renew its age.
Both channels reset on framework-service changes and share the existing single
outstanding I/O limit. The visible fallback resources retain their ten-second
refresh; label acknowledgements retain their five-second observation. This also
prevents partial receipts from removing the fallback action row and shifting an
open dropdown.

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
The linked production bridges pass 540 ARM64 cases covering both heap-base forms,
all modes, native line counts, exact width boundaries, invalid measurements,
memory pressure, a preference change during measurement or a prefix query,
both prefix results, page visibility, and simulated moving roots. Fade cases
cover positive/negative zero, nonzero fractions, NaN/infinity, a missing widget
and all modes, including original-callee argument/return-PC preservation. Page cases
assert Stack initialization precedes the visibility call. These check registers,
roots and original return PCs. They do not execute
the Dart VM or establish visible Flutter behavior. The existing highlight
resolver/bridge regression also passes on both images.
The focused JVM suite passes 56 tests; the exact-patch transactions pass 945
assertions across all three patch kinds and 4/16/64 KiB page sizes. Independent
native-command admission and expiry pass the existing ASan/UBSan harness.

The earlier Release settings UI was installed on the AVD with API 102 Java reconciliation
and unchanged launcher, SystemUI and system-server identities. Chinese, English
and Japanese layouts and all three choices were inspected. Settings search finds
the new control, and a choice without the new resident native capability displays
the waiting status rather than claiming success. The saved choice and app locale
were restored to their defaults after these checks.

Subsequent fresh native activation established actual rendering and live mode
changes on already installed icons. Label-only changes produce one callback,
with stable Launcher/SystemUI/system-server identities. Offscreen-page tracing
confirms that a visible weather page keeps its approximately one-second clock
refresh while cached title marquees stop ticking. See the performance acceptance
report for the final trace results and emulator limitations. Physical-device
acceptance was not performed or requested for this feature.
