# Dependency update policy and November 2026 migration

The 2026-11-11 update selects the newest published stable or release-candidate
version from each dependency's official repository. Alpha, beta, development
and snapshot releases are excluded. MIUIX is an explicit exception:
all five `top.yukonga.miuix.kmp` components remain pinned to
`0.9.4-fa6f5b50-SNAPSHOT`. Its OS4 design migration is separate work.

## Version alignment

| Component | Selected version |
| --- | --- |
| Gradle wrapper | 9.9.1, distribution SHA-256 verified |
| Compose UI, Runtime, Foundation, Animation, tooling and UI tests | 1.13.0-rc01 |
| Stable Compose BOM fallback | 2026.09.00 |
| ProfileInstaller | 1.4.1 |
| Startup | 1.2.0 |
| SavedState and Compose integration | 1.5.0 |
| Collection | 1.7.0 |
| Window | 1.5.1 |

The stable BOM predates the current Compose RC. App-owned Compose APIs and
instrumentation/tooling artifacts therefore have explicit catalog versions.
Their module dependencies align the Android runtime variants. The BOM is an
ordinary platform, not an enforced platform; it does not force those artifacts
back to the older stable version.

`runtime-alignment` contains constraints for libraries already used by the
resolved graph. Constraints raise an existing module's version without adding
an unused library. Versions remain in `gradle/libs.versions.toml`; there is no
global `force`, dynamic `+` version or resolution-time metadata request.
MIUIX's own multiplatform facade artifacts retain their declared versions.
The actual AndroidX implementations underneath them use the app's alignment.

AGP/Zipflinger 9.5.1, Kotlin/compiler plugins 2.4.21, Core 1.19.1,
Activity 1.13.0, Lifecycle 2.11.0, Coroutines 1.11.0, DexKit 2.3.0,
MMKV 2.4.3, MCU 6.0.0, libxposed API/service 102.0.0 and the existing AndroidX
Test versions were already the newest eligible releases when checked.
The SDK-managed NDK 30.0.16248370, CMake 4.1.2 and Build Tools 37.0.0 also remain
current. Compilation uses SDK 37.2 because Compose 1.13 RC requires at least
37.1. The runtime minimum and target remain API 37; API 102 scope semantics
are unchanged.

External FlatBuffers remains at DexKit 2.3.0's generated-code version,
23.5.26. The newest Java runtime, 25.2.10, removes the exact
`Constants.FLATBUFFERS_23_5_26()` guard referenced by DexKit's generated classes.
It is a documented binary-compatibility exception pending a matching DexKit
code-generation update, rather than a version forced past that missing symbol.
Emoji2 uses its own relocated runtime and can be upgraded independently.

## Compatibility and verification

Kotlin's published fully supported toolchain matrix trails the current Gradle
and AGP releases. This project already uses explicit Kotlin, serialization and
Compose compiler plugins. Build-logic's Kotlin JVM plugin uses the same
2.4.21 version. A successful build is project-specific evidence, not an upstream
guarantee for every plugin combination.

Validation includes the JVM suite, Debug/test APK compilation, signed Release
R8/lint, resolved dependency graphs and AVD navigation checks. Onboarding is
checked at standard and enlarged text; tab switching, hint presentation and
deferred profile installation exercise affected runtime integrations.
Dependency upgrades do not justify clearing app data or regenerating users'
downloaded icon resources.

UI tests share `ForegroundComposeHostRule`. HyperOS can leave
`Instrumentation.startActivitySync` waiting when a new test starts after the
previous host closes. The rule brings the target's test `ComponentActivity`
forward before Compose's ActivityScenario rule runs. This is test-only setup;
it does not change production navigation, clock synchronization or background
activity-launch policy.

Build and install the Debug target and AndroidTest APK as a pair from one
Gradle invocation (`:app:assembleDebug :app:assembleDebugAndroidTest`).
KeiHome allocates a new version code for builds, while version-sensitive test
fixtures inline `BuildConfig.VERSION_CODE`. Rebuilding only AndroidTest and
running it against an older Debug target can invert current/stale loading
assertions even though the production status logic is correct.

Official sources:

- [Gradle release feed](https://services.gradle.org/versions/all) and
  [9.9.1 distribution checksum](https://services.gradle.org/distributions/gradle-9.9.1-bin.zip.sha256).
- [Compose UI releases](https://developer.android.com/jetpack/androidx/releases/compose-ui),
  [Runtime releases](https://developer.android.com/jetpack/androidx/releases/compose-runtime),
  [Foundation releases](https://developer.android.com/jetpack/androidx/releases/compose-foundation)
  and [Animation releases](https://developer.android.com/jetpack/androidx/releases/compose-animation).
- [Kotlin toolchain compatibility](https://kotlinlang.org/docs/gradle-configure-project.html).
- [Google Maven repository](https://dl.google.com/dl/android/maven2/master-index.xml),
  [Maven Central](https://repo.maven.apache.org/maven2/) and
  [Android SDK repository](https://dl.google.com/android/repository/repository2-3.xml).

Exact fetched metadata, resolved before/after graphs, checksums, build logs and
device evidence are retained under
`.artifacts/research/dependencies-onboarding-20261111/`.
