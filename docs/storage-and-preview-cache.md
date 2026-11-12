# App storage and complete icon preview caching

## Cache overview

Settings shows a snapshot of the private `cacheDir`, grouped into active icon
snapshots, legacy built-in pack copies, import/export working files and other temporary
files. It also includes the separate Apple/Perfect stores, logs and persistent
local imports under `noBackupFilesDir/icon-packs/`; see
[cache storage](cache-storage.md) for their classification and management links.
The temporary category includes platform or diagnostic files in the cache directory.
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

App-owned local preferences use Tencent MMKV **2.4.3**, pinned to the
[2026-10-16 release](https://github.com/Tencent/MMKV/releases/tag/v2.4.3).
The standard Android JNI artifact is pinned in the version catalog. The launcher
payload imports no C++ Prefab packages, so unused automatic Prefab processing is
disabled; the payload's C++ sources, ABI and runtime protocol are unchanged.

`AppLocalStorage` lazily initializes MMKV in KeiHome's own Application context,
checking the App package and UID before opening a single-process store under
`files/mmkv`. The existing `AppDisplayStorage` wrapper still migrates legacy
`app_display` into `app_display_v1`, with the unchanged migration marker
`__keihome_app_display_migrated_v1`. It includes App
appearance/Monet UI preferences, page gestures, top blur, automatic apply,
ColorOS choices, fallback selection, component associations and remembered
browser filters/sort direction.

The current extension migrates five additional App-owned SharedPreferences
groups: log settings and four related import groups. Their destinations are:

| Legacy App-owned groups | MMKV destination | Contents |
| --- | --- | --- |
| `app_logs` | `app_logs_v1` | App log level |
| `native_icon_associations`, `native_icon_sources`, `native_icon_operation`, `native_icon_auto_navigation` | `native_icon_state_v1`, one serialized `state` value | Source/component mappings and history, pending operation, result, navigation consumption, original request and component palette metadata |

The simple display/log preference migration, on first open:

1. Imports the existing SharedPreferences and checks the imported entry count.
2. Verifies every value using the matching primitive/string/set API and checks
   that its key exists.
3. Requests a sync of the imported values, checks encoding of the migration
   marker, then requests another sync. Log settings use
   `__keihome_migrated_v1`; the display marker remains the one listed above.
4. Returns the store only after those steps complete.

The original XML is retained. Completed migrations do not reimport stale XML
over new choices. Simple migrations use the legacy SharedPreferences entries
with their original types; MMKV's untyped `getAll()` is not used. Log-level writes
check `encode` and request sync before changing the collector's active level;
the settings screen performs the write on the I/O dispatcher before updating
its displayed selection. Existing display StateFlows retain their UI updates
without adding persistence reads during recomposition.

### Serialized import state and recovery

`IconImportLocalStore` reads all four legacy import groups together and persists
one versioned serialized snapshot. The presence of `state` is its migration
boundary: subsequent opens decode that value rather than replaying the retained
XML. A malformed stored snapshot fails instead of silently restoring stale XML.
The snapshot includes associations, source history, pending token/source, result,
consumed navigation tokens, the original request and component palette receipts.

Store operations are serialized. Each changed snapshot checks `encode`, requests
`sync()`, and checks that readback matches the serialized value before replacing
the committed in-memory snapshot and publishing it through StateFlow. Receipt
persistence also precedes releasing a completed operation's handoff or consuming
its automatic navigation. These checks establish local write/readback and
publication ordering. MMKV `sync()` requests a flush; its void return is not a
filesystem durability acknowledgement or a native import success receipt.

The local snapshot and framework request are not one atomic transaction. Local
pending state is saved before the API 102 request is published. An unconfirmed
Binder submission or timeout retains the original request and handoff and offers
an uncertain/recheck state. Cancellation or failure to save an uncertainty
message also leaves the pending operation available for reconciliation. Recheck
reuses the saved token, user, start time, source, converted origin and automatic
apply choice rather than replacing them with a new operation. A migrated pending
operation without a saved full request requires the matching original framework
request. Submission and recheck share a publication mutex; the local store
monitor is not held across Binder or UI work.

Palette metadata belongs to the operation that prepared the import. Its saved
`preparedPalette` value becomes a component receipt only for a matching current
successful import. The UI uses that confirmed component value; recovery without
such metadata only fills missing display metadata, preserving an existing
component choice rather than overwriting it with a later preparation for the
same source.

### Process boundaries and validation

Framework `RemotePreferences` retains API 102 authentication, request publication
and native receipt contracts. The Themes-host-owned `keihome_auto_apply` store
remains SharedPreferences, including its checked ticket authorization and
single-use consumption commits. MMKV is initialized only for KeiHome's App
storage; none of this migration initializes it in an injected host. OEM handoff
and receipt storage keeps its existing process ownership and atomic update
contracts.

The migration follows the official
[Android tutorial](https://github.com/Tencent/MMKV/wiki/android_tutorial).
The display-store tests cover primitive, Unicode string and string-set values,
reopening and skipping stale XML; its original upgrade check covered **42**
display values. The current extension passed **38 focused JVM tests** and
**16 Android device tests** on HyperOS AVD `emulator-5580`. These cover grouped
migration, the AVD's existing XML upgrade, stale XML, competing deliveries and
navigation, failed encoding, caller cancellation, unconfirmed publication,
recheck serialization, and log range/export/clear behavior.

Actual UI checks verified the migrated DEBUG level, changing it back to INFO,
and keeping INFO after the App process reopened. All five legacy XML files stayed
byte-identical. ZIP sharing opened the system chooser, and its exported archive
passed CRC validation. Both automatic and manual native theme application were
exercised; returning to the App reconciled the applied state. A live completed
receipt may legitimately lose its already-consumed one-shot navigation token;
upgrade tests retain all other receipt fields and require saved consumption
before admitting that replay difference.

The final Release artifact, screenshots, operation receipts and host process
identities are recorded in
`.artifacts/research/mmkv-local-state-20261104/acceptance.json`. This is AVD
acceptance; it does not establish physical-device speed or filesystem durability.

## App log archive I/O

Log settings live in MMKV; log contents remain ordinary files under
`files/app-logs`. `AppLogStore` shares one `HourlyAppLogArchive` per canonical
directory between direct records and logcat collection. The archive retains a
buffered append stream for the active hourly file, closing it on rotation,
replacement or archive close. Every accepted append flushes synchronously;
there is no timer or asynchronous append queue, and no added fsync guarantee.

Filenames remain `<epoch-hour>.log`, and records remain UTF-8
`timestamp<TAB>content<LF>`. The **1 MiB per hour** cap accounts for the complete
encoded line, including timestamp, tab and LF; a line that cannot fit is rejected
whole. The App sanitizes CR/LF in content before appending. Seven-day retention
uses hourly buckets, pruning at archive construction and once per clock hour on
append. Only numeric regular `.log` files are owned; unrelated files, directories
and symbolic links are excluded from pruning and range scans.

Stats, export and clear first filter filename hours against the inclusive
requested range. Bounds use `floorDiv` on millisecond timestamps without
multiplying filename hours, avoiding overflow and incorrect selection around
epoch zero. Narrow windows therefore do not read unrelated retained hourly logs.
Individual timestamped records are still filtered inside each selected hour.
Malformed records are ignored by stats/export and retained by clear. Inverted
ranges return zero stats/removals and a ZIP containing guidance and export-time
environment metadata; a range with no matching records has the same empty result.

Stats and export open stable file descriptors and capture their byte-length
limits under the writer lock, then release that lock before streaming records.
Later appends are beyond those limits; an already captured reader keeps its
prior contents if clear replaces the pathname. Streams close on both success
and failure, including snapshot acquisition or ZIP output failures. ZIP exports
contain `README-KeiHome.txt`, `environment.txt` and only nonempty selected hourly
logs; file export writes a temporary ZIP and atomically publishes it after
completion. The README is explicitly application-supplied guidance, separate
from captured and potentially untrusted log text.

The log settings page leads with a clickable MIUIX source card directing Dock,
icon and Hook issues to LSPosed. The App section and export action explicitly say
App logs, with a visible note that they exclude LSPosed. App ZIPs are for App UI,
settings and App-process failures. They never read or merge LSPosed logs or native
feature logs; environment metadata does not turn them into a feature diagnostic trace.

`AppLogStore.export` collects environment information on the existing export IO
path, using About's existing market-name/property probe and bounded, exact-entry
Flutter version read. There is no root command, APK-wide scan or startup probe.
`environment.txt` records collection time with offset and timezone ID, market name,
model/codename, system incremental/display builds, Android release/SDK, the
`com.miui.home` package version name/code, module version name/code and available
Rust/Flutter versions. Missing versions are explicit; optional runtime fields can
be absent. Values are bounded and restricted to one line. The metadata explicitly
describes the **export-time** environment, which can differ from the incident-time
environment. No serial numbers or account identifiers are collected.

App exports live in `cacheDir/share/` and use names such as
`KeiHome-AppLogs-20261107-101530-123-v1.4.0-b0227.zip`. The timestamp uses local
time, millisecond precision and `Locale.ROOT`; the numeric version series and the
last four version-code digits identify the build iteration. Build metadata is
omitted from filenames; malformed version series become `vunknown`. Names contain
safe ASCII only. An atomic `createNewFile` reservation prevents overwriting an
existing share. A same-name collision uses `File.createTempFile` with the same
recognizable prefix and an additional numeric suffix. The archive's existing
temporary-write/atomic-replace path publishes the ZIP into the reservation.
Failed exports remove their reservation.

Before each export, on IO, `AppLogExportCache` removes only owned ZIPs and abandoned
archive temporary files **older than seven days** by modification time, including
the legacy `KeiHome-logs.zip`. It preserves files at the age boundary, all recent
shares, unrelated files, directories and symlinks. Active writers and their
temporary files are excluded; successful completion refreshes the retention age.
There is no count eviction that could remove a recently shared ZIP. This bounds
retention by age during continued use, rather than imposing a byte/count cap on
recent exports; no new exports means no further growth. Cleanup runs again at the
next export. The sharing grace period is seven days; ACTION_SEND does not report
when recipients finish reading, and Android can independently evict cache files.
FileProvider and ACTION_SEND consume the returned file without renaming or a
display-name override, so the URI, displayed filename and ZIP contents stay paired.

Clear holds the writer lock while streaming retained records into sibling
temporary files and atomically replacing affected hourly files. It uses no
full-log lists or in-place truncate fallback. Atomicity is **per hourly file**,
not for the entire selected range: a later failure can leave earlier files
already cleared. An affected append stream closes before replacement and
reopens on the next append. Closing the archive releases its writer and rejects
new operations while allowing already captured readers to finish.

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
