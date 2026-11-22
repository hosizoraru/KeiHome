# MIUIX 2b226150 Snapshot

KeiHome moves from `0.9.5-c83aacd8-SNAPSHOT` to
`0.9.5-2b226150-SNAPSHOT`. The [six-commit range][comparison] ends at
`2b22615097ca404f6a625ba50d4b26fd42fe86e2`, the `main` head verified
against the successful [snapshot publication][publish]. The candidate
uses base version `0.9.5` with its source hash in the Snapshot suffix.
AndroidX Compose stays at `1.13.0-rc01`.

| Upstream changes | KeiHome integration |
| --- | --- |
| `6904e7b` omits the sheet header when the title and both header actions are null. The drag handle is independent of the header, and an empty title still reserves header space. | Current `Os4DetailsSheet` callers supply titles. Keep real titles for the visible header and accessibility pane. |
| `accff62` clips the Glass bottom sheet's edge stroke at the lower boundary, leaving the top and side strokes. | The shared `Os4DetailsSheet` inherits the change for status details, icon-pack import help, and Monet. Its backdrop, navigation bar padding, scroll handling, and dismissal callbacks still belong to the app. |
| `8f62121` adds `WindowComponentFrame` for custom window hosts, constrains window popups to their viewport, and guards tooltip caret drawing in very narrow layouts. `9ca7534` preserves the top corners when the first cascading item expands. | KeiHome uses overlay-based Glass sheets and dialogs, with its own anchored Glass dropdown wrappers. It has no `Window*` popup or cascading-menu caller that needs the new frame API. Ordinary Android windows use the default frame. |
| `8fbd4a1` uses the default card radius in the pull-to-refresh example. `2b22615` reduces duplicate desktop test work. | The example adjustment and desktop tests do not require Android app changes. |

This range introduces no new standalone Glass control. Existing wrappers
already forward the sheet title, backdrop, and dismissal callback. Keep
`NavigationBackdropContent`'s stable viewport recording and the shared
sheet's `onDismissFinished` handoff. Those own app recording and action
ordering, while this update changes header measurement and edge drawing.

## Verification

Debug compile and runtime classpaths resolved the same ten Android MIUIX
modules. Runtime dependency insight identified the Maven publication
`20261122.053756-1`. Debug assembly and Android test compilation passed.
JVM tests returned 2,114 passes and 8 skips. Release Lint reported zero
errors. Android device execution and visual comparison of the sheet edge
remain unverified for this Snapshot.

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/c83aacd8ee737c44e0f98747b68091317d747eec...2b22615097ca404f6a625ba50d4b26fd42fe86e2
[publish]: https://github.com/compose-miuix-ui/miuix/actions/runs/44989895006/job/137108431242
