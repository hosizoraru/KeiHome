# KeiHome

[简体中文](README.md) · English · [日本語](README.ja.md)

![KeiHome icon](branding/exports/keihome-light.svg)

KeiHome customizes the launcher and system appearance on Xiaomi HyperOS. It integrates with supported system components through LSPosed, with controls for app icons, Monet colors, dark mode, and the gesture bar.

[Download the stable APK](https://github.com/hosizoraru/KeiHome/releases/latest) · [Release notes](https://github.com/hosizoraru/KeiHome/releases) · [Telegram updates](https://t.me/VoyagerMIUIUpdate) · [Report an issue](https://github.com/hosizoraru/KeiHome/issues)

## Features

- **Icon packs and icon completion.** Browse built-in styles, use Perfect Icons or Apple icon styles, and import APK, MTZ, or ZIP packs. Keep an app's original icon, replace its mapping, or supply a background and foreground for a static layered icon. [Icon customization](icon-customization.md) · [Perfect Icons integration](perfect-icons.md)
- **Monet dynamic color.** Choose a color source, preview light and dark palettes, and opt in on supported icons and system surfaces. [Monet documentation](monet-customization.md)
- **Launcher and system appearance.** Adjust the Dock, launcher icons, launch effects, wallpaper dimming, and system dark styles on supported builds. Icon behavior in app details and notifications, as well as gesture bar integration, depends on the host build and enabled module scope. [Launcher settings](desktop-navigation.md) · [Gesture bar integration](gesture-navigation-backdrop.md)

## Install and activate

The APK targets `arm64-v8a` devices and requires Android API 37 or later. Module features require a working LSPosed environment with modern Xposed API 102 support. Feature availability also depends on the HyperOS, launcher, Themes, and SystemUI builds.

1. Download and install the APK from GitHub Releases.
2. Enable KeiHome in LSPosed Manager and use the setup guide to choose the scopes for the features you want.
3. On first activation, reboot the device, unlock it, return to the system launcher, and check the **Status** page in KeiHome.
4. Preview an icon pack, generate or update it as prompted, and apply it in Themes. Update and reapply the pack after changing its icon mappings.

Follow the release notes when upgrading an installed copy. Updates containing native library changes require a reboot. You can reopen the setup guide from **Settings → Release notes**.

## Develop and report issues

KeiHome uses Kotlin, Jetpack Compose, and MIUIX. Follow the [app build guide](../app/README.md) to set up the SDK and run `./gradlew :app:assembleDebug`.

| Topic | Documentation |
| --- | --- |
| Code and build ownership | [Project structure](project-structure.md) · [Build logic](../build-logic/README.md) |
| Tests and CI | [Local scripts](../scripts/README.md) · [Testing guide](testing.md) · [GitHub Actions](ci.md) |
| UI and icon rendering | [MIUIX OS4 / Glass](miuix/README.md) · [Icon rendering contracts](icon-rendering-contracts.md) |
| Code and translation contributions | [Contributing](../CONTRIBUTING.md) · [Localization](localization.md) |

When reporting a problem, include the full KeiHome version, your HyperOS and launcher builds, steps to reproduce, and screenshots. Include LSPosed logs for module problems such as icons, launcher, or notifications, and app logs for the KeiHome UI.

## License and acknowledgements

KeiHome project code uses the [Apache License 2.0](../LICENSE). Third-party components, icons, and artwork retain their respective terms. See the [project notice](../NOTICE) and [third-party notices](../THIRD_PARTY_NOTICES.md).

Thanks to [MIUIX](https://github.com/compose-miuix-ui/miuix), the [Perfect Icons Completion Project](https://github.com/pzcn/Perfect-Icons-Completion-Project), and other upstream projects. Thanks to [@jizizr](https://github.com/jizizr) for static light and dark icon support and batched launcher icon refreshes.
