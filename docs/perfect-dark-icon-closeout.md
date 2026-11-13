# Perfect icon dark-mode closeout, November 13

This records the remaining checks after the static, layered and cross-surface
icon corrections. These changes are for the next release; the published 1.9.0
baseline and public changelog are unchanged.

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

The renderer identities remain `perfect-static-night-8` and
`perfect-dynamic-night-7`. Their composed rendering identity participates in
native-source generation, preview and imported-source freshness checks. An old
generated/imported archive must be updated and applied to obtain changed pixels;
updating the module APK does not rewrite the user's existing theme archive.

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
