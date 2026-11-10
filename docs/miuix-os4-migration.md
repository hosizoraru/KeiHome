# MIUIX OS4 migration

## Version and source boundary

The migration pins every MIUIX module to `0.9.4-ca674992-SNAPSHOT`, published
from upstream revision `ca674992c9645395cdb0d5109d66504ff53d018c`.
The revision was checked against the upstream `main` branch and the published
Maven version list on 2026-11-11. Do not use Maven's `latest` field alone:
the repository metadata lists hash-based snapshot names lexicographically.

Primary references:

- [README](https://github.com/compose-miuix-ui/miuix/blob/ca674992/README.md)
- [AGENTS](https://github.com/compose-miuix-ui/miuix/blob/ca674992/AGENTS.md)
- [Material guide](https://github.com/compose-miuix-ui/miuix/tree/ca674992/docs/guide)
- [Glass components](https://github.com/compose-miuix-ui/miuix/tree/ca674992/docs/components/glass)
- [Glass example sources](https://github.com/compose-miuix-ui/miuix/tree/ca674992/example/shared/src/commonMain/kotlin/component)

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
| Grouped/child menus | `Os4DropdownMenu.kt`; measured anchor, root-hosted popup, app-theme material, existing selections and close behavior. |
| Confirmation/help dialogs | `Os4Dialogs.kt`; official Glass dialog with its opaque null-backdrop fallback, themed buttons, native Back/dismiss and disabled actions. |
| Forms and lists | Existing MIUIX controls use the upgraded library without app-owned imitations of the new material. |

The root navigation still owns tab selection. A component callback requests
a destination; it does not introduce a second independent navigation state.
The existing coordinator cancels old animations when interrupted and derives
selection from the pager.

App Settings exposes **Navigation bar style**, independently of page gestures
and the blur mode. The stable serialized names are `IOS_LIKE` and `OS4_GLASS`.
Missing or unknown keys retain iOS-like as the original default. Selecting a
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
6. Migrate navigation and top bars first, then menus/dialogs. Use root overlay
   coordinates and measured anchors. Check interruption, Back, outside taps,
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
