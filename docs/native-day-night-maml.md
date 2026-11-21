# Native day/night MAML icons

**Bounded device acceptance is complete for the generated selector.** On
launcher 8593, an Arcticons Settings pair rendered black -> white -> black
through a light -> dark -> light cycle while the launcher process and start
time stayed unchanged. The launcher log also recorded
`MAML: fancy_icons/com.android.settings.MainSettings build fromZip`. This
proves the generated selector is consumed for the tested component and device
state; it does not prove every package-policy class, retained snapshot or
large-pack performance.

See the [0308 physical-device comparison](maml-launcher-performance.md) for
transition costs and the visual trade-offs of static icon fallbacks.

`IconPackHandoffs.prepare` adds generated fancy resources when converted day
and night PNG bytes differ. It retains both ordinary PNG trees and preserves
original native modules unchanged. The generated-MAML archive validator is a
separate integration responsibility; this evidence does not establish admission
or import persistence. The earlier Dart flag-mutation candidate remains disabled
under `scripts/tests/theme-night/candidate/`.

## Evidence identity

Read-only inspection on October 31, 2026 targeted `emulator-5580`, running
`RELEASE-8.01.02.8593-260918-10241550-R` (`versionCode=801028593`).

| Artifact | SHA-256 |
| --- | --- |
| Installed launcher APK, matching `/tmp/keihome-launcher-8593/launcher.apk` | `a53ab31d3ca08ff16267385972d8c208176537964034256592dae132848b810c` |
| Loaded `/system_ext/lib64/librust_maml_sdk.so`, matching the offline library | `a428c5665f2e735c2cf8b8dad9154908972bbd722ffd4c6f515fc40554c66747` |

The research directory is `/tmp/keihome-maml-pair-contract/`. `identity.json`
records hashes, and `launcher-maps.txt` records the loaded MAML library.
The addresses below are evidence for these exact binaries, not production
constants or a portable runtime ABI.

## Launcher consumer path

1. `isMamlIcon` (`0x1a69428`) checks adaptive, animating and fancy paths.
   `getIconWidget` (`0x1a375e4`) selects them in the same order. Its fancy branch
   calls `IconCustomizer.getFancyIconRelativePath` at `0x1a378d4`, then
   `MamlIconsHelper.getFancyDrawable` at `0x1a37950`.
2. `ThemeIconLoader.getFancyIconRelativePath` (`0x1a37a6c`) builds
   `fancy_icons/<name>/manifest.xml`. It checks mapped names, an exact activity
   within the package namespace, and then a gated package fallback. The helper
   receives an exact native stem; it does not infer activity/package aliases.
3. `_hasRenderableDescriptor` (`0x1a37fec`) calls
   `ThemeResourcesSystem.hasFile`, `getFileStream`, UTF-8 decode and XML parse.
   It rejects an empty root; the generated manifest has two `Image` children.
4. `getMamlIconsResourcePath` (`0x1a3825c`) obtains the active icons package.
   `_FancyIconState._create` (`0x147ad88`) calls `MamlAppWidget.fromZip` at
   `0x147ae70` with that package and the fancy relative path.
5. The MAML ZIP reader loads resources within that relative root. The AVD's
   `/product/media/theme/default/dynamicicons` contains
   `layer_animating_icons/com.android.calendar/0/fancy/manifest.xml`, rooted at
   `<Icon>` with an `<Image src="bg.png">`; `bg.png` is beside the manifest.
   The prior Boundless archive also contains
   `fancy_icons/<package>/manifest.xml`.

Current read-only pool capture independently resolves PP `+0x41f40` to
`fancy_icons/`, `+0x41f48` to `/manifest.xml`, `+0xc880` to `.png`,
`+0xbd70` to `/data/system/theme/icons`, and `+0xf8` to the empty string.
The capture matches native call operands to current string objects; no Dart
pointer is retained or heap field modified by the converter.

Detailed evidence: `pool/anchors.json`, `dart/readers.txt`,
`consumer/readers.txt`, and `widget.txt` in the research directory.

Historical runtime corroboration is in
`/tmp/keihome-theme-anr/theme-apply-logcat.txt`: October 30 logs include
`MAML: fancy_icons/com.android.calendar build fromZip` and animating Settings
and other icons. Those logs corroborate native consumption of existing themes;
they are not acceptance of the new fixture on the current process.

## Dark-mode contract

- `ScreenElementRoot.load` (`0x5105a4`) handles
  `useVariableUpdater="none"`, then unconditionally creates the DarkMode updater
  from `0x510e88`. It constructs indexed variables `0x2f` and `0x30` at
  `0x510f38` / `0x510f58`, and adds the updater at `0x510fc8`.
- `GlobalStrVarName.name_of` (`0x46a690`) resolves those indices through native
  length and relative-address tables: `0x2f` is `__darkmode` (10 bytes), and
  `0x30` is `__darkmode_wallpaper` (20 bytes).
- `DarkModeComponentCallback.on_configuration_changed` (`0x47f860`) computes
  `(uiMode & 0x30) == 0x20`. The updater combines this with native settings mode
  and writes `__darkmode`, independently of the Dart icon-chain mode flag.
- `DarkModeVariableUpdater.on_notify` (`0x50d978`) updates variables and calls
  `ScreenElementRoot.request_update` through outlined helper `0x5f4b84`.
  Initialization and resume also refresh the variables. This provides a redraw
  event with `frameRate="0"`.
