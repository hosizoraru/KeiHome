# ColorOS local icon adaptation

The later [runtime-cache and package-update work](package-icon-refresh.md)
expands the firmware-only inventory described below and verifies installation,
replacement, preview invalidation and the missing Coolapk/Xiaomi Home night art.

This work targets the user-owned ColorOS17_API37 AVD (`emulator-5574`) and
HyperOS Phone AVD (`emulator-5580`). Source firmware resources remain in ignored
`.artifacts/inputs/coloros/`; no ColorOS artwork or decompiled implementation is
checked into the source tree. Optional local builds can include generated
resource-only profiles as described in [the source settings](icon-source-settings.md).

## Verified source contracts

- `android.app.OplusUxIconConfigParser`: `key_ux_icon_config` packs the style in
  bits 4..7, ART+ in bits 8..9, always-dark in bit 10, wallpaper-follow in bit 60,
  and night dimming in bit 61. These are source observations, not HyperOS keys.
- `array/oplus_uxicon_common_style_prefix` contains `recfg`, `recbg`, `mat`, `peb`,
  `day`, `nsd`; the corresponding style values are `[2, 1, 4, 6, 7, 3]`.
- Classic uses separate foreground/background files and night artwork. The
  framework additionally dims with `LightingColorFilter(0xD6D6D6, 0)`.
- ART+ controls redraw selection. Uncovered apps use their own icon resources;
  this resource inventory is not a limit on the number of supported apps.
- Classic's original-adaptive fallback preserves both layers and the source
  integer viewport expansion. Transparent flat icons use the source `#FBFBFB`
  plate and rounded 38/60 dp foreground ratio; other flat icons fill the shape.
  The inspected edge extender is a no-op. Night processing uses the source
  recursive tint guard and `LightingColorFilter(0xD6D6D6, 0)`.
- A real `monochrome.png` supplies an alpha mask, independently of normal
  artwork. Existing Android monochrome layers supply masks for uncovered apps;
  unusable or absent masks retain normal artwork.
  Both sources are raw adaptive children and use the same framework child
  viewport before publication. Completed exported masks use final coordinates;
  see [Monet geometry boundaries](monet-layer-geometry.md). Schema 13 requests
  regeneration of older ColorOS masks that skipped this conversion.

The developer converter independently implements the inspected composition
rules and emits classic appfilter ZIPs. `coloros-source-manifest.json` records
source hashes, layers, aliases, mask provenance and missing modes. A
`coloros-classic-v1` fallback profile opts into the original-icon renderer.
Raw mask assets and the fixed clock/calendar sidecars accompany the profile.
`description.xml` retains the title and author through the catalog/import path.

## Explicit mappings

`coloros-hyperos-aliases.json` maps the actual installed HyperOS launcher
components to user-selected equivalent ColorOS app roles. It includes camera,
browser, gallery, weather, file manager, calculator, notes and compass. No
label-based or guessed package-prefix matching is used. Contacts and dialer are
not aliased together merely because both live in `com.android.contacts`.
The checked PeopleActivity and TwelveKeyDialer entries select separate source
artwork; the latter explicitly uses the source `dialer_` prefix.

Forked apps can share an activity class under different application IDs.
Conversion omits ambiguous global activity filenames and keeps package-specific
fallbacks, rather than aborting or letting one app overwrite another.

The active provider reports the applied Compound parent's ID on this device,
while import returns the Icon child's ID. Its authoritative `resource_path`
identifies the Icon component's UUID-keyed `meta/icons/<id>.mrm`, with a native
SHA-1 in the same cursor. Status resolves that component within the existing
stable-provider snapshot. It does not read the private metadata file:
system_server lacks the file's `ext_data_rw` group. Equal titles do not establish
the "currently used" association.

## Display policy

The icon-pack page offers follow-system, always-light, always-dark and night-dim
for converted APK/classic ZIP artwork. Preferences persist and preview the next
import. The user updates the imported copy and applies it in system Themes.
Existing native MTZ/icons modules keep their complete original behavior.

Converted pairs use HyperOS's original MAML dark-mode observer and zero-rate
images. The ordinary PNG and nightmode trees remain available to other native
readers. The original-icon renderer runs on the app's existing IO dispatcher,
with no app code loaded from the source APK.

## Live resources

The two local time templates use the launcher's existing `DateTime.Minute`
updater at `frameRate="0"`. Calendar text reads the actual weekday/date; clock
hands use hour/minute expressions and the original, separate day/night images.
Appearance selection does not disable time updates. See
[the native contract](coloros-dynamic-contract.md) for target-library evidence,
allowed assets, font differences, lifecycle and canonical matching.

Palette templates observe three fixed system-settings URIs through native
`ContentProviderBinder` observers (`updateInterval="-1"`). The existing system
monitor publishes foreground/background as `#AARRGGBB` after the native policy
ACK, and withdraws the palette when Monet is disabled. Each image applies
SRC_IN to a real glyph/background alpha mask. Decimal settings values are not
fed to the native color parser.

