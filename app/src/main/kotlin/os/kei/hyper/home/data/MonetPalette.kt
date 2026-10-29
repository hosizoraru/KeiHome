package os.kei.hyper.home.data

import android.app.WallpaperManager
import android.content.Context
import android.provider.Settings
import androidx.annotation.ColorInt
import org.json.JSONObject

internal enum class MonetReadState {
  AVAILABLE,
  NOT_PROVIDED,
  UNAVAILABLE,
}

internal data class MonetColor(
  val tone: Int?,
  @ColorInt val value: Int,
)

internal data class MonetColorFamily(
  val name: String,
  val colors: List<MonetColor>,
) {
  val colorCount: Int
    get() = colors.size
}

internal data class MonetPaletteSource(
  val name: String,
  val state: MonetReadState,
  val families: List<MonetColorFamily>,
) {
  val generatedColorCount: Int
    get() = families.sumOf(MonetColorFamily::colorCount)
}

internal data class WallpaperColorSnapshot(
  val state: MonetReadState,
  val colors: List<Int>,
)

internal data class MonochromeIconSettings(
  val enabled: Boolean?,
  @ColorInt val colorArgb: Int?,
) {
  val colorHex: String?
    get() = colorArgb?.let { "#%08X".format(it) }
}

internal data class MonetPaletteSnapshot(
  val source: String?,
  val style: String?,
  val wallpaper: WallpaperColorSnapshot,
  val launcherPalette: MonetPaletteSource,
  val frameworkPalette: MonetPaletteSource,
  val monochrome: MonochromeIconSettings,
) {
  val activePalette: MonetPaletteSource
    get() = launcherPalette.takeIf { it.state == MonetReadState.AVAILABLE }
      ?: frameworkPalette

  val generatedColorCount: Int
    get() = activePalette.generatedColorCount

  val distinctColorCount: Int
    get() = activePalette.families
      .flatMap(MonetColorFamily::colors)
      .map(MonetColor::value)
      .distinct()
      .size

  // Kept as short aliases for the status surface and older UI tests.
  val wallpaperColors: List<Int>
    get() = wallpaper.colors

  val families: List<MonetColorFamily>
    get() = activePalette.families
}

internal fun parseMonetPaletteJson(raw: String): List<MonetColorFamily> {
  val json = try {
    JSONObject(raw)
  } catch (_: Exception) {
    return emptyList()
  }
  val values = linkedMapOf<String, MutableList<MonetColor>>()
  MONET_FAMILY_ORDER.forEach { values[it] = mutableListOf() }
  val keys = json.keys()
  while (keys.hasNext()) {
    val key = keys.next()
    val family = MONET_FAMILY_ORDER.firstOrNull { key.startsWith(it) } ?: continue
    val tone = key.removePrefix(family).toIntOrNull() ?: continue
    if (tone !in 0..100) continue
    val number = when (val value = json.opt(key)) {
      is Number -> value.toLong().takeIf {
        value.toDouble() == it.toDouble()
      }
      is String -> value.toLongOrNull()
      null -> null
      else -> null
    } ?: continue
    val color = number.takeIf {
      it >= Int.MIN_VALUE.toLong() && it <= UInt.MAX_VALUE.toLong()
    }?.toInt() ?: continue
    values.getValue(family).add(MonetColor(tone = tone, value = color))
  }
  return values.map { (name, colors) ->
    MonetColorFamily(
      name = name,
      colors = colors.distinctBy { it.tone }.sortedBy { it.tone },
    )
  }.filter { it.colors.isNotEmpty() }
}

internal class MonetPaletteReader(context: Context) {
  private val context = context.applicationContext
  private val resolver = context.contentResolver

  fun read(): MonetPaletteSnapshot {
    val settings = readSettings()
    return MonetPaletteSnapshot(
      source = settings.first,
      style = settings.second,
      wallpaper = readWallpaperColors(),
      launcherPalette = readLauncherPalette(),
      frameworkPalette = readFrameworkPalette(),
      monochrome = readMonochromeSettings(),
    )
  }

  private fun readSettings(): Pair<String?, String?> {
    val raw = readSetting(THEME_CUSTOMIZATION_OVERLAY_PACKAGES)
      ?: return null to null
    return try {
      val json = JSONObject(raw)
      json.optString(COLOR_SOURCE).takeIf { it.isNotBlank() } to
        json.optString(THEME_STYLE).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
      null to null
    }
  }

