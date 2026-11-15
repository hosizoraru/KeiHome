# Native icon highlight host checks

These tests consume local copies of exact launcher ELFs. They never use ADB,
launch/restart a process, run Gradle, or modify an input ELF. Generated test
libraries, bridge objects and results stay under `--output`.

With no positional ELFs, the runner uses the shared test-only
`scripts/tests/launcher-labels/fixtures.json` inventory: 7722, 8593, 8602,
8604, 8607, 9209, 9226, 9239, 9248 and 9258. Explicit ELF paths or `--inventory` override it.
Install pinned Python dependencies with
`python3 -m pip install -r scripts/tests/icon-highlight/requirements-emulation.txt`.
Add `--payload /absolute/path/to/current/unstripped/libkeihome_hyos_lsp.so`
to execute the actual linked template. The runner snapshots that payload,
authenticates all 64 template bytes against freshly assembled current source,
and records the full SO, source and bridge SHA-256 values in `payload.json`.
Run once without `--payload` and once with it for separate source/linked
execution evidence. Authentication covers the bridge, not other payload C++.

The runner verifies each adjacent `debug.elf` against embedded debugdata and
records its hash and all five symbol roles. It regenerates patterns from all
selected independent symbol bodies and requires byte equality with production.
These symbols and hashes are test evidence; runtime resolution uses instruction
contracts.

9248 and 9239 pass unchanged production patterns in both source and linked
bridge modes. Focused baseline and final evidence is under
`.artifacts/research/launcher-9248-20261114/labels-highlight/`.
Each fixture in each mode passes 242 resolver/builder checks, 288 native
baselines and 1,440 patched executions (including 1,120 disabled bypasses).
Each invocation also passes 945 exact-patch assertions. The linked run uses
`.artifacts/releases/109000457/libkeihome_hyos_lsp.so`; the evidence checks its
allocated sections against the archived APK as well as authenticating its
64-byte bridge against current assembly.

8607 passes without changing production patterns. Focused evidence is retained
under `.artifacts/research/oem-refresh-20261111/labels-highlight/` in
`highlight-8607-source/` and `highlight-8607-linked/`. Each run performs 242
resolver/builder checks, 288 native baselines and 1,440 patched executions
(including 1,120 disabled bypasses), plus 945 exact-patch assertions. The linked run uses
the archived `.artifacts/releases/106000410/libkeihome_hyos_lsp.so`.

Requires Python with Unicorn, a host `clang++`, and the NDK LLVM tools. Example:

```sh
PYTHONDONTWRITEBYTECODE=1 /tmp/keihome-capstone-venv/bin/python \
  scripts/tests/icon-highlight/run.py \
  --output /tmp/keihome-theme-research/launcher-evidence/highlight-implementation/host-tests \
  --ndk /Users/voyager/Library/Android/sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin
```

Local input provenance and archived evidence are indexed in
[the research index](../../../docs/research/README.md). The APK/ELF inputs
are private local fixtures and are not committed.

- C++ resolver is compiled and invoked against mapped PT_LOADs. Each result is
  compared with that exact ELF's own decompressed debug symbols. Production
  resolution does not consume those names, a version list or fixed RVAs.
- Every reviewed opcode/register bit receives a rejection mutation. Additional
  cases cover graph/field mismatch, duplicated candidates, external direct and
  conditional patch entry, invalid mappings and coherent unknown relocation.
- The actual assembly template is assembled; the actual C++ builder fills its
  replay words and atomic address. Unicorn executes that bridge inside original
  guard/lookup/return instructions on each image. Only native lazy lookup is
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
