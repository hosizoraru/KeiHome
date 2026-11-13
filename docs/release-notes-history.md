# Release notes history and content provenance

## Source of truth

`ReleaseNotesCatalog.kt` contains release-note versions, their exact catalog tags, offset-aware target commit times, and localized resource references.
`release_notes_content.xml` in `values`, `values-zh-rCN` and `values-ja` contains
the content. Versions are listed newest first.

Exact publicly shipped APK identities and the default build for unspecified
version feedback are recorded in the [public build ledger](released-versions.md).
Catalog anchors and public artifact identities must be checked separately.

Dates use the target commit's committer timestamp (`git show -s --format=%cI
TAG^{commit}`), not the annotated tag's creation time, APK build time or the
currently installed development build's time. Keep the recorded offset when
deriving the calendar date.

| Version | Tag target commit | Target commit timestamp |
| --- | --- | --- |
| 1.9.0 | `v1.9.0^{commit}` | `2026-11-13T07:12:24+08:00` |
| 1.8.0 | `v1.8.0^{commit}` | `2026-11-12T09:15:03+08:00` |
| 1.7.0 | `8b71d5ae41eaca2a820d8a1105703074ef22425f` | `2026-11-11T17:53:39+08:00` |
| 1.6.0 | `02c8badfcd929c0563fb8c25361167ae5eed0008` | `2026-11-11T03:24:52+08:00` |
| 1.5.0 | `1bf0c4ccb43d049d3caa09f1f3cef96d74052c68` | `2026-11-07T14:07:22+08:00` |
| 1.4.0 | `e00c6c6c5cc46e979633bf14942a455bde5d5a44` | `2026-11-06T11:43:46+08:00` |

For 1.9.0, the local tag targets preparation commit
`d23803205de01c61ec9f48515d32a93fd7df1bfd`. The user-confirmed public APK is
`1.9.0+g70ea216.b0394`, versionCode `109000394`, built from
`70ea21606be2d8277da0c1031853d94b257b396f`, four commits later. Public
confirmation is dated 2026-11-13 in Asia/Singapore. The tag and its catalog
timestamp remain unchanged; use the ledger's artifact and certificate digests
to identify the shipped build. Unspecified subsequent "1.9.0" feedback refers
to that public APK; an explicit full version overrides this default.

The catalog starts at 1.4.0, the first public release. Its Chinese introduction,
twelve feature title/body pairs and closing note are the user-supplied public
announcement. The nine Chinese paragraphs for 1.5.0 are also preserved verbatim.
Only list numbering, blank lines and indentation are presentation concerns;
the resource stores each 1.4.0 title and its body together with `\n`.
English and Japanese faithfully translate these announcements, using existing
localized style names.

The 1.7.0 placeholder has now been replaced with final user-facing notes for
`v1.6.0..v1.7.0`. Later work belongs to 1.8.0, including the new release
history itself. The 1.8.0 row uses the peeled tag reference to avoid embedding
a commit's own hash in that commit; the catalog checker resolves the reference
and verifies the date. Local tags do not imply GitHub publication.

## 1.6.0 editorial boundary

This entry summarizes final user-visible changes strictly within
`v1.5.0^{commit}..v1.6.0^{commit}`. It is not a chronological commit list.
The table maps each paragraph in `release_notes_1_6_0_changes` to retained
pre-tag documentation or production code and representative in-range commits.
Read these paths at **v1.6.0**, for example
`git show v1.6.0:docs/apple-icon-pack.md`; the working copies may contain later
changes.

| Paragraph | User-visible result | Evidence at the 1.6.0 tag | Representative in-range commits |
| --- | --- | --- | --- |
| 1 | Apple schemes, appearance/platform/corner options, local cache and manual/batch mapping, mapping transfer | `docs/apple-icon-pack.md`, `docs/apple-system-mappings.md`; `iconpack/apple/AppleIconRepository.kt` | `596b0a1`, `3f999df`, `c735492` |
| 2 | Perfect Icons source synchronization, styles, previews, local generation and fallback | `docs/perfect-icons.md`; `iconpack/perfect/PerfectIconRepository.kt` | `b2c19ac`, `a4bf440` |
| 3 | Native layered/day-night resources and supported dynamics; Apple Calendar/Clock dynamics with static previews | `docs/native-monet-import.md`, `docs/apple-icon-pack.md`; `iconpack/IconPackArchive.kt` | `a255775`, `596b0a1` |
| 4 | Optional derived dark artwork for eligible Perfect Icons layers, with original dark resources taking precedence and linked previews | `docs/perfect-icons.md`; `iconpack/perfect/PerfectStaticNightRenderer.kt`, `iconpack/perfect/PerfectDynamicNightRenderer.kt` | `878b403`, `cf7ccdd`, `2d48715` |
| 5 | Pack/appearance/Monet consistency on supported recents, notification app icons, HyperOS share targets, Settings and Sidebar; APK-content-sensitive Security cache | `docs/cross-surface-icon-refresh.md`, `docs/hyperos-share-icons.md`, `docs/shared-system-app-icons.md`; `xposed/HyperOsSidebarProfile.kt` | `30f20b3`, `b0fca67`, `c68dac6`, `15ea434`, `01e3be6` |
| 6 | Correct native fallback for Download Manager and verified factory data apps; ColorOS Theme Store aliases | `docs/download-manager-fallback.md`, `docs/preinstalled-icon-fallback.md`, `docs/coloros-theme-store-aliases.md`; `iconpack/SystemIconFallbackRenderer.kt`, `iconpack/BuiltinColorOsMappings.kt` | `b50acef`, `64c9dba`, `4df6aac` |
| 7 | Monet-aware imports/previews and declared pack names, versions and attribution | `docs/native-monet-import.md`, `docs/icon-pack-guidance.md`; `iconpack/ApkIconPackAuthorMetadata.kt`, `iconpack/IconPackArchive.kt` | `0d5a6b3`, `f4ae5bd` |
| 8 | Bounded large/nested archive processing, raw-entry copying, bounded preview caches and retention of last usable resources | `docs/native-zip-import-20261108.md`, `docs/native-monet-import.md`, `docs/perfect-icons.md`, `docs/apple-icon-pack.md`, `docs/ui-performance.md`; `iconpack/ImageArchiveZip.kt` | `c5f015e`, `198c09b`, `f7b3dc3` |
| 9 | More reliable old-generation retirement and supported stale-recents appearance refresh | `docs/cross-surface-icon-refresh.md`; `xposed/KeiHomeModule.kt`, `xposed/LauncherIconRefreshRuntime.kt` | `e3056c5`, `df02f0c`, `30f20b3` |
| 10 | Launcher 9239 compatibility, including Dock/Monet and safe native fallback | `docs/launcher-9239-compatibility.md` and its linked feature evidence | `5cbd8e7` |
| 11 | Pad capability handling, preserving native Pad Dock and saved settings without applying unsupported phone overrides | `docs/dock-pad-support.md` | `37d69fe`, `2ac53e6` |
| 12 | Compact optional guidance, author display and clearer actions; bottom-navigation and large-text improvements | `docs/icon-pack-guidance.md`; `ui/main/MainTabNavigation.kt` | `b695e90`, `221736e`, `1f233c6` |
| 13 | First-activation, scope and onboarding guidance, distinguishing grants from loading and effective behavior | `docs/onboarding.md`, `docs/static-scope.md` | `2ac53e6`, `2ff0a9f` |

Production paths abbreviated above are relative to
`app/src/main/kotlin/os/kei/hyper/home/`.

The content explicitly keeps support qualifications. Notification *app icons*
do not imply replacing all status-bar glyphs or caller-owned images. A host's
authorization is distinct from module loading and visual acceptance. Pad
support preserves the native Dock; it is not a claim that phone glass overrides
were accepted on every tablet. Structural compatibility is not universal
physical-device acceptance, and no FPS or memory benchmark claims are added.

## Exclusions

The following later work is deliberately absent from 1.6.0 notes:

- The MIUIX OS4 snapshot migration, OS4 Glass UI and its navigation-style choices.
- The later Desktop subpages, storage visualization, rewritten scope page and
  rewritten OS4 onboarding.
- The subsequent automatic native icon-configuration completion and white-rim /
  square-corner fixes (`4899553`).
- Launcher 8607 and the subsequent Security APK matrix refresh (`5e707b3`).
- The 1.7.0 version bump and the release-notes feature itself.

## 1.7.0 through 1.9.0 editorial boundaries

[1.7.0 notes](releases/1.7.0.md) group the final effects of OS4 migration,
the two bottom-navigation styles, Desktop categories and material controls,
status pills and cache management, scope authorization, onboarding, native icon
configuration fixes, and launcher/Security compatibility. Representative
in-range commits: `6718348`, `2d7b23f`, `06b7887`, `24dd291`, `cfc2aaf`,
`8822dc4`, `58e68b1`, `4899553`, `5e707b3`.

[1.8.0 notes](releases/1.8.0.md) cover release history and prefix-based startup,
selective Git resource synchronization and resource management, OS4 confirmations,
status/navigation refresh, default style ordering and storage accounting, shared
mask rendering, custom masks, dynamic resource validation and the updated guide.
Representative in-range commits: `ff3ec6d`, `290ca54`, `a5c8203`, `879c1aa`,
`97f6bae`, `413317d`, `b236e15`, `185ad34`, `bc4d232`, plus the release commit.
See `docs/perfect-git-sync.md`, `docs/icon-mask-preview.md`,
`docs/ui-performance.md` and `docs/onboarding.md` for scope and limits.

[1.9.0 notes](releases/1.9.0.md) describe the public 1.9.0 build, whose source
boundary is `v1.8.0^{commit}..70ea21606be2d8277da0c1031853d94b257b396f`.
The older `v1.9.0` preparation tag remains the catalog anchor. The notes cover component/adaptive
icon and dynamic snapshot fixes, publication-aware masked previews, preserved
originals and optional recommended mappings, independent Perfect completion,
OS4 Glass controls and list actions, removable imports, fixed cache reminders
and log accounting, Hook overhead and R8 protection. The notes describe final
behavior rather than individual commits. First upgrade presentation expands
1.9.0/1.8.0/1.7.0/1.6.0; manual entry expands only 1.9.0. See
`docs/icon-rendering-alias-regression-20261112.md`,
`docs/recommended-icon-mappings.md`, `docs/perfect-icons.md`,
`docs/hook-hot-path-performance.md` and `docs/miuix-os4-migration.md`.

Later development, including the new default-on in-call UI to Telecom/Phone
recommended mapping, is outside this public build. Keep those pending changes
out of the published 1.9.0 notes; retaining the same version prefix does not
extend the shipped artifact's scope.

The catalogs retain the supplied Chinese 1.4.0 and 1.5.0 announcements and
existing 1.6.0 notes. The focused catalog check validates XML and localized key /
array parity, newest-first order, the current version, and exact tag target
timestamps. Release-note policy and targeted device navigation checks separately
cover first install, prefix changes, saved acknowledgment, expansion and layout.
