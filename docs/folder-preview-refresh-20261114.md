# Folder preview disappearance after theme application

## Reproduction

On November 14, the Phone AVD reproduced the reported empty closed-folder
previews after an update/application attempt using the already selected
Perfect Icons HyperOS4 archive, followed by returning Home. The folder plate
and title remained visible. Gallery and Settings in the Dock also disappeared;
other desktop icons remained visible. Swiping to the adjacent page and back
restored both folder previews and the affected Dock icons.

The AVD was unlocked, Home was connected and Android services were responsive.
The installed module was diagnostic `1.9.0+gf2eb553.b0445.dirty`
(`109000445`), not the latest source HEAD or a new public release. Home was
8593, PID 9709. This run does not establish boot-time reproduction, behavior
on all launcher versions, or physical-device acceptance.

Evidence is retained under
`.artifacts/research/folder-preview-20261114/`:

- `preflight/preflight.png`: normal initial desktop.
- `app-current.png`: the App's current Perfect Icons HyperOS4 selection.
- `after-update-attempt/preflight.png`: empty folder previews and two missing
  Dock icons after returning Home.
- `after-page-return/preflight.png`: restored content after horizontal paging.
- `launcher-before.stat` and `launcher-after.stat`: process identity.
- `launcher-after-update.log` and `render-lifecycle-excerpt.log`: native and
  Flutter rendering/lifecycle evidence.

The interaction sequence included repeated attempts to activate the update
button because the UI hierarchy did not initially change. The capture proves
the symptom and recovery, not a precise one-tap latency or number of update
operations. Do not use this sequence as a performance benchmark.

## What is established

The log around theme application includes disposal of drawable-handle caches
and engine-snapshot token transitions from 1 through 4. An unchanged launcher
PID therefore does not establish an unchanged engine/isolate lifetime.
Subsequent messages include
folder-preview snapshot selection, MAML snapshot capture and draw scheduling.
These identify relevant rendering paths, but do not prove which message or
cache owns the missing redraw.

Recovery without changing icon resources argues against permanently missing
archive entries as a sufficient explanation. The simultaneous Dock loss means
this should not be treated solely as a folder layout issue. It remains
necessary to distinguish an empty cached snapshot, stale frame ownership,
asynchronous image publication and missing widget invalidation.

## Offline native review

The retained 8593 fixture's folder composite uses a `SnapshotController` and a
`_RenderSnapshotWidget`. Its snapshot paint branch captures when the cached
image is null, then reuses the nonnull result. An allocated image with blank
pixels could consequently survive an ordinary repaint. A controller-state
change clears that image and calls `markNeedsPaint`; setting an unchanged
`allowSnapshotting` value returns without notifying.

MAML icon snapshot capture is a separate asynchronous path. A capture-request
log does not prove successful completion or a nonblank cache insertion.
Existing module bridges do not directly intercept these capture futures.
The retained evidence does not prove a dropped module callback or identify
which cache held the blank result.

The private `native-review.md` records fixture hashes, inspected function
relationships, log line references and limits. Fixture-relative addresses in
that research are not production matching rules or proposed hardcoded hooks.

## Performance and implementation boundary

The Java system-server refresh runtime already merges requests through its
owned worker and invokes authenticated launcher callbacks. Repeated package
refreshes, periodic global invalidation, synthetic swipes, or activity
pause/resume tricks would add work or visible behavior without proving the
underlying render-cache defect.

A suitable repair should follow actual image/snapshot readiness, coalesce
affected visible owners, and stop when that theme generation is replaced.
It must preserve Flutter UI-thread ownership and the API 102 retirement
contract. The current Java bridge does not expose an authenticated
closed-folder preview invalidation method; adding an Android `View.invalidate`
call would not establish a Dart widget rebuild or a fresh MAML snapshot.

The performance/maintenance decision for this report is to defer a new native
rendering workaround. A reliable one-shot operation would require new
controller/receiver resolution, live isolate and mutator-thread guarantees,
rooting, and teardown handling across engine replacement. That is a
substantial native implementation and regression-validation task, despite the
small-looking trigger; its runtime cost has not been measured.

No production workaround is implemented by this investigation. A future
candidate needs before/after visual reproduction, a bounded invalidation
count, existing-fixture rejection checks if a native role is added, and
same-process/hot-reload validation. The new user screenshot keeps this
acceptance item open.
