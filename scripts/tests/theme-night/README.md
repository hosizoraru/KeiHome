# Theme night investigation: not ready for integration

The four `candidate/theme_night_*` files are an isolated experimental
candidate. Do not add them to the production target or install the detour.
There is no proven archive admission protocol. Host resolver success does not
establish runtime ABI safety, archive ownership, or a visible icon fix.

## Proven current-image behavior

These addresses are evidence from the external 8593 ELF, not production
resolver constants.

- `_createChain` (`0x949230`) writes canonical false (`x22 + 0x30`) to each
  new resource node's `+0x13` field at `0x9493a4` / `0x9493ac`.
- `_initIconTheme` (`0x9491b4`) stores three chains in the thread static table
  at `+0x5430`, `+0x5438`, and `+0x5440`. Only the last receives the seeded
  global mode (`+0x5470`) through `ThemeResources.setNightModeEnable`.
- `getSystem` (`0x947954`) calls the initial mode seed before `_initIconTheme`.
- `ThemeResourcesSystem.setNightModeEnable` (`0x18e24b0`) stores the global
  mode at `+0x5470` and propagates it only through `+0x5440`.
- `ThemeResourcesSystem.getIconBitmap` loads `+0x5430` at `0x1bd8304` /
  `0x1bd8308`, then calls the node bitmap reader.
- `_getIconBitmapLocal` (`0x1bd842c`) reads the node flag at `+0xa4`.
  Its `TBNZ bit 4` takes canonical false to the empty prefix at PP `+0xf8`.
  Canonical true falls through to the night prefix slot at PP `+0xc7d0`.
  The node stream and has-file readers use the same field and pool pair.
- The bitmap reader uses that prefix through its last local lookup. The
  caller then considers the child chain; no same-package day retry appears
  in this reader.

This proves an independent missing night-propagation path. It does not prove
the sole cause of black artwork: main observed the active icons archive
disappear while cached launcher artwork remained.

## Admission blocker and epoch proposal

Root identity is not archive identity. `ThemeResources.checkUpdate`
(`0x1bd7eb4`) keeps its node and package, updates the node timestamp at
`0x1bd7f38`, and updates its fallback flag at `0x1bd7f90`.

`ThemePackage.checkUpdate` (`0x1bd8010`) compares a stat-derived value with
the existing package's `+0x23` field. On a change it updates fields and clears
ZIP/cache state in place at `0x1bd80ec` through `0x1bd8110`. It increments
the package's scalar `+0x17` at `0x1bd8104` / `0x1bd810c` and can reload
the ZIP. It does not call `_initIconTheme` or `_createChain`.

Consequently, an epoch invalidated at init/create entry and published at
return misses this verified update path. Its value could stay constant while
the archive contents change. The package's own revision counter is evidence
of a change, not proof of a registered complete paired archive. It also starts
over when a package object is constructed. Retaining a Dart address would
not repair those semantics.

Checking a Java policy flag, the current root, its CID, its path, or the
constant PP strings cannot bind the worker's verified bytes to the loaded
archive. A worker completion can race replacement. The current candidate
will synchronize a newly current root while an old mode=1 remains published.
Mode=0 performs no heap writes and therefore does not undo an earlier true
stored in a surviving node. Mode=2 is not a synchronous revocation mechanism.

Safe current-build decision: leave the candidate unintegrated. A future
admission protocol must identify the exact archive loaded by the native
reader, handle in-place update and root replacement, and reject stale worker
publication. No such protocol is implemented here.

A per-read prefix plus a same-package normal retry could avoid persistent
node state. The current reader does not supply that retry; implementing it
requires a separately verified async control-flow change. It is a research
direction, not a proven replacement bridge.

## Corrected draft errors

- The one-byte `String.==` routine is the `0xb0`-byte routine with an ASR #1
  to untag its byte length, CID 94 in these fixtures. The `0xac`-byte routine
  is the two-byte comparison and was selected incorrectly in an earlier
  draft.
- Normal prefix is empty, not `res/drawable/`. The malformed eight-byte
  comparison constant has been removed along with that incorrect oracle.
  The reader appends its drawable path after choosing the prefix.
- Night/normal pool labels follow the canonical boolean branch, correcting
  an earlier reversed assignment.
- Allocation size is bits `[11:8] * 16`, proved by the generic allocator
  instructions reached from the resource allocator. It is not the low
  twelve tag bits. The host mutation test changes size while retaining CID.

## Host-only resolver command

Run from the repository root; fixtures remain external:

```bash
mkdir -p /tmp/keihome-theme-night-test
clang++ -std=c++17 -O2 -UNDEBUG -Wall -Wextra -Werror \
  -Iapp/src/main/cpp -Iscripts/tests/theme-night/candidate \
  scripts/tests/theme-night/test_resolver.cpp \
  scripts/tests/theme-night/candidate/theme_night_resolver.cpp \
  app/src/main/cpp/monet_image_identity.cpp \
  -o /tmp/keihome-theme-night-test/resolver
/tmp/keihome-theme-night-test/resolver /tmp/keihome-launcher-8593/libapp.so
/tmp/keihome-theme-night-test/resolver \
  /tmp/keihome-launcher-analysis-20261029/unpacked/lib/arm64-v8a/libapp.so
```

The generator uses external debug symbols only as a test oracle. Production
resolution uses bounded mapped instruction scans, normalized code shapes,
decoded operands, and call/field/pool relationships. It neither reads symbols
nor chooses a launcher version by fixed addresses.

ARM64 assembly compilation alone is not ABI validation. Linked Unicorn
bridge execution, actual hook-backend relocation, Dart VM/GC behavior, and
device acceptance have not been completed. No ADB or install action belongs
to this sidecar.
