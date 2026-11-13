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
- Preserve custom splash icons, branding images, complex resource
  backgrounds, explicitly colored splash backgrounds, legacy type-4 artwork,
  and supplied quick-start snapshot descriptors.
- Adapt the native background result after its cache lookup, before the native
  builder derives icon contrast and system-bar appearance.
- Also adapt an eligible solid-color type-6 overlay or the full-size, opaque
  first `ColorDrawable` backing layer of a `LayerDrawable`. Clone the drawable
  rather than mutate an application/shared resource. Subsequent artwork layers
  and their geometry are retained.
- Preserve bitmap and quick-start preview ownership. No image is sampled or
  recolored by this feature. An inset, sized, filtered or translucent base does
  not qualify as a solid backing layer.

The profile resolves the exact installed Shell method/field contract. Hook work
runs on the existing splash worker and uses a scoped thread-local request for
both the earlier background estimate and the final splash construction. Each
request captures its own native attributes instead of relying on a shared
temporary attribute object. Background eligibility is resolved once per
captured attribute set.
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

## November 13 first-frame correction

The authorized physical phone's Bilibili `windowBackground` was an opaque white
`ColorDrawable` followed by a branding `BitmapDrawable` inside a `LayerDrawable`.
The modern splash-background attribute was transparent. The previous blanket
drawable protection rejected that resource. A cold-launch recording showed a
white system `Splash Screen tv.danmaku.bili` until the application's first frame,
approximately one second later. The user recording also showed a white surface
followed by a later dark surface.

Custom icons and branding no longer exempt a separate eligible backing color.
The drawable adapter clones the source's existing constant state without
reapplying resource density, then changes only that backing color. The source,
shared copies, overlaid artwork and presentation properties remain unchanged.
This does not change application windows, ads, snapshots or first-frame timing.

Targeted validation:

- Both existing policy JVM tests pass.
- Nine Android device tests pass, covering rendered branding pixels, unchanged
  source/shared copies, density/padding/geometry, tint/filter and unsupported
  drawable rejection.
- The first device run caught a genuine density/padding regression in cloning;
  that implementation was corrected before the passing rerun.
- Ordinary API 102 updates to diagnostic builds `109000439` and `109000440`
  passed on the unlocked Phone AVD with unchanged critical process identities.
- The physical diagnostic cold launch showed a dark system starting surface
  with the original Bilibili branding, replacing the previous white surface.
  This is not a claim that application-owned pages cannot independently draw
  white.

Evidence: `.artifacts/research/splash-first-frame-20261113/`. Final Release and
installation identities are recorded separately there.
