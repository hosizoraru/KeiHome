# ColorOS native dynamic icon contract

## Converter and handoff protocol

This protocol is independent of `NativePaletteIcon`. It admits local raster
resources into two fixed KeiHome templates, never source XML, fonts, scripts,
binders, native code, or arbitrary MAML.

Use the existing source-manifest entry, including entries excluded from static
artwork:

```json
{
  "component": "com.android.calendar/com.android.calendar.homepage.AllInOneActivity",
  "dynamic_source": {
    "icon_names": ["ic_launcher_calender"]
  }
}
```

The other accepted single name is `ic_launcher_clock`. Preserve the source
spelling `calender`. No additional JSON template tag is needed: the caller maps
these two exact names to `NativeColorOsDynamicIcon.Kind.CALENDAR` and `.CLOCK`.
Unknown or multiple source names do not select a dynamic template. Package and
activity aliases remain the caller's responsibility.

The converter writes these exact sidecar paths into the classic ZIP:

| Source name | Required paths |
| --- | --- |
| `ic_launcher_calender` | `coloros-dynamic/ic_launcher_calender/day.png`, `coloros-dynamic/ic_launcher_calender/night.png` |
| `ic_launcher_clock` | `coloros-dynamic/ic_launcher_clock/day.png`, `coloros-dynamic/ic_launcher_clock/night.png`, plus the six clock PNGs listed below under that same root |

The profile's source ZIP supplies these bytes. Strip the exact sidecar root
before passing them as `assets` keys. Do not pass sidecar paths or manifests
as map keys. Both backgrounds must be square local rasters without baked dates,
weekdays, or hands. In particular, the source package's `rec_night.png` and
`recfg.png` foregrounds contain fixed glyphs/hands and are unsuitable live
backgrounds. Preserve the following clock files from the two source ZIPs,
under `fancy_icons/ic_launcher_clock/images/drawable-xxxhdpi/` in each:

| Asset key under `coloros-dynamic/ic_launcher_clock/` | Source ZIP and original filename |
| --- | --- |
| `clock_day_bg.png` | `icons-rectangle`: `clock_day_bg.png` |
| `clock_day_hour_rectangle.png` | `icons-rectangle`: `clock_day_hour_rectangle.png` |
| `clock_day_minute_rectangle.png` | `icons-rectangle`: `clock_day_minute_rectangle.png` |
| `night_clock_day_bg.png` | `icons-darkrectangle`: `clock_day_bg.png` |
| `night_clock_day_hour_rectangle.png` | `icons-darkrectangle`: `clock_day_hour_rectangle.png` |
| `night_clock_day_minute_rectangle.png` | `icons-darkrectangle`: `clock_day_minute_rectangle.png` |

All six are required for CLOCK. The dark archive uses the same original
filenames with different raster bytes, so the `night_` prefix is the generated
namespace for the actual night artwork. Do not substitute a tint of day artwork.
The two narrow 20x240 hand files without `_rectangle` are different assets.

Main may add `fallback.png` and `fallback-night.png` as an optional pair, rendered
from the actual current date/time or an explicitly identified Android drawable
snapshot. Record the capture timestamp in conversion/export provenance when
using a current-time snapshot. These two files are fallback inputs, never
referenced by live MAML. If absent, ordinary PNG fallbacks use the supplied
backgrounds; this deliberately makes no static date/time claim.

The API is:

```kotlin
NativeColorOsDynamicIcon.entries(
  nativeName = exactNativeAlias,
  kind = NativeColorOsDynamicIcon.Kind.CALENDAR, // or CLOCK
  assets = relativeFilenameToPngBytes,
  fixedDark = null, // null follows system; false forces light; true forces dark
  dim = false,
)
```

The helper owns no preview clock, application lifecycle, timezone, Android
drawable lookup, or filesystem IO. The main renderer owns previews and optional
current fallback snapshots. Appearance selection must never replace the live
template with a preview or change its `DateTime.Minute` updater.

## Native contract evidence

Offline inspection on November 2, 2026 used these local inputs:

