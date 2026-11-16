# OS4 first-use guide

KeiHome's five-step MIUIX guide introduces the app, framework and scope setup,
icon sources, desktop categories, and follow-up checks. Each step can be skipped
or revisited; feature controls remain in their existing pages.

## Layout and interaction

The OS4 rewrite uses a centered illustrated heading, compact progress dots,
short grouped links, and separate floating Back and Continue controls. The footer
has no enclosing opaque panel. Content scrolls behind the controls through a
cached gradient matching the theme surface. The guide uses its own full-screen
`OnboardingPageLayout`, rather than the shared settings scaffold's app-bar slot.
An outer MIUIX Scaffold supplies the popup host required by native glass sheets;
its app-bar slots and content insets are unused. The custom layout continues to
measure the floating header, footer and full-height scrolling content.
Header and footer are measured before the body; their clearances, including
system-bar safe areas, are padding **inside** the scrolling content. The viewport
extends behind both controls. Cards can therefore scroll through the header's
gradual fade instead of being cut off beneath a solid strip; the last link can
still be scrolled fully above the footer. The two gradient brushes are cached;
scrolling does not publish measured sizes into Compose state or create a new blur
layer for the fades.
Content and controls are centered within a 640dp maximum width on wider windows.
The artwork shrinks at larger font scales; content remains scrollable.

MIUIX supplies the glass Back button, primary action, grouped rows, detail sheets,
and page transitions. The welcome artwork is KeiHome's own vector. Each page has
one short entrance animation; there are no looping animations or new dependencies.
The primary action places its directional arrow after the label.

The reference is the actual HyperOS AVD Provision package, inspected statically
with DexSQL. See [research notes](onboarding-os4-research.md) for the exact package,
provenance, useful patterns, and limits. No OEM implementation or assets were
copied, and the configured device's setup wizard was not rerun or reset.

## Five steps

1. **Welcome:** three concise feature tiles and a short invitation to personalize
   icons and the desktop. Copy avoids promises that initialization or dependent
   feature rules never change a setting.
2. **Framework:** actual connection and grant-count pills, an explicit request
   action when scopes can be requested, and links to the framework manager and
   dedicated scope viewer. The old all-in-one scope card is not embedded here.
   First activation explicitly requires a device restart after enabling the
   module and granting scopes, then unlocking to HOME. Skipping or finishing
   the guide cannot acknowledge this requirement. The independent
   [Status reminder](first-activation.md) remains until restart or actual
   system-target injection is confirmed.
3. **Icons:** Library, Perfect, its resource manager and Apple, followed by Monet,
   artwork completion, Icon mask, Keep original icons, Recommended mappings and Icon packs > More
   settings. The resource manager downloads selected styles and required shared
   resources; removal retains resources still needed by other downloaded styles.
   Its top filter and sort menus help find styles, and Git updates reuse unchanged
   resources. Downloaded styles can be previewed and generated offline.
   The mask link compares original, HyperOS4 and custom masks on the same icons:
   when adaptation is enabled, custom masks take priority, followed by the pack
   mask, with HyperOS4 filling a missing mask.
   The original-icon list has app/package search and top filter/sort menus;
   selected apps keep their own artwork after regeneration and application.
   Telegram completion is limited to the official Store, Web and Beta channels:
   a missing default mapping can borrow a sibling's existing light/dark artwork.
   Existing mappings, alternate launcher icons, Telegram X and third-party forks
   are not part of that default borrowing. See
   [preserved app icons](preserved-app-icons.md) for publication and matching rules.
   Recommended mappings independently control eligible app-name matching, official
   Telegram channel sharing and built-in ColorOS theme-store aliases; per-app
   custom icons and kept originals take priority over these mappings. Its link
   returns to the same guide step.
   Per-app custom icons are managed under Icon packs > Icon completion and
   fallback > Custom icons. They are the final override across pack switches.
   Switching between a custom icon and keeping an original requires confirmation.
   Saved custom entries first open an original/custom comparison, then offer
   editing or removal. Reapply the pack after changing these choices.
   Supported native light/dark adaptation remains separate from selecting or
   applying an optional icon pack. Monet has an OS4 overview with quick selectors.
   Source, scheme and preview open sheets; custom colors and palette details open
   subpages. A ColorOS design can enter the same editor through its preset choice.
   Moving between these surfaces keeps the draft. The single **Apply colors**
   floating action submits the draft and preserves the current Monet on/off
   state; use the dedicated switch to enable or disable Monet. Refresh rereads
   system state without submitting the draft. A short note
   distinguishes generated packs from applied themes: after changing artwork,
   completion, recommended mappings, masks, kept originals or generation scope, update or regenerate,
   then apply. Downloads and previews do not apply a theme. Local-resource details
   open a shared OS4 details sheet, with cache/network behavior and the default
   latest-generated archive retention policy. Apple and Perfect each retain their
   newest successful generation; manual imports are preserved. The default direct
   navigation option and floating import workflow are introduced in this step.
   Storage shows imported archive sizes and has separate MTZ/ZIP, Apple and
   Perfect management destinations. Cleaning preview memory, downloaded resources
   and generated archives are distinct actions; the top preview-memory action
   does not remove saved packs.
4. **Desktop:** direct links to Dock, Appearance and text, and Icons and launching,
   plus the category overview. Each destination returns to the originating step.
   The short status note explains that search-bar material can follow Dock or be
   configured independently.
