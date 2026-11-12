# OS4 first-use guide

KeiHome's five-step MIUIX guide introduces the app, framework and scope setup,
icon sources, desktop categories, and follow-up checks. Each step can be skipped
or revisited; feature controls remain in their existing pages.

## Layout and interaction

The OS4 rewrite uses a centered illustrated heading, compact progress dots,
short grouped links, and separate floating Back and Continue controls. The footer
has no enclosing opaque panel. Content scrolls behind the controls through a
cached gradient matching the theme surface. The measured action height and
navigation safe area are reserved inside the scrolling content, so the last link
can still be scrolled fully above the controls without a hard blank band.
Content and controls are centered within a 640dp maximum width on wider windows.
The artwork shrinks at larger font scales; content remains scrollable.

MIUIX supplies the glass Back button, primary action, grouped rows, disclosures,
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
3. **Icons:** Library, Perfect, its resource manager and Apple, followed by Monet,
   artwork completion, Icon mask, Keep original icons and Icon packs > More
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
   Supported native light/dark adaptation remains separate from selecting or
   applying an optional icon pack. Monet is optional, but explicitly choosing
   ColorOS Inspiration enables Monet as a related feature. A short note
   distinguishes generated packs from applied themes: after changing artwork,
   completion, masks, kept originals or generation scope, update or regenerate,
   then apply. Downloads and previews do not apply a theme. Local-resource details
   use the shared OS4 disclosure.
4. **Desktop:** direct links to Dock, Appearance and text, and Icons and launching,
   plus the category overview. Each destination returns to the originating step.
   The short status note explains that search-bar material can follow Dock or be
   configured independently.
5. **Finish:** current framework/scope states, shortcuts to scope authorization,
   App logs and cache management, and the distinction between App and LSPosed logs.
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
separate states. First activation recommends one system reboot; later updates
normally use API 102 automatic hot reload. A newly authorized host may need its
next process start. The guide itself does not restart hosts or the system.

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

Current OS4 receipts and screenshots are under
`.artifacts/research/onboarding-os4-20261111/`. Historical onboarding evidence
under `onboarding-20261106` and `pad-glass-icons-20261107` predates this layout;
it is not a claim that the old full JVM suite was rerun for the OS4 rewrite.
Phone AVD acceptance is distinct from physical-device or OEM-wizard acceptance.

This AVD requires the test Activity to be explicitly foregrounded and may show
an associated-start prompt. Keep the test package installed and update it with
`adb install -r` to retain that authorization. Back tests use the Activity's real
back dispatcher because global Espresso key injection is restricted here.
