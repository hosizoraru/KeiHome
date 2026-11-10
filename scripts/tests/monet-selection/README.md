# Dynamic monochrome selection contracts and host checks

Run from any directory. Nothing connects to Android, loads a launcher, patches a
process, downloads dependencies, or modifies an input ELF. Mutation tests use
private host memory copies.

```sh
PYTHON=/path/to/python-with-capstone \
./scripts/tests/monet-selection/run.sh \
  --fixture /external/old/libapp.so /external/old/debug.elf \
  --fixture /external/new/libapp.so /external/new/libapp.debug.elf \
  --output-dir /tmp/keihome-monet-selection-tests
```

`--fixture` is repeatable. Its independently decoded debug ELF supplies an
address/size oracle only to the tests. The production resolver uses no symbols,
fixture addresses, build numbers or known-image hashes. `--libapp FILE` remains
available for the host mutation checks without a debug-symbol oracle;
`LIBAPP_FIXTURE` is its environment equivalent.

`NDK_DIR` defaults to the project's installed NDK 30.0.16248370. `CXX` selects the
host compiler. Capstone is optional for the instruction-path model and ABI audit;
missing capabilities are **SKIPPED** in `report.json`. No dependency is downloaded.
The report includes first-resolution wall time and executable image size for
each fixture. These are optimized host measurements, not Android launch times.

## Runtime contract and reproducibility

`app/src/main/cpp/monet_selection_patterns.h` contains generated instruction-body
hashes, four-word scan prefixes and function-relative graph edges. It contains
no image RVA table, complete extracted functions or image SHA allowlist. Generate
it from all seven external fixtures listed in `fixture_inventory.json` with
repeatable `--fixture LIB DEBUG` arguments. For example:

```sh
python3 scripts/tests/monet-selection/generate_contracts.py \
  --fixture /external/old/libapp.so /external/old/debug.elf \
  --fixture /external/new/libapp.so /external/new/libapp.debug.elf \
  --evidence /tmp/selection-contract-evidence.json
```

The default output is the production header; `--output` can select a temporary
file for comparison. Keep fixture binaries, decompilation, and generated audit
JSON outside the repository. The JSON intentionally includes observed RVAs as
evidence; the production header does not.

The shared offline inventory includes 7722, 8593, 8602, 8604, 9209, 9226 and
9239. Run all four suites and verify all three generated contract headers with:

```sh
NDK_DIR=/opt/homebrew/share/android-ndk /path/to/python \
  scripts/tests/monet-selection/run_matrix.py \
  --fixture-root /external/launchers --output /tmp/monet-matrix
```

Each fixture directory contains `libapp.so` and `debug.elf`. The interpreter
must provide the pinned Capstone, Unicorn and pyelftools dependencies. Output
contains full commands, per-file source/fixture SHA256s and suite receipts.

To check an existing unstripped shipping SO without rebuilding it:

```sh
/path/to/python scripts/tests/monet-selection/run_shipping.py \
  --elf /external/immutable/libkeihome_hyos_lsp.so \
  --fixture-root /external/launchers --matrix /tmp/monet-matrix \
  --output /tmp/monet-shipping
```

This uses the SO's actual bridge symbols and records its SHA256. Selection is
a bounded instruction-path model; tint and image bridges execute in Unicorn.
Resolver profiles are supplied by the host production-source matrix. These
checks do not execute the shipping resolver or establish device acceptance.

The decoder uses the bounded segment/branch-validation approach of the
Apache-2.0 reference
`MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp`.
The Dart canonicalization and graph implementation are independent. It masks
only decoded external PC-relative branch displacements, PP slot indices, and
indices immediately following `LDR Xt, [THR, #0x78]`. It preserves register
operands, every local branch, frame sizes, VM reserved PP slots, object fields,
CIDs and boolean constants. Repeated slot identities remain distinct and equal
where required within each body. Selection's cross-function type/callback/
initializer/factory relationships receive additional checks.

Every masked external edge must exist in its contract, land in executable
file-backed bytes, and reach the expected role or dependency body. Named direct
dependencies are checked in full; unnamed stubs retain 128-byte contracts.
Their external edges also authenticate a target role or an unmasked 32-byte
terminal anchor. This is a bounded graph contract, not transitive validation of
all launcher code. Terminal anchors deliberately remain conservative: a future
compiler change can require another reviewed contract even when its ABI is
ultimately compatible.

