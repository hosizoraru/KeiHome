# Icon masks and preview parity

Icon packs -> More settings -> Icon mask owns the automatic-completion switch,
custom XML import, restore action and an isolated comparison. The switch remains
on by default. Importing or comparing a mask does not apply a theme.

## Selection and preservation

`IconMaskPolicy` takes one immutable snapshot on a worker. An explicitly imported
custom mask overrides a supported simple native mask configuration. Otherwise
the pack's authored mask wins. A missing simple configuration uses the retained
HyperOS 4 100-unit path, including when the product archive is unavailable.

Legacy bitmap plates, authored filters/matrices, unsupported XML programs and
explicit layer/dynamic flags keep their existing preservation rules. The
completion switch disables additions and custom overrides; it does not remove
the pack's own mask or restore transparency already baked into source images.

`CustomIconMaskStore` uses an app-private atomic record and bounded SAF reads.
Only a direct, unambiguous `IconTransform/Config[name=ConfigIconMask]` path is
imported from `transform_config.xml`; other transformation instructions are not
executed. XML, canonical SVG commands, finite in-viewport geometry and a
nonempty filled region are validated before replacing the previous record.
Invalid files leave the previous mask intact.

## One final compositor

The proposed APK/native archive preview uses the same selected contract as
publication. Full-mask clipping is an alpha intersection, without a white
background, color removal, padding or rescaling. Source density is retained.
Configuration and mask decisions are cached per source and policy, outside the
per-icon draw loop. Import, restore and the master switch invalidate preview
state and generation notices.

Static Perfect Icons dark conversion retains uncropped intermediate images.
The final mask belongs to the preview compositor or the native theme loader;
export must not bake that same edge into a PNG and then apply it a second time.
Static-render cache revision 3 separates older cropped intermediates.
Authored dynamic resources remain native resources.

Current-theme previews read the XML actually published in that theme. Pending
private mask settings are not substituted. Generation manifest schema 6 records
the policy identity, including a canonical custom-path digest. Schemas 1-5
remain readable; changing the effective policy requires regeneration and
reapplication to update the active theme.

The UI displays the compositor's alpha without adding a second rounded
rectangle, neutral backplate or inset. This applies to compact day/night pairs,
the Applications inspection sheet and Perfect previews. A square reference in
mask comparison remains square when that is what its source defines; display
wrappers must not silently apply a different mask.

Style thumbnails observe the preview repository revision, so clearing after a
mask import/reset restarts mounted loads. The applied-completion thumbnail uses
the published-theme repository and theme preview identity, independently of
pending settings. Mask-comparison samples explicitly ignore the user's
preserved-app list so app selection cannot defeat an A/B mask comparison.
Unmodified app artwork independently uses the stock native mask, with the
retained HyperOS 4 path when the native mask is unavailable.

## Comparison

The child page samples up to three real entries from a locally available pack.
It compares the pack's own configuration (including no mask), HyperOS 4 and the
imported custom mask, with a light/dark preview option. Comparison overrides are
ephemeral; they neither change the saved choice nor start a download.
Special legacy programs remain preserved and are identified in the explanation.
Intrinsic transparency in a source image cannot be restored by another mask.

## Dynamic resource boundary

Dynamic resources are checked against native layer configuration, root
`config.xml`, declared layer directories, quiet images, fancy manifests and
their referenced resource closure. Legacy root-quiet and valid fancy-only
variants retain their compatibility paths. Screenshot descriptions inform
format research; they are not permission to copy a packer's implementation or
to reject all historical layouts.

Focused test and device receipts are retained under
`.artifacts/research/icon-mask-20261112/`. Device captures validate the Phone AVD;
they do not establish physical-device acceptance.

## Original application previews and published shapes

APK bitmap artwork needs a final outer shape just like adaptive artwork. The
original preview repository now applies the immutable product default shape on
its workers. When browsing an applied theme, unmapped applications use that
published theme's shape, read through the bounded native-config cache. Shape
identity is part of the original-bitmap cache key; switching themes cannot reuse
a bitmap clipped for a different mask.

The shared preview image uses published shape data for colored artwork as well
as Monet recoloring. Monochrome eligibility is still inspected before clipping:
rounding a full-color bitmap must not turn it into an eligible monochrome mask.
Alpha intersection is reused, without a white matte or repeated alpha
multiplication. The mask comparison page keeps its deliberate unprocessed
reference and is not globally rounded by a UI-only modifier.
