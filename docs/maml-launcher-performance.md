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
declares `ContentProviderBinder`. Its `DateTime.Minute` updater refreshes
`content://weather/actualWeatherData/4`. The generator now keeps the static
weather counterpart and omits that animated descriptor when both are present.
Calendar, clock, music and other local animation descriptors remain dynamic.
The static fallback keeps a useful weather image without a synchronous
provider query during Launcher construction.

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

Before changing defaults, repeat the same test on the reporter's ZIP and Home
version. Check the first launch after switching themes, repeated launch and
return, folders with mixed ordinary and animated icons, and live light/dark
and Monet changes. Compare both displayed artwork and FrameTimeline with the
same app, folder, refresh setting and applied module in each pair.

## Weather-specific follow-up

The stock HyperOS 4 Weather entry contains a quiet static plate and a fancy
layer. The fancy manifest uses `DateTime.Minute` and a
`ContentProviderBinder` for `content://weather/actualWeatherData/4`. The
Perfect archive preserves both stock numbered PNGs and the weather manifest,
with only the layer config adding `layer_only_quiet0=true` and
`layer_only_fancy1=true`. The visible weather artwork is therefore still the
original animated design. The new admission rule changes only the generated
archive when a static pair and a provider-backed descriptor coexist.

The supplied LSPosed log records the desktop launch handoff to
`com.miui.weather2` but contains no frame-drop or crash entry. On the Phone
AVD, repeated Perfetto runs measured the weather launch slice at approximately
885 ms for the stock archive, 647 ms for the complete Perfect archive, and
454 ms for a weather-only control. These numbers vary with cold-start state,
so they are used to compare the archive path rather than claim a universal
launch time. The provider-backed descriptor is still removed from generated
Perfect output because its synchronous external query is avoidable while the
static weather image remains available.

The current HyperOS 4 source snapshot contains 20 dynamic package groups and 28
fancy manifests. Only `com.miui.weather2` contains a
`ContentProviderBinder`. The other 19 groups use local variables, triggers,
or animation commands and stay on the dynamic path.
