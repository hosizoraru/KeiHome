# Research index and local artifacts

The November 1 cleanup replaces accumulated `/tmp/keihome-*` work directories
with a small set of local inputs and a compressed evidence archive. This page
is the entry point for historical paths mentioned in older investigation notes.
Cleanup does not constitute a new device validation or close an open defect.
The exact retention and space accounting are in the
[November 1 cleanup receipt](2026-11-01-cleanup.md).

## Findings to reuse

| Area | Durable conclusions and remaining limits | Main note |
| --- | --- | --- |
| Ordinary icon refresh | LauncherApps package and shortcut callbacks can refresh launcher-owned views without killing the launcher. Dock, folders and profiles need their own visible checks. | [Icon refresh](../icon-refresh.md) |
| Native Monet and launcher updates | Resolve live mapped instructions, call edges, fields and ABI instead of carrying one version's RVAs forward. Both 7722 and 8593 are retained as regression inputs. | [Launcher compatibility](../launcher-8593-compatibility.md) |
| Image ownership | `Image.dispose` invalidates a wrapper even when another wrapper owns the backing image. Cache/State/async ownership cannot be inferred from a successful rebuild. The bounded recovery still needs a positive runtime exercise. | [Image ownership](../monet-image-ownership-8593.md) |
| Community icon packs | Import through the native Themes component, preserve theme-owned art and associate the applied Icon component by its actual identity. Static conversions cannot transfer every pack-specific runtime feature. | [Icon packs](../icon-packs.md) |
| Day/night theme art | The generated MAML selector uses the native `__darkmode` updater. Preserve the original native package instead of replacing all art with APK monochrome. | [MAML contract](../native-day-night-maml.md) |
| Materials | Keep theme identity and native capability/power policy. Refresh the existing volume/Control Center owners, slider drawables and real-tile geometry at their native boundaries. Native highlight publication alone does not prove mounted visual behavior. | [Theme materials](../theme-materials.md) |
| ANR/watchdog | Probe binding and UI-thread hashing were concrete contributors. A passing switch, unchanged PID, or one runnable stack does not establish the original timeout's cause. | [ANR investigation](../anr-investigation.md) |
| Wallpaper and customization | Wallpaper seed discovery, selected accent/neutral scheme and installed icon policy are separate states. Refresh must not silently apply an unsubmitted draft. | [Wallpaper](../wallpaper-colors.md), [customization](../monet-customization.md) |

The archived `cc-refresh-and-geometry.md` and other sidecar reports are dated
research proposals. The implementation and later evidence may supersede them;
they are not additional production requirements or universal compatibility claims.

## Local directory layout

All paths below are relative to the repository. `.artifacts/` is intentionally
ignored: proprietary APKs, device data and large binaries do not belong in Git.

| Path | Purpose |
| --- | --- |
| `.artifacts/inputs/manifest.json` | Original path, size and SHA-256 for each retained input |
| `.artifacts/inputs/launchers/{7722,8593}/` | One original launcher APK and a ready-to-use `libapp.so` per version |
| `.artifacts/inputs/systemui/` | Exact SystemUI host/plugin APKs used by the material investigation |
| `.artifacts/inputs/themes/` | Native Themes importer source APK |
| `.artifacts/inputs/framework/` | Framework resources/JAR and system-partition native readers |
| `.artifacts/inputs/icon-packs/` | Arcticons and Lawnicons test APKs; no artwork is bundled in KeiHome |
| `.artifacts/inputs/icon-samples/` | Original malformed-monochrome sample APKs |
| `.artifacts/research/2026-11-01/evidence.tar.gz` | Deduplicated reports, traces, logcat, screenshots, fixtures and one-off analysis sources |
| `.artifacts/research/2026-11-01/manifest.json.gz` | Per-file retention/deletion decisions, hashes and duplicate-member mapping |
| `.artifacts/releases/1793454007/diagnostics.tar.gz` | Exact R8 mapping, native symbols and saved build/test reports |
| `.artifacts/releases/1793454007/manifest.json` | Release APK hash and hashes of retained diagnostic files |

The current APK remains at `app/build/outputs/apk/release/app-release.apk`.
Its previously delivered copy under the user's visualizations directory is
also preserved. The working `/tmp/keihome-capstone-venv` remains available;
its older duplicate under `keihome-tint-abi/venv` was regenerable.

## Reading an old evidence path

Archive members retain the original path beneath `tmp/`. For example:

```sh
tar -xOf .artifacts/research/2026-11-01/evidence.tar.gz \
  tmp/keihome-theme-research/anr-ownership.md

tar -xOf .artifacts/research/2026-11-01/evidence.tar.gz \
  tmp/keihome-runtime-status/private-cycle.log
```

Identical files are stored once. If a member is absent, look up its original
`source` in `manifest.json.gz`: `duplicate-evidence` points to the retained member,
and `duplicate-input` points to the retained input. `regenerable` entries are
APK/JAR extractions or compiled scratch output, not archived evidence.

For a binary trace, extract just its listed member into a new working directory.
Avoid unpacking the whole archive back into `/tmp`.

## Recreating derived analysis

Use the retained APK as input to JADX or unzip it into a new scratch directory.
For example, either launcher ELF can be recreated without a device:

```sh
unzip -p .artifacts/inputs/launchers/8593/launcher.apk \
  lib/arm64-v8a/libapp.so > /tmp/keihome-8593-analysis.so
```

Host test runners still accept explicit ELF paths; their documented examples
now use the retained fixtures. No fixture contains a portable production RVA.
Project-local Gradle, Kotlin, CMake and build intermediates regenerate on the
next build. Global dependency caches, SDK/NDK, signing configuration, reference
repositories and AVD userdata were outside this cleanup.
