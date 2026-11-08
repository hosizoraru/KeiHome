# Standalone native ZIP import report

## Sample and result

The user supplied `icons.zip` (SHA-256
`b75814b2670a9d441305abb4200c6ebf50d84e05bdcb657a518e7438e10de4ea`).
It is a standalone MIUI icons module, not an APK/appfilter pack or a complete
MTZ theme. Its name and extension are not used to choose its format.

- Compressed size: 74,910,464 bytes.
- Expanded payload: 100,638,169 bytes.
- 3,084 ZIP entries: 2,496 files and 588 directories.
- Roots: `fancy_icons`, `res`, and `transform_config.xml`.
- 583 MAML manifests; these include calendar/date behavior and animations.
- Two fancy-icon assets named `.png` actually contain WebP. Their bytes belong
  to the native module and are not rewritten based on their suffix.
- The current index exposes 582 valid native icon names. An unsupported native
  name does not cause its original files to be removed from the module.

The supplied file passed current archive admission, including CRC, path, entry,
expansion, and XML bounds. The unmodified installed Release
`1.5.0+g7311a70.b0296` imported it successfully on the Phone AVD. No production
compatibility fix was required to admit this sample.

## Device boundary

Only `emulator-5580` was used. The real file-picker flow added the sample to the
local catalog (582 mappings, 41 installed targets). A subsequent native import
completed and opened its system Themes detail page with an Apply button.

Automatic application was temporarily disabled for this test and then restored
to its original enabled state. The sample was **not applied**: the existing
ColorOS Classic ART+ theme remains active. The imported sample remains available
locally and in Themes; its latest receipt therefore represents an imported,
not-yet-applied theme. The temporary public Downloads copy was removed.

Launcher PID 9500/start 3785, SystemUI PID 9494/start 3783, system_server PID
8660/start 2869, and Themes PID 26876/start 34858 remained unchanged. No native
activation, core-process restart, physical-device installation, or theme reset
was performed.

This proves import into the current AVD's Themes environment. It does not prove
application or playback of every third-party MAML effect on a different device.
The original user's failure remains unconfirmed without that device's actual
error and logs; special icons alone are not evidence of a rejection.

## Regression coverage

`IconPackArchiveTest` includes a standalone-module fixture with directory
entries, component-style native names, a DateTime updater, BroadcastBinder,
CDATA, transform metadata, and opaque fancy-icon assets. It verifies native
copying preserves every byte without interpreting MAML commands.

The optional local-sample test imports a supplied file twice, checks deduplication,
copies its native module, compares indexed names, and verifies source and output
SHA-256. Third-party artwork is not committed into the repository:

```bash
KEIHOME_TEST_ICON_ARCHIVE=/absolute/path/icons.zip \
  ./gradlew :app:testDebugUnitTest --tests '*IconPackArchiveTest' --console=plain
```

All 14 tests passed with this exact sample, including existing malformed ZIP,
traversal, CRC, quota, nesting, and cancellation cases. The
`NATIVE_ANIMATION_PREVIEW_UNSUPPORTED` notice describes preview limitations; it
does not reject native import or strip dynamic resources.

Machine-local evidence is under
`.artifacts/research/native-zip-20261108/`, including `archive-audit.json`,
`archive-tests.log`, `native-theme-detail.png`, `final-identities.json`, and
`acceptance.json`.

## Follow-up evidence for an actual failure

Obtain the precise failing stage, installed KeiHome version, and error text.
Local file selection/catalog failures need App logs; system Themes import,
application, or Hook failures need LSPosed logs. Do not attribute an unobserved
failure to MAML or relax archive safety limits without reproducing the rejection.

## Follow-up: actual application and day/night test

The user subsequently authorized applying this sample on the Phone AVD to test
light/dark transitions. This exposed a failure **after successful import**:

- The system Themes detail page opened, but pressing Apply did not activate the
  imported component.
