# MIUIX 5ff41ba9 Snapshot

KeiHome upgraded from `0.9.5-670cf597-SNAPSHOT` to
`0.9.5-5ff41ba9-SNAPSHOT`. The published artifacts correspond to
`5ff41ba90d963e48c3db1f3b28eb8dbebc9391f9`. The migration reviewed the
titles, messages and source of the
[21 upstream commits](https://github.com/compose-miuix-ui/miuix/compare/670cf5975c157b64d625d94deca7e7c21bd4a7b6...5ff41ba90d963e48c3db1f3b28eb8dbebc9391f9).
The [snapshot publish step](https://github.com/compose-miuix-ui/miuix/actions/runs/44571884911)
succeeded. The consuming project resolved and compiled the Android artifacts.

| Upstream change | KeiHome impact |
| --- | --- |
| `8410305`, `322500d` | Glass bottom navigation inherits the badge clipping fix and owns horizontal navigation-bar and cutout insets. Remove the outer horizontal safe-area workaround. Keep the app's symmetric margins and width cap. |
| `9155cd2` | The scrollbar uses a drawing and pointer-input node with RTL support. Restore logical-end placement and pass the page's existing app-bar behavior from the app browser, custom and preserved icons, navigation app selection and pack preview. Touch scrolling and pull-to-refresh keep their existing owners. |
| `cb64c8d` | Use the progress lambda and native accessibility range in the icon-pack modal. Stage, measured percent, processed count and dialog actions remain app state. |
| `2fa9b2c`, `5ff41ba` | Glass top bars benefit from title alignment, obstruction support and system-bar-aware progressive blur. Keep the app's page and overlay layout. The standalone `SmallTopAppBar` already regained its action slot in `af988eb`, before this range. |
| `9dc0969`, `07a1d6b`, `4dad195`, `4e67e39`, `4341c5c` | Existing callers inherit progressive blur and noise pass reductions, seam fixes and idle button backdrop capture avoidance. |
| `58b0a3d`, `47d75e6` | Existing Glass tabs inherit material transitions. The icons example gates material by content overlap. The search overlay gains scroll-dependent Glass, while its private query and built-in action labels still differ from the app's persistent, localized inline search. |
| `f897e73`, `c1f3c1f`, `1a05471`, `1926883` | Desktop/web wheel settling, desktop window callback and Linux frame changes require no Android app migration. |
| `20da9cf`, `d27cbb0`, `2140f7f`, `08d52d4` | Example controls, licensing and build/publishing maintenance. In particular, the package workflow now prunes older snapshots, so publication history alone cannot prove that an old artifact still resolves. |

The range adds no new standalone Glass component. KeiHome already uses native
Glass tabs. The leaf backdrop and measurements in `Os4ViewTabs` remain relevant to
inline sampling and large text. The search overlay remains a candidate for a
dedicated search destination. It cannot currently replace the shared editable
search field while preserving its query, clearing, focus and localization behavior.

## Workaround review

| App site | Migration | Check |
| --- | --- | --- |
| [`Os4NavigationBar`](../app/src/main/kotlin/os/kei/hyper/home/ui/components/Os4NavigationBar.kt) | Remove the app's horizontal `safeDrawing` wrapper from the native bar. Keep the centered 560dp cap and 16dp side margins, and keep the custom Liquid bar's separate inset handling. | Exercise both saved bar styles, including a drag with a badge, and verify the selected page and semantics after release. A physical landscape cutout remains open. |
| [`Os4LazyColumn`](../app/src/main/kotlin/os/kei/hyper/home/ui/components/Os4LazyColumn.kt) | Remove manual RTL switching. Wire the same `ScrollBehavior` to the bar and active list scrollbar, passing the list's top and bottom padding to the track. | Reveal the fading thumb with a scroll first. Check that dragging it updates the list and app-bar offset and that trailing row actions still respond. Verify touch scroll and refresh separately. |
| [`IconPackProgressHost`](../app/src/main/kotlin/os/kei/hyper/home/ui/main/IconPackProgressHost.kt) | Remove the duplicate semantics and use the upstream `LinearProgressIndicator` progress lambda. Keep measured progress as the range value, with the stage, displayed percentage and count in the app UI. | Assert the native range and the visible count for measured work. Check the waiting state and its background action separately. |
| `GlassTopAppBar` and shared title layout | Allow the title group to align to the start when side controls or a rail obstruct centering. Keep both strings together, the app's actions, and blur-mode selection. | Check title/subtitle bounds, controls and grouping with long text and RTL. Preserve the distinction between a desired behavior change and a weakened test. |

The upstream icon example shows how to let Glass tabs pick up material as
content scrolls under them. The search demo shows the same effect for search.
Use the examples to check overlap and motion. Retain the app's inline search
while its query and focus lifecycle differ from the overlay.
[Icon example](https://github.com/compose-miuix-ui/miuix/blob/5ff41ba90d963e48c3db1f3b28eb8dbebc9391f9/example/shared/src/commonMain/kotlin/IconPage.kt)
and [search demo](https://github.com/compose-miuix-ui/miuix/blob/5ff41ba90d963e48c3db1f3b28eb8dbebc9391f9/docs/demo/src/commonMain/kotlin/GlassSearchOverlayDemo.kt).
For the general migration procedure, see the [OS4 playbook](miuix-os4-playbook.md).

## Verification

- The Debug dependency graph selected one MIUIX revision. Ten Android AARs
  resolved at the published snapshot timestamp `20261119.170416-1`. The app
  and instrumentation APKs compiled. JVM tests passed with 2,097 successes
  and eight conditional skips.
- Phone AVD `emulator-5580` ran build `113000634` on
  `OS4.0.31.0.XFRCNXM`, Home `801030308`. Native payload hashes matched the
  prior install. Critical Java reload and process identity checks passed.
  The whole observed population contained 11 successes and one failure.
  Android reclaimed `com.miui.securitycenter:cache` during its reload.
- All 23 focused device tests passed. They covered compact and synthetic wide
  layouts, both bottom-bar styles and themes, large text and RTL,
  progressive/Gaussian/off blur, navigation gestures, pull-to-refresh,
  scrollbar and title synchronization, and the modal's accessible progress
  range. There was no separate live Pad or landscape cutout run.
- Initial `lintDebug` failed on the existing API 37 `getOverlayView` probe.
  A separate [SystemUI material fix](gesture-navigation-backdrop.md#soft-glass-gesture-handle-november-19)
  scoped the probe to the OEM host and preserved the blur fallback.
  Debug and Release Lint then passed. Signed Release b0636 was installed on
  the Xiaomi 17 Pro, with nine observed successful reloads and unchanged
  critical PIDs. The OEM handle material attached and stayed aligned during
  the recorded short pull and swipe home. This physical run exercised the
  gesture handle only. The Phone/Pad Glass checks above retain their own scope.

Dependency receipts, snapshots, build logs and device test results are in
`.artifacts/research/miuix-5ff41ba9-20261120/`. The later signed build and its
physical verification are in `.artifacts/releases/113000636/`.
