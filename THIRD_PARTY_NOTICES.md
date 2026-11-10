# Third-party notices

## Perfect Icons resources and historical research

Perfect Icons source resources are acquired on demand from
<https://github.com/pzcn/Perfect-Icons-Completion-Project>. The individual graphics
and source resources retain their authors' rights; this is not an executable
dependency or an inferred blanket asset license.

PerfectIconsPacker (<https://github.com/YifePlayte/PerfectIconsPacker>, GPL-3.0)
was an earlier format research reference. It is retained as historical research
in the app's acknowledgements, not as a bundled, build or runtime dependency.
Current native configuration output is independently specified from retained
native/source data. See `docs/perfect-icons-provenance.md` for the implementation
boundary and the limits of that statement.

The in-App notices catalog groups the resolved dependency graph by project and
provides upstream links and offline license texts. Exact resolved coordinates
and versions for each build are generated into
`assets/third-party-dependencies.tsv` from that variant's runtime classpath;
they include transitive dependencies and platform metadata. Source references
and historical licenses are presented separately from current dependencies.

## MIUIX

KeiHome uses the MIUIX UI, icons, preferences, navigation, blur, material, glass
and glass-icons modules under Apache 2.0. The current pinned snapshot is
`0.9.4-ca674992-SNAPSHOT`.
The full Apache 2.0 text is bundled as `assets/licenses/Apache-2.0.txt`.

Source: <https://github.com/compose-miuix-ui/miuix/tree/ca674992>

## AndroidX, Compose, Kotlin and supporting libraries

The following resolved component families use Apache 2.0. Their exact module
coordinates and versions are included in the generated dependency inventory
and are shown in their in-App component details:

- AndroidX and the JetBrains AndroidX ports:
  <https://cs.android.com/androidx/platform/frameworks/support>
- Compose Multiplatform: <https://github.com/JetBrains/compose-multiplatform>
- Kotlin: <https://github.com/JetBrains/kotlin>
- Kotlinx coroutines, serialization and immutable collections:
  <https://github.com/Kotlin>
- libxposed framework service and interface:
  <https://github.com/libxposed/service>,
  <https://github.com/libxposed/interface>
- FlatBuffers: <https://github.com/google/flatbuffers>
- Guava ListenableFuture: <https://github.com/google/guava>
- JetBrains Annotations: <https://github.com/JetBrains/java-annotations>
- JSpecify: <https://github.com/jspecify/jspecify>
- Poko annotations: <https://github.com/drewhamilton/Poko>

The libxposed API itself is a compile-only framework contract rather than a
bundled implementation.

## Zipflinger 9.5.1 and Android Tools Annotations 32.5.1

KeiHome uses `com.android:zipflinger:9.5.1` for runtime ZIP archive access and
entry copying. Its runtime dependency is
`com.android.tools:annotations:32.5.1`. Both are Android Open Source Project
components licensed under the Apache License, Version 2.0.

Zipflinger source attribution: Copyright (C) 2019 The Android Open Source Project.
The Android Tools Annotations NOTICE records:
Copyright (c) 2005-2013, The Android Open Source Project.

The full Apache 2.0 license is bundled as `assets/licenses/Apache-2.0.txt`.
The complete, unmodified NOTICE from the Android Tools Annotations 32.5.1 JAR
is retained as `assets/licenses/AndroidTools-NOTICE.txt`. Android Tools
Annotations is attributed separately from JetBrains Annotations.

- Zipflinger source and README:
  <https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-main/zipflinger/README.md>
- Zipflinger publication and runtime dependency declaration:
  <https://dl.google.com/dl/android/maven2/com/android/zipflinger/9.5.1/zipflinger-9.5.1.pom>
- Android Tools Annotations source:
  <https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-main/annotations/>
- Android Tools Annotations publication:
  <https://dl.google.com/dl/android/maven2/com/android/tools/annotations/32.5.1/annotations-32.5.1.pom>

## libc++ build dependency

The resolved native build support includes `dev.rikka.ndk.thirdparty:cxx`.
Its upstream license is Apache 2.0 with LLVM exceptions and retained legacy
license terms, bundled as `assets/licenses/libcxx-LICENSE.txt`. The dependency
inventory records build components; it does not imply a separate libc++ shared
library is packaged in the APK.

Source: <https://github.com/RikkaW/libcxx-prefab>
License: <https://github.com/topjohnwu/libcxx/blob/master/LICENSE.TXT>

## Tencent MMKV 2.4.3

KeiHome uses Tencent MMKV for App-owned preferences. MMKV is distributed under
the BSD 3-Clause License. Its complete upstream license and third-party notices
are included in `app/src/main/assets/licenses/MMKV-BSD-3-Clause.txt`.

Copyright (C) 2018 THL A29 Limited, a Tencent company.
Source: <https://github.com/Tencent/MMKV/tree/v2.4.3>

## DexKit 2.3.0

KeiHome distributes the unmodified `org.luckypray:dexkit:2.3.0` Android
library for bounded runtime DEX contract discovery. DexKit's Java/Kotlin
portions are licensed under the Apache License 2.0. Its native `Core/`
implementation is licensed under the GNU Lesser General Public License 3.0
(LGPL-3.0).

- Exact corresponding source: <https://github.com/LuckyPray/DexKit/tree/2.3.0>
- DexKit license details: <https://github.com/LuckyPray/DexKit/blob/2.3.0/README.md#license>
- Apache License 2.0: `app/src/main/assets/licenses/DexKit-Apache-2.0.txt`
- LGPL-3.0 for DexKit `Core/`: `app/src/main/assets/licenses/DexKit-LGPL-3.0.txt`
- GNU GPL 3.0 incorporated by the LGPL: `app/src/main/assets/licenses/GPL-3.0.txt`
- Source access and rebuilding: `app/src/main/assets/licenses/DexKit-SOURCE.txt`

Copyright (C) 2022 LuckyPray. KeiHome does not modify DexKit. Recipients may
modify DexKit and reverse-engineer KeiHome as needed to debug modifications
to DexKit; no KeiHome distribution term may restrict those rights.

KeiHome APK distributions must provide access to the corresponding DexKit
source and the exact KeiHome source revision, including build scripts,
dependency information, any local changes and any required installation
information for rebuilding with a compatible modified library. The source
access and rebuilding instructions are in `DexKit-SOURCE.txt`. An upstream
link alone does not identify the corresponding KeiHome source for an APK.

The APK packages `META-INF/DEXKIT-NOTICE` and all four files above under
`assets/licenses/`. No DexKit source archive is embedded in the APK; the
APK distribution must supply the matching source access.

## MaterialKolor color utilities

Wallpaper quantization, seed ranking and tonal schemes use MaterialKolor's
`material-color-utilities` 6.0.0, the same engine already used by MIUIX.
No Material UI components are included through this dependency.

Source: <https://github.com/jordond/MaterialKolor/tree/6.0.0>

MaterialKolor's generated color engine is derived from Google's Kotlin
Material Color Utilities under Apache 2.0. The MaterialKolor project itself
is MIT licensed, copyright (c) 2026 Jordon de Hoog. Both texts are included:
`assets/licenses/Apache-2.0.txt` and
`assets/licenses/MaterialKolor-MIT.txt`.

Engine source:
<https://github.com/material-foundation/material-color-utilities/tree/5b3618b16fdc3825e21d5679bafd144662088ea1>

## MIUIX liquid-glass navigation and blur samples

The following files are adapted from the compose-miuix-ui/miuix example app:

- `app/src/main/kotlin/os/kei/hyper/home/ui/components/MiuixSampleChrome.kt`
- `app/src/main/kotlin/os/kei/hyper/home/ui/components/about/*` (background shader and animation)

Source: <https://github.com/compose-miuix-ui/miuix>

Copyright 2025-2026, compose-miuix-ui contributors.
Licensed under the Apache License, Version 2.0. See `LICENSES/Apache-2.0.txt`.

The optional iOS-like navigation retains local liquid-glass navigation,
optics and drag helpers adapted through MIUIX from Kyant0/AndroidLiquidGlass:
<https://github.com/Kyant0/AndroidLiquidGlass>. The files under
`ui/components/liquid/` and `ui/components/animation/` retain their Apache-2.0
headers. Their shader, sensor and edge-light integration uses MIUIX's current
material APIs. The separate OS4 Glass option uses the published MIUIX component.

The OS4 adapters are authored for KeiHome against MIUIX's public Apache-2.0
APIs and current upstream source. MeiloX and InstallerX-Revived were inspected
for usage patterns; their implementation code is not copied, translated or
bundled by this migration. See `docs/miuix-os4-migration.md` for the source
boundary and reproducible migration notes.

## MiuiBackGestureHook native hook infrastructure

The LSPosed native hook backend, madvise hook-page guard, HYOS process
activation, and ARM64 operand decoding / mapped-image resolution approach
under `app/src/main/cpp/` are adapted from
wxxsfxyzm/MiuiBackGestureHook:

Source: <https://github.com/wxxsfxyzm/MiuiBackGestureHook>

Dynamic resolver reference:
`miui-home-hyos-native/runtime_profile_resolver.cpp`. KeiHome adapts the
bounded scan, unique structural candidate and decoded-call-graph mechanism
to its own Dart icon paths; the gesture-specific Rust targets are not used.

Licensed under the Apache License, Version 2.0. See
`LICENSES/Apache-2.0.txt`.

## LSPlt

Earlier native payloads bundled LSPlt 2.1 with 16 KB ELF segment alignment.
The current payload uses the framework hook API and no longer ships that
binary or its public header. Its license is retained for provenance at
`app/src/main/assets/licenses/LSPlt-LGPL-3.0.txt`.

Source: <https://github.com/LSPosed/LSPlt>

Copyright LSPosed contributors. Licensed under the GNU Lesser General Public
License, version 3.0.
