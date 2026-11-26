plugins {
  id("keihome.android.application")
}

// MMKV is used through JNI. This payload imports no C++ Prefab packages.
android.buildFeatures.prefab = false

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  implementation(libs.androidx.compose.animation)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.runtime)
  constraints {
    // Upgrade libraries already present in the graph without adding unused components.
    libs.bundles.runtime.alignment.get().forEach { implementation(it) }
  }
  androidTestImplementation(composeBom)

  compileOnly(libs.libxposed.api)
  implementation(libs.libxposed.service)
  implementation(libs.dexkit)
  // Same pure color engine already used by MIUIX; also needed on our compile classpath.
  implementation(libs.material.color.utilities)
  implementation(libs.mmkv)
  implementation(libs.zipflinger)
  implementation(libs.commonmark)
  implementation(libs.jgit.core)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.navigationevent.compose)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.androidx.startup.runtime)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.bundles.miuix)
  implementation(libs.lucide.icons)
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
