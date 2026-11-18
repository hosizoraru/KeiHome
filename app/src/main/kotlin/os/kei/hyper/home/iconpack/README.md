# Icon-pack integration

This package supplies the app-process catalog, SAF import, source-artwork previews,
and a native icons archive handoff. The main UI owns tab index 3 (status, apps,
Monet, icon packs, settings); the native bridge owns observing and applying the
system theme. Importing a pack does not select it or change any system files.
All Kotlin types are `internal`, accessible to the rest of the app module.

## Entry points

```kotlin
val repository = IconPackRepository(context)
val previews = IconPackPreviewRepository(context)

val installed: IconPackScanResult = repository.listInstalled()
val imported: IconPackScanResult = repository.listImported()

// Launch IconPackRepository.createImportIntent(context) and read the returned data URI.
// Call while the document read grant is valid. No persistent grant is needed.
when (val result = repository.import(uri)) {
  is IconPackImportResult.Success -> {
    val pack: IconPackDescriptor = result.pack
    val alreadyStored: Boolean = result.alreadyPresent
  }
  is IconPackImportResult.Failure -> showImportFailure(result.message)
}

val bitmapPair: IconPackPreviews = previews.load(pack, entry, sizePx = 128)
// light/dark are nullable shared Bitmaps. Never mutate or recycle them.
// lightFailure/darkFailure explain absent previews. clear() drops cached results.

val exported = repository.exportNativeIcons(
  pack,
  targets = launcherComponents.map { IconPackComponent(it.packageName, it.className) },
  darkMode = requestedDarkMode,
  sizePx = 192,
)
// exported.archive is a validated ZIP suitable as the native `icons` module.
// The caller deletes this temporary file after the bridge consumes it.
```

`IconPackScanResult` contains `packs`, per-source `failures`, and
`visibilityLimited`. Installed discovery uses visible launcher/ADW/Apex/Nova/
Lawnchair intents and probes actual appfilters. It is scoped to the current
Android user and current package visibility; it does not request broad package
or storage permissions. An empty result cannot establish that no packs are
installed. Refresh installed descriptors and call `previews.clear()` after a
package update, theme/overlay update, or palette change.

`IconPackDescriptor` contains `id`, `displayName`, `format`, `source`, `entries`,
`description`, `previews`, `notices`, and retained appfilter `directives`.
An entry has an exact component or native filename stem plus light/dark artwork.
Native stems can name activities or packages; prefix-based ownership is not
inferred. `previews` names original outer-MTZ preview images. Native animation
entries with no static art remain present with unavailable app-process previews.

For `MIUI_MTZ`/`MIUI_ICONS`, export copies the native icons module **unchanged**.
`preservesNativeResources == true`; both night resources and original masking,
animation, and configuration files survive. The requested `darkMode` is recorded
but does not rewrite the module. Targets report exact known filename coverage;
they do not filter the native module. `StoredIconPack.iconsFile` is the private
inner ZIP for MTZ; for a bare icons ZIP use `originalFile`.

For classic ZIP/APK packs, export renders a static PNG snapshot of the requested
mode under `res/drawable-xxhdpi/`. It preserves color/alpha/aspect ratio and does
not add Monet tint, masks, backgrounds, or missing-app replacements. Exact
activity filenames are emitted. A package alias is emitted only when every
supplied launcher target in that package maps to the same entry. Conflicting
native filenames fail the export; missing or unsupported artwork is listed in
`skippedTargets`. This is a static export, without ongoing calendar/clock or
palette updates. `IconPackHandoffs` combines the two mode exports using native
`nightmode/res/drawable-xxhdpi/` paths and packages an icons-only MTZ for Themes.
Both native trees survive export; this alone does not prove the installed
launcher's mode selection. For converted day/night PNG pairs, KeiHome also
emits a canonical MAML selector so launcher 8593 can redraw the pair from its
own `__darkmode` updater. The source MTZ and its full inner module are retained
when no static conversion is needed.

An exact archive SHA-256 can be checked with `pack.matchesLoadedIcons`. The
native Themes library uses SHA-1 metadata instead, and the UI links an imported
source to its native Icon component localId. A fresh applied-component query
establishes the current theme; a saved preference or completed import does not.

## Input and storage contracts

- Classic packs: text `assets/appfilter.xml`, root `appfilter.xml`, or
  `res/xml[-qualifiers]/appfilter.xml` and `res/raw[-qualifiers]/appfilter.xml`.
  APKs additionally use Android's resource table and compiled XML parser, so
  shortened ZIP filenames do not break lookup. APK code is never loaded.
