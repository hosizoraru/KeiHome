# Apple icon pack

The expanded system presets, new Siri artwork, locale-aware storefront defaults,
shared-versus-incomplete dark-resource presentation, and incremental batch/backoff
policy are documented in [Apple system mappings](apple-system-mappings.md).

Checkpoint: 2026-11-08. The source is available at Icon packs > Available icon
packs > Apple. Ordinary mappings use static light/dark artwork. Mobile Calendar
and Clock additionally have bounded native-layer dynamic templates, described
below; their actual launcher acceptance is still in progress. Clear and Tint
remain research, not advertised runtime effects.

## Selection and mapping

- Platforms: iOS, iPadOS and macOS. A product unavailable on the chosen platform
  is not silently replaced with artwork for another platform.
- Resolution: 256, 512 or 1024 pixels; 512 is the default and 512/1024 recommended.
- Rounded requests Apple's platform presentation. Original retains the source
  shape. Original can lack the distinct dark rendition available with Rounded.
- Import appearance uses the existing system-following/light/dark preference.
  Changing the generated artwork requires applying/updating the theme.
- Confirmed equivalent products and conservative system-app roles are mapped
  automatically. App-store roles use the system App Store, not Apple Store retail.
  Uncertain namesakes stay unmapped.
- The mapping subpage supports per-app search, developer/bundle-ID inspection,
  light/dark preview, manual replacement, automatic restoration and explicit
  exclusion. Overrides are saved separately for each platform, including apps
  without automatic matches.
- Matched icons opens a separate gallery of the resolved installed-app mappings,
  with Android package, automatic/custom origin, Apple destination, storefront
  and light/dark previews. Missing dark resources are labelled explicitly.
- Mapping cards compare the current applied theme with the Apple destination.
  The current-theme side shows one image when only one rendition is available
  or decoded day/night pixels are equal, and two when they differ.
  This does not infer support from filenames alone.
  Missing, unreadable or live-MAML-only current artwork is labelled unavailable;
  it is not silently replaced with the original APK icon.
- Custom mappings includes all launchable apps, including unmatched/excluded
  entries. Both browsers support local search, sorting, filtering, a storefront
  filter and a floating refresh action. Sort/filter use the same MIUIX top-bar
  cascading menus as the Apps page, not a control card inside the list.
  Refresh rereads installed apps and local
  resources; it does not refresh every remote icon.
- The editor shows the existing mapping before searching. Its storefront is
  independent of the global default and is saved with each manual choice.
  Presets include US/CN/JP/HK/TW/GB/CA/KR; other ISO two-letter regions can be
  entered. A valid region code does not guarantee catalog/product availability.
  Different storefronts can coexist in one generated pack.

Apple Music Classical is a valid user-selected replacement for an unrelated
Android music player. It is not automatically assigned to an arbitrary app just
because its name contains “music”.

## Retrieval and recovery

The observed Apple storefront `iconArtwork` API is preferred. The ordinary iTunes
catalog artwork is a fallback when the extended endpoint is unavailable or
rate-limited. These web rendition contracts are observed behavior, not an Apple
public compatibility guarantee; see [the source investigation](ios-icon-artwork.md).
HTTP success alone is not considered dark support: decoded pixels are compared.
No artificial dark overlay is presented as an official dark icon.

Requests are user-driven, serialized and cancellable, with at least 3.5 seconds
between metadata requests. Retrieval adds no background polling or per-icon
network worker. Ordinary generated mappings remain static; only the two dynamic
products below opt into native MAML.
HTTPS hosts and redirect destinations are checked, bodies/dimensions/URLs bounded,
and downloaded images decoded before publication. A failed/cancelled generation
retains the last valid pack and completed downloads; partial failures are visible.
“Complete missing resources” retries incomplete entries while retaining complete
cached pairs. “Refresh artwork for this app” explicitly refreshes one mapping.
Both respect server backoff. Opening a screen does not fetch the whole pack.

