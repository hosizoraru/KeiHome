**Monet tint host validation**

Run from any working directory by supplying the script's path and an external,
extracted ARM64 `libapp.so` matching the current native tint profile:

```bash
bash scripts/tests/monet-tint/validate.sh \
  --library /path/to/external/libapp.so \
  --require-emulation
```

The launcher library is read in memory by the resolver test. It is never copied
into the repository or output directory. The runner derives the repository root
from its own location, independently of the current directory. It creates a new
system temporary directory for compiled objects and JSON reports; `--output`
selects another directory. `commands.log` records the exact compiler and test
commands, and `validation.json` records their results and source hashes.

macOS and Linux hosts require Python 3.8+, a host C++17 compiler, and an Android
NDK containing the API 37 AArch64 compiler wrappers. The optional emulation
packages are listed in `requirements-emulation.txt`:

```bash
python3 -m venv /path/to/test-venv
/path/to/test-venv/bin/python -m pip install \
  -r scripts/tests/monet-tint/requirements-emulation.txt

NDK_DIR=/path/to/android-ndk \
PYTHON=/path/to/test-venv/bin/python \
CXX=clang++ \
bash scripts/tests/monet-tint/validate.sh \
  --library /path/to/external/libapp.so \
  --require-emulation
```

`PYTHON` and `CXX` select executable paths, not command strings with extra flags.
Without `NDK_DIR`, the runner looks for the newest installed NDK under
`ANDROID_SDK_ROOT`, `ANDROID_HOME`, the repository's `local.properties`, or the
usual macOS/Linux SDK locations. All LLVM tools come from that NDK. Direct
invocation with `python3 scripts/tests/monet-tint/validate.py` also works.

The resolver and Android compilation need only Python's standard library. When
Unicorn, pyelftools, or Capstone cannot be imported, the default run gives the
exact import failure and installation command, marks emulation **skipped**, and
records an overall **partial** result. `--require-emulation` turns that omission
into a failing exit status for CI. `--skip-emulation` explicitly requests the
partial run. No packages are installed automatically. Assertions stay enabled;
Python `-O`/`PYTHONOPTIMIZE` is rejected and the host compiler uses `-UNDEBUG`.

The retained checks are:

- The actual resolver, linked with repository `monet_tint_profile.cpp` and
  `monet_image_identity.cpp`, accepts the exact external library using its real
  backing-file SHA. It exercises **41 rejected inputs** covering modified
  functions/call instructions, build identity, mapping boundaries, permissions,
  missing anchors, overflow and malformed metadata. Rejections clear the result
  and preserve previously published caller globals. Mutations affect only the
  test's in-memory copy.
- NDK compilation checks the tint bridge, tint profile, shared image identity,
  `monet_hooks.cpp`, and `monet_policy_server.cpp` with the native
  warning/visibility/branch-protection flags. The test ELF links the actual
  repository bridge with authored trampoline stops and primitive globals from
  `test_symbols.S`.
- **69 AArch64 machine-code cases covering 81 bridge invocations** run that ELF
  in Unicorn with synthetic registers, native policy storage, Dart stack, PP,
  and heap objects. They check successful tint, caller/object/mode/input guards,
  Smi/Mint background values, wrong CIDs, null/sentinel state, never-enabled
  equality, repeated invalidation after withdrawal, reader saturation and a
  policy pointer swap. Every invocation verifies register/vector/NZCV/stack
  preservation, reader balance, permitted writes, and unchanged tint history.
  Disassembly rejects added calls/syscalls.

ColorFilter equality preserves **stock behavior until the first enabled policy
publication**. The native process-lifetime flag `kh_monet_tint_ever_enabled`
starts at zero. For both verified setter callers, the bridge then transfers to
the original equality function before reading live Dart roots. Disabled
publications and rejected first-enable requests leave the flag zero. The
publisher sets it atomically before an enabled policy becomes visible, including
an enabled policy with no deny entries (`count == 0`).

**After enablement, equality invalidation is independent of current policy.**
The flag is never consumed or reset on withdrawal, expiry, disconnect, or
API-102 Java hot reload. The two verified setter return PCs and both operands
matching the live constant then return Dart false on every invocation. Other
callers or operands still enter the original equality function. Equality tests
require zero policy reads and zero reader-counter writes, including null policy,
flags zero, reserved flags and a saturated counter. Both render and layer callers
have null/disabled never-enabled cases. Each also has two stateful withdrawal
cases: one enabled invocation followed by three distinct setter frames after
disabled or null-policy withdrawal, reusing the same emulator and history flag.

This preserves the standard foreground refresh after a disable/manual-selection
ACK: each instance can invalidate an old native filter, and the foreground
bridge, whose policy guard still applies, passes through the original black
ARGB. With an enabled policy and expired denies (`count == 0`), the accepted
colors remain eligible when the live selected background matches. The
optimization only avoids forced invalidation in processes that have never
enabled tint; it does not suppress invalidation after prior enablement. The
hooks themselves do not schedule refresh, acknowledge Rx updates, or prove that
a UI frame repainted.

Run the separate publisher suite with
`bash scripts/tests/test-monet-policy-server.sh`. It checks the real policy
publisher under AddressSanitizer and UndefinedBehaviorSanitizer, including
rejected first enablement, visibility of tint history to a reader acquiring an
enabled policy, and preservation through withdrawal, expiry, cleanup, and
replacement policy ownership. These host checks do not exercise actual Java
hot reload or Flutter layer lifetimes.

A separate `Object.==` guard makes the original enabled-Rx setter notify once
when the native selection revision changes. Unrelated equality callers take a
bounded fast path without reading live Dart roots or policy; the host test
uses an invalid frame pointer and limits that path to 16 instructions. The
scoped path authenticates both caller PCs,
the Dart frame shape, the live bool GDT target, two exact true operands, and the
live controller's RxBool receiver. A compare-and-swap consumes only the observed
revision; concurrent newer updates stay pending. No Dart reference is retained
and no Dart heap object is written. Tests reject unrelated receivers, stale
revisions, frames, CIDs and dispatch entries, and cover concurrent consumption.
The Java owner notifies the existing settings URI only after native ACK; actual
controller updates and painting remain owned by the launcher's original stream.

**Provenance and scope**

These harnesses were authored for the independent 2026-10-29 KeiHome tint ABI
review and migrated from its host-only experiments. The original dataset was
the launcher image with build ID `4f1bdaed62b77ed2de7d3ebc34e453ca`; its required
SHA-256 is maintained by `kMonetTintImageSha256` in the native profile. The test's
numeric RVAs and PT_LOAD extents are metadata verified against that image, not
copied function bodies. The synthetic ARM64 stop routines are only authored
`nop`/`ret` instructions; all bridge instructions come from compiling repository
assembly. No proprietary ELF, heap dump, extracted instruction array, or copied
official-source tree is included here. No generator or oversized duplicate
binary fixture is needed to run these tests.

The host tests do not use ADB, launch an emulator/device, install an APK, or
deploy hooks. Unicorn instruction execution is not Dart GC stress, an ARM
weak-memory concurrency proof, Flutter lifecycle validation, or visual acceptance.
