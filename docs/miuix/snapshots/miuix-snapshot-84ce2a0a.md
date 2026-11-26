# MIUIX 84ce2a0a Snapshot

KeiHome upgrades from `0.9.5-44f1d315-SNAPSHOT` to
`0.9.5-84ce2a0a-SNAPSHOT`. The [47-commit comparison][comparison]
ends at a [successful package publication][publish]. AndroidX Compose
and the corresponding JetBrains Compose UI artifacts resolve to
`1.13.0-rc01`. The Debug and Release graphs use the same MIUIX snapshot.

| Upstream change | KeiHome integration |
| --- | --- |
| The dialog fixes preserve spring overshoot, synchronize backdrop sampling during motion, and restore offscreen compositing for foreground drawing. | Existing dialogs inherit these fixes. The shared backdrop and `onDismissFinished` sequencing still own app-level capture and follow-up actions. |
| Switch drag arbitration, shared indications, cached popup anchors and progressive blur reduce work in common controls. | Theme preferences use the shared switch and dropdown. The app still owns its backdrop viewport, global scale, and scroll-edge haptic preference. |
| AboutPage uses `GlassCard` and defers its scroll-driven title alpha. | KeiHome uses Glass cards over its existing About background and passes animated title alpha through the shared top bar. The title is hidden at the top, appears as the hero moves away, and clears again when scrolling back. |
| The example separates light and dark selection from the palette. | Settings has a Theme settings route with two appearance previews, a system-follow switch, and an independent App palette. Existing display settings move to this route and remain searchable under their previous labels. |

The page writes through `AppPreferences` and uses the existing effective
light/dark value for the selection outline. Disabling system follow keeps the
appearance visible at that moment. The three App palette values reuse the
existing resolver. Interface scale, blur, rounded corners, scroll haptics, and
navigation style use their existing storage and defaults.

## Verification

- Debug, Android test and signed Release APKs assembled. JVM tests passed
  2,124 cases with eight skipped. Release Lint and the three-locale resource
  audit reported zero errors.
- On Xiaomi 17 Pro, the b0729 Release verified independent light/dark and
  Monet selection, system-follow restore, and the About title and cards on
  scroll. The b0730 Release refined the preview frame and was checked at
  375 dp portrait and 817 dp landscape widths. The installed b0730 APK hash
  matched the signed artifact, and API 102 reinstalled the system-server
  Java hooks without changing the core process PIDs.
- Fourteen device tests in an isolated app instance covered theme preference
  persistence, three-locale search labels, adaptive chrome and dropdowns.
  Physical Pad acceptance and a new native activation were outside this run.

Build and device evidence is in
`.artifacts/research/miuix-theme-settings-20261123/`. That directory includes
the dependency graphs, ten Android AAR hashes, and the upstream commit audit.

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/44f1d31580bd127360d79bdb0bb869bd6c5af025...84ce2a0a577ccc34803e39df2e570e1f1068f4f9
[publish]: https://github.com/compose-miuix-ui/miuix/actions/runs/45110025715
