# Launcher icon refresh

KeiHome observes system night-mode configuration changes through LSPosed API
102 in `system_server`. The required LSPosed scope is `system`; the module does
not inject a native library into the launcher or terminate its process.

For each accessible launcher user/profile, it checks that the registered
`com.miui.home` callback and the package visibility are still valid, then
dispatches package-change callbacks to that listener alone. This asks the
launcher to use its own model and icon-cache update path. The callbacks are
serialized because this launcher can overlap asynchronous icon updates. Newer
night-mode generations cancel the remainder of older work.

Resource inspection runs in an isolated app process, not in `system_server` or
the launcher. It considers each launchable activity and alias. A package is
skipped only when its supported static resource graph and both rendered states
appear unchanged; unknown resources, animated or theme-dependent drawables,
errors, service failures and timeouts are refreshed conservatively. No app
drawable or package inventory is retained by the module.

## Validation and limits

The current flow has been exercised on `KeiOS_HyperOS4_Phone`, Android API 37,
with `com.miui.home` version `RELEASE-8.01.02.7722-260904-09221445-R`.
KeiHome and two ErinAO icons were checked in both directions, including rapid
mode changes and switching while another app was foreground. The launcher PID
and process start time remained unchanged. This is not evidence for other
launcher builds, devices, private/work profiles, deep shortcuts or every
folder and widget configuration.

The framework service reflection is validated during hook installation and
unsupported signatures disable the refresh. Launcher package-update semantics
can vary across HyperOS versions and still need device-specific verification.
The 400 ms interval is a conservative tested delay, not an acknowledgment that
the launcher's asynchronous update has finished. Icons can therefore update
one after another, and a large number of candidate packages takes longer.
When the isolated inspector is unavailable, all accessible launchable
packages are refreshed instead of risking missed icons.

The solution uses launcher package callbacks and does not depend on whether
`libhyper_os_flutter.so` comes from the launcher APK or the system partition.
It does not implement Monet or monochrome icon generation.
