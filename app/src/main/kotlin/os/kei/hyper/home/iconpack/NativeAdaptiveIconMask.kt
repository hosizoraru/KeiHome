package os.kei.hyper.home.iconpack

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import java.io.File

/**
 * Stock destination shape for APK conversion, from transform_config.xml / IconTransform /
 * Config[name=ConfigIconMask]. IconCustomizer scales this path from a 100x100 viewport.
 *
 * Verified miui-framework.jar SHA-256: dfd36a5a1867c3e94171b7ba0ceeb6f3f1334265e442d8ba8a2699649ca31b16.
 * ThemeResources wraps product/default with /data/system/theme; icons only fall back when the
 * active ZIP is invalid/missing, not when one of its entries is missing. needProvisionTheme()
 * is false, and ThemeZipFile adds no alternate filesystem paths. Conversion replaces the active
 * pack, so it uses the product default shape, not a mask inherited from that active pack.
 * An unreadable/missing config leaves the framework mask unchanged; there is no invented shape
 * or speculative /system/media/theme fallback.
 */
internal class NativeAdaptiveIconMask private constructor(
  val sourcePath: String,
  val pathData: String,
  private val normalized: Path,
) {
  fun pathFor(drawable: Drawable): Path? {
    if (drawable !is AdaptiveIconDrawable || !isRectangle(drawable.iconMask, drawable.bounds)) return null
    return pathForBounds(drawable.bounds)
  }

  fun pathForBounds(bounds: Rect): Path {
    val matrix = Matrix().apply {
      setScale(bounds.width() / VIEWPORT, bounds.height() / VIEWPORT)
      postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
    }
    return Path(normalized).apply { transform(matrix) }
  }

  companion object {
    /** 100-unit HyperOS 4 stock contract, independent of the host's installed theme. */
    const val HYPER_OS4_PATH_DATA = "M 0 42.7 C 0 27.7 0 20.3 2.9 14.6 C 5.5 9.5 9.5 5.5 14.6 2.9 C 20.3 0 27.7 0 42.7 0 L 57.3 0 C 72.3 0 79.7 0 85.4 2.9 C 90.5 5.5 94.5 9.5 97.1 14.6 C 100 20.3 100 27.7 100 42.7 V 57.3 C 100 72.3 100 79.7 97.1 85.4 C 94.5 90.5 90.5 94.5 85.4 97.1 C 79.7 100 72.3 100 57.3 100 H 42.7 C 27.7 100 20.3 100 14.6 97.1 C 9.5 94.5 5.5 90.5 2.9 85.4 C 0 79.7 0 72.3 0 57.3 L 0 42.7 Z"
    private const val VIEWPORT = 100f
    private const val CONFIG = "transform_config.xml"
    private val source = File("/product/media/theme/default/icons")

    fun load(): NativeAdaptiveIconMask? = try {
      val bytes = IconPackArchive.readEntry(source, CONFIG, 16 * 1024)
      var data: String? = null
      BoundedPackXml.read(bytes, IconPackLimits(maxXmlBytes = 16 * 1024, maxMappings = 128),
        start = { name, attributes, depth ->
          if (depth == 1) require(name == "IconTransform")
          if (depth == 2 && name == "Config" && attributes["name"] == "ConfigIconMask") {
            require(data == null) { "Duplicate native icon mask" }
            data = requireNotNull(attributes["value"]).also { require(it.isNotBlank()) }
          }
        })
      val pathData = requireNotNull(data)
      fromPathData(source.path, pathData)
    } catch (_: Exception) { null }

    fun fromPathData(sourcePath: String, pathData: String): NativeAdaptiveIconMask {
      require(pathData.length in 1..4096)
      val path = PathParser().parsePathString(pathData).toPath().asAndroidPath()
      val bounds = RectF().also(path::computeBounds)
      // Arc conversion can land a few floating-point steps beyond an exact viewport edge.
      require(!path.isEmpty && !path.isInverseFillType && !bounds.isEmpty &&
        listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() } &&
        bounds.left >= -0.001f && bounds.top >= -0.001f &&
        bounds.right <= VIEWPORT + 0.001f && bounds.bottom <= VIEWPORT + 0.001f) {
        "Icon mask bounds $bounds must fit the 100-unit viewport"
      }
      require(Region().setPath(path, Region(0, 0, 100, 100))) { "Icon mask has no filled area" }
      return NativeAdaptiveIconMask(sourcePath, pathData, Path(path))
    }

    /** isRect proves the contour itself; equal bounding boxes or sparse samples are insufficient. */
    fun isRectangle(path: Path, bounds: Rect): Boolean {
      if (bounds.isEmpty || path.isInverseFillType) return false
      val rectangle = RectF()
      return path.isRect(rectangle) && rectangle == RectF(bounds)
    }
  }
}
