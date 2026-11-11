# Launcher label host checks

The fixtures are the user's exact launcher libraries, kept outside version
control. The resolver uses mapped instruction contracts, without reading symbols.
The symbol/debugdata files used during research are independent role oracles.

The durable inventory is `fixtures.json`: 7722, 8593, 8602, 8604, 8607, 9209,
9226 and 9239. Each exact ELF lives at
`.artifacts/inputs/launchers/<version>/libapp.so`, next to its unpacked
`debug.elf`. No fixture is copied into version control.

Run the complete inventory from the repository root (no Gradle or device):

```sh
python3 -m pip install -r scripts/tests/icon-highlight/requirements-emulation.txt
python3 scripts/tests/launcher-labels/run.py \
  --payload app/build/intermediates/cxx/RelWithDebInfo/2m3s6l5v/obj/arm64-v8a/libkeihome_hyos_lsp.so \
  --ndk "$ANDROID_HOME/ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin" \
  --output .artifacts/research/launcher-labels
```

Use the actual current unstripped payload path; the path above is an example.
Optional positional ELF paths select a subset; `--inventory` selects a different
test-only inventory. The runner compiles the current host resolver, compares
five resolved roles with independent symbols, verifies `debug.elf` matches
embedded debugdata, then executes both freshly assembled and linked ARM64
bridges. It also reproduces the expanded label patterns from the applicable
9209/9226/9239 fixture oracles and compares the output with production.
It snapshots the supplied payload before use and authenticates every
bridge byte and entry offset against current assembly. `payload.json` records
the full SO SHA-256, bridge SHA-256, source SHA-256 and entry offsets.
This authenticates the bridge only, not the rest of the compiled C++ payload.
Results and separate source/linked bridge logs remain under `--output`.

Build the host resolver/rejection runner from the repository root:

```sh
clang++ -std=c++17 -Wall -Wextra -Werror -O2 -Iapp/src/main/cpp \
  app/src/test/cpp/launcher_label_profile_test.cpp \
  app/src/main/cpp/launcher_label_profile.cpp \
  app/src/main/cpp/icon_highlight_profile.cpp -o /tmp/keihome-label-profile-test
/tmp/keihome-label-profile-test .artifacts/inputs/launchers/8593/libapp.so
/tmp/keihome-label-profile-test .artifacts/inputs/launchers/7722/libapp.so
```

After building the Release native payload, run the linked ARM64 bridge checks
with a Python environment containing the pinned native-test dependencies in
`../icon-highlight/requirements-emulation.txt`. Set `--payload` to the unstripped
Release `libkeihome_hyos_lsp.so` under `app/build/intermediates/cxx/`:

```sh
python scripts/tests/launcher-labels/test_bridge.py \
  --payload /absolute/path/to/unstripped/libkeihome_hyos_lsp.so \
  --resolver /tmp/keihome-label-profile-test \
  .artifacts/inputs/launchers/8593/libapp.so \
  .artifacts/inputs/launchers/7722/libapp.so \
  .artifacts/inputs/launchers/8593/libapp.so \
  .artifacts/inputs/launchers/8602/libapp.so \
  .artifacts/inputs/launchers/8604/libapp.so \
  .artifacts/inputs/launchers/8607/libapp.so \
  .artifacts/inputs/launchers/9209/libapp.so \
  .artifacts/inputs/launchers/9226/libapp.so \
  .artifacts/inputs/launchers/9239/libapp.so
```

The suite has 686 execution cases per fixture (5,488 for the full eight-fixture
inventory). These stub the original Dart measurement, prefix, allocation,
constructor, fade, visibility, editing and title-refresh callees. They assert original return
PCs, tagged roots, simulated relocation during multiple calls, prefix-dependent
width boundaries, preference changes, native pressure fallbacks and unchanged VM
registers. Editing cases cover title-only pause, cached and both cold returns,
immutable native child/key/field preservation, pressure, controller absence and
relocated original/copy roots. The temporary rooting shell is never published
into the widget tree. Invisible-page cases retain tickers during editing and
unknown-controller states, including relocation during the second query.
Fade cases cover zero/nonzero/invalid fractions, all modes and a
missing widget, checking the unchanged callee arguments and return PC. Page cases
also execute the exact 11
initialization instructions from each fixture and assert that the Stack is
initialized before visibility can trigger GC. They do not run Dart GC or prove
Flutter rendering. Device acceptance is a separate required check after a fresh
native load.

The 8607 fixture passes the existing production graph without adding patterns.
Its `libapp.so` is byte-identical to the archived 8604 fixture; fixture names
and digests remain test provenance, never runtime admission criteria.
Its focused source and archived 106000410 linked-bridge evidence lives under
`.artifacts/research/oem-refresh-20261111/labels-highlight/labels-8607/`.
That run checks five independent debug-symbol roles, 19 rejection mutations and
686 execution cases for each bridge. The archived unstripped payload is
`.artifacts/releases/106000410/libkeihome_hyos_lsp.so`; that archive has no
`symbols/arm64-v8a/` subdirectory. Linked checks authenticate and execute the
bridge, while resolver checks compile current C++ source on the host.

`generate_variants.py` reproduces the reviewed newer instruction shapes from
the external 9209/9226/9239 debug-data oracles. It covers inline title configuration,
the expanded grid field access and changed item-event prefix. Runtime resolution
still derives fields, live root slots and targets from mapped code. The shared
visibility tail is located uniquely within a bounded span so the new native
landscape condition can keep its original semantics. See
[`docs/launcher-native-matrix.md`](../../../docs/launcher-native-matrix.md) for
the source matrix and separate AVD acceptance boundary.

The existing `icon-highlight/test_exact_patch.cpp` now exercises all three exact
patch kinds, including page boundaries and injected protection/rollback failures.
`test-monet-policy-server.sh` covers the independent capability, command framing,
policy isolation and expiry under ASan/UBSan. Focused JVM tests cover API framing,
lost/stale ACK reconciliation and backward-compatible runtime reports.
