# Launcher label host checks

The fixtures are the user's exact launcher libraries, kept outside version
control. The resolver uses mapped instruction contracts, without reading symbols.
The symbol/debugdata files used during research are independent role oracles.

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
`../monet-tint/requirements-emulation.txt`. Set `--payload` to the unstripped
Release `libkeihome_hyos_lsp.so` under `app/build/intermediates/cxx/`:

```sh
python scripts/tests/launcher-labels/test_bridge.py \
  --payload /absolute/path/to/unstripped/libkeihome_hyos_lsp.so \
  --resolver /tmp/keihome-label-profile-test \
  .artifacts/inputs/launchers/8593/libapp.so \
  .artifacts/inputs/launchers/7722/libapp.so \
  .artifacts/inputs/launchers/8602/libapp.so \
  .artifacts/inputs/launchers/9209/libapp.so \
  .artifacts/inputs/launchers/9226/libapp.so
```

The 3,430 execution cases across five fixtures stub the original Dart measurement, prefix, allocation,
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

`generate_variants.py` reproduces the reviewed newer instruction shapes from
the external 9209/9226 debug-data oracles. It covers inline title configuration,
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
