package os.kei.buildlogic.assets

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

internal fun Project.configureGeneratedAssets() {
  // Firmware-derived profiles are optional inputs supplied outside source control.
  val generatedAssetsOverride = providers.gradleProperty("keihome.generatedAssetsDir")
  val generatedAssets = rootProject.file(generatedAssetsOverride.getOrElse(".artifacts/generated/assets"))
  val builtinAssets = tasks.register("prepareBuiltinIconStore", PrepareBuiltinIconStore::class.java) { task ->
    task.requirePacks.set(generatedAssetsOverride.map { true }.orElse(false))
    task.packs.from(rootProject.fileTree(generatedAssets.resolve("builtin-icon-packs")) { tree ->
      tree.include("*.zip")
    })
    task.otherAssets.from(rootProject.fileTree(generatedAssets) { tree ->
      tree.exclude("builtin-icon-packs/**")
    })
    task.generator.set(rootProject.layout.projectDirectory.file("scripts/pack-builtin-icons.py"))
    task.outputDirectory.set(layout.buildDirectory.dir("generated/assets/shared-builtins"))
  }
  val noticeAssets = tasks.register("prepareThirdPartyNotices", PrepareThirdPartyNotices::class.java) { task ->
    task.notice.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
    task.apacheLicense.set(rootProject.layout.projectDirectory.file("LICENSES/Apache-2.0.txt"))
    task.outputDirectory.set(layout.buildDirectory.dir("generated/assets/third-party-notices"))
  }
  extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
    val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
    val dependencyVersions = tasks.register(
      "prepare${variantName}DependencyVersions",
      PrepareDependencyVersions::class.java,
    ) { task ->
      task.modules.set(configurations.named("${variant.name}RuntimeClasspath").map { configuration ->
        configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
          (component.id as? ModuleComponentIdentifier)?.let {
            "${it.group}\t${it.module}\t${it.version}"
          }
        }
      })
      task.outputDirectory.set(layout.buildDirectory.dir("generated/dependency-notices/${variant.name}/assets"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(dependencyVersions, PrepareDependencyVersions::outputDirectory)
    variant.sources.assets?.addGeneratedSourceDirectory(noticeAssets, PrepareThirdPartyNotices::outputDirectory)
    variant.sources.assets?.addGeneratedSourceDirectory(builtinAssets, PrepareBuiltinIconStore::outputDirectory)
    val verify = tasks.register("verify${variantName}BuiltinIconStore", VerifyBuiltinIconStore::class.java) { task ->
      task.apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
      task.generatedAssets.set(builtinAssets.flatMap(PrepareBuiltinIconStore::outputDirectory))
    }
    tasks.matching { task -> task.name == "assemble$variantName" }.configureEach { task ->
      task.dependsOn(verify)
    }
  }
}
