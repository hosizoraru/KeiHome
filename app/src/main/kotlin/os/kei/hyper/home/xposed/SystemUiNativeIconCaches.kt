package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.util.SparseArray
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * SystemUI's own app-icon layers, which keep the artwork of the archive they first read.
 *
 * - `ThemeResources` in an app process only rechecks `/data/system/theme/icons` on a theme
 *   configuration change; until then its zip stays open on the replaced file. Its
 *   `checkUpdate` reopens the archive when the modification time differs and then calls
 *   `IconCustomizer.clearCache`.
 * - `AppIconsManager` keeps plain bitmaps per user and package (`mIconsCache`,
 *   `mQuietFancyIconsCache`) and drops them only when a package is replaced. A system-following
 *   archive's MAML icons bypass that cache (`NoCacheBitmapDrawable`); the plain PNGs of a
 *   fixed-appearance copy do not, so they outlive a day/night swap.
 *
 * [refresh] runs on SystemUI's main thread when KeiHome reloads its own snapshots, so the next
 * native lookup reads the current archive. Status-bar descriptors keep the dropped bitmaps, so
 * [refresh] returns them for [SystemUiStatusBarIconRuntime.adoptNative].
 */
internal class SystemUiNativeIconCaches(
  private val manager: Any,
  private val caches: List<Field>,
  private val userId: Int,
  private val themeResources: () -> Any? = ::systemThemeResources,
) {
  init {
    require(caches.isNotEmpty() && caches.all { it.type === SparseArray::class.java && it.declaringClass.isInstance(manager) })
  }

  /** Returns the dropped package bitmaps that were still alive, at most [MAX_ENTRIES]. */
  fun refresh(): List<Pair<String, Bitmap>> {
    themeResources()?.let { resources -> checkUpdate(resources.javaClass).invoke(resources) }
    val dropped = ArrayList<Pair<String, Bitmap>>()
    for (field in caches) {
      val cache = field.get(manager) as? SparseArray<*> ?: continue
      // AppIconsManager mutates these maps under the SparseArray's monitor.
      synchronized(cache) {
        val values = cache.get(userId) as? MutableMap<*, *> ?: return@synchronized
        for ((key, value) in values) {
          if (dropped.size >= MAX_ENTRIES) break
          val name = key as? String ?: continue
          val bitmap = (value as? java.lang.ref.WeakReference<*>)?.get() as? Bitmap ?: continue
          if (!bitmap.isRecycled) dropped += name to bitmap
        }
        values.clear()
      }
    }
    return dropped
  }

  companion object {
    const val MAX_ENTRIES = 1_024

    private fun systemThemeResources(): Any? =
      Class.forName("miui.content.res.ThemeResources").getMethod("getSystem").invoke(null)

    private fun checkUpdate(type: Class<*>): Method = type.getMethod("checkUpdate").also {
      check(it.returnType == Long::class.javaPrimitiveType)
    }
  }
}
