# Hook hot-path performance

## Reference and scope

This review was informed by [MiuiBackGestureHook 0.13.0](https://github.com/wxxsfxyzm/MiuiBackGestureHook/releases/tag/0.13.0)
and its [performance commit e3bcb784](https://github.com/wxxsfxyzm/MiuiBackGestureHook/commit/e3bcb784260f5e29c550c23f2bf0b7b7e4adeed4),
licensed under Apache-2.0. Thanks to wxxsfxyzm for the implementation and explanation.
Its useful patterns are allocation-free cache hits, sharing configuration snapshots,
eliminating redundant state queries, and gating diagnostics before message preparation.
KeiHome's changes are independently implemented for its existing ownership model.
No upstream source files are vendored.

Most host reflection profiles already resolve members during installation. Shared icon
loading already coalesces invalidations and uses workers for ZIP/bitmap/palette work.
Those paths should remain event-driven; suppressing freshness or widening reflection
caches would not improve their contract.

## Changes

* `SurfaceIconKeyIndex` canonicalizes package/activity keys within each
  `CrossSurfaceIconCache`. Warm hits and definitive-miss checks reuse those keys.
  The existing access-ordered working set still controls admission and eviction;
  every eviction removes its canonical key and retirement clears the index.
  Unknown negative lookups do not admit keys. The index holds only strings and keys,
  with no host class, drawable, context or classloader references. It is accessed
  exclusively under the owning cache lock.
* Host `ImageView` hooks copy the parameter array only when substituting an available
  bitmap. Unassociated views and pending/uncovered targets proceed with the existing
  chain arguments. Original artwork and deferred refresh bindings are still saved.
* `NativeMonetPlatformMembers` lazily resolves six boot-class members once per module
  generation. It caches no receivers, current users, package UIDs, processes,
  authentication outcomes or sockets. Every original invocation and ownership check
  remains live, including the foreground-user recheck. Failed lazy initialization is
  retryable and does not create an unbounded negative cache.

There are no native, Hook-ID, scope, preference, R8-rule or status-polling changes.
The conservative module boundary in [r8-boundaries.md](r8-boundaries.md) still applies.

## Evidence and limitations

The focused JVM group is `python3 scripts/checks.py hook-hot-paths --run`.
Device acceptance covers `CrossSurfaceIconCacheWorkloadTest`,
`CrossSurfaceIconCacheShutdownTest`, `CrossSurfaceIconEventsTest`,
`HostAppIconFrameworkDeviceTest` and `HostAppIconPresentationDeviceTest`.
The framework getter check may require the root-probe descriptor and matching device
fingerprint because ordinary instrumentation is subject to hidden-API filtering.
Only supply those arguments after the live root probe succeeds on that device.

The reproducible [isolated probe](../scripts/tests/hook-hot-paths/README.md) compiles the
exact production helpers. It compares the former composite-key construction against
the canonical index, with a 64-target working set and one million warm lookups per
sample, alternating order after warmup. It also verifies member reuse and invokes the
cached platform members against live Android state.

ART allocation counters are process-wide and quantized; a small nonzero delta does
not establish a per-lookup allocation. Host allocation counters are thread-local.
Host JVM timing can favor the older allocation-based approach even when ART favors
the index. Both raw outputs and medians must be retained rather than selecting only
the favorable runtime. These are unminified, isolated helper measurements, excluding
locking, bitmap work, rendering and IPC; they establish no frame-rate, battery,
physical-device or whole-app speedup.

The dated acceptance receipt is under
`.artifacts/research/hook-hot-paths-20261112/`, including reference hashes, raw probes,
test logs, signed artifact identity and API102 handoffs. Final runtime acceptance
requires the minified Release, complete system-server initialization, and unchanged
critical process identities. Ordinary Java reload must suffice; native reactivation
is not part of these changes.

## Further observations

Status reporting can open two authenticated capability connections when Monet and
Dock are both queried. This is worker/control-path work, about once per visible
status interval, rather than an animation callback. Combining the probes would
require explicitly preserving fresh greeting provenance, Dock failure clearing and
process/lease identity. This patch retains those semantics.

Some optional native image diagnostics prepare string payloads before their final
log gate. The associated observer/worker feature is disabled by default. Normal
production Hook execution already avoids that path; changing native activation
for this review would add risk without an established production benefit.
