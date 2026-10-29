# Monochrome selection bridge host tests

Run from any directory. Nothing connects to Android, loads a launcher, patches a
process, downloads dependencies, or changes the supplied ELF.

```sh
NDK_DIR=/path/to/android-ndk \
PYTHON=/path/to/python3 \
./scripts/tests/monet-selection/run.sh \
  --libapp /external/path/to/libapp.so \
  --output-dir /tmp/keihome-monet-selection-tests
```

`NDK_DIR` defaults to the project's NDK 30.0.16248370 in the local Android SDK.
`PYTHON` selects the interpreter; only the optional instruction-path model needs
Capstone. `CXX` can select the host C++ compiler. `--libapp` is optional and can
also be supplied through `LIBAPP_FIXTURE`. Missing optional capabilities appear
as **SKIPPED** in `report.json`, never as verified results.

The fixture must be an independently supplied launcher image accepted by
`monet_image_identity.cpp`: APK ELF SHA-256
`fb8b1f91eba2d9c43875f49bbded70cca462be341fc379b9864e7c6136d6e463`,
or extracted AVD ELF SHA-256
`44ab76174a5085b9cc45d44420b82e53c46423e2f58d32e0eee86264dc683dd5`.
Do not add this ELF, decompilation, or full extracted function bytes to the
repository. Production code holds four-instruction prefixes, function hashes,
call relationships and small explicit ABI checks. The runner puts generated
objects, logs and reports in the selected temporary output directory.

The checks cover:

- NDK compilation of the production resolver and assembly with warnings as errors.
- Actual compiled AArch64 instructions: no BL/BLR, syscall or exception, and one
  false return plus one original tail transfer through x16.
- With the external ELF: native C++ resolution, every overwritten-function word
  mutation, String layout/boolean drift, x16 deadness, unexpected callers,
  branch-to-patch-interior, duplicated root, malformed ELF/readable ranges,
  relocated host mapping, segmented mappings and profile publication identity.
- With Capstone: 35 bounded instruction-path cases, including deny/miss, flags,
  count/length limits, null/Smi/non-String, live factory/callback mismatch,
  RCU counter saturation/retry and register/NZCV/x15 preservation.
- On an ARM64 host: execution of the production bridge against a synthetic
  tagged heap and a native original stub. On macOS the harness changes only
  object-format directives and relocation spelling; every unrelocated text byte
  must equal the Android ELF object before execution. It checks deny and original
  paths, register/NZCV preservation, balanced readers and fresh live roots.
  Each old heap/pool arena becomes PROT_NONE before another invocation, exposing
  any retained/reused reference. These are native bridge lifetime regressions;
  they do not claim that the Dart VM, a Dart GC cycle, or launcher rendering ran.

Integration order: main verifies the exact backing ELF and resolves both tint
and selection before any capability patch, publishes the code-only selection
profile, installs the framework hooks on the first fresh libapp notification,
then starts the policy server after all original pointers are available. The
selection bridge itself validates Function.entry_point through live x27 on each
enabled invocation. No setup-time PP cache or external safepoint is required.

Actual launcher/GC/visual acceptance remains a separate gate requiring a new,
authorized launcher generation. This runner does not create that generation.