- Themes logged `downloadRights failed, serverResponseCode=408`, the message
  "you have not purchased this theme", and
  `Fail to buy resource because of empty product ID`.
- Its online authorization flow subsequently returned code 402 with a
  download-frequency warning. Further Apply attempts were stopped.
- The active provider continued reporting ColorOS Classic ART+, component
  `ba3bd1e5-d7b6-4f79-9b16-59a8c9568eac`. SHA-256 of
  `/data/system/theme/icons` remained
  `b1b6c7c428918497a99a177dbfc84e0d1c99d5c04955d79fc27778b400ffcb19`.

At that baseline, source inspection distinguished the paths: `IconPackHandoffs.prepare` assigned
`CONVERTED_APK`/`CONVERTED_CLASSIC_ZIP` only to generated APK/classic-ZIP
conversions. Native MIUI modules retain their resources and have no converted
origin. `ConvertedIconArchive` intentionally accepts only validated generated
resources, not arbitrary native MAML. Thus this sample did not receive that
converted-resource approval and remains subject to normal native rights checks.
This explains the current path without proving the original reporting user's
failure has the same cause.

No DRM behavior, origin marker, or safety admission was overridden to force a
successful test. Since the sample never became active, its desktop day/night,
folder, and foreground/background transitions remain **not tested**, rather
than passed or failed. All four recorded host process identities stayed
unchanged, and night mode remained enabled.

Evidence is in `.artifacts/research/native-zip-20261108/day-night/`, including
the Apply log excerpt, provider identity, source/active archive hash comparison,
screenshots, and `acceptance.json`. The temporary baseline archive was removed
after its hash comparison; the existing active theme did not need restoration.

## Local visual-module adaptation

Standalone `MIUI_ICONS` imports now use the distinct `LOCAL_NATIVE_ICONS`
origin. Full MTZ themes retain their existing handling. Origin alone is not an
approval: both native resource registration and subsequent application retain
the existing caller, user, token, resource/parent association, file identity,
and SHA-1/SHA-256 verification.

`LocalNativeIconValidator` checks the standalone archive with the original
ZIP/CRC/expansion/path/XML limits, then admits only local PNG/WebP artwork,
bounded transform XML, and the supported visual MAML vocabulary. This includes
day/night image selectors, date updates, visual animation commands, and a small
set of clock/configuration broadcasts. File/network/provider binders, external
references, arbitrary commands, store metadata and other theme components do
not qualify. Computed image paths must use verified local string variables or
the supported pure-expression grammar; indirect absolute paths, unknown string
functions and unverified image variables are rejected. The module's appended Settings-based selectors qualify only if
they exactly match the existing generated templates. PNG chunks and static
WebP containers are validated by content even when a filename is misleading.

This is an import-time check on existing workers, not a new renderer or a
per-frame task. No native payload, OEM version gate, global rights override,
new service, polling loop, or wallpaper/Monet policy was added.

### AVD acceptance

The signed development Release 304 passed normal API 102 Java reload with
the same Launcher, SystemUI, system_server and Themes identities. Its native
payload was identical to formal Release 296.

Updating the imported sample through its existing catalog action then:

- Completed native application automatically, with no purchase/download step.
- Changed the active component to `0c81b00d-1eec-4936-9ffb-a05b7518a94d`,
  title `KeiHome-import-probe.zip`.
- Preserved all 2,496 original files byte-for-byte in the actually applied icons
  archive, including all supplied MAML.
- Updated desktop and Dock artwork through dark -> light -> dark -> light.
- Kept light artwork after opening Settings from its actual Dock icon and
  returning Home.
- Showed correct light artwork inside the system-tools folder.

There is a remaining native cache boundary: the large folder's unopened cover
initially retained its old dark thumbnails after a light-mode switch. Opening
the folder refreshed them; subsequent light/dark transitions updated the cover.
The initial unopened-cover case is **not fixed by this application adaptation**.
No periodic redraw or forced Launcher restart was added to hide it.

