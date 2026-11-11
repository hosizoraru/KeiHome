# Dock glass checks

The current Phone inventory in `fixtures.json` includes 7722, 8593, 8602, 8604,
8607, 9209, 9226 and 9239. Regenerate strict patterns with `generate_patterns.py`;
`verify_fixture_oracle.py --binary PATH` independently compares every inventory
entry to native debugdata and caller relationships. See the
[9239 checkpoint](../../../docs/launcher-9239-compatibility.md) for the final
shipping-payload and device boundary.

Use `verify_fixture_oracle.py --versions 8607 --binary PATH` for a new fixture's
independent role comparison without repeating the complete retained matrix.
The 8607 AOT image equals 8604 byte for byte; its APK and Rust base remain separate
inputs. Shared production contracts are unchanged.

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

The renderer executes 3,411 cases, including 579 five-slot clearance regressions:
inclusive zero clearance, fractional boundaries, negative overflow, and unchanged
one-to-four/elderly behavior. The pure geometry suite runs 39,347 ASan/UBSan
checks. Mode/curve, refresh and glass ownership/radius have separate execution
suites. They authenticate the payload from the supplied ELF rather than a
separately assembled approximation.

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

The bounded profile inventory in `fixtures.json` covers 7722, 8593, 8602, 9209,
9226 and 8604. The latter uses a fused IC `LDP` instead of `ADD` + `LDP`, reducing
the phone closure from `0x4c0` to `0x4bc` bytes. Its root-list save moves by four
bytes. The resolver bounds its scan by the matched contract and binds the save
to the list result, receiver reload and matching Stack reload. The two added
Phone/Outer contracts retain exact instructions; existing masks are unchanged.

Focused host checks and reproducible regeneration:

```sh
clang++ -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror \
  -Iapp/src/main/cpp scripts/tests/dock-glass/test_profile.cpp \
  app/src/main/cpp/dock_glass_profile.cpp \
  app/src/main/cpp/launcher_label_profile.cpp \
  app/src/main/cpp/icon_highlight_profile.cpp -o /tmp/keihome-dock-profile-test
for fixture in 7722 8593 8602 9209 9226 8604 9239; do
  /tmp/keihome-dock-profile-test ".artifacts/inputs/launchers/$fixture/libapp.so"
  /tmp/keihome-dock-profile-test ".artifacts/inputs/launchers/$fixture/libapp.so" --root-only
done
python3 scripts/tests/dock-glass/verify_fixture_oracle.py \
  --binary /tmp/keihome-dock-profile-test
python3 scripts/tests/dock-glass/generate_patterns.py \
  --fixtures scripts/tests/dock-glass/fixtures.json \
  --output /tmp/keihome-dock-patterns.inc
cmp app/src/main/cpp/dock_glass_patterns.inc /tmp/keihome-dock-patterns.inc
```

The oracle reads native symbols independently from `.gnu_debugdata` and locates
anonymous closures through calls to the named background/sync functions and
Dart frame boundaries. The profile mutations include both IC forms, the shifted
root-list sequence, and executable/non-executable duplicate phone contracts.
These checks establish resolver compatibility; device acceptance is separate.
The direct root proof rejects two complete call/result/receiver/save sequences,
accepts the sole sequence when moved, and rejects missing or truncated proofs.
`test_modes.py` imports the same six-fixture inventory for its choices and `all`
execution; each caller stack map is still decoded independently from the actual
serialized snapshot. Run mode bridge acceptance against the final linked SO.

Controlled managed-call boundaries and simulated root relocation test the
bridge contracts. Actual Dart GC, rendering, input, material following, runtime
preference application, and performance require separate AVD acceptance.

The ownership suite also executes both native task-radius return observation and
the inlined flight-radius join against linked production instructions. It checks
exact primitive writes, native frame-load replay, ownership counters, preserved
registers, and tail transfers. Config: 456 bytes; copied payload: 4,512 bytes.

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
