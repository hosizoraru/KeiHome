# MIUIX OS4 migration

## Version and source boundary

The current dependency and scroll/overlay follow-up are documented in
[the 2026-11-14 migration](miuix-19b5b831-migration.md).
The historical 2026-11-12 migration below pinned every module to `0.9.4-9d4b9c85-SNAPSHOT`, published
from upstream revision `9d4b9c85c18707c7da12cf8c497ea57955fcf81e`.
The revision was checked against the upstream `main` branch and the published
Maven publication on 2026-11-12. Do not use Maven's `latest` field alone:
the repository metadata lists hash-based snapshot names lexicographically.

Primary references:

- [README](https://github.com/compose-miuix-ui/miuix/blob/9d4b9c85/README.md)
- [AGENTS](https://github.com/compose-miuix-ui/miuix/blob/9d4b9c85/AGENTS.md)
- [Material guide](https://github.com/compose-miuix-ui/miuix/tree/9d4b9c85/docs/guide)
- [Glass components](https://github.com/compose-miuix-ui/miuix/tree/9d4b9c85/docs/components/glass)
- [Glass example sources](https://github.com/compose-miuix-ui/miuix/tree/9d4b9c85/example/shared/src/commonMain/kotlin/component)

The [2026-11-12 follow-up](dependency-upgrades-20261112.md) records fractional
blur scaling, the removed pager mode and window-coordinate popup migration,
plus the independent native dropdown and safe-area refinements.

KeiHome's `Os4*` adapters are independently authored against MIUIX's public
APIs and Apache-2.0 upstream source. MeiloX's style separation and
InstallerX-Revived's `feat/glass` branch were read for API usage and integration
constraints. No implementation code from either application is copied,
translated, included as source, or linked into KeiHome by this migration.
MeiloX's independent Glasmio choices are not treated as MIUIX OS4 APIs.

Existing adapted MIUIX About/background code retains its attribution.
The selectable iOS-like navigation retains the project's existing Apache-2.0
AndroidLiquidGlass adaptations and attribution. Its shader, sensor and
edge-stroke integration is migrated to the new material APIs. It is independent
of the published OS4 Glass navigation implementation.

## Read the commit contracts, not only the release number

The introduction is [b7a5f08](https://github.com/compose-miuix-ui/miuix/commit/b7a5f08).
Its body includes review fixes which matter to consumers:

- A floating material includes blur **and** ordered blend layers. Blur alone
  leaves a dark page looking like a transparent hole.
- A source surface is either material or glass shading; stacking both can
  incorrectly gray a menu or a bar.
- The progressive top-bar scene is the sampling source for its buttons.
- Dialogs and popups must preserve shadows during alpha transitions, stop
  accepting actions during exit, and use root overlay coordinates.
- An in-list control must be excluded from the backdrop it samples.
- Floating navigation must respect Scaffold padding and safe insets.

The subsequent changes included in the pin are:

| Commit | Consumer consequence |
| --- | --- |
| `799d3d0` | Reducing alpha preserves the material instead of dropping its blend layers. |
| `ba0fca0` | Navigation drag stabilization and theme accent use replace app-owned motion approximations. |
| `b59ed57` | Display-cutout values remain stable during inset animations. |
| `ea1b935` | Glass symbol scaling preserves intended bounds without clipping. |
| `4564585` | Independent navigation actions/capsule width animate; exiting actions release focus. |
| `ca67499` | Material, shader/cache, tilt and edge-stroke APIs move into `miuix-material`; `miuix-shader` is removed. |

The complete messages and exact upstream checkout are retained in
`.artifacts/research/miuix-os4-20261111/`.

## Dependency and API migration

Use one MIUIX version for UI, preference, icons, nav, blur, squircle, core,
glass, glass-icons and material. KeiHome declares the modules it directly
uses and resolves the platform variants consistently.

`RuntimeShader`, `asBrush` and `isRuntimeShaderSupported` now come from
`top.yukonga.miuix.kmp.material`, not `blur`. The About shader behavior is
unchanged. Do not add a compatibility shim that conceals an old/new module
mix. Prefer the current source signatures over stale example snippets.

The existing Kotlin 2.4.21 / Compose 1.13.0-rc01 alignment and compile SDK 37.2
remain. This migration does not change minSdk, targetSdk, icon rendering,
native hooks or the validated native payload.

## Shared scene and theme setup

`KeiHomeScaffold` supplies the page's `LocalKeiHomeBackdrop`. The page records
its content using `Modifier.layerBackdrop`; top/bottom bars and root popup
hosts are outside that recording. This is a scene description, not an image
cache: do not capture the popup scrim, the glass panel, or its own shadow back
into the source.

Pass `GlassDefaults.style(LocalKeiHomeDarkTheme.current)` explicitly for
shaded controls. The library's plain `GlassDefaults.Style` is not an automatic
app-theme choice. Unshaded dialogs instead select `GlassMaterials.dialogGlass`
and the theme-specific edge stroke explicitly.
Use the existing `MiuixTheme` controller and color roles for Monet, fixed
appearance, and system appearance; avoid separate hardcoded light/dark
surface palettes.

For a multi-style application, scope these adapters and locals to the MIUIX
branch. Keep Material/Glasmio roots and their sampling layers independent;
do not turn a reference application's global style selector into a required
dependency for every screen.

The in-app blur setting keeps its existing serialized Gaussian/Progressive
names and default. It now also offers `OFF`. Old installations retain their
choice. This controls application chrome, not the launcher's Dock material.
When off or unsupported, no page backdrop is allocated and ordinary OS4
controls/opaque surfaces remain usable.

Ordinary scrolling child pages use the shared toolbar and connect their scroll
behavior to the content. Insets belong inside the scrollable content so it can
pass behind the progressive material; an outer top inset blocks that effect.
Release history, Logs and the guide's Desktop overview follow this contract.
About intentionally keeps its
own animated background and specialized toolbar treatment.

## Components and ownership

| Surface | Adapter / behavior |
| --- | --- |
| Page scaffold | `Os4Chrome.kt`; preserves MIUIX overlay hosting and safe-inset defaults. |
| Top bar | `KeiHomeTopAppBar`; official `GlassTopAppBar` when a source exists, basic MIUIX fallback otherwise. |
| Progressive treatment | Official `progressiveBlur` and band material; no extra legacy progressive layer. |
| Gaussian treatment | Existing uniform blur underneath the bar; no additional progressive treatment. |
| Toolbar actions | `KeiHomeIconButton`; official `GlassIconButton` inside a glass toolbar, native disabled/held/custom-color behavior retained. |
| Navigation selector | `KeiHomeNavigationBar.kt`; persisted iOS-like / OS4 Glass choice, one existing pager coordinator and icon-pack update badge. |
| OS4 Glass navigation | `Os4NavigationBar.kt`; official Glass navigation with its native materials, icons, shape and safe inset. |
| iOS-like navigation | `liquid/LiquidGlassNavigationBar.kt`; original lens, chromatic dispersion, dual-peak reflected light, drag motion and 24dp horizontal margins; migrated to `material` APIs. |
| Grouped/child menus | `Os4DropdownMenu.kt`; window-space anchor, root-hosted popup with native coordinate conversion, app-theme material, existing selections and close behavior. |
| Confirmation/help dialogs | `Os4Dialogs.kt`; official Glass dialog with its opaque null-backdrop fallback, themed buttons, native Back/dismiss and disabled actions. |
| Forms and lists | Existing MIUIX controls use the upgraded library without app-owned imitations of the new material. |

The root navigation still owns tab selection. A component callback requests
a destination; it does not introduce a second independent navigation state.
The existing coordinator cancels old animations when interrupted and derives
selection from the pager.

App Settings exposes **Navigation bar style**, independently of page gestures
and the blur mode. The stable serialized names are `IOS_LIKE` and `OS4_GLASS`.
Missing or unknown keys default to OS4 Glass. Saved iOS-like and OS4 Glass
choices retain their values across upgrades. Selecting a
style updates the preference immediately; it does not recreate the pager,
reset the current page, or change the launcher's Dock. Only the selected
implementation is composed. Disabling blur also gives iOS-like its opaque
surface fallback instead of enabling its lens implicitly, and stops its
tilt-reflection subscriptions.

The old `Highlight` / `BloomStroke` contract is replaced by `EdgeStroke`
with `EdgePeak.Dual`, the original light coordinates/intensities, and the same
flat band/bevel parameters. `drawEdgeStroke` runs in `drawBackdrop.onDrawFront`:
gravity and press state remain draw-time reads instead of causing sensor-rate
recomposition of the navigation tree. OS4 Glass retains its separate native
material and interaction behavior.

### Return events and popup tests

Use the existing navigation dispatcher hierarchy. A `ComponentActivity`
already integrates platform input; do not attach an additional
`OnBackInvokedInput` blindly or mix a second legacy fallback into every popup.
The minified application's actual system Back and outside-tap paths must be
checked separately from component tests.

Isolated component tests use `Os4NavigationTestHost`: an official
`NavigationEventDispatcher` with `DirectNavigationEventInput`, supplied through
`LocalNavigationEventDispatcherOwner`. The input drives the real MIUIX popup
handlers; an unhandled event throws instead of invoking a test callback that
pretends to dismiss the popup. This avoids confusing test-Activity/system-key
dispatch with the component's behavior.

For clipping tests, inset the test Scaffold and verify that the root popup
escapes a nested, clipped toolbar without applying its origin twice. An
outside tap must stay **inside the popup host's Scaffold**, below the popup.
Tapping the margin outside an intentionally inset Scaffold or the system
gesture strip does not test its dismiss surface. Check selection and semantics
uniqueness after reopening; do not hide measurement clones by selecting only
the first matching node.

The navigation harness covers 24 combinations: both styles, three blur modes,
light/dark and font scale 1.0/1.6. It exercises every tab, retained selection
across style changes, stable bounds within each style and a single accessible
update badge. Assert the badge through its content description, as production
does: a tab merges descendant semantics, so a child test tag does not represent
the accessible update indicator. Hidden optical recording layers must not
introduce additional accessible labels or badges.

## Recipe for another project

1. Record the old runtime graph, preferences, current screen and screenshot.
   Read that project's theme/overlay ownership before selecting adapters.
2. Pin a published snapshot to a full upstream revision. Read the introduction
   and subsequent fixes, README, AGENTS, relevant source and examples.
3. Align all MIUIX modules, add direct glass/material dependencies, and migrate
   moved types. Compile the real application rather than only a sample.
4. Keep app appearance and palette state authoritative. Pass themed material
   styles explicitly; preserve existing serialized choices.
5. Establish one content sampling layer per visible page. Keep glass controls
   and root overlays out of it. Do not record a whole Scaffold containing the
   glass back into itself.
6. Migrate navigation and top bars first, then menus/dialogs. Keep root overlays
   and measured window-space anchors; let native hosts convert coordinates.
   Check interruption, Back, outside taps,
   repeated opening, disabled controls and exit-time actions.
7. Test light/dark, palette changes, Gaussian/progressive/off, font scale,
   keyboard/insets, long labels, scrolling, animation interruption and
   confirmation flows using actual touches.
8. Validate a minified signed release after debug tests. Restore temporary QA
   settings and retain the exact final APK, graph, screenshots and receipts.

## Acceptance evidence

Machine-readable results, build logs, localized screenshots and deployment
receipts are recorded under
`.artifacts/research/miuix-os4-20261111/acceptance.json`.
That record distinguishes device behavior from source review and records
which app settings were restored.

## Shared preference selector completion (2026-11-12)

The full production UI audit found 20 remaining calls to upstream
`OverlaySpinnerPreference`. All now import the app-owned compatibility adapter
in `ui/components/OverlaySpinnerPreference.kt`, which delegates to the same
`Os4DropdownPreference` / native `GlassDropdownPreference` used by the six App
Settings selectors. This covers logs, Desktop/Dock/search, icon appearance,
Perfect/ColorOS and Apple choices, plus Monet controls. Existing indices, values,
callbacks and row spacing are preserved. Disabled and empty selectors expose
disabled accessibility semantics, and invalid/disabled selections cannot invoke
callbacks.

New simple preference selectors must use one of these app adapters, rather than
importing the upstream legacy preference. Grouped filters, sorting and cascading
menus retain the OS4 `GlassIconDropdownMenu` adapter; their grouped behavior is
intentional. The source scan found no remaining production imports of legacy
Spinner, SuperDropdown, DropdownPreference or window-spinner controls. Native
OS4 visuals remain independent of the user's iOS-like/OS4 Glass navbar choice.

The focused device checks are `Os4SelectorPreferenceUiTest`,
`PerfectCompletionUiTest` and `Os4DropdownMenuDeviceTest`. Validation and the
per-file audit are retained under
`.artifacts/research/perfect-completion-20261112/ui/selector-migration/`.

## Remaining Glass surfaces (2026-11-12)

The follow-up inventory compares production imports and their app-owned aliases
against the public Glass API at `9d4b9c85`. Dialog actions now use native
`GlassDialogButton` even without a backdrop; its opaque fallback retains button
roles and colors. Perfect resource selection uses native `GlassIconButton` for
its five enabled actions. Disabled actions retain the accessible noninteractive
fallback because the native icon button has no `enabled` parameter.

Search/clear glyphs and 19 disclosure glyphs use the corresponding Glass icons.
Search input, ordinary content cards, switches, sliders and page actions retain
the current MIUIX OS4 components: this revision has no public Glass search field,
general text button or floating action button. The existing Glass top bars,
menus and navigation wrappers remain in use, and the iOS-like navbar remains
an explicit user choice.

`Os4ItemDropdownPreference` handles selectors with individually unavailable
choices, such as device-dependent Dock corner styles. The public string-only
`GlassDropdownPreference` cannot express disabled entries, so this narrow
adapter combines native `BasicComponent`, `GlassDropdownPopup` and
`GlassPopupItem`. It retains disabled labels, accessibility state, original
indices and callback order. Ordinary selectors keep the simpler native
preference path. The popup portal stays mounted through its exit spring and
stops accepting selections when closing or disabled.

The API inventory, retained-component rationale and changed-file receipts are
under `.artifacts/research/perfect-completion-20261112/ui/glass-audit/`.

### Shared press, preview tabs and detail sheets

The next interaction pass uses `os4Clickable`/`os4Toggleable` for app-owned
clickable surfaces. The click and `glassPress` modifier share one interaction
source, so cancellation and disabled state follow the real gesture. Native
controls keep their own feedback; cards already using native Tilt/Sink do not
receive a second press animation. Read-only content keeps the ordinary card
hierarchy.

`Os4DisclosureContent` now gives its children a full-width `Column` inside
`AnimatedVisibility`. Multiple children in the animation container previously
overlapped, including Monet's five palette families and icon-role controls.
The shared fix also covers status, release-note and guidance disclosures.

View modes use native `GlassTabRow`: light/dark/compare previews in Monet,
application inspection, Perfect inspection and Apple inspection; light/dark
mask comparisons; Monet tone families and editor grid/sliders. Persisted
settings with many choices remain dropdown preferences.
Inline tabs use a leaf surface backdrop for the native material. Sampling the
page backdrop from a tab that is itself inside that page's recorded layer
creates a RenderNode cycle. The focused device group includes drawing tabs
inside a recorded page, as well as the actual Monet editor scenarios.

`Os4DetailsSheet` uses native `GlassBottomSheet` for preview inspection,
guidance, cache explanations, cache threshold editing, Monet color editing
and Perfect source information. It preserves composition locals, accessible
dismissal, nested scrolling and the native exit transition. Destructive
confirmations and action choosers retain `Os4ActionDialog`.
Overlay sheets must be composed within their page's Scaffold provider. A
sibling composed after `PerfectSubpage` has no dialog host and can receive
clicks without displaying a panel. Perfect preview declares its inspection
content in the Scaffold's floating slot, outside the recorded list layer.
Source information already uses that slot; guidance and threshold editors
are hosted by their page's toolbar. Standalone component tests are therefore
supplemented by opening these panels in the actual navigation destinations.

Apple mapping and matched-artwork pages have explicit selection mode and a
Glass Panel toolbar. Matching keeps the existing batch/cooldown policy; the
status summary reports the selected and actionable counts. Export includes
only selected effective mappings through the existing validated transfer
format. Done/Back exits selection without modifying mappings. There is no
bulk deletion of upstream artwork.

Focused validation and before/after captures are retained under
`.artifacts/research/glass-interactions-20261113/`; the
`ui-glass-interactions` check group excludes unrelated native and export tests.

Expanded list search, joined view selectors, short anchored explanations and
the value-based Gaussian backdrop pipeline are documented in
[OS4 search and contextual help](os4-search-and-context.md). Their focused
regression group is `ui-os4-context`; long guidance and live diagnostic cards
remain separate from the short-help popup pattern.
