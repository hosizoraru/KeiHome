package os.kei.hyper.home.xposed

import android.content.Context
import android.net.Uri
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram

/** Owned by one snapshot worker and scoped to its Android user. */
internal class WeatherIconTemperature {
  private data class Reading(val minute: Long, val temperature: Int?)

  private val temperaturePattern = Regex("""([+-]?\d{1,3})\s*(?:°\s*[CF]?|℃|℉)?""")
  private val readings = arrayOfNulls<Reading>(2)

  fun read(context: Context, global: Boolean, now: Long): Int? {
    val slot = if (global) 1 else 0
    val minute = now / 60_000L
    readings[slot]?.takeIf { it.minute == minute }?.let { return it.temperature }
    val temperature = try {
      val owner = context.packageManager.resolveContentProvider("weather", 0)?.packageName
      if (owner != PACKAGE) null
      else if (global) query(context, NativeWeatherLayerProgram.GLOBAL_URI, true)
        ?: query(context, NativeWeatherLayerProgram.GLOBAL_FALLBACK_URI, true)
      else query(context, NativeWeatherLayerProgram.LOCAL_URI, false)
    } catch (_: RuntimeException) {
      null
    }
    readings[slot] = Reading(minute, temperature)
    return temperature
  }

  private fun query(context: Context, uri: String, requirePublished: Boolean): Int? {
    val projection = if (requirePublished) arrayOf("temperature", "publish_time")
      else arrayOf("temperature")
    return context.contentResolver.query(Uri.parse(uri), projection, null, null, null)?.use { cursor ->
      if (!cursor.moveToFirst()) return@use null
      if (requirePublished && cursor.getString(cursor.getColumnIndexOrThrow("publish_time"))
          ?.toLongOrNull()?.takeIf { it != 0L } == null) return@use null
      val value = cursor.getString(cursor.getColumnIndexOrThrow("temperature"))?.trim()
        ?: return@use null
      temperaturePattern.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()
    }
  }

  companion object {
    const val PACKAGE = "com.miui.weather2"
  }
}
