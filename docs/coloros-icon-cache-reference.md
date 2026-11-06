# ColorOS icon cache / memory reference for launcher-open

Research date: 2026-11-07. Scope: read-only OEM source research, with one passive
retained memory snapshot. No production edits, builds, installs, UI interaction, app
launch/stop, theme changes, or physical-device investigation.

## Result

ColorOS provides useful examples of selecting the active night asset before its
decode, separating monochrome pixels from palette colors, and cloning drawable
state while sharing bitmap storage. These are architectural references for the
HyperOS Rust MAML path, not evidence that its Java implementation, memory usage,
or launch performance is directly transferable. Its own animation path also
rasterizes dynamic icons and can copy hardware bitmaps to software.

## Provenance and reproducibility

All paths below are relative to the KeiHome repository unless stated otherwise.
Evidence aliases:

- `INPUT` = `.artifacts/inputs/coloros/`
- `OLD` = `.artifacts/research/coloros-converter/`
- `CACHE` = `.artifacts/research/coloros-icon-mechanisms/sources/`
- `E` = `.artifacts/research/launcher-open-20261107/coloros-evidence/`
- `SRC` = `E/sources/` (fresh targeted JADX output; filename is full Java class).
- `ASM` = `E/smali/` (DexSQL whole-class disassembly, not pseudocode).

Current device identification: `emulator-5574`, AVD `ColorOS17_API37`,
product/model `PMX110`, device `OP659FL1`. Launcher package:
`com.android.launcher`; installed path:
`/system_ext/priv-app/OplusLauncher/OplusLauncher.apk`. Framework:
`/system/framework/oplus-framework.jar`.

| Input | SHA-256, local and installed copies identical |
| --- | --- |
| `INPUT/OplusLauncher.apk` | `b67118677ed9f6bc23ecbd22a2f7f4826b4f31c08641347cc58648fa986196fc` |
| `INPUT/oplus-framework.jar` | `52c4783bc6027756b56dcb7780d838dd4b36b9726b28f4db2f0a03d49237a0ae` |

Raw commands, timestamp, exit codes and local hashes: `E/identity.json`.
Device responses: `E/adb-inventory.txt`, `E/avd-name.txt`,
`E/launcher-path.txt`, `E/device-hashes.txt`, `E/launcher-meminfo.txt`.
No pull was necessary. Only inventory, AVD identity, installed path, checksum
and passive memory reads were performed; no other device was targeted.

DexSQL MCP opened the two retained inputs as `dataset-1`, inspected
`classes`/`methods` schemas and queried actual descriptors. SQL uses `LIKE`;
this server rejected `ILIKE`. The dataset is temporary; the inputs and saved
disassemblies are the reproducible evidence. CLI example:

```bash
dexsql -t 2 decode -i .artifacts/inputs/coloros/OplusLauncher.apk \
  --class 'Lcom/android/launcher3/icons/FastBitmapDrawable$FastBitmapConstantState;'
```

JADX output is navigational pseudocode. Important cache, source-selection,
mask and clone claims also have corresponding disassembly in `ASM`.
Class filenames and method names below are the stable evidence anchors.

## 1. Source choice before decode: real but limited

`OLD/RectangleStyle.java:17`, `tryLoadThemeContent`, computes current mode and
loads `rec_night` or `monochrome` before the rectangle foreground/background
fallback. `DaylightStyle.java` uses `daylight`, then `MaterialStyle`'s
`material` path. `NightShadowStyle.java` uses `rect_shadows` and its own
fallback. These are style-specific asset names, not two full icon sets that
must always be decoded. See `ASM/rectangle.smali`.

`OLD/OplusUXIconLoaderImpl.java:370`, `loadUXIconByPath`, tests that a candidate
exists before `Drawable.createFromPath`. The `findAppDrawable` chain then
tries product/carrier/base/stock locations as applicable; failures can cause
multiple probes. `loadUxIcon` at line 256 still obtains the original app
drawable using `res.getDrawable(id, null)` before themed substitution. Thus
"the entire ColorOS pipeline avoids original-image decoding" is false.
See `ASM/ux-loader.smali`.

The stronger case is `CACHE/BaseIconCache.java:273`,
`updateTitleAndIconLocked`: choose `INDEX_ICON_DARK` or `INDEX_ICON` using
`mIsDarkIconActive`, fetch that blob, then `BitmapFactory.decodeByteArray`
with preferred `HARDWARE` config. It does not decode both day/night blobs
in this path. It can additionally restore a mono layer. `addIconToDB` stores
regular, dark and mono representations, so storage and generation can still
contain both variants. See `ASM/base-icon-cache.smali`.

Transfer: resolve asset identity and render mode before ZIP extraction or
image decode. Keep fallback order explicit. This supports lazy active-mode
loading; it does not establish that every alternate representation can be
discarded or that ZIP destruction is cheap.

## 2. Cache identity, colors and configuration are separate concerns