## Small bundled source

`app/src/main/assets/apple-system-icons/` contains 38 lossless WebP snapshots,
10,691,322 image bytes (approximately 10.2 MiB), including App Store, Settings,
Apple Music, Safari, Finder and verified mobile system products. Platform
availability differs. Attribution, source provenance,
decoded-pixel digests and the capture/render recipe are recorded in
`provenance.json` and `scripts/prepare-apple-system-icons.py`.

Important source distinction: App Store's iOS presentation is rendered from
Apple's macOS App Store layered artwork using Apple's native iOS presentation.
It is not claimed to be a full-resolution extraction from the attached iPhone.
Physical-device icon retrieval was limited in resolution; the Simulator supplies
other verified layered assets. The generated files are static snapshots, not a
live Liquid Glass renderer on Android. The separate native Calendar/Clock layer
bundle is documented below.

Bundled originals cap real source resolution at 1024; selected output sizes never
imply that an unavailable higher-resolution original was recovered. Unavailable
bundled roles/corners fall back through the normal source logic rather than
inventing a different system icon. Original Apple Music does not claim separate
dark support when decoded light/dark pixels match.

## Storage and incremental generation

App-owned options and overrides use MMKV. Artwork lives in app-private
`no_backup/apple-icons/`, outside Android backup:

- SHA-256-addressed PNG blobs; identical image content is stored once.
- Request identity distinguishes region, platform, resolution, corners, appearance
  and source template/revision.
- Publication schema 2 qualifies artwork/resource identities by Apple product and
  effective storefront. An identical product ID from two regions cannot reuse
  the other region's provenance. Content-identical PNG blobs are still shared.
  Legacy choices without a country inherit the global region. Schema 1 archives
  restore only in their recorded region and migrate during regeneration.
- Search/metadata records are bounded. Image cache has a 256 MiB eviction budget.
- Atomic publication points to a content-addressed ZIP with static artwork and,
  for the two supported kinds, a bounded dynamic-layer index. A small previous
  generation is retained; old generations are pruned.
- ZIP image entries use STORED. No new MAML, Binder-query manifest, timer or
  perpetual rendering instance is generated for these ordinary static images.
- Existing mapped artwork is reused without network requests. Unchanged content
  retains the existing pack. Mapping changes update only the affected source set.
- Clearing downloads preserves overrides and the generated pack. Existing archive
  images can restore previews/rebuilds offline, even after metadata removal.
- Generation metadata includes source digest so previews and normal theme-update
  checks can distinguish changed generated content under the stable Apple source.
- Browsers read published archives and local metadata only. Full remote previews
  are fetched on an explicit editor action. Sampled thumbnail decoding runs on IO
  and uses a shared, bounded 8 MiB bitmap cache, keyed by immutable image path and
  requested pixel size. Row preview space is reserved to avoid loading jumps.
- Current-theme comparisons use a separate 2 MiB bitmap-result LRU and at most
  two concurrent loads on IO. The provider is recreated when the applied source,
  palette, preview generation or configuration changes. Only a complete applied
  theme with a matching source revision is used. An unavailable result is cached
  too; opening or scrolling this comparison does not start network retrieval or
  instantiate a live MAML renderer.

The existing icon renderer/exporter/native import and fallback policies handle
the resulting pack. The new source does not bypass the installed-only export
setting, native theme receipts or the user's selected fallback policy.

## Verification

`AppleIconPolicyTest` covers rendition/transport bounds and conservative role
matching. `AppleIconRepositoryDeviceTest` exercises linked Android decoding,
archive parsing, deterministic generation, cache reuse, failure/cancellation
preservation, metadata throttling fallback, digest corruption, offline restoration,
platform-specific overrides, original-corner behavior and the then-bundled 18
images in the initial checkpoint.

