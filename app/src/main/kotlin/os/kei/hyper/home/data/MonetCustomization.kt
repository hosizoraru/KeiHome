package os.kei.hyper.home.data

import org.json.JSONArray
import org.json.JSONObject

internal enum class MonetSeedSource { HOME_WALLPAPER, LOCK_WALLPAPER, BASIC_COLOR }

internal data class MonetCustomization(
  val source: MonetSeedSource = MonetSeedSource.HOME_WALLPAPER,
  val style: MonetSchemeStyle = MonetSchemeStyle.TONAL_SPOT,
  val colorIndex: Int = 0,
  val color: Int = BASIC_COLORS.first(),
) {
  fun seed(wallpaper: WallpaperColorSnapshot): Int? = when (source) {
    MonetSeedSource.BASIC_COLOR -> color
    MonetSeedSource.HOME_WALLPAPER -> wallpaper.colors.let { it.getOrNull(colorIndex) ?: it.firstOrNull() }
    MonetSeedSource.LOCK_WALLPAPER -> wallpaper.lockColors.let { it.getOrNull(colorIndex) ?: it.firstOrNull() }
  }

  fun encode(): String = "$source:$style:$colorIndex:$color"

  companion object {
    val BASIC_COLORS = listOf(0xFF1976D2, 0xFF388E3C, 0xFFEAA21A, 0xFFE76A24, 0xFFB53F6F, 0xFF7853A6)
      .map(Long::toInt)

    fun decode(raw: String?): MonetCustomization? = try {
      raw?.split(':')?.takeIf { it.size == 4 }?.let { fields ->
        MonetCustomization(
          MonetSeedSource.valueOf(fields[0]),
          MonetSchemeStyle.valueOf(fields[1]),
          fields[2].toInt().coerceAtLeast(0),
          fields[3].toInt(),
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
