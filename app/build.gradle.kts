plugins {
  id("keihome.android.application")
}

abstract class PrepareThirdPartyNotices : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val notice: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val apacheLicense: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun prepare() {
    val output = outputDirectory.file("THIRD_PARTY_NOTICES.md").get().asFile
    output.parentFile.mkdirs()
    notice.get().asFile.copyTo(output, overwrite = true)
    outputDirectory.file("licenses/Apache-2.0.txt").get().asFile.apply {
      parentFile.mkdirs()
      apacheLicense.get().asFile.copyTo(this, overwrite = true)
    }
  }
}

abstract class PrepareDependencyVersions : DefaultTask() {
  @get:Input
  abstract val modules: ListProperty<String>

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun prepare() {
    val output = outputDirectory.file("third-party-dependencies.tsv").get().asFile
    output.parentFile.mkdirs()
    output.writeText(modules.get().distinct().sorted().joinToString("\n", postfix = "\n"))
  }
}

// Local firmware-derived profiles are optional build inputs, never checked into source control.
// Each object already has its own DEFLATE stream. Keep offsets seekable inside the APK.
android.androidResources.noCompress += "kicons"

abstract class PrepareBuiltinIconStore @javax.inject.Inject constructor(
  private val execution: org.gradle.process.ExecOperations,
  private val fileSystem: org.gradle.api.file.FileSystemOperations,
) : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val packs: ConfigurableFileCollection
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val otherAssets: ConfigurableFileCollection
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val generator: RegularFileProperty
  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun prepare() {
    fileSystem.sync {
      from(otherAssets.asFileTree)
      into(outputDirectory)
    }
    if (packs.isEmpty) return // Public builds without optional OEM inputs do not need Python.
    execution.exec {
      commandLine(listOf("python3", generator.get().asFile.absolutePath, "--output",
        outputDirectory.dir("builtin-icon-store").get().asFile.absolutePath) +
        packs.files.sortedBy { it.name }.flatMap { listOf("--pack", it.absolutePath) })
    }
  }
}

val builtinAssets = tasks.register<PrepareBuiltinIconStore>("prepareBuiltinIconStore") {
  packs.from(rootProject.fileTree(".artifacts/generated/assets/builtin-icon-packs") { include("*.zip") })
  otherAssets.from(rootProject.fileTree(".artifacts/generated/assets") { exclude("builtin-icon-packs/**") })
  generator.set(rootProject.layout.projectDirectory.file("scripts/pack-builtin-icons.py"))
  outputDirectory.set(layout.buildDirectory.dir("generated/assets/shared-builtins"))
}
val noticeAssets = tasks.register<PrepareThirdPartyNotices>("prepareThirdPartyNotices") {
  notice.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
  apacheLicense.set(rootProject.layout.projectDirectory.file("LICENSES/Apache-2.0.txt"))
  outputDirectory.set(layout.buildDirectory.dir("generated/assets/third-party-notices"))
}
androidComponents.onVariants { variant ->
  val dependencyVersions = tasks.register<PrepareDependencyVersions>(
    "prepare${variant.name.replaceFirstChar(Char::uppercaseChar)}DependencyVersions",
  ) {
    modules.set(configurations.named("${variant.name}RuntimeClasspath").map { configuration ->
      configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
        (component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let {
          "${it.group}\t${it.module}\t${it.version}"
        }
      }
    })
    outputDirectory.set(layout.buildDirectory.dir("generated/dependency-notices/${variant.name}/assets"))
  }
  variant.sources.assets?.addGeneratedSourceDirectory(dependencyVersions, PrepareDependencyVersions::outputDirectory)
  variant.sources.assets?.addGeneratedSourceDirectory(noticeAssets, PrepareThirdPartyNotices::outputDirectory)
  variant.sources.assets?.addGeneratedSourceDirectory(builtinAssets, PrepareBuiltinIconStore::outputDirectory)
}
// MMKV is used through JNI. This payload imports no C++ Prefab packages.
android.buildFeatures.prefab = false

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  compileOnly(libs.libxposed.api)
  implementation(libs.libxposed.service)
  implementation(libs.dexkit)
  // Same pure color engine already used by MIUIX; also needed on our compile classpath.
  implementation(libs.material.color.utilities)
  implementation(libs.mmkv)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.androidx.startup.runtime)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.bundles.miuix)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.jvm.json)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.libxposed.api)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

}
