# Static day/night icons (opt-in)

**Status: implemented behind a default-off option.** Partly checked on one
physical HyperOS 4 phone (see [Device evidence](#device-evidence)); the rest of
[Remaining acceptance](#remaining-acceptance) is open.

## Problem

HyperOS Home does not select `nightmode/res` for ordinary icons. The Dart icon
chain receives the night flag only on a chain the bitmap reader does not use
(see [theme-night investigation](../scripts/tests/theme-night/README.md)). A
system-following archive therefore gives every differing pair a generated
`fancy_icons/<name>/manifest.xml` selector ([NativeDayNightIcon](../app/src/main/kotlin/os/kei/hyper/home/iconpack/NativeDayNightIcon.kt),
[NativePaletteIcon](../app/src/main/kotlin/os/kei/hyper/home/iconpack/NativePaletteIcon.kt)).
Each selector enters Home's MAML path, including snapshot preparation during
launch and return transitions. The [0308 comparison](maml-launcher-performance.md)
and the November 21 report attribute launcher frame timeouts to that cost.

## Option

**Imported icon brightness → Follow system → Static day/night icons** (default
off, `icon_pack_static_appearance`). The setting applies to the next converted
import (APK, classic ZIP or local native archive). The imported archive is still
the normal system-following one; the option adds two derived copies and
swaps between them.

## Pipeline

1. **App.** `HomeViewModel` sets `IconPackImportRequest.staticAppearance` only
   when the option is on and the selected brightness is Follow system. The flag
   travels with the existing request (`static_appearance` remote preference,
   `staticAppearance` in the local receipt).
2. **system_server, import worker.** After a converted import succeeds,
   `IconPackImportMonitor` calls `NativeIconPackManager.registerAppearanceVariants`
   before delivering the report, so automatic application already finds the copies.
   - The `icons` module is extracted from the system-owned original and must
     still match the converted proof.
   - [NativeAppearanceVariants](../app/src/main/kotlin/os/kei/hyper/home/iconpack/NativeAppearanceVariants.kt)
     removes only selector groups it can regenerate byte for byte from the
     archive's own `res` and `nightmode/res` PNGs (plain and palette templates,
     current and legacy forms). The dark copy writes the night PNG at the
     default path. These stay unchanged:
     - names in `keihome-system-fallback.txt` and phone companions
       (`RecommendedPackageIcons.PHONE_TARGETS`), whose frontends need a descriptor;
     - names another resource can also serve: static layer pairs, other densities,
       root PNGs, `animating_icons`, `layer_animating_icons` and `nightmode/fancy_icons`;
     - authored programs (clock, calendar, weather) and unrecognized groups.

     Unchanged entries are copied raw by `RawIconArchive`.
   - Both copies pass the origin's own admission (`ConvertedIconArchive.validateIconsFile`).
   - Their SHA-256s are persisted in `/data/system/keihome-icon-imports/appearance-variants-v1.json`
     before Themes receives them. `ConvertedIconRuntime` approves a copy only
     while its primary converted digest is registered. The existing
     `converted-drm-v1.json` format is unchanged.
   - An import made with the option off asks Themes to remove copies that an
     earlier import of the same archive registered (`0x004b4805`). If one of
     those copies is applied, Themes first puts the primary back, so the active
     archive never loses its approval; Home is then poked and refreshed.
3. **Themes, private bridge** (`0x004b4803`). `ThemeAppearanceVariants` checks the
   grant and the native component's bytes, then copies both stages into its private
   `keihome_appearance_variants` directory. It verifies each copy's digest and keeps
   the two most recent records. The record whose primary or copy is applied is
   always kept, so importing other packs without applying them cannot remove it.
   The receipt is read under the store's lock on every use, without a cache.
4. **Swap** (`0x004b4804`). Themes replaces `/data/system/theme/icons` only when
   its digest equals a registered primary or copy. It writes a verified temporary
   file beside the active archive and matches its owner, mode and SELinux label.
   It checks that the active archive did not change, renames atomically, and then
   stages the *opposite* copy as `/data/system/theme/.keihome-appearance-{light,dark}.icons`.
   Each locked operation first removes temporary files a killed process left behind.
   The reply carries both files' identities (device, inode, size, mtime).
   system_server coalesces requests on the import worker. They come from
   night-mode changes, icon-theme changes (including re-applying the archive),
   user unlock, palette changes and new registrations.
5. **Pre-dispatch rename.** A hook on `UiModeManagerService.applyConfigurationExternallyLocked`
   runs before the new night bit is sent to any process. It holds only the
   UiModeManager lock. If the active archive and the staged copy still have the
   identities Themes just verified, `AppearanceFastSwap` renames the staged copy
   into place. That is two `lstat` calls and one same-directory `rename`.
6. **Post-apply validation in Themes** (`ConvertedIconGrants.validates`,
   `ThemeConvertedGuard.validates`) also accepts a grant's registered copies.
   The guard is published only after its copy lookup is bound. In system_server,
   `ConvertedIconRuntime` hashes the active archive once more if a rename
   replaced it during the first read.

## Making Home reload the archive

A rename alone does not change Home's icons. Home keeps the replaced archive
open and draws from its own caches. Two further steps are needed.

1. **Reopen the archive: a theme event for Home's process only.** In Home's Dart
   code, only `ThemeChangeSignals.handleConfigEvent` → `ThemeResourcesSystem.poke`
   → `_checkIconUpdate` → `ThemePackage.checkUpdate` reopens the archive. It runs
   when the MIUI theme sequence (`extraConfig.themeChanged`) increases. Night
   handling and icon lookups keep the open file.
   - [LauncherThemePoke](../app/src/main/kotlin/os/kei/hyper/home/xposed/LauncherThemePoke.kt)
     holds the WM lock and calls `WindowProcessController.dispatchConfiguration`
     for Home's process alone. The configuration carries a higher theme sequence
     and flag `2097152`, which is outside `MiuiConfiguration`'s restart set.
   - Each global configuration dispatch resets the server's last-reported copy,
     so KeiHome tracks the highest sequence it delivered. The value is a JVM
     system property, so a hot-reloaded module (including a module update)
     continues above the sequence Home has already seen.
   - After a real icon-theme change, the poke is repeated so Home, whose
     sequence may be ahead, still reopens the archive. A night change that
     clears the refresh queue keeps that queued poke.
   - The poke runs in the UiModeManager hook right after the rename, before the
     night change is dispatched.
2. **Redraw all icons: one batched package callback.** After the night change,
   KeiHome sends `IOnAppsChangedListener.onPackagesUnsuspended(user, packages)`
   once, instead of one `onPackageChanged` every 220 ms
   ([LauncherIconBatch](../app/src/main/kotlin/os/kei/hyper/home/xposed/LauncherIconBatch.kt)).
   - Home's Rust model (`AllAppsList.update_disabled_flags`) rewrites only the
     suspended word of each package's first entry and marks it modified.
   - Dart's `ItemChangeProcessor._handleAppUpdate` then evicts and reloads all
     of those icons in one pass.
   - Suspended packages, packages with several launcher entries, and shortcut
     callbacks keep the paced per-package path.
   - Each profile alternates its own batch order, since one refresh sends a batch
     per enabled profile. If the batch callback fails, that profile falls back to
     the per-package path.
   - A successful rename requests a full refresh, so the isolated resource probe
     (about 2 s) is skipped.

If the rename could not run (first switch after boot, a quick second toggle, or
a palette change), Themes swaps afterwards. KeiHome then pokes Home and always
sends the same full refresh (`scheduleArchiveRefresh`): reopening the archive
does not redraw mounted icons.

### Rebuilding Home when caches cannot follow

The poke and the batch do not reach every cache. Home's drawer keeps its grid items alive
(`AutomaticKeepAliveClientMixin`) with the images they were built with, and `IconCacheV2`
replaces an entry only when a mounted `IconDrawableWidget` for that key updates
(`didUpdateWidget` → `evict`). Drawer-only apps therefore keep the previous artwork. A full
`PACKAGE_CHANGED` does not change that either. Home only clears these caches when its engine
is rebuilt, and KeiHome has no code inside Home's process on a normal device.

[LauncherRebuildSchedule](../app/src/main/kotlin/os/kei/hyper/home/xposed/LauncherRebuildSchedule.kt)
therefore rebuilds Home through `ActivityManagerInternal.killProcess`, which records the exit
and lets the system start Home again:

- **After a dark/light switch:** at the next screen-off, once the screen has stayed off for
  3 s. A screen that turns on earlier cancels the attempt until the following screen-off.
  The workspace, Dock and folders already changed through the batch; the rebuild completes
  the drawer. Home returns to its first page, which the user does not see.
- **After an import is applied:** at once. Themes' own theme change has just reloaded Home
  with the system-following original. A sync that replaces that original
  (`applied … after apply`) or restores it during an opt-out reimport is urgent.
- system_server watches `/data/system/theme` (`CLOSE_WRITE`, `MOVED_TO` on `icons`) and syncs
  50 ms after Themes writes the archive, instead of waiting for a later event. Events whose
  archive identity matches the last sync's active or staged copy are our own swaps and are
  ignored; a sync then could still read the old night bit.
- A primary that the store placed for the live palette is remembered by identity in Themes'
  private directory. Turning the palette off is an ordinary switch, not an applied import.
- A failed rebuild (no launcher uid or service) is retried at the next screen-off.
- An import without the option that removes a pack's copies also removes them from the
  framework validator, so syncs stop once no pack uses copies.
- After a real theme change, Home was observed to ignore later pokes until it restarted. The
  rebuild also recovers from that state.

A *global* theme configuration change cannot replace the poke. Two drafts were
tested:

- `MiuiConfiguration.sendThemeConfigurationChangeMsg(0x8)`.
- A global theme change with flag `2097152`.

ActivityTaskManager relaunched every activity that does not handle
`CONFIG_THEME` (`0x400000`) in both cases. That includes Home, which returns to
its first page, and the foreground app, which appears to exit
(`wm_relaunch_resume_activity` with `400000`).

## SystemUI notification and status-bar icons

SystemUI loads app icons through `AppIconsManager`, which keeps plain bitmaps per user and
package (`mIconsCache`, `mQuietFancyIconsCache`) and only drops one when its package is
replaced. A system-following archive's day/night icons are MAML `FancyDrawable`s, which it
returns uncached (`NoCacheBitmapDrawable`), so they followed each switch. A fixed-appearance
copy serves plain PNGs, which it caches. Below it, `ThemeResources` keeps the replaced archive
open until a theme configuration change, and `IconCustomizer` caches its own bitmaps. Status-bar
descriptors also keep the bitmap they were built with. After a switch, notifications and
status-bar icons therefore kept the previous appearance (observed on 2026-11-26: QQ and WeChat
dark in light mode, while KeiHome's own SystemUI snapshots rendered light).

KeiHome's SystemUI icon cache already watches the archive. On each archive or appearance
refresh it now posts [SystemUiNativeIconCaches](../app/src/main/kotlin/os/kei/hyper/home/xposed/SystemUiNativeIconCaches.kt)
to SystemUI's main thread, the thread the framework itself uses for this check:

1. `ThemeResources.getSystem().checkUpdate()` reopens the archive when its modification time
   differs and calls `IconCustomizer.clearCache()`.
2. The current user's `AppIconsManager` maps are cleared; the bitmaps that were still alive
   are returned.
3. `SystemUiStatusBarIconRuntime.adoptNative` binds status-bar views that still draw one of
   those bitmaps to its package (bitmap identity), so the ordinary replay draws the current
   snapshot. Notification rows are refreshed by the existing row replay.

Device check (116000783 on the same phone, light mode): a no-op write to the archive logged
`Native app-icon caches refreshed; dropped=6`, a status-bar replay and a row replay, with no
SystemUI error. The user then switched to dark and back to light (14:33:36 and 14:33:41).
Each switch logged `Fixed-appearance icons swapped before the night change` in system_server
and, about 330 ms later, `Native app-icon caches refreshed` (dropped 6 and 7), a status-bar
replay of 3 views and a row replay of 7 rows in SystemUI. A notification-shade screenshot
in light mode afterwards showed QQ's light artwork; before the fix it showed the dark one.

## Behavior and limits

- **Target:** light copy in light mode, dark copy in dark mode. If palette selectors
  were flattened and the generated palette is enabled, the original archive is
  restored. It is read from Themes' own component content and verified by digest,
  so live Monet coloring keeps working. That transition always takes the slow path.
- **Fast path:** the icons switch together about 1.5 s after the night change.
  That is the refresh's settle delay; Home then loads the icons over a few seconds.
  The night change itself still relaunches apps that do not handle `uiMode`;
  that is Android behavior, independent of this option.
- **Slow path:** Themes swaps after the night change. Home is poked and receives the
  same batched refresh.
- **Without the batch callback** (older frameworks), icons refresh one package
  after another at 220 ms per package.
- **Late entries:** packages with several launcher entries (for example Phone and
  Contacts) and suspended packages are not batched. They follow over the paced
  path, a few seconds later. A quick second toggle cancels the rest of that work.
- **Icons not on screen:** Home's `IconCacheV2` is replaced only for icon widgets
  that are mounted when the refresh arrives (`didUpdateWidget` → `evict`). A widget
  mounted later reads the cache by key (`getIconImageIfNull`). Its only other
  invalidations are memory pressure (`trimUnusedEntries`) and engine disposal.
  An `am send-trim-memory` reaches Home's Rust `on_trim_memory`, which only logs.
  Drawer rows and folder pages that were not built at refresh time could
  therefore show the previous artwork. This was not observed in the phone
  acceptance below, but it has not been ruled out.
- **Applying another theme** produces an unknown digest, so nothing is swapped and
  the staged files are removed. Turning the option off affects the next import;
  importing the same archive again then removes its copies. Re-applying an archive
  that is still registered keeps swapping.
- **Drawer after a switch:** drawer icons of apps that are not also on the workspace keep the
  previous appearance until the next screen-off rebuild. An app that is long-pressed, opened
  or redrawn for a badge picks up the workspace's refreshed cache entry earlier.
- **Hot reload** drops a pending rebuild; the next archive change schedules it again.
- **Interrupted imports:** if the import worker restarts after the native import
  but before the copies were registered, the journal reconciliation delivers the
  report without copies. That import stays system-following.
- **Equal copies:** a pack whose flattened selectors are all palette selectors
  with equal day and night artwork produces identical light and dark copies.
  Registration refuses them and the import stays system-following.
- **Secondary users:** the poke targets Home's process for user 0. In a second
  space Home may not reopen the archive until the next theme event.
- **Without KeiHome's system and Themes hooks,** the active copy stays fixed. A
  standalone export is unaffected; it remains the system-following archive.
- **Storage:** two private copies in Themes' data, plus one staged copy in
  `/data/system/theme`.

## Tests

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'os.kei.hyper.home.iconpack.NativeAppearanceVariantsTest' \
  --tests 'os.kei.hyper.home.xposed.ConvertedAppearanceVariantsTest' \
  --tests 'os.kei.hyper.home.xposed.AppearanceFastSwapTest' \
  --tests 'os.kei.hyper.home.xposed.LauncherIconBatchTest' \
  --tests 'os.kei.hyper.home.xposed.ThemeAppearanceVariantsTest' \
  --tests 'os.kei.hyper.home.xposed.ConvertedIconRuntimeTest' \
  --tests 'os.kei.hyper.home.xposed.LauncherRebuildScheduleTest'
```

They cover selector recognition, exclusions (phone companions and names another
resource can serve), the dark default path, legacy templates, refusal without
selectors, target policy, record retention (the applied record outlives newer
imports), framework approval inheritance and one re-hash after a mid-read rename. They also cover pre-dispatch rename preconditions: a matching
transition, a single use, foreign or changed files, and staging paths. Batch
tests cover eligibility (single entry, not suspended) and the per-profile
alternating order that keeps Home from dropping a repeated batch. `LauncherThemePoke` reflects on
system_server classes and has device evidence only.

## Device evidence

Phone `82411d42`, HyperOS `OS4.0.4.0.XOCCNXM`, Home
`RELEASE-8.01.02.9239-261013-11081915-R`, 2026-11-25. KeiHome was updated in
place with hot reload; Home (PID 6412) and system_server (PID 3828) kept their PIDs.

- Import with the option on: `528 selectors flattened, 350 with palette`.
- `115000803`: each toggle logged `Fixed-appearance icons swapped before the night
  change` and a process-level poke (`sent theme=6`, then `7`, `procState=2`).
  Afterwards Home's open archive descriptors pointed at the new active inode.
  The night refresh still sent 414 paced callbacks (about 70 s), so icons
  changed one after another.
- `115000804`: the hot-reload reconciliation sent one batch of 308 packages. Home
  logged `processItemsChanged ... modified:307`, with `runtimeStatusFlags=0` for each
  entry. Its `ThemeIconLoader` reloaded the icons in about 5 s.
- Six manual toggles in drawer mode (08:15–08:16) each logged a swap, a poke and
  one batch. Home processed `modified:307` per batch, and visible icons reloaded
  within about 2.5 s of the night change. The events log showed no `wm_relaunch_*`
  during the toggles.
- A full-resolution home screen capture in dark mode matched the dark copy for
  every sampled Dock and folder icon. The user reported the test as passing.

Follow-up on 2026-11-25 with 115000805–115000808 on the same phone:

- After the 804 → 805 hot reload, pokes restarted at 6 while Home had reached 13 and were
  ignored; the sequence is now a JVM system property and continues across reloads.
- A drawer screenshot in light mode matched the dark copy for about 20 of 24 visible icons;
  only a badge-redrawn icon matched the light copy. This established the drawer cache limit.
- A screen-off after a switch logged `Launcher rebuilt after the icon archive change:
  restarted uid=10138`; Home restarted at once and held the current copy.
- An import was registered at 13:35:17, the copy replaced the applied original 1 s later
  (`applied light after apply`) and Home was rebuilt immediately. After a later switch and
  screen-off the user reported both the home screen and the drawer correct.

## Remaining acceptance

- Classic (non-drawer) layout; drawer rows and folder pages first built after a
  switch (see *Icons not on screen*).
- Slow path (Themes swaps after the night change): timing and artwork.
- Work profiles: the batch is sent per enabled profile; not yet exercised.
- FrameTimeline: compare the same apps and page against the system-following
  archive (see [maml-launcher-performance.md](maml-launcher-performance.md)).
- Survive a real `ValidateThemeTask` with each copy active, and a reboot.
- Palette on/off transitions with flattened palette selectors.
