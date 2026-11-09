package os.kei.hyper.home.iconpack

import java.io.IOException

internal enum class IconPackFormat { INSTALLED_APK, APK, CLASSIC_ZIP, MIUI_MTZ, MIUI_ICONS }

/** Identity from an appfilter, never a guessed package prefix or native alias. */
internal data class IconPackComponent(val packageName: String, val className: String? = null) {
  val flattened: String get() = className?.let { "$packageName/$it" } ?: packageName

  companion object {
    fun parse(value: String): IconPackComponent? {
      var text = value.trim()
      if (text.startsWith("ComponentInfo{")) {
        if (!text.endsWith('}')) return null
        text = text.substring(14, text.length - 1)
      }
      if (text.length > 1024 || text.count { it == '/' } > 1) return null
      val parts = text.split('/', limit = 2)
      val pkg = parts[0]
      if (!validDottedName(pkg)) return null
      val activity = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let {
        if (it.startsWith('.')) pkg + it else it
      }
      if (activity != null && !validDottedName(activity)) return null
      return IconPackComponent(pkg, activity)
    }
  }
}

internal fun validDottedName(value: String): Boolean {
  if (value.length !in 1..512) return false
  var start = true
  var index = 0
  while (index < value.length) {
    val code = value.codePointAt(index)
    if (code == '.'.code) {
      if (start) return false
      start = true
    } else {
      val letter = code in 65..90 || code in 97..122 || code == 95 || code == 36 ||
        (code > 127 && Character.isLetter(code))
      val number = code in 48..57 || (code > 127 && Character.getType(code) in 9..11)
      if (!letter && (start || !number)) return false
      start = false
    }
    index += Character.charCount(code)
  }
  return !start
}

internal data class IconDrawableName(val name: String, val type: String = "drawable") {
  companion object {
    fun parse(value: String): IconDrawableName? {
      val text = value.trim().removePrefix("@")
      val parts = text.split('/')
      val type = if (parts.size == 1) "drawable" else parts.first()
      val name = parts.last()
      if (parts.size !in 1..2 || type !in setOf("drawable", "mipmap") ||
        name.length !in 1..255 || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
      ) return null
      return IconDrawableName(name, type)
    }
  }
}

internal enum class IconNightMode { ANY, LIGHT, DARK }

/** Unknown qualifiers are retained for inspection, but never silently selected for a preview. */
internal data class IconResourceQualifiers(
  val directory: String,
  val night: IconNightMode = IconNightMode.ANY,
  val densityDpi: Int? = null,
  val minSdk: Int = 1,
  val unsupported: List<String> = emptyList(),
) {
  companion object {
    fun parse(directory: String): IconResourceQualifiers? {
      val tokens = directory.split('-')
      if (tokens.first() !in setOf("drawable", "mipmap", "xml", "raw")) return null
      var night = IconNightMode.ANY
      var density: Int? = null
      var sdk = 1
      val unknown = mutableListOf<String>()
      for (token in tokens.drop(1)) {
        when {
          token == "night" || token == "notnight" -> {
            if (night != IconNightMode.ANY) unknown += token
            night = if (token == "night") IconNightMode.DARK else IconNightMode.LIGHT
          }
          token in DENSITIES -> {
            if (density != null) unknown += token
            density = DENSITIES.getValue(token)
          }
          token.matches(Regex("[1-9][0-9]{0,3}dpi")) -> {
            if (density != null) unknown += token
            density = token.removeSuffix("dpi").toInt()
          }
          token.matches(Regex("v[1-9][0-9]{0,2}")) -> sdk = token.drop(1).toInt()
          else -> unknown += token
        }
      }
      return IconResourceQualifiers(directory, night, density, sdk, unknown)
    }

    private val DENSITIES = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213,
      "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
      "nodpi" to 0, "anydpi" to -1)
  }
}

internal data class IconArchiveVariant(val path: String, val qualifiers: IconResourceQualifiers) {
  val isRaster: Boolean get() = path.substringAfterLast('.').lowercase() in setOf("png", "webp", "jpg", "jpeg")
}

/** APK selection is delegated to Android; this selector is only for uncompiled ZIP/MTZ artwork. */
internal fun selectArchiveVariant(
  variants: List<IconArchiveVariant>, dark: Boolean, densityDpi: Int, sdk: Int,
): IconArchiveVariant? = variants.filter {
  it.qualifiers.let { q -> q.unsupported.isEmpty() && q.minSdk <= sdk &&
    (q.night == IconNightMode.ANY || (q.night == IconNightMode.DARK) == dark) }
}.maxWithOrNull(compareBy<IconArchiveVariant> { if (it.qualifiers.night == IconNightMode.ANY) 0 else 1 }
  .thenBy { if (it.qualifiers.densityDpi == -1) 2 else if (it.qualifiers.densityDpi == 0) 1 else 0 }
  .thenBy { -kotlin.math.abs((it.qualifiers.densityDpi ?: 160) - densityDpi) }
  .thenBy { it.qualifiers.densityDpi ?: 160 }
  .thenBy { it.qualifiers.minSdk })

