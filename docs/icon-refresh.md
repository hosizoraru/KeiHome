# Launcher icon refresh

KeiHome observes system night-mode configuration changes through LSPosed API
102 in `system_server`. This refresh path requires the `system` scope and does
not terminate the launcher process. A separate experimental native payload
also targets the launcher for themed-icon tinting and resource selection.

The wallpaper picker, extracted representative colors, selectable schemes and
latest 2026-10-30 validation are described in [Wallpaper colors](wallpaper-colors.md).

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
errors, service failures and timeouts are refreshed conservatively. App drawables
and package inventories are not persisted. Monet inspection caches resource
identity metadata within its Java generation. Separately, the app
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

### Batched resource refresh

For a full or night-mode (`RESOURCE_CHANGE`) refresh, KeiHome first sends
`onPackagesUnsuspended(user, packages)` once for every eligible package.
Eligible packages have a single launcher entry and are not suspended.

- HyperOS Home's Rust model handles this callback in
  `AllAppsList.update_disabled_flags`. It rewrites only the suspended word of
  each package's first entry, which is 0 for an unsuspended package, and marks
  the entry modified.
- Dart's `ItemChangeProcessor._handleAppUpdate` then evicts and reloads all of
  those icons in a single model update.
- Suspended and multi-entry packages, shortcut callbacks and targeted package
  refreshes keep the paced `onPackageChanged` path.
- Home ignores a batch identical to its previous one for about five seconds, so
  consecutive batches alternate the package order. Each user profile alternates
  on its own, because one refresh sends a batch per enabled profile.
- A batch needs at least two packages. If the framework has no such callback, or
  the callback fails, every package of that profile uses the paced path.

