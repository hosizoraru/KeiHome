package os.kei.hyper.home.data

import org.json.JSONArray
import org.json.JSONObject

internal enum class MonetSeedSource { HOME_WALLPAPER, LOCK_WALLPAPER, BASIC_COLOR, SYSTEM_COLOR }

internal data class MonetCustomization(
  val source: MonetSeedSource = MonetSeedSource.HOME_WALLPAPER,
  val style: MonetSchemeStyle = MonetSchemeStyle.TONAL_SPOT,
  val colorIndex: Int = 0,
  val color: Int = BASIC_COLORS.first(),
  val paletteOverrides: Map<MonetPaletteFamily, MonetTonalSpec> = emptyMap(),
  val backgroundFamily: MonetPaletteFamily? = null,
  val iconOverrides: Map<MonetIconRole, Int> = emptyMap(),
  val iconAppearance: IconPackAppearance = IconPackAppearance.SYSTEM,
) {
  init {
    require(color ushr 24 == 255)
    require(iconOverrides.values.all { it ushr 24 == 255 })
  }

  fun seed(wallpaper: WallpaperColorSnapshot): Int? = when (source) {
    MonetSeedSource.BASIC_COLOR, MonetSeedSource.SYSTEM_COLOR -> color
    MonetSeedSource.HOME_WALLPAPER -> wallpaper.colors.let { it.getOrNull(colorIndex) ?: it.firstOrNull() }
    MonetSeedSource.LOCK_WALLPAPER -> wallpaper.lockColors.let { it.getOrNull(colorIndex) ?: it.firstOrNull() }
  }

  val hasCustomColors: Boolean
    get() = paletteOverrides.isNotEmpty() || backgroundFamily != null || iconOverrides.isNotEmpty()

  fun encode(): String {
    val base = "$source:$style:$colorIndex:$color"
    if (!hasCustomColors && iconAppearance == IconPackAppearance.SYSTEM) return base
    val palettes = MonetPaletteFamily.entries.mapNotNull { family ->
      paletteOverrides[family]?.let { "$family=${it.hue},${it.chroma}" }
    }.joinToString(";")
    val icons = MonetIconRole.entries.mapNotNull { role -> iconOverrides[role]?.let { "$role=$it" } }
      .joinToString(";")
    return "$base:v3:$palettes:${backgroundFamily?.name ?: "AUTO"}:$icons:$iconAppearance"
  }

  companion object {
    val BASIC_COLORS = listOf(0xFF1976D2, 0xFF388E3C, 0xFFEAA21A, 0xFFE76A24, 0xFFB53F6F, 0xFF7853A6)
      .map(Long::toInt)

    fun decode(raw: String?): MonetCustomization? = try {
      raw?.split(':')?.takeIf { it.size == 4 || it.size == 8 && it[4] == "v2" ||
        it.size == 9 && it[4] == "v3" }?.let { fields ->
        val palettes = if (fields.size == 4 || fields[5].isEmpty()) emptyMap() else {
          fields[5].split(';').associate { entry ->
            val (key, values) = entry.split('=')
            val (hue, chroma) = values.split(',')
            MonetPaletteFamily.valueOf(key) to MonetTonalSpec(hue.toDouble(), chroma.toDouble())
          }
        }
        val icons = if (fields.size == 4 || fields[7].isEmpty()) emptyMap() else {
          fields[7].split(';').associate { entry ->
            val (key, color) = entry.split('=')
            MonetIconRole.valueOf(key) to color.toInt()
          }
        }
        MonetCustomization(
          MonetSeedSource.valueOf(fields[0]),
          MonetSchemeStyle.valueOf(fields[1]),
          fields[2].toInt().coerceAtLeast(0),
          fields[3].toInt(),
          palettes,
          if (fields.size == 4) null else when (fields[6]) {
            "AUTO", "false" -> null
            "true" -> MonetPaletteFamily.NEUTRAL
            else -> MonetPaletteFamily.valueOf(fields[6])
          },
          icons,
          if (fields.size == 9) IconPackAppearance.valueOf(fields[8]) else IconPackAppearance.SYSTEM,
        )
      }
    } catch (_: Exception) { null }
  }
}

/** Only small color metadata crosses into the app; wallpaper pixels stay in the system process. */
internal object WallpaperSeedStorage {
  const val KEY = "keihome_wallpaper_seeds"

  fun encode(snapshot: WallpaperColorSnapshot): String = JSONObject()
    .put("home", JSONArray(snapshot.colors)).put("lock", JSONArray(snapshot.lockColors))
    .put("extracted", snapshot.extracted).toString()

  fun decode(raw: String?): WallpaperColorSnapshot? = try {
    raw?.let { JSONObject(it) }?.let { json ->
      fun colors(key: String) = json.getJSONArray(key).let { array ->
        List(array.length()) { array.getInt(it) }
      }
      val home = colors("home")
      val lock = colors("lock")
      WallpaperColorSnapshot(
        if (home.isNotEmpty() || lock.isNotEmpty()) MonetReadState.AVAILABLE else MonetReadState.NOT_PROVIDED,
        home, lock, json.optBoolean("extracted"),
      )
    }
  } catch (_: Exception) { null }
}
