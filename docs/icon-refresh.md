# Launcher icon refresh

KeiHome observes system night-mode configuration changes through LSPosed API
102 in `system_server`. The required LSPosed scope is `system`; the module does
not inject a native library into the launcher or terminate its process.

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

Resource inspection runs in an isolated app process, not in `system_server` or
the launcher. It considers each launchable activity and alias. A package is
skipped only when its supported static resource graph and both rendered states
appear unchanged; unknown resources, animated or theme-dependent drawables,
errors, service failures and timeouts are refreshed conservatively. No app
drawable or package inventory is retained by the module. The app queries
visible launcher entries for each accessible profile and distinguishes
detected, undetected and uncertain resource support.

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
It does not implement Monet or monochrome icon generation.

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
