# Perfect Icons: source and implementation boundary

Checkpoint: 2026-11-09.

## Current inputs

The only upstream input to synchronization/generation is the complete
[pzcn resource repository](https://github.com/pzcn/Perfect-Icons-Completion-Project).
The retained checkpoint is commit `fb8b5c9224510fedb653a7dceafdcb364b4e2263`,
archive SHA-256
`8cbbb7a398649902f437eadd90c55e25884bee776f3fadfce825cdedf57577c1`.
It is acquired explicitly and cached; it is not bundled as an executable library.
Source graphics, XML and MAML resources retain their original authors' rights.
The repository snapshot has no top-level LICENSE file. Availability on GitHub
is not treated as a blanket license for the artwork or scripts.

`HyperOs4IconModule` writes destination configuration from native loader facts:

- `IconTransform` / `Config` is the observed native configuration structure.
- The retained system framework reads `SupportLayerIcon` and `UseDynamicIcon`.
- Static native layers are indexed in order, with layer zero as the background.
- `ConfigIconMask` is optional. When available, KeiHome reads it from the
  destination's original product theme with the pre-existing
  `NativeAdaptiveIconMask` reader. It does not embed the shape of a research AVD
  or read a third-party applied theme as the stock shape.
- If that stock shape is unavailable, the writer omits the override. This uses
  the native loader's default handling; it is not a promise that every loader
  path will track every user shape preference live.

The configuration has its own contract revision. That revision and stock shape
are part of derived publication/preview identity, so stale outputs cannot
silently supply an earlier writer's configuration. Source snapshots and
content-addressed graphics remain reusable without another download.

The system destination is HyperOS 4 with the app's existing minimum API 37.
`HyperOS4`, `HyperOS3`, `HyperOSClassic`, etc. in the upstream directory are
artwork choices, not system-version gates. All available artwork choices remain
available.

## Data selection and preserved source resources

The pzcn snapshot supplies `icons/<key>/0.png` and `1.png`, theme overrides under
`themes/<style>/icons/`, and original dynamic trees under
`themes/<style>/layer_animating_icons/`. There are 2,391 complete base layer
pairs at this checkpoint. The files named zero and one are layers, not a
light/dark pair.

KeiHome's explicit selection policy is that a selected style's static variant
replaces the matching base variant; selected-style dynamic trees replace
matching common trees. No default dynamic directory is implicitly merged into
another selected style. This is a product policy, not a claim that another
assembler requires the same order.

Original dynamic config/manifests and resources pass through the bounded native
archive validation pipeline; they are not synthesized from another packer's
templates or installation scripts. Individual dynamic variables have not all
been independently proven against every loader. Resource observation, archive
admission and actual animation acceptance must remain separate claims.

## Historical reference, not a clean-room claim about history

Earlier integration notes and test-fixture documentation cited
[PerfectIconsPacker](https://github.com/YifePlayte/PerfectIconsPacker) format
documentation and an assembler as research references. That project's license
is [GPL-3.0](https://github.com/YifePlayte/PerfectIconsPacker/blob/main/LICENSE).
Those historical references remain recorded in Git and in the app's historical
research acknowledgements. Removing a reference would not settle whether any
earlier implementation was derivative.

The current build/runtime has no dependency on that packer, ships none of its
executable components, and neither invokes nor downloads it. Its documents and
assembler are no longer implementation/test oracles. This checkpoint replaces
the old destination-config writer and its embedded research-device mask with
the independently specified native writer.

For this checkpoint, an isolated evidence worker was prohibited from reading
the packer, its docs, and the previous Perfect formatter/repository/parser.
It examined only retained pzcn source inputs and original native system inputs.
Its functional findings informed the new writer. That is an independent input
boundary for this work, not a certification that the whole project's historical
development was a formal clean-room process, or a new license grant for assets.

## Evidence and verification

The independent observations, exact native paths and hashes are retained in:

- `.artifacts/research/perfect-independent-20261109/independent-evidence.md`
- `.artifacts/research/perfect-independent-20261109/resource-observations.json`
- `.artifacts/research/perfect-independent-20261109/acceptance.json`

Tests cover native configuration serialization, optional shape handling, shape
changes invalidating derived caches without new source downloads, and dynamic
flags reflecting actual selected resources. Device tests admit modules both
with the original stock shape and without a custom shape override.

The associated UI cleanup places generation statistics and changed-option
explanations inside the generated pack card. Statistics still describe the
last generated pack, not settings that have yet to be generated.
