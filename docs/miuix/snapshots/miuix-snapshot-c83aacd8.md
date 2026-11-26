# MIUIX c83aacd8 Snapshot

KeiHome moves from `0.9.5-f3829d3d-SNAPSHOT` to
`0.9.5-c83aacd8-SNAPSHOT`. The [18-commit range][comparison] ends at the
successful [snapshot publication][publish]. Gradle selected timestamp
`20261121.152934-1` across the Android MIUIX modules. AndroidX Compose
remains on `1.13.0-rc01`. Upstream's AGP, Hot Reload, and Spotless changes
apply to its own build and examples.

| Upstream commits | KeiHome integration |
| --- | --- |
| `daa2d15`, `3f56c65`, `cadfb0c` | Deferred reads, stable callbacks, fewer allocations, and faster rounding benefit the existing Glass, picker, popup, and navigation components. |
| `0320daa` | `GlassTabRow` retains its glass node through fade-out, and the top bar keeps its underlay available during that transition. Example list tabs use a transparent resting surface. `Os4ViewTabs` still supplies its own backdrop, sizes labels, reserves shadow room, and handles horizontal overflow. |
| `715ac29`, `234db0f`, `d21e82f` | Expandable Glass top bars drive the gradient and progressive blur by collapse progress. Fixed bars regain the 100 ms fade. The blur covers the measured bar and follows `gradientOverhang`. `KeiHomeTopAppBar` already supplies the native scroll behavior and selects the user's blur mode. |
| `de9da1d`, `acdb410`, `ee0281d`, `da59017`, `c83aacd` | Upstream preserves the noise effect and scrim coverage, tightens blur padding and shader work, and skips the zero-blur layer. These changes enter through the existing backdrop pipeline. |
| `6d08f0b`, `3526cfe`, `06e4537` | The Example separates theme settings, uses JankStats for its FPS monitor, splits lazy list rows, and corrects the icon search scroll target. These changes have no new Glass component to adopt. |

KeiHome keeps its stable viewport backdrop in `NavigationBackdropContent`.
That fix controls what the gesture settings page records during scrolling.
Upstream's changes control how Glass samples and draws that recording.
KeiHome also keeps its inset, shadow, and label sizing rules because those
belong to its app layout.

## Verification

Debug assembly, Android test compilation, signed Release assembly, and
Release Lint completed. JVM tests passed 2,107 cases with 13 skips.
Lint reported zero errors and 588 warnings.

On Xiaomi 17 Pro with HyperOS `OS4.0.8.0.XBLCNXM` and Home `801030308`,
the KeiOS-signed `1.13.0+g7134887.b0696.override.dirty` candidate
installed in place over `b0695`. It was built with the snapshot pin in
the working tree, recorded as `tested-catalog.diff` in the run artifacts.
The installed APK hash matched the candidate, and all three bundled native
libraries matched the baseline. The framework logged 10 Java reload
successes for running targets including Android System, SystemUI, and Themes.
KeiHome's desktop status confirmed a current native reply. The launcher PID
stayed stable.

Compared recordings of repeated scroll and overscroll on the immersive
gesture settings page. The status card stayed visually stable through
movement and rebound. Tapping and dragging the app category tabs moved the
selection and restored the original category. App Settings rendered in
both portrait and landscape. Restored the original rotation and foreground
page, and verified the same theme hash, palette, and system night mode.
Physical Pad and Android instrumentation execution remain unverified.

The recordings, dependency resolution, APK, mapping, and install receipts
are under `.artifacts/research/miuix-c83aacd8-20261122/`.

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/f3829d3d162eb267a792d43b3c8541e64effc28d...c83aacd8ee737c44e0f98747b68091317d747eec
[publish]: https://github.com/compose-miuix-ui/miuix/actions/runs/44919198318/job/136891309048
