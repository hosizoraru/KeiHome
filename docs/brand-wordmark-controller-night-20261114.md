# Small brand lettering and translucent controller artwork

The November 14 physical-device screenshots reopen visual acceptance for
`com.yek.android.kfc.activitys` and `com.xiaomi.gamecenter`. Earlier icon checks
do not establish that either of these examples was correct.

## KCOFFEE contrast

The default KFC source has separate black COFFEE lettering beneath a red K and
a white KFC badge. The previous wordmark detector required larger anchor
components, so this smaller row stayed black against the generated dark plate.

Keep the existing large-letter proof as the first pass. A second pass accepts
smaller lettering only when at least four sparse, neutral components form an
aligned row of sufficient width. Both passes retain the existing limits on
component count, row geometry, neighboring artwork and dark underlay. The
second pass cannot replace a component assignment from the first pass.

This is a shared pixel rule, with no package-name recolor. It makes the exposed
COFFEE lettering light while preserving the red K, the portrait and black
lettering inside the white badge. Pixel alpha is unchanged. An isolated pair or
three small shapes cannot establish the new small-letter proof.

## Game Center lower edge

The HyperOS4 flat source is 235 by 235. Its separated background and foreground
are 351 by 351. The exact integer centered crop is
`Rect(58, 58, 293, 293)`. The 20,022 source pixels covered by the foreground
match the flat source with maximum channel delta 1 in the independent host
check. The lower controller silhouette includes deliberately translucent white
artwork; it should not be reconstructed as an opaque, segmented shape.

The existing partial-foreground proof accepted equal-sized inputs only.
It now also accepts an even, centered native crop, using the same geometry
already supported by whole-image equivalence. Every covered source pixel must
still match all ARGB channels within tolerance 2. There is no alignment search,
rescaling to manufacture a match, package exception or reduced proof threshold.

The replacement uses cropped authored layers at matching sampling geometry.
Only proven foreground coverage is replaced; output alpha and unrelated flat
artwork remain intact. Writes remain staged until the final cancellation
fence. Odd offsets, mismatched layer grids and a single covered mismatch reject
the repair without publishing partial results.

A background mismatch outside foreground coverage disqualifies whole-image
equivalence but can still permit a partial repair. Regression tests separately
verify this preservation case and a mismatching alpha beneath translucent
foreground that must reject both paths.

The initial foreground-only patch passed its coverage tests but failed visual
inspection: it still left colored pieces of the flat background in the lower
controller gap. The full native source also proves the background, but the
background classifier rejected its wide multicolor gradient. This is a second
cause, separate from the source-grid mismatch. Final acceptance must inspect
uncovered background as well as foreground coverage. The intermediate image and
raw test logs are retained rather than being represented as the finished fix.

Native background classification now also admits full-canvas, near-opaque
multihue gradients only when every raw neighboring RGB delta is at most 8 and
at least 99.5 percent of locally averaged curvature samples remain within 3.
The local average handles authored dithering and is used only for classification;
source pixels are never blurred. Existing low-range classification is unchanged.
Textured and small hard-edged artwork are negative regression cases.
With this classification the whole-image proof can compose the complete native
Game Center artwork. The final test checks every uncovered background pixel and
the previously colored lower central gap, in addition to the translucent edges.

## Cache and source receipts

Static renderer revision: `perfect-static-night-11`.
Equivalent-layer policy: `centered-native-pixel-proof-4`.
Existing generated ZIPs must be regenerated and applied to receive new pixels;
installing or hot reloading the module alone does not rewrite an applied pack.

Retained upstream archive:
`.artifacts/research/perfect-icons-20261108/source/repository-complete.zip`.
The KFC assets use the default `icons/com.yek.android.kfc.activitys/{0,1}.png`;
HyperOS4 has no override. Game Center uses
`themes/HyperOS4/icons/com.xiaomi.gamecenter{.png,/0.png,/1.png}`.
The evidence directory records exact asset SHA256 identities.

Evidence root:
`.artifacts/research/kfc-gamecenter-night-20261114/`.
Tests use actual retained assets, including explicit SHA256-named Calendar and
Game Center fixtures. Missing fixtures and conditional skips are not counted as
acceptance.

## Validation boundaries

The Phone AVD is the available device for this change. The physical target
`192.168.31.105:5325` did not answer the connection attempt, so current physical
desktop acceptance is pending. No physical-device settings were changed.
The source renderer images are evidence of production rendering on Android;
they are not represented as screenshots of the physical desktop.

Manual inspection of `kfc-wordmark-night.png` confirms readable light COFFEE
lettering with the red K and badge retained. Inspection of
`gamecenter-proof-night.png` confirms the lower blue residue is gone and the
controller retains its continuous translucent silhouette.

The final focused ledger has 54 distinct passing Android cases and no skips:
5 small-wordmark cases, 39 static-renderer cases and 10 foreground/gradient
cases. This combines the final affected-class rerun with the unchanged passing
cases from the preceding focused run. Earlier failures remain in the raw logs,
including stale revision/ramp expectations, PNG premultiplication expectations
and the incomplete foreground-only visual result. The final 10-case rerun
reports `OK (10 tests)`. See `final-case-ledger.json` and
`final-focused-results.json` for the per-case evidence.

The separate square first heads-up icon during KeiHome's own hot reload remains
open. These fixes do not close that notification lifecycle issue.