`OLD/OplusUXIconLoadHelper.java:57`, `getDrawable`, is synchronized. It checks
the PM theme cache, reapplies the current dark filter on hits, and stores the
result on misses. `configurationChanged` at line 126 reacts to icon-config,
theme-change and UI-mode bits; `updateExtraConfig` in the loader marks the
style state dirty. `checkConfig` parses extra configuration and updates
style/dark-filter state. See `ASM/ux-load-helper.smali`,
`ASM/ux-loader.smali`, and `ASM/dark-filter.smali`.

The PM adapter does not implement a new LRU:
`SRC/android.app.UxIconPackageManagerExt.java:160` delegates
`getCachedIconForThemeHelper(packageName, id)` to the application PM extension's
`getCachedIcon(packageName, id)`; `putCachedIconForThemeHelper` delegates
`putCachedIcon`. See `ASM/pm-theme-cache.smali`. Density, user, palette,
eviction and full framework cache invalidation cannot be inferred from these
two visible arguments alone.

Launcher cache evidence is more explicit:

- `CACHE/BaseIconCache.java:73`: `new HashMap(50)`. Fifty is initial capacity,
  not a 50-icon limit or byte budget. Keys are `ComponentKey(component,user)`.
  DB primary key is `(componentName,profileId)` (`IconDB`, line 752).
- Low-resolution entries are distinct in eligibility, and full-size lookup
  can reload them. Entries carry `iconSystemState`; lookup compares it before
  accepting the entry. Base system state contains locale tags and SDK level.
  `SRC/com.android.launcher3.icons.IconCache.java:280` delegates per-package
  state to the provider; the provider adds the date for calendar icons.
- `setDarkIconActive` (`CACHE/BaseIconCache.java:621`) changes the active-column
  flag and posts a worker task when it changes. That task clears the memory map
  under synchronization (`BaseIconCache.a`, line 128). See
  `ASM/dark-cache-runnable.smali` for `b8.a.run`, switch case 0. This is explicit
  asynchronous invalidation, not proof that night mode is part of each map key.
- `SRC/com.android.launcher3.icons.LauncherIconProvider.java:38`,
  `getSystemIconState`, adds `with-theme` / `no-theme` to the platform mask
  state. `setIconThemeSupported` drops or lazily rebuilds the grayscale map.
  This does not prove that every palette change invalidates every bitmap.
- `SRC/com.android.launcher.theme.LauncherIconConfig.java:834`, `update`,
  rereads system configuration and parses icon style, shape, sizes and pack.
  This is a source implementation; none of its mutating methods was invoked.

Transfer: distinguish immutable source/cache identity from mutable render
state. A proposed native identity should account for source generation,
component/user, selected asset and mode, dimensions/density, mask/foreground
geometry, and any color baked into pixels. A separate palette/config epoch is
appropriate only where updates reach all render instances. These are design
requirements inferred from the evidence, not an exact ColorOS key schema.
Do not copy its unbounded `HashMap` or assume package/resource ID is sufficient.

## 3. Monet: monochrome pixels plus draw-time paint

`SRC/com.android.launcher3.icons.BaseIconFactory.java:340`,
`createBadgedIconBitmap`, creates the normal icon, obtains adaptive monochrome
content if supported, and renders mono content into `ALPHA_8`.
`BitmapInfo` holds `icon`, `mMono`, and `mWhiteShadowLayer`; mono does not mean
the regular bitmap has disappeared. DB mono restoration also uses temporary
alpha storage and a hardware copy (`BaseIconCache`, line 287).

`SRC/com.android.launcher3.icons.ThemedIconDrawable.java`:
`getColors` (line 68) selects colors from night mode, custom ColorTools values,
and system accent/neutral resources. Its constructor installs separate
`SRC_IN` color filters on background/mono paints. `drawInternal` (line 102)
draws existing layer bitmaps with those paints. `changeBackgroundColor`
(line 95) updates a paint filter and invalidates the drawable; that method
does not decode source images. The constant state shares `BitmapInfo`.
See `ASM/themed-icon.smali` and `ASM/bitmap-info.smali`.

This proves render-time palette application for this monochrome drawable.
It does not prove that the entire OEM "material" style is Monet, or that the
wallpaper-to-ColorTools-to-all-existing-drawables update chain is complete:
that listener chain was not traced in this bounded pass.

Transfer: share immutable mono/alpha content and update per-instance palette
state without reacquiring the pack. HyperOS Rust MAML has different native
image ownership: `docs/monet-image-ownership-8593.md` already records that the
replaced native image is a drawable vtable object and must use its verified
clone/drop contract. Java `Bitmap` sharing is not permission to alias or free
Rust objects differently. Palette changes also need clone/snapshot coherence.

## 4. Masks, artwork scale and the cost of composition

`OLD/DefaultStyle.java:182`, `buildFromAppIconAfterDesk`, preserves adaptive
foreground/background separation. Transparent nonadaptive icons receive a
background and foreground inset; other opaque icons can be treated as the
background. `buildAppIconContentPebbleRectDaylight` (line 213) can edge-extend
opaque art. `OLD/OplusUXIconLoaderImpl.java:540`, `applyScaleFgIfNeeded`,
adds style-dependent insets. This is per-artwork geometry, not simply resizing
every source to the same square. See `ASM/ux-loader.smali`.

