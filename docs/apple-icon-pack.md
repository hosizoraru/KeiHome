# Apple icon pack

Checkpoint: 2026-11-08. The source is available at Icon packs > Available icon
packs > Apple. This implements static light/dark artwork; Clear and Tint remain
research, not advertised runtime effects.

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
between metadata requests. There is no background polling or per-icon renderer.
HTTPS hosts and redirect destinations are checked, bodies/dimensions/URLs bounded,
and downloaded images decoded before publication. A failed/cancelled generation
retains the last valid pack and completed downloads; partial failures are visible.
Explicit “Check Apple icon updates” refreshes metadata and fetches only missing
or changed content. Opening a screen does not fetch the whole pack.

## Small bundled source

`app/src/main/assets/apple-system-icons/` contains 18 lossless WebP snapshots,
approximately 3.8 MiB, for five families: App Store, Settings, Apple Music,
Safari and Finder. Platform availability differs. Attribution, source provenance,
decoded-pixel digests and the capture/render recipe are recorded in
`provenance.json` and `scripts/prepare-apple-system-icons.py`.

Important source distinction: App Store's iOS presentation is rendered from
Apple's macOS App Store layered artwork using Apple's native iOS presentation.
It is not claimed to be a full-resolution extraction from the attached iPhone.
Physical-device icon retrieval was limited in resolution; the Simulator supplies
other verified layered assets. The generated files are static snapshots, not a
live Liquid Glass renderer on Android.

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
- Atomic publication points to a content-addressed static ZIP. A small previous
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

The existing icon renderer/exporter/native import and fallback policies handle
the resulting pack. The new source does not bypass the installed-only export
setting, native theme receipts or the user's selected fallback policy.

## Verification

`AppleIconPolicyTest` covers rendition/transport bounds and conservative role
matching. `AppleIconRepositoryDeviceTest` exercises linked Android decoding,
archive parsing, deterministic generation, cache reuse, failure/cancellation
preservation, metadata throttling fallback, digest corruption, offline restoration,
platform-specific overrides, original-corner behavior and all 18 bundled images.

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
