# Icon-pack actions, remembered menus and native return

## Browsing the library

The APK and MTZ/ZIP lists use compact light/dark previews and colored source
and active-state pills. Installed APKs show their reported version name. File
import remains available after the list, with a recommendation to install APK
packs for easier version and update tracking; replacing a local file requires
selecting the updated file again. ColorOS style previews use the same compact
pair layout.

Both lists can search the pack name and installed package name or imported file
name. The sorting menu offers name, package/file name, most recently updated or
added, matched-app count, and reverse order. The actually applied pack remains
first for every order, including reverse, when it matches the query. Ownership
uses the applied native component's recorded source, including its original
system-fallback choice. The existing menu-memory setting also controls sort
persistence; disabling memory clears the saved order. Filtering never forces a
nonmatching active pack into the results.

The search row ends in a numeric pill: the total when all packs are shown, or
visible/total after searching or filtering. Its accessibility label includes
the full result count. This replaces the separate result-count sentence.

Locally imported files have a red removal icon in the card header, including
file-based APK imports. Installed APK packs have no removal action here.
The OS4 Glass confirmation names the pack and explains that only KeiHome's
private copy and catalog entry are removed. The original SAF/source file,
exports, native import receipts and system Themes copy remain unchanged.
Cancellation makes no change. Import and removal actions are guarded against
concurrent work; successful removal clears catalog previews and immediately
updates the list/count. A later import of the same source works normally.
Deletion validates the content-addressed ID and never follows symbolic links.

The preview pairs and import/apply/update actions share one row. The preview
strip uses 32 dp artwork with 6 dp column spacing and retains up to six
representative entries; the available width determines how many pairs are
actually loaded. The right-hand action column stays accessible as font size
increases. Shared cards use this layout for installed/file APK packs, MTZ/ZIP,
built-ins and generated sources. Import eligibility and secondary update actions
are unchanged.

## UI

The built-in HyperOS card uses native MIUIX text buttons with a tinted pill
surface, 48 dp touch targets and 8 dp horizontal/vertical separation. Actions
wrap when their localized labels do not fit on one row. Icon-library refresh
feedback is grouped into a small card with an aligned MIUIX progress ring,
completion/error icon and a separate time pill. The toolbar and feedback card
both follow catalogue loading, including automatic reads. Their icon slots keep
fixed bounds while crossfading, and error details expand within the card.
Feedback observes only refresh and catalogue result fields; existing page
observers and automatic resource updates retain their original lifecycle.

The current-pack page, ColorOS configuration and ColorOS Inspiration use the
MIUIX FloatingActionButton content slot for a primary icon and a text label.
The button sits above navigation, has a stable minimum width and icon slot,
and fades between its action icon and progress ring. Preparing resources and
native import have separate labels. Content has bottom clearance for the FAB.
Other icon-pack cards use compact inline actions for import/update/details.
The current fallback shows actual launcher artwork at 50 dp and a larger
applied-source label, avoiding unrelated background-service icons.

MIUIX progress indicators need their `size` argument to match the drawing
bounds: their radius is computed from that argument, not just Modifier
constraints. All circular-indicator call sites now pass explicit sizes
and 2 dp strokes. The FAB uses on-primary foreground and a translucent track.
These changes retain indeterminate progress rather than inventing percentages.
Preview requests are scoped by source, resource, configuration and generation:
switching the source drops the old bitmap immediately, and loading is distinct
from a completed request with no static artwork. Preview boxes keep their size.
The previous native detail receipt stays visible but disabled during generation,
so removing an action row cannot clamp a list that is scrolled to the bottom.

