# HyperOS Home 8.01.03.308

The Phone AVD (`emulator-5580`) runs Home `801030308`. Its APK SHA-256 is
`fccc89d61fbbda8d6a43e777c082443463b2898a41ad2c67101bc806d585f8f3`.
The Dart `libapp.so` SHA-256 is
`2b123ee04d82ebd0bb7c1efca92549e1a833b37cbd071c4e6cc4765618f9e87b`.

## Resolver changes

- The reactive value getter has several callers with similar instruction
  shapes. Bind the shared grid configuration to the title reader, using
  either its call or the matching controller slot and reactive field. Resolve
  the page grid from the cell factory and retain the separate title-rendering
  proof. The current build returns to a direct native Marquee constructor.
- Item changes can move the logger's frame slots as a group. Require correlated
  slot movement and validate the exact GC bitmap at the native logger return
  PC from the decoded FP slots. The live label roots come from the Text
  construction and its continuation.
- Dock geometry accepts the separately proven inlined grid-width reads.
  Validate the receiver, reactive getter and width fields. Find the boxed
  Double header in native hotseat allocation code, including when the inner
  closure leaves its own width unboxed. The mode bridge also verifies the
  caller's frame size and exact original return-PC stack map.
- The larger native ClipRRect initializes an additional field. Keep the native
  allocator for that Glass layout and preserve its radius path. Search capsule
  resolution admits the matching additional zero store and verifies the
  allocator shape before installing the independent material selector.
- Extend Monet's selection, tint and image recovery contracts with the new
  compiler output. Preserve both existing families and the independent
  asynchronous feature gate.

Runtime matching uses bounded instruction contracts, call relationships, native
class/layout checks and serialized Dart metadata. Fixture names, hashes, debug
symbols and code addresses are host-side evidence. Follow-up builds still
require verification when their compiler or object layout changes.

## Acceptance

- App names and Monet were checked against 13 launcher fixtures. The label
  bridges passed 8,852 emulated cases each for source and linked payload.
  Shipping Dock/Search material and refresh passed across 13 fixtures.
  The Dock graph was also checked against 11 independent symbol/call oracles.
- On Phone, app-name settings were acknowledged for system, two-line and
  marquee. On b0743, sampled frames show Apple Music and Arcticons Material
  You scrolling while short names stay static.
- Regenerated Arcticons Material You (49,252 mappings) on b0742 and repeated
  native application and pixel checks on b0743. Blue and pink palettes passed
  in both light and dark mode. The test also checked icons with Monet disabled
  and restored the previous theme and settings.
- Debug b0743 (`112000743`) passed build and lint. Its installed APK identity,
  native mappings, and launcher initialization were verified independently
  of Java hot reload. Java recorded ten successful handoffs and one failure:
  the system reclaimed `com.android.settings:provider` during its reload.
  `system_server` and SystemUI identities stayed constant. The launcher
  retained the same 71 layout records as before the upgrade.
- The retired host-only material experiment suite still fails its complete
  optional-source check on 9261, including at the starting commit.
  `run_material_tests.py --shipping-only` verifies the shipping capabilities
  and the separate folder refresh proof.

## Phone desktop follow-up on b0743

| Path | Outcome |
| --- | --- |
| Mounted icon highlights | Toggled Preserve system materials off and back on with the same theme and Home PID. Both changes received a native acknowledgement and a package refresh, with 101 callbacks in each generation. Phone, Themes and Gallery icon crops changed and returned to the original pixels within a 1% color tolerance. The linked highlight template passed 3,146 structural checks and 18,720 patched ARM64 executions across 13 launcher fixtures. |
| Dock and Search | Dock material, independent Search material, glass on/off and five corner presets received acknowledgements. Dock padding and vertical position changed visibly (18 to 27 dp using the Pad geometry preset on Phone, and 0 to +19 dp). The system folder opened with its native materials and icons. |
| Recent tasks | The original archive provided identical day and night artwork for Phone, Security and Themes. Applying a separate Arcticons archive with distinct artwork demonstrated a regression: Phone and Security changed correctly, but the Theme Store task still showed the light icon. Bilibili and KeiHome also followed system appearance with the original archive. |
| Starting windows | Captured launch and return motion. SystemUI reported applied-theme icon insertion for Settings, Phone, Security and Themes, including the Theme Store alias. Splash drawable tests exercised the background policy. A dedicated on-device comparison with a neutral overlay that changes brightness remains unverified. |
| Status bar | The desktop tools page reported a dark wallpaper area, a connected wallpaper subscription and a light-icon request. Manual refresh returned a restored-configuration receipt, followed by a readable status bar on Home. |
| Gesture bar | The settings page read the current native receipt and opened the app picker. The saved general color compatibility produced a solid strip on the active Bilibili skin. Its separate artwork switch was off for that capture. |