`SRC/android.app.OplusIconNormalizer.java:36` retains an `ALPHA_8` scratch
bitmap, byte buffer and boundary arrays. `getScale` is synchronized, rasterizes
for alpha/convex-area analysis, and can reuse an adaptive normalization result.
This reduces scratch allocation, but still performs rendering/pixel scanning
when analysis is needed.

`SRC/android.graphics.drawable.AdaptiveIconDrawableExtImpl.java:79`,
`drawIcon`, clears and redraws background/foreground into a retained layers
bitmap before applying its mask shader. `updateParams` (line 207)
allocates that bitmap only when dimensions differ, using `ARGB_8888` in
Display P3. `updateBounds` updates geometry. See `ASM/adaptive-mask.smali`.
The retained bitmap is scratch reuse, not proof of a final composited-image
cache or free masking.

Transfer: cache normalized geometry with the source/config identity and reuse
appropriately sized scratch storage outside the launch-critical work. Preserve
source aspect, foreground inset, mask and color-space semantics. Do not add a
fresh normalizer scan or mask rasterization to each launch snapshot.

## 5. Launch clones and screenshots: sharing has exceptions

`SRC/com.android.launcher3.views.OplusFloatingIconView.java:265`,
`fetchAndFillLocalIcon`, reads the visible source icon. For
`FastBitmapDrawable`, it calls `getConstantState().newDrawable()`. For adaptive
and some edited icons it also creates a new drawable and mutates its state;
dynamic icons can instead go through `BitmapUtils.drawable2Bitmap`.
`getOplusIconResult` (line 618) selects the current drawable for certain item
types and for an already-themed drawable when themed icons are enabled. Its
other adaptive-animation branches can call `Utilities.getFullDrawable`,
including a badge worker lookup with a 100 ms timeout. Thus this is not a
universal no-load or nonblocking path. See `ASM/floating-icon.smali`.

`FastBitmapDrawable.newConstantState` and its
`FastBitmapConstantState.createDrawable` retain the same `Bitmap` reference,
with new drawable state. `BitmapInfo.clone` likewise retains the normal,
mono and shadow bitmap references. This is shallow pixel sharing, not
`Bitmap.copy`. Exact DEX evidence: `ASM/bitmap-constant-state.smali`,
`ASM/bitmap-info.smali`.

Exceptions matter:

- `SRC/com.android.launcher3.anim.floatingdrawable.IconDrawableView.java:194`,
  `setIconDrawable`, reads the bitmap, copies `HARDWARE` content into
  `ARGB_8888` for background pixel sampling, and otherwise can rasterize a
  drawable through `BitmapUtils`. This branch can allocate on animation setup.
- `SRC/com.android.launcher3.shortcuts.animations.ShortcutSmoothAnimationManager.java:73`,
  `createNewIconViewSnapshot`, can rasterize mono/theme or dynamic art into a
  bitmap and hands it to the floating view as its screenshot. Its reset at
  line 182 drops view/drawable/screenshot references; this is Java reference
  release, not evidence of immediate native/GPU destruction.
- `SRC/com.android.launcher3.anim.floatingdrawable.AbsDrawableView.java:174`,
  `getScreenshot`, creates an `ARGB_8888` target, draws the floating view and
  returns it. Source-view reuse does not eliminate screenshot rasterization.

Transfer: preserve the currently displayed variant and keep mutable animation
state separate from immutable source pixels. Apply only the verified native
clone/ownership contract, retain resources until all render/snapshot users
finish, and avoid archive reopening for a snapshot where an owned current
render result is available. Do not port Java GC timing or `ConstantState`
semantics directly to Rust MAML. Native queue/release implementation remains
with the main task.

## Passive memory evidence and limits

`E/launcher-meminfo.txt`: PID 1801, total PSS 177512 KB, total RSS 411688 KB,
total swap 4 KB, Native Heap row PSS 66573 KB, Java Heap summary PSS 30096 KB,
Graphics summary PSS 0 KB. Native Allocations reports 78 malloced bitmaps
(117 KB) and 76 nonmalloced bitmaps (15905 KB), whereas the trailing BITMAPS
section reports 0 KB / count 0. The latter therefore cannot establish zero
cached bitmap memory. These allocation categories are not a per-icon cache
breakdown, and hardware/native/shared accounting must not be double-counted.

There is no cache hit/miss counter, per-icon residency measurement, heap dump,
launch trace, before/after benchmark, or matched HyperOS comparison here.
The device was already running; its foreground/warmth/workload was not
controlled or changed. The snapshot cannot establish an icon-memory saving
percentage or explain main-task jank.

Safe handoff priorities: active-mode selection before decode; source/palette
separation; exact cache invalidation; pixel sharing under verified ownership;
and snapshot reuse with bounded scratch allocation. Treat all performance
benefits as hypotheses pending the main task's native queue work and physical /
AVD QA. No Dalvik/ART optimization is claimed portable to the Rust renderer.