| Artifact | SHA-256 |
| --- | --- |
| `.artifacts/inputs/framework/librust_maml_sdk.so` | `a428c5665f2e735c2cf8b8dad9154908972bbd722ffd4c6f515fc40554c66747` |
| `.artifacts/inputs/coloros/uxicons/hdpi/icons-rectangle` (ZIP) | `532d0ede5ef1cbe2f67e810178c7b3c5df0e3cdae0b7da78df47fb90c63ea9c9` |
| `.artifacts/inputs/coloros/uxicons/hdpi/icons-darkrectangle` (ZIP) | `278e4890505a59f1177f5c1912179c3cc6d2b739abb5feae08f6cdc69df99e97` |
| ZIP `fancy_icons/ic_launcher_calender/manifest.xml` | `5b180a2ee3271069f3270f239468f118298883ff6bd4509f3c9b68c7fd274caf` |
| Dark ZIP `fancy_icons/ic_launcher_calender/manifest.xml` | `46ac25aff784c75cd30eb216752626a6fee941f0c59bedd03ddb4c80783f9822` |
| ZIP `fancy_icons/ic_launcher_clock/manifest.xml` | `258fe4c72a803e3a285dfa4b2be909c97bb31741521916d6a7429f48d65f6d22` |
| Dark ZIP `fancy_icons/ic_launcher_clock/manifest.xml` | `686488892f3247d2120472a88c56c3e93ce4bdcb689c225a25ecef9adc1d944a` |

The target ELF build ID is `dd010dce0c2714b438637cb3ba94a24b922ae495`.
Its ordinary symbol table is stripped. XZ-decompressing its existing
`.gnu_debugdata` section recovers the local Rust symbol names; no downloaded
symbols or copied OEM implementation is used. Addresses below refer to this
binary's virtual addresses, not file offsets, production constants, or an ABI.

### DateTime, Text, variables, and rotation

| Contract | Target evidence |
| --- | --- |
| `DateTime` is an actual supported element | `ElementGroup.on_create_child` compares the `DateTime` tag at `0x4d89e4`, then constructs `TextScreenElement.new_impl<DateTimeScreenElement>` at `0x4d91e4`, targeting `0x5048d4`. |
| `format` and `textExp` are parsed | `text_formatter.from_element` (`0x50447c`) reads `format` at `0x504514` and `textExp` at `0x50452c`; `0x504548` builds the text expression. `TextFormatter.get_text` evaluates the current string expression at `0x4ab4cc`, rather than storing a conversion-time date. |
| The weekday format uses the live calendar | `DateTimeScreenElement.do_tick` calls `DateFormatter.get_text` at `0x4b7294`; initialization also calls it at `0x4b7338`. The formatter reads current time and calls `Calendar.format` (`0x440a40`), including the ordinary-format path at `0x4b7a64`. `Calendar.format` recognizes pattern characters using the table at `0x1e5db4` and consumes repetitions at `0x440e80`; `E` is a recognized entry. This is the native formatting path for `EEEE`. |
| `#date`, `#hour12`, and `#minute` are real indexed variables | `GlobalStrVarName.name_of` (`0x46a690`) loads lengths from `0x2086f0` and relative string addresses from `0x208930`. Decoding these tables gives `0x04=hour12`, `0x05=hour24`, `0x06=minute`, `0x07=second`, `0x09=month`, `0x0a=date`, and `0x10=day_of_week`. `DateTimeVariableUpdater.update_time` (`0x48ff0c`) reads system time, updates its calendar, and writes indexed values; the updater tick at `0x491b44` invokes it. |
| Image rotation and pivots accept numeric expressions | `AnimatedScreenElement.new<ElementGroup>` reads `angle`/`rotation` through helper `0x5ebf14`, calls `create_numeric_exp` at `0x4d6138`, then parses `centerX`/`pivotX` and `centerY`/`pivotY`. `create_numeric_exp` (`0x4c1300`) calls `expression.build_numeric` at `0x4c134c` and `0x4c1384`. The Image constructor uses the same machinery. |
| Rotation is evaluated during rendering | `get_rotation` (`0x476f9c`) calls `PropertyWrapper.get_value` at `0x476fb4`. That getter evaluates `NumericRootExpression` at `0x433af8`. Rendering calls `get_rotation` at `0x476610` and obtains the pivots at `0x476e84` / `0x476e90`. |
| The clock arithmetic is supported | The numeric expression evaluator at `0x43400c` implements floating division (`0x434568`), addition (`0x4347f0`), and multiplication (`0x434800`). The generated expressions are `#hour12*30+#minute/2` and `#minute*6`; no expression is evaluated or cached by the exporter. |

