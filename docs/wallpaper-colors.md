# Wallpaper colors and icon schemes

The Monet screen follows the stock Google Pixel flow: choose a wallpaper color,
preview a scheme, then apply it. MIUIX supplies the cards, picker, check indicators,
buttons and existing app chrome. The application target is launcher themed icons;
the preview's neutral surface illustrates the palette, not a SystemUI overlay.

## Reading HyperOS wallpapers

On the tested HyperOS 4 image, `dumpsys wallpaper` reports
`isColorExtracted=false` for its static ImageWallpaper. Both standard
`getWallpaperColors()` calls return null, although the wallpaper file exists.
The lock screen shares the home wallpaper (`getWallpaperId(FLAG_LOCK) == -1`).

The existing system-server worker uses `WallpaperManager.getWallpaperFile()` to
decode a small static thumbnail. This also avoids losing useful seeds when the
public primary/secondary/tertiary summary contains only one color. Reported
colors supply the fallback for wallpapers without an accessible static image.
It quantizes and scores the pixels with MaterialKolor's Material Color Utilities.
Up to four representative seeds are returned; a grayscale image uses its actual
dominant cluster instead of a fabricated blue seed. A shared lock wallpaper reuses
the home result. Images and file descriptors are released after extraction.

Only seed integers cross to the app in the user-scoped `keihome_wallpaper_seeds`
setting. The app gains no storage or wallpaper-reading permission. Unchanged
wallpaper IDs/reported colors reuse the result. Wallpaper colors and launcher
palette notifications wake the existing worker; its heartbeat is a fallback.
Live wallpapers without reported colors or an accessible image remain unavailable.

## Selection and application

- Sources: home wallpaper, lock wallpaper, or six fixed basic colors.
- Styles: Tonal Spot, Vibrant, Neutral, Monochrome (MCU's `SPEC_2021`).
- A wallpaper choice follows its ranked color index when the wallpaper changes;
  if the new wallpaper has fewer colors, it follows the first available seed.
- Basic colors remain fixed. Every style still adapts to light/dark mode.
- Previews and the runtime share `MonetSchemes`. Light icons use primary90/30;
  dark icons use secondary20/primary80, matching the existing AOSP icon roles.
- The API 102 request carries the complete choice with its revision. Only a native
  acknowledgement followed by persisted settings produces the application receipt.
- `keihome_monet_customization` stores the accepted source/style/index/basic color.
  Native foreground/background and malformed-mask fallback use the existing channel.
- Selecting a manual launcher color still releases automatic ownership. Applying
  a new scheme explicitly takes ownership again.

## API 102 handover

Retirement interrupts an outstanding isolated-probe wait and allows up to five
seconds for tracked workers to exit. If an older runtime refused the install-time
reload while busy, the app makes one official `hotReloadModule` request after its
framework service connection. Apply stays unavailable while the system target's
loaded version differs from the app. This does not restart the launcher and does
not replace the resident executable native foundation; no native ABI changed here.

## Focused validation: 2026-10-30

Only `emulator-5580` / `KeiOS_HyperOS4_Phone` was used. Every installed APK had a
strictly increasing versionCode. Final Release: **1793305279**.

- The original static wallpaper yields two scored colors: `#3F4AE2` and `#517BCF`.
  A temporary green wallpaper changed the published seed and visible themed icons
  to green. The original image was restored and the fixture app was uninstalled.
- Home wallpaper / Vibrant and a fixed orange choice were accepted through the
  app's API 102 request and matching receipt. The final choice is Home wallpaper /
  Tonal Spot, color index 0. A black intermediate orange-light capture is excluded
  from visual acceptance.
- Settled light and dark images show KeiHome and both ErinAO packages using the
  selected foreground/background pair. HyperPods-X remains a normal color icon
  in both images. A transient UNKNOWN probe no longer drops a known malformed
  mask result for the same installed source; first-ever UNKNOWN remains unknown.
- The color logic was checked on Release 1793303410. Release 1793304542 moves
  the scheme check indicator inside its swatch to fit Japanese labels; its API 102
  install and final UI/dark-icon checks passed. Release 1793305279 additionally keeps
  the fixed basic-color choice when tapping a wallpaper seed. Japanese labels fit;
  all translatable keys exist in English, Simplified Chinese and Japanese.
- Across the post-cold-boot wallpaper/color operations, launcher PID/start ticks
  remained `9692 / 3609`; system_server remained `8839 / 2743`. Final installation
  `1793304542 -> 1793305279` also preserved SystemUI `568 / 199972` and completed
  Java hook reconciliation without requesting native replacement.
  The installer's final name-based check saw multiple temporary SystemUI PIDs;
  a separate follow-up confirmed each original PID and start time against the
  preceding deployment's identity record. That follow-up is recorded explicitly.
- Focused runs covered 38 tests cumulatively: nine color/seed tests, 23 action
  tests, three customization tests and three fallback-retention tests. Release
  assembly, R8 and required Release lint passed. The adjusted instrumented detail
  tests were not rerun, and no full test suite was rerun for these changes.

Evidence is saved locally under `/tmp/keihome-wallpaper-validation/`, including
`install-final-selection/post-install-identity.json`, `final-home-light.png`,
`final-home-dark-restored.png`, `picker-zh-final.png` and `picker-ja-final.png`.
The AVD ends in dark mode with its original wallpaper, app language following the
system, and wallpaper-derived themed icons enabled. The temporary wallpaper app,
its device backup and helper jars were removed.

### Unresolved stability observations

The entire session was not restart-free. Around 03:03 +0800, system_server hit a
watchdog. Traces showed main waiting for AlarmManager and an AlarmManager thread
waiting for AppOps while holding AlarmManager/AMS; the AppOps owner was not
captured. KeiHome's worker was submitting native readiness work, not decoding a
wallpaper. Causality remains unassigned. An authorized AVD cold restart established
the later process baseline above.

At 03:52:07, SystemUI PID 9672 had a MultiTaskSwitch gesture-monitor input ANR.
Its later main-thread sample was waiting in synchronous `IWindowSession.remove`
while removing the navigation bar after a configuration change. WMS/ATMS logs
showed 14-16 second lock waits involving `getTaskBounds`, but no complete Binder/
owner chain ties these waits to KeiHome. The exact earlier AppOps chain was not
recovered here. SystemUI was ended after the error; one replacement timed out
starting, then PID 568 recovered. Launcher and system_server survived this event.
High CPU pressure is context, not proof of cause. Saved traces and events are
`systemui-gesture-anr.txt` and `events-final.txt`.

One final `am start -W` foregrounding attempt also reported a timeout before the
app became interactive; subsequent navigation and language checks succeeded.
These observations and the earlier focus ANR in `icon-refresh.md` remain open.
Successful color/installation checks do not establish that those stability issues
are fixed. Live wallpapers without accessible images/reported colors and global
SystemUI palette customization are outside this implementation's validation.

## References

- [Google Pixel Wallpaper & style](https://support.google.com/pixelphone/answer/7289143?hl=en)
- [Repainter](https://repainter.app/) and its
  [privileged integration service](https://github.com/kdrag0n/android_packages_apps_RepainterServicePriv)
- [ColorBlendr](https://mahmud0808.github.io/ColorBlendr/)
- [MaterialKolor 5.0.1](https://github.com/jordond/MaterialKolor/tree/5.0.1)

Repainter/ColorBlendr are interaction and architecture references; no implementation
code from either project was copied.
