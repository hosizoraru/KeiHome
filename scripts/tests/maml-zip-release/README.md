# Rust MAML ZIP metadata release

Run the durable framework inventory plus the 8607, 9239, 9248 and 9261 launcher widget-SDK
negative fixtures from the repository root:

```sh
python3 -m pip install -r scripts/tests/icon-highlight/requirements-emulation.txt
python3 scripts/tests/maml-zip-release/run.py \
  --output .artifacts/research/maml-zip-release --sanitizers
```

`fixtures.json` keeps the two external framework `librust_maml_sdk.so`
fixtures separate from the launcher's `libnative_widget_sdk.so`. The runner
obtains framework release RVAs from independent debug symbols, runs both host
ownership suites, regenerates their graph patterns under `--output` and
compares them byte-for-byte with production. It never writes production output.
For each exact launcher widget SDK it requires both absence of `zip_archive`
symbols/release roots and rejection by the production mapped-code resolver
with a zero output address. This is a negative regression fixture, not newly
admitted SDK support. `--sanitizers` also runs the queue/priority suite under
ASan+UBSan and TSan. `results.json` records fixture hashes and outcomes.

The focused 8607 evidence is retained under
`.artifacts/research/oem-refresh-20261111/labels-highlight/maml-8607/`.
Its widget SDK is byte-identical to the archived 8602 and 8604 SDKs. It has no
ZIP role symbols and remains rejected with a zero output address. This check
compiles the current resolver on the host; it does not execute the shipping
MAML C++ code or establish positive launcher-SDK support.

9248 has 3,130 decompressed debug symbols, no ZIP archive or `Arc<Shared>`
drop-role symbols, and returns the same rejection with a zero output address.
Both external framework SDK controls still pass all 328 checks each. Focused
baseline and final evidence is under
`.artifacts/research/launcher-9248-20261114/labels-highlight/`.
No production pattern or role admission was broadened.

The bounded queue uses one worker, 64 pending slots and a semaphore-backed MPSC
ring. The host test covers 9,095 exactly-once releases, initialization failures,
out-of-order publication, single-attempt reservation races, ticket wraparound,
saturation, worker recursion and eight concurrent producers. Run the same test
under ASan/UBSan and TSan using the commands below. It also runs nine injected
worker-priority scenarios. Neither sanitizer run substitutes for Android
interaction and frame-trace acceptance.

Run from the repository root. SDK binaries are local source fixtures, not
redistributed project assets.

```sh
clang++ -std=c++17 -Wall -Wextra -Werror -O2 -Iapp/src/main/cpp \
  app/src/test/cpp/maml_zip_release_profile_test.cpp \
  app/src/main/cpp/maml_zip_release_profile.cpp \
  app/src/main/cpp/icon_highlight_profile.cpp \
  -o /tmp/keihome-zip-profile-test

/tmp/keihome-zip-profile-test \
  .artifacts/inputs/framework/librust_maml_sdk.so 0x4592b0
/tmp/keihome-zip-profile-test \
  .artifacts/inputs/framework/maml/pandora-20261106/librust_maml_sdk.so 0x49d640
```

Copyable queue/priority sanitizer commands (no SDK fixtures or Gradle needed):

```sh
(
  set -eu
  host_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/keihome-zip-priority.XXXXXX")
  for sanitizer in address,undefined thread; do
    case "$sanitizer" in
      address,undefined) test_name=deferred_zip_release_asan ;;
      thread) test_name=deferred_zip_release_tsan ;;
    esac
    clang++ -std=c++17 -Wall -Wextra -Werror -O1 -g \
      "-fsanitize=$sanitizer" -pthread -Iapp/src/main/cpp \
      app/src/test/cpp/deferred_zip_release_test.cpp \
      app/src/main/cpp/deferred_zip_release.cpp \
      -o "$host_test_dir/$test_name"
    "$host_test_dir/$test_name"
  done
)
```

Each binary is invoked **without arguments**: there are no CLI case selectors.
Both runs execute all nine scenarios below, followed by initialization-failure
checks and the existing 9,095-allocation regression. The names are the exact
`PriorityScenario` identifiers in the C++ test, not invented command-line flags.

| Scenario identifier | Assertion coverage |
| --- | --- |
| `Hysteresis` | Remaining backlog 32 boosts 10 to 0; 9 retains 0; 8 restores 10; renewed pressure repeats the cycle. |
| `GateOff` | Fixed nice 10 under pressure; no boosts or per-item priority calls. |
| `ToggleGate` | Gate 0 restores 10 on the next active iteration even with 31 queued. |
| `DeniedBoost` | Failed boost retains observed 10, records one failure, and disables all later transitions. |
| `DeniedRestore` | Failed restore retains observed 0, records one failure, and disables retries. |
| `ReadbackFailure` | A successful set followed by unknown readback records failure/unknown nice and disables transitions. |
| `InitialDeniedSafe` | Initial failure with observed 10 permits the queue but disables transitions. |
| `InitialDeniedNegative` | Initial failure with observed -10 rejects startup, including concurrent starters. |
| `InitialDeniedUnknown` | Initial failure with unknown nice rejects startup, including concurrent starters. |

The seven usable-worker scenarios each verify 65 releases exactly once,
worker-only adapter calls, no negative requests, failure/boost counters and
idle behavior. Priority adapters are injected; tests never change macOS
process niceness. Each assertion failure causes a nonzero suite exit. The
executable prints one aggregate success line, not individual case output.

The 2026-11-07 command expansions, source hashes, captured stdout/stderr, exit
codes and per-case assertion results are retained in
`.artifacts/research/launcher-followup-20261107/priority-host-tests.log`.

The two SDK tests each perform 328 checks. They include typed destructor
callees, wrong-but-executable branch destinations, allocator import identity,
live-style relocated dynamic pointers, ambiguity and malformed bounds. The
addresses above are independent test oracles; production has no address table.

To reproduce the 37-node/238-instruction normal graph, install Python
`pyelftools` and `capstone`, then run:

```sh
python3 scripts/tests/maml-zip-release/generate_patterns.py \
  .artifacts/inputs/framework/librust_maml_sdk.so \
  .artifacts/inputs/framework/maml/pandora-20261106/librust_maml_sdk.so \
  /tmp/keihome-zip-patterns.inc
cmp app/src/main/cpp/maml_zip_release_patterns.inc /tmp/keihome-zip-patterns.inc
```

The generator uses both miniature debug-symbol images as source oracles.
Production uses neither those symbols nor image hashes. Exception landing pads
are excluded from the normal graph. The allocator must be imported `free`;
the implementation currently bound/interposed behind that import remains the
platform's allocator contract.

On-device verification must authenticate the resident module ELF before writing
the private diagnostic gate, retain normal MAML finish/texture operations, and
restore the gate to enabled. Validate same-process paired gestures, cleanup
drain, thread idling, native visuals and process continuity. AVD execution does
not substitute for physical-device performance evidence.

The matched physical cleanup-dispatch A/B on launcher 8602, including unchanged
process identities and remaining GC/layout costs, is recorded in
`docs/launcher-drop-performance.md`.
