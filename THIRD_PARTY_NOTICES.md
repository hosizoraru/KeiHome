# Third-party notices

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

The LSPosed native hook backend, madvise hook-page guard, and HYOS process
activation approach under `app/src/main/cpp/` are adapted from
wxxsfxyzm/MiuiBackGestureHook:

Source: <https://github.com/wxxsfxyzm/MiuiBackGestureHook>

Licensed under the Apache License, Version 2.0. See
`LICENSES/Apache-2.0.txt`.

## LSPlt

The bundled `app/src/main/jniLibs/arm64-v8a/liblsplt.so` is LSPlt 2.1 rebuilt
with 16 KB ELF segment alignment. The public header and license are included
under `app/src/main/cpp/third_party/lsplt/` and
`app/src/main/assets/licenses/LSPlt-LGPL-3.0.txt`.

Source: <https://github.com/LSPosed/LSPlt>

Copyright LSPosed contributors. Licensed under the GNU Lesser General Public
License, version 3.0.