The checkpoint passed 721 JVM tests, 15 targeted Android instrumentation tests
and Release lint. Actual Phone AVD screenshots establish online Apple Music
Classical search, custom mapping of MT Manager, successful theme application,
and distinct light/dark desktop artwork. macOS bundled generation produced six
mapped packages with independent dark artwork. These are AVD acceptance results,
not physical Android acceptance or proof of every store product's availability.

Evidence and the final signed-release receipt:
`.artifacts/research/apple-pack-20261107/acceptance.json`.

The browser/storefront checkpoint adds `AppleMappingBrowserTest` and expands the
repository tests to cover per-mapping regions, same-ID regional separation,
country persistence, cache-only misses and offline legacy migration. The current
suite passed 728 JVM tests and 24 repository instrumentation tests. Device/UI
acceptance and the matching signed release are recorded separately in
`.artifacts/research/apple-browser-20261108/acceptance.json`.

## Appearance correction and portable mappings (2026-11-08)

The native IconFoundation export API uses appearance `0` for default/light and
`1` for dark. These values differ from UIKit's `UIUserInterfaceStyle` enum.
The former exporter passed `1/2`, which put dark artwork in some light slots.
The renderer and provenance now record the corrected contract. Regenerated
native resources invalidate affected published pairs by bundled revision;
unrelated downloaded images and storefront mappings remain reusable.
Cache-only previews may decode bundled assets locally after an update without
making network requests.

The bundle now includes genuine Photos, Safari and Files catalog renders from
the installed iOS Simulator runtime, used as offline alternatives for Rounded
iOS/iPadOS options. There are 24 bundled images including the existing Music
snapshots. App Store's iOS presentation still uses the macOS layered catalog
rendered through IconFoundation; its provenance does not claim an iPhone catalog
extraction. This is a bounded static resource set, not a new runtime renderer.

Live Apple responses confirmed the automatic mapping IDs' expected bundle IDs.
They also exposed a separate limitation: ordinary iTunes raster URLs can ignore
appearance, and `bb`/Original output can lack a distinct dark variant even when
the platform's rounded template provides one. Catalog fallbacks are now marked
limited so a transient HTTP 429 cannot replace cached complete metadata.
Distinct pixels demonstrate an independent variant; they do not prove
equivalence to every iOS release or represent Liquid Glass.

Both mapping browsers expose document import/export in the top menu. Schema 1
JSON includes manual replacements and exclusions on all three platforms,
uninstalled package names and per-mapping storefronts. It excludes downloaded
images, cache paths and other app settings. Import validates the complete UTF-8
document before writing, merges by package/platform, and preserves unrelated
choices. Bounds are 2 MiB and 10,000 choices; duplicate keys/choices, unknown
platforms and malformed values are rejected. Android's document picker handles
storage access without a new broad-storage permission.

The top filter menu includes "Missing independent dark icon"; matching rows
carry an explanatory badge. Capability inspection is local and cancellable,
limited to 128 distinct pairs per snapshot. Missing/uninspected cache entries
remain unknown and are not treated as proven missing dark variants. This does
not start a bulk network refresh. Explicit metadata refresh remains available.

Validation: 745 JVM tests, 33 repository/resource/client device tests and Release lint.
The device tests cover offline cold bundled previews, incorrect legacy
publication slots, actual MMKV transfer across all platforms, exclusions and
storefronts, and invalid-import non-mutation. Actual AVD checks exercised document
save/open, corrected previews and the missing-dark filter.
Final signed Release and process identities are recorded in
`.artifacts/research/apple-artwork-correctness-20261108/acceptance.json`.

## Camera dark fallback (2026-11-08)

Camera does support an independent dark icon. The previous missing-dark badge
described the locally obtained raster, not Apple's complete artwork. Its
verified `iconArtwork` `.mill` template supplies distinct 1024px light/dark
images with `Icon.ApSiOS01`; the `bb`/Original endpoint returns identical pixels.
The rounded pair is now bundled as a lossless offline fallback for iOS/iPadOS.
All previously verified images are retained, bringing the bundle to 26 images.
Bundled revision invalidation also applies to Camera, replacing the old
single-appearance publication and preview without discarding unrelated caches.
No rounded artwork is substituted for an Original request.