Reference: pinned MIUIX
[FloatingActionButton](https://github.com/compose-miuix-ui/miuix/blob/fc455d9a/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/FloatingActionButton.kt)
and
[ProgressIndicator](https://github.com/compose-miuix-ui/miuix/blob/fc455d9a/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/ProgressIndicator.kt).

## Automatic application

Settings contains **Apply after generation**, enabled by default. Disabling it
keeps the generated pack in the native library and exposes a manual apply link.
The setting is captured with an import request; the foreground App also checks
the current preference before opening an authorized native apply operation.
Legacy requests default to no automatic application.

The inspected Themes 11540 PC Binder only imports/queries resources. Application
still uses its original `ResourceApplyHelper.toq` and `ApplyThemeTask`.
After durable import and converted-resource approval, the system worker can
authorize one local component and operation token through a new private Binder
transaction. The bridge continues enforcing system UID. The authorization is
persisted in Themes, expires after ten minutes and is consumed before use;
the same operation cannot re-arm a consumed ticket.

The App opens the exact local icons detail with the authorized token. The
Themes hook waits until the local resource is ready and the activity has
started, validates the component/token and invokes the native icons-only apply
task without related wallpaper. Original native validation remains in place.
Unsupported automatic setup leaves the native detail available.

The host's obfuscated LiveData/Observer signatures are resolved structurally.
LiveData is seeded with a native `ThemeStatus`: ApplyThemeTask otherwise finds
null and emits no progress/completion. On status 98 (native task completion),
the native activity returns RESULT_OK and finishes on the next main-loop turn.
This does not bypass its subsequent original validation.

KeiHome uses an Activity Result launcher. A native completion requests fresh
runtime/source state and a platform layout/redraw of the existing window.
Pager, child destination and scroll state stay with their existing owners.
Neither launcher, SystemUI nor system-server is restarted. Automatic navigation is recorded
before launching so report replay, recomposition and recreation do not reopen
Themes.

## MIUIX navigation, blur and badges

The App uses the pinned `miuix-nav` runtime, with serializable `HomeRoute` keys.
The five tabs retain iOS-like paging. Focused pack destinations use the native
`MiuixDefault` transition with parallax, device corner radius, a 0.26 maximum
dim and input blocking while moving. Swipe direction follows layout direction;
the library arbitrates horizontal child scrolling and predictive back.
Each child owns its MIUIX app-bar and lazy-list state. The shared ViewModel and
automatic-apply listener sit above the entry lifecycle owners. Entry polling
still follows STARTED; an observation generation prevents a departing entry
from clearing its successor's visible-page state.

Settings offers Gaussian and Progressive top-bar blur, persisted separately
from menu memory. Both use MIUIX's `textureBlur` / `progressiveTextureBlur`.
Progressive is the original default. There is no additional opacity mask.
The style collector observes only that preference, within each visible bar.

The existing floating-navigation `badge` slot renders a native MIUIX dot on
Packs when a managed active pack has fillable missing artwork, a changed source
version or a changed fallback preference. The background check runs on source,
catalog, package or preference changes; it does not add a polling timer.
Known mappings and ColorOS fallback candidates participate; an uncovered user
app alone does not create an update task. Missing candidates are probed on IO
with the exporter's guarded renderers and shared live-resource policy, so a
deliberately retained dynamic original or unavailable static resource cannot
leave a permanent completion notice.

The authenticated current-icon snapshot includes the original module's name
inventory before adding stock preview fallbacks. Generated modules carry a
bounded source/version record inside `icons`, so importing a library update
alone cannot clear the notice for an older applied module. The converted
archive validator admits only this fixed path and canonical bounded plain text;
existing image/MAML validation and native grants remain required. Snapshot
format v3 prevents reuse of an older snapshot without that distinction.

Reference: [pinned MIUIX navigation guide](https://github.com/compose-miuix-ui/miuix/blob/fc455d9a/docs/zh_CN/guide/miuix-nav.md).

## Remembered choices

**Remember menu choices** defaults on. The Apps scope, user/system origin,
coverage, capability filter, ordering, reverse direction and overlay visibility
are stored through AppPreferences. Invalid/older stored values restore safe
defaults. Search text is a separate page draft.

Disabling memory clears saved browser choices, preserving the current live
selection until the next process start. Re-enabling saves the current
selection. Functional preferences such as icon appearance and App colors keep
their existing persistence.

## Focused acceptance

Evidence is under `.artifacts/research/pack-actions/`; the final deployment
receipt and release acceptance JSON identify the installed version and hash.

- 82 focused JVM tests passed, including browser storage bounds, ticket
  identity/expiry/replay, import receipts, hook retirement, update notices and
  converted-archive acceptance/rejection of source provenance.
- Release assembly, R8 and required lint passed.
- HyperOS AVD `emulator-5580`, Themes 11540: a single FAB action generated,
  imported, applied Classic ART+ and returned to KeiHome. Native logs confirm
  icons-only application and original DRM_SUCCESS validation.
- Automatic application off: generation stayed in KeiHome with the manual
  native apply link.
- User-only filtering survived APK replacement and force-stop/relaunch.
  Recent-update sorting and reverse direction were also restored. Memory off
  cleared the saved filter and restored all 308 applications on restart.
- Busy-ring and returned-page screenshots were visually inspected. Source AVD
  and physical devices were not modified.
- Gaussian and Progressive were visually checked. Gaussian persisted across
  force-stop/relaunch; Progressive was restored. Both main and child bars use
  the same preference and blur wrapper.
- Changing Default fallback to Classic displayed the native navigation dot.
  Update applied the stamped Classic module, returned to KeiHome and cleared
  the dot. Restoring Default repeated the same full flow successfully.
- During child-page generation/application, the ColorOS title, appearance row
  and ART+ row retained identical coordinates before, during and after loading.
- Final installed version: `1793594248`. Launcher, SystemUI and system-server
  identities remained unchanged through deployment and automatic application.

Release Compose reports confirm strong skipping and skippable AppsPage,
AppCapabilityCard, PackIconPair, PackStyleThumbnail, PackActionFab and MainScreen.
No stability annotations were added to bitmap holders, repositories or mutable
Android objects. Necessary status, palette, expiry, package and import refresh
paths remain active. Compiler reports were copied to `compose-release/` before
other variants could overwrite the shared report directory. These are static
skippability results, not measured recomposition counts.

`gfx-before.txt` and `gfx-after.txt` hold warm Release Apps scroll samples:
user-only, name ascending, previews enabled, three down/up pairs of 450 ms.
The baseline recorded 570 frames, one modern janky frame (0.18%), and
p50/p90/p95/p99 of 11/16/20/36 ms. The final sample recorded 586 frames,
zero modern janky frames, and 13/19/22/34 ms. Legacy jank was 9.82% and
13.99%, respectively. The mixed percentiles do not establish a speedup;
these are emulator smoke samples, not a controlled or physical-device benchmark.

The defaults were restored with automatic application and menu memory enabled,
user-only origin, name sorting, ascending order and overlays hidden. Desktop
Monet remains off with the existing Classic ART+ / Default fallback.

This acceptance does not close the separate native launcher/watchdog,
dual-display or physical-device investigations.
## Preferences location

Icon pack preferences are under **Icon packs > More settings**. Generation and
application options share one card; remembered choices and compact help share
another. Existing preference keys and values are preserved. The setup wizard
opens this same Packs child and returns to its original step. The Settings
root no longer lists this page; global preference search still finds its
individual switches. The old `pack-actions` Settings route remains a restored
state alias.
