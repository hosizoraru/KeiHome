# Rust MAML ZIP metadata release

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

clang++ -std=c++17 -Wall -Wextra -Werror -O1 -g \
  -fsanitize=address,undefined -pthread -Iapp/src/main/cpp \
  app/src/test/cpp/deferred_zip_release_test.cpp \
  app/src/main/cpp/deferred_zip_release.cpp \
  -o /tmp/keihome-zip-queue-test
/tmp/keihome-zip-queue-test
```

Repeat the queue command with `-fsanitize=thread` for concurrent ownership
checks. The queue test covers 8,067 allocations, a blocked consumer/full queue,
startup fallback, eight producers, recursion rejection and complete drain.

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
