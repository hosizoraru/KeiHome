# Perfect Icons layered MTZ interoperability

Checkpoint: 2026-11-17. The retained reporter MTZ is
`完美图标补全计划-HyperOS4-20261117-041158.mtz`, SHA-256
`218830da64a3b7ae7ff68c8655fe1a6e3b8687525bb245a8c39c5f1cf8d1eb3b`.
It contains 5,318 icon-module paths. Its catalog has 2,443 mappings; these
counts describe different things.

## Import boundaries

`IconPackArchive` accepts both `MIUI-Theme` and `theme` as exact description
roots. XML size, depth, path, DTD, and archive admission limits still apply.
The original native icon component is retained rather than flattened for
system import.

The reporter also exposed a separate publication failure: original flat PNGs
contain ancillary `eXIf` metadata, while the generated Monet-resource contract
intentionally accepts a narrower PNG structure. `ImportedStaticPng` validates
bounded static source PNGs and normalizes only the optional derived resource.
It checks framing and CRC, rejects animation chunks and unknown critical
chunks, supports cancellation, and decodes/re-encodes only when necessary.
Unsupported optional derivatives are omitted; the original source is retained.
The strict generated-resource validator has not been relaxed.

## Static layer geometry

Native `0.png` and `1.png` are background and foreground, not day/night images.
Their textures account for the native adaptive viewport. Drawing both directly
into the final output rectangle made the foreground appear too small.

`NativeLayerViewport` asks `AdaptiveIconDrawable` for its child bounds.
Recognized native layered previews, Perfect fallback rendering, and optional
static-night composition use those bounds. Flat artwork and already flattened
images retain their existing geometry. The final destination mask is still
owned by the caller. Derived cache revisions invalidate old scaled results.

Original numbered layers and dynamic resource trees remain unchanged.
The static-night renderer performs source composition in one pass. Its
antialiased edge rasterization need not be byte-identical to a drawable render;
tests compare foreground bounds, coverage, and bounded channel error.

## Validation

Evidence is retained in
`.artifacts/research/perfect-layered-import-20261117/`, including the reporter,
source review, before/after images, device tests, build logs, and native
import/application gate receipts. The gate records import, application,
Monet behavior, and restoration separately; an imported catalog entry or
Themes details screen alone does not prove application.

The target is HyperOS Phone AVD `emulator-5580`, launcher `801029259`.
This does not establish every upstream MAML program, every pack style, or
physical-device compatibility.

See [source provenance](perfect-icons-provenance.md) for the pinned packer
documentation review and resource-rights boundary.
