# Native icon highlight host checks

These tests consume local copies of both launcher ELFs. They never use ADB,
launch/restart a process, run Gradle, or modify an input ELF. Generated test
libraries, bridge objects and results stay under `--output`.

Requires Python with Unicorn, a host `clang++`, and the NDK LLVM tools. Example:

```sh
PYTHONDONTWRITEBYTECODE=1 /tmp/keihome-capstone-venv/bin/python \
  scripts/tests/icon-highlight/run.py \
  /tmp/keihome-theme-research/apk-home/lib/arm64-v8a/libapp.so \
  /tmp/keihome-launcher-analysis-20261029/unpacked/lib/arm64-v8a/libapp.so \
  --output /tmp/keihome-theme-research/launcher-evidence/highlight-implementation/host-tests \
  --ndk /Users/voyager/Library/Android/sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin
```

- C++ resolver is compiled and invoked against mapped PT_LOADs. Each result is
  compared with that exact ELF's own decompressed debug symbols. Production
  resolution does not consume those names, a version list or fixed RVAs.
- Every reviewed opcode/register bit receives a rejection mutation. Additional
  cases cover graph/field mismatch, duplicated candidates, external direct and
  conditional patch entry, invalid mappings and coherent unknown relocation.
- The actual assembly template is assembled; the actual C++ builder fills its
  replay words and atomic address. Unicorn executes that bridge inside original
  guard/lookup/return instructions on both images. Only native lazy lookup is
  mocked; material guard execution remains original.
- Result, all non-result GPR/SIMD/flags/frame state, writes and call sequences
  are compared to native. Disabled guards never enter the bridge. Policies
  0/1/0/invalid/invalid run on the same synthetic receiver.
- The actual patch transaction runs with injected OS callbacks, including
  cross-page writes, 4/16/64K pages and protection/rollback failures.

Run the independent installer/capability tests without ELF or NDK fixtures:

```sh
bash scripts/tests/test-monet-install-readiness.sh
bash scripts/tests/test-monet-policy-server.sh
```

The readiness test includes the actual installer orchestration with mocked
loader, resolver, detour and transport boundaries. It injects all four partial
hook failures and pre-hook failures, checks publication before transport starts,
and verifies that transport failure cannot clear installed capability or
cause another hook installation. The separate server suite checks greeting
capability masks and isolates Monet and material command admission. Both run
with AddressSanitizer and UndefinedBehaviorSanitizer.

Production uses an explicit 16-byte `ldr x0; br x0; .quad bridge` at the verified
theme tail. It does not pass a mid-function address to opaque `hookFunc`.
The native disabled branch and epilogue lie beyond the patch.

The private bridge follows RW -> RX. The target page follows the existing
backend's temporary writable+executable permission, so a protection error
cannot leave launcher code nonexecutable. Before publication both pages are
registered with the existing madvise guard. Failed restore attempts rollback;
if rollback cannot proceed, the complete resident detour remains default-off
and its bridge is retained for process lifetime. Such a failure never reports
installed or accepts opt-in.

Only fresh-image integration is provided. No hot patch, heap write, extra Dart
call, forced theme identity, package refresh or widget reinitialization occurs.
Tests do not validate Android memory protection, live branch protection, GC,
renderer behavior, transport leases or mounted-icon visual refresh.
