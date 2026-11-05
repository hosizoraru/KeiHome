# Desktop classic-clock glass: deferred research

Status: **withdrawn, not implemented**. Checkpoint: 2026-11-06.

The experiment targeted only the default classic desktop clock advertised as
synchronizing with the lock screen. It did not establish a reliable replacement
with the lock screen's genuine background-dependent glass on the digits.
The setting, UI, transport, native hooks, experimental dependencies, providers,
overlay permission and feature tests have been removed from the application.
Existing Dock, search, folder and icon features are outside this withdrawal.

## Findings and limits

| Route | Evidence | Remaining problem / decision |
| --- | --- | --- |
| MAML augmentation and rendered PNG foreground | The original widget archive and native text bindings were examined; derivatives could be substituted without overwriting the original archive. | A foreground image cannot reproduce live nonlinear backdrop sampling and contour refraction. Image-cache lifetime and style synchronization also needed more proof. Retired. |
| OEM `TimeView` in a real Android hardware window | The actual native view produced visible glass, with 17,402 ink pixels changing between two backgrounds and 17,075 showing a nonlinear response. | This proves rendering within that window only. It is not acceptance of a clock on the launcher. |
| Android overlay above the launcher | Attachment and screenshot capture passed. With `passWindowBlur=false` the glyph was effectively invisible; with it enabled the result was dark and thin. | Correct live sampling of the Rust/Flutter launcher's backdrop was not established. The test's success means attachment/capture, not visual parity. Retired. |
| Direct native scene drawing | A synchronous Rust Text -> Dart paragraph -> native DisplayList recording chain was identified. A geometry/lifetime observation route was researched. | No complete hook, genuine glyph-glass draw or lifecycle/performance acceptance. Deferred. |

The same-window result used `passWindowBlur=false`. Initial test startup timing
failed before a retry passed; later startup synchronization changes were not
accepted as a complete desktop feature. The overlay diagnostic used window
alpha 0.79. A source-only change to alpha 1 was never built or accepted.
A proposed SystemUI window owner was also never integrated or tested there.

## Why switching to native drawing did not close the feature

The examined launcher 8593 is Rust/Flutter without DEX or a live ART/JAR host.
An Android `TimeView` therefore cannot simply be inserted into its widget tree.
The actual default classic clock uses Rust **Text** nodes; observing only
DateTime nodes misses its main digits.

The native text callback is synchronous and isolate-local. Primitive thread
local scope can identify the relevant recording work without retaining Dart or
Rust references. DisplayList pointer plus unique ID, nested list propagation and
`DisplayListLayer::Preroll` form a candidate route for following transforms and
clips, including retained layers. This route was researched, not device-proven.
Pointer reuse, unique-ID wrap, widget identity and lifetime still need guards.

The engine supports real path clipping around a glass draw, but clipping a
rectangle's glass is not equivalent to glass refracting along glyph contours.
The examined OEM SDF texture uses signed RGBA16F distance/gradient data. Its
consumer folds sampling into a rounded rectangle's corner domain and reflects
normals by quadrant. Substituting a whole glyph texture alone cannot represent
asymmetric glyphs and holes under that sampling contract.

A future native implementation needs a correctly bound glyph distance/normal
field and compatible UV mapping, or another independently proven drawing route.
Ordinary path paint does not automatically enable the OEM glass image filter.
Demangled signatures alone are insufficient for calling optimized internal ABI
entries. The retained engine provides mini debug symbols, not full type DWARF.

## Evidence retained outside production sources

Research root: `.artifacts/research/desktop-clock-glass-20261105/`.
These local ignored files are not shipped in the APK:

- `native-live-window/quality.json`, `gray32.png`, `gray224.png` and
  `native-live-retry-draw.log`: same-window native rendering proof.
- `native-live-overlay/` and `native-overlay-device-test.log`: unaccepted
  cross-window diagnostic.
- `material-pipeline/native-geometry-checkpoint-20261106.md`,
  `native-text-tls-chain-20261106.md` and `engine/ENGINE-FINDINGS.md`:
  native identity, geometry, rendering and ABI findings.
- `withdrawal-source/SDF-FINDINGS.md` and `sdf-contract.log`: glyph/SDF sampling
  limits, including an asymmetric-glyph counterexample.
- `retired-maml-route/`: earlier MAML/archive prototype.
- `withdrawal-source/clock-experiment.patch`, `archive.json`, `tracked/` and
  `untracked/`: final unfinished experiment snapshot before removal.
- `withdrawal-deploy/` and `withdrawal-acceptance.json`: final restoration
  receipts, once generated.

Earlier progress files describe abandoned intermediate states and must not be
read as current implementation or acceptance. Source APKs, original widget
archives, fonts and engine fixtures remain available for future research.

## Conditions for restarting

1. First prove a genuine glyph-glass draw against the live launcher scene, or
   reliable cross-surface sampling. Validate it on both light and dark
   wallpapers before building settings or transport.
2. Establish target identity, source font/style/color, lifetime, transforms,
   clipping and visibility through page slides, edit/drag, widget recreation,
   lock/unlock and app transitions.
3. Preserve original drawing until a complete replacement is ready. Removal,
   expiry, unsupported binaries and partial failure must leave no ghost clock.
4. Use authenticated dynamic contracts; research RVAs and binary hashes are
   evidence identifiers, never production version/address allowlists.
5. Measure allocation, raster/GPU work, redraw rate and memory growth on actual
   accepted visuals. Prove API 102 setting changes without forced restarts.

This checkpoint does not establish that the feature is impossible. It records
why the current attempt was stopped instead of shipping an unreliable effect.