The source 1x1 calendar uses `DateTime format="EEEE"` and
`Text textExp="#date"` on a 150x150 logical canvas. Its field positions and
sizes are retained. Independently generated fields preserve the scalar source
colors: weekday `#0A7FFB` in both modes, date `#000000` by day and `#FFFFFF` by
night. The source `SysSans-En-Regular.ttf` is not bundled: the target's default
sans typeface is used, so glyph metrics can differ. The unavailable `line.png`
is not a dependency.
The clock retains the source 1x1 hand formulas and source full-canvas rasters,
using equivalent top-left positioning and a 75,75 rotation pivot. No enlarged
1x2, 2x1, 2x2, second hand, animation, or source `<Var>` is translated.

### Minute updater, zero frame rate, and lifecycle

The manifest explicitly requests `DateTime.Minute` for both kinds. The source
calendar's hourly cadence is narrowed to minutes so a live calendar does not
depend on an animation or on a later reimport to cross midnight.

| Contract | Target evidence |
| --- | --- |
| `DateTime.Minute` activates the updater | `ScreenElementRoot.load` reads `useVariableUpdater` at `0x510794`, splits its tokens, and compares `DateTime`. `0x5108b8` calls `Accuracy.from_str` (`0x48f4e0`); its comparison against `Minute` at `0x1f515b` yields enum value 2. `0x510b48` constructs the selected `DateTimeVariableUpdater`, then `0x510b60` adds it to the updater manager. |
| Updates have a native minute timer | `check_update_time` (`0x490220`) uses its accuracy jump table at `0x1392b6`. Accuracy 2 selects `0x4903fc`, loading 60,000 ms. `0x490414` / `0x49041c` align the current time to that interval; the next deadline is the aligned time plus one interval. |
| Redraws work at `frameRate="0"` | An interval change calls `ScreenElementRoot.request_update` at `0x49047c`. A positive next delay creates a timer and posts it at `0x490574`. Its callback at `0x490a88` upgrades a weak reference and calls `check_update_time` again at `0x490aa8`. This is an independent, bounded minute wakeup, not a requested continuous frame rate. |
| Initialize/resume refresh actual time | `init` (`0x48fb2c`) invokes `update_time` and then `check_update_time`. `resume` (`0x491b70`) clears the paused flag, refreshes current values, requests a redraw at `0x491e4c`, and rearms the deadline at `0x491e68`. `DateTimeScreenElement.resume` (`0x4b7358`) resets its calendar. |
| Pause/finish do not keep an active timer chain | `pause` (`0x491eb4`) sets the paused flag at `0x491ec8` and pauses notifier listeners. `finish` (`0x491ee4`) sets the finished flag at `0x491ef8`, unregisters listeners, and cancels the root timer at `0x491f20`. `check_update_time` tests finished/paused at `0x490234` / `0x490280` before scheduling another wakeup. |
| Native time notifications refresh without reimport | `on_notify` (`0x49231c`) checks the same lifecycle flags, calls `update_time` at `0x4923ac`, and checks/rearms the deadline at `0x4923c4`. |

The same library hash and native dark-mode observer were inspected in
[native-day-night-maml.md](native-day-night-maml.md). Its `__darkmode`
configuration observer requests redraws at zero frame rate as well. This work
reuses that declarative observer and introduces no native hooks or process
restarts.

### Appearance and original night artwork

