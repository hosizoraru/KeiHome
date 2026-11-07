# Launcher 8604 compatibility

## Report and reproduction

The supplied launcher is `RELEASE-8.01.02.8604-260918-10282146-R`,
versionCode `801028604`. Its original APK and extracted native libraries are
retained in `.artifacts/inputs/launchers/8604/`. These are research fixtures;
production never chooses a profile by version, APK hash, symbol name or RVA.

The supplied LSPosed log records `native Monet capability unavailable` and
one Binder endpoint deadline. It does not contain the underlying native
resolver rejection, so the transport timeout alone does not establish the cause.
Installing the exact APK over the Phone AVD launcher reproduces the missing
native capability and `Dock inactive: ambiguous Dock instruction roles`.

## Dynamic contracts

- **Monet selection:** retain the previous five fixtures' complete contracts
  and append the newly observed dependency graph shapes. Validate their direct
  call relationships, pool loads and String consumers against the independent
  debug-data/Capstone oracle. Tint and monochrome-gate admission already pass.
- **Dock:** append the outer/phone instruction shapes without changing older
  contracts. Find the native root-list save by its bounded instruction
  relationship and bind it to the actual subsequent reload. A fixed relative
  offset inside the phone builder no longer determines that save. Missing,
  duplicate or inconsistent saves are rejected.
- **Image lifecycle:** append the guarded Image-clone body shape. Existing
  clone/cast relationships, image class identity and ownership checks remain
  required. This is a protection-path compatibility change, not a new cache.
- **Other features:** the existing label, highlight, glass material and search
  capsule contracts admit 8604 without new production changes.

All additions are startup parsing work. No new per-frame hook, timer, worker,
retained image or Dart object is introduced. Native bridges and their managed
frame/GC contracts remain unchanged.

## Verification boundary

Source verification covers 7722, 8593, 8602, 8604, 9209 and 9226. The full
selection audit includes independently decoded native roles and linked ARM64
bridge tests. Image recovery tests include rejection cases, and label execution
tests cover 8604's actual derived fields and original return PCs.

Live acceptance is on HyperOS Phone AVD only. The detailed test results,
screenshots, native logs, process identities, final Release identity and
restoration receipt belong in
`.artifacts/research/launcher-8604-20261107/acceptance.json`.
Do not interpret source acceptance as verification on the reporting REDMI
device or on all retained firmware versions.

## Hot-reload listener retirement

The first development activation exposed a separate Android display-dispatch
failure: `HandlerExecutor` rejected a callback after its worker queue quit,
terminating `system_server`. The trace does not identify the delegate's owner,
but the module's `MonetColorMonitor` had this exact unsafe lifetime: listener
unregistration was queued and `quitSafely()` was called immediately.

Retirement now closes that listener's executor first, unregisters the listener
on its worker, and quits from cleanup's `finally` block. The executor also drops
callbacks racing with retirement, including callbacks already queued. Unexpected
active-queue rejection and unrelated callback failures remain visible.
Six focused concurrency/lifetime tests exercise these distinctions.
The failed activation remains recorded and is not counted as process-stable
acceptance; subsequent Java hot reload and setting changes require a new receipt.

## Accepted checkpoint

Release `1.4.0+g59ea509.b0226` (`104000226`), signed with KeiOS Release:

- The exact 8604 APK was installed over the Phone AVD launcher. The final module
  reports ready/connected, with actual Dock glass and native icon effects.
- Dock OFF/ON advances revision 2 then 3, with matching native acknowledgements.
  Monet ON reaches the system setting and renders on the desktop; it is restored
  OFF. Light/dark mode and display sleep/wake also complete.
- The final Java update and a subsequent same-APK replacement restore fresh
  runtimes while preserving Launcher `5737/start8675688`, system_server
  `29043/start8668563`, and SystemUI `2258/start8671481`. Settings and display
  tests preserve these identities too. The post-fix crash buffer has no new
  fatal entry during this bounded run.
- Source checks cover six retained versions. Selection graph checks and linked
  tests pass; Dock has 128 structural/rejection checks and seven isolated
  root-relation checks per fixture. Final linked payload checks include 686
  label cases, 35 mode cases with 8604 metadata, and the Dock render suite.
  The six retirement unit tests, Release assembly and Release lint pass.
- The original launcher 8593 was restored by cover installation, with no
  uninstall or data clearing. Its actual native builder `0x14a359c`, matching
  loaded APK path, ready/connected state, and Dock acknowledgement were checked.
  SystemUI and system_server remain unchanged through restoration.

Native payload changes need a one-time new launcher process to load the updated
machine code. API 102 Java reload and normal preference changes do not require
that development activation. This checkpoint makes no physical REDMI acceptance
claim or performance-improvement claim.
