package os.kei.hyper.home.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.round

/** HyperOS moves the same preset between its current directory and a history slot. */
internal object WallpaperPresetIdentity {
  private val templateDirectory = Regex("/data/user_de/0/com\\.miui\\.aod/files/templates/(?:current|history/[0-9]+)/")
  private val metadata = setOf("updateTemplate", "lastUpdate", "isUpdated")
  private val renderedWallpaperPaths = setOf("source", "originResourcePath", "depthVideo", "depthVideoScreenShot")

  fun key(config: JSONObject, lock: String, home: String): String {
    val payload = JSONArray().put("hyperos4-v1").put(normalize(config, root = true)).put(lock).put(home)
    return MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray()).toHexString()
  }

  private fun normalize(value: Any?, root: Boolean = false, wallpaperInfo: Boolean = false): Any? = when (value) {
    is JSONObject -> JSONObject().apply {
      for (key in value.keys().asSequence().sorted()) {
        if (root && key in metadata) continue
        // Lockscreen and home file contents are already included explicitly in the digest.
        if (wallpaperInfo && key in renderedWallpaperPaths) continue
        val entry = value.opt(key)
        put(key, if (key == "matrixArr" && entry is JSONArray && entry.length() == 9) normalizeMatrix(entry)
        else normalize(entry, wallpaperInfo = key == "wallpaperInfo"))
      }
    }
    is JSONArray -> JSONArray().apply {
      repeat(value.length()) { put(normalize(value.opt(it))) }
    }
    is String -> templateDirectory.replace(value) { "template/" }
      .replace("/data/system/theme_magic/users/0/wallpaper/crop_subject", "template/crop_subject")
    else -> value
  }

  // The editor recalculates crop matrices while applying the preset, with subpixel float drift.
  private fun normalizeMatrix(matrix: JSONArray): JSONArray = JSONArray().apply {
    repeat(matrix.length()) { index ->
      val value = matrix.opt(index)
      val precision = if (index == 2 || index == 5) 10.0 else 10_000.0
      val rounded = if (value is Number) round(value.toDouble() * precision) / precision else value
      // Android's JSON writer distinguishes -0.0 from 0.0.
      put(if (rounded is Double && rounded == 0.0) 0 else rounded)
    }
  }
}

internal data class WallpaperPresetChoices(val light: String?, val dark: String?) {
  fun forMode(darkMode: Boolean): String? = if (darkMode) dark else light
}

/** Only resolve a current row or an exact content match in the latest system inventory. */
internal fun <T> wallpaperPresetToApply(target: String, current: String?, entries: List<T>, key: (T) -> String): T? =
  if (target == current) null else entries.firstOrNull { key(it) == target }
