# Apps browser and App appearance

## Current source

Status and Apps read the applied native Icon component, including mixed themes,
rather than treating an imported or selected catalog record as already applied.
Status includes the source title and directly matched installed-app count.

An explicit icon-theme status request can include a private preview snapshot key.
The authenticated system worker copies the active icons archive and missing stock
mod resources into the App cache. Active artwork takes precedence across density
variants and fancy-resource roots. Publication uses a completed-file marker,
bounded archive sizes and system-UID-only provider endpoints. The App requires
no root or shared-storage grant. Only the current and preceding completed
snapshots are retained.

The optional key extends the icon-theme report; older reports without it remain
readable. Source parsing runs on IO and is reused until the snapshot changes.

## Inventory and previews

- All installed packages include ordinary system apps and non-launcher apps.
  The explicit all-installed feature requires `QUERY_ALL_PACKAGES`.
- Runtime resource overlays are identified from the package's own manifest
  `<overlay>` element, hidden by default and optionally shown. Names containing
  "overlay" are not used as a heuristic.
- The three browser scopes are all installed apps, launcher apps and all pack
  icons. Pack scope includes resources without a directly matched installed app.
- Coverage and capability filters are separate from sorting. MIUIX cascading
  filter groups provide submenus, selection marks and separators; sorting has
  its own top-bar menu. Resetting filters preserves the chosen sort.
- Pack artwork supplies the original light/dark pair. Native PNG differences
  and KeiHome's generated MAML day/night pairs determine detected night support.
  Covered artwork does not inherit the APK's monochrome capability.
- Known generated MAML masks and backgrounds supply Monet previews. Unsupported
  scripts or animations show static fallback artwork and unknown capability.
  The App does not execute third-party scripts.
- A missing direct package/activity mapping is labelled original preview;
  arbitrary launcher aliases and dynamic rendered output are not inferred.

Search, list position and page form state survive pager navigation. Preview
loading remains limited to visible rows; existing APK and pack bitmap caches
are bounded. The current snapshot is a resource preview, not a screenshot of
every live launcher drawable.

## Appearance and navigation

Settings exposes MIUIX System, Light, Dark, MonetSystem, MonetLight and MonetDark,
plus Follow desktop Monet. The follow option observes the applied desktop Monet
state and uses its primary color as the MIUIX theme key. It retains an independent
light/dark preference. Settings observation is lifecycle-bound and runs without
background polling. Choosing an App palette does not enable desktop Monet.

The status overview uses explicit semantic container, text and accent colors.
Ready is green, warning amber and error red. Light/dark selection follows the
App appearance, including a forced App appearance different from system mode.
This keeps the overview visible with default MIUIX colors and Monet colors.

Page scrolling defaults to enabled. The default gesture is MIUIX
`PagerInterceptionMode.TapToHalt` (iOS-like), including its child-fling tracker.
Native and CrossAxis modes remain selectable. The pager uses the library's
gesture modifier and matching nested-scroll connection, with the same spring
navigation for bottom-tab selection.

## Validation, 2026-11-02

Release `1793570823` was installed on HyperOS Phone `emulator-5580`.
API 102 Java owner replacement completed without a native reload. Launcher
PID/start `9715/3611`, SystemUI `9696/3609` and system_server `8812/2684`
remained unchanged across installation.

- Eight focused browser JVM tests and two report compatibility tests passed.
- Release resource compilation, R8 and required lint passed. The AndroidTest
  APK also compiled; a full instrumentation suite was not run.
- AVD screenshots verified the ready overview with default light/dark and
  Monet light/dark App modes. These four screenshots used `1793570095`;
  the final build only changed generic filter-menu "All" labels afterward.
- The applied ColorOS Inspiration source showed 303 installed non-overlay apps,
  66 direct matches, 53 detected night icons and 37 detected Monet icons.
  Counts belong to this AVD/source snapshot, not a universal compatibility list.
- ErinAO's actual pack light/dark artwork and both Monet previews were checked.
- Installed desktop Monet flags remained enabled through the API 102 update.
- Showing overlays changed the count from 303 to 404; resetting filters returned
  it to 303. The independent sort menu showed the selected package-name order.
- With page scrolling disabled, a horizontal swipe stayed on Settings. With it
  enabled, the first horizontal swipe during a child fling stayed on Apps, the
  following swipe reached Icon packs, and a reverse swipe returned to Apps.
- Follow desktop Monet returned to ordinary dark MIUIX colors when desktop
  Monet was disabled. Final preferences are Follow desktop Monet, System
  appearance, scrolling enabled and iOS-like. Desktop Monet was restored off,
  matching the beginning of this UI session; the icon component was retained.

Local evidence, screenshots, build logs and deployment receipts are under
`.artifacts/research/ui-browser/`. Existing Flutter image ownership, watchdog
and SystemUI investigations retain their independent acceptance requirements.

## Compact cards and reverse ordering, 2026-11-02

Release `1793573694` compacts Apps without removing the four icon previews.
The scope and count share one row; the applied source and an accessible
explanation disclosure share the next. Preview notes are collapsed initially.
Each card retains the app name and package, moves user/system origin beside
the name, and groups artwork, launcher and capability metadata into wrapping
pills. Detected support uses a check, missing support a dash, and unknown
support an information symbol with a full accessible state description.
The fallback original-image caption uses an intentional line break.

On the same 1120-pixel-wide AVD, the vertical distance between the first two
app labels changed from 788 to 561 pixels, approximately 29% less. This is a
layout-density measurement, not a frame-time or performance benchmark.

The sort menu has separate groups for its rule and Reverse order. Reversing
inverts the complete selected order, including coverage priority. Filter reset
preserves both rule and direction. Choosing either sort control scrolls to the
start so a previously scrolled position cannot hide the new order's beginning.

Nine focused browser JVM tests passed, including explicit reverse expectations
for all four rules. Release/R8/required lint and API 102 deployment passed.
AVD checks covered disclosure, reversing from the middle of the list, retained
rule/direction after filter reset, and both App light/dark appearances. The App
was returned to its current default/System preference and normal name ordering.
The launcher, SystemUI and system_server identities remained unchanged during
deployment. Local evidence is in `.artifacts/research/ui-density/`.
