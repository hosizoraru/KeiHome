# Perfect publication import and native admission

This fix is preparation for 1.10.0. It is not part of the already published
1.9.0 APK or its release notes.

## Confirmed failure boundary

The connected physical device reported `1.9.0+g214d43b.b0391`. At
2026-11-13 10:22:57.132 Asia/Singapore, generation finished with 117 mappings
(102 static, 15 dynamic), but adding the finished ZIP to the local library failed.
The saved publication remained available for recovery.

The supplied LSPosed archives were also examined. One independently identifies
the public `1.9.0+g70ea216.b0394` build on Xiaomi 15 Pro. These archives do not
contain the underlying local-library exception. Their earlier theme-service
initialization and theme-card JSON errors are separate evidence, not substitutes
for a local import failure.

The physical publication is 34,264,929 bytes with 931 ZIP entries. It is within
the existing archive limits and has no exact duplicate paths or case collisions.
Two resources have a `.png` name with another supported raster encoding:

- `res/drawable-xxhdpi/com.twitter.android/1.png`: WebP VP8X.
- `layer_animating_icons/com.netease.cloudmusic/0/quiet/quietImage.png`: JPEG.

`PerfectIconFormat.raster` accepts these encodings by content. The generic native
archive index previously required a PNG signature, so a successfully generated
publication could still fail to enter the local library. An app-process probe
loaded the actual previously installed AVD APK and reproduced:

```text
Invalid native PNG header: res/drawable-xxhdpi/com.twitter.android/1.png
```

This explains how installed-only selections can succeed on a smaller AVD app
set yet fail on a physical device with one of the affected resources. It does
not establish that every user failure has the same cause.

## Implementation

`IconRasterHeader` identifies PNG, JPEG and WebP by content. The native index
uses it for artwork, layers and quiet snapshots; it does not depend on the
Perfect feature or special-case any package name. Image payloads and authored
MAML programs are not transcoded or rewritten.

PNG keeps the 24-byte dimension path. JPEG scans marker segments with a 1 MiB
header budget and bounded stream skipping. WebP checks its RIFF/chunk boundaries,
padding, supported image headers and dimensions with a bounded chunk count.
The existing 8,192-pixel dimension and 32-megapixel limits remain, as do all ZIP
path, CRC, expanded-size, entry-size and compression-ratio limits.

`IconPackImportResult.Failure` retains the underlying exception. Generated
publication imports now preserve its message and cause, and local-state recovery
logs failures. Cancellation remains cancellation. Error reporting occurs on
failed operations, without adding work to icon rendering.

The same real publication is also checked at the later local-native admission
boundary, rather than stopping after library indexing. Additional compatibility
fixes retain its original bytes:

- JPEG artwork passes bounded header and full marker/scan validation, including
  progressive scans, restart markers and EOI. The local 2,048-pixel / 8 MiB
  limits remain independent of the generic archive-index limits.
- A complete, CRC-checked PNG can contain opaque bytes after IEND. Those bytes
  remain inside the entry-size budget and never become separate resources.
  WeChat's `1/fancy/2.png` contains such a trailer; Android decoding is compared
  with a clean image in the device test.
- Canvas names such as `bg` use the same restricted root role; child roles and
  attributes remain explicit. Visual objects and their targets can use numeric
  names. Variable, function, state and config identifiers keep their restrictions.
- `Group.layered` and enumerated `Image.xfermode` support isolated compositing.
  Blending requires an isolated ancestor. Each surface has literal dimensions,
  with limits of eight surfaces, four nested surfaces and 2,048 squared total
  pixels per program.
- The native MAML comparison aliases `{`, `}`, `{=`, `}=` are accepted in bounded
  numeric expressions. Function/variable checks remain; the additional shift
  aliases `{{` and `}}` are rejected.
- A missing single literal `setTo` state follows native no-op behavior. This
  handles authored WeChat `state_ini1/2/3` references without guessing replacements
  for the declared `state_init1/2/3`. Other state commands, targets, configs and
  callback-graph checks remain enforced.

The retained Rust MAML SDK's parser, state lookup and `setTo` branches were
inspected independently; evidence is in
`log-audit/additional-local-admission.md` under the research directory.
No version/package whitelist, native Hook or extra IO/IPC/script capability is added.

## Recovery and checks

Existing completed ZIPs can be retried locally after updating. Reopening the
Perfect page can recover a publication whose saved record ID is empty; it does
not require deleting app data or fetching the resources again.
The controller commits its loaded state only after recovery succeeds. Publishing
a new completed ZIP clears that state until import succeeds, so a failed import
can be retried on a later entry/resume event in the same process. There is no
retry timer or automatic network loop.

Focused checks are available through:

```sh
python3 scripts/checks.py native-artwork-import --run
python3 scripts/checks.py native-artwork-import-device --serial emulator-5580 --run
```

The optional `PerfectPublicationDeviceTest.observedPhysicalPublicationCanRetryTheLocalImport`
case accepts an instrumentation argument `perfectPublicationFixture` pointing
to a readable publication. It imports twice into an isolated local library,
checks idempotency and verifies the stored SHA-256 against the input. It does
not apply a system theme. It also exports the imported record and passes that
archive through `LocalNativeIconValidator`, covering the next admission boundary.
Portable fixtures separately verify WebP/JPEG under PNG names, unchanged payload
bytes, previews, and retained failure causes.
`PerfectPackRecoveryDeviceTest` repairs a failed saved publication while retaining
the same controller, then verifies successful entry/resume recovery, idempotency
and zero transport calls using isolated directories and MMKV IDs.

Research evidence, original-log digests, the retained physical publication and
before/after probe output are local under
`.artifacts/research/perfect-import-failure-20261113/`. Private log archives and
the physical publication are not source fixtures.

## Acceptance

The final focused JVM run passed 146 cases, with four pre-existing optional
fixture skips (150 cases total). All 27 selected Android cases passed on
`emulator-5580`, including the supplied physical ZIP's import/reimport/export/
local-native validation and the same-process recovery cases. Standalone probes
against the installed diagnostic APK independently accepted the unchanged raw
publication at both indexing and local-native admission.

Normal API 102 Java deployment verified adopted/retired handoffs and unchanged
critical process identities. No native payload, system theme, device settings or
downloaded resources were changed by these isolated tests. Physical collection
was read-only. A real physical-device theme import/application still needs a
retest; these results do not establish that every user's failure has the same
cause or that Theme Manager service/authorization failures are resolved.
