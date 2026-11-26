# MIUIX snapshot interoperability and Glass migration

Reviewed 2026-11-17 through 2026-11-18.

## Dependency selection

KeiHome uses `0.9.4-76504ab0-SNAPSHOT` for all eight declared MIUIX modules.
The source commit is `76504ab028bdff42dff346577b4e3780069a1969`.
The version remains pinned rather than using a changing snapshot selector.

At review time, upstream HEAD was
`6d2554a850b8960e2616c968263ff64beb5ffb03`, and some modules advertised
`0.9.5-6d2554a8-SNAPSHOT`. Its GitHub Packages publication run
`44105631618` failed. The new version was present for blur and glass, but
absent for UI, icons, preference, nav, glass icons and material. Attempting
to resolve the whole version confirmed missing artifacts.

The selected version is the newest common snapshot in the publication
metadata for all declared Android modules and the root UI artifact, ordered
by source ancestry. The full release runtime dependency graph resolves at
this version. We do not mix a partly published new release with older
transitive UI/material classes, introduce a local-only repository, or change
repository authentication to conceal the failure.

Publication should be checked again before the next dependency update;
this is a dated availability result, not a permanent reason to reject 0.9.5.

## Upstream review

The review covered the titles and full messages of 29 commits after the
previous `d8efe187` baseline, the relevant source changes, and both shared
app and documentation examples. The selected snapshot includes the first
21 of those commits.

Important changes available in the selected version:

| Upstream change | KeiHome integration |
| --- | --- |
| Native `GlassFloatingToolbar` and `GlassFloatingActionButton` | Shared floating actions and selection tools use the native toolbar surface; primary actions use native FAB fill/material defaults. |
| Navigation badge support and spacing fixes | Badges belong to their native navigation item, including non-middle destinations and RTL. |
| Segmented-tab dragging | Existing `Os4ViewTabs` receives the native gesture implementation; touch release/cancellation is tested in both directions. |
| Small-title blur transition | Existing Glass top bars and small titles use the updated library behavior. |
| Reduced-resolution blur rendering, sampler/render-effect reuse and texture-blur refinements | Existing page recording and backdrop consumers receive the library changes without adding another recording layer. |
| Initial Glass navigation rail | Reviewed, but not inserted into the phone's existing bottom navigation. Its overlay, title occlusion and content-offset contract requires a deliberate wide-layout design. |

Later commits at HEAD add secondary rail destinations, fix rail alignment,
clean up/rename several glass icons, update profiles, and fix an example's
adaptive top-bar background. These are reviewed source changes, but are not
claimed as shipped fixes in the selected snapshot. In particular, an example
fix is not evidence that a library behavior changed.

Source references:

- [Selected source](https://github.com/compose-miuix-ui/miuix/tree/76504ab028bdff42dff346577b4e3780069a1969)
- [Native toolbar](https://github.com/compose-miuix-ui/miuix/blob/76504ab028bdff42dff346577b4e3780069a1969/miuix-glass/src/commonMain/kotlin/top/yukonga/miuix/kmp/glass/GlassFloatingToolbar.kt)
- [Native FAB and defaults](https://github.com/compose-miuix-ui/miuix/blob/76504ab028bdff42dff346577b4e3780069a1969/miuix-glass/src/commonMain/kotlin/top/yukonga/miuix/kmp/glass/GlassFloatingActionButton.kt)
- [Shared Glass examples](https://github.com/compose-miuix-ui/miuix/blob/76504ab028bdff42dff346577b4e3780069a1969/example/shared/src/commonMain/kotlin/GlassPage.kt)
- [Publication workflow](https://github.com/compose-miuix-ui/miuix/actions/runs/44105631618)

## Application changes

`Os4FloatingToolbar` owns native floating-surface material selection and uses
the owning page's backdrop and explicit app dark mode. The scaffold owns
navigation, safe-area and keyboard spacing, so the native container receives
zero extra outside padding. The container stays outside the page recording.

`PageActionFab` retains visible text, its accessibility description, Button
role, disabled/busy behavior, progress indicator, click cancellation and
height reporting. The content owns physical clicks because the native
toolbar is a container that consumes background taps. The outer surface
publishes one accessibility node containing its description, Button role,
guarded click action and busy-state description; native descendants do not
split the action from its label. A native circular FAB
is not stretched into an undocumented extended-button shape.

Apple batch selection and Perfect resource selection share the same native
floating container. Their individual actions retain labels, 48 dp targets,
and disabled behavior. Existing native dialogs, sheets, dropdowns, top bars,
and inline segmented controls continue to use their established wrappers.

The Glass navigation bar now populates `GlassNavigationItem.badge`. The
old overlay called only destination 2 and positioned it with a fixed
offset; the native slot follows each item's actual icon/layout.

Plain list cards, text-field internals, and disabled icon controls are not
turned into glass surfaces merely because a new container exists. This
preserves visual hierarchy and avoids recording or sampling a surface inside
its own backdrop.

## Verification

- All eight MIUIX dependency declarations and transitive MIUIX runtime
  dependencies resolve at the selected snapshot.
- JVM suite: 2,077 cases, 2,069 passed, eight conditional skips, no failures/errors.
- Focused UI regression suite: 37 tests passed on HyperOS AVD launcher 9259.
  Coverage includes top bars, dialogs, popups/dropdowns, segmented controls,
  Apple tools, pull-to-refresh, and the HyperOS/ColorOS platform tabs.
- New interaction tests cover native navigation badges on two separate
  destinations, dynamic removal and RTL; primary-action touch cancellation,
  enabled/busy/disabled states, Button semantics, both themes, glass/opaque
  surfaces and 160% fonts; and one selection commit on drag release.
- After unifying the Button role, label and accessibility action, the
  floating-action/navigation cases are rerun against the final source,
  including semantic activation and rejection while disabled/busy. Release build, lint, installed
  identity, native-library equality and final device preservation receipts
  are recorded in the acceptance evidence.

Evidence directory:
`.artifacts/research/miuix-snapshot-20261117/`.
It retains publication metadata, commit messages, dependency-resolution output,
test logs, screenshots, installation receipts and source/build identities.
