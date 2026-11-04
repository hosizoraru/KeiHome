# App storage and complete icon preview caching

## Cache overview

Settings shows a snapshot of the private `cacheDir`, grouped into active icon
snapshots, legacy built-in pack copies, import/export working files and other temporary
files. The last category includes platform or diagnostic files in that directory.
The scan runs off the UI thread, does not follow symbolic links, and reports an
incomplete result if any file cannot be read. It can be refreshed from the top
bar and is remeasured after clearing previews. File lengths are logical bytes,
not filesystem block allocation.

The separate in-memory figure is an estimate based on the three bounded preview
caches, and can include shared bitmaps more than once. Clearing previews evicts
only those memory caches; it does not delete active snapshots, imported packs,
settings, or import/export files that another process may still be using.
Android can independently reclaim files in its cache directory.

Built-in profiles now share content-addressed objects in the APK and create no
pack-cache copies. Their APK bytes are not part of this private-cache statistic.
See [shared built-in resources](builtin-icon-storage.md) for the format, migration
and device checks. The regenerable copies from older App versions are removed
on the first built-in scan; user imports and active native themes are separate.

Preview requests treat a snapshot removed during loading as temporarily
unavailable while still propagating coroutine cancellation. This prevents a
stale ZIP reference from crashing the app during theme changes or cache eviction.

## MMKV

App-owned display preferences now use Tencent MMKV **2.4.3**, the stable
[2026-10-16 release](https://github.com/Tencent/MMKV/releases/tag/v2.4.3).
The standard Android JNI artifact is pinned in the version catalog. The launcher
payload imports no C++ Prefab packages, so unused automatic Prefab processing is
disabled; the payload's C++ sources, ABI and runtime protocol are unchanged.

`AppDisplayStorage` lazily initializes MMKV in KeiHome's own Application context.
`app_display_v1` is a single-process store under `files/mmkv`. It includes App
appearance/Monet UI preferences, page gestures, top blur, automatic apply,
ColorOS choices, fallback selection, component associations and remembered
browser filters/sort direction.

On first open it:

1. Imports the existing `app_display` SharedPreferences.
2. Verifies every value using the matching primitive/string/set API.
3. Syncs the imported values, then records and syncs a migration marker.
4. Exposes the store only after migration completes.

The original XML is retained. Completed migrations never reimport stale XML over
new choices. MMKV's untyped `getAll()` is not used; known key types and key
prefixes are enumerated explicitly. Existing StateFlows continue to publish UI
changes, so switching stores does not introduce persistence reads during
recomposition or remove necessary refresh.

Framework remote preferences retain API 102 authentication and acknowledgement.
OEM-injected handoff/receipt storage retains its existing process and atomic
update contracts. They are independent of this App-owned migration.

The migration follows the official
[Android tutorial](https://github.com/Tencent/MMKV/wiki/android_tutorial).
Device tests verify boolean, integer, long, float, Unicode string and string-set
values, store reopening, and skipping a stale legacy source. An explicit upgrade
check also verified all **42** original values on the target AVD.

## Complete previews

The browser previously cached the base colored pair while separately reopening
the current ZIP and decoding native overrides, Monet masks and backgrounds on
each return to a row.

`CurrentIconPreviewRepository` caches the complete result with the established
Android `LruCache`:

- Four MiB charged by distinct bitmap allocations; negative/tiny results cost
  at least one KiB to bound entry overhead.
- Keys contain source revision, pack and entry identity, file path, size,
  modification time and resource configuration.
- A semaphore bounds in-flight work to two across suspensions, with a dispatcher
  limited to two workers; 32 fixed mutex stripes coalesce requests
  without an unbounded map of locks or private coroutine jobs.
- Native day/night images are decoded first. Existing static rendering runs
  only when a mode still needs fallback artwork.
- Bounded ZIP reads and PNG dimensions remain enforced; large admitted native
  layers downsample to at most 256 pixels before display.
- Clearing advances a generation before eviction. Old work cannot refill the
  cache after invalidation. Caller cancellation stays with the visible row.
- Package/overlay changes, explicit refresh and the Settings clear action retain
  the necessary invalidation paths. Eviction drops references without recycling
  bitmaps still used by Compose.

The existing APK and pack caches remain separately bounded at four MiB each.
The complete cache includes shared monochrome layers only once in its byte
accounting. Bitmap payloads are not written into MMKV.

Device tests verify that eight concurrent identical requests produce one load,
return all cached mask layers, and reload after clearing. A second test clears
during an in-flight load and verifies that its obsolete result is not republished.
The suspension test also verifies that eight different requests never exceed two
in-flight loads. These establish cache behavior, not a physical-device frame-rate improvement or
a universal MMKV-vs-SharedPreferences benchmark.
