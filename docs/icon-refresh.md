# Launcher icon refresh

KeiHome observes system night-mode configuration changes through LSPosed API
102 in `system_server`. The required LSPosed scope is `system`; the module does
not inject a native library into the launcher or terminate its process.

For each accessible launcher user/profile, it checks that the registered
`com.miui.home` callback and the package visibility are still valid, then
dispatches package-change callbacks to that listener alone. This asks the
launcher to use its own model and icon-cache update path. The callbacks are
serialized because this launcher can overlap asynchronous icon updates. Newer
night-mode generations cancel the remainder of older work. Listener snapshots
use the framework's registered-callback lock rather than taking its broadcast
slot; incompatible private framework structures disable the refresh.

Resource inspection runs in an isolated app process, not in `system_server` or
the launcher. It considers each launchable activity and alias. A package is
skipped only when its supported static resource graph and both rendered states
appear unchanged; unknown resources, animated or theme-dependent drawables,
errors, service failures and timeouts are refreshed conservatively. No app
drawable or package inventory is retained by the module. The app queries
visible launcher entries for each accessible profile and distinguishes
detected, undetected and uncertain resource support.

The status page reads the API 102 service connection, scope and loaded target
state. The system process separately sends narrowly scoped, system-UID-checked
installation and callback reports. These are historical observations, not
proof that a hook is still installed or that an icon visually changed.

For API 102 hot reload, KeiHome declines retirement while hooks, refresh jobs,
reporting or tracked worker threads are still active. Matching hooks are
replaced individually, and the new runtime schedules a reconciliation against
the current configuration. Hook replacement is not an atomic transaction, and
a late call from an old hook snapshot can still race reconciliation. If a
replacement fails, refresh is disabled and reported rather than presented as
successful. Reports are best-effort and may be absent.

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
