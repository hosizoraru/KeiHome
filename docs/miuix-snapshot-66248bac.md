# MIUIX 66248bac Snapshot

KeiHome moved from `0.9.5-5ff41ba9-SNAPSHOT` to
`0.9.5-66248bac-SNAPSHOT`. The
[eight upstream commits](https://github.com/compose-miuix-ui/miuix/compare/5ff41ba90d963e48c3db1f3b28eb8dbebc9391f9...66248bacc7be29ae6bdc0957de9458b42ac67732)
span `5ff41ba90d963e48c3db1f3b28eb8dbebc9391f9` through
`66248bacc7be29ae6bdc0957de9458b42ac67732`. The snapshot upload succeeded in
the [publish workflow](https://github.com/compose-miuix-ui/miuix/actions/runs/44683541438),
and Gradle resolved the published Android artifacts.

| Upstream commits | KeiHome impact |
| --- | --- |
| `bb43a5f`, `ef53583` | MIUIX builds with Compose Multiplatform `1.13.0-rc01`. KeiHome already pins AndroidX Compose at `1.13.0-rc01`. The deprecated value-based `GlassTextField` calls in cache budget, log export and icon-pack network settings now use `TextFieldState`, with the same numeric limits, validation and persistence. The Monet editor already used that API. |
| `17a892e` | `GlassTopAppBar` now uses its mask progress for both progressive band activation and opacity. KeiHome's shared toolbar inherits the change through its existing scroll behavior and backdrop. |
| `8c3401c` | The native scrollbar touch strip shrinks from 48dp to 24dp. `Os4LazyColumn` already delegates hit testing and RTL placement to MIUIX, so the updated width applies without another local offset. |
| `93c8e3a` | The icon, license and search examples use `LazyLayoutCacheWindow`. The sample change provides a configuration to evaluate with app-specific measurements before changing shared list prefetch. |
| `ffbc71e`, `3ad3935`, `66248ba` | Upstream desktop dependency cleanup, plugin repository ordering and Gradle 9.9.2. KeiHome's Android toolchain can consume the snapshot with its existing wrapper. |

## App-owned behavior

The range adds no standalone Glass component. The
[Glass example](https://github.com/compose-miuix-ui/miuix/blob/66248bacc7be29ae6bdc0957de9458b42ac67732/example/shared/src/commonMain/kotlin/GlassPage.kt)
uses the state-based input API in its dialog. KeiHome keeps its editable search
field because it owns the caller's query, focus, clearing and localized
accessibility labels. The upstream search overlay owns and resets its own query
on expansion changes. KeiHome continues to pass its adaptive title layout,
toolbar actions and blur choice to the upstream top bar.

MIUIX now uses `DialogProperties.scrimAlpha` to disable the Android platform
dialog scrim. KeiHome's shared Glass dialog already delegates platform window
effects to MIUIX and supplies the app content and actions. The previous
[Glass migration](miuix-snapshot-5ff41ba9.md) already removed the overlapping
RTL scrollbar and horizontal bottom-bar inset handling.

## Verification

- Debug, Android test APK and Release builds succeeded. Debug and Release Lint
  reported zero errors. JVM tests recorded 2,097 passes and eight skips.
- The KeiOS-signed Release candidate b0641 (`113000641`) was installed in
  place on Xiaomi 17 Pro, HyperOS `OS4.0.8.0.XBLCNXM`, Home `801030308`. The
  installed APK hash matched. All nine observed Java hot reloads succeeded.
  The three native libraries matched b0640 byte for byte. The critical
  process identities, icon theme hash, night mode and palette were preserved.
- Physical checks covered cache budget prefill, 49 MB rejection, 490 MB
  acceptance and cancellation with the original 520 MB threshold; log range
  validation at 168 and 169 hours; and proxy port rejection at 65536, error
  clearing on edit and cancellation with the original connection settings.
- Screenshots during and after scrolling show the compact toolbar title and
  its actions on the MTZ/ZIP page. A scrollbar drag advanced through the
  197-item applicable-app list while retaining its toolbar and selection
  counts. Both original selected apps were still present after the drag.

The device pass covered the physical phone in portrait. Wide screens, RTL
touch interaction and successful proxy submission were outside this pass.
Build and device records are in
`.artifacts/research/miuix-66248bac-20261120/`; signed APK and matching R8 mapping
are in `.artifacts/releases/113000641/`.