On phone `82411d42` (Home 9239), 307 entries changed in one update, and icon
loading finished in about 5 s instead of about 70 s of paced callbacks. See
[static-appearance-icons.md](static-appearance-icons.md#device-evidence).

## Runtime-resolved two-tone Monet

### Official icon styles and theme-owned artwork

The resource policy now inventories the active `/data/system/theme/icons` archive,
the stock archive when no active archive exists, and the system `miui_mod_icons`
directory. The verified launcher initializes its mod-source gate to true; the
Default, Classic and Boundless configurations do not override that gate.
The inventory recognizes exact package/component PNG names, fancy/animating MAML
descriptors and explicitly declared Classic layers. It does not guess aliases or
interpret an arbitrary PNG as an adaptive-icon monochrome layer.

Packages with matching theme artwork keep the launcher's original styled path.
An APK's monochrome mask cannot replace a theme's own drawing, including its
native light/dark selection. Night changes also refresh theme-covered launcher
entries through LauncherApps. A real icon-theme configuration change invalidates
the worker-side inventory and requests a full refresh. No theme archive is edited
by the module, and no Dart object is retained or replayed for this feature.

The existing native veto is package-keyed, so a themed Activity protects its whole
package. Unknown native aliases and theme-provided monochrome formats are not
claimed as supported. Inventory completion means the recognized resources were
read successfully, not that their rendering or night behavior was proven. An
incomplete/replaced archive preserves the last published Monet policy; the night
refresh conservatively includes all visible entries until the inventory recovers.

AVD resource inspection found no proven light/dark pair in the three supplied
official packs. Unit fixtures cover night-qualified resource ownership; visual
checks cover each pack's native artwork, APK night icons and Monet rendering.
Latest Classic/Boundless functional screenshots used a temporary secondary-display
power-off control. Classic previously triggered a real launcher input ANR under
normal load. The subsequent default-style, two-display mixed check was a bounded
pass after restoring display power. These results do not establish no-restart
acceptance for arbitrary theme packages or close the historical ANR issue.
See `anr-investigation.md` for the separate stability failures and test conditions.

The native foundation now resolves the loaded Dart image instead of consulting
an APK SHA/version allowlist. Following MiuiBackGestureHook's mapped-code
resolver approach, it scans bounded executable segments, identifies instruction
contracts, and verifies their actual call relationships. Branch destinations,
object-pool indices, controller field-table slots and allocation class IDs are
decoded from the current image. Object-field layouts, stack/register contracts,
String representation and GC/lifetime boundaries remain explicit checks.

Tint fingerprints normalize only relocation operands. The optional capability
gate parses the async support/preference graph and derives its branch target,
preserving the original `Future<bool>` completion. Selection verifies the entry's
callers and live factory/callback pool linkage before a package-specific veto.
No resolver retains a Dart heap reference, calls into Dart, or uses a version RVA.
Native policy acknowledgement is the system-side compatibility boundary; the
old Kotlin whole-file SHA gate has been removed. The backing-file hash is still
computed to bind a native publication to its loaded image, not to whitelist it.

The same instruction contracts are exercised against the 7722 and 8593 images.
This supports compatible code families across relocation changes; it does not
promise arbitrary future compiler/object-layout changes. Ambiguous or changed
contracts preserve original behavior. See `launcher-8593-compatibility.md` for
initial failure evidence and the subsequent device adaptation record.

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

### Dart ABI and ownership

The foreground constant is `ColorFilter.mode(black, srcIn)`. A leaf assembly
bridge changes only the unboxed ARGB argument at the verified conversion call.
It checks the caller, live pool constant, blend mode, original color, current
controller and selected background. The two original filter setters own engine
filter invalidation; narrowly scoped equality prevents an obsolete filter from
being retained, including after policy withdrawal. Before the process accepts its
first enabled tint policy, equality stays stock: no module-tinted filter can exist
yet. A native-owned monotonic flag is armed before publication and survives Java
hot reload, expiry and withdrawal. It retains no Dart object. After first enablement,
the existing per-instance invalidation behavior remains; this is not an active-mode
frame-rate optimization. Other filters pass through.
The launcher's resource debug demo shares this exact constant and is also in
scope.

Malformed-resource selection is denied at the original package capability
query before its cache is consulted. The live String class, closure links,
callers and image are validated. Mounted icon controllers additionally cache a
selection bool. A separate `Object.==` guard authenticates both caller PCs,
Dart stack shape, bool GDT entry, two true operands and the live controller's
RxBool receiver. On a new selection revision it returns false once, allowing
the original Rx setter and stream to recompute subscribed controllers.
A compare-and-swap cannot consume a newer revision or rewind another reader.
Palette-only renewals do not generate selection revisions. When the acknowledged background
changes and the user, native owner, fallback set and theme source remain the same,
the original color observer paints the new palette without a full package sweep.
Foreground-only changes with an unchanged background still request a sweep to
avoid Rx equality suppressing the update. First publication, Java reload and
explicit apply retain their full source reconciliation.

These bridges do not allocate, invoke Dart/C, retain heap references, write
Dart objects, replay a State, or replace the Flutter root tree. They use live
objects synchronously on the original Dart thread. Native immutable snapshots
contain only primitive colors and copied package bytes. Readers pin before
loading; retired policy storage stays alive until those readers drain.

### Authenticated policy and hot reload

Direct abstract-socket connections are denied by the AVD's SELinux policy in
both directions between system_server and the launcher. KeiHome does not change
SELinux. Instead, a guarded private transaction on the generated PackageManager
Binder stub transfers one endpoint of a system-created socketpair. It validates
the kernel caller UID/PID, foreground launcher identity and process start time
before allocation. Native verifies creator UID 1000 and system_server peer
context. Normal PackageManager transactions retain their original handling.
The PFD is encoded as a typed Parcelable, matching the NDK Binder reader.
No exported service or additional app permission is introduced.

The Java generation owns bounded channels, deadlines, pending and borrowed
endpoints, and their cleanup worker. Retirement stops admission, closes channels
outside lifecycle locks, and waits for tracked work to drain. New API 102 Java
generations replace hooks and reconnect to the resident native foundation.
A 15-second renewal maintains a 45-second denial lease. Expiry removes denials
and marks selection notification pending, but without a Java owner there is no
immediate repaint request; mounted selection updates on the next original
settings/controller event. Accepted colors remain eligible only while the live
selected background matches. No unauthenticated timer creates a policy.

Settings writes follow native ACK. The owner then notifies the existing color
URI, excluding its own observer, so the original launcher stream handles the
change without cycling the enable setting. Manual presets, transparent values
and the stock off value are preserved unless the user explicitly requests
wallpaper following. SettingsProvider has no multi-key compare-and-set; the
implementation rechecks ownership/state and fails closed on detected changes.

API 102 reloads Java hooks, observers, preferences and policy automatically.
It does **not** replace changed executable native bytes already mapped by a
HYOS process. The stable native foundation is deliberately resident; first
activation or a future native ABI change requires a launcher-generation restart.
Theme changes, palette changes, fallback updates and the App action contain no
launcher termination path. The developer-only `scripts/deploy-hyperos-avd.py`
enforces exact AVD identity, increasing versionCode and Java reload evidence.
Its explicit `--reload-native` option replaces the verified owning spawner for
native development, following MiuiBackGestureHook's deployment model. It is not
called by the module or by an ordinary APK hot install.

### App request and ownership

The MIUIX Monet page offers wallpaper/basic colors and scheme selection in
English, Simplified Chinese and Japanese. API 102 remote preferences atomically
carry the choice, a monotonic revision and requesting user. The system-side reader accepts a fresh request
only for the current foreground user, checks the native ACK and current choice,
then records consumption, writes the background/ownership marker, enables icons
and publishes a separate applied receipt. The App reports success only for its
matching applied revision; submission alone is not success.

Automatic following owns both tint and fallback policy. Selecting a stock
manual preset withdraws that policy and preserves the original launcher mode.
A handled revision cannot replay over a later manual preset after Java reload.
Detected manual changes cancel the pending revision without writing the color
or toggle. Failed-I/O retries retain the first observed selection within the
current synchronizer. Only a successfully persisted cancellation survives a
reload; the in-memory baseline itself does not. These checks do not create a
SettingsProvider transaction or eliminate races with an external writer.

### Resource fallback

The isolated inspector uses application icon owners, matching HYOS selection,
including installed packages without their own launcher entry. It evaluates the
current night configuration across enabled profiles. Conflicting per-package
sources remain unknown because the native selector is package-keyed.

Uniform-alpha layers are unusable. A conservative bitmap check also detects
padded full-color squares with a dense opaque interior and no alpha detail,
which otherwise become solid blocks when tinted. Sparse masks, real cutouts,
single-color silhouettes and small antialias color noise are preserved.
Animated, unreadable, theme-dependent or otherwise unknown resources keep the
original selection unless the same installed source was already known unusable.
A transient unknown result retains that known fallback; a positive usable/absent
result, changed source or removal clears it. Batches are bounded at 128; unresolved evidence is retried
after 60 seconds rather than rasterized at each 15-second policy renewal.

On the AVD this denies `com.hyper.podsrx`, `com.miui.voiceassist` and
`com.miui.voiceassistProxy`. HyperPods-X and Super XiaoAi visibly return to their
ordinary color icons after policy notification, without toggling the icon mode.
KeiHome and both ErinAO packages retain valid monochrome glyphs. The App's
resource-capability list remains detection evidence, not a per-icon repaint ACK.

### Validation boundary

The host suites cover exact backing-image identity, 41 tint-profile rejection
cases, 61 executed ARM64 tint/notification cases, selection resolver/bridge
checks, register/stack preservation, parser/authentication predicates, selection
revisions, expiry and concurrent retirement under ASan/UBSan. External ELF
fixtures are required; proprietary binaries and heap dumps are not committed.
Unicorn does not establish real Dart GC behavior or visual correctness.

The authorized native development replacement established launcher PID `1235`,
start ticks `2936707`, and system_server PID `8818`. Subsequent normal theme
changes visibly produced dark backgrounds/light glyphs and light backgrounds/
dark glyphs for KeiHome and both ErinAO packages, with those identities unchanged.
The AVD dark pair was background `0xff2b2e4d`, foreground `0xffbec2ff`.
Both ErinAO entries were also checked together inside an open folder.
KeiHome also switched in a five-slot Dock. Closed-folder thumbnails and the
open folder both followed the palette. A temporary managed profile showed
ErinAO and KeiHome changing in both directions while retaining their profile
badges; the temporary profile was then removed. These checks cover ordinary
activity entries, not arbitrary pinned-shortcut or widget artwork.

During the combined UI instrumentation run on 2026-10-30, the earlier launcher
PID 1235 suffered a FocusEvent(hasFocus=false) ANR and was ended by Android at
00:32:13 +0800. Its main thread was sampled in futex_wait_queue, but debuggerd
registration failed and no usable native stack was collected. CPU pressure
some/avg10 was 73.49; that is context, not proof of causality. The three UI tests
that failed lifecycle cleanup/attachment passed a focused rerun. This does not
exonerate the module or establish that the ANR is fixed. Unrelated Object.==
callers now take a bounded fast rejection path, independently covered by the
ARM64 tests. Subsequent checks use a new baseline and must not be joined to the
old PID's stability record.

#### Earlier Release validation: 1793293083 through 1793294125

The following records describe the earlier native/action implementation. See
[Wallpaper colors](wallpaper-colors.md) for the latest APK and AVD state.

Release versionCode `1793293083` was installed with the explicit native
replacement option after the authorized development restart. New launcher
baseline: PID `14567`, start ticks `3727021`; system_server remained PID `8818`.
The MIUIX App action successfully enabled themed icons from a disabled/manual
selection, received its matching Secure applied receipt, and left the App in
the foreground. The private receipt was readable by the App on this AVD. Serialized foreground
App/Home checks then ran eight cycles with Monet off and eight with Monet on.
All sixteen kept PID/start `14567 / 3727021`. This smaller focus scenario did
not reproduce the earlier ANR; it does not prove the original stress failure
has been fixed.

Final Release `1793294125` was hot-installed over `1793293083`; the native
payload SHA-256 was unchanged. Java reconciliation completed without changing
launcher `14567 / 3727021`, system_server or SystemUI. A manual color of `-1`
and cleared ownership marker survived the upgrade, so the old applied request
did not replay. A fresh App request then restored automatic following. The
final dark/light/dark checks kept the same launcher identity. Screenshot pixel
counts confirmed background/foreground `#2B2E4D / #BEC2FF` in dark mode and
`#E0E0FF / #2F37AA` in light mode; HyperPods-X stayed in its ordinary color form.
Release diagnostics use explicit state labels instead of R8-obfuscated names.

The integrated build passed 94 JVM tests, Debug/Release and AndroidTest packaging,
R8, full Release lint, signature verification and 16 KB ZIP alignment. Device
resource tests passed 10/10. The separate UI/data suite passed 15/18 initially;
the three lifecycle/attachment failures passed their focused 3/3 rerun. The
initial all-class instrumentation startup also timed out publishing providers,
before the grouped runs. These retries are retained as test-environment evidence,
not a claim that the original stress ANR is resolved.

The AVD was restored to night mode `yes`, with `key_monochrome`,
`key_monochrome_color` and `keihome_monet_auto_color` unset. The two ErinAO icons
and KeiHome were returned to their original workspace positions; the temporary
folder and managed profile were removed. The test-only MIUI AppOp 10021 returned
to `ignore`. The latest Release remains installed, and handled/applied request
receipts are retained to prevent stale request replay. Foreground launcher
PID/start remains `14567 / 3727021` after restoration.

Final APK SHA-256:
`65a69b2c618e91bfe478bac9210f7586c8ab43655f643ade8b2e4973f95ce02e`.

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
