package os.kei.hyper.home.xposed

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.view.ViewGroup
import android.view.ViewOverlay
import android.view.View
import androidx.core.graphics.ColorUtils

/** A pill-sized, non-interactive child of the OEM handle overlay. Vendor flags belong to this view alone. */
internal class HyperOsHandleMaterial private constructor(
  context: Context,
  val api: Api,
  private val parameters: FloatArray,
) {
  // SystemUI extensions cache the concrete class of every View they encounter.
  // A framework View and background can outlive this controller without pinning its loader.
  val view = View(context)
  private val surface = ShapeDrawable()
  // SystemUI composites the MIUI surface in its own render pass. Put the readable edge
  // in the host overlay's drawable pass, which Android draws after the material child.
  private val finish = GestureGlassFinish()
  private var curvature = 0f

  init {
    view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    surface.paint.isAntiAlias = true
    api.glassPaint.invoke(surface.paint, true)
    view.background = surface
    view.clipToOutline = true
  }

  fun update(rect: RectF, radius: Float, intensity: Float, widthScale: Float) = with(view) {
    val l = Math.round(rect.left)
    val t = Math.round(rect.top)
    val r = Math.round(rect.right)
    val b = Math.round(rect.bottom)
    val sizeChanged = width != r - l || height != b - t
    if (left != l || top != t || right != r || bottom != b) {
      layout(l, t, r, b)
      if (sizeChanged) {
        api.clip.invoke(this, 0f, 0f, width.toFloat(), height.toFloat())
        api.sdfSize?.invoke(this, width.toFloat(), height.toFloat())
      }
    }
    val color = ColorUtils.blendARGB(0x15ffffff, 0x0c111111, intensity)
    if (curvature != radius || surface.paint.color != color) {
      if (curvature != radius) {
        // The clock preset is authored for much larger glyphs. These HyperOS shape fields
        // are in pixels. Keep the refracting edge inside the rounded shoulder of this pill.
        parameters[19] = (radius * .82f).coerceAtLeast(1f)
        parameters[21] = radius * 3.2f
        parameters[22] = radius * 1.8f
        api.glass.invoke(this, parameters)
        curvature = radius
        surface.shape = RoundRectShape(FloatArray(8) { radius }, null, null)
        invalidateOutline()
      }
      surface.paint.color = color
      surface.invalidateSelf()
    }
    finish.update(l, t, r, b, radius, intensity, widthScale)
  }

  fun removeFrom(host: View) {
    host.overlay.remove(finish)
    (view.parent as? ViewGroup)?.removeView(view)
  }

  class Api(loader: ClassLoader) {
    private val overlay = ViewOverlay::class.java.getDeclaredMethod("getOverlayView").apply { isAccessible = true }
    fun attach(host: View, child: HyperOsHandleMaterial) {
      (overlay.invoke(host.overlay) as ViewGroup).addView(child.view)
      host.overlay.add(child.finish)
    }
    private val clock = loader.loadClass("com.miui.clock.allInOne.TimeView")
    private val preset = clock.getMethod("getGlassData")
    private val constructor = clock.getConstructor(Context::class.java)
    private val supports = loader.loadClass("miuix.core.util.HyperMaterialUtils").getMethod("isSupportGlass")
    private val material = View::class.java.getMethod("setMiViewMaterialType", Integer.TYPE)
    private val viewBlur = View::class.java.getMethod("setMiViewBlurMode", Integer.TYPE)
    private val colorType = View::class.java.getMethod("setMiCustomSurfaceColorType", Integer.TYPE)
    private val backgroundRadius = View::class.java.getMethod("setMiBackgroundBlurRadius", Integer.TYPE)
    private val backgroundBlur = View::class.java.getMethod("setMiBackgroundBlurMode", Integer.TYPE)
    private val radii = View::class.java.getMethod("setMiGlassBlurRadius", Integer.TYPE, Integer.TYPE)
    val sdfSize = runCatching { View::class.java.getMethod("setMiGlassSdfMaxSize", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType) }.getOrNull()
    val glass = View::class.java.getMethod("setMiGlass", FloatArray::class.java)
    val clip = View::class.java.getMethod("setMiGlassClip", Float::class.javaPrimitiveType,
      Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    val glassPaint = Paint::class.java.getMethod("setGlassEffect", Boolean::class.javaPrimitiveType)
    private var parameters: FloatArray? = null

    fun create(context: Context): HyperOsHandleMaterial? {
      if (supports.invoke(null) != true || android.provider.Settings.Secure.getInt(
          context.contentResolver, "high_text_contrast_enabled", 0) != 0) return null
      val params = parameters ?: (preset.invoke(constructor.newInstance(context)) as FloatArray)
        .takeIf { it.size == 42 && it.all(Float::isFinite) }?.copyOf()?.also { parameters = it } ?: return null
      val profile = params.copyOf().apply {
        this[14] = .09f // Reduce the clock preset’s contribution to the body tint
        this[20] = 2.6f // Edge exponent
        this[24] = .82f // Reflection strength
        this[32] = 1.48f // Refraction index
      }
      val child = HyperOsHandleMaterial(context, this, profile)
      val view = child.view
      val density = context.resources.displayMetrics.density
      backgroundBlur.invoke(view, 1)
      backgroundRadius.invoke(view, Math.round(1.4f * density))
      viewBlur.invoke(view, 3)
      colorType.invoke(view, 16)
      material.invoke(view, 1)
      radii.invoke(view, Math.round(density), Math.round(2 * density))
      return child
    }
  }

  companion object {
    fun resolve(loader: ClassLoader) = runCatching { Api(loader) }.onFailure {
      android.util.Log.d("KeiHomeGestureGlass", "HyperOS glass unavailable: ${it.javaClass.simpleName}")
    }.getOrNull()
  }
}
