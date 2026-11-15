# Large icon-pack import and application

## Scope

This change covers the icon packs already present in HyperOS Phone AVD, including
installed APKs, retained ZIP/MTZ imports, generated Apple/Perfect packs, and every
ColorOS style with ART+ both enabled and disabled. Importing into the native
Themes library and applying to the active system icons are separate checks.
Only HyperOS Phone is used. ColorOS AVD and physical devices are outside this
acceptance run.

## Problems and changes

- The supplied LSPosed log identifies `Ambiguous published icon path`. The
  13,387-mapping MTZ contains legal case-distinct package names. Publication now
  checks exact ZIP paths, including file/directory collisions, without folding
  their case or imposing a second 8,192-image limit.
- Mask publication copies unchanged compressed entries and transforms one image
  at a time. Repeated, identical MAML proofs and mask stencils are reused.
  Archive, image, expansion, cancellation, and path limits remain enforced.
- ColorOS generation reuses bounded app/activity/resource lookups for one
  generation. It reports phases and completed/total work, offers cancellation,
  and releases the preparation state on completion or failure. It does not keep
  app bitmaps in a long-lived cache.
- ColorOS clock/calendar validation distinguishes rendered fallback snapshots
  from live backgrounds. A clock snapshot containing its hands must not be used
  as proof of the bare background. Canonical fancy-only palette modules use
  their own day/night images; a nonexistent ordinary fallback is not required.
  Partial fallbacks, missing images, extra resources, and unknown programs still
  fail validation.
- Local native modules admit closed, image-only day/night crossfades and the
  corresponding bounded 31-day calendar form. Unknown executable MAML remains
  rejected. The known `UseDynamicIcon` value `fales` is normalized to `false`;
  explicit authored settings remain authoritative.
- Local MIUI MTZ imports register the same constrained approval as local native
  icon ZIPs. Preview/import success alone is not reported as proof of application.
- Native Themes can consume the imported parent MTZ, leaving its registered
  parent file empty while retaining the actual icon component. Approval accepts
  this specific state only with the registered parent/child association, native
  identities, no store IDs, and fresh child SHA-1/SHA-256 verification. Missing,
  changed, nonempty invalid parents and mutated child contents fail closed.
- Provider input respects asset offsets and declared lengths. Regular file
  windows use positional reads, independently of a shared descriptor cursor;
  pipes remain sequential and descriptor ownership is explicit.

## Acceptance evidence

The local evidence root is
`.artifacts/research/large-zip-import-20261115/`.
The final `ACCEPTANCE.md` and machine-readable receipts record exact source,
installed artifact, complete inventory, preparation/import timings, application
hashes, screenshots, and restoration of the original active icons.

The import matrix calls production preparation and import code with automatic
application disabled. Its opt-in instrumentation host stays in the foreground
to avoid HyperOS freezing an otherwise headless test process. This harness
detail is not a product-side deadlock fix.

Application acceptance opens each native Themes detail page and presses its real
Apply control. It waits until the active `/data/system/theme/icons` source marker
and SHA-256 match that pack's imported native icon component, then captures Home.
Transient reads while native Themes replaces the active ZIP are retried.

The change does not modify the validated launcher-9258 native payload, grant
rights to store themes, or require terminating the launcher or rebooting a guest.
Preparation timings are measurements of this AVD and input set, not a claimed
percentage improvement on physical devices.
