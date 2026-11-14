# Dark brand lettering: November 14

Idlefish and Meituan retain dark brand lettering on a derived dark plate. Their
authored foregrounds are not strictly neutral silhouettes: Idlefish uses warm ink
and an underline touching the fish; Meituan includes chromatic antialias pixels.
The existing isolated-symbol proof also requires symmetry and relatively large
component geometry, which is inappropriate for these character parts.

`PerfectWordmarkPixels` is a fallback after the established whole-foreground and
symbol-component proofs. It identifies dark ink independently of opaque colored
artwork. Multiple substantial, sparse, isolated neutral-core parts must establish
a bounded word row before complete neighboring dots, warm stems or underlines
can join it. Anchor parts touching opaque color cannot establish a row; a larger
ink component is never clipped into one. Solid eyes, staggered shapes and
texture-like component sets cannot independently authorize conversion.

The core and bounded decreasing-alpha fringe become white, retaining every alpha
value. The usual final dimming yields the same 214-level solid foreground as
existing symbol derivation. Colored fish artwork keeps normal dimming. Original
day bytes, authored night assets, masks and dynamic programs retain their existing
contracts.

Work is bounded by the pixel limit, 4096 components, 128 admitted parts and at most
four fringe steps, with cooperative cancellation. This runs during static
derivation, not in host icon hooks.

`perfect-static-night-9` invalidates derived publications and previews. Regenerate
and apply the selected pack to receive changed pixels; the downloaded source
objects remain reusable.

Evidence: `.artifacts/research/brand-dark-contrast-20261114/`.
The source fixtures are extracted unchanged from the retained Perfect Icons
repository snapshot. Software-bitmap assertions cover real Idlefish and Meituan,
warm asymmetric synthetic letters, colored artwork, alpha equality, dense eyes,
staggered ink and the existing renderer regression matrix. Readability thresholds
apply to solid source ink; antialias edges intentionally blend at lower brightness.
Device-rendered output images are retained for visual inspection.

On the unlocked Phone AVD, the renderer run passed 37 cases with the retained
brand fixture directory supplied. The subsequently added colored-illustration
anchor rejection passed separately (38 distinct device cases in total). The
repository freshness suite also passed 51 JVM cases before this refinement was
finalized. These are focused checks, not a full test-suite run.

Notification-row, weather and collapsed miniature masks are separate issues; a
successful source-bitmap test is not evidence that those surfaces are accepted.
