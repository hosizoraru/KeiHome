# Monet native startup scan cost

## Incident and evidence

On 2026-11-17 the physical launcher was updated from 8602 to 9259.
ActivityManager records the previous process exiting for `PACKAGE UPDATED` at
21:50:18, followed by an input-dispatch ANR in process 12259 at 21:50:25.
The gesture-monitor input channel had waited five seconds. That process exited
for ANR at 21:50:32.

The captured main-thread stack is inside the synchronous library-load callback:

```text
dlopen / Flutter initialization
HandleLoadedLibrary
InstallMonetHooksAtLibraryLoad
InstallMonetImageRecoveryAtLibraryLoad
ResolveMonetImageRecovery
Find
```

The symbol file matches the captured native Build ID
`4fd4fdcf1b887c27fd39da86dcc6f747479d1971`.
This establishes a blocking startup path in KeiHome; it does not establish that
this scan accounts for the entire ANR interval. Cold page faults, other startup
work and device load are not isolated by a single ANR stack.

## Shared scan changes

Previously image recovery walked executable ranges separately for seven roles.
Monet selection also walked the same ranges for each of its 112 pattern
variants. Both now collect candidates for all roles in a single segment walk.

`arm64_entry_filter.h` constructs a compile-time table of distinct two-instruction
entry prefixes. It rejects obvious nonentries before inspecting full patterns.
The two resolvers share this prefilter; it performs no acceptance decision.
There is no heap allocation, persistent disk cache, new worker or timer.

Full instruction-body matching, normalized SHA-256 digests, call-graph
relationships, readable bounds and candidate limits remain required. The scan
continues after finding all roles so later duplicate entries still reject.
Additional recovery tests duplicate disposal, creation and renderer entries in
the last executable range.

The existing callback installation and runtime policy behavior are retained.
In particular, event coalescing and the 15-second policy renewal are unchanged:
the renewal keeps expiring native policies alive and must not be removed as an
idle-loop optimization. Runtime switches continue to work without reinstalling
hooks.

## Measured result

Both devices ran independent ARM64 parser executables against the exact 9259
library. The installed launcher's APK SHA-256 matched the retained fixture.
Before and after were compiled with the same NDK and `-O2`. Each result below is
the median of three trials, with the ELF already loaded into private memory.

| Device | Parser | Before | After |
| --- | --- | ---: | ---: |
| Physical | Monet selection | 344.839 ms | 47.916 ms |
| Physical | Image recovery | 427.544 ms | 20.364 ms |
| HyperOS AVD | Monet selection | 270.757 ms | 39.653 ms |
| HyperOS AVD | Image recovery | 326.843 ms | 17.980 ms |

The sums of component medians fall from 772.383 to 68.280 ms on the physical
device, and from 597.600 to 57.633 ms on the AVD. All compared code targets,
pool offsets, pool-load location and displaced instructions match after
normalizing process-specific addresses.

These are isolated parser measurements, not full launcher cold-start timings
or proof that an input-dispatch ANR cannot recur.

## Validation and activation

The selection and image-recovery matrix covers all 11 retained fixtures
(7722 through 9259), including the held-out 9258 and 9259 libraries.
Generated contract tables remain byte-identical. Recovery checks include
12 positive and 136 rejected inputs per fixture, plus 54 ARM64 recovery cases
per fixture. Complete Android native Release builds pass with tracing both off
and on. Selection additionally checks graph mutations and actual bridge code.

Reproduce the offline matrix without interacting with a device:

```sh
uv run --with pyelftools --with capstone --with unicorn python \
  scripts/tests/monet-selection/run_matrix.py \
  --fixture-root .artifacts/inputs/launchers \
  --suite selection --suite image --output /tmp/keihome-monet-startup-matrix
```

The device benchmark source, commands, original ANR and raw outputs are retained
under `.artifacts/research/launcher-9259-reentry-20261117/`. The initial benchmark
output with an unnormalized tagged pool-load address is retained separately and
excluded from equivalence evidence.

Installing a new APK does not replace native code already mapped in a running
launcher. This change applies when a subsequent launcher process loads the new
module library. Validation must distinguish an in-place install, independent
native parser tests and an actual subsequent launcher startup. Do not kill or
restart a user's launcher solely to activate or benchmark this change.

## Consolidated native audit

The follow-up audit includes the library-load dispatcher, highlight, labels,
Dock, glass materials, image recovery, MAML ZIP cleanup, policy leases and
reader retirement. The Monet observer and splash artwork cache were also
reviewed for recurring work.

The additional measured startup hotspots were label and Dock profile lookup.
Their masked patterns previously searched every instruction repeatedly.
`IndexedArm64Image` now collects canonical Dart entry candidates once per
resolver invocation. Labels and Dock share it. Fully masked canonical entry
prefixes may use the index; all other patterns retain the complete scan.
Visitors receive a first instruction already read within a proved executable
extent, avoiding another range search for each rejected word.

The index has a 256 KiB allocation cap per invocation. Allocation failure,
saturation and unsupported prefixes use the original complete scan. It is
destroyed when the resolver returns; no index survives code patching. Nested
Dock/label resolution may temporarily hold two indexes (512 KiB total).
Publication revalidation creates a fresh index. Full patterns, caller
relationships, duplicate rejection and feature dependencies remain required.
Dock also validates its image before constructing the index.

Physical-device medians from three independent ARM64 trials:

| Stage | Before | After |
| --- | ---: | ---: |
| Labels | 549.730 ms | 240.517 ms |
| Dock, including its label dependency | 1975.603 ms | 396.410 ms |
| Highlight, unchanged control | 39.962 ms | 38.432 ms |
| Glass after labels, unchanged control | 96.602 ms | 97.280 ms |

These are separate stage measurements, not an end-to-end sum. Dependencies
overlap, and actual installation intentionally resolves some profiles again
before publication. The unmodified controls show why this batch concentrates
on labels and Dock instead of rewriting every scanner.

Other reviewed paths retain their existing behavior:

- Full backing-library SHA-256 and ABI/graph checks remain; source identity is
  not replaced by a weaker cache key.
- Policy waiting remains driven by socket readiness, deadlines and expiry;
  short retirement waits apply while readers still own retired snapshots.
- The 15-second renewal, lease expiry and acknowledgement checks remain.
- MAML ZIP metadata ownership and its bounded release worker remain.
- Event coalescing, theme/configuration invalidation and the bounded splash
  bitmap cache remain. No new frame callback, Binder polling, permanent worker
  or disk cache was introduced.

The index has ASan/UBSan checks for executable ranges, partial masks, overflow
fallback, early termination, oversized views and fresh-phase insertion.
Label and Dock positive/mutation tests pass on all 11 retained launcher images.
Independent label symbols and source/linked ARM64 bridge cases are checked
separately. The glass material suite runs 15,022 checks, including 862 rejection
cases; its missing historical derived oracles were regenerated from retained
original libraries, without weakening assertions. MAML ownership/release,
policy lifetime and frozen Dock-oracle regressions also pass.

Keep the two scan helpers separate in purpose: the exact-pattern prefilter is
allocation-free and scans once; the short-lived Dart index amortizes repeated
masked queries. Making all parsing use a permanent cache would add invalidation
and ownership risks.

Future palette, preference, UI and icon-generation changes should continue to
use the existing runtime protocol and hot-reload paths when their native ABI
and behavior are unchanged. Reserve native updates for actual native fixes or
ABI changes, and batch related measured work with this same matrix rather
than changing the SO speculatively for each feature.
