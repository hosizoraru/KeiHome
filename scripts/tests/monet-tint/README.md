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
  backing-file SHA. It exercises **36 rejected inputs** covering modified
  functions/call instructions, build identity, mapping boundaries, permissions,
  missing anchors, overflow and malformed metadata. Rejections clear the result
  and preserve previously published caller globals. Mutations affect only the
  test's in-memory copy.
- NDK compilation checks the tint bridge, tint profile, shared image identity,
  and `monet_hooks.cpp` with the native warning/visibility/branch-protection
  flags. The test ELF links the actual repository bridge with authored
  trampoline stops and primitive globals from `test_symbols.S`.
- **43 AArch64 machine-code cases** run that ELF in Unicorn with synthetic
  registers, native policy storage, Dart stack, PP, and heap objects. They check
  successful tint, caller/object/mode/input guards, Smi/Mint background values,
  wrong CIDs, null/sentinel state, policy withdrawal, reader saturation and a
  policy pointer swap. Every case verifies register/vector/NZCV/stack
  preservation, reader balance, and permitted writes. Disassembly rejects
  added calls/syscalls.

Scoped equality is intentionally **independent of policy**: the two verified
setter return PCs and both operands matching the live constant are sufficient
to return Dart false. Its tests require zero policy reads and zero reader-counter
writes, including null policy, flags zero, reserved flags and a saturated
counter. Other callers or operands still enter the original equality function.
Thus the standard foreground refresh after a disable/manual-selection ACK can
invalidate an old native filter; the foreground bridge, whose policy guard
still applies, then passes through the original black ARGB. With an enabled
policy and expired denies (`count == 0`), the accepted colors remain eligible
when the live selected background matches. The hooks themselves do not schedule
refresh, acknowledge Rx updates, or prove that a UI frame repainted.

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
