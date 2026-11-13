# Hook initialization isolation and lifecycle recovery

## Scope

The public 1.9.0 screenshot report has no complete exception header, runtime
Diagnostic or reporter APK. The user's subsequent assessment is that this is
a ported ROM on an older phone. That is useful context, not proof of the
failure's cause. See [the screenshot diagnosis](launcher-wait-report-20261114.md).

This change hardens Java SystemUI initialization independently of that report.
It does not establish the cause of the reporter's launcher connection failure
and does not relax native or DexKit structural validation.

## Independent stages

Previously, material preference acquisition could abort splash setup and
existing-plugin discovery. Resolving the combined material profile also
preceded notification-icon startup. A failed first profile attempt left its
classloader permanently marked as scheduled.

The runtime now tracks independent initialization stages for plugin observation,
preferences, splash hooks, live-plugin discovery, notification icons, material
profiles and the material/volume/control-center installations. Material parsing
or preference failures do not prevent notification icons or splash setup.

Successful stages are not repeated. Recoverable failures permit one additional
attempt on a subsequent PackageReady/plugin lifecycle signal. An unavailable
early prerequisite remains pending without being logged as an error. Hot-path
feature guards retain their separate fail-safe policy; initialization retries
do not repeatedly invoke a broken rendering hook.

## Work and ownership

An on-demand dispatcher serializes initialization. Bootstrap has one pending
slot; each observed plugin has its own pending slot. Repeated signals replace
pending work instead of creating a new thread or running DexKit concurrently.
The diagnostic publisher remains separately coalesced. There is no timer,
periodic retry or permanent initialization thread.

Background priority is best-effort. A recoverable priority-setting failure,
including failure of its local diagnostic, does not prevent entry into the
dispatcher and strand its ownership token. Work still runs on the owned
worker, not the UI callback thread; fatal VM failures still propagate.

Preferences and partial notification-icon runtimes are retained before
registration/start. A retry cannot replace an icon runtime until its owned
resources have stopped. Preference acquisition/registration and framework
diagnostic delivery run outside the stage/queue state monitors.

Retired hook targets are handed over separately. One failed replacement does
not abort all feature initialization. Any successful new feature replacement
removes its target from pending neutralization, so a later retry cannot replace
that feature with a native pass-through. The pending descriptors refer to host
methods, not a second list of retired module callbacks.

Retirement still requires the lifecycle's active operations and actual owned
threads to drain. The dispatcher closes and releases pending closures only
after that gate; failed retirement can reopen admission. No forced GC, native
unloading or assumed ClassLoader collection is added.

## Host lookup

The existing plugin-factory class remains the primary host-loader anchor.
An actual SystemUI Application subclass is an additional fallback. The current
Application's classloader is also considered during hot reload. A probe does
not initialize the requested class; fatal VM failures are not silently treated
as missing classes.

A first diagnostic candidate incorrectly replaced the existing anchor with the
Application-name check. Although API 102 handoff succeeded, feature installation
did not resume on the AVD. That candidate is not functional acceptance. The
anchor was corrected and regression coverage uses JVM fixtures with the actual
binary names, because `Class.forName` rejects a loader returning a misnamed
class.

## Diagnostics and validation

Initialization warnings identify the failed stage, failure budget and whether a
later lifecycle signal may retry. Complete causes are queued for LSPosed at
Warning level as well as written to local Android logging. Hook-call diagnostics
remain off the UI callback/Binder path.

Run the focused JVM group with:

```sh
python3 scripts/checks.py hook-initialization --run
```

The group covers stage isolation, deferred prerequisites, bounded retries,
concurrent signals, completed-stage suppression, dispatcher coalescing,
launch failure/recovery, close/reopen, host-loader probing, fatal/interrupted
work, hook fail-safe behavior and retirement drain.

Evidence is under `.artifacts/research/hook-init-hardening-20261114/`.
Final receipts distinguish build, JVM tests, framework handoff, fresh feature
initialization logs and any instrumented/visual checks. Source inspection
alone, an old ready log, or an adopted-generation message is not feature-ready
acceptance. Validation uses the unlocked Phone AVD with unchanged native payload
and does not claim universal official-ROM or reporter-device coverage.

## Separate native audit

The accompanying read-only native audit identified transport startup and
diagnostic follow-ups, including the optional ZIP worker's startup wait,
policy-thread creation failure, and diagnostic-only late attach. Those paths
were not changed or validated as fixes by this Java hardening work. In
particular, this change does not enable late native patching or claim that an
unsupported native image is safe to initialize.
