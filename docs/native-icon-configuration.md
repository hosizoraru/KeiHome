# Native icon configuration completion

Converted icon archives must carry the framework contracts used for icons they do
not supply. A missing `transform_config.xml` is not equivalent to the stock theme:
on the inspected HyperOS AVD, the stock archive supplies `SupportLayerIcon=true`,
`UseDynamicIcon=true`, and a full-viewport `ConfigIconMask`.

Without those contracts, `miui.content.res.IconCustomizer` takes its legacy
composition route. Its fallback mask is inset, `mIsFullMask` becomes false, and
`composeIconWithTransform` may put the system `icon_pattern.png` behind partially
transparent artwork. This can brighten the antialiased perimeter of dark icons.
Legacy/default bitmap icons also miss the normal adaptive shape route. The
inspected DEFAULT exports did not establish a newly painted white contour in
`SystemIconFallbackRenderer`; the OEM adaptive stroke is translucent black.
Authored light contours are preserved.

The analysis uses the retained AVD `miui-framework.jar`, its framework methods,
and the before/stock archives under
`.artifacts/research/icon-mask-edges-20261111/`. It does not add version, binary
hash, resource-address or per-application allowlists to the implementation.

## Policy and author intent

Icon packs > More settings > Generation and application includes
**Auto-complete icon configuration**, enabled by default. Shared final handoff
preparation applies the policy to converted APK
packs, built-in/Apple output, and preserved native MTZ/ZIP modules:

- Missing configuration gets the system mask and the stock layer/dynamic flags.
- Partial standard configuration gets only absent contracts. Explicit values,
  including `false`, are retained.
- Complete standard configuration stays byte-identical.
- Legacy global mask/pattern/border/background assets, including night and drawable
  qualifiers, keep their existing composition route. Per-app MAML assets are not
  mistaken for global plates.
- Authored filters, transformation points, unknown config fields, root attributes,
  ambiguous, oversized or malformed XML stay untouched. Full-mask mode can bypass
  the legacy filter/matrix compositor, so these are not safe completion targets.
- Turning the option off stops automatic completion; it does not remove authored
  configuration. Update imported icons to apply either policy. Native archives
  that already contain configuration retain it on subsequent imports.

There is no all-app hook or new scope. Applications outside module scopes receive
the completed native theme through their existing framework icon loading path.
Already retained drawables still depend on the application's normal theme/cache
refresh; this policy does not walk arbitrary view trees.

## Integrity, updates and performance

Generation manifest v3 records the completion policy. v1/v2 remain readable and
trigger one regeneration. Switching policy marks managed imports for an update;
a matching v3 generation clears the notice. Existing source association remains
intact.

Converted import validation permits only the fixed canonical stock XML contract,
with bounded size and known fields. It does not open a path for arbitrary XML
programs in converted payloads. Preserved native archives retain their separate
existing validation. XML completion uses the bounded parser before DOM changes,
and existing complete/unsupported programs are not reserialized.

Configuration is inspected once on the IO handoff path, not while binding or
drawing each icon. There is no pixel-color removal, image-content heuristic,
new background polling, dependency or native payload change.

## Focused validation

JVM groups cover configuration preservation/security, generation migration,
update notices, converted import validation and preference persistence.
`NativeIconShapeCompletionDeviceTest` covers converted paired archives, the
opt-out, native author settings, partial contracts, global legacy plates and
unchanged MAML resources. `SystemIconFallbackEdgeTest` distinguishes synthetic
light pixels from authored light contours. The read-only
`NativeIconStyleProbeDeviceTest` captures representative framework-loaded images
before/after an actual update without using a scoped host adapter.

Exact counts, source/artifact identities, runtime captures and acceptance limits
are recorded in the task's research directory. Phone AVD evidence is separate
from physical-device acceptance.

The final focused validation passed 85 JVM tests and six device cases. The device
cases cover author/legacy preservation, enabled/disabled completion, fallback
alpha edges, the More settings switch and the onboarding shortcut. Run the
`icon-shape`, `icon-shape-device` and `ui-pack-settings` check groups separately.

On the Phone AVD, ColorOS Classic ART+ was regenerated and reapplied through the
normal UI. Fresh framework loads for seven representative apps were captured
before and after. Glasmio's default adaptive icon changed from an opaque top-left
pixel to a transparent corner and follows the system mask. The visible bright
outline on the Cross-device services icon disappeared. Neither archive supplies
dedicated entries for those two apps; this exercises default framework loading
without a new package-specific hook.

The active archive now includes the full mask and the stock layer/dynamic flags,
with generation schema 3 and `shape complete`. Existing imported packs require
one update and reapplication. Changing the preference affects generation; it
does not rewrite an already-applied archive or remove author-supplied settings.
