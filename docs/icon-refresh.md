# Launcher icon refresh

KeiHome observes system night-mode configuration changes through LSPosed API
102 in `system_server`. This refresh path requires the `system` scope and does
not terminate the launcher process. A separate experimental native payload
also targets the launcher for themed-icon tinting and resource selection.

For each accessible launcher user/profile, it checks that the registered
`com.miui.home` callback and the package visibility are still valid, then
dispatches package-change callbacks to that listener alone. For packages with
dynamic, manifest, pinned or cached shortcuts, it also sends the matching
`onShortcutChanged` callback with the current `ParceledListSlice`. This asks
the launcher to use its own model and icon-cache update path for both ordinary
activity entries and shortcut-backed entries such as a Dock shortcut menu.
The callbacks are serialized because this launcher can overlap asynchronous
icon updates. Newer night-mode generations cancel the remainder of older
work. Listener snapshots use the framework's registered-callback lock rather
than taking its broadcast slot; incompatible private framework structures
disable the refresh.

Refresh resource inspection runs in an isolated app process, not in
`system_server` or the launcher. It considers each launchable activity and alias. A package is
skipped only when its supported static resource graph and both rendered states
appear unchanged; unknown resources, animated or theme-dependent drawables,
errors, service failures and timeouts are refreshed conservatively. No app
drawable or package inventory is retained by the module. Separately, the app
inspects visible launcher entries for each accessible profile and distinguishes
detected, undetected and uncertain resource support. A declared monochrome
layer whose rendered alpha is completely uniform is marked uncertain, since
tinting it cannot retain any internal detail. The app records that candidate
with its user id, exact package, version code and icon resource ids, and shows
that the normal icon is recommended. This is a resource-level fallback
candidate, not proof that the launcher has already switched that entry back.
Other unsuitable masks still require visual verification.

The status page reads the API 102 service connection, scope and loaded target
state directly. The runtime does not send a diagnostic broadcast back to the
app, so the status page does not present historical callback counts as proof of
visual icon changes.

For API 102 hot reload, KeiHome declines retirement while hooks, refresh jobs,
or tracked worker threads are still active. Matching hooks are replaced
individually, and the new runtime schedules a reconciliation against the
current configuration. Hook replacement is not an atomic transaction, and a
late call from an old hook snapshot can still race reconciliation. If a
replacement fails, refresh is disabled and logged rather than presented as
successful.

## Validation and limits

The current flow has been exercised on `KeiOS_HyperOS4_Phone`, Android API 37,
with `com.miui.home` version `RELEASE-8.01.02.7722-260904-09221445-R`.
KeiHome and two ErinAO icons were checked in both directions, including rapid
mode changes and switching while another app was foreground. On the current
build, both apps also switched while their folder was open; a temporary
resource-backed dynamic shortcut switched in its open menu. The launcher PID
and process start time remained unchanged during these transitions. A previous
build was also checked with a temporary work profile: ErinAO switched in both
directions and appeared as a separate list entry. The profile, folder and
shortcut fixtures were removed after testing.

An additional higher-versionCode install exercised automatic hot reload from
the current implementation to itself. The recovered service and replacement
hooks submitted a reconciliation, and KeiHome and both ErinAO icons switched
in both directions without restarting the launcher or `system_server`.

The current AVD build was also checked with KeiHome in a five-slot Dock. Its
ordinary Dock icon switched in both directions, and the `KeiHome QA` dynamic
shortcut shown by the Dock long-press menu switched with it. The launcher
process stayed at PID `9665` with start time `3209` throughout the install,
four theme transitions and the open shortcut-menu check. The corresponding
system-server refresh reported 30 package callbacks plus 25 shortcut callback
deliveries per transition.

The host emulator's Vulkan renderer aborted once during the manual gesture
tests; the emulator was restarted and the process-stability checks were
repeated from a new baseline. These results do not establish behavior on other
launcher builds or devices, locked or private profiles, pinned shortcuts,
arbitrary shortcut icon formats, or every folder and widget configuration.

The framework service reflection is validated during hook installation and
unsupported signatures disable the refresh. Launcher package-update semantics
can vary across HyperOS versions and still need device-specific verification.
The 220 ms interval is a tested delay, not an acknowledgment that
the launcher's asynchronous update has finished. Icons can therefore update
one after another, and a large number of candidate packages takes longer. On
the tested AVD, one personal-profile transition submitted 30 callbacks in
about eight seconds after the change; an additional active work profile added
nine callbacks and about two seconds. These are not universal timing guarantees.
When the isolated inspector is unavailable, all accessible launchable
packages are refreshed instead of risking missed icons.

The solution uses launcher package callbacks and does not depend on whether
`libhyper_os_flutter.so` comes from the launcher APK or the system partition.

## Two-tone Monet candidate

The native implementation is an experimental candidate for the launcher image
with SHA-256 `fb8b1f91eba2d9c43875f49bbded70cca462be341fc379b9864e7c6136d6e463`
and Dart build ID `4f1bdaed62b77ed2de7d3ebc34e453ca`. A different image,
changed instruction dependency, ambiguous structural match, or unavailable
authenticated policy preserves original selection/tint behavior. The
single-instruction capability patch preserves the existing asynchronous
`Future<bool>` contract.

The previous background-only path is replaced with acknowledged color pairs.
The tone roles follow AOSP iconloaderlib's Android 16 resources:

| Mode | Background | Foreground |
| --- | --- | --- |
| Light | primary90 / system_accent1_100 | primary30 / system_accent1_700 |
| Dark | secondary20 / system_accent2_800 | primary80 / system_accent1_200 |

