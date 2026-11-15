package os.kei.hyper.home.ui.main

import androidx.annotation.StringRes
import os.kei.hyper.home.R

internal data class BundledDependency(val group: String, val module: String, val version: String) {
  val coordinate: String get() = "$group:$module:$version"
}

internal fun parseBundledDependencies(text: String): List<BundledDependency> =
  text.lineSequence().filter(String::isNotBlank).map { line ->
    val fields = line.split('\t')
    require(fields.size == 3 && fields.all { it.isNotBlank() }) { "Invalid dependency metadata" }
    BundledDependency(fields[0], fields[1], fields[2])
  }.toList().also { dependencies ->
    require(dependencies.isNotEmpty())
    require(dependencies.distinctBy { it.group to it.module }.size == dependencies.size)
  }

internal enum class AttributionKind { DEPENDENCY, SOURCE, HISTORY }

internal enum class NoticeCategory(val key: String, @param:StringRes val title: Int) {
  PRESENTATION("presentation", R.string.project_license_category_presentation),
  SYSTEM("system", R.string.project_license_category_system),
  IMAGES("images", R.string.project_license_category_images),
  DATA("data", R.string.project_license_category_data),
  RUNTIME("runtime", R.string.project_license_category_runtime),
  REFERENCES("references", R.string.project_license_category_references),
}

internal fun noticeCategoryForRoute(route: String): NoticeCategory? =
  NoticeCategory.entries.firstOrNull { route == "notices/category/${it.key}" }

internal data class ThirdPartyComponent(
  val key: String,
  val name: String,
  @param:StringRes val description: Int,
  val source: String,
  val licenseKeys: List<String> = listOf("apache"),
  val groups: List<String> = emptyList(),
  val kind: AttributionKind = AttributionKind.DEPENDENCY,
  val category: NoticeCategory,
) {
  fun contains(dependency: BundledDependency): Boolean =
    groups.any { prefix ->
      if (prefix.endsWith('.')) dependency.group.startsWith(prefix) else dependency.group == prefix
    }

  fun dependencies(all: List<BundledDependency>): List<BundledDependency> =
    all.filter(::contains).sortedBy(BundledDependency::coordinate)
}