### Theme Store task fix on b0745

On 0308, the Theme Store launcher component is both the base intent and
`realActivity` of the task while a Themes activity supplies the live window.
Rejecting all mixed-package roots excluded that card from the shared icon
pipeline. The resolver now accepts this exact registered launcher identity
for a plain launcher intent with matching framework roots and component
artwork. This keeps ordinary task ownership, document-task and user checks in
place, with package fallback disabled for redirected entries. No package
name or launcher version is used in the decision.

The same Arcticons archive now visibly changes all three cards between light
and dark, including the Theme Store entry. The mounted-highlight comparison
also passed again on b0745. Debug build, Lint and 14 focused unit tests
passed. All three packaged native libraries matched b0743
byte-for-byte. The in-place b0745 update produced 11 successful observed
API 102 reloads and zero failed or unknown receipts. Process identities were
preserved and the native runtime continued in the same Home process.

The desktop UI suite on b0743 passed 21 tests, including compact and wide layout
fixtures. The navigation-backdrop and Recents/splash device groups passed 17
tests with two conditional skips (cross-UID OEM rule delivery and the
running-task type fixture). The wide fixture exercises app navigation;
live Pad and physical-device acceptance remain pending.

After the checks, the same 71 layout rows, icon component hash, custom-icon
preferences, eight Monet settings and night mode matched the baseline.
The gesture-backdrop preference fingerprint also returned to its original
value. Home, SystemUI and `system_server` kept their process identities.

Local evidence: `.artifacts/research/launcher-0308-20261119/`.
Follow-up captures, recordings, test logs and the restoration receipt are in
`desktop-acceptance/` under that run.

## Physical Xiaomi 17 Pro icon-pack acceptance

On `OS4.0.6.0.XBLCNXM`, the launcher was updated in place from 9259 to
`801030308`. Native two-tone initialization was verified with KeiHome b0745.
The 20-source library matrix returned `IMPORTED` for every source and applied
every resulting archive on the foreground launcher. It covered five APKs,
three imported MTZ/ZIP packs, ten built-in ColorOS variants, and the existing
Apple and Perfect generated packs. The largest pack had 49,252 mappings.

KeiHome b0750 selects the installed system `OPEN_DOCUMENT` handler explicitly.
On this firmware the implicit picker could be redirected to Safe Access, where
the activity result was lost. The phone's separate 47-mapping iOS ZIP was
selected through the system picker, imported with an `IMPORTED` receipt, and
applied through Themes. Its active archive matched the imported archive and
all four pinned custom resources matched their saved originals. The Perfect
Icon Completion MTZ was covered by the library matrix. Its neighboring
KernelSU ZIP is outside the icon-pack inventory.

All 13 Monet-capable sources rendered both published palettes on the launcher
with both role colors visible in the camera icon. This check publishes the
palette directly. The 13,387-mapping HyperOS Monet MTZ also passed the separate
API 102 request-and-acknowledgement test, with blue and pink seeds in both
light and dark modes and unchanged artwork when palette application was
disabled. That test compares encoded screenshot channels to avoid a color
space conversion on the phone's Display P3 captures.

Evidence is in `.artifacts/research/physical-0308-iconpacks-20261119/`.
`matrix.json` and `pixel-application.json` record the library results,
`ios-application.json` records the extra ZIP, and `p3-physical-monet/` records
the request test. The original icon theme, Monet settings, night mode and
custom icon preferences were restored. The launcher remains on 0308. This
run covers the physical phone's icon-pack and Monet flows.
