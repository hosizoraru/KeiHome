# Dynamic icon artwork audit (2026-11-14)

## Weather: complete preview and native scene are different inputs

The follow-up screenshots reopened Weather acceptance after its published
notification outline was corrected. Transparent corners did not prove correct
artwork. Both desktop and notification consumers could still show an angular
blue fragment behind the lower-right cloud.

The retained source revision is
`fb8b5c9224510fedb653a7dceafdcb364b4e2263`. A fresh upstream HEAD check on
2026-11-14 matched that revision.

Its Weather root quiet image has baked sky/cloud gradients. Generic flat-image
background classification leaves part of the sky as a foreground fragment.
The actual native layers do not have that fragment:

- Layer 0 quiet and fancy `bg.png` are byte-identical 351x351 backgrounds.
- Layer 1 quiet and fancy `bg.png` are byte-identical 351x351 cloud artwork,
  including its original alpha.
- Both native scenes center their images on the same 351x351 canvas.
- The source config uses a quiet background and a fancy Weather foreground.
- The root quiet image is not pixel-equivalent to compositing those two layers:
  its authored sky shading differs. The existing general flat/layer equivalence
  proof must therefore remain strict.

The new derivation selects the native scene's appearance for the *night root
preview*, rather than guessing the boundary between its baked sky and cloud.
Admission requires the exact recognized Weather provider schema, two layers
with the original mode selectors, matching quiet/fancy image hashes, the simple
centered background scene, and 351x351 PNGs for both layers and the root preview.
It then uses the existing two-layer night renderer. The author's day root
preview remains unchanged.

The complete native layers also include adaptive overscan. Compositing their
whole canvases would make the notification clouds smaller than the native
desktop clouds. The generated preview therefore uses the platform
`AdaptiveIconDrawable` child bounds before the final mask. It does not hardcode
an inset fraction or apply the platform mask to the exported artwork.

The dynamic derivation revision becomes `perfect-dynamic-night-9`, so an old
publication must be regenerated and applied. This changes no provider, location
permission, temperature value, refresh interval, glyph path, or animation
command. The missing AVD temperature is outside this defect.

## Calendar text

The Calendar root also contains a weekday outside the date foreground. Its
complete image therefore fails whole-image equivalence. All 5,712 pixels covered
by the date foreground nevertheless agree exactly with the same-theme layer
composite. A partial proof checks every covered source pixel before repairing
that region. It preserves the weekday, alpha, unrelated artwork and day bytes.
One mismatched foreground edge rejects the repair; cancellation cannot publish
partially staged pixels.

Some selectable Calendar styles use native `Text` for their weekday. Only
literal neutral text with RGB at most 32 receives the exact generated day/night
expression. Alpha, text, geometry, font, commands and the original day color
stay unchanged. Admission requires the registered, unshadowed `DarkMode`
updater; arbitrary expressions and Folme text remain excluded. The actual AVD
SystemUI `ColorParser` passed twelve day/night/day checks, including partially
and fully transparent colors. This is Java expression proof, not a claim about
unrelated native runtimes.

## Inventory and external references

The full archive contains 162 authored groups across 22 styles and common
resources. Common + HyperOS4 selects 20 IDs (18 distinct source trees).
The production-equivalent probe generates night variants for 15; five retain
their original structures:

- `com.android.vending.AssetBrowserActivity`
- `com.autonavi.minimap`
- `com.baidu.BaiduMap`
- `com.tencent.mm`
- `eu.kanade.tachiyomi.j2k`

Those five are not newly generated night coverage. Unsupported roots,
transforms and blend programs stay authored rather than approximated.
Incomplete resources in other styles retain the existing fallback.
The task's `dynamic-audit` artifacts contain the complete resource-role and
coverage inventory.

[PerfectIconsPacker](https://github.com/YifePlayte/PerfectIconsPacker/tree/54412bbf3ceb75fe3663de5327d0da1132bbba76)
was reviewed at the pinned revision. Its ordered quiet-layer preview and
complete native-tree packaging are useful references; it does not supply a
general dynamic night or glyph transformation. No implementation was copied.

Installed-code research on the ColorOS AVD found separate authored `rec_night`
selection/background pairing and an independent brightness filter. The
framework retains full layer canvases and one final mask; Launcher switches
its light/dark model column and explicitly invalidates its memory cache.
This supports separating artwork selection, geometry, dimming and refresh.
It does not justify copying private Oplus classes, its dim factor or mask
viewport into HyperOS. Installed hashes, signatures and source extracts are
in the task's `coloros-icon-reference/REPORT.md`. That pass did not change or
visually test the ColorOS AVD's theme.

## Validation boundaries

The image-level regression must inspect the generated complete preview, not
only its mask alpha or equality with an algorithm's own output. The actual
native clouds, dynamic namespace closure, source archive and day artwork must
also be checked.

The combined focused JVM run passed 145 cases with two existing conditional
skips. All 58 distinct Android source, geometry, partial-proof and static-renderer
cases passed. One assertion initially named the old revision; its corrected
focused rerun passed. The raw failure is retained.

The latest AVD ZIP was regenerated and applied through the actual UI. Expanded
desktop folder and notification header captures show the enlarged complete
Weather cloud. The installed archive and captures are retained separately from
the earlier pre-viewport evidence. Physical-device acceptance and Super Island
acceptance are separate gates; pending checks are not acceptance.

The first floating notification during KeiHome's own API102 hot reload remains
a separate open issue.
