# Native launcher compatibility matrix

This checkpoint adds the user's three newer launcher APKs to the two existing
fixtures. All five are ARM64 Flutter/Rust launchers with no DEX payload. These
contracts therefore use mapped native instructions; the separate Themes APK
import/apply contract continues to use DexKit.

## Source fixtures

The original APKs and extracted libraries live outside version control under
`.artifacts/inputs/launchers/<build>/`. The three newly supplied APKs were moved
from Downloads to `launcher.apk` in those directories without changing bytes.
Each new directory has `provenance.json`, four extracted native libraries and
the decompressed debug-data oracle. Source filenames, version metadata and
every extracted library's SHA256 are recorded in
`.artifacts/research/launcher-matrix-20261104/inputs.json`.

| Home build | APK versionCode | libapp.so SHA256 |
| --- | ---: | --- |
| 7722 | existing baseline | `fb8b1f91eba2d9c43875f49bbded70cca462be341fc379b9864e7c6136d6e463` |
| 8593 | 801028593 | `68f62bbd0a73ae0096c27a4cb4bf1789a4452220510a05c658a3968fa38f470d` |
| 8602 | 801028602 | `5030b84065cfbacd2db0a92f3af284a9bf71d592c05ba06b9292b7d004849610` |
| 9209 | 801029209 | `5d9c0106f8fe72170dac888ba992371f2c9560aed9358e01f7c8b799f317627b` |
| 9226 | 801029226 | `a981d774576e1d3b0ab925305346c7a2c9900b831e11299c53ebba9541cd5f0a` |

These identities authenticate research inputs. Production does not select a
profile by version, hash, symbol name or an absolute-address table. Unknown or
ambiguous instruction/call graphs leave the corresponding capability inactive.

## Changes required by the newer binaries

**Labels.** The newer title-line getter inlines its configuration lookup and
the reactive grid field moved. Resolution authenticates the inlined/native
getter relationship, derives the actual field and locates the correct return
tail. Expanded item-event and grid-observer instruction shapes are reviewed
separately. The native page-visibility predicate gained a landscape condition;
its shared tail is found uniquely in a bounded range. The complete native
predicate still decides visibility. The existing GC-safe page Stack/TickerMode
bridge, prefix-aware width decision and one-callback title refresh remain in use.

**Monochrome selection.** 8602 already needs additional dependency shapes.
9209/9226 replace the legacy icon-query closure with desktop-cache and preview
paths. Both new callers must be present and supply the same live package String;
partial or mixed families are rejected. Exact root-caller counts are
2/2/2/2 for the legacy Has/Should/Update/Resource graph and 2/3/2/1 for the cache
graph. Every direct site must belong to an authenticated body.

The update callback's object-pool slot also moved far from the controller type
and factory. Resolution derives it from Init's actual closure-allocation edge,
checks distinct bounded slots and rechecks their load instructions at publication.
The unchanged leaf bridge checks both live Function entry points on every enabled
call. Candidate addresses and candidate contracts have separate bounds, allowing
several relocated body contracts for one address without increasing the
128-address search budget.

**Monet tint.** The foreground/background widgets have new complete instruction
shapes and the render filter field moved. The resolver follows the authenticated
closure captures to discover the filter pool operand and cross-checks its other
consumer, getter, setter, equality and allocator graph. Object-field changes are
reviewed shapes rather than generally masked immediates.

**Image ownership.** RenderImage's image/debug fields and allocation size changed
together. The setter, update, create and disposal bodies must agree with the
decoded allocator size. The Image type test is no longer adjacent to its allocator:
an authenticated cast-to-clone caller now provides an inline CID check and the
actual nullable cast target. Both must agree with the Image allocator. Frame
spills, backing/disposed fields, disposal calls and patch-interior rejection
remain independently checked. Positive CID relocation tests include a carry
between the allocator's MOVZ and MOVK halves.

Material/highlight and the framework monochrome gate already resolve all three
new inputs; their existing contracts required no relaxation.

## Host evidence

All six production resolvers pass on all five fixtures. Evidence is under
`.artifacts/research/launcher-matrix-20261104/`:

| Suite | Verification |
| --- | --- |
| Labels | 1,350 linked production ARM64 cases; all five profile/rejection checks |
| Monochrome selection | 79 checks on each legacy fixture, 80 on each cache fixture; independent symbol/Capstone ABI audit; 1,445 native bridge assertions |
| Monet tint | 17 rejected structural mutations and 69 ARM64 cases per fixture |
| Image recovery | 8 positive and 63 rejected profiles plus 54 ARM64 cases per fixture; complete native builds with tracing both off and on |
| Material/highlight | 242 resolver checks per fixture, 1,440 patched and 1,120 bypass executions per fixture; 945 exact-patch assertions |
| Monochrome gate | 40 checks per fixture, including relocation and ambiguity |

The standalone instruction suites stub Dart callees or use synthetic roots.
They do not execute Dart GC or establish rendering on the three newer launchers.
The selection resolver's first optimized host scans were approximately 0.25 s;
these are host measurements, not Android startup or frame-time measurements.

Generators reproduce reviewed shapes from external fixture/debug-data inputs:

- `scripts/tests/launcher-labels/generate_variants.py` (9209 and 9226).
- `scripts/tests/monet-tint/generate_variants.py` (9209 and 9226).
- `scripts/tests/monet-selection/generate_contracts.py` (all five).
- `scripts/tests/monet-image-recovery/generate_contracts.py` (all five).

All suite CLIs accept repeatable fixture/library arguments or an image list;
`icon-highlight/run.py` now accepts any nonempty image list. Use the same ordered
five-fixture inputs when regenerating the complete production headers. See each
suite's README/help for compiler, NDK, Python and payload arguments.

## Live acceptance boundary

The current HyperOS AVD (`emulator-5580`) runs Home 8593. The three new launcher
APKs are retained as host fixtures. Live regressions, the exact Release artifact,
native activation, process identities, UI screenshots and final restored settings
are recorded separately in
`.artifacts/research/launcher-matrix-20261104/acceptance.json`.

This task's device surface is that AVD only. No physical-device acceptance is
implied. Resolver work runs at native library activation; the render and
selection bridges add no per-frame scanning, C callback or polling timer.