- `ScreenElementWrapper.load` compiles nonconstant `visibility` as a numeric
  expression at `0x463560` through `0x463598`. `is_visible_inner`
  (`0x4540e8`) evaluates it and tests greater than zero.

See `maml/readers.txt`, `maml-key-xrefs.txt`, and `maml-updater-proof.txt`.
The updater construction, variable-table decoding and redraw call establish
the contract beyond the presence of a `__darkmode` string.

## Converter contract

`NativeDayNightIcon.entries(nativeName, lightPng, darkPng)` returns:

| Path | Content |
| --- | --- |
| `res/drawable-xxhdpi/<nativeName>.png` | Unchanged day PNG fallback |
| `fancy_icons/<nativeName>/manifest.xml` | Generated declarative selector |
| `fancy_icons/<nativeName>/day.png` | Unchanged day PNG |
| `fancy_icons/<nativeName>/night.png` | Unchanged night PNG |

For a 192-pixel pair, the manifest is:

```xml
<?xml version="1.0" encoding="utf-8"?>
<Icon version="1" width="192" height="192" screenWidth="1080" resDensity="480" frameRate="0" useVariableUpdater="none">
  <Image x="0" y="0" w="192" h="192" src="day.png" visibility="1-#__darkmode" />
  <Image x="0" y="0" w="192" h="192" src="night.png" visibility="#__darkmode" />
</Icon>
```

There are no binders, network resources, scripts, timers or animation loops.
Inputs must be already-rendered square PNGs of matching size, 1..512 pixels
and at most 4 MiB each. Header/dimension checks are not a full untrusted-PNG
validator. Native names use the existing dotted-name and UTF-8 length checks.
The helper copies input bytes and performs no filesystem or network IO.

The caller owns aliases, filename collisions and archive limits. When ordinary
PNG trees have already been written, append only the three `fancy_icons/`
entries to avoid duplicating the normal fallback. Original native MTZ modules
must retain their own MAML and resources.

## Native policy limitation

`LiteFancyIconConfig.shouldUseMamlIcon` and `MamlIconChecker.batchCheck` restrict
MAML on lite/middle devices. The captured pool list at `+0x41fd0` contains
`com.android.calendar`, `com.miui.weather2`, `com.android.deskclock`, and
`com.xiaomi.calendar`. The current device-level classification was not
established by this investigation.

A denied package retains its static fallback. This converter does not override
native policy, and arbitrary community-icon coverage is not yet established.

## Tests and device evidence

The compact repository JVM test covers byte-preserving entry layout, the
declarative manifest, and rejection of unsafe names or incompatible PNG headers:

```bash
./gradlew :app:testDebugUnitTest --tests 'os.kei.hyper.home.iconpack.NativeDayNightIconTest'
```

The earlier standalone host harness and fixtures remain outside the repository:

- `/tmp/keihome-maml-pair-contract/run-host.sh`: broader host checks and fixture
  generation; it does not execute Android or native MAML.
- `/tmp/keihome-maml-pair-contract/fixture/fixture.mtz`: Settings package and
  exact `com.android.settings.MainSettings` activity, using the captured
  192x192 Arcticons gear pair. The normal fallback is the unchanged black gear.
- `/tmp/keihome-maml-pair-contract/fixture/fixture-clock.mtz`: a separate policy
  control assigning the same gear to `com.android.deskclock`, which is in the
  native lite/middle allowlist. That assignment is intentional diagnostic art.

The bounded device run used the generated Arcticons fixture and recorded:

- light -> dark -> light foreground artwork as black -> white -> black;
- unchanged launcher identity `PID 9685 / start 3565`;
- MAML construction for `fancy_icons/com.android.settings.MainSettings`;
- successful visibility on the desktop, Dock and folder path used by the
  fixture.

The native Apply operation that admitted the fixture also produced a separate
SystemUI gesture-monitor ANR on this AVD. SystemUI was restarted by Android,
while the launcher stayed on the same process during the later MAML cycle.
That responsiveness issue is recorded in `docs/anr-investigation.md` and is
not attributed to the MAML selector without a module-off comparison.

Remaining boundaries are package/device MAML policy, retained snapshots during
other transitions, and full-pack performance. The separate Clock fixture
under `/tmp/keihome-maml-pair-contract/` remains a diagnostic control for
devices that deny MAML to Settings.

## Identical artwork

Generated pairs whose day and night PNGs are byte-identical keep both ordinary
fallbacks. Ordinary artwork does not need a fancy descriptor. When a native
compatibility override or live palette requires one, it contains just
`day.png` and one base Image. Its visibility is `1`, or
`(1-#palette_enabled)` in a palette selector. Real day/night pairs, palette
masks, Settings observers and the zero frame rate are unchanged.

This avoids starting MAML for equivalent ordinary artwork without removing
system-fallback coverage. Validation accepts both canonical compact and
expanded forms when a descriptor is present. It still compares exact paths
and bytes; arbitrary MAML programs and mixed layouts are rejected by the
generated-resource validator. Existing applied packs are not rewritten in
place; regenerate and apply to receive the new output.

The November 7 physical snapshot contains 937 descriptors, of which 38 have
identical day/night artwork. This is a bounded reduction, not evidence that
all large-pack memory peaks or application-launch stalls have been resolved.
