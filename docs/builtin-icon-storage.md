# Shared built-in icon resources

Built-in profiles share identical files while retaining their original path
names, source manifests, image bytes and original ZIP identities. The app reads
individual objects directly from APK assets. Browsing and previews need no
extracted pack or whole shared archive in `cacheDir`; native handoff generates
its existing temporary icons/MTZ module only when requested.

## Build inputs and format

User-owned, ignored originals stay under
`.artifacts/generated/assets/builtin-icon-packs/*.zip`. The existing ColorOS
generation/capture scripts and their full source provenance remain unchanged.
`PrepareBuiltinIconStore` replaces their packaging with these generated assets:

- `builtin-icon-store/objects.kicons`: a standard ZIP of independent raw-DEFLATE
  objects named `objects/<SHA-256>`. This asset is **STORED in the outer APK**,
  enforced by `androidResources.noCompress`, so its offsets remain seekable.
- `builtin-icon-store/catalog.json`: schema 1, blob length/digest, object
  `[relative payload offset, compressed bytes, raw bytes]`, and each profile's
  original ZIP digest plus original-path-to-object mapping. This small index
  can use ordinary outer-APK compression.

The build uses stdlib-only `scripts/pack-builtin-icons.py`. It sorts its inputs
and objects, preserves every file's complete bytes, verifies stored objects
before publication, and never changes the originals. Profiles must be classic
appfilter archives; imported APK/MTZ/native modules keep their existing readers.
Other local assets, including HyperOS fallback profiles, retain their paths.
Builds without optional OEM pack inputs skip Python and have an empty catalog.
With those inputs, Python 3.11+ is required at build time. There is no new
Android library, JNI codec, or host-process initialization.

Variant assembly checks the final APK for the catalog and the uncompressed
object entry at its generated size. Checking merged asset bytes alone cannot
detect a change in outer ZIP compression. Builds without a catalog and object
store still pass this check.

## Reader, bounds and ownership

`BuiltinIconPackStore` validates the catalog schema, names, paths, file/parent
collisions, object references, lengths, offset arithmetic and existing expansion
bounds. Each read opens `AssetFileDescriptor`, performs bounded positional reads
at `asset.startOffset + object.offset`, inflates one object, checks its exact
length/end-of-stream and SHA-256, then closes its stream and descriptor.

Descriptors retain the catalog and object references, not open descriptors,
copied archives or decoded-image byte caches. Reads use independent descriptors
and are safe across concurrent previews. Cancellation is checked during catalog
parsing, positional reads and inflation. App repository methods run on their
injected worker dispatcher; a coroutine mutex coalesces concurrent built-in
scans and the hidden runtime-fallback load. Cancelled work cannot publish a
partial catalog/result.

`IconPackSource.Builtin` shares the existing appfilter/description parser with
imports. Static rendering, ColorOS masks/provenance, official icon preference
and dynamic calendar/clock handoff use the same path-based bytes. The launcher
continues receiving the existing validated native module format; it does not
read or decompress this shared asset. API 102 preferences/ACK and injected
OEM-owned storage contracts are unchanged.

## Identity and migration

Both descriptor ID and `IconBuildInventory.sourceIdentity` retain each original
ZIP digest, rather than the shared blob digest or App version code. Changing
packaging alone therefore does not invalidate selections, active-source
associations, or applied-generation receipts. Actual regenerated source ZIP
changes still change identity through the existing update flow.

The first scan removes only `cache/builtin-icon-packs`, the old regenerable
per-version copies. It does not touch `noBackupFilesDir/icon-packs`, app settings,
active native snapshots or OEM resources. New previews read immutable APK
assets, so private-cache eviction does not require recreating whole packs.

## Verification checkpoint

The 2026-11-04 source set contains 11 profiles (10 selectable and one hidden
runtime fallback), 11,826 path references and 3,121 unique original objects.
Independent original ZIPs total 125,157,499 bytes. Shared object ZIP payload is
52,911,031 bytes; the 1,733,564-byte catalog is outer-APK compressed.
The final APK's actual stored contribution must include both entries, rather
than counting only object payload. No PNG/WebP transcoding is used.

Host tests cover byte-exact per-path roundtrips, cross-profile deduplication,
determinism and unchanged originals, optional empty input, unsafe/duplicate
paths, rejected input formats and failed-build publication. Focused JVM tests
cover archive parsing, update identity, catalog sorting and cache accounting.

HyperOS AVD `emulator-5580`, launcher 8593:

- Android reads and SHA-verifies all 3,121 unique objects / 89,675,089 original
  bytes through actual APK offsets, including XML/templates and static images.
- All selectable profiles render concurrent day/night previews; no pack copy
  is created. Concurrent catalog requests receive the same completed result.
- A reconstructed legacy Classic ART+ archive produces the same parsed entries
  and ColorOS provenance, and 28 sampled day/night previews match pixel-for-pixel.
- Cancellation/oversize/path rejection and subsequent retries work; the full
  object sweep does not leave descriptors open.
- Five separate ColorOS priority diagnostics verify official day/night and
  Monet precedence, opt-in scoping, exact supplied native resource preservation,
  and admissible whole-catalog handoff.

Device measurements in Debug are functional diagnostics, not a Release speedup
or physical power claim. The full object sweep took approximately 1.5–1.7 seconds
in the recorded runs; a cold full-profile parse includes JIT/XML/provenance work.
Release UI/native apply acceptance, artifact metadata, screenshots, cache
measurement and process identities are recorded separately in
`.artifacts/research/shared-builtins-20261104/acceptance.json`.

This checkpoint does not test a physical phone. Optional future PNG optimization,
WebP conversion or a Zstd block codec requires its own pixel/metadata, native
handoff and Android random-read measurements; the current implementation keeps
original resource encodings and platform DEFLATE.
