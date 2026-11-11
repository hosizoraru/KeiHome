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
App data. No App data was recreated or theme reapplied after this discovery.

Device writes stopped. The final UI and live material-pair tests therefore did
not run: instrumentation could not find its target package. Original App-data
recovery and/or an isolated test instance require a separate environment
choice. Do not describe the old imports, source caches or settings as restored.

Retained evidence is under `.artifacts/research/desktop-categories-20261111/`:
`qemu-system-aarch64-2026-11-11-093243.ips`, `native-activation.log`,
`recovery-all-logcat.txt`, `avd-recovery-launch.log`, and `final-ui-tests.log`.
The Android deletion records include the failed parse, invalid-package removal,
and subsequent data-wipe warning.

The developer deployment helper now flushes guest I/O and checks the installed
base APK SHA-256 before an explicit native process replacement. It refuses the
restart if package lookup or integrity checking fails. This guard is not a fix
for the observed host graphics crash and has only host syntax validation in
this run. Ordinary Java deployment still does not request a process restart.