Sources: [light resources](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-16.0.0_r1/iconloaderlib/res/values-v31/colors.xml),
[dark resources](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-16.0.0_r1/iconloaderlib/res/values-night-v31/colors.xml).
Both colors must be opaque and have an unrounded contrast ratio of at least
4.5. Missing or unusable tones do not produce a substitute palette. Wallpaper
representative colors, palette tone entries and available generation schemes
remain separate concepts in the app.

Read-only inspection of the live AVD confirmed that the foreground constant is
`ColorFilter.mode(black, srcIn)`. The bridge changes only the unboxed ARGB
argument at the authenticated conversion call. It verifies the caller, the
live pool constant, blend mode, original color and the current controller's
selected background. It never allocates a Dart object, retains a heap pointer,
replays a State/root tree, or invokes Dart/C code. The two original filter
setters still own invalidation; scoped equality prevents them from retaining
an obsolete engine filter, including after policy withdrawal. The constant is
also used by the launcher's resource debug demo; that known demo is within the
same identity scope. Other ColorFilters and other callers retain their behavior.

Malformed-mask decisions use the package's **application icon**, matching
HYOS's resource selector, and the current night configuration. Only a declared,
successfully rendered uniform-alpha layer is denied. Missing, animated,
theme-dependent, unreadable or profile-ambiguous resources remain unknown and
keep original selection. Activity aliases continue to be evaluated
separately for the compatibility UI. A pure assembly entry guard returns the
normal-icon choice before the launcher's package capability cache is consulted;
it checks same-image String layout and live closure links on every enabled
call. It neither writes the cache nor retains the package String.

Policy delivery uses a process-local abstract socket with both system UID and
`system_server` SELinux peer authentication. The client verifies the foreground
user's launcher UID and rejects user switches during an exchange. Immutable
native snapshots contain only primitive colors and package bytes. Readers pin
before loading a snapshot; writers retain retired storage until readers drain.
A 15-second renewal maintains a 45-second denial lease. Expiry clears denials;
the last accepted colors are eligible only while the actual selected background
still matches. No unauthenticated request or timer creates a new policy.

The monitor writes the background only after the native side acknowledges the
pair. Existing manual colors, including transparent/off values, are preserved.
Automatic following currently requires an unset color or one still matching
KeiHome's ownership marker; a stock manual preset is not silently opted in.
Turning the themed-icon switch off alone retains that marker so it can resume.
SettingsProvider has no atomic compare-and-set, so simultaneous manual writes
remain a narrow race despite rechecking before and after native publication.
Palette changes and withdrawal schedule the standard targeted package/shortcut
refresh; a pending full sweep survives superseding night-mode generations.

### Validation boundary

Host checks cover backing-image hashing (direct ELF and stored APK entry),
structural/caller/layout rejection, actual ARM64 bridge execution, register and
stack preservation, parser/authentication predicates, and reader retirement
under ASan/UBSan. The reproducible runners are in `scripts/tests/`, with external
launcher ELF input; proprietary binaries and heap dumps are not committed.
These checks do not establish Flutter repaint timing or visual acceptance.

The running AVD's older native payload cannot be replaced by API 102 Java hot
reload. Installed LSPosed build 7936 exposes no live HYOS native-injection CLI;
its logs show Java updates in system_server while the launcher keeps the old
APK mapping. MiuiBackGestureHook's deployment helper replaces the owning
spawner, which creates a new launcher generation. No such replacement is
performed by this candidate or by its tests. Multi-instruction detours are
installed only on the first fresh library-load callback; an already loaded
snapshot is never repatched through backfill.

Therefore the new two-tone and fallback paths still require a separately
authorized first native deployment and visual light/dark acceptance for
KeiHome, ErinAO, malformed icons, Dock, folders and profiles. Subsequent runtime
theme/palette transitions use notifications and in-process rendering; there is
no launcher termination, restart, root-tree replacement or settings-shell path
in the implementation. Do not describe host tests or Java hot reload as that
missing visual acceptance.

On 2026-10-29, candidate versionCode `1793275142` passed Debug/Release and
instrumentation-APK builds, R8, full Release lint, 59 JVM tests and 10 resource
tests on `emulator-5580`. The main package advanced from `1793275000`; the test
package advanced from `0` to the same candidate version. API 102 reconciled the
Java hooks in system_server PID `8818`. Launcher PID/start ticks stayed
`9719 / 3194`; the crash buffer was unchanged. Night mode stayed enabled and all
three experimental monochrome settings stayed unset. The new native policy
server was not activated in that existing process.

Offline evidence includes 36 tint-profile rejection cases, 43 executed ARM64
tint cases, 59 selection-resolver checks, 35 selection instruction-path cases,
and 1,445 ARM64 host assertions over 39 successive synthetic root generations.
The policy-server tests cover parser limits, peer predicates, expiry and
concurrent reclamation under ASan/UBSan. Those synthetic roots are not a real
Dart GC test. Release signing and 16 KB ZIP alignment checks also passed; the
private candidate APK SHA-256 is
`ff04f72fec5a5b464e174b6be81b8a52e086d93171f060b6cee5c34b341e69f7`.

## Build and packaging

Android convention configuration lives in the included `build-logic` composite
build. Release uses R8 and resource shrinking with the narrow
`app/proguard-rules.keep` file; it does not keep the whole AndroidX or Compose
graph. The private test release is signed with the machine-local Android debug
key because no distribution keystore is stored in this repository. A
distribution release must provide a project-owned signing configuration before
publishing.

The app manifest contains only the launcher activity, the system-bound isolated
resource probe service, and the providers required by AndroidX/API 102. The
diagnostic report receiver and its app-to-system broadcast path were removed.
