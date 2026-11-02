# ColorOS-style splash background adaptation

This feature changes eligible system-owned starting surfaces. It does not change
an application's content window, advertisements, first-frame timing, task
snapshot lifecycle or exit animation.

## Exact source and target

| Input | Live path | SHA-256 |
| --- | --- | --- |
| ColorOS System UI | `/system_ext/priv-app/SystemUI/SystemUI.apk` | `0ae85a1e05e8b6a01b0d24d8eb7bbe71873ceac5c1158f908b7f0ccec92711ec` |
| ColorOS window services | `/system/framework/oplus-services.jar` | `d721ac90f896dafb92f0e5db8b5c52d15681614a75b78537c4c590c882abcb56` |
| HyperOS Shell | `/system_ext/framework/Miui-WindowManager-Shell.jar` | `fdc3f1bbec45673489c7ea3c16a1a9a28ece45eb9eae6d257d2667f207c46e24` |

Live hashes matched the pulled inputs. Sources, source proof, screenshots and
logs are under `.artifacts/research/splash/`.

`OplusShellStartingWindowManager` treats selected system/exception/default
starting windows separately from ordinary third-party custom splash artwork.
Its handling distinguishes solid colors, state lists, bitmap/layer backgrounds
and cached previews. Effective night mode accounts for the task configuration
and OEM force-light, force-night and no-night-support flags.

`OplusShellStartingWindowUtils.makeColorBackground` converts the background to
CIELAB. It leaves L <= 50 unchanged and reflects brighter L around 50, preserving
the chromatic coordinates and alpha. The running reference reports
`mDarkModeBackgroundMaxL=0.0`; other ColorOS darkening levels can modify the
reflection floor. This port uses the reference's zero-floor case.

HyperOS already supplies default night backgrounds, configuration-keyed color
caching, force-invert handling and native contrast-dependent system bars.
However, `TaskSnapshotHelperImpl.modifySplashIfNeeded` can return type 6 with a
`windowBackground` overlay. Updating only the modern splash color would leave
that overlay visible.

## Ported behavior

Settings -> App launch -> **Adapt splash backgrounds**, enabled by default,
persists through API 102 remote preferences. Turning it off takes effect on the
next cold launch without a process restart or color-cache rewrite.

- Process eligible bright, opaque, nearly neutral colors only. The additional
  KeiHome guard limits RGB channel spread to 16, preserving colored branding.
- Follow the application's effective Android resource night configuration,
  including an application-level forced-light override.
- Preserve custom splash icons, branding images, non-color resource
  backgrounds, explicitly colored splash backgrounds, legacy type-4 artwork,
  and supplied quick-start snapshot descriptors.
- Adapt the native background result after its cache lookup, before the native
  builder derives icon contrast and system-bar appearance.
- Also adapt an eligible solid-color type-6 overlay. Replace the `ColorDrawable`
  rather than mutate an application/shared drawable.
- Preserve bitmap, layered and quick-start preview ownership. No image is
  sampled or recolored by this feature.

The profile resolves the exact installed Shell method/field contract. Hook work
runs on the existing splash worker and uses a scoped thread-local request.
There is no new timer, animation, worker loop, package scan or file I/O in the
hook. Exceptions in optional color adaptation retain the native result. Hook
leases and remote listeners participate in the existing hot-reload retirement.

This is deliberately narrower than copying ColorOS's private cloud lists,
preview service and whole starting-window stack. A library-local light/dark
choice that an app never reports through Android configuration cannot be
inferred from its future pixels.

## Validation

`prepare-splash-fixtures.py` builds a permission-free fixture with controlled
themes and a delayed first draw for capture. The 1.6-second hold belongs only to
the disposable fixture, never to the module.

The same fixed neutral background changed from `#F5F5F5` to `#0C0C0C` on HyperOS
in night mode, with readable native system bars. Pure JVM tests cover the LAB
reflection, idempotence and day/dark/brand/alpha/artwork exclusions. Device
captures cover the preference toggle, both system modes and the fixture's
night-aware, branded, custom-icon and artwork cases. After the application-level
night override settled, five forced-light capture points were identical with the
feature on and off. The initial sample during configuration transition is kept
separately in the evidence rather than counted as cold-start acceptance. Source AVD calculator
launch captures also show the reference's dark native transition.

The [Android splash-screen contract](https://developer.android.com/develop/ui/views/launch/splash-screen)
remains responsible for declared icons, backgrounds and app-controlled exit
animation. Apps should still provide their own night resources.
