# Perfect Icons Completion Project integration

KeiHome offers this source under Available icon packs. Resources come from
[Perfect Icons Completion Project](https://github.com/pzcn/Perfect-Icons-Completion-Project).
The destination is the HyperOS 4 native theme loader. Repository names such as
`HyperOS3` and `HyperOS4` select artwork only, not OS compatibility. The app's
minimum Android API level remains 37; native host contracts still determine
whether the installed launcher is supported.

Current output configuration is owned by `HyperOs4IconModule`. Its stock shape
comes from the destination product theme through `NativeAdaptiveIconMask`, not
one research device's path. Dynamic loading is enabled only when the generated
module actually includes dynamic resources. The contract revision and shape
participate in publication and preview identity; the selected source graph
and content-addressed resources are reused.

See [source and implementation provenance](perfect-icons-provenance.md) for the
independent evidence boundary, prior reference history and resource-rights scope.
Neither upstream installation scripts nor another project's packer are executed.

## System application fallback

After a repository snapshot is downloaded, Icon completion and fallback offers
`Perfect Icons · HyperOS4` for system applications not covered by the selected
pack. The default-off **Extra Perfect Icons styles** switch exposes the other
locally available artwork styles. Collapsing the choices retains an already
selected extra style instead of silently changing the user's configuration.
If resources are removed, the saved choice remains visible with a download
entry; generation requires local resources rather than silently fetching them.

This fallback reads verified source layers or a supplied flat PNG, uses the
destination stock mask, and emits static artwork through the existing exporter.
System frontends retain the existing compatibility descriptors with
`frameRate="0"` and no settings-provider binders; the new source does not add
animation timers or a new runtime refresh mechanism. Native
clock/calendar exclusions and the existing explicit-artwork, official
adaptation and ColorOS precedence remain intact. An unmatched system app can
continue through the default HyperOS fallback.

The source snapshot, bounded two-style plan cache and content-addressed image
cache are reused. No network request or extra full publication is made by the
fallback renderer. Export pins the source hash, rejecting a concurrent source
change instead of mixing snapshots. Manifest versions 5–8 record that hash
alongside the selected style; versions 1–4 remain byte-compatible. Updating the
local repository can therefore invalidate applied fallback resources, while
unchanged sources and previously considered targets do not continually request
another update.

### Device checkpoint

The independent-writer/fallback checkpoint is recorded in
`.artifacts/research/perfect-independent-20261109/acceptance.json`.
On HyperOS Phone AVD 8593, the default and expanded dropdowns, retained extra
selection, source-page jump, local preview and local generation were exercised.
Generation reported 51 static and 5 original dynamic entries; these figures
describe the actual generated archive, not all repository assets.

A deliberately sparse owned test pack was imported and applied with HyperOS4
fallback. The applied module recorded manifest v5, the selected style and full
source hash, with 57 fallback names. The Settings icon was rendered on the
actual desktop; the test pack's explicit original PNG remained byte-identical.
The original ColorOS Classic ART+ theme was then restored: all 4,019 original
PNG entries matched their pre-test hashes. Owned QA imports and native theme
resources were removed. Launcher, SystemUI and system_server identities were
preserved; no native payload activation or physical-device test was performed.

The checkpoint ran 990 JVM tests (984 passed, 6 skipped) and passed 6 AVD instrumentation tests,
including source admission and end-to-end fallback export. These do not claim
acceptance of every upstream dynamic animation or every OEM device.

## Workflow

1. Check the remote explicitly through anonymous Git HTTPS. This reads the `main`
   commit and a shallow, blob-filtered directory index, without image resources.
   Select a style, then explicitly download its dependencies; opening the page
   and changing a style remain local. See [Git synchronization](perfect-git-sync.md).
2. Select a theme. The default choice uses upstream `themes/default`, over
   the common third-party `icons` tree. `common` is shared dynamic data,
   not a selectable style.
3. Generate a local icon pack. Installed applications only and original
   dynamic resources are enabled initially. Turning off the installed-only
   option includes upstream mappings for applications not yet installed.
4. Preview, then use the existing native Themes import/application action.
   Generating or syncing a snapshot does not change the active theme.

Matching uses exact upstream package or activity IDs. No speculative app-name
aliases are created. The selected theme overrides the base static icon pair;
selected-theme dynamic resources override the corresponding common dynamic
directory as a complete unit.

The main page reuses the app's semantic status card, including its large right
status symbol, one count/revision pill per row, selected style and successful-sync
time. The revision pill still copies the complete revision; the rest of the card
opens Source and storage rather than starting a download. Loading, errors and
cooldown details remain visible in a separate card when relevant. The separate Preview page
samples up to 24 icons from the **currently selected** style, even when the
last generated pack used another style. It reads only local resources, resolves
installed application labels, supports search and filtering, and identifies
dynamic quiet snapshots. Its compact summary shows a real local sample on the
right, the current visible/total sample counts and active filter. Filtering lives
in the top-bar menu: all samples, static icons, dynamic snapshots, or independent
dark resources. Search and filtering work together without network access.
Shared day/night artwork is displayed once; an independent night resource is
shown separately. Previewing never publishes
or imports a new pack.

Source attribution, copyable complete commit/checksum, original download time,
snapshot size and derived-cache cleanup are on the Source and storage page.

## Native resources and previews

The [November 17 layered-MTZ interoperability record](perfect-layered-mtz.md)
describes metadata compatibility, original PNG metadata handling, and native
adaptive viewport preservation. These fixes apply to imported packer MTZs as
well as the shared static rendering paths.

- `res/drawable-*/<id>/0.png` and `1.png` are background and foreground.
  They are composed in that order for previews. They are not day/night images.
- `layer_animating_icons/<id>/config.xml` declares native dynamic layers.
  Original manifests, quiet images, hand images, animations, and date rules
  are retained. Previews use the supplied quiet snapshot, or compose quiet
  layer snapshots when needed; a preview is not a live MAML runtime.
- Actual independent night resources are recognized separately. A static
  layered pair alone never creates a false dark-icon capability.
- Native fallback coverage protects layered source artwork from being
  replaced by a fallback. Applied-theme coverage requires both static images.
- Standalone native ZIPs and MTZ icon components can contain these structures.
  Importing a Magisk/KernelSU module ZIP is not the workflow.

The local-native validator checks bounded images, local visual XML and the
exact native weather program schemas. With original dynamic icons enabled,
the generator keeps the recognized `com.miui.weather2` resources so Home can
draw the live temperature. Validation checks the weather query, refresh
schedule, glyph layout, and all required textures. An unrecognized
provider-backed program keeps its available static fallback. Regenerate and
apply a pack made under an earlier contract to restore its live temperature.

## Download and storage behavior

Production acquisition uses JGit, with no GitHub REST API, account, token,
external Git executable, checkout, installer scripts or source ZIP repacking.
A remote check reads the advertised branch and filtered metadata. Explicit sync
fetches only missing Git blob identities for the selected styles' dependency
graph. Previously downloaded styles stay available offline. Identical objects
are shared between styles and revisions; upstream deletions and renames are
reflected by replacing the index rather than appending filenames.

The full Git commit, actual upstream commit time and subject are separate from
local check and successful-sync times. Checking updates never records a resource
sync as successful. The short revision can be copied in full. Source identity is
a SHA-256 of the selected resource graph and acquired style set, so a commit that
does not change those resources need not invalidate generated artwork.

A verified last-good index remains published through network, validation and
cancellation failures. Local generation and previews open bounded Git objects,
not a full archive. Extracted/rendered previews remain disposable caches. Repair
uses a new store generation and publishes its index only after acquisition is
complete. See [the storage and transport contract](perfect-git-sync.md).

Source and storage offers two distinct actions with MIUIX OS4 confirmation:
clear derived caches, or delete downloaded source resources and their caches.
Both preserve generated ZIPs and imported/applied packs. Source deletion also
resets local source/check/sync status. User options remain available for the next
download. No action requires clearing the entire application's data.

Older ZIP snapshots remain readable for bounded fixtures and internal builds;
production sync no longer contacts codeload. Historical full-archive receipts
below describe the former transport, not the current network cost. In
particular the retained `fb8b5c9` archive was 594,366,831 bytes; that is not an
estimate for any selected style's Git transfer.

No resource artwork is bundled into the shipped APK. The upstream repository
and authors retain their resource rights.

## Acceptance record

Source and device evidence for this checkpoint is kept in
`.artifacts/research/perfect-icons-20261108/`. The acceptance receipt records
the exact upstream revision, signed APK, automated checks, source-file
preservation, AVD observations, and any remaining limits. Source compatibility
is not a claim of physical-device or all-style acceptance.

The full upstream snapshot has been validated on `emulator-5580`, including
local generation, actual decoded samples, cached regeneration, derived-cache
repair without HTTP and an app-UID conditional request returning 304. Normal
API 102 Java updates preserve Launcher, SystemUI and system_server identities.

Ordinary tests use bounded synthetic/source XML fixtures. The explicit
`PerfectSnapshotDeviceTest` accepts `perfectSourcePath`, `perfectEtag`, and
optional `perfectOnline=true` instrumentation arguments for the complete,
previously downloaded real archive. It does not silently download 567 MiB
during routine suites. Snapshot transfer, extraction and preview counters in
the receipt are distinct from installed-app and mapping counts.

For the 1.14.0 Hyper and HyperOSClassic packaging regression, pass the retained
snapshot as `perfectHyperSourcePath` to
`PerfectSnapshotDeviceTest.retainedHyperSnapshotImportsAndPackagesOriginalAnimationsOffline`.
The test enables dynamic and additional dark artwork, imports each generated
ZIP into an isolated library, and prepares the final MTZ. It verifies both
calendar manifests and compares the native vector JSON against the source.
Set `perfectHyperAll=true` to include the full static set. Keep the fixture in
the app's files directory so it stays available during Android cache trimming.
The local run records are under
`.artifacts/research/perfect-hyper-native-20261124/`.

With KeiHome b0738 on the HyperOS Phone AVD, the retained `fb8b5c9` snapshot
produced a Hyper ZIP of 837,265,373 bytes with 2,428 static icons and a
HyperOSClassic ZIP of 842,817,152 bytes with 2,434 static icons. Both passed
local import and final MTZ validation with 31 dynamic icons, 13 additional
dark adaptations, and 12 byte-identical vector files per style. The weather
group declares a fancy-only slot 2 without a corresponding `layer2`. The
retained source was used as a compatibility fixture for the follow-up repair
below. This check ends at the completed theme handoff.

### Hyper source compatibility follow-up, 2026-11-24

The retained Hyper and HyperOSClassic source modules were rescanned after the
1.14.0 import report. The scan covered 36 real ZIPs, including both styles,
the full dynamic-original archive, and the original per-package layer modules.
All 36 passed the current local native admission path. The receipts are in
`.artifacts/research/perfect-hyper-native-20261124/current-scan-results.txt`.

Two source conventions were responsible for the remaining false rejections.
Hyper weather keeps `layer_only_fancy2` and the `2/fancy` resource directory
while omitting `layer2` from `config.xml`. The bounded config reader now
recovers that slot only when the matching numeric resource directory exists.
HyperOSClassic weather uses the fixed `actualWeatherData/4` provider schema,
while Hyper weather uses the two-provider `actualWeatherData/1` and
`weather` schema. Both are now recognized by the existing weather-only
structural validator. The resource closure, provider names, refresh behavior,
temperature expression, image set, and XML bounds remain checked. No general
provider or external-script admission was added.

The change increments the derived dynamic revision to
`perfect-dynamic-night-10`, so already generated packs are regenerated when
the dynamic dark-artwork option is enabled. The complete import, generator,
MTZ preparation, and native admission checks remain separate. This scan did
not claim Theme application or visual animation acceptance on a physical
device.

The 2026-11-09 UI acceptance checkpoint is recorded separately in
`.artifacts/research/perfect-preview-20261109/acceptance.json`, including the
exact signed build, AVD screenshots, menu/search behavior, font-scale restoration
and process continuity. This layout change does not modify the source downloader,
conversion pipeline, generated theme or native Hook payload.

## Explicit source repair

The Source and storage action repairs the selected and previously downloaded
styles. An OS4 confirmation explains the resource download and retention of
usable old resources on failure. Cancel performs no network work. Repair uses
an isolated store generation; it does not apply a theme. Routine updates reuse
verified existing objects instead of selecting this repair path.


## Layer rule checkpoint (2026-11-09)

Static background/foreground pairs remain native layers; `0.png` and `1.png` are not light/dark variants. Configured native layer resources are selected and validated independently from unconfigured resource folders. A valid configured theme group overrides its common counterpart as a complete group. Generation validates referenced local assets and preserves original native programs rather than manufacturing MAML for ordinary static artwork.

Static and active-fancy classifications are exclusive for statistics even when both source representations are preserved in the output. A quiet-only configured group remains a static icon, while the native configured-directory admission flag stays enabled for it. Incomplete configured groups retain an available static alternative; the result exposes skipped-group counts. Changing layer rules invalidates preview/publication identity without downloading the source snapshot again, and older saved generation results show a regeneration reminder.

The separate investigation of ThemeStore's application protocol is in [theme-apply-without-navigation.md](theme-apply-without-navigation.md). No alternative no-navigation apply mechanism is enabled by that investigation.

The retained `fb8b5c9224510fedb653a7dceafdcb364b4e2263` snapshot has 22 artwork styles. All 20 HyperOS4 configured groups pass source dependency validation. Its exclusive counts are 2,445 static IDs and 20 configured dynamic IDs; the static alternatives for 19 of those dynamic IDs remain in the archive. These are resource IDs, not installed-app counts or a promise that every configured icon continuously animates. Other styles contain 18 incomplete groups in total: Hyper/HyperOSClassic have a flag for an undeclared weather layer, lrone lacks a declared weather layer, and luck7 lacks 15 referenced image assets. Available static alternatives are retained for those groups.

Local auto-apply admission remains a separate, stricter execution boundary. A host test constructs native modules from each of the 20 original HyperOS4 groups and exercises the production admission validator. Clock, calendar and seven other groups pass; 11 complex or noncanonical groups remain outside that automatic admission policy. Source closure success does not authorize arbitrary MAML scripts, external data sources or commands. Such a generated archive can still require the existing native Themes workflow; this checkpoint does not broaden the execution whitelist to obtain a passing statistic.

Legacy `use_fancy_when_static_layerN` and `use_quiet_when_anim_layerN` boolean fields are preserved and bounded against declared layers. Their names are not interpreted as exclusive quiet/fancy modes. Only the existing explicit `layer_only_quietN` / `layer_only_fancyN` rules provide that exclusivity.

## Optional icon light/dark adaptation

The default remains the original native layers, animation and existing dark behavior. The separate, default-off **Icon light/dark adaptation** option derives dark artwork from the cached snapshot for eligible static icons and, within the dynamic scope below, bounded raster-only dynamic layer groups. The historical static checkpoint below does not establish dynamic acceptance; see the separate dynamic checkpoint.

- A static background/foreground pair is composited at 512px for the day image. For night, the original background alpha shapes a vertical `#383838` → `#151515` gradient; the original foreground is composited above it, then the complete RGB image is multiplied by `#D6D6D6`. Alpha is retained. This replaces background color rather than placing an invisible gradient behind an opaque original background.
- Flattened artwork and artwork baked into a foreground can contain their own opaque plate. A bounded, edge-connected plate detector replaces only a sufficiently supported background region. Small dark glyphs are lifted only when isolated and lacking bright/color detail. Photographic or ambiguous artwork retains conservative dimming. Alpha is retained in all cases.
- Both static images use the authenticated native system mask when available. Native dynamic programs are not flattened into replacement static icons by this option.

**Dynamic scope:** Bounded groups with two to four layers can retain their existing raster-backed native programs, including supported literal Text, date-frame selectors and canonical clock functions. Publication adds live day/night texture selectors. An existing plain background program retains its viewport; a quiet-only background gets a zero-requested-frame-rate program. Original layer order, foreground names, animation timing and events remain. Root quiet composites are rendered as complete icons, separately from semantic background textures. A derived night tree also serves quiet previews and readers which select that tree.

Weather has a separate structural contract shared by generation and native admission: the recognized original program keeps its fixed provider, refresh conditions and ImageChars temperature glyphs. Its canonical derivative adds only a dark visibility branch and a complete namespaced texture closure. This does not admit arbitrary binders or external commands. Other complex Lottie/procedural programs, authored dark-mode programs, unproven filename expressions, layered masks, path collisions and unsafe XML remain outside automatic derivation. A failed conversion preserves the complete original group.

When the original foreground omits its updater or leaves it blank, the selector explicitly retains `DateTime.Minute` before appending `DarkMode`. This preserves the default documented in [Xiaomi's MAML grammar](https://zhuti.designer.xiaomi.com/docs/grammar/), rather than silently replacing it with only `DarkMode`. An explicit `none` remains free of a time updater. This distinction is necessary for the unmodified upstream HyperOS4 Music program; selector admission and real-source tests cover it. Changes to this contract invalidate derived publications through the renderer revision while retaining the downloaded snapshot.

Native local application has a separate admission step after generation. Its layer validator accepts only a literal local `src` paired with the exact `ifelse(#__darkmode,'keihome_night/<src>','<src>')` selector, a declared `DarkMode` updater and a complete validated texture pair. Date-frame selectors additionally retain the bounded original `srcid` frame family; Weather uses the separate canonical contract above. Other expressions, external URIs, missing files, mutable appearance state and arbitrary updater combinations remain rejected. This gate must be tested on the complete emitted archive, not just on individual converted textures.

The 1.14.0 admission fix preserves the original fractional weekday font size and
the bundled localized weekday expression in both calendar variants. It also
supports the clock's minute trigger, simple Folme callbacks and local hand
geometry. Vector admission validates inline precompositions and alpha track
mattes with cycle, depth, node and surface budgets. Referenced vector files and
an icon's same-name vector export beside a Folme manifest pass the same checks
and keep their source bytes. Errors name the failing resource.

The current derivation keys are `perfect-static-night-9` and `perfect-dynamic-night-7`. Older generated publications need regeneration and application; downloaded Git objects remain reusable. A generated pack's **Dark layered** count describes dynamic groups only, not total static dark coverage. The retained HyperOS4 audit and current acceptance evidence are under `.artifacts/research/perfect-night-statusbar-20261113/`.

The default-off fixed-dark mode is an explicit performance tradeoff for
ordinary launcher entries. It removes the ordinary MAML descriptor from the
published archive and writes the selected dark raster at the native fallback
path. It does not flatten authored dynamic programs or Monet layers. Use it
when launcher transition cost matters more than automatic light-mode artwork.
The AVD acceptance and archive comparison are recorded in
`docs/maml-launcher-performance.md`.

The [November 14 wordmark refinement](brand-dark-wordmarks-20261114.md) admits
multiple substantial sparse ink shapes in a shared row. It preserves alpha and
can include complete warm stems and underlines touching a colored illustration,
while requiring isolated neutral-core anchors. Colored illustration details and
ambiguous ink retain conservative dimming.

When system shape completion is enabled, the final published archive also clips
recognized complete quiet images to its actual mask. This covers native-preserving
handoffs and folded notification miniatures that bypass the ordinary bitmap
bridge. Dynamic programs and their individual resource layers remain intact;
see [the composite-mask investigation](published-icon-composite-masks-20261114.md).

An authored gradient must be treated as a complete surface: accepting only the
near-median portion leaves colored bands in generated night textures. The
classifier compares a smooth edge-derived surface with the interior and retains
bounded, connected coverage. Smooth neutral projections are considered separately
from sharp glyph boundaries and enclosed white brand marks.

When a flat icon and two separated layers come from the same theme directory, a
bounded pixel proof can use their original alpha compositing for night. The
centered crop must reproduce every source flat pixel, allowing at most one alpha
code and two RGB codes for compositing rounding; sampled or differing artwork
cannot pass. Day rendering stays on the original flat path. The separated
background must itself qualify as a complete plate before the foreground is
composited over its dark replacement. Both output modes retain the original flat
alpha coverage exactly. All source digests and the proof policy
participate in the cached render identity. This proof runs during generation,
not in notification or status-bar callbacks.

Mixed foreground artwork needs component-level contrast checks: a colored
calculator key must not prevent isolated dark arithmetic stencils from being
recognized. Only bounded, thin, nearly uniform neutral components over the
derived dark plate qualify for lifting. Colored artwork, filled decorative
shapes, connected shadows and uncertain components retain their source pixels.
Component ownership includes the antialiased fringe, while source alpha remains
unchanged. This analysis runs on the generation worker and shares the existing
revision-keyed cache; it adds no pixel scanning to system UI callbacks.

The retained Notes fixture also exercises the equivalence path: its ruled lines
share the original background's gold color, so a flood fill of the flat image
can erase segments and expose jagged remnants. Reconstructing the proven
foreground over the derived plate preserves those lines and the translucent
pencil glow. Downloaded artwork is reused; an already applied older generated
pack still requires regeneration and application to receive the corrected PNGs.

The retained source weather programs have a separate exact structural admission,
shared by generation and local validation. The Classic variant keeps its sole
temperature query, ten-minute refresh condition, delayed refresh command and
complete 13-image glyph closure. The Hyper variant keeps its two fixed provider
queries, temperature selection expressions and refresh commands with the same
13-image closure. Their paired dark forms preserve the selected provider
contract and declare the complete local day/night image closure. Extra binders,
queries, attributes, commands, text or faster refresh timing are rejected.
This supports the recognized weather artwork without enabling general-purpose
provider binders.

Source image position/alpha keyframes retain their original timing. Admission limits these tracks to local images, literal bounded values, ordered times within 60 seconds, at most 64 items per track, 32 tracks and 256 items per program. Only the two-integer `play(start,end)` command is accepted for a resolved image with keyframes; additional loop parameters, expression-driven playback, duplicate tracks and external actions remain rejected.

Original layers remain in the archive. Eligible static IDs receive the existing canonical light/dark container with `frameRate="0"` and the native `__darkmode` variable. The module adds no ContentProvider binder, palette query or polling loop. Native renderers can still schedule frames, including their own bootstrap and animation work: zero requested frame rate is not a zero-cost guarantee. The additional containers can increase loading and transition costs. This is an opt-in appearance tradeoff. Source dynamic counts continue to describe original dynamic groups, not the number of generated paired-image containers.

Rendered static pairs are keyed by source-image hashes, native mask and renderer revision. They are checked before reuse, published atomically, rebuilt locally after corruption, included in cache accounting/clearing, and bounded by a 256MiB LRU budget. A changed source icon invalidates only affected pairs. Preview and publication identities include the enabled renderer revision; disabling the option retains the original publication identity. Generated ZIP output is bounded to 1GiB; preview retains its smaller limit. Changing this option requires generation and application again.

**Dynamic cache:** Texture entries use source SHA-256, background/foreground role and the adaptation revision. They participate in existing cache accounting and clearing, are verified before reuse, and rebuild locally after corruption. Preview and publication use the same writer and revision. Rendering failures and selector failures preserve the whole original group. No automatic source download or theme reapplication runs when system appearance changes.

Complete native publications include the standard
`nightmode/res/drawable-xxhdpi/<name>.png` fallback alongside each generated
static day/night selector. The native admission boundary verifies that fallback
against the selector textures. Static renderer revision 2 invalidates older
publications that omitted it; the contract records both the static revision and
the enabled dynamic revision, including when original dynamic resources are OFF.
Original `android` framework static pairs are admitted through the same bounded
identifier and image checks as other packages; a dot in the package name is not
a safety requirement.

### Dynamic-device checkpoint, 2026-11-10

Diagnostic build `105000497` generated the complete installed-only HyperOS4
publication from the retained snapshot without downloading it: 46 static groups,
5 original dynamic groups, and 1 admitted derived dark dynamic group. A second
generation reused 175 local resources and produced the same file and SHA-256
`a54fe25b8412e8e6712b3360b6bee3d6aed73c337bf977753e3565e9e0e30baf`.
Native admission, native export with original layered-program preservation,
import through the Themes process, and actual icon-only application passed.
The applied module's source marker matched that publication hash.

A separately owned Music-to-Settings probe confirmed day/night texture changes,
normal app opening, and the original rotating-note animation during a fast
return-home gesture. The full publication was also captured in both system
appearances. This is Phone AVD evidence; the other admitted source fixtures are
not a claim of physical-device or every-style animation acceptance.

The final production checks covered 1,218 JVM tests (1,212 passed, 6 skipped),
lint, and 11 device cases (10 passed, 1 missing-fixture skip), plus separate
probe and complete-module import cases. The diagnostic APK deliberately retained
the previously validated Phone native payload. No native activation was needed
for this resource-generation feature.

During live testing the AVD also recorded input-monitor ANRs in SystemUI and
Launcher, followed by a primary display stuck behind an undrawn keyguard.
SystemUI was separately restarted to recover interaction. The available ANR
record lacks a usable Java main-thread stack, so this checkpoint does not
attribute those failures to this feature or claim to fix them. Normal update
handoffs and identities must be evaluated separately from that recovery.
Detailed receipts, screenshots, animation video, publication and recovery facts
are kept in `.artifacts/research/perfect-dynamic-night-20261110/acceptance.json`.

The generated-pack card's **Dark layered** pill counts actual emitted nightmode layer groups whose live selector was also admitted. A persisted night renderer revision marks older generated packs as requiring regeneration only when both dark adaptation and dynamic resources are enabled. With dark adaptation OFF, this revision check leaves the original behavior unchanged.

The archive reader recognizes the exact generated zero-frame-rate template, including its compact single-image form, for selectable light/dark previews. This is format recognition only; it does not broaden native script execution admission.

The main page and preview page share the same persisted light/dark adaptation option. Changing it clears the in-memory preview and reloads the appropriate content-addressed preview archive; toggling back reuses the original preview without a source download. The preview identifies the current mode and its hero image follows the filtered items. Previews are still images or quiet snapshots for the current options, not live native animation or proof of the desktop's applied appearance. Eligible dynamic groups are intended to animate through their original native program after generation and application; still previews cannot verify that behavior. The generated-pack card separately identifies the last generated mode and keeps the options-changed warning until regeneration. Previewing never rewrites or applies a published pack. With the option OFF, original layers, configured native dynamics and existing dark behavior are retained, and no additional derived dark artwork is generated. Changing the applied result requires generation and application, including when disabling the option.

Historical static-only AVD checkpoint (2026-11-09): the retained HyperOS4 snapshot and installed-app selection generated 51 mappings, comprising 46 static IDs and five original dynamic IDs. The opt-in archive contains 46 paired-image containers and is 17,574,278 bytes, compared with 6,775,689 bytes for the original native-layer output. All 83 original dynamic files and 102 original static layer files remain byte-identical. Repeated generation produced the same archive SHA-256 and reused cached pairs; cache timestamps may advance for LRU bookkeeping. Twenty device tests cover rendering, layer composition and publication, while 1,100 host tests passed with four skipped. Actual application to the Launcher and transition performance were not measured in this checkpoint: the existing Classic ART+ theme remains active. The new option was restored OFF after preview/generation acceptance. Exact build, identities and evidence are recorded in `.artifacts/research/perfect-static-night-20261109/acceptance.json`. This record does not establish live-device acceptance of the dynamic extension or its transition performance.
