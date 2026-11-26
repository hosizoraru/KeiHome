# Build logic

This Gradle included build owns KeiHome's Android application convention. It configures SDK and native toolchains, Kotlin and Compose, versioning, signing, and generated assets.

- The [root settings](../settings.gradle.kts) include this build in `pluginManagement`. The app applies its convention in [`app/build.gradle.kts`](../app/build.gradle.kts).
- The [application convention](src/main/kotlin/os/kei/buildlogic/KeiHomeAndroidApplicationPlugin.kt) owns common Android build configuration. The [asset tasks](src/main/kotlin/os/kei/buildlogic/assets/) register generated output through Android Components.
- App dependencies are declared in the app's build file using the [version catalog](../gradle/libs.versions.toml). The included build declares its own Gradle plugin dependencies in [`build.gradle.kts`](build.gradle.kts).

Run the included build's tests and plugin validation from the repository root.

```sh
./gradlew :build-logic:check
```

See [build versions and signing](../docs/versioning.md), [built-in icon storage](../docs/builtin-icon-storage.md), and [CI](../docs/ci.md).