- APKs resolve light and dark configurations independently through Android.
  The original resource table handles qualifiers, color references, vectors,
  and adaptive icons. An imported APK uses its private archive paths in `ApplicationInfo` with
  `PackageManager.getResourcesForApplication`, without KeiHome's resource table.
  Android owns the resulting AssetManager lifetime; the app does not mutate
  system assets or manually close shared resource providers.
  Custom drawable classes and animated drawable inflation are unsupported;
  referenced static drawable graphs have size, node, depth, and cycle limits.
- ZIP drawable variants retain their complete qualifier directory. Preview
  selection supports day/night, density, and API version; unknown qualifiers
  are reported and excluded from automatic selection. Uncompiled drawable XML
  and SVG rendering are not supplied; the original bytes are retained.
- MTZ: outer ZIP, optional `description.xml` rooted at `MIUI-Theme`, exact root
  `icons` ZIP, optional `preview/` artwork. Native static filenames follow the
  existing `ActiveIconTheme` inventory contract. Fancy/animating/layered icon
  descriptors are cataloged, but MAML is not executed in the app.
- Component aliases, duplicate mappings, special launcher shortcuts, calendar/
  clock declarations, and fallback-mask directives are not silently conflated.
  Static duplicate component mappings use last declaration; conflicts and
  invalid entries produce notices. Directives are retained for later work.
- Imports are copied unchanged beneath `Context.noBackupFilesDir/icon-packs`,
  keyed by SHA-256, with private versioned Properties metadata. Display names and
  URI paths never choose storage paths. No document-provider root is assumed.
- Default bounds: 256 MiB input, 512 MiB combined outer/inner expansion,
  96 MiB ordinary entry, 100,000 total ZIP entries, compression ratio 512,
  16 MiB text appfilter, 100,000 filter items, 32 stored packs, 1 GiB stored data.
  MTZ `icons` can occupy up to the input bound. CRCs and actual streamed lengths
  are checked, including unrelated outer entries. Nested arbitrary archives
  are not recursively expanded. DTDs/entities, unsafe/duplicate paths, deep XML,
  file/directory collisions, corrupt ZIPs, and over-budget imports are rejected.
  Cancellation propagates and unfinished staging directories are removed.

## Primary sources and licenses

These are format/API references, not vendored implementations or bundled artwork.
Downloaded samples remain outside the repository.

| Source | Evidence used | Upstream license |
| --- | --- | --- |
| [Lawnicons v2.19.0](https://github.com/LawnchairLauncher/lawnicons/releases/tag/v2.19.0) | Stable APK: resource `xml/appfilter` maps to obfuscated `res/hAN.xml`; vector/adaptive resource lookup | [Apache-2.0](https://github.com/LawnchairLauncher/lawnicons/blob/v2.19.0/LICENSE) |
| [Arcticons 15.3.2](https://github.com/Arcticons-Team/Arcticons/releases/tag/15.3.2) | Stable Day & Night APK: 6,527,359-byte text appfilter; `color/icon_color` day/night values; [separate variant resources](https://github.com/Arcticons-Team/Arcticons/tree/main/app/src/dayNight/res) | [GPL-3.0 code; CC BY-SA 4.0 icons](https://github.com/Arcticons-Team/Arcticons#license) |
| [Candybar sample appfilter](https://github.com/zixpo/candybar-sample/blob/main/app/src/main/assets/appfilter.xml) and [manifest](https://github.com/zixpo/candybar-sample/blob/main/app/src/main/AndroidManifest.xml) | Classic components, launcher shortcuts, mask/scale directives, discovery intents | [Apache-2.0](https://github.com/zixpo/candybar-sample#license) |
| [IconPack-to-MTZ compatibility](https://github.com/Adiker/IconPack-to-MTZ/blob/main/docs/COMPATIBILITY.md) | Outer `description.xml`/`icons`/preview layout; inner `res/drawable-xxhdpi`; device-specific application limitations | [Apache-2.0](https://github.com/Adiker/IconPack-to-MTZ/blob/main/LICENSE) |
| [Android ResourcesProvider](https://developer.android.com/reference/android/content/res/loader/ResourcesProvider), [PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager) | Resource-only APK loading and configuration-scoped installed resources | API reference |

Host sample SHA-256 values:

```text
Arcticons-15.3.2-dayNight-release.apk
d5aec9fa1e02718743c216193006bfa0ba71a6ade74504d06e046a66b29ed4b8
Lawnicons.2.19.0.apk
5ed67a0338c207bd0f8c44392dedbae8d0449fef5c8d773a202451d42bbd8cdd
```

Host validation accepts both release APK archives. The Arcticons text parser
produces 49,252 unique component mappings and 14,027 drawable names. Focused JVM
tests cover malformed paths, ZIP/CRC/size/nesting failures, XML limits/entities,
component parsing, night qualifiers, atomic storage, and byte-identical native
module export. This does not establish device rendering or native application;
those remain separate integration checks.
