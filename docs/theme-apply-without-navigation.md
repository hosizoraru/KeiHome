# Applying icons without foreground navigation: source investigation

Research checkpoint: 2026-11-09. Scope is icon modules only, preserving wallpapers, lock screen, fonts, sounds and other theme selections. No replacement runtime apply path has been enabled in this checkpoint.

## ThemeStore protocol

The inspected revision of [ThemeStore](https://github.com/wxxsfxyzm/ThemeStore) is `12657104577b543f1bb39eb45b6b662805ba6b03`. Its installer starts the system theme application's `com.android.thememanager.ApplyThemeForScreenshot` Activity and supplies an archive path plus component flags; its flags define icons as `0x10`. The normal path places the archive in a theme-manager-readable staging location. The Shizuku path copies into the theme-manager private staging area and uses privileged activity launch. These are host Activity protocol facts, not a generic public Android icon-application API.

The accessibility service maintains status/result notification handling; the inspected service does not establish a generic UI-click automation protocol. Starting a special Activity is also different from directly calling a service with no Activity; user-visible behavior depends on the installed host implementation.

ThemeStore currently declares AGPL-3.0. KeiHome is investigating the host interface and observed behavior only. No ThemeStore implementation or dependency is incorporated.

## Current HyperOS 4 evidence

The retained Theme applications `Themes-11540.apk`, `Themes-11541.apk`, `Themes-11560.apk` and `Themes-11600.apk` each contain six DEX files. All four lack the `ApplyThemeForScreenshot` Activity declaration and class descriptor, and the `theme_file_path` / `theme_apply_flags` DEX keys used by that protocol. Their decoded manifests for 11540–11560 are retained in `.artifacts/research/dynamic-layer-apply-20261109/themestore/`. The Phone AVD ran 11.5.6.0 (versionCode 11560) at that checkpoint, and 11.6.0.0 (11600) was confirmed by DexSQL on 2026-11-28. Copying the external Intent into KeiHome would therefore not provide a verified path on HyperOS 4.

KeiHome's existing DexKit-resolved contract instead locates `ResourceApplyHelper`, `ApplyThemeInfo` and `ThemeApplyUtils` through signatures and relationships. Inspection of 11.5.6.0 shows the selected path eventually constructs an `ApplyThemeTask` and executes it on the host executor. This is a useful future entry point to investigate. It does not yet prove a complete cold-start, no-Activity, icon-only apply contract. The current adapter intentionally launches the native detail page, observes its resource, and invokes its normal apply path.

The external protocol's component bit mask must not be confused with the current adapter's resource-type identifiers. Any independent headless adapter must prove resource/context construction, import readiness, icon-only selection, native success/failure delivery, required UI conditions and cancellation/hot-reload retirement. It also needs a cold-process starting route; a receiver dynamically registered in an already running theme process is insufficient. Host-side current-APK contracts, rather than OEM version gates, must decide availability. The existing UI path remains the fallback until those properties are verified.

No Activity bypass, hidden full-theme application, direct protected-theme-file replacement or additional Root/Shizuku dependency was added by this investigation.