Reproduction inputs are retained under
`.artifacts/inputs/apple/camera-ios-snapshot`; pass that directory using
`--camera-snapshot` when preparing the bundled artwork. Source requests, raw and
decoded fingerprints, device tests and final acceptance are recorded under
`.artifacts/research/apple-camera-20261108/`. The focused device suite passed
29 tests, including Camera's offline iOS/iPadOS variants at 256/512/1024px
with legacy limited metadata.

## Native Calendar and Clock (2026-11-08)

The mobile Apple Calendar (`1108185179`) and Clock (`1584215688`) selections now
carry native-layer dynamic resources for iOS/iPadOS. macOS does not enter this
live path or receive mobile artwork as a substitute. The supported dynamic
schema has exactly two kinds, `CALENDAR` and `CLOCK`; other Apple selections
continue to produce static images. This is not general support for imported
MAML, arbitrary Apple widgets or an iOS Liquid Glass renderer.

### Layer provenance and bounds

`app/src/main/assets/apple-dynamic-icons/provenance.json` records the actual
iOS 27.3 Simulator CoreUI sources: `MobileCal.app/Assets.car` for blank Calendar
backgrounds and `ClockAngel.app/Assets.car` for clock dials and separate alpha
hand layers. Its **20 platform/part/appearance records reference 10 deduplicated
lossless WebP images totalling 973,388 image bytes**, excluding the manifest.
The iOS/iPadOS records share identical content where appropriate.

The manifest retains source-catalog hashes, image hashes, dimensions and original
hand rotations. The extraction recipe is
`scripts/prepare-apple-dynamic-icons.py` with
`scripts/render-apple-dynamic-icons.m`. Clock hands were normalized to vertical
offline from the measured 306/54/180-degree source positions; Android does no
runtime angle calibration or Apple private API invocation. Calendar backgrounds
contain no baked weekday/date, and clock dials contain no baked hands. Original
uses the supplied layers' own contour rather than reconstructing hidden artwork.

Live layer dimensions are capped at 512px, including when a user selects 1024px
previews. Clock has a day/night dial and three full-canvas hands per appearance;
Calendar has a day/night background pair. Preview snapshots retain the selected
256/512/1024px resolution. Native handoff downsamples a 1024px fallback snapshot
to 512px, separately from the live layer group. Compressed source layers have
their own 2 MiB LRU; this is separate from both browser bitmap caches.

### Updates, localization and previews

- Calendar uses native `DateTime.Minute`, with natural `EEEE` weekday and `d`
  date formatting. Weekday/date follow the runtime's locale and default time
  zone; there is no table of hardcoded weekday translations.
- Clock uses native `DateTime.Second`. The hour and minute angles include the
  smaller units; seconds advance by the native second update rather than a
  continuous sweep animation.
- Both templates keep `frameRate="0"` and add no settings binders, application
  polling loops or module-owned periodic timers. The native DateTime updater
  supplies the scheduled date/time ticks; zero frame rate does not mean no
  updates or zero rendering cost.
- Fixed light/dark, system-following appearance and night DIM use a finite set
  of canonical templates. Import validation checks the exact template and local
  asset schema; it does not execute caller-provided expressions or scripts.

The Apple pane and ordinary fallback resources are **snapshots** captured using
the device's system language, default time zone and time at rendering. They do
not tick continuously in the browser and can become stale; a captured clock
position or date is not evidence that the installed live icon is frozen.
Calendar uses Android system `sans-serif-medium`/`sans-serif-light` typography;
no Apple SF font is bundled and pixel-identical SF layout is not claimed.

