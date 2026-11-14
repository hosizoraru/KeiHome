# Perfect icon dark-mode closeout, November 13

This records the remaining checks after the static, layered and cross-surface
icon corrections. These changes are for the next release; the published 1.9.0
baseline and public changelog are unchanged.

**Latest status, November 14: own-update heads-up icon masking is unresolved.**
The user's 13:46 screenshot again shows an opaque square behind the wrench
icon in KeiHome's own module-update banner on the Phone AVD. Earlier isolated
success and successful API 102 handoff do not close this visual issue. See
[the reopened acceptance record](#reopened-own-update-heads-up-masking-november-14).

## Code and resource audit

No further production renderer defect was identified in this closeout.
The inspected fixes cover:

- Same-theme flat/layer equivalence proofs, with bounded channel/alpha rounding
  tolerance and original day bytes retained.
- Neutral foreground symbols and their antialias fringes, including Calculator
  marks and Notes. Colored and white artwork retain their existing treatment.
- Calendar date textures and dynamic foreground alpha.
- Native Telegram channel completion, including layered geometry and preservation
  of explicitly authored channel artwork.
- Status-bar provenance and already-visible native icon continuity, followed by
  bounded requests arriving before the new SystemUI cache starts.

The renderer identities are `perfect-static-night-9` and
`perfect-dynamic-night-7`. Their composed rendering identity participates in
native-source generation, preview and imported-source freshness checks. An old
generated/imported archive must be updated and applied to obtain changed pixels;
updating the module APK does not rewrite the user's existing theme archive.

The November 14 [brand-wordmark follow-up](brand-dark-wordmarks-20261114.md)
adds bounded word-row admission for warm, asymmetric lettering, including Idlefish
and Meituan. Notification row and miniature-preview masks remain a separate
acceptance gate from these generated pixels.

## Completed software checks

Evidence is retained under
`.artifacts/research/dark-icon-closeout-20261113/`.

The installed diagnostic 109000436's production renderer and test assertions
were executed using Android's software Bitmap/Canvas implementation in a
standalone `app_process`, without launching an Activity:

| Check | Result |
| --- | --- |
| Same-theme flat/layer proofs and unchanged day bytes | Browser, File Manager, Gallery, Calculator and Notes passed |
| Mixed Calculator foreground symbols | Passed |
| Antialias fringes, shadow cutoff and dark underlay | Passed |
| Dynamic foreground dimensions and every alpha value | Passed |
| Retained calendar date 13 texture | Passed |
| Bounded cold requests, drain on cache start | Passed |
| Stop before start remains terminal and idempotent | Passed |

The 15 source PNG checksums were verified on the guest before the final real
fixture run. Earlier failed attempts used incorrectly named/nested QA fixture
directories; their logs are retained separately. They are not production
renderer failures. An initial broad standalone run exceeded its 90-second budget
and was stopped; only the explicitly completed selected cases are counted.

These are pixel and cache assertions. The isolated cache context deliberately
supports only the resource/cache operations and recording registrations used by
the selected tests. It does not prove Android service registration or a visible
notification update. Debug/interpreter execution durations are not performance
measurements.

## Remaining visual acceptance

The existing release-425 continuity evidence covers an already-visible Updater
status icon across an ordinary API 102 handoff. The first heads-up notification
created by KeiHome's own update needs separate acceptance; a second reload or a
later notification does not substitute for it.

For this check, first unlock and enter primary HOME, wait for relevant runtimes
to settle, and pass all deployment health gates. Record the first ordinary
self-update and the resulting banner through its settled state, retaining old
retirement/new adoption and critical-process identity receipts.

The guest screenshot interface has repeatedly timed out. Emulator-console
capture can retrieve a valid full-resolution image, so the deployment tooling
now supports it directly. A valid PNG alone does not prove a responsive desktop:
user/keyguard, WMP, SystemUI and recent-failure checks remain mandatory. In
particular, a cached frame must not override a service timeout or recent Provider
failure.

This is not an all-style or all-device acceptance claim. The retained real
artwork is from the downloaded HyperOS4 source; unsupported photographic art
and resources that cannot satisfy structural proofs intentionally keep their
safer existing rendering.

## Physical update and framework-notification identity

The authorized phone `192.168.31.105:5325` was unlocked on Home before the
November 13 update from `1.9.0+g214d43b.b0391` to the signed, local Release
`1.9.0+g58bac49.b0438` (`109000438`). Ten observed host processes recorded both
retirement and adoption. The existing deployment reconciliation checks passed,
and Launcher, System UI and system_server retained their PID, parent and start
time. The active icons archive hash remained unchanged. This validates a code
update, not regeneration or application of the newer dark icon resources.

The module-updated notification was independently identified on that phone:

- Package and operation package: `android`, UID `1000`.
- Channel: `lsposed_module_updated`, tag: `os.kei.hyper.home`.
- Small icon: a `78 x 78` bitmap; `android.appInfo` identifies `android`.
- The substituted display name is `LSPosed`.

The retained Vector source also posts the update through `android` and supplies
its own notification bitmap. Its notification-specific small icon must be
distinguished from the OEM header's application-icon slot. Package identity,
bitmap dimensions and the view's position alone do not justify replacing the
notification-specific symbol or treating it as themed artwork.
The exact running framework revision was not established by this source
comparison.

No first heads-up banner was observed in the physical update recording, so
this attempt does not close the earlier first-self-update visual check. It also
does not establish that a second update is required. The app-icon header's
producer/consumer provenance must be checked before changing this boundary.

The update additionally logged a native theme-import attempt while the Themes
bridge was unavailable. It did not alter the active icon archive; no successful
resource application is inferred from the successful Java handoff.

Evidence is in `.artifacts/research/dark-icon-physical-20261113/`, including the
bounded notification identity, update receipt, module-log window, Release
receipt and build/lint log. No phone restart or scope change was performed.

## Earlier first-notice correction in diagnostic 109000445

The user's paired screenshots reopened this check: the own-update notice was
square, while a later notice for another module was rounded. Bounded diagnostic
hooks then observed an initially opaque-corner native `114 x 114` app bitmap
and a later masked `192 x 192` bitmap from the shared cache. `android` is a valid
native icon resource name; its header does enter the pipeline. Do not infer
exclusion from the helper name `validDottedName`.

The correction uses the authenticated cached-app-icon consumer, preserving the
separate notification-specific small/custom/hybrid roles:

- When native code has just installed its exact cached app drawable, bind and
  apply it again even if that view already had an older association. A previous
  association alone must not hide a new native overwrite.
- For framework notifications only (`android`, app UID `1000`, same user), retain
  at most eight weak native row references during the first 30 seconds of
  installation. Both the row and notice must still be eligible and current.
- Once the shared bitmap cache is ready, request each attached native row's
  own `reInflateViews()` at most once. The host rebuilds its notification
  content, including a header captured before the new icon pipeline was ready.
  No notification icon is generically clipped or replaced by this replay.
- Resolve that optional public native row method using the authenticated
  consumer's SystemUI loader, not the plugin loader. Missing roles fail
  independently of the icon pipeline.
- Mark a row consumed before invoking native code, do not hold the helper lock
  while invoking it, and clear admission/references during retirement.

Fifteen focused device tests passed for row admission/readiness/ownership and
existing app-icon binding boundaries. The own-update recording for diagnostic
`109000445` shows the same notice rounded after one native row refresh; the
module log records `rows refreshed=1`. No second module update was needed.
This verifies correction within the first notice, not a claim of zero native
fallback frames before the asynchronous bitmap is ready.

The temporary per-bitmap diagnostics were removed before Release compilation.
Evidence: `row-loader-first-update.webm`, `row-loader-settled.png`,
`row-loader-live.log` and `row-replay-device-tests.log` in the physical-research
directory above. Final optimized Release acceptance is recorded separately.

## Reopened own-update heads-up masking, November 14

**Status: known unresolved visual defect; further repair deferred.**

The user supplied a new Phone AVD screenshot at 13:46 showing the floating
`Xposed module updated` notification for `os.kei.hyper.home`. Its wrench icon
still has a solid square background. The banner says nine processes were hot
reloaded. This is the first notice associated with reloading KeiHome itself,
the same scenario previously distinguished from a later notification produced
by updating another module.

The last recorded installation is signed Release
`1.9.0+g8edc759.b0495` (`109000495`). The screenshot does not independently
identify the installed version. That deployment's API 102 handoff and
Launcher/SystemUI/system_server continuity passed; the Runtime and Monet sheet
visual checks also passed. Those are separate acceptance gates and do not
establish correct heads-up icon masking.

The diagnostic-445 recording above remains evidence that one native row
refresh corrected one first notice. The new screenshot reopens the broader
repair: neither a cache-ready notification nor that earlier successful refresh
proves this path works consistently. The precise cause of this occurrence is
unconfirmed. Initial handoff, native header caching and row-refresh timing are
investigation directions, not established causes. No additional device
reproduction, runtime-code change or build was performed for this report.

Earlier live identity evidence associated this framework notice with package
`android`, UID `1000`, channel `lsposed_module_updated`, and module tag
`os.kei.hyper.home`. Those details were not re-probed from this screenshot.
Preserve the distinction between its notification-specific wrench symbol and
the application-icon header when resuming work.

Retained evidence:
`.artifacts/research/self-reload-headsup-open-20261114/`, containing
`user-self-reload-square.png` and `report.json`. The dashboard-sheet acceptance
receipt now explicitly excludes this unresolved notification path.

Closure requires repeated ordinary self-reloads after unlocking and entering
HOME, recording the first floating notice from appearance through its settled
state. The icon must remain correctly masked, with no disruptive host restart.
A second reload, another module's update, an already-visible status icon, or a
successful handoff receipt cannot substitute for that visual check.

The later Wallet, Weather and folded-notification quiet-image defects have a
separate archive-boundary repair documented in
[published composite masks](published-icon-composite-masks-20261114.md). Those
checks must not be used to close the first self-reload heads-up issue above.
