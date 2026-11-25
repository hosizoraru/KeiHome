# MAML icon cost during launcher transitions

## Physical-device comparison

The November 21 report describes recurrent dropped frames on an exported
KeiHome Apple icon pack, including outside KeiHome. The reporter removed
591 `fancy_icons/*/manifest.xml` entries and measured 17 versus 2 launcher
timeouts during app launch. The exact reported artifact is still needed.

The November 24 report supplied a screen recording and an LSPosed capture.
Perfetto comparison on the Phone AVD measured the weather launch phase at
about 885 ms with the stock dynamic archive and about 647 ms with the complete
imported archive. The recording therefore does not prove that KeiHome added
the stall. The imported archive still made Launcher parse the weather
descriptor repeatedly.

The HyperOS 4 weather descriptor is the only scanned dynamic group that
declares `ContentProviderBinder`. Its `DateTime.Minute` updater and weather
refresh commands are part of the source manifest. The November 24 generator
used the static weather counterpart to avoid loading that descriptor.
The `keihome-perfect-layers-6` contract restores the original `com.miui.weather2`
program using the same schema checks as native import. It keeps the original
queries, update timing and glyphs. Desktop icon batch refresh is a separate
optimization from evaluating the MAML program.

Local testing used a Xiaomi 17 Pro, HyperOS `OS4.0.8.0.XBLCNXM`, Home
`801030308`, and the installed KeiHome `113000646`. The display was set to
120 Hz and system dark mode was on. Each group ran four launches/returns and
four folder opens/closes per variant. The stock Themes apply screen attempted
to download related resources without applying the test archive. The controlled
runs used an atomically swapped local icon module and a fresh Home process for
each variant. The original icon module and the Themes catalog copy were
verified by SHA-256 after restoration.

The table shows original to experimental values. P95 is the median of four
per-action P95s for Home's VRI layer in the first 800 ms after input, measured
with Perfetto FrameTimeline. Late frames use `on_time_finish = 0`; none of
these rows contained the explicit `App Deadline Missed` classification.

| Sample | Launch P95 | Return P95 | Folder open P95 | Late launch frames |
| --- | ---: | ---: | ---: | ---: |
| Active ColorOS ART+, only 961 manifests removed | 11.92 to 5.39 ms | 6.22 to 3.43 ms | 9.96 to 8.79 ms | 4/300 to 0/269 |
| Archived applied Apple module, only 109 manifests removed | 6.42 to 6.16 ms | 4.85 to 3.78 ms | 10.02 to 8.92 ms | 1/275 to 1/268 |
| Older native `icons.zip`, 563 validated selectors flattened to fixed dark PNGs | 9.78 to 6.00 ms | 5.63 to 3.98 ms | 10.85 to 9.07 ms | 1/298 to 1/272 |
| Same older ZIP, only the Xiaomi Shop activity and package aliases flattened | 9.78 to 6.98 ms | 5.63 to 4.99 ms | 10.85 to 11.68 ms | 1/298 to 1/268 |

The first two comparisons preserved the payload bytes of every retained
entry, including the regular PNGs, night resources and transform config.
The fixed-dark comparison replaced only the regular PNG for each admitted
selector with its existing `dark.png`. It retained 20 descriptors, including
authored live content and entries without a validated static substitution.
The last comparison retained 581 of 583 descriptors. The launcher still
built other MAML icons, while reports of Xiaomi Shop entering `build fromZip`
went from eight to zero. That isolates a per-icon cost. The folder result for
that comparison varied and does not establish a benefit when only the
launched app's two descriptors are removed.

These measurements show additional work in transitions, with the largest
local frame cost in the palette-heavy module. They do not reproduce the
reported 17 late frames, and the small sample cannot establish a threshold
based on total descriptor count. The active module and the older ZIP launched
different apps, so compare values within a row. Trace files, marker windows,
screenshots, archive checks and the restoration receipt are in
`.artifacts/research/maml-launcher-performance-20261121/`.

## Producer and consumer paths

