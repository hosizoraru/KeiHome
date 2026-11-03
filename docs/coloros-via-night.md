# ColorOS downloaded artwork, native adaptations and update decisions

## Source and root cause

Investigation date: 2026-11-03. Target acceptance is HyperOS `emulator-5580`.
ColorOS `emulator-5574` supplied artwork and ran its native downloader; no physical
phone was used.

Both installed Via packages are `mark.via`. HyperOS has 7.3.3 and ColorOS has
7.3.4, but their icon `res/` files and `resources.arsc` are byte-identical.
The discrepancy was a missing downloaded ColorOS redraw in the old bundled
snapshot. The old applied theme dimmed the original white icon to `#D6D6D6`;
this was not an independently drawn night icon.

Actual framework inspection found that `OplusUXIconLoaderImpl.findAppDrawable`
reads `/data/oplus/uxicons/<package>` for third-party artwork.
`RectangleStyle.tryLoadThemeContent` chooses `rec_night` over a vertical
`#383838` to `#151515` background and then applies native dimming.
Framework SHA-256:
`52c4783bc6027756b56dcb7780d838dd4b36b9726b28f4db2f0a03d49237a0ae`.
Launcher SHA-256:
`b67118677ed9f6bc23ecbd22a2f7f4826b4f31c08641347cc58648fa986196fc`.

## Complete available native catalog

The whitelist has 672 rows, with 619 nonempty unique package strings. It is
not the complete artwork catalog: Via and Apple Music are absent. The native
`com.oplus.uxdesign` icon database supplies additional package names.
Their union contains 1,006 valid application package names, excluding internal
`android` and `oplus` identifiers.

The inspected native downloader accepts batches through
`com.oplus.uxdesign/.icon.service.LaunchIconService`, with **String ArrayList**
extra `android.intent.extra.PACKAGES` and `icon_theme=2`. The shell equivalent
is `am --esal`, rather than `--esa`. No corresponding application APKs need
installation. Native code owns requests, integrity checks and extraction.

All 575 packages with native `published` state were captured. The remaining
431 names have native `default` state and no returned usable cached artwork.
The snapshot contains 9,842 static images: 9,817 PNG, 2 JPEG and 23 WebP. Some
JPEG/WebP files have `.png` names; admission checks the decoded format and
rejects animated or unbounded inputs. Raw bytes and per-file hashes remain
in the ignored source snapshot. Existing usable images were not replaced by
different artwork during this acquisition.

[The complete package inventory](coloros-runtime-coverage.md) lists all 575
packages and their day, night, monochrome, style and size resources. Resource
presence does not imply that every style or every monochrome mask is usable.
Shortcut-only resources are retained as source evidence and do not become
ordinary launcher icons. The current converter uses the standard 1x1 viewport.

The 10 visible built-ins plus the hidden downloaded-only fallback contain
119.4 MiB of generated source archives. Classic ART+ has 602 day and 596 night
mappings; the hidden fallback has 572/564. These are source mappings, including
aliases, rather than installed application counts.

## Independent capability priority

For each capability, choose usable resources in this order:

1. Explicit ART+ / selected pack artwork or monochrome layer.
2. The installed application's genuine day/night pair or official monochrome layer.
3. Downloaded ColorOS artwork or monochrome mask.
4. The chosen HyperOS system fallback, where applicable.
5. Original application artwork.

Night support is measured before OEM dimming, so a brightness filter does not
qualify an application as having native night artwork. Monochrome is checked
independently. Apple Music 7.0.0 on the target supplies real night-qualified
layers and an official monochrome drawable; both precede downloaded fallback.
There is no Apple Music version or package exception in the selection policy.

ColorOS profiles always use the downloaded fallback. Other packs require the
**default-off** option in System & Hook settings. Explicit APK artwork and
existing native MTZ/icon-module resources, animations and night trees remain
authoritative. Supplemental resources fill uncovered entries only. Ordinary
ColorOS styles can carry fallback Monet selectors without forcing Monet on or
replacing explicit colored pack artwork. The user's global Monet setting
controls these selectors.

## Applied state and updates

`keihome-icon-generation.txt` records actual downloaded-fallback package IDs,
considered targets and fingerprints of the official adaptations consulted by
that generation. The ColorOS pill counts unique package IDs that contributed
usable fallback resources to the generated module, including mappings for
applications not currently installed. Component aliases and both modes do not
double count a package. The count comes from the applied snapshot.

