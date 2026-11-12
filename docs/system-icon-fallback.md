# Icon completion and system fallback

The optional sharing/name rules are configured in [Recommended mappings](recommended-icon-mappings.md). Existing choices are preserved; generated themes require updating and reapplying after a change.

Custom icon packs now have an independent HyperOS system-app fallback choice:
Default (the default), Classic, or Boundless. It applies to ColorOS, APK, MTZ and
ZIP imports. Choosing a style does not apply a theme or change Monet by itself.
Generate the pack, then apply it through the native Themes detail page.

Perfect Icons is a separate completion option. Its explicit resources can cover
ordinary apps as well as system apps, including apps absent from the ColorOS
redraw collection. It works with a ColorOS primary pack, with another primary
pack plus ColorOS supplementation, and without ColorOS supplementation.

Enabling Perfect completion suspends the native system fallback selector. Turning
it off restores the previously selected native fallback, including after process
recreation. The remembered Perfect style is independent of the style currently
selected in Perfect resource management. Existing saved `PERFECT:<style>` choices
migrate without a download or a change to the active theme.

The page order is ColorOS supplementation, Perfect completion and style/resources,
native system fallback, then name matching. The original-icon preservation entry
remains before these settings. Perfect artwork is labeled completion in the app
list and applied-pack information; built-in HyperOS artwork retains fallback wording.

Enabling or selecting Perfect completion rechecks the local catalog and validates
the chosen style on a worker. Any usable downloaded style qualifies; HyperOS4 is
recommended rather than required. Missing resources leave the setting unchanged
and offer an OS4 dialog leading to resource management. Navigation never starts a
download. A deleted saved style remains visible with a missing-resource warning,
and returning from resource management refreshes the page.

The icon-pack home page can regenerate the currently applied pack when its
original source is known. It retains the original ColorOS style, ART+ choice and
appearance mode. If the original source cannot be identified, select it again
from the library. Native HyperOS themes retain their own behavior.

## Resource precedence

1. Exact component or package artwork supplied by the pack wins.
2. For an uncovered installed system app in an APK or converted ColorOS pack,
   optionally try the pack's artwork by application name. This is enabled by
   default under Icon packs > Completion and fallback. The app or launcher activity label
   is compared with resource names and a small set of equivalent OEM roles
   (phone manager, theme store, app/software store). Ambiguous artwork is rejected.
3. When enabled, use explicit ColorOS supplemental artwork for remaining targets.
4. Use the selected Perfect style's explicit static resources for remaining targets,
   or the selected native fallback for eligible system apps when Perfect is off.
5. ColorOS generic synthesis can still handle a Perfect miss. If a selected native
   preset has no supported resource, use native Default resources.
6. If no safe resource can be rendered, retain the existing launcher fallback.

Existing native light/dark and Monet adaptation, live clock/calendar handling,
explicit primary mappings, and original-icon exclusions keep their protection.
Perfect does not run the native Default renderer on a miss: its independent
setting and the native fallback setting are mutually exclusive. ColorOS remains
an independent supplement.

Without installed-only generation, the downloaded Perfect style's explicit static
names are included for future installations too, after original-icon exclusions.
Installed-only generation retains its existing restriction. No coverage counts
are hardcoded, and missing third-party icons never become eligible for native
system fallback merely because they have a package-name prefix.

System eligibility uses `ApplicationInfo` system/updated-system flags. It does
not use package-name prefixes. Ordinary user apps retain the previous handling.
Known OEM package identities only help index equivalent artwork and disambiguate
the HyperOS Security Center label `安全服务`; they do not make an app eligible for
name matching. Native MTZ/MIUI_ICONS sources keep their original resource logic;
additional completion resources are merged only into gaps.
Changing the switch requires regenerating and applying the converted pack.
Live clocks and calendars are excluded from static system fallback rendering;
existing native programs and ColorOS live templates remain responsible for them.

## Lookup cost and source lifetime

Each generation pins one local source snapshot and builds a bounded static-name
index. Lookups use that index, verified local blobs and at most one lazily opened
source reader. They do not parse the catalog, open Git, query refs, list a directory
or stamp Git on each icon. Package metadata is batched for Perfect completion
rather than queried once for every offline target.

Cheap file-identity guards reject removal or replacement during a generation.
End validation checks the catalog/source identity again and closes the reader.
Cancellation and unsuccessful generation close it too. Source changes do not
silently combine old and new icons in a published archive.

The generation source identity is now `system-fallback-v4`. Older v1/v2/v3 IDs
still resolve to their original imported source, but an existing generated pack
requires one regeneration/application to acquire the wider completion policy.
Changing a preference does not retroactively modify the applied theme.

Focused checks:

```sh
python3 scripts/checks.py icon-completion --run
python3 scripts/checks.py icon-completion-device --serial emulator-5580 --run
```

The built-in pack page offers direct entries to the Themes icon catalog and
Launcher icon-style settings. About shows the marketed device name (falling
back to `Build.MODEL`), device codename, model number, HyperOS build, Android
version and installed launcher version for support reports.
When available, it also shows `rust.runtime_version` from the running system
and the installed launcher's `flutter-version.txt` build asset. The latter is
the launcher's bundled Flutter version, not an Android-wide runtime. Missing
metadata is omitted rather than guessed.

