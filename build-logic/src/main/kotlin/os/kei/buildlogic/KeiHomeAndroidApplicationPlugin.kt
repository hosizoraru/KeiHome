package os.kei.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

class KeiHomeAndroidApplicationPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("com.android.application")
    project.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    project.pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

    val android = project.extensions.getByType(ApplicationExtension::class.java)
    with(android) {
      namespace = "os.kei.hyper.home"
      compileSdk = 37
      ndkVersion = "30.0.16248370"

      // Each AVD installation must identify a distinct LSPosed module generation.
      val generatedVersionCode = (System.currentTimeMillis() / 1000L).toInt()

      defaultConfig {
        applicationId = "os.kei.hyper.home"
        minSdk = 37
        targetSdk = 37
        versionCode = generatedVersionCode
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
          abiFilters += "arm64-v8a"
        }
      }

      buildTypes {
        release {
          // No distribution keystore is part of the repository. Use the
          // machine-local debug key so the private release artifact can be
          // installed over the AVD/debug build for device testing.
          signingConfig = signingConfigs.getByName("debug")
          isMinifyEnabled = true
          isShrinkResources = true
          proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            project.file("proguard-rules.keep"),
          )
        }
      }

      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
      }

      buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        prefab = true
        shaders = false
      }

      packaging {
        jniLibs {
          excludes += "**/libandroidx.graphics.path.so"
          excludes += "**/liblsplt.so"
          useLegacyPackaging = false
        }
        resources {
          excludes += "/META-INF/{AL2.0,LGPL2.1}"
          merges += "META-INF/xposed/*"
        }
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
      task.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
  }
}
