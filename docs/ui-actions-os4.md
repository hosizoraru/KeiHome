# OS4 action and icon conventions

The UI uses short visible action captions with full accessibility descriptions.
EN, Chinese and Japanese captions share the same operation distinctions.

| Operation | Glyph | Meaning |
| --- | --- | --- |
| Update | MIUIX Glass Update | Replace an existing imported/generated pack |
| Apply | MIUIX Glass Ok | Apply a prepared pack |
| Download | MIUIX Glass Download | Retrieve source resources |
| Import | Lucide File Input | Select/import a local archive or APK |
| Generate | Lucide Package Plus | Produce a new local pack |
| Export | Lucide File Output | Write logs to a user-selected file |
| Clear | MIUIX Glass Delete | Clear the selected cache |
| Search / match | MIUIX Glass Search / Link | Search artwork / associate applications |
| Refresh | MIUIX Glass Refresh | Reread current state or reload a list |

`PageActionIcons.kt` centralizes actions and bottom navigation semantics.
The five tab symbols represent activity/status, applications, icon shapes,
desktop layout and settings. Both selectable navigation implementations use
these meanings while retaining their own geometry, optics and interactions.
Existing suitable MIUIX icons remain the first choice. The supplemental
`com.composables:icons-lucide-android:2.2.1` resource dependency is the same
coordinate used by KeiOS; no application implementation was copied.

`PageActionFab` measures its content instead of imposing the former 164dp
minimum width. Its caption stays on one line within available width; its
minimum touch height is 56dp. Busy actions retain their accessible description
and measured-height callback and cannot execute. Destructive/large-download
confirmation and existing import/apply readiness checks are preserved.

`Os4DisclosureHeader` uses MIUIX's public OS4 BasicComponent and Glass
ChevronForward, with a shared trailing position and coordinated spring
rotation/expansion. The entire header performs a single disclosure action,
with an accessible expanded/collapsed state. Status, Monet editor and pack
guidance use this header. Navigation is separate from disclosure: the Monet
status shortcut remains available as a centered action.

`CenteredLinkAction` centers its bounded icon/text group in the available
button width, permits wrapping at large text sizes, and exposes a single
full accessibility label. Standalone theme/view/navigation buttons and
scope action groups use centered placement. Ordinary preference rows retain
their normal leading title and trailing navigation chevron.

The resource dependency's MIT notice, Lucide ISC notice and Feather-derived
MIT notice are bundled and reachable from third-party notices. Release
resource shrinking is enabled; only referenced glyph resources should remain
in the shipping APK.

Validation evidence is retained under
`.artifacts/research/ui-actions-os4-20261111/`. The focused device tests cover
compact width, large-font/narrow-width bounds, busy click suppression,
disclosure alignment/state and centered navigation/accessibility. UI-only
deployment uses normal API 102 reload without native reload or scope restart.

For repeated HyperOS QA, retain `os.kei.hyper.home.test` and update it with
`adb install -r`. If HyperOS presents an associated-start confirmation for the
test/application pair, choose its persistent allow option. Uninstalling the
test package can discard that authorization. The foreground host rule handles
ActivityScenario setup; it does not change product launch permissions.