  private fun readLauncherPalette(): MonetPaletteSource {
    val uri = android.net.Uri.parse(LAUNCHER_PALETTE_URI)
    return try {
      val raw = resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) null
        else {
          val resultColumn = cursor.getColumnIndex("result")
          if (resultColumn < 0) null else cursor.getString(resultColumn)
        }
      }
      if (raw.isNullOrBlank()) {
        MonetPaletteSource(
          name = "HyperOS launcher",
          state = MonetReadState.NOT_PROVIDED,
          families = emptyList(),
        )
      } else {
        val families = parseMonetPaletteJson(raw)
        MonetPaletteSource(
          name = "HyperOS launcher",
          state = if (families.isEmpty()) {
            MonetReadState.UNAVAILABLE
          } else {
            MonetReadState.AVAILABLE
          },
          families = families,
        )
      }
    } catch (_: Exception) {
      MonetPaletteSource(
        name = "HyperOS launcher",
        state = MonetReadState.UNAVAILABLE,
        families = emptyList(),
      )
    }
  }

  private fun readFrameworkPalette(): MonetPaletteSource {
    val tones = (0..100 step 10).toList()
    val families = listOf(
      "primary" to "system_accent1",
      "secondary" to "system_accent2",
      "tertiary" to "system_accent3",
      "neutral" to "system_neutral1",
      "neutral-variant" to "system_neutral2",
    ).mapNotNull { (name, resourcePrefix) ->
      val colors = tones.mapNotNull { tone ->
        val id = context.resources.getIdentifier(
          "${resourcePrefix}_$tone",
          "color",
          "android",
        )
        if (id == 0) return@mapNotNull null
        try {
          MonetColor(tone = tone, value = context.resources.getColor(id, context.theme))
        } catch (_: Exception) {
          null
        }
      }
      colors.takeIf { it.isNotEmpty() }?.let { MonetColorFamily(name, it) }
    }
    return MonetPaletteSource(
      name = "Android framework",
      state = if (families.isEmpty()) {
        MonetReadState.UNAVAILABLE
      } else {
        MonetReadState.AVAILABLE
      },
      families = families,
    )
  }

  private fun readWallpaperColors(): WallpaperColorSnapshot {
    return try {
      val wallpaper = WallpaperManager.getInstance(context)
        .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        ?: return WallpaperColorSnapshot(
          state = MonetReadState.NOT_PROVIDED,
          colors = emptyList(),
        )
      WallpaperColorSnapshot(
        state = MonetReadState.AVAILABLE,
        colors = listOfNotNull(
          wallpaper.primaryColor.toArgb(),
          wallpaper.secondaryColor?.toArgb(),
          wallpaper.tertiaryColor?.toArgb(),
        ).distinct(),
      )
    } catch (_: Exception) {
      WallpaperColorSnapshot(
        state = MonetReadState.UNAVAILABLE,
        colors = emptyList(),
      )
    }
  }

  private fun readMonochromeSettings(): MonochromeIconSettings {
    val enabled = when (readSetting(KEY_MONOCHROME)) {
      "1", "true" -> true
      "0", "false" -> false
      else -> null
    }
    val colorArgb = readSetting(KEY_MONOCHROME_COLOR)
      ?.toLongOrNull()
      ?.takeIf {
        it >= Int.MIN_VALUE.toLong() && it <= UInt.MAX_VALUE.toLong()
      }
      ?.toInt()
    return MonochromeIconSettings(enabled = enabled, colorArgb = colorArgb)
  }

  private fun readSetting(key: String): String? = try {
    Settings.Secure.getString(resolver, key)
      ?: Settings.System.getString(resolver, key)
  } catch (_: Exception) {
    null
  }

  private companion object {
    const val LAUNCHER_PALETTE_URI = "content://com.miui.launcher.wallpaper.palette"
    const val THEME_CUSTOMIZATION_OVERLAY_PACKAGES =
      "theme_customization_overlay_packages"
    const val COLOR_SOURCE = "android.theme.customization.color_source"
    const val THEME_STYLE = "android.theme.customization.theme_style"
    const val KEY_MONOCHROME = "key_monochrome"
    const val KEY_MONOCHROME_COLOR = "key_monochrome_color"
  }
}

private val MONET_FAMILY_ORDER =
  listOf("primary", "secondary", "tertiary", "neutral-variant", "neutral")

internal val MonetThemeStyles = listOf(
  "TONAL_SPOT",
  "VIBRANT",
  "EXPRESSIVE",
  "SPRITZ",
  "RAINBOW",
  "FRUIT_SALAD",
  "MONOCHROMATIC",
)