Known unrenderable targets do not repeatedly request the same regeneration.
New targets still receive a completion check. A package replacement invalidates
native fingerprint cache identities; unchanged day/night/monochrome pixels do
not demand regeneration, while a newly available official adaptation does.
Checks run on the existing worker flow, with bounded caches and cancellation;
no per-frame probing, polling loop or launcher-native patch is added.

Importing is distinct from applying. Only the native applied module determines
coverage and source identity. In-flight imports suppress stale update prompts;
failed operations retain the old applied state and an error receipt.
Already-generated theme PNGs remain static: use Update icon pack and native
Themes application to adopt newly selected artwork after an app upgrade.
Package callbacks invalidate inventory and previews but do not silently rewrite
an active user theme.

## Themes adaptation

The installed Themes application changed from 11.5.4.0 to 11.5.4.1 during this
work. Its old version gate rejected imports. Both actual APKs were inspected;
the relevant native methods retained their signatures and instruction sequences.
Production now resolves 11 method roles with DexKit using signatures, strings
and call relationships, including the local-ID lookup relationship. Obfuscated
member names and OEM version numbers do not select the production contract.
Resolution refuses ambiguous candidates. A cache is tied to the installed APK
path and DEX/Manifest entry identities; host updates invalidate it. Native
service ownership, Binder ABI, conversion proofs and exact local-resource
associations remain checked.

The additional 11.5.6.0 fixture also resolves and reflects all 11 roles.
[Three-version diagnostics](theme-import-compatibility.md) record APK identities,
method comparisons and the repeatable device matrix without adding version gates.

## Reproduction and evidence

```bash
python3 scripts/fetch-coloros-runtime-icons.py --serial emulator-5574
uv run --with pillow python scripts/capture-coloros-runtime-icons.py --serial emulator-5574
uv run --with pillow --with resvg-py python scripts/prepare-coloros-builtins.py
```

`--audit-only` on the fetch tool reads current state without requesting new
downloads. The fetch tool authenticates the inspected source downloader and
framework; those source-script fingerprints are provenance checks, not a
production system-App version table.

Evidence is under `.artifacts/research/via-night-20261103/`, including resource
comparisons, native catalog receipts, device tests, deployment identities,
applied archive captures and screenshots. Final visual acceptance and Release
identity are recorded in the acceptance receipt after the complete workflow.

## HyperOS AVD acceptance, 2026-11-04

- The complete catalog handoff and four priority/preservation cases passed on the
  actual installed Apple Music 7.0.0 and Via APKs. The actual applied icons archive
  contains the selected native Apple masks and downloaded Via night artwork.
- Two DexKit diagnostics passed against both inspected Themes APKs, and reject an
  unrelated APK. Native import, automatic apply and manual apply then worked on
  the current Themes installation using the resolved methods.
- Classic ART+ shows **ColorOS fallback: 531 unique packages** in the applied
  completion card. Via becomes the downloaded dark icon and returns to the white
  icon in light mode. The completed module has 780 native resources / 725 night
  resources; these include activity aliases and differ from package counts.
- Ordinary Classic ART+ now exposes Monet when its applied module has validated
  palette selectors. Live desktop Via and Apple Music changed to the published
  palette while explicit pack artwork stayed colored. Turning Monet off restored
  the artwork. The relevant original Apple monochrome selection is separately
  asserted by the device test.
- With automatic apply disabled, generation shows Ready to apply and the primary
  button opens the existing native component. Manual application clears pending
  and update status, with no second conversion. Automatic apply was restored and
  another generation/application restored name matching. Refresh does not revive
  the update badge for the completed copy.
- 39 Python and 25 focused JVM tests passed. Release lint passed. API 102 Java
  replacements preserved Launcher 19539, SystemUI 9696 and system_server 8812.
  No launcher-native payload reload or physical-device acceptance was performed.
- Final user state: Chinese, dark mode, marquee labels, default system fallback,
  app-name matching and automatic apply enabled; Monet and the external-pack
  ColorOS option disabled. Final Release identity and installation verification
  are recorded in `.artifacts/research/via-night-20261103/acceptance.json`.
