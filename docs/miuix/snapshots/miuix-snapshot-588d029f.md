# MIUIX 588d029f snapshot

KeiHome now uses `0.9.5-588d029f-SNAPSHOT`, published from
`588d029f2b0747a0a8710db0858ed0957d78b122`. Gradle resolved the Android
compile and release runtime graphs to the new snapshot, including
`miuix-glass-android` build `20261126.033151-1`. [Publish run][publish]

| Upstream change | KeiHome integration |
| --- | --- |
| `6aa83241` disables progressive blur by default in `GlassBottomSheet`. | `Os4DetailsSheet` explicitly enables it and continues applying the header padding supplied by MIUIX to its content. |
| `d2e52284`, `c217b445` and `e5338080` clamp the top-bar collapse offset, smooth the progressive scrim and fix a seam in the non-progressive band. | The shared top bar and details sheet use the updated MIUIX drawing code through their existing backdrop and scroll connections. |
| `04e9b818` adds `GlassScrollableTabRow`. | KeiHome retains its measured tab widths, selected-tab auto-scroll and single text node per label. The new component uses content-sized pills and adds a duplicate text node to reserve the bold label width. |
| `588d029f` adds a scroll indicator for overflowing dropdowns. | `Os4DropdownPreference` already delegates popup rendering to MIUIX. |

The [comparison][comparison] also includes updated library baseline profiles,
sample layout and scroll changes, and an upstream build-tool update.

## Verification

- `testDebugUnitTest` ran 2,203 cases with 12 skipped and no failures.
  `assembleRelease`, `lintRelease` and `assembleDebugAndroidTest` succeeded.
  Lint reported 0 errors, 656 warnings and 4 hints.
- The KeiOS Release-signed build `116000783` installed over `116000779` on a
  Xiaomi `25098PN5AC` running `OS4.0.8.0.XBLCNXM` with launcher `801030308`.
  KeiHome opened, and the launcher, System UI and system server process IDs
  and active icon-pack checksum matched the pre-install values.
- In portrait, the ColorOS icon preview preserved the translucent top bar as
  it collapsed and expanded during scrolling. The wallpaper pairing sheet
  and icon guidance sheet opened and dismissed. The ColorOS page and guidance
  sheet also rendered in landscape on the same phone.
- The system dark-style dropdown showed all three options and Back dismissed
  it with the previous selection intact.
- The landscape phone did not reach the adaptive Pad height threshold.
  Device instrumentation and the scrollbar in an overflowing dropdown
  remain unverified. Device captures and build logs are in
  `.artifacts/miuix-588d029f`.

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/a09d4f3268ece3fc262d0bba433a3941a312bb9a...588d029f2b0747a0a8710db0858ed0957d78b122
[publish]: https://github.com/compose-miuix-ui/miuix/actions/runs/45748501062