- The supplied older [`icons.zip`](native-zip-import-20261108.md) contains
  583 descriptors. Of these, 576 use only two full-size `Image` nodes and
  `#__darkmode` to choose between `common.png` and `dark.png`. The remaining
  seven have other programs, including calendar resources. The sample has
  no separate `nightmode/res` tree. The exact reporter artifact and its
  generator revision could not be identified from this sample.
- KeiHome's [Apple generator](apple-icon-pack.md) writes regular artwork
  snapshots and separate native layers for supported Calendar/Clock assets.
  Native publication can add other MAML descriptors after generation.
  `IconPackHandoff` and `IconPackExporter` use `NativeDayNightIcon` to select
  paired images and `NativePaletteIcon` to bind live palette state.
  `frameRate="0"` disables a requested animation rate, but the icon still
  enters MAML. A compact descriptor still incurs that cost when required for
  native compatibility or live palette binding.
- The [PerfectIconsPacker reference writer](https://github.com/YifePlayte/PerfectIconsPacker/blob/54412bbf3ceb75fe3663de5327d0da1132bbba76/app/src/main/java/io/github/perfecticons/domain/build/IconArchives.kt)
  writes ordinary layers to `res/drawable-xxhdpi`. It copies complete authored
  `layer_animating_icons` trees into the MTZ icon module or a separate module
  archive. Its archive writer does not synthesize `fancy_icons` selectors for
  every static image. This revision does not explain the older sample's
  generated selectors.

The installed Home APK matched the retained 0308 APK by SHA-256.
`ThemeIconLoader._hasRenderableDescriptor` checks the file, opens its ZIP
stream, decodes UTF-8 and inspects the parsed XML. `_FancyIconState._create`
can call `MamlAppWidget.fromZip` or reuse a retained widget.
`FolderIconGetxController.prepareMamlIconSnapshot` calls
`MamlIconCache.prepareSnapshotSync`. The logs confirm repeated ZIP builds at
launch and return for the tested components. The folder call graph identifies
a synchronous path to profile, but the measured difference does not attribute
all folder cost to snapshots. These are Home-side paths, which remain
available when using the exported module by itself.

## Shared fixes

- Ordinary generated pairs with byte-identical appearances do not need MAML.
  Recommended completion, ColorOS completion and Perfect static-night
  generation use the same policy as the main handoff. The ordinary day/night
  fallback entries remain present. System fallback and phone companions keep
  their compatibility descriptors where the platform can ignore flat overrides.
  A palette binding, genuinely different artwork, or an authored live program
  is not equivalent to a static image.
- Convert an imported appearance selector to Monet only after proving that it
  supplies two different resources. Identical source paths or encoded images
  cannot pass the existing two-tone proof, so they no longer enter bitmap
  decoding. Generated palette validation compares canonical XML and verifies
  image assets without rebuilding and copying a complete ZIP payload for each
  historical template variant.
- Reuse the archive reader while indexing and group Perfect's authored layers
  once before generating dark counterparts. When completing ColorOS artwork
  into a native archive, stage the derived payloads on disk instead of holding
  the entire completion in memory. These costs recur during import and
  generation, not on every launcher frame.
- Store newly generated, already encoded PNGs without another Deflate pass.
  Existing ZIP members keep their original compression method and unchanged
  payloads are still copied raw. In the 13,387-mapping MTZ sample, 26,838
  authored PNGs use ZIP compression; rewriting them all would trade around
  2.3 MiB of archive space for lower inflation cost without any evidence that
  those assets, rather than the 13,389 descriptors, dominate transitions.

## Candidate verification

The KeiOS-signed `113000650` Release was installed in place on the same Xiaomi
17 Pro with Home `801030308`. The read-back APK hash and unchanged native
payload were verified. Nine Java reloads were observed to succeed; this is
not a count of every possible target. The launcher, SystemUI and system server
retained their process identities during this candidate's normal apply tests.

- The installed Arcticons Material You APK exposed 49,252 source mappings.
  Its generated archive reached Home with 326 palette descriptors and 2,077
  stored PNGs. Two explicit palettes were verified on the launcher by pixel
  read-back.
- The 13,387-mapping HyperOS Monet MTZ and the older native `icons.zip` were
  reprocessed and applied from the app. The MTZ kept the compression of
  unchanged resources and stored all 1,204 newly added PNGs. The initial MTZ
  probe changed several settings together and the second capture had different
  colors than requested. An isolated replay writing only the packed MAML
  palette state matched blue, pink and blue again on the same icon, with
  settings read-back for all three. This verifies the archive's live binder,
  not the automatic wallpaper-to-palette transaction.
- The saved Apple ZIP was reprocessed and applied with the native clock
  programs still present. This is not the reporter's original 591-descriptor
  artifact. The broader offline audit also inspected installed APK samples,
  stock HyperOS, ColorOS ART+, Perfect and a layered MTZ.
- The Debug JVM suite ran 2,111 tests with eight conditional skips and no
  failures; Android test code compiled. Release assembly and lint completed
  with zero lint errors. Original theme, night mode and palette were verified
  after the physical tests.

`shared-pack-*` receipts and the archive inventory are in the evidence folder
linked above. These checks prove resource selection and application on the
candidate. The FrameTimeline table is a diagnostic comparison of test
archives, not a measured frame-time improvement for the signed candidate.

## Remaining acceptance

## Static ordinary-icon pilot on the Phone AVD

The attached HyperOS 4 KSU archive was inspected as a reference. Its ordinary
icon tree contains 2,443 pairs under `res/drawable-xxhdpi/<entry>/{0,1}.png`,
and its dynamic archive contains the calendar program. It does not contain the
reported two-second mode-switch service or a `fancy_icons` tree. The archive
supports the resource-level finding that a flat ordinary pair can avoid MAML
admission. Its mode-switch and cache-refresh behavior remains unverified.

KeiHome now exposes a separate, default-off **Fixed dark for ordinary icons**
option inside Perfect Icons' existing light/dark adaptation settings. With this
option, eligible ordinary entries publish the generated dark PNG at the
standard native path and omit the ordinary `fancy_icons` descriptor. Authored
clock, calendar, weather and other admitted dynamic programs remain in the
archive. The option is opt-in because a fixed dark image cannot follow a later
light-mode switch. The existing automatic option keeps system selection
behavior and its MAML compatibility resources.

The Phone AVD is HyperOS `OS4.0.31.0.XFRCNXM`, Home `801030308`, resolution
1120×2436. Validation used KeiHome `114000739`. The AVD imported all 22
discovered sources after the initial all-at-once run hit its 192 MiB Java heap
limit. The remaining coverage ran as one-source batches. All 22 then passed
import validation, followed by 24 real applications and a return to the
Default icon style. The restored night mode, user icon preference digest and
stock resource state matched the baseline.

The two generated Perfect variants both imported and applied. The fixed-dark
archive contained 34 ordinary MAML descriptors and the automatic archive
contained 98. Their 204 retained dynamic-layer paths were byte-identical.
The fixed archive was 6.8 MiB and the automatic archive was 22.1 MiB for this
51-mapping AVD generation. The launcher screenshots show the applied artwork
and the restored screenshot shows the Default style after cleanup.

The icon settings UI check covered the dynamic switch, the light/dark switch,
the conditional fixed-dark switch, and persistence while the conditional option
is hidden and shown again. The Monet suite passed 21 tests. The runtime release
gate passed for Arcticons Material You, Lawnicons and the HyperOS Monet icon
pack while toggling both palette seeds and day/night modes. The AVD returned to
`Night mode: no` with the stock icon style selected.

These AVD checks validate packaging, import, application and restoration. They
do not establish a universal launcher frame-time improvement or prove that a
standalone archive should enable fixed-dark by default. Reporter-device
FrameTimeline comparison remains required before changing the default.

Removing all descriptors is a diagnostic control. On 0308 it changed some
dark icons to the regular light PNG despite the preserved night PNG tree. It
also removes the live palette, date and clock program for affected icons.

For a standalone lower-overhead export, the fixed-color experiment supports
flattening only verified appearance selectors to the explicitly selected
light or dark variant. Keep authored programs and their resources. Check
activity/package aliases, icon masks and displayed scale before offering the
result. For system-following appearance, verify a native static path that
actually selects the dark resources on the target Home versions, or keep the
descriptors on disk and prove a launcher-side static path with narrow
recognition and invalidation on appearance, palette and icon theme changes.
An optimization that requires the KeiHome hook cannot fix standalone export.

An opt-in implementation of the system-following static path now swaps
derived fixed light/dark copies on appearance changes; see
[static-appearance-icons.md](static-appearance-icons.md). It has been checked on
one phone; FrameTimeline acceptance is still open.

Before changing defaults, repeat the same test on the reporter's ZIP and Home
version. Check the first launch after switching themes, repeated launch and
return, folders with mixed ordinary and animated icons, and live light/dark
and Monet changes. Compare both displayed artwork and FrameTimeline with the
same app, folder, refresh setting and applied module in each pair.

## Weather-specific follow-up

The stock HyperOS 4 Weather entry contains a quiet static plate and a fancy
layer. The fancy manifest uses `DateTime.Minute` and a
`ContentProviderBinder` for `content://weather/actualWeatherData/4`. The
pre-fix Perfect archive preserves both stock numbered PNGs and the weather
manifest, with the layer config adding `layer_only_quiet0=true` and
`layer_only_fancy1=true`. The November 24 generator kept the authored static
artwork when a validated static fallback and an enabled provider-backed fancy
layer coexist. Validation classifies the fancy layer from the XML it already
parsed. XML comments and fancy manifests belonging to quiet-only layers have
no effect on the decision. The publication identity was bumped so previously
generated archives are rebuilt on generation.

The supplied LSPosed log records the desktop launch handoff to
`com.miui.weather2` but contains no frame-drop or crash entry. On the Phone
AVD, repeated Perfetto runs measured the weather launch slice at approximately
885 ms for the stock archive, 647 ms for the complete Perfect archive, and
454 ms for a weather-only control. These numbers vary with cold-start state,
so they are used to compare the archive path. The November 24 Perfect output
omits the external weather provider and its live temperature and condition
artwork. The weather app and the separate launcher widget still display
their weather data.

The current HyperOS 4 source snapshot contains 20 dynamic package groups and 28
fancy manifests. Only `com.miui.weather2` contains a
`ContentProviderBinder`. The other 19 groups use local variables, triggers,
or animation commands and stay on the dynamic path.

### HyperOS Phone AVD and Xiaomi 17 Pro acceptance on 2026-11-24

Both devices ran the same debug QA APK (`115000758`), Home `801030308` and
Weather `18.0.1.4-R`. The HyperOS Phone AVD ran `OS4.0.31.0.XFRCNXM` and the
Xiaomi 17 Pro ran `OS4.0.8.0.XBLCNXM`. Both APK updates used the existing
signing identity and byte-identical native libraries. On the AVD, the
deployment receipts recorded ten successful Java API 102 hot reloads among
observed processes. Physical-device evidence covers the installed APK and
visible behavior. Native initialization was outside this gate.

Both devices generated the full HyperOS4 pack through the Perfect Icons UI
from the cached `fb8b5c9` snapshot with original dynamic icons enabled,
derived dark artwork disabled and the installed-app filter off. Both output
ZIPs were 84.35 MiB with SHA-256
`1f79f06df1eeefa182629e6d4705d3f0c05ddc7b42757128fdc52afb74ac08dc`.
The generated pack was imported and applied through the system theme route
on each device. The active archive's source marker matched that ZIP and its
hash matched the theme manager's local component.

| Timing | Phone AVD | Xiaomi 17 Pro |
| --- | ---: | ---: |
| Generate tap to output ZIP observed | 8.31 s | 5.82 s |
| Generate tap to UI ready observed | 13.94 s | 8.79 s |
| Theme Apply tap to active component hash observed | 2.26 s | 0.80 s |

Each generation figure is one observation with the source already cached.
The apply figure measures a local, already imported theme component.
Fresh import also performs native validation, completion and theme export.

For the frame comparison, the control added the original 20 weather paths
to each device's actual applied component. Every candidate path was
byte-identical in its control. Home was restarted and allowed to settle at
the start of each variant. Each variant used two process-cold and two warm
Weather launches from the same launcher icon. Cold trials stopped Weather
before tapping, and warm trials reopened it after pressing Home. The table
shows control to optimized, in milliseconds, for the median of the two
trials in each cell. First frame measures from the input marker to the end
of the first matching actual surface frame. P95 uses unique presented
surface frames starting in the first 1.25 s after the marker.

| Device | Weather start | Weather first frame | Weather launch P95 | Home launch P95 | Home first frame on return | Home return P95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Phone AVD | Cold | 539.0 → 487.3 | 272.2 → 238.3 | 232.6 → 166.2 | 505.2 → 355.2 | 274.7 → 247.8 |
| Phone AVD | Warm | 222.8 → 234.8 | 84.6 → 99.0 | 155.6 → 180.1 | 319.9 → 423.3 | 179.8 → 225.5 |
| Xiaomi 17 Pro | Cold | 167.7 → 156.4 | 12.2 → 12.1 | 11.2 → 9.4 | 39.5 → 37.7 | 7.3 → 7.1 |
| Xiaomi 17 Pro | Warm | 79.3 → 81.0 | 10.2 → 10.3 | 10.1 → 10.3 | 50.3 → 47.7 | 7.8 → 7.5 |

The physical cold-start samples show an 11.3 ms earlier first Weather frame
and a median maximum Weather frame duration of 42.0 → 33.2 ms. Weather app
deadline misses in those samples went from two to four. Home recorded zero
app deadline misses in both variants on the physical device. The AVD was
heavily deadline-limited and the warm samples went in the opposite direction
to the cold samples. The data supports retaining the scoped fallback based
on the removed provider work and the physical-device experience. The
sample size and AVD variance limit claims about general speedup.

Both generated and both applied archives preserved all 187 original files
across the other 19 dynamic groups byte for byte. The AVD recording shows
the clock hands moving, the launcher calendar showing the current date and
the clock and Weather launch transitions. The Xiaomi 17 Pro recording shows
Apple Music opening and animating back into its dock icon. These are visual
spot checks. The remaining groups were covered by resource comparison.
The AVD's theme completion also emitted Weather palette manifests that read
KeiHome's palette setting. Those manifests are separate from the removed
live weather-data layer and were present in both AVD variants.

The AVD returned to the stock icon pack and the phone to the original
component hash. Generation options changed for the run were restored.
The Debug build succeeded. The eight focused JVM suites passed 235 tests.
Six cases skipped because their optional local fixtures were absent.
The artifact directory `.artifacts/research/weather-acceptance-20261124/`
contains the frame traces, SQL, summary, archive comparisons, deployment
and apply receipts, and motion recordings.

### November 25 weather restoration

KeiHome b0782, signed with the KeiOS release key, was installed in place on
the USB-connected Xiaomi 17 Pro running `OS4.0.8.0.XBLCNXM`, Home `801030308`
and LSPosed 7948-log. Generating and applying the HyperOS4 pack through KeiHome
put all 20 weather resources back into the active icon component. The
temperature manifest and all of its glyphs matched the source bytes. The
existing Twitter custom layers matched the previously applied component.

The desktop showed `10°` in dark mode, with Perfect's extra dark adaptation
disabled. The recording captured opening Weather and animating back to Home.
Home's Rust MAML logs recorded the native `temperature` query during the
transition. The new component was left applied. This was a functional
check on one physical phone. No new frame-time benchmark or cold boot was
performed. The build, resource comparison, screenshot, and motion recording
are in `.artifacts/weather-live-20261125/`.
