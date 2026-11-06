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

The current suites execute 2,785 render cases, 175 mode/curve cases, 470 refresh, and 593 glass ownership/radius
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
registers, and tail transfers. Config: 456 bytes; copied payload: 4,168 bytes.

The render suite also executes the native unlock wrapper construction against
linked production instructions. It checks the 48-byte widget, source-owned
category/global screen identity, canonical Offset/boolean/debug fields, original
IgnorePointer and icon/native-background separation. Its young-space boundary
cases include the entire wrapper before publishing the allocation top.
The installer permits a bounded 8 KiB payload, rounded to the actual page size.
No second animation implementation is modeled by this host test: native Flutter
animation, interruption and idle behavior require actual device recordings.

The profile suite resolves the search-center edges and native StatefulWidget
dispatch in all five source fixtures. Seven additional mutations per source
cover the new caller, allocator, category, field store, state construction and
geometry roles; rejected output must also clear the unlock fields.

Full-five geometry has additional conservative curved-rectangle containment
checks for every icon corner across native icon sizes, heights and observed
Recents radii. The <=4 branch retains its existing bounds and radius policy.

Mode execution includes both corner choices and preference changes during the
managed call. Owned scope execution terminates at the authenticated visual
continuation; stock scope still enters the original diagnostic. Source mutation
checks reject changed diagnostic callees, visual joins and retained-call GC maps.

Display adaptation regression covers complete vertical icon containment and
conservative fallback when the original parent cannot contain the painted ink.
Parent clipping additionally checks the completed native child list before the
Stack stores it, preserving stock clipping on pressure/invalid/empty fallback.
Its dynamically encoded SizedBox CID comparison preserves all NZCV values,
uses one dead register, and adds no managed allocation.

## Geometry options and placement

`test_bottom.py` executes the linked native margin return leaf for all five
styles, enabled/disabled gates and offset boundaries, checking unchanged stack,
LR and non-scratch registers. Extended renderer cases cover height, Pad/custom
radius, screen-reference geometry and one/five-icon limits. The policy tests
exercise packed options validation and quiet lease renewals. See
`docs/dock-customization.md` for source-coordinate limits and device acceptance.