The reviewed images need distinct shapes for changed controller fields and
added conditions in `shouldUseMonochrome`/`_updateMonochromeState`, as well as the
changed folder-builder body. Those differences remain exact; they are not hidden
by generic immediate masks. Candidate ambiguity fails closed. The implementation
bounds readable ranges, LOADs, image span, body size, distinct candidate addresses
(128 per role), candidate contracts (512 per role) and dependency depth. Multiple
relocated contracts for one entry do not consume the address budget. Only primitive code addresses and offsets are
returned.

## ABI evidence for the unchanged bridge

All five fixtures independently establish these contracts:

- `_hasMonochromeLayer` receives the live package String in x1. `shouldUseMonochrome`
  restores its incoming x1; `_updateMonochromeState` loads/decompresses the model's
  package field and moves it to x1. Each is a direct caller exactly once.
- The legacy `shouldUseMonochrome` callers are the icon query closure and folder
  fallback. Icon build stores the package getter result in closure context;
  the query closure reloads it. The folder argument is followed through its
  actual stack store/reload, including the changed stack slot in the newer body.
  The complete bodies and downstream String consumers are authenticated.
  The newer graph instead has cache-query, preview-update and folder callers.
  Cache query keeps its incoming String; preview reconstructs the model's package
  field before calling the same predicate. A partial or mixed caller family is
  rejected, including a mutation that removes just one optional-role match.
- Header at tagged pointer -1, CID bits 12..31, OneByteString CID 94,
  TwoByteString CID 95, tagged length +7, one-byte payload +15, and false
  `x22 + 0x30` are confirmed by String equality and allocation code. The bridge
  accepts only bounded, nonempty ASCII OneByteString inputs and otherwise uses
  the original function.
- The original prologue does not read x16. Its first callee, AllocateContext,
  overwrites x16 at +0x14 before any read or branch, including the allocation
  slow path. The Has body then overwrites it at +0x30 before its own first use.
  The original tail transfer through x16 therefore does not discard live input.
- Decoded PP producers identify controller type, update callback, cache initializer
  and resource factory independently. Callback comes from the unique authenticated
  Init -> ClosureAllocateFast edge; it need not be adjacent to the other slots.
  Publication rechecks the actual factory/callback load instructions. ClosureAllocateFast
  loads `[Function + 7]` and copies it to the closure entry, proving the entry
  layout used by the bridge. On every enabled invocation the bridge checks both
  live PP function entries against the resolved code addresses. It captures no
  external PP and retains no Dart reference.
- Executable mappings are scanned for all incoming direct branches to Has,
  Should, Update and Resource (legacy counts 2/2/2/2; cache graph 2/3/2/1), and branches into the displaced
  patch interior are rejected. This enumerates direct callers; it does not claim
  whole-program enumeration of arbitrary indirect calls. Runtime String and
  live-pool guards apply to every invocation.

The bridge stays a call-free leaf, preserving the existing Dart roots, x15,
NZCV, return address and register contract. Native policy memory uses the
existing reader-count/RCU lifetime; all acquired paths release their reader
before returning false or transferring to the original trampoline. Main must
keep the mapped image and original trampoline executable for that publication.

## Checks and integration

The runner checks NDK compilation with warnings as errors, the actual 142
AArch64 bridge instructions, 35 bounded instruction-path cases, all five real
fixture profiles against the symbol/Capstone oracle, and 79 or 80 profile/identity
assertions per fixture. These include every Has instruction mutation, String
layout/boolean drift, x16 deadness, dependency drift, changed pool producers,
extra callers, patch-interior targets and malformed readable mappings.

Positive mutation cases move the Has body and rewrite both callers and its
outgoing BLs, coherently move selection PP slots, and change a non-code ELF byte.
They must continue to resolve, proving independence from original RVAs and known
file SHA values. The PP mutation tests do not rewrite a Dart heap: live function
entry checks remain necessary before the bridge can apply its veto.

On ARM64 hosts the runner also executes the production bridge against a
synthetic tagged heap. On macOS only object-format directives and relocation
spelling change; every unrelocated text byte must equal the Android object.
The native harness checks 1,445 assertions across 39 fresh root generations and
sets retired heap/pool arenas to `PROT_NONE` before later calls. This verifies
reference lifetime in the harness; it does not execute a Dart VM, GC cycle or
launcher rendering.

Public resolver signatures and `MonetSelectionProfile` fields are unchanged.
Main computes the exact backing ELF digest, resolves the current mapped image,
then calls `PublishMonetSelectionProfile(profile, digest)` before installation.
Publication requires a nonzero computed digest and binds the entire code profile
and digest immutably. It never consults a known-image allowlist; an identical
publication is idempotent and a changed identity/profile is rejected. The unused
`kMonetSelectionImageSha256` fixture constant has been removed.
