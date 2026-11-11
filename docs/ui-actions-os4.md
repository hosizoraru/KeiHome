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
the familiar HyperOS desktop outline and settings. Both selectable navigation implementations use
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
guidance use this header. Monet's expanded information area itself opens its
settings using MIUIX's native clickable component and a labelled accessibility
action. There is no separate details button. The header independently toggles
expansion; the collapsed card keeps only its state and timestamp pills.
The short appearance note has 16dp bottom padding.
Runtime and Monet summaries use wrapping state pills: system/launcher and
icons/palette are independent, with green for ready/on/read, grey for off or
unknown/not provided, amber for pending/stale, and red for failures. Each pill
retains its textual state so meaning does not depend on color. A failed read
clears the old ready/active presentation.

`CenteredLinkAction` centers its bounded icon/text group in the available
button width, permits wrapping at large text sizes, and exposes a single
full accessibility label. Standalone theme/view/navigation buttons and
scope action groups use centered placement. Ordinary preference rows retain
their normal leading title and trailing navigation chevron.

Perfect Icons timestamps live in metadata pills. Today's successful sync shows
time only; other dates in the current year omit the year; older dates include
it. Formatting follows the locale, time zone and system 12/24-hour preference.
Full dates remain available in file details and accessibility descriptions.
The previous successful time remains visible alongside a distinct failed,
interrupted or damaged-resource pill. A new successful sync clears the old
attempt failure. First use without local resources does not imply corruption.

Sync history is recorded before network work. Explicit cancellation records an
interruption; a later process recovers an unfinished attempt from its previous
owner, while a second controller in the same process leaves live work alone.
The local health probe checks the source pointer and ZIP directory metadata,
with a bounded cache keyed by file stamps and corruption markers. Payload
hash/CRC verification stays on actual read/sync paths. Opening a page does not
rehash the complete archive or fetch the source. Failed downloads preserve the
last usable snapshot. Generated/imported pack failures are separate from sync
history.

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
