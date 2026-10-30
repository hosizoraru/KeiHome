# Third-party notices

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
