# Phone AVD activation incident, 2026-11-11

The development native activation installed diagnostic 106000367 and verified
API 102 Java handoff with unchanged critical identities. Replacing the Launcher
spawner then coincided with the macOS QEMU process exiting. The macOS crash
report points to the host MoltenVK/Vulkan memory-release path. This does not
establish a guest Hook crash or a fully diagnosed host-driver root cause.

The canonical Phone launcher was used to reopen the same AVD without a data
wipe. Android's boot scan then failed to parse the newly installed KeiHome APK,
deleted that invalid package, and explicitly logged that its App data would be
wiped. The former App directory and protected Perfect source ZIP were absent.
The inspected AVD backup directories contained launcher scripts, not recoverable
App data. The original imports, source ZIP and preferences were not recovered.

Device writes initially stopped. The first instrumentation attempt could not
find its target package; this was an environment failure, not a UI assertion.
The user subsequently authorized reinstallation and a full system reboot.

The preserved LSPosed `log.old` reports ENOENT for a different APK path from
the one PackageManager rejected. Its higher-level I/O/parser exceptions do
not establish ZIP corruption. Available release and diagnostic APKs pass CRC,
signature and alignment checks. The exact failed diagnostic 106000367 was
overwritten before preservation, so its bytes cannot establish the cause of
the boot parser failure.

## Authorized recovery and acceptance

Release candidate 106000371 was reinstalled without clearing data or
uninstalling another package. Its on-device SHA-256 matched the retained
artifact after `sync`. A normal Android `adb reboot` succeeded and retained
the APK. Package removal had also cleared the module authorization records.
Enabling KeiHome through the official LSPosed manager restored its seven
declared scopes; a read-only database snapshot confirms those grants.

A second normal Android reboot, after authorization and another integrity
check, crashed the host in `_platform_memmove -> gldRenderVertexArray ->
glDrawElements -> gfxstream CompositorGl`. Earlier direct SIGSTOP/SIGCONT
preservation also coincided with a separate host gRPC callback abort. Directly
pausing the QEMU process is not an appropriate recovery procedure and must not
be repeated. These distinct crashes do not establish that the material bridge
caused them, nor demonstrate that the emulator graphics fault is fixed.

The same Phone instance was cold-started through
`/Users/voyager/HyperOS-AVD/maintenance/adb-isolation/run_avd.py phone`, the
flow behind `Start KeiOS Phone.command`. The emulator is managed by launchd,
independently of the Codex process. No disk replacement, wipe, theme application
or Launcher layout reset was performed. Fresh KeiHome App data was created by
reinstallation; this is not recovery of the deleted data.

Cold startup loaded the current module. API 102 update 106000371 -> 106000373
then passed with unchanged Launcher, SystemUI and system_server identities.
Live material tests received schema-8 reports with capabilities 127 and exact
Dock/search acknowledgements from Launcher PID 9642, start ticks 3290. They
exercised independent light/dark/system values, Follow Dock, and restoration
in `finally`, without a Launcher restart.

The focused device invocation passed 16 of 17 checks. The remaining navigation
assertion matched both outgoing and incoming status symbols during a transition.
Scoping that assertion to the current category, and waiting for a unique Back
action, resolved the test ambiguity; all three navigation checks then passed.
Together these runs cover 17 distinct device checks, including About copying,
preview preference placement/persistence, category navigation, progress states,
and the live native capabilities/material pair. This is Phone AVD acceptance,
not physical-device or host-emulator stability acceptance.

Retained evidence is under `.artifacts/research/desktop-categories-20261111/`:
`qemu-system-aarch64-2026-11-11-093243.ips`, `native-activation.log`,
`recovery-all-logcat.txt`, `avd-recovery-launch.log`, and `final-ui-tests.log`.
The Android deletion records include the failed parse, invalid-package removal,
and subsequent data-wipe warning.
Recovery evidence adds `pre-reinstall/`, the three host crash reports,
`incident-analysis/REPORT.md`, `restored-scope.json`, `recovery-java-reload/`,
`recovered-focused-ui-tests.log`, `recovered-desktop-navigation-tests.log`,
and `recovered-glass-pair-acceptance.txt`. Exact recovery QA APKs are retained
under `recovery-qa/106000373/`.

The developer deployment helper now flushes guest I/O and checks the installed
base APK SHA-256 before an explicit native process replacement. It refuses the
restart if package lookup or integrity checking fails. This guard is not a fix
for the observed host graphics crash and has only host syntax validation in
this run. Ordinary Java deployment still does not request a process restart.
