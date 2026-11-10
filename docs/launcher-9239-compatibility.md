# Launcher 9239 compatibility

Input: `RELEASE-8.01.02.9239-261013-11081915-R`, package
`com.miui.home`, version code `801029239`.
APK SHA-256:
`d0c890d7ce7f1b931126b65cd2dfbcf2c1f6414bc2687859a092a14b6ca9e1dd`.
The APK contains native Flutter/Rust libraries and no DEX. Its `libapp.so`
Build ID is `4f1bdaed011bbe7156bde6449a5efb49`.

The private fixture and provenance are retained under
`.artifacts/inputs/launchers/9239/`. The supplied Downloads file is unchanged.
Versions, hashes, Build IDs and fixture addresses are not production admission
criteria.

## Changes and rejection boundaries

Unchanged production resolvers initially rejected Dock, Monet selection,
tint/widget contracts and the guarded image-recovery clone. Labels, highlight,
Dock refresh and wallpaper material contracts already admitted the new source.

- Dock adds complete reviewed variants for the phone/outer builders, Recents
  eligibility, glass interpolation/build and Recents mount/constructor.
  All previous complete instruction patterns remain. Phone/outer changes include
  closure pool offsets; stack-map, allocation, ownership and call checks remain.
- Selection adds complete instruction/call-graph alternatives. All previous
  930 body graphs and 111 root graphs remain represented.
- Tint adds exact widget/render variants. Its source generator accepts both
  observed foreground-body lengths within the existing production bound.
- Image recovery adds the complete guarded-clone instruction contract.
  Receiver/class/ownership checks and corrupt-body rejection remain.

These are authenticated structural alternatives, without per-version execution
addresses or generic wildcards replacing object-field checks.

## Verification

All seven Phone fixtures are included:
`7722`, `8593`, `8602`, `8604`, `9209`, `9226`, `9239`.

| Area | Verification |
| --- | --- |
| Dock profile | 128 structural/rejection checks per fixture; independent debugdata/call-target oracle |
| Dock geometry | 39,347 ASan/UBSan checks |
| Labels | 4,802 ARM64 cases; roots, callbacks, cached/cold paths and editing |
| Highlight | 1,694 resolver checks, 2,016 native baselines and 10,080 patched executions |
| Wallpaper glass | 9,574 profile/oracle checks, including 550 rejection mutations; 1,439 linked ARM64 cases |
| Monet | Seven-fixture selection/tint/recovery matrix; 483 tint and 378 recovery cases against the integrated shipping library |
| Deployment helper | 12 tests, including a temporary process gap, timeout and unexpected-failure propagation |

Final integrated unstripped library SHA-256:
`d45219c70015619dd11efa5610d3dbfbb8524e35d86ac7791e29e970027314e4`.
The final packaged library must have the same ELF Build ID as that payload;
stripping changes the whole-file hash.

The Phone AVD was temporarily upgraded to the actual supplied 9239 APK.
Visual checks cover native glass Dock, four/five icons, live corner/enable
changes, and Monet light/dark artwork. The normal setting-control interval
retained Launcher `14233/13356/10914754`, system_server
`26084/26005/8548910` and SystemUI `6497/26005/9313556`
(PID/parent/start ticks).
Two selected apps were also dragged across pages and placed together; their
native selection animations completed, and five-app Dock glass remained visible.
This is interaction acceptance, not a claim about frame-time improvements.

Development native activation intentionally replaces the launcher spawner.
The helper previously treated a temporary missing process as fatal instead of
continuing its bounded replacement wait. It now handles that specific condition.
This is separate from module API 102 retirement; unexpected errors still fail.

The AVD rejected online snapshots with `UNSUPPORTED_VK_APP`. Before upgrading,
the guest was synced and paused for an APFS disk clone. This is a disk recovery
copy, not a RAM snapshot. The original environment is restored after acceptance.

Evidence, final artifact identity, API 102 handoffs, screenshots and restoration
are recorded in `.artifacts/research/launcher-9239-20261110/acceptance.json`.
Host execution uses synthetic receivers and selected mocked Dart callees; it
does not prove arbitrary live GC, every GPU or physical-device behavior.
Separate MAML framework SDK fixtures are regression inputs, not libraries
claimed to have come from the 9239 launcher APK.