This respects Android's separate foreground/background/monochrome roles:
[Android adaptive icon documentation](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
It does not synthesize monochrome glyphs from full-color PNGs.

Converted archive admission regenerates these fixed local manifests and checks
their raster resources. It does not parse supplied MAML as executable input or
admit arbitrary providers, scripts, fonts, resource paths or source expressions.
Clock/calendar directives in other APK/classic packs retain the original
launcher icon until their specific live contract is implemented; they are not
silently converted into a frozen first frame.

## AVD verification, 2026-11-02

Both AVD identities and root access were checked. ColorOS settings were read
only; no physical device was targeted.

- Masked Classic ART+ source: 96 light/86 night mappings; ART-off: 57/86.
  The two profiles also carry 92 original mask resources and ten dynamic
  sidecar PNGs. Source artwork remains outside version control.
- On the HyperOS AVD, 48 of 50 launcher entries exported normally. Calendar and
  clock filled the other two using native templates. These are 49 packages;
  Contacts exposes two separate launcher activities.
- Native Themes imported and applied source SHA-256
  `b6aa1e4c62a2d142f2eafdb7260c21771e77b259304ebe26dc2bd09596e403b4`,
  Icon component `9a25f784-7cc5-45d7-8068-6fd3c877c63f`.
- Ordinary home, Dock, large-folder previews and the open folder switched from
  original dark ART+ artwork to the source's light artwork and back.
  Calendar displayed Monday/2; clock hands advanced with the actual minute,
  including 01:35 to 01:40 across the folder/night transition.
- Changing only the fixed palette foreground/background keys recolored already
  mounted icons and Dock from lavender/gray to gold/dark green. The module's
  ordinary `key_monochrome_color` did not change. Original values were restored.
  Disabling Monet restored the colored ART+ artwork.
- The final Release card showed 12 redraw-covered packages, 35 original-icon
  packages and two live-icon packages, with the correct "currently used" badge.
- Ten instrumented raster tests and the full import/export/approval diagnostic
  passed, along with 29 focused converter tests. Focused
  palette/publication/archive/time-template JVM tests passed
  (44 initial passes plus one corrected mutation test); Release R8/lint passed.
- Debug installs `1793550450 -> 1793552717 -> 1793553096` and Releases
  `1793554328 -> 1793557171 -> 1793558163` each passed API 102 Java reload.
  The last Release uses the provider path for component identity; four focused
  identity regression tests passed. The intermediate private-file approach
  could not read the Themes-owned file and was replaced without changing
  system permissions.
  Launcher PID/start ticks remained
  `9715/3611`, system_server `8812/2684`, SystemUI `9696/3609` throughout these
  installs and the selected switching flow. No development restart was used.

Detailed receipts/screenshots are in ignored
`.artifacts/research/coloros/evidence/`, including the native import receipt,
conversion diagnostic, palette comparison, folder pair and deployment records.
These selected results do not close the separate historical ANR investigation.
The crash buffer is not empty: it records the pre-existing
`com.mobiletools.systemhelper` receiver-registration exception at 01:29.
No target launcher/SystemUI/system_server crash/ANR/death event or disposed-image
exception was found in the retained test-window logs. One launcher accessibility
dump returned a null root; screenshots and actual interaction verified the flow.

## Reproducing the local source conversion

With the inspected source firmware, resource dump and mask in ignored local
inputs:

```bash
uv run --with pillow python scripts/import-coloros-icons.py \
  --styles classic --art both \
  --aliases docs/coloros-hyperos-aliases.json \
  --resource-dump .artifacts/research/coloros/framework-resources.txt \
  --mask .artifacts/inputs/coloros/mask-rectangle.png \
  --output .artifacts/exports/coloros
```

Import the resulting ZIP through KeiHome's Icon packs page, choose its display
policy, then import/apply the converted Icon component in system Themes.
`--include-static-dynamic` is an explicit research-only snapshot option; normal
profiles use the time sidecars and leave other live source contracts intact.
Both validated profiles are also available in the HyperOS AVD's
`Documents/KeiHome-ColorOS/` directory, with `ART-Plus-On` and `ART-Plus-Off`
filenames. The final local APK and checksum receipt are retained under
`.artifacts/releases/1793558163/`.

## Boundaries

- Source extraction is currently a developer workflow. It does not fetch artwork
  or install ColorOS framework code on HyperOS.
- Source resource-default masks are verified separately from active G2/customized
  shape geometry. The latter has not been reproduced.
- Classic/default-size has the earlier full device validation. The source
  settings now expose Daylight, Night Shadow, Material and Pebble profiles,
  including ART toggles and original-app fallbacks. Active G2/custom sizing,
  exact Material smooth curves and arbitrary source animations remain outside
  the implementation.
- Supported calendar/clock templates retain day/night ART+ artwork; they are
  not yet combined with the palette template. Unsupported live sources keep
  the original native icon. PNG-only native fallback readers cannot execute
  the time templates.
- Ordinary converted artwork covers the installed launcher entries at import
  time. Adding apps or updating their resources requires updating/reapplying
  the converted component. There is no fixed 99-app coverage limit.
- No third-party theme rights, paid store resource or unrelated system process is
  altered by this conversion.
