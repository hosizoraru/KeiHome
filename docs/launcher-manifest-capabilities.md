# HyperOS Home manifest capabilities

The Xiaomi 17 Pro running `OS4.0.6.0.XBLCNXM` has Home `801030308`
(`RELEASE-8.01.03.308-261102-11171006-R`). Its installed APK hash matches the
retained 0308 fixture (`fccc89d61fbbda8d6a43e777c082443463b2898a41ad2c67101bc806d585f8f3`).
The APK declares `hasCode=false`. Launcher implementation evidence comes from
Dart AOT and its native bridge. DexSQL identifies the Java/Kotlin readers in
the paired system and OEM packages.

0308 has 34 `<application>` metadata declarations. The two
`com.miui.wallpaper.version_to_wallpaper` declarations both have integer value
`1`, leaving 33 unique application keys. Three component-scoped declarations
bring the raw Manifest total to 37. See the [Android metadata contract](https://developer.android.com/guide/topics/manifest/meta-data-element).

## Research status as of 2026-11-19

Independent folder material and freeform folder resize are deferred research.
KeiHome exposes neither as an app setting. The checked-in tools inspect
local launcher fixtures and exercise them offline. The device results below
record native system settings and isolated AVD experiments. KeiHome's
shipped material setting covers Dock and Search.

- [Folder material](#resting-folder-experiment) verified visible refresh
  from the system material selector. A KeiHome-specific override and
  authored-theme coverage remain open.
- [Folder resize](#phone-avd-visual-experiment) verified storage, occupancy
  and transient preview geometry. One uninterrupted drag, save and
  reload with correct geometry remains open.

## Folder Glass style

| Home fixture | Host declaration | Widget material notification | Folder Glass builder |
| --- | --- | --- | --- |
| Pad `801025490` (2026-08-07) | Absent | No corresponding symbol or `usingFolderGlassStyle` string in the matched AOT image | Present |
| Phone `801027722` (2026-09-04) | `true` | Present | Present |
| Phone `801029261` (2026-10-13) | `true` | Present | Present |
| Phone `801029263` (2026-10-13) | `true` | Present | Present |
| Phone `801030308` (2026-11-02) | `true` | Present | Present |

All 14 retained Phone manifests carry the flag. These fixtures establish that
the Phone contract was present by 7722. They leave the precise introduction
build open because the earlier available example is a different form factor.
Pad 5490 demonstrates that a launcher can render folder Glass without publishing
this widget capability.

The Phone AOT image contains the separate runtime key `usingFolderGlassStyle`.
`MiuiWidgetManager.notifyAllWidgetBlurOptionsForMaterialChange` calls
`_notifyWidgetSizeOptionsAsync` and has a targeted broadcast path through
`BundleImpl.putI32List` and `contextSendBroadcast`. The related widget option
senders build the default options, read `BlurUtilities.isMamlBlurSupported`,
add a boolean option, compare the options signature, and call
`AppWidgetManagerImpl.updateAppWidgetOptions` or its async variant. The AOT log
strings mention `usingFolderGlassStyle` in both option and broadcast contexts.
This is evidence of a launcher host capability and widget update protocol.
The exact external reader of `supportUsingFolderGlassStyleApi`, the broadcast
receiver behavior, and delivery of the runtime key to an installed widget
remain unverified. The sampled framework, settings, widget catalog, wallpaper,
assistant and companion packages yielded no exact DEX reader for the flag.

Three renderer paths matter for a folder appearance setting:

1. The resting preview uses `FolderBackgroundWidget` and its 1×1 and large
   builders. It selects native color backgrounds or theme resources through
   `RawIconCache.getDrawable`.
2. The light and press layer runs through `_FolderGlassLightWidgetState.build`
   → `FolderBlurUtils.buildFolderGlass` → `GlassToken.withPressLight`. The
   builder reads a native reactive value before choosing the tokens.
3. The expanded folder backdrop is managed by `FolderClingBlurController`,
   with `getStyleWithProgress` participating in the animated state. It has a
   distinct lifetime from the closed preview.

The existing KeiHome [material research](launcher-glass-material.md) already
found that a press-layer match alone leaves resting-folder pixels unchanged.
Its production resolver currently admits Dock and the search capsule. Folder
material should start at the resting background owner, preserving theme artwork,
folder type, tile geometry and animation ownership. A runtime profile can use
the complete builder, resource-fallback and refresh relationships and leave the
native path in charge when they are ambiguous. The Manifest flag is useful to
identify the widget protocol during diagnostics.

### System material selector

The Settings APK from `OS4.0.6.0.XBLCNXM` binds `bionics_mode` to 柔光玻璃
(Bionic Glass) and `classic_mode` to 轻透磨砂 (classic frosted material).
`MaterialPreference.pickEvents` writes the chosen mode to both Secure settings.

| State | Effective `material_style` | Saved `material_style_selected` |
| --- | --- | --- |
| Bionic Glass selected | `1` | `1` |
| Classic frosted material selected | `0` | `0` |
| Background blur turned off on a supported device | `-1` | Previous selection |
| Background blur turned back on | Restored from saved selection, with `0` as default | Previous selection |

`MiuiDisplaySettings.notifyBackgroundBlurStatusChanged` owns the last two
transitions. The physical device reported `background_blur_enable=1` and both
material values at `1`. Home 0308 registers a content observer and reads the
effective style in `BlurUtilities._refreshMaterialStyle`. It updates a reactive
Dart value on a change and refreshes native blur state. The separate widget
option and broadcast paths above still require widget-side validation.

### Reference projects and experiments

| Reference | Observed implementation | Local result |
| --- | --- | --- |
| [RuoGuang Glass](https://github.com/gjwjy/ruoguang-glass) `5d1a089` | `native/glass.c` is a loader shell with an empty effective patch table and hard-coded 4 KiB page arithmetic in its write path. The published APK contains `libhyperglass.so` with HyperGlass resolver and folder branch diagnostic strings. | The public shell compiled. The published APK passed signature and 16 KiB alignment checks. Its rendering recipe and exact kernel revision remain unverified. |
| [HyperGlass](https://github.com/finall1008/HyperGlass) `2abf2d3` | Generates locators from a reference binary and resolves current method boundaries, opcode and operand relationships, and call edges. Validates and enables feature groups independently. | The resolver returned the expected full feature mask on all 13 retained Phone images, including 0308. Pad 5490 resolved only the badge group. |

HyperGlass labels one recipe `Folder background: select native glass`.
The resolved site belongs to `ShortCutCommonWidgetMixin.buildBackground` on
7722, 9261 and 0308. It routes a shortcut widget background through
`FolderBlurUtils.buildFolderGlass`. A separate Android widget recipe
selects folder-derived presets for the widget host. For the 0308 shortcut
background, an ARM64 branch test exercised effective styles `-1`, `0` and `1`
with both outcomes of `BlurUtilities.isBlurSupported`. The stock branch reached
the Glass builder only at style `1` with blur supported. HyperGlass's returned
patch reached it at all three styles when blur was supported. All six original
and six patched cases matched. The same folder-related locator resolved on
Pad 5490, where its feature group withheld the patch. These are distinct from
`FolderBackgroundWidget`, the owner of the resting folder preview.

The HyperGlass icon, widget loader and transition adapter tests passed on the
host. Relocated Dart adapters ran in ARM64 emulation on 7722, 9226, 9261,
0308 and a synthetic shifted ELF. Eight negative cases confirmed that
incompatible layouts or ambiguous matches withhold the affected feature
group. The native material tests exercised simulated GPU copy ownership,
viewport crop geometry, bounded exports, and independent startup filtering
for backdrop streams. The macOS harness substitutes private VM reservation
and Android API shims. It uses retained local reference ELFs in place of the
upstream private CI bundle. These runs validate the resolver, instruction
plans and tested ownership rules. Android installation, GPU timing and live
visual effects remain open.

For KeiHome, start with the native effective material and blur capability.
Resolve the resting folder builder independently of shortcut widgets, the
press layer and the expanded backdrop. Restrict a future override to the
default background branch and preserve authored theme resources. A useful
first validation would compare 1×1, 2×2 four-item and 2×2 nine-item previews
under both system styles, then verify switching with the same folder already
mounted. Use a native update signal for changed material or theme and retain
the existing renderer when a candidate is ambiguous. HyperGlass's separate
current-boundary capture path is relevant if open or return transitions later
show stale material. It adds GPU work and has a separate acceptance surface.

### Resting folder experiment

The [offline resolver](../scripts/research/resolve-folder-background.py)
uses the executable-segment candidate scan and related-call validation seen
in MiuiBackGestureHook's `ResolveSide` (`ba8afe5`). It starts with a short
widget construction wrapper whose frame saves and restores geometry and
object arguments. It then checks that two independent callers:

- branch to the theme lookup or return their own native color background;
- share the theme provider, drawable lookup and theme wrapper;
- pass their own color builder's result into the themed path as a fallback.

The resolver accepts a single complete graph. Branch displacements, frame
slots and pool offsets are decoded or normalized. It reads the executable
ELF alone, with debug symbols used separately as the test oracle. All
seven expected methods matched on 13 Phone fixtures and Pad 5490. A relocated
executable segment and changed Dart frame layout also passed. Seven cases
with missing structure, inconsistent roots or duplicate graphs were
rejected. ARM64 emulation confirmed the two stock branch outcomes for both
builders on all 14 images (56 cases). The helper emits addresses for
inspection and has no runtime patch integration.

```sh
python3 scripts/research/resolve-folder-background.py \
  .artifacts/inputs/launchers/0308/libapp.so
```

On the physical Phone (Home 0308, PID `11256`), selecting classic frosted
material changed the closed 2×2 System Tools folder at the same desktop
position without opening it or restarting Launcher. Returning to Bionic
Glass matched the original pixels in the folder crop. Turning advanced
material off rendered the same folder with reduced blur. Re-enabling
restored effective and saved material to `1` and the visible Glass plate.
That capture was within 0.18 mean levels per color channel of the initial
crop. The closed folder on the original desktop page also changed during
the Glass/classic/Glass sequence.

A user-prepared page then allowed a foreground test with five footprints:
1×1, 1×2, 2×1, 2×2 nine-icon and 4×2. Only the effective material and
advanced-material Settings values were changed. The Launcher ActivityRecord
and launcher, SystemUI and system_server PIDs stayed the same throughout.
All five preview crops had changed by the first frosted capture after a
220 ms wait. The settled blur-off capture used the native fallback. All
five restored Glass crops matched the starting pixels exactly. The desktop
page and folder placements were preserved. This demonstrates visible
invalidation of the current page, while the identity of individual Dart
widgets was not instrumented.

The 2×2 variants share the large background builder. The 1×1 layout resolves
separately. Live authored theme artwork and live Pad behavior remain open.
A separate KeiHome preference should retain the native material
subscription, blur-off fallback and themed drawable precedence.

## Nine-item folder layout format

`support_parse_folder2x2_9=true` is present in all 14 retained manifests,
including Pad 5490. The related XML element is `folder_2x2_9`. Home 0308
ships it in `default_workspace_private_model.xml` and
`default_fold_workspace_private_model.xml`, with a 2×2 workspace footprint.
Other bundled workspace resources use `folder` and `folder_2x2_4` as separate
elements.

The Rust bridge in `libapp_launcher.so` has two relevant paths:

- `DefaultInitialDataProvider::parse` recognizes the XML tag and branches to
  `parse_folder`. The ordinary, four-item and nine-item branches supply
  different folder variants. The 0308 writer translates those variants into
  workspace columns as shown below.
- `CloudXmlDataProvider::parse` calls `quick_xml::de::from_str::<xml_model::Item>`.
  Its serde tag visitor recognizes the same three tags as distinct variants.
  `InitialDataGenerator::process_item` and `process_folder_unified` handle the
  typed data. For 0308, the provider returned by
  `OtherParser::get_mishow_cloud_config_parser` uses this parser, as confirmed
  by its vtable relocation.

| XML tag | Workspace footprint | 0308 default-parser `itemType` |
| --- | --- | --- |
| `folder` | 1×1 | `2` |
| `folder_2x2_4` | 2×2 | `21` |
| `folder_2x2_9` | 2×2 | `22` |

These IDs are observed values for the 0308 default writer. The serde variant
ordinal is a different number and must not be persisted as an `itemType`.
The 0308 Dart renderer also distinguishes
`FolderLayout2x2Grid4` and `FolderLayout2x2Grid9`. Their `itemsMaxCount`
methods return 7 and 12 respectively, so the 4/9 in the format names should
not be used as a limit on folder contents.

The ARM64 serde visitor ran in isolation against the exact 0308, 9261 and
Pad 5490 images. Each accepted the three supported tags as distinct variants
and sent `folder_2x2_8`, `folder2x2_9` and `folder_2x2_90` to the error path.
This verifies tag classification and leaves XML placement, activity mapping,
and full import behavior for integration testing. Exact searches of Home's
native payload, Themes, Theme Store, Backup and Cloud Backup did not locate
a reader of the manifest key itself. The sample therefore establishes a
format capability and its implementation, with the flag's outside consumer
still open.

A first feature could preview and validate workspace layouts, preserving
the folder kind, width and height, child order and unresolved components.
Ordinary, four-item and nine-item folders should stay distinct during
Phone/Pad conversion. Grid collisions, hidden or missing apps, and custom
folder artwork need their own preview states. A future writer can use an
export/import round trip on a disposable layout and validate the target
profile before attempting any launcher changes.

### Interactive resize and placement

Home 0308's long-press Edit sheet has a horizontally scrolling size picker.
The accessibility tree exposed seven entries on the physical Phone:

| Workspace footprint | Picker variants |
| --- | --- |
| 1×1 | Standard folder |
| 2×1 | Horizontal folder |
| 1×2 | Vertical folder |
| 2×2 | Four-icon and nine-icon previews |
| 4×2 | Ten-icon and eighteen-icon previews |

The numbers describe the preview layout. The four-icon and nine-icon
options occupy the same cells, as do the ten-icon and eighteen-icon
options. The nine-icon folder on the test page reported its selection
through accessibility. Selecting four-icon, ten-icon and eighteen-icon
previews worked in the sheet. Cancelling preserved the original folder.

The picker and corner handle have different responsibilities:

- The picker selects a folder item type. Confirmation calls
  `FolderIconConvertSizeUtil.convertFolderSize`, which maps the type to
  spans and searches for a legal cell position. Its placement path can
  search the last screen and insert a new workspace screen.
- The handle chooses among native layout strategies.
  `_spanToDefaultItemType` recognizes five footprints, with one default
  preview per footprint. `4×1`, `1×4`, `2×4`, `3×2` and `3×3` use the
  same default path as `1×1` at that mapping site. `4×2` already has a
  native strategy.
- A device/configuration condition in the span helpers selects a
  different mapping featuring two additional types, `55` and `56`, at
  `2×1`. The current Phone picker showed the seven regular choices.
  The selector for the alternate branch and its live UI remain open.

### Grid-based resize experiment

The handle selects a span in `_getFolderTypeBySize`. `_finishDrag` uses
that span to look up a layout strategy, dispatch the end event and animate
the corner radius. `_dispatchEvent` resolves both the old and the new
strategy for the transition. The subscriber, `doCornerDragEnd`, changes
the type, writes the folder spans, adjusts the horizontal anchor for RTL,
clamps to the current grid and calls `updateFolderInDatabase`. It also
synchronizes geometry with the shortcut menu. The cell controller
reconciles occupancy through `updateCellAfterFolderSizeChange`, including
the squeezed-drop path. `_clampFolderSpanWithinGrid` moves the anchor
inward on overflow without changing the spans. A span larger than the
whole grid stays oversized after that helper. `hasEnoughRoomForResize`
compares the sum of occupied areas with grid capacity. Free resize needs
both dimension checks and coordinate-based collision checks before commit.

0308's `favorites` schema stores `spanX` and `spanY` independently of
`itemType`, with children linked by `container`. The Dart save path goes
through `LauncherModelManager.updateItemDatabase` and the Rust bridge's
item codec. `ItemInfoModel.fixInvalidSpan` repairs nonpositive spans and
preserves positive pairs. `FolderIconGetxController.folderIconSize`
computes each axis from the corresponding stored span, cell dimension
and edit scale on the workspace path. The preview strategy still controls
icon positions, visible count, padding and parts of the transition.
The outer size can therefore be tested independently of the preview.

The host-only [resize resolver](../scripts/research/resolve-folder-resize.py)
uses the bounded call-graph approach from MiuiBackGestureHook `ba8afe5`.
It relates the five-case span mapper to the handle's size reads and both
of its end-of-drag call sites. It compares object-pool relations across
callers and requires a unique graph within executable segments. Symbols
are used by the checker as ground truth. The resolver scans executable
bytes without a launcher version or address table. This resolves the
stock handle graph. A second scan relates the four preview metric reads
to the initializer and updater that own the same strategy field. It
identifies the per-folder factory return before the controller publishes
the strategy and checks the compressed pointer store and write barrier.

The [offline checker](../scripts/research/check-folder-resize.py) passed:

- Twelve symbol comparisons and 24 native span-mapper cases per image across
  13 Phone launchers and Pad 5490. The mapper returned the standard
  strategy for `4×1`, `1×4` and `2×4`, and the wide strategy for `4×2`.
- Seven `fixInvalidSpan` cases and eight native `folderIconSize` cases on
  0308. The size cases exercised all four requested spans with two sets of
  cell metrics and edit scales. The harness supplies controller values
  and allocation, and the original code computes and stores the result.
- Seven native grid-clamp cases with a supplied 4×6 grid config. All of
  them preserved `spanX` and `spanY`. Overflow moved the anchor inward,
  including an oversized 5×7 case that remained oversized at `(0, 0)`.
- Code and object-pool relocation, rejection of five damaged graphs,
  exclusion of a non-executable graph and rejection of ambiguous resize
  and preview graphs.
- Round trips for the four requested spans in an isolated database built
  from the actual `favorites` schema. Reopening retained the dimensions,
  preview type, child and neighboring item. Additional cases covered
  transaction rollback, occupied cells, grid edges, RTL and a wider grid.
  This SQL experiment exercises the schema. Launcher startup, backup
  import and the Rust writer were outside the execution harness.

An interactive implementation can keep the existing folder ID and type,
using the current workspace grid for the selected footprint. For a
nonstandard rectangle, derive a preview layout from both spans and the
available inner area, then use the same geometry for the resting
background, preview icons, hit regions and open/close animation. Keep
the stock picker variants and their theme resources for preset sizes.
The handle's `FolderHelper.getLayoutStrategy` consults a shared map, so
custom metrics must be owned by the folder or drag session.
During a drag, snap each axis independently and keep the last valid
rectangle when the user's anchor, a grid edge or an occupied cell
prevents growth. At release, revalidate against current occupancy and
publish the saved geometry to the cell controller, shortcut menu and
cached previews together.

#### Phone AVD visual experiment

Home `801030308` on HyperOS `OS4.0.31.0.XFRCNXM` loaded all four
footprints in an isolated Android user and marked the corresponding cells
occupied. The workspace reloaded the same folder ID and its three children.
With only the database spans changed, the previews and tap regions still
followed their stock type. A bottom-right drag with a debugger adjustment
at the final clamp also saved `4×1` through Home's normal writer. During the
drag, occupancy and the resize handle were still using the `4×2` preset.

Shrinking only the per-folder strategy height and padding left the old
preview icons overhanging the body and title. The preview layout consumes
cached items with width, height and two origins. Both the desktop preview
and the open/close transition read them. Changing these items and the
body before the first preview bind corrected the overflow.

Use native strip layouts as the visual baseline. Centering and shrinking
the entire icon group made the first prototype look sparse. The original
`2×1` and `1×2` presets keep icons near the start of the strip, including
the compact cluster for additional apps. They provided the following
three-child comparisons:

| Footprint | AVD result |
| --- | --- |
| `4×1` | The horizontal preset supplied icon size and spacing. The wide controller retained the full-width label, centered under the glass. Tapping the empty extension opened the folder and closing returned the same icon placement. |
| `1×4` | The vertical preset kept the full-size previews at the top. A first cold run showed partial preview binding. The repeat showed all three icons on the first settled screen and after opening and closing from the extension. The cause of the intermittent binding failure remains open. |
| `2×4` | The four-icon preset kept the compact top-left arrangement. The longer native glass and centered title were visible before paging. The empty lower region opened the folder, and the preview returned on close. |
| `4×2` | The stock wide strategy displayed all three previews. Opening from the empty region and closing preserved the native layout. |

The display experiments use transient geometry on a single lab folder at
a time. The Phone AVD ran KeiHome `112000745`. The isolated user was
stopped after testing and the AVD returned to user 0. All 71 owner
workspace records matched the baseline for ID, title, intent, type,
containment and placement. The owner launcher, SystemUI and system_server
kept their process identities. See `visual-owner-restored.txt` in the
local evidence directory.

#### Resuming the resize research

1. Trace the proposed span into `onCornerDragUpdate` before native
   occupancy and neighbor displacement. Keep the stock picker types
   distinct from custom spans. Verify cancellation and a rejected
   collision before attempting a saved change.
2. Locate the per-folder preview invalidation path. `updateFolderSize`
   calls `FolderLayoutStrategy.resetPositions`; `getPreviewItemInfo`
   reads those cached positions. `_buildLayout` also reads strategy
   dimensions directly, and `getPreviewIconRects` and
   `calOriginPreviewIconLoc` consume preview geometry for transitions.
   Carry the same validated footprint to these consumers and the title
   constraints. Leave the handle's shared preset map alone.
3. On an isolated workspace, verify immediate redraw, tap bounds, opening
   and closing in motion, persistence and restoration after a cold
   launcher start. Capture the first settled screen before paging. Test
   the intermittent preview binding separately from geometry, then
   exercise denser folders, RTL, grid changes, Pad and Fold layouts.

Local evidence lives in
`.artifacts/research/launcher-folder-resize-avd-20261119/`:

| Question | Evidence |
| --- | --- |
| Do the spans persist and occupy the requested cells? | `settled-*-storage.txt`, `settled-*-launcher.log`, `drag-custom-persistence.txt` and `drag-custom-commit.log` |
| Which paths own the visual geometry? | `geometry-owners.asm`, `visual-refresh-flow.asm`, `visual-strategy.asm` and `folder-animation-geometry.asm` |
| How did the prototypes compare at rest and during transitions? | `4x1-before-after.png`, `4x1-title-motion.mp4`, `repeat-1x4-comparison.png`, `repeat-2x4-comparison.png` and the corresponding `repeat-*-motion.mp4` recordings |
| What passed offline? | `resolver-visual-matrix.log` |
| Was the original desktop restored? | `visual-owner-restored.txt` |

## Other paths worth investigating

| Capability | Code path and implications | Practical investigation |
| --- | --- | --- |
| MiShow cloud layout | The 0308 MiShow path constructs a `CloudXmlDataProvider` over a `workspace` element. The setup code inspects existing AppWidget host IDs and can delete selected IDs through `AppWidgetHost`. | Reuse the layout model for a standalone preview. Establish the actual external delivery and ownership checks before considering live import. |
| Split divider background V2 | The retained HyperOS WM Shell's `SoScUtilsImpl` reads the Home flag through `checkHomeVersionSupportDividerBgV2`. `supportDividerBackground` gates the split background path. `initOrUpdateDividerBackground` controls a distinct surface's color, alpha, crop and parent, and the transition code carries that surface separately. | Follow the background surface through split entry, divider drag, rotation and exit on Pad/Fold. The layer is a concrete place to investigate dark/light seams or flashes. Confirm the matching Shell on the target device before customizing it. |
| PersonalAssistant blur and stacks | PersonalAssistant reads both Home flags. Home has a reciprocal stack compatibility check and a separate widget option update path. | Compare host capability, selected material and delivered widget options. A controlled widget fixture can separate a missing host signal from a provider choosing another background. |
| Home shortcut wakeup | The retained framework's `InputFeature.supportBlockCombinationKey`, Settings' Circle to Search controller and VoiceAssist read the same version with different conditions. Settings accepts Home version 3 or higher for its switch check. | Diagnose whether a gesture issue comes from the selected Home, framework or assistant. Keep the version observation tied to the individual feature check. |
| Store progress and wallpaper | Market explicitly checks the download provider flag. MiWallpaper reads two versions, one from Home metadata and one from framework settings. | Extend Desktop tools only with signals that help explain a failure, such as a wallpaper handshake or missing Home progress route. Measure actual callbacks separately. |

The mixed-content picker, `synShortcut`, and desktop-mode flags still lack
confirmed direct readers in the inspected packages. The presence of laptop
mode and widget code in Home does not establish which device-side gates enable
them. The picker flag is present on the retained Phone builds and absent on
Pad 5490, making this a compatibility question for the exact picker and Home
pair before it becomes a feature proposal.

## Application metadata inventory

The reader column distinguishes direct reads, related implementation evidence
and entries whose specific consumer is still open. Class names are from the
exact installed packages and can change between builds.

| Application key | 0308 value | Reader or implementation evidence |
| --- | --- | --- |
| `miui.screenOrientation` | `1.unspecified,2.portrait,3.unspecified` | Window management reads the same key in `MiuiOrientationImpl.retrieveScreenOrientation`. `MiuiMultiWindowUtils.getRequestedAspectRatio` also references it. |
| `support_mixed_content_picker_version` | `1` | Declaration also present on Phone 7722 and 9261. Absent on Pad 5490. Exact host and picker negotiation remains open. |
| `supportPaidWidget` | `true` | Themes `PackageUtils.k` reads the active Home package capability. PersonalAssistant `MamlConfigUtil` also references the flag. Catalog entitlement and UI checks remain separate. |
| `HomeShortcutKeyWakeUpVersion` | `6` | Settings `CircleToSearchController.isHomeSupportCircleToSearchSwitch` accepts `>= 3`. VoiceAssist has V3/V4/V5 and PTT handoff checks. InputFeature also references the version. Phone 7722 and Pad 5490 advertise `5`. |
| `support_transmit_gesture_state_by_content_provider` | `true` | VoiceAssist `fp0.a.isDesktopSupportBack` checks the Home capability. Home has a gesture provider and `updateGestureStateToRemote`. |
| `supportSplitDividerBgV2` | `true` | The retained HyperOS WM Shell reads it in `SoScUtilsImpl.checkHomeVersionSupportDividerBgV2` and consults it when managing the split divider background surface. |
| `multiFingerGestureAdaptationGameToolbox` | `1` | Security Center's game gesture controller reads the value from Home and checks `>= 1`. |
| `hyperos_package` | `true` | Android package parsing `RustPackageImpl` and `RustPackageInfoUtilsImpl` use it for the HyperOS package extension. Settings also uses it when identifying HyperOS packages. |
| `hyperos_app_lib_name` | `libapp_launcher.so` | Parsed by the same Rust package path. Identifies the launcher bridge library alongside Flutter's `libapp.so`. |
| `flutterEmbedding` | `2` | Embedding declaration. This APK uses the native HyperOS package path and has no embedded application DEX. |
| `auto_density` | `true` | Density-related declaration. Exact reader and scaling behavior remain open. |
| `support_download_progress_by_content_provider` | `true` | Market `DesktopProgressManagerMiui.isDesktopSupportContentProvider` checks it. Home declares `LauncherDownloadStateProvider`. |
| `supportServiceDelivery` | `true` | PersonalAssistant `ServiceDeliveryCapability.checkServiceDeliveryCapability` looks up this capability on a supplied package. |
| `miui.home.supportMiuiPin` | `true` | Settings `SettingsFeatures.isSupportScreenPinning` reads the capability for the screen-pinning feature. |
| `miui.supportFlipFullScreen` | `1` | `BoundsCompatUtils.getFlipCompatModeByApp`/`ByActivity` and `WindowManagerServiceImpl.getFullScreenValue` read the key for flip-screen compatibility. |
| `support_parse_folder2x2_9` | `true` | Both workspace XML paths recognize the distinct `folder_2x2_9` tag. The 0308 default writer assigns a 2×2 footprint and `itemType=22`. The flag's external reader remains open. |
| `ignore_rro_relaunch` | `true` | `ActivityRecord.shouldRelaunchLocked` and `relaunchActivityLocked` consume the key for OEM overlay-related relaunch decisions. |
| `synShortcut` | `true` | Shortcut synchronization candidate. Exact reader remains open. |
| `supportPABlur` | `true` | PersonalAssistant reads Home directly (`g5.a.a` and `k6.j.b`). Launcher `ApplicationConfig.getSupportHomeBlur` also references this capability. |
| `supportStackWidget` | `true` | PersonalAssistant reads the Home flag. Home AOT has the reciprocal `ApplicationConfig.checkPASupportStackWidget` path for PersonalAssistant compatibility. |
| `support_overlay_window_handle_gesture` | `true` | VoiceAssist `fp0.a.isDesktopSupportOverlayWindowGesture` checks the Home capability. |
| `miui.home.settings.simplified` | `true` | Settings search status and `SettingsFeatures.isNeedRemoveEasyMode` consult it for menu/search visibility. |
| `support_new_home_new_dynamic_effect` | `true` | New Home dynamic effects candidate. Exact reader remains open. Launcher has separate capability logic for feed effects. |
| `support_mishow_cloud_layout` | `true` | Home's MiShow parser factory returns a `CloudXmlDataProvider` over `workspace` XML. The external reader and delivery contract remain open. |
| `com.miui.wallpaper.version_to_wallpaper` | `1` (twice) | `MiWallpaperApplication.onCreate` reads Home's integer protocol version and separately obtains the framework wallpaper version. |
| `com.xiaomi.mirror.relay_dock` | `true` | Xiaomi Mirror `relay.F0.p` reads it when checking relay surfaces. |
| `xiaoai.supportGestureLineWakeUp` | `true` | VoiceAssist `isHomeAppSupportGestureLineWakeUp` reads it for gesture-line wakeup capability. |
| `com.xiaomi.mirror.relay_recents` | `true` | Xiaomi Mirror checks it in the same method, with Dock or Recents sufficient for that capability result. |
| `supportMiuiDktMode` | `true` | Desktop-mode declaration. Exact consumer and device eligibility remain open. |
| `supportWidgetsInMiuiDktMode` | `true` | Desktop-mode widget declaration. Exact consumer and widget constraints remain open. |
| `supportUsingFolderGlassStyleApi` | `true` | See the widget and renderer traces above. |
| `com.hyperos.AI_ACTION_DESCRIPTION` | `res/raw/aiaction_ddf_data.json` | Four actions in the resource: open desktop settings, open PersonalAssistant settings, toggle PersonalAssistant, query its state. |
| `miuiwidget.global.compat.version` | `1` | The literal is present in launcher AOT. Exact regional compatibility check remains open. |

| Component metadata | Owner and evidence |
| --- | --- |
| `android.support.FILE_PROVIDER_PATHS` | FileProvider XML resource for URI path configuration. |
| `app_tools_summary` | Service metadata for `APP_TOOL_PROVIDER`. OSBot and VoiceAssist read `ServiceInfo.metaData` while discovering providers. The text describes desktop settings tools. |
| `com.hyperos.AI_ACTION_PROPERTY` | Provider metadata pointing to `res/xml/aiaction_data_activity.xml`. Its four action types match the JSON description. The declarations do not establish that an arbitrary caller can use the provider. |

## Feature investigations

1. **Independent folder appearance.** Test native closed-preview colors first
   on 1×1, large four-item and nine-item folders in light and dark modes. Include
   a theme with custom folder artwork and a recording of open and close. A
   suitable refresh must update already mounted previews without changing
   folder items, layout or launcher PID.
2. **Glass-aware widgets.** On a dedicated AVD, use a diagnostic widget to check
   host options at bind, resize and material change. The standard Android
   callback is `AppWidgetProvider.onAppWidgetOptionsChanged`. Compare the payload
   on 0308 and the older Pad build before depending on the OEM key. A verified
   signal could let a KeiHome widget choose an appropriate background without
   adding the widget package to hook scope. See the [Android widget callback](https://developer.android.com/reference/android/appwidget/AppWidgetProvider#onAppWidgetOptionsChanged(android.content.Context,android.appwidget.AppWidgetManager,int,android.os.Bundle)).
3. **Desktop compatibility diagnostics.** For the handful of protocols relevant
   to a feature, show the advertised capability alongside the resolved runtime
   path and the last observed outcome. Gesture, wallpaper, and widget failures
   then point to the appropriate owner. Package metadata can be read on demand
   and refreshed on launcher replacement.
4. **Folder layout preview.** Start with parsing and preview, where fixture
   coverage exists for ordinary and large folders. Treat any live restore as
   a separate operation because workspace placement and AppWidget ownership
   are involved.

## Reproduction

The local evidence directory `.artifacts/research/launcher-metadata-0308/`
contains the APK hashes and declarations by build (`manifest-matrix.json`),
the exact DexSQL readers and disassembly (`consumers.json`, `reader-methods.smali`),
and the ARM64 call relationships for 0308, 9261, 7722 and Pad 5490
(`*-folder-calls.txt`). The initial inventory used local fixtures and read-only
device inspection. The later material and resize experiments are recorded
separately above. Widget option delivery remains unverified.

The `folder-layout/` follow-up contains workspace tag counts, parser and
provider disassembly, `typed-tag-results.json` from the three ARM64 visitors,
the additional Themes/Backup scan, and the retained WM Shell reader and
surface traces. The exact flag string was absent from the earlier physical
SystemUI and plugin scan. Pairing the retained Shell path with the current
physical ROM and exercising the split transition remain open.

The `glass-references/` follow-up contains the exact Settings APK, decoded
`settings-*.smali` methods, material and caller disassembly, HyperGlass resolver
logs and ARM64 plans, and `test-material-branch.py` with its results. Device
inspection was read-only. The two reference repositories were inspected at
their recorded commits.

The `folder-material-experiment/` follow-up contains the host checks
`check-profiles.py` and `emulate-owner-gates.py`, their logs and the 14 resolved plans.
It also contains `system-tools-material-compare.png`, the same-page
`fixture-all-materials.png` and `fixture-live-results.log`. Size evidence
includes picker accessibility snapshots, `0308-folder-size-trace.asm`,
`0308-folder-handle-and-picker.asm` and `folder-span-emulation.log`. The
device finished the material experiment on the user-prepared page with
the initial material, blur values and folder placements. The later
resize experiment records its results in `free-resize-check.log`, with
the related native traces in `0308-folder-*.asm`.

From the repository root, inspect the retained ARM64 0308 fixture with
the APK and ELF identities in the [0308 compatibility notes](launcher-0308-compatibility.md):

```sh
python3 scripts/research/resolve-folder-resize.py .artifacts/inputs/launchers/0308/libapp.so
python3 scripts/research/resolve-folder-resize.py .artifacts/inputs/launchers/0308/libapp.so --preview
```

Run the offline matrix with Python 3.12, Unicorn 2.1.4, the retained
launcher ELFs and debug sidecars, and a local launcher database snapshot.
The checker reads the snapshot schema and creates its test rows in an
isolated temporary database:

```sh
uv run --python 3.12 --with unicorn==2.1.4 python scripts/research/check-folder-resize.py \
  .artifacts/inputs/launchers --nm /path/to/llvm-nm \
  --layout-schema .artifacts/research/launcher-folder-resize-avd-20261119/owner-before.db
```