The export target inventory includes installed system APKs without an Android
MAIN/LAUNCHER activity. On this HyperOS image that includes native frontends such
as Themes and Security Center. Resource overlays are excluded by parsing their
actual manifest.

Default reads the target firmware's stock `miui_mod_icons/dynamic/<name>/0..4.png`
layers, then raw adaptive APK resources, then stock flat PNGs. Layer composition
uses the native `IconCustomizerUtils.transformToAdaptiveIcon` arrangement.
Default and Classic use their native shape masks; Boundless retains its own
alpha silhouette. OEM input PNGs are bounded and decoded/re-encoded by Android,
without requiring the OEM's ancillary chunks to match our generated-PNG format.
These are static neutral-position renders, not a port of native parallax motion.

Classic and Boundless are optional PNG-only assets extracted from the inspected
native packs. This image provides 16 Classic quiet images and 120 Boundless
images. A missing preset resource falls back to Default. Missing preset assets
hide that choice rather than offering an unusable control.

## Native resource handoff

An ordinary PNG override alone was insufficient for several HyperOS native
frontends: the applied archive contained the correct Default Themes brush, while
the desktop still displayed the old Boundless artwork. Explicit generated
day/night MAML descriptors made these overrides effective. System fallback
entries therefore keep a descriptor even when both images are identical, in
every appearance mode. The descriptors use the existing fixed, zero-frame-rate
template; they do not execute imported scripts.

Native MTZ/ZIP augmentation preserves the uncompressed bytes of all original
entries. Any existing flat, fancy, animating or layered resource protects that
name, including programs and qualifiers we cannot preview. Only missing system
resources and their generated descriptors are appended. If no additions are
needed, the native archive remains unchanged. Existing native authorization
continues to apply; this does not authorize arbitrary imported MAML.

`keihome-system-fallback.txt` records the requested style and exact generated
filename stems. It is bounded, canonical provenance, not an authorization token.
Generated-archive validation still verifies PNGs, ordinary day/night trees and
the permitted descriptor templates. Apps previews display a system-fallback
badge and do not count these resources as the pack's own redraws or infer
unrelated APK monochrome support.

Native import receipts retain both source-to-latest-component and
component-to-original-source associations. Regenerating a source cannot erase
the identity of an older applied component. Legacy ColorOS recovery requires an
owned component, the exact generated title, a known built-in source, matching
Monet intent and one unambiguous original appearance configuration. Other
unknown sources are never guessed.

## Asset preparation

OEM bytes remain under ignored `.artifacts/`; they are not vendored into source:

```sh
python3 scripts/prepare-system-icon-fallbacks.py \
  --classic /path/to/native-classic-icons.zip \
  --boundless /path/to/native-boundless-icons.zip
```

The script writes deterministic PNG-only ZIPs, the native Classic mask, and input
and output SHA-256 provenance into `.artifacts/generated/assets/system-icon-fallback`.
Default resources are read from the destination firmware at runtime.

## Validation

Focused JVM coverage comprises 41 tests: source/style identity, import-source
resolution, fallback provenance, browser classification and generated-archive
validation. Fifteen instrumentation tests read real HyperOS resources and cover
all three styles, modern Themes/Security artwork, user-app and live-icon
exclusion, exact preservation of native art and unknown programs, reverse import
history, and a complete ColorOS handoff retaining the redrawn camera.

The complete ColorOS Default handoff on this device contains 38 fallback
filename stems, including non-launcher system apps. Counts depend on firmware,
installed apps and pack coverage. Actual desktop acceptance and final artifact
identity are recorded with the release evidence under
`.artifacts/research/system-icon-fallback/`.

Release `1793579801` passed native Themes application and desktop inspection of
all three choices on `emulator-5580`:

| Choice | Desktop result |
| --- | --- |
| Default | Modern pink Themes brush and dimensional green Security shield |
| Classic | Classic quiet Security artwork; Themes safely uses Default |
| Boundless | Geometric Themes artwork and the unmasked two-tone Security shield |

The applied archives contained 38 Default/Classic fallback stems and 42 Boundless
stems. Comparing the actual applied ZIPs found no changes to common non-fallback
resources, including the exact redrawn ColorOS camera PNG. The final Default
archive matches the earlier verified Default archive entry for entry. The Apps
page displays the real brush previews and `system fallback / Default` badge,
without claiming an independent night redraw or Monet mask.

The device was left on ColorOS Classic ART+ with Default fallback, with desktop
Monet still off. Launcher PID/start remained `9715/3611`, system-server
`8812/2684`, and SystemUI `9696/3609` throughout the final install and theme
switches. Installation used API 102 Java hot reload without native reload.

This is HyperOS Phone AVD validation, not physical-device acceptance or closure
of the historical native Flutter/watchdog investigation. No launcher,
system-server or SystemUI restart is part of this implementation.
