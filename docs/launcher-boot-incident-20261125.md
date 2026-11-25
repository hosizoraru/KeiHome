# Xiaomi 17 Pro launcher boot crash on LSPosed 7949

On November 25, 2026, `com.miui.home` repeatedly crashed during startup on
Xiaomi 17 Pro (`pandora`, HyperOS `OS4.0.8.0.XBLCNXM`, Android 17), with
Zygisk Next `1.5.3 (880-22a1bf5-release)` and LSPosed `v2.2.1-log (7949)`.

| Device time (SGT) | Observation |
| --- | --- |
| 14:52:59 | LSPosed received `PACKAGE_REMOVED` for KeiHome while running 7948. |
| 15:40:04 | LSPosed received `PACKAGE_ADDED` for KeiHome. |
| 15:41:37 | KernelSU staged LSPosed 7949. |
| 15:43:57 | On the next boot, the first `com.miui.home` process died in the HyperOS native spawner. |
| 15:57:01 | After staging 7948 and rebooting, launcher PID 6316 loaded KeiHome's native library. |

The captured sample contains 17 launcher crashes with the same signature.
LSPosed emitted `libart.so '' not found` in the failing PID immediately
before a `SIGSEGV` (`SEGV_MAPERR`, fault address `0x78baf43018`). Frames
`#00`–`#08` were in LSPosed `zygisk/arm64-v8a.so` (top PC `0x1783a8`),
followed by the Zygisk Next loader and `hyos_spawner`. The installed
LSPosed ARM64 binary matched the 7949 archive (Build ID
`0276f76fdbf1ca4e433182cb61ed5bb4aba282d9`). The instruction at `0x1783a8`
loads from `x8`, whose value equals the reported fault address. The
address is at relative offset `0x279018`, past the end of the framework's
last ELF load segment at `0x278928`. The captured launcher PIDs contain no
KeiHome `native_init` logs.

The [7949 release note](https://t.me/LSPosedArchives/4206) describes a rewrite
of the symbol parser and reports a 1.62× speedup for that change. The
faulting loop advances by `0x38` bytes, compares the first word with
`PT_DYNAMIC` and `PT_LOAD`, and reads the virtual address at `+0x10`.
These are the stride and field offsets of `Elf64_Phdr`. The failing read
is consistent with a walk using an invalid ELF program-header span.
The attached developer log ZIP matched the on-device archive byte for byte
(`d85d7c1ff6c94996d2a24bca79dba22d1e22bbeb910ad7f9841947dd03c0a37a`).
A matching source revision or symbol file is needed to identify which
lookup supplied the bad span and whether ART lookup failure caused it.
[Zygisk Next's HyperOS Runtime documentation](https://github.com/LSPosed/ZygiskNext/blob/main/docs/hyos_runtime.md)
describes the runtime as independent of ART.

Both 7949 archives retain the same ARM64 HyperOS Runtime registration as
7948 in `zn_modules.arm64-v8a.txt`:

```text
path=/system_ext/bin/hyos_spawner companion zygisk/arm64-v8a.so
```

The USB retest confirmed that Zygisk Next entered the 7949 log library
from `hyos_spawner`. It reproduced the crash at `0x1783a8` with the
same installed KeiHome and launcher. This confirms that the native Runtime
entry is active and that native module delivery fails on this configuration.
The non-log 7949 ZIP has the same registration and a different framework
Build ID (`52e572f9a3e51a15a6c149486fa7d307b67d60f3`). Its registration
was checked offline. The user reported the same launcher failure after
installing the non-log build. The exported device capture was identified
as 7949-log by the daemon version and the live ARM64 library hash.

## Module compatibility

Maven Central lists `102.0.0` as the latest release for both
[`api`](https://repo.maven.apache.org/maven2/io/github/libxposed/api/maven-metadata.xml)
and [`service`](https://repo.maven.apache.org/maven2/io/github/libxposed/service/maven-metadata.xml).
KeiHome already uses those versions. The published sources and Javadoc
describe the module callbacks and service interface. The 7949 device log
reported API 102 and recorded KeiHome initializing hooks in `system_server`,
SystemUI and Settings.

KeiHome's desktop payload enters through `META-INF/xposed/native_init.list`
and `libkeihome_hyos_lsp.so`. LSPosed owns the spawner initialization before
that entry point. An upstream repair must allow HyperOS Runtime startup
with ART absent, bound the program-header and dynamic table walks to valid
mapped ranges, and still deliver the native module callback. Keep the current
KeiHome dependencies and scope for the compatibility check so that the
framework build is the only variable.

## Recovery and verification

KernelSU staged the original LSPosed 7948 archive in place after verification
of the ZIP SHA-256 and the staged ARM64 library hash. The module configuration
database was backed up and passed SQLite `quick_check`. The pre-recovery
KeiHome scope included `com.miui.home` and was identical after reboot. The
launcher and KeiHome data directory inodes matched the pre-reboot values.
After reconnecting at the new ADB address, the phone reported an unlocked
user 0, SELinux Enforcing, active LSPosed 7948, Zygisk Next 880, launcher
`801030308`, and KeiHome `115000770` (b0770). Pressing Home entered the
primary display's desktop. Screenshot inspection and the native load log
for PID 6316 confirmed recovery. The captured crash buffer contained no
main launcher crash.

## Candidate acceptance

Run after applying the candidate on a test device with the native HyperOS
launcher, rebooting, unlocking and pressing Home. Run early enough to
preserve the boot's crash and native load logs. The checker reads device
state and logs without changing the launcher, APK, module or scope.

```sh
python3 scripts/check-launcher-cold-boot.py \
  --serial "$serial" \
  --framework-version "$framework_version" \
  --keihome-code "$keihome_code" \
  --evidence "$new_evidence_directory"
```

Test the developer log build and the actual release artifact separately.
Pass requires the expected framework variant and KeiHome build, primary
HOME in the foreground, a stable live native launcher PID, no main launcher
crash in the captured buffer, and a KeiHome native load record for that PID.
Capture and inspect the desktop separately and exercise one enabled native
feature. Also verify the Java targets and API 102 reload separately when
accepting a new framework. The 7948 log build passed the cold boot checker
on the recovered phone. The 7949 log build failed native startup on this
configuration. A crash capture tied to the production Build ID and
verification of a framework fix are still pending.
