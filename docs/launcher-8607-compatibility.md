# Launcher 8607 and SecurityCenter 13.8.1 admission

## Inputs and identity

The two newly supplied APKs were signature-verified, copied into the retained
fixture store, hash-verified, and removed from Downloads:

| Package | Version | versionCode | Retained directory |
| --- | --- | --- | --- |
| `com.miui.home` | `RELEASE-8.01.02.8607-260918-10301044-R` | 801028607 | `.artifacts/inputs/launchers/8607/` |
| `com.miui.securitycenter` | `13.8.1-261023.0.1` | 40001381 | `.artifacts/inputs/securitycenter/安全服务_13.8.1-261023.0.1-bec1ab84a5c9/` |

Each directory retains provenance, full APK SHA-256, actual manifest information,
and signer information. Both signers match the retained OEM signer
`c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`.
The research root is `.artifacts/research/oem-refresh-20261111/`.

The launcher has no DEX. Its `libapp.so` is byte-identical to 8604, while
`libresources_frb.so` and `libnative_widget_sdk.so` equal the 8602/8604 inputs.
`libapp_launcher.so` is distinct and was audited separately: 23 relevant
symbolized functions preserve the recorded function sizes, mnemonic sequences,
and named call relationships; 11 also match after the documented relocation
normalization. This is static evidence, not binary or runtime equivalence.

## Production result

Existing structural resolvers admit the new supported roles without production
changes. No version, hash, obfuscated-name or RVA admission branches were added.
The module's production Kotlin and native files remain unchanged from `4899553`.

The retained Phone fixture inventories now cover eight versions. Relevant test
runners accept a selected fixture so normal work can validate a new sample
without repeating every historical matrix.

## Focused native checks

The checks use production source and the authenticated native payload from
Release 106000410. Host fixture symbols supply independent oracles only.

| Area | Passing evidence for 8607 |
| --- | --- |
| Dock | 128 profile checks; independent role oracle; 52 linked mode cases; 94 linked refresh cases |
| Wallpaper materials | 1,362 positive/rejection assertions and 78 rejection cases; distinct Rust image rejects with 64 assertions / 6 rejection cases; 2,792 linked pair-policy cases |
| Labels | Five independent symbol-role comparisons, 19 rejection mutations, 686 source and 686 shipping bridge cases |
| Highlight | Source and shipping runs together: 484 resolver/builder assertions, 1,890 patch assertions, 576 native baselines and 2,880 patched executions |
| Monet selection | 79 host checks and 35 decoded bridge-model cases, including the shipping bridge |
| Monet tint | 26 resolver rejection checks and 69 ARM64 cases against source and shipping bytes |
| Image recovery | 8 positive / 127 rejection checks and 54 ARM64 cases against source and shipping bytes |
| MAML release | Widget SDK correctly rejects with output address zero; two separate framework positive controls pass 328 checks each |

Adding the eighth fixture reproduces the existing Dock and Monet contracts
byte-for-byte. The theme-night candidate remains disabled: 8607 passes its
6 positive / 32 rejection checks, while 9209, 9226 and 9239 retain their original
rejection. Candidate results do not claim an active night-mode hook.

Exact commands and structured results are in the research root's
`dock-material/`, `monet/`, and `labels-highlight/` directories. Copied ARM64
execution and decoded models do not establish Android loader, live Dart VM/GC,
GPU rendering or visual acceptance for a newly installed launcher.

## SecurityCenter runtime checks

All eight retained APKs pass the independent DexSQL oracle. On Phone AVD, the
expanded DexKit device test then resolves both UI roles, invokes both receiver
paths and restores descriptors into a separate ClassLoader for every APK.
All samples reuse one APK pathname. The two `13.6.6` samples additionally verify
rejection of a cached proof when versionCode is unchanged but bytes differ.

The instrumented test passed in 8.395 seconds: eight samples, one explicit
same-version rejection and two receiver requests per sample. The new 13.8.1
sample took 289 ms for initial resolution and 83 ms for restoration in this
particular run. These are worker-path observations, not startup or FPS benchmarks.

Fixtures are streamed into the debuggable app's private cache using `adb shell
-T run-as ... tee`. This avoids shared-storage permissions. The earlier
shared-storage attempt failed before discovery with `EACCES`; its failed log is
retained separately. Verified staging files are removed after acceptance.

See [shared system application icons](shared-system-app-icons.md) for the
runtime source-hash/cache contract and the repeatable matrix workflow.

## Acceptance boundary

The OEM APKs were not installed over the stable AVD's system applications. The
new launcher has native fixture and shipping-payload acceptance; the new
SecurityCenter additionally has on-device DexKit/ClassLoader acceptance. Neither
is a claim of full visual or physical-device acceptance of those APK versions.
Ordinary module deployment uses API 102 Java handoff with the existing native
payload; no native activation or AVD lifecycle change is required by this work.
