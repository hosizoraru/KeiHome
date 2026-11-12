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
3. **Icons:** Library, Perfect and Apple, followed by Monet, artwork completion,
   the existing Icon mask child page and Icon packs > More settings. Perfect's
   resource manager downloads selected styles and required shared resources;
   later Git updates reuse unchanged resources. Downloaded styles can be
   previewed and generated offline. The mask link opens import and real-icon
   comparison: when adaptation is enabled, a custom mask takes priority;
   otherwise the pack mask is retained, with HyperOS4 filling a missing mask.
   Supported native light/dark adaptation remains separate from selecting or
   applying an optional icon pack. A short note distinguishes generated packs
   from applied themes: after changing artwork, completion, masks or generation
   scope, update or regenerate, then apply. Downloads and previews do not apply
   a theme. Local-resource details use the shared OS4 disclosure.
4. **Desktop:** direct links to Dock, Appearance and text, and Icons and launching,
   plus the category overview. Each destination returns to the originating step.
5. **Finish:** current framework/scope states, shortcuts to scope authorization,
   App logs and cache management, and the distinction between App and LSPosed logs.
   Finishing the guide does not claim that every host hook has loaded. Check the
   launcher and the share sheet, Settings or Sidebar actually used.

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

Run `python3 scripts/checks.py ui-onboarding --serial emulator-5580 --run` after
building and deploying matching main/test APKs. The group exercises the actual
navigation with isolated MMKV guide stores, without clearing user data or
changing scope grants:

- Complete flow, framework/icon/desktop/settings round trips (including the
  direct Icon mask child-page link), Settings replay,
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
