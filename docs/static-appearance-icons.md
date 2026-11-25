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
     default path. Names in `keihome-system-fallback.txt`, authored programs
     (clock, calendar, weather) and unrecognized groups stay unchanged.
     Unchanged entries are copied raw by `RawIconArchive`.
   - Both copies pass the origin's own admission (`ConvertedIconArchive.validateIconsFile`).
   - Their SHA-256s are persisted in `/data/system/keihome-icon-imports/appearance-variants-v1.json`
     before Themes receives them. `ConvertedIconRuntime` approves a copy only
     while its primary converted digest is registered. The existing
     `converted-drm-v1.json` format is unchanged.
3. **Themes, private bridge** (`0x004b4803`). `ThemeAppearanceVariants` checks the
   grant and the native component's bytes, then copies both stages into its private
   `keihome_appearance_variants` directory. It verifies each copy's digest and keeps
   the two most recent records.
4. **Swap** (`0x004b4804`). Themes replaces `/data/system/theme/icons` only when
   its digest equals a registered primary or copy. It writes a verified temporary
   file beside the active archive and matches its owner, mode and SELinux label.
   It checks that the active archive did not change, renames atomically, and then
   stages the *opposite* copy as `/data/system/theme/.keihome-appearance-{light,dark}.icons`.
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
     so KeiHome tracks the highest sequence it delivered.
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
   - A successful rename requests a full refresh, so the isolated resource probe
     (about 2 s) is skipped.

If the rename could not run (first switch after boot, a quick second toggle, or
a palette change), Themes swaps afterwards. KeiHome then pokes Home and sends
the same full refresh (`scheduleArchiveRefresh`).

A *global* theme configuration change cannot replace the poke. Two drafts were
tested:

- `MiuiConfiguration.sendThemeConfigurationChangeMsg(0x8)`.
- A global theme change with flag `2097152`.

ActivityTaskManager relaunched every activity that does not handle
`CONFIG_THEME` (`0x400000`) in both cases. That includes Home, which returns to
its first page, and the foreground app, which appears to exit
(`wm_relaunch_resume_activity` with `400000`).

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
  the staged files are removed. Turning the option off affects only the next
  import. An archive imported with the option keeps swapping when it is re-applied.
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
  --tests 'os.kei.hyper.home.xposed.LauncherIconBatchTest'
```

They cover selector recognition, exclusions, the dark default path, legacy
templates, refusal without selectors, target policy, and framework approval
inheritance. They also cover pre-dispatch rename preconditions: a matching
transition, a single use, foreign or changed files, and staging paths. Batch
tests cover eligibility (single entry, not suspended) and the alternating order
that keeps Home from dropping a repeated batch. `LauncherThemePoke` reflects on
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

## Remaining acceptance

- Classic (non-drawer) layout; drawer rows and folder pages first built after a
  switch (see *Icons not on screen*).
- Slow path (Themes swaps after the night change): timing and artwork.
- Work profiles: the batch is sent per enabled profile; not yet exercised.
- FrameTimeline: compare the same apps and page against the system-following
  archive (see [maml-launcher-performance.md](maml-launcher-performance.md)).
- Survive a real `ValidateThemeTask` with each copy active, and a reboot.
- Palette on/off transitions with flattened palette selectors.
