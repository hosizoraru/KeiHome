# App splash screen

KeiHome uses the platform SplashScreen on its existing launcher Activity
(minimum SDK 35). There is no extra Activity, artificial minimum duration,
startup animation dependency or wait for framework/status queries.

## Appearance and handoff

- Both theme variants use the adaptive brand icon, retaining the background
  behind the white tile in the Kei sakura palette.
- Startup uses pale sakura `#FFEDF5` in light mode and dark rose `#26151F`
  in dark mode. `windowBackground` and `windowSplashScreenBackground` share
  this palette so HyperOS's legacy backing layer cannot cover the tint.
- `AppLaunchAppearance` synchronizes the saved appearance using the public
  `UiModeManager.setApplicationNightMode` API. Android persists the app-local
  configuration for subsequent starting windows, before the process exists.
  It does not change the system-wide appearance or injected host processes.
- Initialization reads only the display store's appearance key. Compose
  applies subsequent setting changes; an enum-only process cache avoids
  sending the same request twice during Application/Activity startup.
- `MODE_NIGHT_AUTO` clears the app-local override when following the system,
  including scheduled system appearance changes. It does not implement a
  second day/night scheduler.
- Inside the MIUIX theme provider, the Activity window background follows the
  actual surface, including Monet. This avoids exposing an unrelated window
  color during the handoff. The pre-process splash uses the static brand
  palette; it does not synchronously calculate a Monet palette. In-app
  surfaces and accent colors continue to use the user's chosen theme.

On the first run after upgrading from a version without synchronization, the
system may already have created the starting window before the saved choice
can be migrated. The persisted override controls subsequent cold starts.

## Verification

Original appearance synchronization evidence:
`.artifacts/research/app-splash-20261114/`.
Sakura icon and full startup background verification:
`.artifacts/research/sakura-app-icon-20261115/`.

The signed, R8-optimized diagnostic was tested on the unlocked HyperOS Phone
AVD with the real launcher and system transition. Cold-start recordings cover:

| App appearance | System appearance | Observed splash and first page |
| --- | --- | --- |
| Follow system | Dark | Dark |
| Light | Dark | Light |
| Dark | Light | Dark |
| Follow system | Light | Light |

The brand is visible in the starting window and the recordings include its
handoff to the real status page. No test-only delay was introduced. Android's
persisted package configuration uses night mode 16 for light, 32 for dark,
and is removed when returning to the system setting.

The App selection and system mode were restored to their initial values:
follow system and dark. Recordings are functional evidence, not a cold-start
performance benchmark. Release/R8 and targeted Lint validation are retained
alongside the deployment and final APK identity receipts.

No native payload, injected Hook, scope, version prefix, public changelog or
release tag changes are part of this work.

## Platform references

- [Splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen)
- [App dark theme](https://developer.android.com/develop/ui/views/theming/darktheme)
- [UiModeManagerService app-local mode implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/UiModeManagerService.java)