`fixedDark=null` follows `__darkmode`; `false` selects day artwork in both
branches; `true` selects night artwork in both branches. With no dimming,
forced modes use constant branch visibility. With dimming, branch visibility
continues to follow system dark mode so DIM can react without interrupting
date/time updates.

`dim=true` multiplies RGB by 214/255 only in the system-dark branch:
backgrounds use `tint="#FFD6D6D6" tintmode="13"`, preserving alpha.
This is brightness multiplication, not `alpha="214"`. Calendar field colors
apply the same rounded per-channel multiplier: blue becomes `#086BD3`, black
stays black, and white becomes `#D6D6D6`. Main's DIM_AT_NIGHT appearance passes
`fixedDark=false, dim=true`, retaining day artwork in both modes.

The source clock's night face and hands are read from `icons-darkrectangle`,
whose manifest references the same original names but whose raster bytes differ
from `icons-rectangle`. When artwork is dark, every clock layer selects the
corresponding `night_` asset. The generator preserves original day and night
RGB/alpha artwork; it adds no grayscale, SRC_IN replacement, or surrogate
contrast tint. DIM alone adds mode 13 to all visible raster layers in the
system-dark branch. Main should pass `fixedDark=false` only when the user's
appearance explicitly selects day artwork, including DIM_AT_NIGHT.

The target Image constructor parses `tint` at `0x4e09d8` through
`ColorParser.new` and parses numeric `tintmode` at `0x4e0a48` / `0x4e0a84`.
Its tick evaluates the mode at `0x4afb50` and calls
`get_porter_duff_mode_int` at `0x4afb58`. The native mode parser
(`0x4a4bf4`) maps `MODULATE` to 13; its comparison
loads `MODULATE` at `0x4a4d88` and returns 13 at `0x4a4d94`.

## Canonical output and validator integration

All generated roots have exactly these attributes, in generator order:

```xml
<Icon version="1" width="150" height="150" screenWidth="1080" resDensity="480" frameRate="0" useVariableUpdater="DateTime.Minute">
```

Each root has two flat appearance branches, each in drawing order:

| Kind | Per-branch children |
| --- | --- |
| CALENDAR | `Image` background; `DateTime` at x=75/y=20/size=26, format `EEEE`; `Text` at x=75/y=43/size=76, textExp `#date` |
| CLOCK | `Image` background; `Image` clock face; `Image` hour hand with angle `#hour12*30+#minute/2`; `Image` minute hand with angle `#minute*6` |

All Image positions are x=0/y=0/w=150/h=150. The two clock hands have
centerX=75/centerY=75. Both calendar fields align center and use bold=true.
The weekday color is `#FF0A7FFB`; the date color is `#FF000000` for day artwork
and `#FFFFFFFF` for night artwork. DIM applies the corresponding rounded
214/255 value to each field independently. Night clock image sources prefix
each of the three day filenames with `night_`.

The exact output paths are:

| Output | Content |
| --- | --- |
| `res/drawable-xxhdpi/<nativeName>.png` | Copied `fallback.png`, or `day.png` when absent |
| `nightmode/res/drawable-xxhdpi/<nativeName>.png` | Copied `fallback-night.png`, or `night.png` when absent |
| `fancy_icons/<nativeName>/manifest.xml` | Canonical UTF-8 XML declaration, root, flat children, terminal LF |
| `fancy_icons/<nativeName>/day.png`, `.../night.png` | Unchanged input background bytes |
| `fancy_icons/<nativeName>/clock_day_bg.png`, `.../clock_day_hour_rectangle.png`, `.../clock_day_minute_rectangle.png` | CLOCK only; unchanged day ZIP source raster bytes |
| `fancy_icons/<nativeName>/night_clock_day_bg.png`, `.../night_clock_day_hour_rectangle.png`, `.../night_clock_day_minute_rectangle.png` | CLOCK only; unchanged dark ZIP source raster bytes |

CALENDAR returns five entries; CLOCK returns eleven. Optional fallback inputs
do not become fancy resources or additional files. Every output raster has an
independent copy of its input bytes. Callers that already emitted ordinary
PNGs must reconcile those two exact fallback paths before merging the map.
The optional fallback pair is supplied after any caller-side appearance and
brightness processing; this byte-preserving helper does not recolor snapshots.

