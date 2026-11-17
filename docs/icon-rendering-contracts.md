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

`NativeDayNightIcon.entries` returns a complete closure, including both native
flat fallbacks. Palette, recommended and Perfect generation reuse that closure
instead of independently remembering to append the night fallback. Explicit
native night files retain their normal precedence when indexing a source pack.

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
