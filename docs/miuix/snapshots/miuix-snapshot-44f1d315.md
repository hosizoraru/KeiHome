# MIUIX 44f1d315 Snapshot

KeiHome moves from `0.9.5-2b226150-SNAPSHOT`
(`2b22615097ca404f6a625ba50d4b26fd42fe86e2`) to
`0.9.5-44f1d315-SNAPSHOT` (`44f1d31580bd127360d79bdb0bb869bd6c5af025`).
The [three-commit range][comparison] ends at the successful
[snapshot publication][publish]. AndroidX Compose stays at `1.13.0-rc01`.

| Upstream changes | KeiHome integration |
| --- | --- |
| `9128921` adds `GlassCard` for grouping content inside a Glass sheet or dialog and reduces the default Glass sheet horizontal inset from 24dp to 12dp. | The shared `Os4DetailsSheet` explicitly keeps the 24dp inset for existing content. Icon-pack help follows the upstream example with a 12dp sheet inset and 12dp card content padding. The notes scroll inside the card and the action stays below it. |
| `bf61ef4` gives `GlassDialog` the launcher motion preset. It slides from the bottom on compact screens and scales in when centered. Dialogs, sheets and popups release touch interception before the exit animation settles. | Existing `Os4Dialog` callers inherit the motion. Keep the shared backdrop and the app's `onDismissFinished` sequencing for actions that must wait until the overlay is gone. Earlier touch release and visual dismissal are separate events. |
| `44f1d31` adds a Maven Central mirror in upstream CI and adjusts repository setup. | KeiHome keeps its existing Maven repositories and build toolchain. |

`GlassCard` draws a translucent fill over its parent surface. The existing
sheet owns backdrop sampling, corner shape and fallback; the card adds
no backdrop recording. The upstream changes leave KeiHome's fixed
scroll-backdrop viewport and conditional overlay mounting in place.

## Verification

- Debug compile and Release runtime graphs resolve all MIUIX modules to
  `0.9.5-44f1d315-SNAPSHOT`, publication `20261122.090909-1`.
  The ten Android AAR hashes are recorded with the build evidence.
- Debug APK assembly and Android test compilation pass. JVM tests report
  2,121 passed and eight skipped. Release Lint reports zero errors.
- Visual acceptance of the updated dialog motion and Glass help card on
  Phone and Pad remains pending.

Evidence is under `.artifacts/research/miuix-44f1d315-20261122/`.

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/2b22615097ca404f6a625ba50d4b26fd42fe86e2...44f1d31580bd127360d79bdb0bb869bd6c5af025
[publish]: https://github.com/compose-miuix-ui/miuix/actions/runs/45005476831