5. **Finish:** current framework/scope states, shortcuts to scope authorization,
   App logs and cache management, and the distinction between App and LSPosed logs.
   App export requires confirmation with a twenty-second wait; runtime diagnostics
   and complete About environment information help identify the right log.
   Finishing the guide does not claim that every host hook has loaded. Check the
   launcher and the share sheet, Settings or Sidebar actually used.
   Cache guidance explains the fixed default cleanup reminder of **520 MB**,
   independent of Perfect downloads. App logs count toward cache usage, and their
   cache entry opens the log viewer for inspection or export. The reminder does
   not automatically delete resources. Clearing preview memory keeps downloaded
   artwork and the applied theme.

Guide links use existing routes; they do not introduce a destination or toggle
features just by opening a page. Feature choices retain their existing linkage
rules. In particular, **Keep original icons is an icon-pack destination**, not a
desktop section:

| Guide entry | Callback and route key |
| --- | --- |
| Perfect resources | `onOpenPack("perfect-sources")` |
| Icon mask comparison | `onOpenPack("icon-mask")` |
| Keep original icons | `onOpenPack("preserved-icons")` |
| Dock | `onOpenDesktopSection("dock")` |
| Appearance and text | `onOpenDesktopSection("appearance")` |
| Icons and launching | `onOpenDesktopSection("icons")` |
| App logs / Cache | `onOpenSettings("log")` / `onOpenSettings("cache")` |

Desktop > Icons and launching uses `icons` for desktop behavior settings;
`preserved-icons` is not a `DesktopSection` key.

Service connection, authorization, hook loading and visible icon results remain
separate states. First activation **requires a full system restart** after
enabling the module and granting scopes, followed by unlocking to the home screen.
API 102 automatic hot reload updates only the Java part. An upgrade containing
new `.so` native code, including 1.11.0, requires a manual system restart so
processes load the new libraries. Restarting KeiHome or the launcher alone does
not replace the first-activation requirement. A newly authorized host may need
its next process start.

After reading upgrade release notes and returning to the app's main page,
restart advice appears once per version. Its confirmation button becomes
available after five seconds. Confirming records that the advice was read;
the user performs the system restart manually. This version advice is separate
from the first-activation requirement: reading notes, skipping the guide or
acknowledging advice does not establish that a system restart occurred.
The guide and advice do not trigger an automatic restart or require clearing
app data. See [release-note behavior](release-notes.md) and the
[1.11.0 notes](releases/1.11.0.md).

The [1.12.0 preparation](releases/1.12.0.md) introduces no native-library restart
requirement. Version advice and per-version reading remain separate: All/Unread
views let users revisit notes without treating a read confirmation as a completed
system restart. Existing historical release text is retained.

## Entry, persistence and lifecycle

The existing `onboarding_v1` MMKV schema and five page indices are unchanged.
A new installation starts here. Existing MMKV configuration or legacy display
XML identifies upgrades, which retain their normal entry point. Back saves the
previous step; process loss restores pending progress and earlier steps.
Completion or deferment prevents another automatic presentation, including when
an obsolete first-use navigation stack is restored after completion.

Settings keeps its searchable replay entry. Replay begins at Welcome without
resetting preferences or rearming first-use presentation. Catalog, desktop and
settings round trips retain the current step. Callbacks from an outgoing step
are ignored once another route owns navigation, preventing rapid duplicate
Continue, Back or destination actions from changing the wrong stack.

Only the framework and final pages observe scope status, using the existing
lifecycle-bound observer. Desktop destinations reuse their existing observers.
No new background polling or scanning service was introduced. EN, Simplified
Chinese and Japanese resources share the same keys and placeholders.

## Focused verification

Native-changing releases can opt into the reusable
[upgrade restart reminder](upgrade-restart-reminders.md). This is independent
from both release-note acknowledgement and the first-activation reboot state.

For resource-only validation, run `python3 scripts/i18n/check_resources.py`.
The latest guide changes also extend the existing navigation assertions for
Perfect resources, original icons and all three desktop categories. These
assertions require the matching APKs and a device; static resource checks alone
do not establish layout or navigation acceptance.

Run `python3 scripts/checks.py ui-onboarding --serial emulator-5580 --run` after
building and deploying matching main/test APKs. The group exercises the actual
navigation with isolated MMKV guide stores, without clearing user data or
changing scope grants:

- Complete flow, framework/icon/desktop/settings round trips (including direct
  Icon mask, Perfect resources and original-icon links and all three desktop
  categories), Settings replay,
  persisted state re-read, and equality of AppPreferences before and after.
- Resume/defer at 1.6 font scale, with final-content bounds above the measured
  footer on all five pages.
- Duplicate Continue and Back callbacks before recomposition.
- All pages retain a full-height scroll viewport beneath the floating header.
- A scrolled color-card fixture checks the rendered gradient on both sides of
  the header boundary in light and dark themes, including unmodified body color.

Current OS4 receipts and screenshots are under
`.artifacts/research/onboarding-os4-20261111/`. Historical onboarding evidence
under `onboarding-20261106` and `pad-glass-icons-20261107` predates this layout;
it is not a claim that the old full JVM suite was rerun for the OS4 rewrite.
Phone AVD acceptance is distinct from physical-device or OEM-wizard acceptance.

This AVD requires the test Activity to be explicitly foregrounded and may show
an associated-start prompt. Keep the test package installed and update it with
`adb install -r` to retain that authorization. Back tests use the Activity's real
back dispatcher because global Espresso key injection is restricted here.
