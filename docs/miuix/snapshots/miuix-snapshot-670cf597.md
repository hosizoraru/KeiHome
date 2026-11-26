# MIUIX 670cf597 Snapshot

KeiHome moved from `0.9.5-3177a684-SNAPSHOT` to
`0.9.5-670cf597-SNAPSHOT`. The
[13-commit range](https://github.com/compose-miuix-ui/miuix/compare/3177a684622a774276c49f52489a43d0d0ed123d...670cf5975c157b64d625d94deca7e7c21bd4a7b6)
was reviewed against the resolved Android artifacts. The
[package publication](https://github.com/compose-miuix-ui/miuix/actions/runs/44343476082)
succeeded, and the physical sample reports `1.1.0.670cf597` (1334).

| Upstream commits | KeiHome use |
| --- | --- |
| `e875daf`, `3a435ef` | Existing Glass controls inherit deferred feedback, dropdown bounds reads, navigation layer updates and size-dependent press motion. Keep the app's mixed enabled/disabled dropdown items and popup navigation ownership. |
| `60c6204`, `9413f69` | Existing Glass rail inherits the secondary selection morph. The upstream padding change is for the sample's Liquid navigation bar. KeiHome retains its window-inset calculation. |
| `a1998b0`, `416709f` | `GlassSearchOverlay` is now a library component with an opaque fallback. Its internal query resets across expansion and it owns English action descriptions. Keep the shared inline search where queries, localized actions and list state survive navigation. |
| `6bb8e14`, `4931815` | Shared progressive blur inherits the cached shader paths, downsampling and specialization through the existing API. |
| `af988eb` | Restores actions in `SmallTopAppBar`. KeiHome's Glass title groups use `GlassTopAppBar`, so the fix does not replace their slot sizing or backdrop policy. |
| `27cb8bc` | Use native dialog and sheet material and stroke defaults so they inherit the upstream dynamic tint. Keep the existing blur-off fallback, accessible dismissal and adaptive presentation. |
| `d0f6948` | The Icon example groups the catalogue and opens a short Glass dialog for comparison. KeiHome uses its shared Glass action dialog for original/saved icon comparison and waits for exit before edit or removal confirmation. The device sample's Glass Components dialog also informed the custom log-hours `GlassTextField` migration. |
| `a915666`, `670cf597` | Documentation and example build changes. |

The About page follows the sample's collapsed toolbar pattern first introduced
in `7188729`. Its hero remains the visible page heading.

## Verification

Debug and signed Release builds and lint completed. The JVM suite reported
2,092 passes and eight conditional skips. On the Xiaomi 17 Pro with Home 0308,
b0755 passed 40 focused device checks, including compact and wide navigation
fixtures. Foreground checks also exercised comparison, edit, cancellation of
removal, and valid and invalid log-hours input. After the About change, b0756
passed 12 targeted checks and the About toolbar was checked while scrolling.
Physical Pad and installed Release verification remain open.

The phone has Debug b0756. All three native libraries match the previous
installed build byte for byte. Home, SystemUI and `system_server` retained
their process identities, and the active icon theme, palette, night mode and
saved custom icon preferences matched the baseline.

Resolved AAR hashes, upstream commit messages, sample captures and local
test receipts are in `.artifacts/research/miuix-670cf597-20261119/`.