The original ColorOS Classic ART+ component was reapplied through native Themes
after testing, and dark mode was restored. No physical device was used.
Final clean-build version and receipts are recorded in
`.artifacts/research/native-zip-20261108/local-apply/acceptance.json`.

### Validation

88 focused JVM tests passed, including the supplied archive, existing generated
conversion/registration/receipt cases, visual-MAML admission, malformed images,
unknown origins, external references, nonvisual commands, inappropriate
broadcasts, store metadata, and tampered generated Settings binders. Signed
Release assembly and `lintRelease` passed.

An initial preflight probe timed out during host build load; after the build,
the SystemUI main thread was observed in normal epoll wait and a complete
health preflight passed. Failed preflights did not install an APK and their
evidence was retained separately.

## Follow-up: calendar rules and folder-cover snapshots

The follow-up evidence is under
`.artifacts/research/native-zip-20261108/followup/acceptance.json`.
This checkpoint changes a development repair tool and an optional repaired
resource archive; it does not change the module's native or Java runtime.
The already accepted signed Release remains `1.5.0+gd9460fb.b0308`.

### Calendar weekday correction is specific to the supplied artwork

Both retained Rust MAML runtimes publish `day_of_week` as Sunday=1 through
Saturday=7. The supplied archive's `calendar_bg_1.png` through
`calendar_bg_7.png` instead contain Monday through Sunday. Its two calendar
manifests use `#day_of_week-1==0` to select image 1, producing Monday artwork
on Sunday. This is an authored expression/artwork mismatch, not an import
rewrite or evidence that the native weekday variable needs a global patch.

`scripts/repair-native-calendar-weekdays.py` requires the caller to explicitly
confirm Monday-first artwork and select calendar packages. It recognizes only
the complete paired seven-predicate layout used by this source, rejects
incomplete/ambiguous layouts, preserves every other entry, and never overwrites
the input or an existing output. It is not an automatic import transformation:
unrelated themes can intentionally use different weekday artwork conventions.

For this source:

```bash
python3 scripts/repair-native-calendar-weekdays.py \
  /Users/voyager/Downloads/icons.zip \
  .artifacts/research/native-zip-20261108/followup/icons-calendar-fixed.zip \
  --calendar-package com.android.calendar \
  --calendar-package com.xiaomi.calendar \
  --confirm-monday-first-artwork
```

The repaired archive changes only these two manifests' 14 weekday predicates
each. Date expressions, light/dark conditions, hands, images, and other
animations remain intact. Read-back of the actually applied system archive
matched all 2,496 source files against the repaired archive. Phone AVD
screenshots on Sunday November 8 show the correct Sunday/date artwork in both
appearances and the clock hands advancing. Seven-day predicate coverage and
archive preservation are covered by four Python regression tests; no device
clock was changed to simulate the other six days.

### Folder covers remain a separate native cache limitation

The follow-up reproduced a dark-to-light switch where the expanded folder's
icons were already light but its unopened cover retained dark thumbnails.
Opening and closing the folder updated that cover. Disassembly distinguishes
`MamlIconCache.getSnapshot` / `prepareSnapshotSync` and
`FolderIconGetxController.prepareMamlIconSnapshot` /
`updateMamlIconSnapshot` from the live icon content. This localizes the
observed lag to the cover snapshot path; it does not prove the complete
invalidation cause or a safe universal fix.

No polling, repeated theme application, forced activity reconstruction, or
global cache bypass was introduced to mask that issue. The stale-cover case
remains unresolved and must not be described as fixed by the calendar repair
or ZIP import support. A future native correction needs to authenticate the
snapshot ownership/disposal and post-render timing contracts.

Acceptance used only `emulator-5580`; Launcher PID 9500, system_server 8660,
and SystemUI 9494 retained their original parent/start identities. The existing
ColorOS Classic ART+ theme and dark mode were restored afterward. No physical
device, core-process restart, APK reinstall, or native activation was used in
this follow-up.
