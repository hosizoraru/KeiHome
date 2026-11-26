package os.kei.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import os.kei.buildlogic.assets.configureGeneratedAssets

class KeiHomeAndroidApplicationPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("com.android.application")
    project.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    project.pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

    if (project.providers.gradleProperty("composeCompilerReports").orNull == "true") {
      project.extensions.configure(ComposeCompilerGradlePluginExtension::class.java) { compiler ->
        compiler.reportsDestination.set(project.layout.buildDirectory.dir("compose_reports"))
        compiler.metricsDestination.set(project.layout.buildDirectory.dir("compose_reports"))
      }
    }

    val android = project.extensions.getByType(ApplicationExtension::class.java)
    with(android) {
      namespace = "os.kei.hyper.home"
      compileSdk = 37
      compileSdkMinor = 2
      // Resolve the pinned NDK from the configured Android SDK on each machine.
      ndkVersion = "30.0.16248370"

      val generatedVersion = KeiHomeVersioning.resolve(project)

      defaultConfig {
        applicationId = "os.kei.hyper.home"
        minSdk = 37
        targetSdk = 37
        versionCode = generatedVersion.code
        versionName = generatedVersion.name
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
          abiFilters += "arm64-v8a"
        }
      }

      val releaseSigningFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
      val releaseSigningEnvironment = listOf(
        "KEIHOME_RELEASE_STORE_FILE", "KEIHOME_RELEASE_STORE_PASSWORD",
        "KEIHOME_RELEASE_KEY_ALIAS", "KEIHOME_RELEASE_KEY_PASSWORD",
      )
      val releaseSigning = releaseSigningFields.zip(releaseSigningEnvironment).map { (field, env) ->
        project.providers.gradleProperty("keihome.release.$field")
          .orElse(project.providers.environmentVariable(env)).orNull
      }
      val hasReleaseSigning = releaseSigning.all { !it.isNullOrEmpty() }
      val requireReleaseSigning =
        project.providers.gradleProperty("keihome.requireReleaseSigning").orNull == "true"
      if (!hasReleaseSigning && (requireReleaseSigning || releaseSigning.any { it != null })) {
        throw GradleException("Complete KeiHome Release signing credentials are required; see docs/versioning.md")
      }
      val releaseSigner = if (hasReleaseSigning) {
        signingConfigs.create("keiosRelease").apply {
          storeFile = project.rootProject.file(releaseSigning[0]!!)
          storePassword = releaseSigning[1]
          keyAlias = releaseSigning[2]
          keyPassword = releaseSigning[3]
        }
      } else {
        signingConfigs.getByName("debug")
      }

      buildTypes {
        release {
          // Private AVD builds retain their development identity unless formal credentials are supplied.
          signingConfig = releaseSigner
          isMinifyEnabled = true
          isShrinkResources = true
          proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            project.file("proguard-rules.keep"),
          )
        }
      }

      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
      }

      buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        prefab = true
        shaders = false
      }

      packaging {
        jniLibs {
          excludes += "**/libandroidx.graphics.path.so"
          useLegacyPackaging = false
        }
        resources {
          excludes += "/META-INF/{AL2.0,LGPL2.1}"
          merges += "META-INF/xposed/*"
        }
      }

      externalNativeBuild {
        cmake {
          path = project.file("src/main/cpp/CMakeLists.txt")
          version = "4.1.2"
        }
      }
    }

    project.configureGeneratedAssets()

    project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
      variant.androidTest?.let { test ->
        val task = project.tasks.register(
          "${variant.name}TestApkVersion", TestApkVersionTask::class.java,
        ) { stamp ->
          stamp.versionCode.set(android.defaultConfig.versionCode!!)
        }
        test.artifacts.use(task).wiredWithFiles(
          TestApkVersionTask::inputManifest, TestApkVersionTask::outputManifest,
        ).toTransform(SingleArtifact.MERGED_MANIFEST)
      }
    }

    project.configurations.all { configuration ->
      configuration.exclude(
        mapOf(
          "group" to "org.jetbrains.compose.material3",
          "module" to "material3-window-size-class",
        ),
      )
      configuration.exclude(
        mapOf(
          "group" to "org.jetbrains.compose.material3",
          "module" to "material3-window-size-class-android",
        ),
      )
    }

    project.tasks.withType(KotlinJvmCompile::class.java).configureEach { task ->
      task.compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
  }
}
