# KeiHome Android app

The `:app` module contains the Compose settings app, icon-pack tooling, and the Kotlin and native integrations loaded by LSPosed.

| Area | Entry point |
| --- | --- |
| App navigation and feature UI | [`ui/`](src/main/kotlin/os/kei/hyper/home/ui/) |
| Icon import, preview, and generation | [Icon-pack integration](src/main/kotlin/os/kei/hyper/home/iconpack/README.md) |
| Host integration and scope | [`xposed/`](src/main/kotlin/os/kei/hyper/home/xposed/) · [Module metadata](src/main/resources/META-INF/xposed/) |
| JNI and native runtime | [`src/main/cpp/`](src/main/cpp/) |
| JVM and Android tests | [`src/test/`](src/test/) · [`src/androidTest/`](src/androidTest/) |

## Build locally

Use JDK 21, Python 3, and the Android SDK packages below. Set `ANDROID_HOME` to the SDK directory or set `sdk.dir` in the repository's local `local.properties`.

```sh
sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;37.0.0" \
  "ndk;30.0.16248370" "cmake;4.1.2"
```

From the repository root, run:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The Debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Official packages are available in [GitHub Releases](https://github.com/hosizoraru/KeiHome/releases). See [versioning and signing](../docs/versioning.md) before using a locally built APK to update a device.

[Project overview](../docs/README.md) · [Project structure](../docs/project-structure.md) · [Testing guide](../docs/testing.md)
