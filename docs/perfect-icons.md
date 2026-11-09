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
participate in publication and preview identity; the complete source snapshot
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

1. Explicitly sync the complete upstream repository snapshot. A single streamed
   GET to GitHub's codeload ZIP endpoint downloads the current `main` tree,
   without Git history. Opening the page only loads local metadata.
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

The existing validated local-native import boundary still applies: bounded
images and local visual XML are admitted, while external sources, binders,
script installation payloads, malformed/ambiguous layers, and unsafe paths
are rejected. Upstream identity is not a reason to bypass these checks.

## Download and storage behavior

The primary flow makes no REST API tree queries or per-image raw requests.
Later explicit syncs send `If-None-Match` to the same codeload endpoint. A
`304` updates the successful-check time, keeps the snapshot download time,
and reports that the repository is unchanged. A changed snapshot is fully
validated before its durable catalog pointer replaces the previous one.
Network or validation failure preserves the old snapshot, timestamps and
generated pack.

The source commit is read from GitHub's Git-archive ZIP comment, tying the
displayed revision to the downloaded archive rather than a separate branch
lookup that might race an upstream update. The UI shows seven characters
and copies the complete commit when tapped. An absent comment is recorded
as an unknown commit; the independently computed archive SHA-256 is then
labelled as a snapshot checksum, never as a Git commit.

Source archives are persistent offline data, with their size shown separately.
The actual `fb8b5c9` source snapshot is 594,366,831 bytes (about 567 MiB):
27,597 ZIP entries and 609,922,400 expanded bytes. This is a substantial
one-time transfer; reducing the request count does not make the repository
smaller. The parser's retained limits admit this real snapshot.
Generation, style changes and installed-app filtering read only that snapshot.
Files are cached by locally verified Git blob identity
(`SHA1("blob " + length + NUL + content)`), allowing reuse across theme
selections and later commits. Clearing derived caches keeps the complete
snapshot, options and generated/imported packs; later generation repairs
those caches locally without another download.

The downloader streams to a private temporary file with cancellation checks.
Archive size, expanded size, entry count, compression ratios, duplicate paths,
links, CRCs and local/central-name consistency are bounded or verified.
Only selected icon resources enter the generated pack; repository scripts
are never executed. Requests observe persisted cooldowns without recursive
retries. A `403` without rate-limit evidence is a download failure, not an
assertion that GitHub rate-limited the user. Whole snapshots avoid REST's
anonymous request quota but are not exempt from network or site protections.

No artwork from these projects is added to the shipped APK by this integration.
Both sources are linked in the page and the component attribution screen.

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

The 2026-11-09 UI acceptance checkpoint is recorded separately in
`.artifacts/research/perfect-preview-20261109/acceptance.json`, including the
exact signed build, AVD screenshots, menu/search behavior, font-scale restoration
and process continuity. This layout change does not modify the source downloader,
conversion pipeline, generated theme or native Hook payload.

## Explicit full download

The Source & Storage page has a floating “Force download latest resources”
action. A confirmation explains the full repository transfer (potentially over
600 MB), temporary disk space and preservation of the active theme. Cancel
performs no network work. Confirm skips the local ETag and source rehash but
still observes the existing cooldown, validates the complete downloaded ZIP
and publishes its pointer atomically before retiring older source bytes.
Failure retains the previous usable snapshot. Normal synchronization remains
conditional. Neither action applies a generated theme.

The system-app fallback resource entry always opens Source & Storage, including
when resources have not yet been downloaded. Progress and errors stay visible
on this page, with measured space reserved below the list for the floating action.
EN, Simplified Chinese and Japanese use the same flow. Acceptance evidence is
recorded in `.artifacts/research/perfect-force-sync-20261109/acceptance.json`.

Source & Storage uses the shared status-card layout, with local icon count,
archive size and a copyable short revision in pills. The full revision, checksum,
download timestamp and generated filename are available in a MIUIX WindowDialog.
Cache size sits alongside its cleanup action; source attribution has its own
local subpage. The full-download confirmation also uses MIUIX WindowDialog,
with native title/summary layout and equal-width cancel/primary buttons.
Opening the page never starts a download.

Before the first snapshot exists, the action and confirmation use first-download
wording. With a usable snapshot, the same entry explicitly offers a forced
re-download. Both paths require confirmation and share the validated transfer.

## Layer rule checkpoint (2026-11-09)

Static background/foreground pairs remain native layers; `0.png` and `1.png` are not light/dark variants. Configured native layer resources are selected and validated independently from unconfigured resource folders. A valid configured theme group overrides its common counterpart as a complete group. Generation validates referenced local assets and preserves original native programs rather than manufacturing MAML for ordinary static artwork.

Static and active-fancy classifications are exclusive for statistics even when both source representations are preserved in the output. A quiet-only configured group remains a static icon, while the native configured-directory admission flag stays enabled for it. Incomplete configured groups retain an available static alternative; the result exposes skipped-group counts. Changing layer rules invalidates preview/publication identity without downloading the source snapshot again, and older saved generation results show a regeneration reminder.

The separate investigation of ThemeStore's application protocol is in [theme-apply-without-navigation.md](theme-apply-without-navigation.md). No alternative no-navigation apply mechanism is enabled by that investigation.

The retained `fb8b5c9224510fedb653a7dceafdcb364b4e2263` snapshot has 22 artwork styles. All 20 HyperOS4 configured groups pass source dependency validation. Its exclusive counts are 2,445 static IDs and 20 configured dynamic IDs; the static alternatives for 19 of those dynamic IDs remain in the archive. These are resource IDs, not installed-app counts or a promise that every configured icon continuously animates. Other styles contain 18 incomplete groups in total: Hyper/HyperOSClassic have a flag for an undeclared weather layer, lrone lacks a declared weather layer, and luck7 lacks 15 referenced image assets. Available static alternatives are retained for those groups.

Local auto-apply admission remains a separate, stricter execution boundary. A host test constructs native modules from each of the 20 original HyperOS4 groups and exercises the production admission validator. Clock, calendar and seven other groups pass; 11 complex or noncanonical groups remain outside that automatic admission policy. Source closure success does not authorize arbitrary MAML scripts, external data sources or commands. Such a generated archive can still require the existing native Themes workflow; this checkpoint does not broaden the execution whitelist to obtain a passing statistic.

Legacy `use_fancy_when_static_layerN` and `use_quiet_when_anim_layerN` boolean fields are preserved and bounded against declared layers. Their names are not interpreted as exclusive quiet/fancy modes. Only the existing explicit `layer_only_quietN` / `layer_only_fancyN` rules provide that exclusivity.
