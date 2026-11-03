# Package changes and ColorOS runtime artwork

## Source correction

ColorOS uses both `/my_product/media/theme/uxicons/hdpi` and
`/data/oplus/uxicons/<package>`. The inspected `OplusUXIconLoaderImpl.findAppDrawable`
uses product resources first for preset applications, downloaded resources first
for third-party applications, and falls through per missing file.
`DefaultStyle` selects night artwork independently of the ART+ switch.

The runtime cache on the source AVD contained the missing Coolapk and Xiaomi
Home night artwork, plus other style images and actual monochrome masks.
`import-coloros-icons.py --runtime-source PATH` accepts validated static source images;
`prepare-coloros-builtins.py` includes the optional
`.artifacts/inputs/coloros/runtime-uxicons` directory. Staging directories,
symlinks, invalid names, excessive counts and oversized images are rejected.
The source manifest retains per-file hashes and runtime provenance.

The source cache is a snapshot, not a complete catalog: ColorOS can add artwork
after installation or a native download request. Fetch the union of the whitelist
and native resource catalog without installing their apps, then capture the
explicitly selected source AVD before regenerating the built-ins:

```bash
python3 scripts/fetch-coloros-runtime-icons.py --serial emulator-5574
uv run --with pillow python scripts/capture-coloros-runtime-icons.py --serial emulator-5574
uv run --with pillow --with resvg-py python scripts/prepare-coloros-builtins.py
```

The capture checks the exact inspected framework hash and reads the source
device without writing to it. It admits only bounded package/static PNG, JPEG or WebP pairs,
excludes staging files, records per-file hashes and added/changed/removed
resources, and retains the previous local snapshot. Invalid or incomplete
artwork cannot replace that snapshot. All captured artwork, provenance and
generated packs remain under ignored `.artifacts/`.

See [the Via night-icon investigation](coloros-via-night.md) for a concrete
case where newly available runtime art was absent from the bundled snapshot.

Monochrome images are decoded and encoded without source metadata. Nonsquare
downloaded masks, including a real 241x240 source, are drawn into a square
drawable viewport. Original source hashes remain in the manifest. Generated
archive admission still enforces its existing PNG and alpha-mask rules.

The 2026-11-03 bulk acquisition contains 575 published package resources and
9,842 static images. Classic ART+ now has 602 light and 596 night mappings.
See the [complete inventory](coloros-runtime-coverage.md) and
[priority and generation policy](coloros-via-night.md). These are source mapping counts, not
installed-app coverage or native filename counts.

## Export and upgrade behavior

ColorOS conversion now includes declared component mappings in addition to the
installed launcher inventory. Complete known pairs can therefore be resolved
by the launcher after a later installation without another theme application.
Existing export size and entry limits remain enforced.

For a ColorOS entry lacking artwork in the selected mode, conversion attempts
the configured system fallback or the installed application's original icon.
An entry containing unusable supplied artwork does not silently become original
art. This also fixes ART-off entries with night artwork but no day redraw.

Updated built-in bytes change their store hash. An applied older built-in can
be recovered for “Update current icon pack” only through its exact known
style, appearance and generated ART title. Foreign APKs and ambiguous renamed
themes are not substituted. The user still generates and applies the updated
copy through native Themes.

## Package lifecycle

`PackageChangeObserver` listens to protected PackageManager added, replaced,
removed and changed broadcasts, with a `package:` data scheme and validated
package name. Replacement intermediate added/removed events are ignored.
Receivers are removed when their owner closes.

The system monitor immediately reconciles its icon policy and queues a
`PACKAGE_CHANGE` generation. Changes coalesce into a package set, use a 500 ms
initial delay and refresh only affected launcher entries and shortcuts.
Unfinished source/night/resource work retains its broader scope; an installation
cannot narrow that work. Retirement clears pending package state.

The App separately invalidates installed-pack and preview caches with a 350 ms
debounce. Status and Apps rescan when visible; the pack library reloads when
visible. Synthetic `LauncherApps.onPackageChanged` refresh callbacks are not
used as evidence of real package changes, preventing a refresh/scan feedback
loop. Package add/remove and profile availability callbacks remain registered.

## Focused AVD acceptance, 2026-11-02

Target: HyperOS `emulator-5580`; source ColorOS `emulator-5574` was read only.
Release versionCode: `1793583368`.

- Native Themes generated/applied the updated Classic ART+ pack with Default
  system fallback. Monet stayed disabled and appearance followed the system.
- Coolapk, Xiaomi Home, Bilibili and Taobao displayed their actual dark
  artwork. Switching to light restored their light artwork; dark was restored.
- A resource-only `com.heytap.health` fixture was absent during theme generation.
  Its first installation received ColorOS's heart artwork rather than the
  fixture's red icon. Updating to version 2 retained that themed artwork.
- An unmapped `os.kei.home.installfixture` changed from a red to a blue icon
  on update. Its visible App row, name and four previews updated automatically;
  the desktop also displayed blue.
- Logs recorded package generations with one and two affected packages,
  `cause=PACKAGE_CHANGE`. Neither action required a manual refresh or theme
  reapplication. A screenshot taken immediately during initial icon loading
  preceded the settled image; no subsecond display guarantee is asserted.
- Both fixtures were uninstalled. The visible filtered inventory returned from
  `2 / 310` to `0 / 308`; search was cleared afterward.
- 31 Python importer tests, 51 focused JVM tests and 17 device tests passed.
  Release assembly, including R8 and required lint, passed.
- Launcher, SystemUI and system-server PID/start identities stayed unchanged
  through deployment and this acceptance sequence.

Evidence: `.artifacts/research/install-icon-refresh/`, including deployment
receipts, before/after desktop images, fixture App previews and package logs.
`scripts/prepare-icon-install-fixtures.py` builds the two permission-free,
resource-only QA APKs; it performs no installation. Check package absence
before using these identities and remove only fixtures owned by the test.

## Limits

The shipped App ports the inspected assets. The source acquisition tool can
request all currently known native cloud resources without installing their apps;
it does not add ColorOS's online downloader to the HyperOS runtime. Unknown applications still require available artwork or original
fallback. A day fallback that requires an APK cannot be captured while that
APK is absent; regenerate after installation to fill such incomplete pairs.

Arbitrary APK icon packs still convert the selected installed inventory;
native MTZ/ZIP modules retain all their original resources. Updating a source
icon-pack APK or changing artwork used by an already generated static copy
requires regeneration and native application. Package notifications do not
silently rewrite an active user theme.

Cross-profile update behavior and physical devices were not accepted in this
round. These results do not close the separate launcher watchdog, Flutter or
dual-display lifecycle investigation.
