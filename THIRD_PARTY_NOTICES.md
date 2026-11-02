# Third-party notices

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
`material-color-utilities` 5.0.1, the same engine already used by MIUIX.
No Material UI components are included through this dependency.

Source: <https://github.com/jordond/MaterialKolor/tree/5.0.1>

Copyright (c) 2025 Jordon de Hoog. Licensed under the MIT License; the full
notice is included in `app/src/main/assets/licenses/MaterialKolor-MIT.txt`.

## MIUIX liquid-glass navigation and blur samples

The following files are adapted from the compose-miuix-ui/miuix example app:

- `app/src/main/kotlin/os/kei/hyper/home/ui/components/MiuixSampleChrome.kt`
- `app/src/main/kotlin/os/kei/hyper/home/ui/components/liquid/*`
- `app/src/main/kotlin/os/kei/hyper/home/ui/components/animation/*`
- `app/src/main/kotlin/os/kei/hyper/home/ui/components/about/*` (background shader and animation)

Source: <https://github.com/compose-miuix-ui/miuix>

Copyright 2025-2026, compose-miuix-ui contributors.
Licensed under the Apache License, Version 2.0. See `LICENSES/Apache-2.0.txt`.

The liquid-glass navigation implementation is adapted by MIUIX from
Kyant0/AndroidLiquidGlass: <https://github.com/Kyant0/AndroidLiquidGlass>.

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