The current-theme pane cannot play arbitrary live MAML. Such an icon is marked
unavailable instead of presenting its static fallback as the actual animated
theme. The Apple pane can still show its separately generated snapshot, with
the dynamic-preview explanation. A missing live comparison is not proof that
the generated dynamic archive lacks its layers.

### Verification boundary

The implementation checkpoint passes **788 unit tests** and **44/44 focused
device tests** (35 dynamic/repository and nine current-theme preview tests).
These include `ConvertedIconArchive` inspection with 1024px preview snapshots
and 512px handoff resources. The actual current-theme comparison screenshot has
been accepted, and Clock seconds have been observed advancing on the launcher.
Settled HyperOS Phone AVD screens also confirm the live Calendar weekday in
Chinese, English and Japanese, and distinct light/dark Calendar and Clock art.
Natural midnight changes November 8 / Sunday to November 9 / Monday without
regeneration or a launcher process change. The separate light/dark switch also
preserves that launcher identity. Automatic time, Asia/Singapore, Chinese and
dark mode are restored after the test.
The observed clock scene requests draws at roughly one-second intervals while
visible. A five-second settled background sample contains no draw request from
that scene; this is a bounded lifecycle check, not a zero-cost or battery claim.

Changing the global system locale caused graceful native launcher exits and
roughly 30--40 seconds of cold startup in this AVD. Early transition screenshots
were rejected; the locale results use settled screens. No stable-PID claim is
made across those global configuration changes. Time-zone rendering is covered
by injected device tests; switching the live launcher's time zone was not part
of this checkpoint.

The [dynamic acceptance receipt](../.artifacts/research/apple-dynamic-20261108/acceptance.json)
records the final artifact, normal-install/API 102 identity gate, screenshots,
background lifecycle sample and restored user state separately. These are AVD
checks, with no physical-device or general performance claim.


## Incremental third-party matching

The custom-mapping browser offers a ten-target batch action and selection of
unmapped, non-excluded apps. A batch uses the **currently selected storefront**;
manual mappings retain their individual storefronts. System presets, exclusions
and existing manual or automatic assignments are not overwritten. Batch matching
searches the store directly, bypassing the manual editor's local system-preset
suggestions, and does not fetch icon images. Artwork fetching remains the separate
incremental generation/preview operation with its existing caches and backoff.

Names are normalized for Unicode compatibility, case, spacing and benign
punctuation. A unique exact bundle identifier takes precedence; otherwise a
unique exact normalized title is required. Editions such as Pro, Lite and HD are
not discarded, substrings are not sufficient, and duplicate exact-title products
remain review candidates. These rules are conservative suggestions, not proof of
publisher identity; every saved automatic result can still be edited or excluded.

Each completed attempt is saved immediately in MMKV by platform, storefront,
package and launcher label. Untried targets precede expired retries. Unattended
batches skip review and no-match history for seven days, with up to five local
candidates for review. This history is not an HTTP 429 cooldown. Explicit matching
from the browser or selection toolbar can retry these entries immediately,
including records saved by older versions. It reuses searches up to 15 minutes
old; older searches are refreshed on demand. Users can open saved candidates in
the editor at any time without repeating the search. The browser labels saved
results separately from failed-request retry deadlines.
Network failures are not classified as misses. A failure stops the current batch,
uses the API's persistent retry deadline (or a 15-minute failure retry interval),
and leaves untouched targets for the next batch. There is no background retry
job or loop. Cancellation retains completed mappings and leaves the in-flight
search eligible for a subsequent explicit batch.

Recent history is bounded to 2,000 records. Successful mappings are stored
separately and survive history pruning and download-cache cleanup. Mapping export
includes automatic assignments; importing them makes them explicit choices,
retaining their storefront and the usual manual-override priority. Browser
refresh does not erase history or force network requests. A new label, platform
or storefront creates an independent search history, while an existing mapping
remains available until the user changes or resets it.

Device results for this checkpoint are recorded separately in
`.artifacts/research/apple-batch-matching-20261108/acceptance.json`.