internal enum class IconArchivePart { ORIGINAL, ICONS }

internal sealed interface IconPackArtwork {
  data class Resource(val drawable: IconDrawableName) : IconPackArtwork
  data class Archive(
    val variants: List<IconArchiveVariant>,
    val part: IconArchivePart = IconArchivePart.ORIGINAL,
    /** Back-to-front groups of density variants, never light/dark pairs. */
    val layers: List<List<IconArchiveVariant>> = emptyList(),
    /** Quiet snapshot of a dynamic icon; does not imply animated preview support. */
    val snapshot: Boolean = false,
    /** Closed image-only MAML pair; pixel validation is required before deriving Monet. */
    val palettePair: Boolean = false,
  ) : IconPackArtwork
}

internal data class IconPackEntry(
  val key: String,
  val component: IconPackComponent? = null,
  /** Exact MIUI filename stem. It may be a package OR an activity; do not infer ownership. */
  val nativeName: String? = null,
  val light: IconPackArtwork? = null,
  val dark: IconPackArtwork? = null,
) {
  fun artwork(darkMode: Boolean): IconPackArtwork? = if (darkMode) dark else light
}

internal data class IconPackDescription(
  val title: String? = null,
  val author: String? = null,
  val designer: String? = null,
  val version: String? = null,
  val uiVersion: String? = null,
  val description: String? = null,
)

internal enum class IconPackNotice {
  INVALID_FILTER_ITEMS, CONFLICTING_MAPPINGS, LAUNCHER_SHORTCUTS,
  DYNAMIC_FILTER_DIRECTIVES, FALLBACK_MASK_DIRECTIVES, MISSING_DRAWABLES,
  SOURCE_XML_PREVIEW_UNSUPPORTED, UNSUPPORTED_RESOURCE_QUALIFIERS,
  NATIVE_ANIMATION_PREVIEW_UNSUPPORTED,
}

internal sealed interface IconPackSource {
  data class Installed(val packageName: String, val versionCode: Long, val updatedAt: Long,
    val versionName: String? = null) : IconPackSource
  data class Imported(val record: StoredIconPack) : IconPackSource
  data class Builtin(val record: BuiltinIconPack) : IconPackSource
  /** Immutable local output of an online source; identity changes only with its contents. */
  data class Generated(val file: java.io.File, val digest: String, val createdAt: Long) : IconPackSource
}

internal data class IconPackDescriptor(
  val id: String,
  val displayName: String,
  val format: IconPackFormat,
  val source: IconPackSource,
  val entries: List<IconPackEntry>,
  val description: IconPackDescription = IconPackDescription(),
  val previews: List<IconArchiveVariant> = emptyList(),
  val notices: Set<IconPackNotice> = emptySet(),
  /** Raw appfilter directives, preserved for a future renderer; no masking/recoloring is applied. */
  val directives: List<IconPackDirective> = emptyList(),
  val colorOsProfile: ColorOsPackProfile? = null,
  val builtinKey: String? = null,
) {
  /** Only an observed hash from the native integration establishes a loaded-theme relationship. */
  fun matchesLoadedIcons(observedSha256: String?): Boolean = observedSha256 != null &&
    (source as? IconPackSource.Imported)?.record?.nativeIconsSha256 == observedSha256.lowercase()
}

internal data class IconPackDirective(val tag: String, val attributes: Map<String, String>, val mode: IconNightMode)

internal class IconPackException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal data class IconPackLimits(
  val maxInputBytes: Long = 256L * 1024 * 1024,
  val maxExpandedBytes: Long = 512L * 1024 * 1024,
  val maxEntryBytes: Long = 96L * 1024 * 1024,
  val maxEntries: Int = 100_000,
  val maxCompressionRatio: Int = 512,
  val maxXmlBytes: Int = 16 * 1024 * 1024,
  val maxMappings: Int = 100_000,
  val maxStoredPacks: Int = 32,
  val maxStoredBytes: Long = 1024L * 1024 * 1024,
) {
  init {
    require(maxInputBytes > 0 && maxExpandedBytes > 0 && maxEntryBytes > 0 && maxEntries > 0 &&
      maxCompressionRatio > 0 && maxXmlBytes > 0 && maxMappings > 0 && maxStoredPacks > 0 && maxStoredBytes > 0)
  }
}
