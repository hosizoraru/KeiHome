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
