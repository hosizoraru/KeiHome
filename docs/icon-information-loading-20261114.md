# Icon information loading and applied-pack navigation

## Report and baseline

Users reported a long wait whenever entering Icon Packs, including on fast
physical devices. Treat this as data-loading work, not only animation or
low-end CPU performance. The connected physical device has 590 installed
packages, including 225 third-party packages; this count is not a latency
measurement.

An explicitly selected, read-only Debug instrumentation diagnostic on Phone
AVD `emulator-5580` found the following serial stage times for a new repository:

| Stage | Time | Input/result |
| --- | ---: | --- |
| Discover icon targets | 1,818 ms | 196 targets, 195 packages |
| Read installed APK packs | 5,559 ms | 5 packs, 147,952 entries |
| Read imported packs | 36,339 ms | 10 packs, 14,394 entries |
| Read built-in packs | 2,951 ms | 10 packs, 3,404 entries |
| Build matching/preview card data | 130 ms | 25 cards |

The same repository reused imported descriptors in 9 ms, while target discovery
still cost roughly 2 seconds. Imported packs were repeatedly undergoing full
archive decompression/CRC validation after process recreation. UI publication
also waited for all sources, so one slow source delayed already completed ones.

These are Debug serial stage measurements, not Release UI latency, a guaranteed
cold-storage benchmark, or physical-device speedup percentages. Production
source reads run concurrently; do not add these numbers to predict page latency.

## Changes and boundaries

- Store a bounded, app-private archive-validation receipt. Every read still
  hashes the compressed original and any extracted native module. A receipt is
  valid only for the matching hashes, format, SDK, all limits and validation
  revision. Unchanged bytes can skip repeated full decompression/CRC checking;
  current directory/XML interpretation and index construction still run.
- New, changed or unrecognized archives receive full validation. Canceled or
  failed validation cannot publish a receipt. Cache corruption or write
  failure falls back to full validation. Receipts contain no parsed artwork.
  A future change to full payload-validation rules must bump the receipt revision.
- Retain up to 512 manifest overlay verdicts, keyed by package/profile and
  base/split APK identities. Retain no Resources, parser or Context objects.
  Missing identities and parse failures are not cached. APK changes invalidate
  the verdict.
- Publish completed catalog families independently, preserving previous cards
  until a replacement is ready. Show loading for the sources needed by the
  visible section. Preserve cancellation, dirty-state retry and failure details.
- Reuse successful Apple entry loads; explicit refreshes and mutations still
  reload. Coalesce invalidations during an active load, including locale changes,
  and build mapping rows off Main. Package changes also refresh an already loaded
  Apple controller when no general catalog has been opened.
- During application filtering, retain rows only for a compatible source and
  scope while the new query is pending. Show empty only when the exact current
  request completed; canceled old requests cannot publish over new results.

Async Perfetto slices `KeiHome:icon-info:*` support Debug UI-stage inspection
without retaining diagnostic logging in the product flow. They pair correctly
across dispatcher switches.

## Status shortcut

Icon Packs -> More settings -> Experience contains a default-on setting to open
the current pack directly from its Status card. Turning it off preserves the
Icon Packs overview destination. Successful direct entry returns to Status.

Resolve from a fresh applied-theme report and import receipts, not a displayed
name or selected preview. Read only small local provenance metadata on IO, so a
fresh Status entry does not require the heavy catalog or Perfect controller to
load. Reuse `SystemIconFallback.baseSourceId` for historical and current v1-v4
source suffixes, and share the Perfect storage namespace with its controller.
Unknown, removed, incomplete or stale identities fall back to the overview.
Repeated taps, leaving Status and lifecycle changes cannot deliver a late route.

## Verification evidence

After seeding validation receipts, a second instrumentation process read the
same 10 imported packs in **8,932 ms**, compared with the **36,339 ms** baseline.
Its PID differed from the seeding process, so this was not reuse of the
repository's in-memory descriptor map. Repeated target discovery within one
process changed from **1,969 ms to 46 ms**. All 25 cards, 165,750 entries, target
counts and aggregate per-pack matching counts were unchanged.

Do not attribute the smaller differences in installed/built-in parsing to the
receipt cache: those stages are also affected by OS/resource/JIT warming.
The separate 25-second Debug UI trace contained three unfinished source slices;
it is retained as diagnostic evidence and is not a page-latency result.

Task evidence is retained in
`.artifacts/research/status-pack-shortcut-20261114/`.
Baseline evidence includes `baseline-benchmark-events.log` and the exact Debug
APK identity. Unit tests cover receipt integrity and policy invalidation,
resource-cache limits and 100/500/1,000-entry inventories, progressive publication
and cancellation, Apple invalidations, asynchronous filtering, preference
persistence and source-aware routing.

Final measurements and device acceptance are recorded in the task evidence
ledger. Newly encountered or changed archives still incur the first full
validation. Installed APK filter parsing, built-in parsing and current index
construction remain real work; this change does not claim zero cold-load cost.