The only accepted map keys are the two background names plus, for CLOCK,
the six named source clock PNGs. The two optional fallback keys must appear
together. Everything else, including paths, fonts, XML, and additional clock
hands, is rejected. Each PNG must have the expected signature and IHDR header,
square dimensions 1..512, and at most 4 MiB. These are header checks, not a full
untrusted-PNG decoder. Background dimensions must match each other; the six
clock face/hand rasters must match each other; optional fallback dimensions
must match each other. The three groups may have different resolutions: the fixed 150x150
logical canvas scales source 240x240 artwork independently of a 192x192 preview.

`GeneratedIconTemplateValidator` / `ConvertedIconArchive` integration belongs
to the other agent. Match by regenerating through this helper, rather than
broadening a general MAML allowlist. For a fancy resource root, gather the exact
required raster keys and regenerate its manifest for the two kinds and six
parameter combinations:

```kotlin
for (fixedDark in listOf(null, false, true)) {
  for (dim in listOf(false, true)) {
    val canonical = NativeColorOsDynamicIcon.entries(nativeName, kind, assets, fixedDark, dim)
    // Compare manifest bytes with canonical's exact manifest.xml bytes.
  }
}
```

This remains twelve bounded candidates across both kinds. Normal fallback
snapshots are outside the live manifest and should be validated as ordinary
PNGs. The validator should enforce the exact fancy resource set for the
matched kind; there are no binders, URLs, font paths, arbitrary expressions,
unknown tags/attributes, scripts, timers declared by XML, or children under
the leaf elements.

## Verification and remaining device acceptance

Run the portable JVM tests without writing to an AVD:

```bash
./gradlew :app:testDebugUnitTest --tests 'os.kei.hyper.home.iconpack.NativeColorOsDynamicIconTest'
```

Tests cover live calendar fields and hand formulas, all twelve template
variants, forced appearance plus native night dimming, original day/night clock
asset selection, independent source calendar colors, exact filenames, byte
preservation and independent buffers, optional paired fallbacks, incompatible
sizes, and rejected extra capabilities. The final run passed all 10 tests
with zero failures/errors.

The local research and host fixture directory is
`/tmp/keihome-coloros-dynamic-contract/`. It contains the decompressed symbol
ELF, annotated native disassembly, generated calendar/clock XML, and
`dynamic-fixture.icons`. The final fixture has 16 entries and uses the actual
local day and night source face/hands and day background bytes plus a locally
rendered night gradient matching the dark manifest's scalar colors.
`fixture-proof.json` records manifest hashes and closure checks. All PNGs
decoded successfully at 240x240; each of the six clock PNGs matched the
corresponding source ZIP bytes exactly. The generated calendar contains the
verified independent weekday/date colors, and neither manifest references an
unprovided image. No source artwork or generated fixture is added to the
repository.

The native parser/updater contract is established for the hashed target.
Host fixture generation and XML/PNG inspection establish resource closure;
they do not execute Android or the native renderer. No AVD state was written
by this work. Main's device acceptance still needs to confirm both clock hands
move across a minute, date/weekday cross midnight or a controlled time change,
resume catches up after suspension, light/dark/force/dim remain live, and night
glyphs remain visible. The existing package/device MAML policy boundary in
`native-day-night-maml.md` still applies; a static fallback cannot establish
live date/time behavior.

## Main AVD integration result

The subsequent `2026-11-02` HyperOS run imported/applied the complete local
Classic profile. Calendar showed the real Monday/2 rather than the source's
baked 28; clock hands matched 01:35, 01:40 and 01:48 as time advanced. Both the
large-folder preview and the open folder selected the actual source light/dark
assets. No process restart occurred. Receipts and screenshots are indexed in
[coloros-port.md](coloros-port.md).

This accepts the tested follow-system path and normal minute updates.
Midnight/date rollover, controlled timezone changes, and all forced/dim
appearance combinations still have host-contract coverage rather than a
complete device matrix.
