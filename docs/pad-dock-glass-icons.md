# Pad Dock function icons and a possible glass icon style

## Scope and evidence

This is research, not a newly shipped icon pack or a production native hook.
The Phone AVD received only the guide changes. No physical phone was used.

The retained Pad launcher APK was compared with the installed APK on the running
`KeiOS_HyperOS4_Pad` AVD (`emulator-5554` in this session):

- Launcher `RELEASE-8.01.02.5490-260807-09101711-R`.
- APK SHA-256 `a80390a9098bdfa09b89b6b8eb104ac29c995d1b35ee157cc64552bcfe8bd150`.
- ELF SHA-256 `40667d2c34edf537929e84440838e83bfd9057ae64bf0801410c5c9e383368e8`.
- Installed/source identity, original light/dark screenshots, SVG inventory, and
  exact source windows are under `.artifacts/research/pad-glass-icons-20261107/`.
- System night mode was restored to its original `no` setting. No Pad app/theme
  was installed or replaced, and no icons were moved.

These identities authenticate research inputs; they are not runtime version or
address allowlists.

## Resources and native composition

The APK has fourteen `assets/flutter_assets/assets/images/ic_dock_*.svg` assets:

| Role | Resources | Meaning for an icon-pack proposal |
| --- | --- | --- |
| `phone` | light/dark blur pair | Dock phone/device function; do not silently map to a dialer |
| `xiaoai` | light/dark blur pair | Dedicated assistant function |
| `search` | light/dark blur pair | Dedicated search function |
| `allapps` | light/dark blur pair | Launcher action, not an installed app |
| `home` | light/dark blur pair | Launcher action |
| `recents` | light/dark blur pair | Launcher action |
| mirror device/exit | two additional SVGs | Additional device-function artwork |

The three requested pairs use a `0 0 50 50` view box. Their light resources
contain black/white paths and a `0.1` alpha layer; the dark variants contain
white paths and a `0.3` alpha layer. These SVGs contain paths, not SVG filter
definitions. Exporting them as PNG preserves their artwork and fixed alpha,
but cannot encode sampling of whatever is behind the icon.

`scripts/inspect-pad-dock-assets.py` reproduces the inventory, hashes, colors,
alpha values and SVG element types without treating launcher actions as package
mappings:

```bash
python3 scripts/inspect-pad-dock-assets.py \
  .artifacts/inputs/launchers/pad-5490/launcher.apk \
  --output .artifacts/research/pad-glass-icons-20261107/assets.json
```

The authenticated native source gives the more useful architectural boundaries:

- `_HotseatWidgetPadState._initFactories` registers separate ordinary and blur
  artwork, including `search_svg`/`xiaoai_svg` and the Dock blur assets.
- `_updateGlassMaterialState` reads native blur support and glass-style
  availability. `FunctionIconItemFactory.build` selects registered artwork
  through the native item/factory state, rather than an APK icon-pack lookup.
- `_AssetShortCutIconWidgetState._buildIconWidget` reads current grid
  configuration and builds the resource-based icon. This is a dedicated
  function-icon path, not the ordinary application icon renderer.
- `_ShortCutBlurChildState` has its own material child construction and reactive
  setup. Its `build` is only a cached-child read/return, with an uninitialized
  sentinel failure path. This is evidence of subtree reuse, not a measurement
  of GPU cost or proof that every material parameter is identical to Dock glass.
- The companion material source windows include
  `CompatBlurBlendColorWidget.build`, its `toHyperos` conversion, and native
  `GlassOs4Background`/`_GlassWidgetState` paths. Further production work must
  authenticate the exact selected material branch and its field contract; the
  appearance and SVG suffix alone do not prove equivalent refraction.

The original Pad screenshots confirm the three function entries alongside normal
app icons in both system night modes. They establish the stock appearance on
this source/device pair, not acceptance of a new Phone renderer.

## Relation to Monet

The shared idea is separation of **shape**, **color** and **material**.
Monet supplies a palette; glass rendering supplies background-dependent blur,
lighting and, where supported by the selected renderer, refraction.

The inspected SVGs themselves have fixed black/white artwork and alpha values.
No evidence here establishes that these three entries obtain their colors from
Android's Monet palette. Wallpaper-dependent blending must not be labelled Monet
solely because its appearance changes with the background.

It is reasonable to explore using a supplied monochrome icon as a shape/mask,
optional Monet colors as tint, and a native glass material as a third independent
input. A colored application icon does not automatically provide a useful
monochrome mask. Missing masks need an explicit fallback preserving the app's
identity.

## Feasible product directions

1. **Static glass-style artwork.** Render selected SVG artwork at the target
   density and use normal static theme entries. This is cheap and compatible
   with the existing pack workflow, but its highlights/background are fixed.
   The original six roles are not a full application catalog; mappings need
   deliberate artwork decisions.
2. **Native glass icon appearance, initially Dock-only.** Retain the selected
   pack's foreground or a validated monochrome mask and use an authenticated
   native material beneath it. This is the promising route for genuine live
   glass. It is better modelled as an optional appearance setting layered over
   a pack than as a conventional portable MTZ/PNG pack.

For the second route, do not copy Pad function controllers, action handlers or
cached widget instances into ordinary app icons. Preserve original keys, tap,
launch, drag, badge and edit-selection ownership. Scope material changes to
module-owned wrappers. Reuse immutable children and avoid per-frame decoding,
shape extraction, new per-icon MAML engines, or periodic settings queries.

Background-filter sharing is an experiment to validate, not a guaranteed free
optimization: overlapping filters and backdrop ordering can change the result.
Acceptance must compare actual idle scheduling, launch/drop frames, GPU work and
memory with light/dark wallpaper, system night mode, editing and folders. Keep
system-following behavior as the default and preserve live API 102 preference
updates. Production admission must derive roles/layout contracts from native
source relationships and fail closed when ambiguous, following existing profiles.

This checkpoint adds no experimental renderer to release builds. It preserves
the research inputs so a later implementation can start from the actual stock
function-icon design instead of a frozen screenshot imitation.
