# Shared icon rendering contracts

## Resolve ownership before rendering

`IconPackEntryIndex` is shared by ordinary preview, export, native recommended
completion and the custom picker. It resolves exact components, package entries,
qualified native activity names, legacy activity names, then package stems.
It does not guess from application labels or package substrings.

`IconPackRenderer.Session.completionDonor` selects permitted phone/Telegram donors.
Both colored artwork and ColorOS monochrome lookup use that selection.
Borrowing colored artwork must not leave monochrome lookup pointed at a
different application's mask. Mapping opt-outs and authored native resources
retain their existing protection.

## Convert raw layers once

Raw native numbered textures and raw supplied ColorOS monochrome images are
child layers. `NativeLayerViewport` obtains the child bounds from Android's
`AdaptiveIconDrawable`. Completed PNGs and generated `mask.png` files already
occupy the final canvas and must not receive that expansion again.

APK monochrome paths can contain intentional whitespace. A small authored glyph
is not evidence of another scale operation. Compare against the actual APK
adaptive child on the same device before changing its dimensions.

Saved custom previews now use `SurfaceIconSnapshotRenderer`, the same closed
template/static-layer renderer used by runtime surfaces. Selecting the first
PNG from a resource closure could show a background texture alone. Unknown
foreign MAML is still not executed.

`NativePaletteIcon.recognizes` owns exact canonical template recognition for
both runtime snapshots and saved-mask migration. It accepts the historical
generated variants without treating arbitrary authored XML as a known template.

`NativeDayNightIcon.artworkEntries` writes both native flat fallbacks and only
adds a selector if the encoded images differ or a native compatibility
override requires it. The handoff uses the same decision when both flat
fallbacks are already in its output. Palette generation always keeps its
live binding and uses the complete closure from `NativeDayNightIcon.entries`.
Explicit native night files retain their normal precedence when indexing a
source pack. Neither rule strips descriptors from authored resource closures.

## Refresh derived state without replacing user choices

A regenerated main pack does not replace durable custom snapshots. Snapshot
`captureRevision` distinguishes old captures from current output.
`CustomIconGeometry` refreshes only an exact installed built-in ColorOS source,
the same saved source entry, and a recognized generated palette template.
Only `mask.png` changes; all colored artwork, other files, mapping metadata and
appearance choices remain intact. Missing sources and unrecognized templates
retain their saved resources.

Publication compares the complete expected selection under the store lock.
Cancellation and concurrent selection changes cannot overwrite a newer choice.
Preview-list loading and final handoff invoke the same migration. New captures
and successfully migrated captures do not repeat it. Migration verifies one
eligible snapshot at a time, using the same integrity reader as publication;
opening the list does not load every saved resource closure merely to inspect
an unavailable or unrelated legacy source.

Generation schema 14 marks the corrected monochrome source selection. Existing
ColorOS-derived output requests regeneration through the normal update notice.
Historical generation schemas remain readable.

## Monet coverage and application

Pixel / AOSP and ColorOS Inspiration select a palette. Both use the same
icon-pack generation preferences for static artwork coloring and full-bleed
foreground padding. Compatible APK, classic ZIP, MTZ and native icons ZIP
sources enter the shared handoff. Imported Monet layers and dynamic resources
retain ownership. Padding is independent of coloring and applies to eligible
foregrounds generated or converted by KeiHome.

Generation schema 16 adds coverage for static native layer pairs without flat
aliases. Their original numbered resources remain intact. A qualifying pair
gets a palette tree, while any native flat aliases already present use the same
day/night snapshots. Older native packs with static coloring enabled can use
the existing update action to pick up the added coverage.

The Monet page can update a current pack whose source KeiHome can resolve, or
open the pack library to select its original source. The normal import and
application receipts govern this shortcut. Artwork changes reach the desktop
when the rebuilt pack is applied. Enabling Monet icons and applying a palette
remain separate actions.

## Manual application in Themes

Icon Pack Settings can show a manual Themes link for an imported pack even
when the converted-icon validation receipt is incomplete. KeiHome opens the
existing local record without an automatic-application token. System Themes
still decides whether to offer or accept manual application. A failed or
pending import does not establish a local record for that attempt. The usual
validation, application receipt, and applied-pack status remain authoritative.

Manual detail launches use a separate full-screen task so Themes can show its
Apply action. When Themes immediately returns a cancellation or the launch
fails, KeiHome prompts users to update or reimport the pack. The detail result
does not identify why Themes closed. A normal Back action within the
1.5-second cancellation window can show the same prompt.

To verify manual application, tap Apply in Themes, check the same desktop page
before and after, and compare the active icon archive with the imported record.
Reapply the original pack in Themes and verify its active archive matches the
pre-test checksum.

On Xiaomi 17 Pro (`OS4.0.8.0.XBLCNXM`, Themes `11.5.6.0`), signed `b0695`
opened the Apple pack from KeiHome directly in full screen. Apply switched
the active archive and the icons on the same desktop page. Reapplying the
original Perfect pack restored its archive byte for byte. A separate test app
with a deliberately missing local record reproduced the immediate Themes
exit and displayed KeiHome's update/reimport message. The original failed
record was unavailable for replay.

## Preservation and fallback boundaries

Preserved packages use original APK artwork in previews, are excluded from
pack completion/export, and cannot acquire a recommended monochrome donor.
Explicit custom selections retain precedence through the existing mutually
exclusive UI actions and final custom-resource publication.
Removing an existing selection can finish while application inventory refreshes;
that background read does not own the saved selection or its original-icon
preference. Resource edits remain serialized by the controller's busy state.

Perfect/native fallback pairs use the same raw child viewport. Flattened
fallback images retain final bounds. Dynamic resources, authored mappings and
custom resource closures must not be replaced merely to equalize optical size.

The device regression suite checks native layer previews, canonical mask
migration, missing sources, cancellation, concurrent edits, mapping opt-outs,
preserved targets and the reporter's actual installed source assets.
