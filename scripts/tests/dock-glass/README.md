# Dock glass checks

See [feature and acceptance boundaries](../../../docs/dock-glass.md) and
[refresh contract](../../../docs/dock-glass-refresh.md).

Run pure geometry checks with ASan/UBSan:

```sh
bash scripts/tests/dock-glass/run_tests.sh
```

The execution suites use Python with Unicorn, pyelftools, and Capstone and the
linked production Release SO. They consume the retained, external launcher
fixtures under `.artifacts/inputs/launchers/`:

```sh
python3 scripts/tests/dock-glass/test_bridge.py --elf PATH_TO_RELEASE_SO --report /tmp/dock-render.json
python3 scripts/tests/dock-glass/test_modes.py --elf PATH_TO_RELEASE_SO --report /tmp/dock-modes.json
python3 scripts/tests/dock-glass/test_refresh.py --elf PATH_TO_RELEASE_SO --report /tmp/dock-refresh.json
python3 scripts/tests/dock-glass/test_glass_scope.py --elf PATH_TO_RELEASE_SO --report /tmp/dock-glass-scope.json
```

The current suites execute 2,485 render cases, 145 mode/curve cases, 470 refresh, and 593 glass ownership/radius
cases across the five saved source fixtures. They authenticate the payload from
the supplied ELF rather than a separately assembled approximation.

`test_profile.cpp` is a standalone render profile/mutation runner. Compile it
with `dock_glass_profile.cpp`, `launcher_label_profile.cpp`, and
`icon_highlight_profile.cpp`; pass one fixture `libapp.so`. The refresh resolver's
standalone fixture/mutation runner is
`app/src/test/cpp/dock_glass_refresh_profile_test.cpp`.

`generate_patterns.py` consumes an external JSON source-oracle inventory via
`--fixtures` and writes the reviewed instruction contracts via `--output`.
Source versions and symbol addresses in that inventory are test/generator
oracles, not production admission gates. Regeneration requires review of the
native call/data relationships, not merely widening masks until a fixture fits.

Controlled managed-call boundaries and simulated root relocation test the
bridge contracts. Actual Dart GC, rendering, input, material following, runtime
preference application, and performance require separate AVD acceptance.

The ownership suite also executes both native task-radius return observation and
the inlined flight-radius join against linked production instructions. It checks
exact primitive writes, native frame-load replay, ownership counters, preserved
registers, and tail transfers. Config: 424 bytes; copied payload: 3,936 bytes.

Full-five geometry has additional conservative curved-rectangle containment
checks for every icon corner across native icon sizes, heights and observed
Recents radii. The <=4 branch retains its existing bounds and radius policy.
