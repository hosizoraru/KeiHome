# MIUIX d8efe187 migration

## Upstream review

All eight direct MIUIX dependencies now use
`0.9.4-d8efe187-SNAPSHOT`. The GitHub Packages artifacts resolved on
2026-11-15. The retained upstream checkout was fast-forwarded from
`19b5b831679a45d3d8a1484fce00ef12b9b494db` to
`d8efe1872c08c863a8b33e7044dd2a8b43038032`.

The review covers both commit messages, the icon guide in Chinese and
English, the Glass top-bar contract, icon source and affected layouts:

- [2855a36: Add more miuix glass icons](https://github.com/compose-miuix-ui/miuix/commit/2855a36ba516cbf2b5ff46d722da38c94a59c07b)
- [d8efe187: Center collapsed top-bar title and subtitle groups](https://github.com/compose-miuix-ui/miuix/commit/d8efe1872c08c863a8b33e7044dd2a8b43038032)
- [Icon guide](https://github.com/compose-miuix-ui/miuix/blob/d8efe187/docs/zh_CN/guide/icons.md)
- [Glass top-bar contract](https://github.com/compose-miuix-ui/miuix/blob/d8efe187/docs/zh_CN/components/glass/glasstopappbar.md)

Most of the large source diff consists of generated vectors: 336 added
Glass icon files, 15 renames, and removal of `Recent`. The update does not
introduce a new sheet, search or popup component.

## Application migration

Existing `Settings`, `Folder` and `WorldClock` calls become
`SettingsOutline`, `FolderOutline` and `Time`. Their vector paths match
their previous versions.

Settings uses `UsageHelp`, `AppData` and `Log` for the guide, cache and logs.
The shared pack action uses `Item`; pack help uses `UsageHelp`.
The existing callback, content description, tint and button size remain
owned by each caller. Brand artwork and the saved navigation style are
preserved. Other unrelated Lucide icons remain available.

The shared `KeiHomeTopAppBar` already delegates to `GlassTopAppBar`, which
uses the regular expanding top-bar layout through `BlurTopAppBar`. It
therefore receives the grouped title/subtitle alignment fix directly.
There is no app-side offset or duplicate title layout.

The default background overhang changes from 48 dp to 28 dp. The band
uses a quintic fade over its bottom 12 dp plus the overhang, computed from
the measured background height. KeiHome retains the default material
behavior, its real scroll/elastic offset separation and intentional
Gaussian transparent bands.

The separate upstream `SmallTopAppBar` layout measures its action slot
but no longer places it in this revision. No production KeiHome caller
uses that component. This migration keeps the existing expanding
top-bar entry point; the application action regression verifies the
actual shared wrapper.

## Validation and release identity

`MiuixTopBarMigrationTest` checks the regular and Glass collapsed title
groups with asymmetric controls, LTR/RTL, normal/large text and light/dark
themes. It also exercises both shared toolbar actions with a long title
on a 320 dp surface. The focused `ui-miuix-migration` check includes the
existing navigation, spring-offset and pack-guidance coverage.

The candidate retains the `1.10.0` prefix. The existing annotated
`v1.10.0` tag and its archived APK remain unchanged; this migration is a
subsequent candidate until release attribution is explicitly updated.
The three-language 1.10.0 notes mention the new Glass icons, collapsed
title alignment and top-bar fade. Earlier release notes stay unchanged.

Evidence is retained in
`.artifacts/research/miuix-d8efe187-20261115/`, including resolved artifact
hashes, build and test results, screenshots, deployment receipts and
final acceptance. Device work uses only HyperOS Phone AVD; no ColorOS
AVD is started. The native payload and activation settings are preserved.
