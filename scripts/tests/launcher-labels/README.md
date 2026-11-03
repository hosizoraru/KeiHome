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
  .artifacts/inputs/launchers/7722/libapp.so
```

The 540 execution cases stub the original Dart measurement, prefix, allocation,
constructor, fade, visibility and title-refresh callees. They assert original return
PCs, tagged roots, simulated relocation during multiple calls, prefix-dependent
width boundaries, preference changes, native pressure fallbacks and unchanged VM
registers. Fade cases cover zero/nonzero/invalid fractions, all modes and a
missing widget, checking the unchanged callee arguments and return PC. Page cases
also execute the exact 11
initialization instructions from each fixture and assert that the Stack is
initialized before visibility can trigger GC. They do not run Dart GC or prove
Flutter rendering. Device acceptance is a separate required check after a fresh
native load.

The existing `icon-highlight/test_exact_patch.cpp` now exercises all three exact
patch kinds, including page boundaries and injected protection/rollback failures.
`test-monet-policy-server.sh` covers the independent capability, command framing,
policy isolation and expiry under ASan/UBSan. Focused JVM tests cover API framing,
lost/stale ACK reconciliation and backward-compatible runtime reports.
