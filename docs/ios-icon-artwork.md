# iOS artwork source investigation

Checkpoint: 2026-11-07. This document records source research and a desktop-side
prototype. The subsequent Android implementation and its boundaries are described
in [Apple icon pack](apple-icon-pack.md).

## What actually works

| Appearance | Apple storefront / CDN | Local iOS Simulator | Android status |
| --- | --- | --- | --- |
| Default / light | Verified | Native layered resources exist | Static ZIP imported on Phone AVD |
| Dark | Verified, independently rendered pixels | Native dark resources exist | Static ZIP imported on Phone AVD |
| Tinted | No general downloadable variant confirmed | Native tint rendering verified | Not exported as a supported online appearance |
| Clear | No general downloadable variant confirmed | Native Clear rendering verified | Not exported as a supported online appearance |

The important correction to an initial Search-API-only investigation is that
Apple has **two different artwork fields**. An absence of variants in the old
Search API does not establish that Apple cannot provide dark icons.

### Existing implementations

- [HQ-ICON](https://github.com/YuKongA/HQ-ICON), inspected at
  `d1586daf02d4fbfb0050f32224f971cac967602b`:
  `src/searchApp.js` uses iTunes Search / Lookup; `src/drawOutline.jsx` loads
  artwork and renders the requested output/mask.
- [HQ-ICON Compose](https://github.com/YuKongA/HQ-ICON_Compose):
  `utils/Search.kt`, `LoadIcon.kt`, and `Download.kt` use the same storefront
  search family with local image loading, masking, and export.
- KeiMi's `iphone/AppleAppIconSearch.kt` searches CN then US, matches names /
  bundle identifiers, and downloads `artworkUrl512`. That is a useful matching
  precedent, but not the source of independently selected iOS appearances.
- [Apple Search API](https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/Searching.html)
  provides the documented candidate-search path. The extended storefront
  endpoint below is an observed web implementation detail, not a documented
  stable third-party API.

### Extended storefront artwork

Observed request shape:

```text
https://apps.apple.com/api/apps/v1/catalog/us/apps/6448311069
  ?platform=web
  &additionalPlatforms=iphone
  &extend=iconArtwork
  &fields[apps]=name,artwork,iconArtwork
  &l=en-US
```

The response's `attributes.platformAttributes.ios` can contain:

- `artwork.url`: conventional raster source, also exposed through older APIs.
- `iconArtwork.url`: a different template, observed using
  `PurpleSource.../Placeholder.mill/{w}x{h}{c}.{f}`.

The storefront's own JavaScript requests `iconArtwork` and selects it in
preference to the conventional artwork for these icon presentations.
`Placeholder.mill` is a source URL component; its presence is not proof that
the complete layered source can be downloaded.

Verified substitutions:

```text
{w}, {h} = requested dimensions
{c}      = Icon.ApSiOS01 for the observed iOS icon presentation
{f}      = png
query    = appearance=light or appearance=dark
```

QQ Music, ChatGPT and Apple Music produced different decoded RGBA images
between light and dark. ChatGPT and Apple Music were additionally exercised
through the reusable probe. There is no app-version or package-name special
case selecting these sources.

The probe also supports `bb` as an original-artwork crop request. It records
returned dimensions and alpha instead of assuming a query necessarily changes
the result. This is not an implementation of every custom corner-radius option.

### Unsupported-parameter controls

HTTP 200 is insufficient. In the sampled requests, candidate names such as
`clear`, `tinted`, camel-case and hyphenated variants, and several
rendering-mode / appearance-variant query forms returned the same decoded
pixels as the default artwork.

The reusable probe always compares:

1. Decoded RGBA hashes, not just PNG file hashes or sizes.
2. The light baseline.
3. A deliberately unsupported appearance control.

An identical output is recorded as **unconfirmed-identical**, not as a
successful Clear/Tint download. Identical output can mean an ignored parameter
or identical variants for that app. Conversely, a distinct output alone does
not prove a physically correct realtime material.

The ordinary `.mill` URL without a rendition returned 404; the attempted
`image/source` form returned 403. These observations do not establish that
every possible source-download route is unavailable.

## Native Simulator findings

Research used the user-provided booted iOS 27.2 Simulator
`25D4D9B1-622B-48F8-AE77-68804E42E5D1`. This runtime has two displays;
display 3 was the active visible screen. No runtime was installed or erased.
The connected physical iPhone was only inspected for connection/display state
and an initial screenshot before switching to Simulator.

`assetutil --info` on the Simulator's Settings and Photos `Assets.car` files
found actual `IconImageStack` resources with:

- `UIAppearanceLight`, `UIAppearanceDark`, `ISAppearanceTintable`;
- separate layers and blend modes;
- layer translucency, specular, refraction, and shadow properties.

The native `IconRendering` framework exposes `ICRRenderingMode.color`,
`.clear`, and `.tintWithRed:green:blue:alpha:`.

Reading the local asset catalog through `CUICatalog` /
`CUINamedIconLayerStack` and rendering through its native
`_IF_ImageWithSize:...` bridge produced eight Settings PNGs.
In this runtime, appearance variant 2 selected Tint with the supplied blue
color and variant 3 selected Clear. The native helper's mode payload was
compared against objects returned by `ICRRenderingMode.clear` and `.color`;
the classification did not rely solely on a gray screenshot.

These numeric values are **research coordinates for this Apple runtime**,
not a version table or Android production implementation.

An earlier IconServices bundle-icon approach produced a generic fallback
instead of the actual Settings artwork and was rejected. The direct catalog
approach resolved the real Settings layer stack.

This proves that native Clear/Tint rendering is possible with the local
layered source. It does **not** prove that the same layers can be fetched
for arbitrary App Store applications through the public image URL.

[Icon Composer documentation](https://developer.apple.com/documentation/xcode/creating-your-app-icon-using-icon-composer)
describes the multilayer icon model. The
[decant source](https://github.com/kylebshr/decant/blob/main/icon-extract.m)
was useful for researching CoreUI catalog/layer-stack access.
Its source extraction work is separate from our native-render experiment.

### Static artwork versus realtime glass

A PNG can preserve a server/native render's highlight, shading and translucency.
It cannot by itself reproduce wallpaper-dependent refraction or interactive
lighting. Transparent corner pixels also do not prove a transparent icon body.

Therefore:

- Do not name a filtered conventional PNG an Apple Liquid Glass asset.
- Do not advertise a cached native Clear PNG as full realtime Liquid Glass.
- Do not add one constantly ticking MAML or glass compositor per ordinary icon.
- Genuine realtime Android glass would require a separate bounded renderer and
  performance investigation, with explicit visual differences documented.

## Reusable prototype

`scripts/probe-apple-icon-artwork.py` requires Pillow, already used by the
project's artwork tooling.

Candidate search never automatically assigns a namesake:

```bash
python3 scripts/probe-apple-icon-artwork.py \
  --search Telegram --country us \
  --output .artifacts/research/ios-search
```

For example, searching `Telegram` returned Telegram Messenger together with
other messengers and companion apps. Literal Android-label equality alone is
not a safe general identity check.

Explicitly confirm a mapping first:

```json
{"com.apple.android.music": "1108187390"}
```

```bash
python3 scripts/probe-apple-icon-artwork.py \
  --mapping mapping.json --resolution 512 \
  --probe clear --probe tinted \
  --output .artifacts/research/ios-artwork
```

Outputs include metadata, source URLs, image/pixel hashes, per-appearance
classification and, when mappings are supplied, `ios-icon-pack.zip`:

- ordinary `appfilter.xml`;
- static day / distinct-night PNG resources;
- stored PNG ZIP entries, avoiding redundant Deflate;
- source manifest, no MAML or ContentProviderBinder;
- no unconfirmed experimental appearance exported as a supported variant.

`--metadata-dir` reuses saved `<id>-metadata.json` responses. Metadata requests
are paced, bounded to 12 selected IDs per invocation, and stop on failure rather
than retrying continuously or publishing a partial pack. HTTP 429 occurred
during exploration; a restricted observed field set subsequently succeeded.
The exact cause of those 429 responses was not established.

The generated Apple Music ZIP was imported through the installed KeiHome
1.5.0 file picker on HyperOS Phone AVD `emulator-5580`. The real catalog showed
one mapping, one installed application match, and the correct separate light
and dark previews. The PNG entries were stored without redundant ZIP Deflate.
The ZIP was not applied to the system theme; Classic ART+ stayed active.
Launcher 9732, system_server 8850 and SystemUI 9699 remained unchanged.
The staging file in Downloads was removed; the imported local prototype is
left available for inspection.

Focused tests:

```bash
python3 -m unittest discover -s scripts/tests -p test_apple_icon_artwork.py -v
```

## Proposed Android integration boundary

1. An optional online iOS source; no network access just for opening the catalog.
2. User-selected installed apps, candidate search, publisher/platform display,
   and confirmation for ambiguous or cross-language names.
3. Persist confirmed Android package -> storefront ID associations through APK
   updates; reuse the existing local settings/storage architecture.
4. Cache metadata with freshness information and downloaded artwork by source
   URL/content identity. Batch only missing or explicitly refreshed items.
5. Export through the existing static icon-pack path. Keep existing fallback
   priority and leave unrelated apps untouched.
6. Offer genuine supported light/dark appearances first. Add Clear/Tint online
   choices only after the required source/rendition path is established.

No production Android source, background service, dependency, version prefix,
release tag or installed module APK was changed by this research checkpoint.

## Evidence

Ignored evidence is retained in
`.artifacts/research/ios-icon-source-20261107/`:

- `store-api-web.json`, `metadata-cache/`, and storefront JavaScript;
- `web-artwork-templates.json`;
- appearance probe receipts and PNGs, including negative controls;
- `verified-static-demo/` and `music-network-demo/`;
- `simulator-icon-bundles.json` and `com.apple.*-assets.json`;
- native runtime method inventories, local probe sources and `coreui-render/`;
- `coreui-render.log`;
- `acceptance.json` for the final source-versus-device receipt.