internal val thirdPartyComponents = listOf(
  ThirdPartyComponent("lucide", "Lucide Icons", R.string.notices_role_icons,
    "https://github.com/composablehorizons/compose-icons", listOf("compose-icons-mit", "lucide-isc"),
    listOf("com.composables"), category = NoticeCategory.IMAGES),
  ThirdPartyComponent("miuix", "MIUIX", R.string.notices_role_miuix,
    "https://github.com/compose-miuix-ui/miuix", groups = listOf("top.yukonga.miuix.kmp"),
    category = NoticeCategory.PRESENTATION),
  ThirdPartyComponent("androidx", "AndroidX", R.string.notices_role_android,
    "https://cs.android.com/androidx/platform/frameworks/support",
    groups = listOf("androidx.", "org.jetbrains.androidx."), category = NoticeCategory.PRESENTATION),
  ThirdPartyComponent("compose", "Compose Multiplatform", R.string.notices_role_compose,
    "https://github.com/JetBrains/compose-multiplatform", groups = listOf("org.jetbrains.compose."),
    category = NoticeCategory.PRESENTATION),
  ThirdPartyComponent("kotlin", "Kotlin", R.string.notices_role_kotlin,
    "https://github.com/JetBrains/kotlin", groups = listOf("org.jetbrains.kotlin"), category = NoticeCategory.RUNTIME),
  ThirdPartyComponent("kotlinx", "Kotlinx", R.string.notices_role_kotlinx,
    "https://github.com/Kotlin", groups = listOf("org.jetbrains.kotlinx"), category = NoticeCategory.RUNTIME),
  ThirdPartyComponent("mmkv", "Tencent MMKV", R.string.notices_role_mmkv,
    "https://github.com/Tencent/MMKV", listOf("mmkv"), listOf("com.tencent"), category = NoticeCategory.DATA),
  ThirdPartyComponent("commonmark", "CommonMark", R.string.notices_role_markdown,
    "https://github.com/commonmark/commonmark-java", listOf("commonmark"), listOf("org.commonmark"),
    category = NoticeCategory.DATA),
  ThirdPartyComponent("jgit", "Eclipse JGit", R.string.notices_role_git,
    "https://eclipse.dev/jgit/", listOf("jgit"), listOf("org.eclipse.jgit"), category = NoticeCategory.DATA),
  ThirdPartyComponent("javaewah", "JavaEWAH", R.string.notices_role_git_bitmap,
    "https://github.com/lemire/javaewah", groups = listOf("com.googlecode.javaewah"), category = NoticeCategory.DATA),
  ThirdPartyComponent("slf4j", "SLF4J", R.string.notices_role_logging,
    "https://www.slf4j.org/", listOf("slf4j"), listOf("org.slf4j"), category = NoticeCategory.RUNTIME),
  ThirdPartyComponent("commons-codec", "Apache Commons Codec", R.string.notices_role_encoding,
    "https://commons.apache.org/proper/commons-codec/", listOf("apache", "commons-codec-notice"),
    listOf("commons-codec"), category = NoticeCategory.DATA),
  ThirdPartyComponent("dexkit", "DexKit", R.string.notices_role_dexkit,
    "https://github.com/LuckyPray/DexKit",
    listOf("dexkit-apache", "dexkit-lgpl", "gpl", "dexkit-source"), listOf("org.luckypray"),
    category = NoticeCategory.SYSTEM),
  ThirdPartyComponent("libxposed", "libxposed", R.string.notices_role_libxposed,
    "https://github.com/libxposed/service", groups = listOf("io.github.libxposed"), category = NoticeCategory.SYSTEM),
  ThirdPartyComponent("materialkolor", "MaterialKolor", R.string.notices_role_materialkolor,
    "https://github.com/jordond/MaterialKolor", listOf("apache", "materialkolor"), listOf("com.materialkolor"),
    category = NoticeCategory.IMAGES),
  ThirdPartyComponent("flatbuffers", "FlatBuffers", R.string.notices_role_serialization,
    "https://github.com/google/flatbuffers", groups = listOf("com.google.flatbuffers"), category = NoticeCategory.DATA),
  ThirdPartyComponent("zipflinger", "Zipflinger", R.string.notices_role_zipflinger,
    "https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-main/zipflinger/README.md",
    groups = listOf("com.android"), category = NoticeCategory.DATA),
  ThirdPartyComponent("android-tools-annotations", "Android Tools Annotations", R.string.notices_role_annotations,
    "https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-main/annotations/",
    listOf("apache", "android-tools-notice"), listOf("com.android.tools"), category = NoticeCategory.RUNTIME),
  ThirdPartyComponent("guava", "Guava · ListenableFuture", R.string.notices_role_async,
    "https://github.com/google/guava", groups = listOf("com.google.guava"), category = NoticeCategory.RUNTIME),
  ThirdPartyComponent("annotations", "JetBrains Annotations", R.string.notices_role_annotations,
    "https://github.com/JetBrains/java-annotations", groups = listOf("org.jetbrains"), category = NoticeCategory.RUNTIME),
  ThirdPartyComponent("jspecify", "JSpecify", R.string.notices_role_annotations,
    "https://github.com/jspecify/jspecify", groups = listOf("org.jspecify"), category = NoticeCategory.RUNTIME),
  ThirdPartyComponent("poko", "Poko", R.string.notices_role_annotations,
    "https://github.com/drewhamilton/Poko", groups = listOf("dev.drewhamilton.poko"), category = NoticeCategory.RUNTIME),
  ThirdPartyComponent("cxx", "libc++", R.string.notices_role_cxx,
    "https://github.com/RikkaW/libcxx-prefab", listOf("libcxx"), listOf("dev.rikka.ndk.thirdparty"),
    category = NoticeCategory.SYSTEM),
  ThirdPartyComponent("liquid-glass", "AndroidLiquidGlass", R.string.notices_role_glass,
    "https://github.com/Kyant0/AndroidLiquidGlass", kind = AttributionKind.SOURCE,
    category = NoticeCategory.REFERENCES),
  ThirdPartyComponent("back-gesture", "MiuiBackGestureHook", R.string.notices_role_native,
    "https://github.com/wxxsfxyzm/MiuiBackGestureHook", kind = AttributionKind.SOURCE,
    category = NoticeCategory.REFERENCES),
  ThirdPartyComponent("perfect-icons", "Perfect Icons Completion Project", R.string.notices_role_perfect_icons,
    "https://github.com/pzcn/Perfect-Icons-Completion-Project", licenseKeys = emptyList(),
    kind = AttributionKind.SOURCE, category = NoticeCategory.IMAGES),
  ThirdPartyComponent("perfect-packer", "PerfectIconsPacker", R.string.notices_role_perfect_packer,
    "https://github.com/YifePlayte/PerfectIconsPacker", licenseKeys = emptyList(),
    kind = AttributionKind.HISTORY, category = NoticeCategory.REFERENCES),
  ThirdPartyComponent("lsplt", "LSPlt", R.string.notices_role_history,
    "https://github.com/LSPosed/LSPlt", listOf("lsplt"), kind = AttributionKind.HISTORY,
    category = NoticeCategory.REFERENCES),
)

internal fun thirdPartyComponentForRoute(route: String): ThirdPartyComponent? =
  thirdPartyComponents.firstOrNull { route == "component/${it.key}" }
