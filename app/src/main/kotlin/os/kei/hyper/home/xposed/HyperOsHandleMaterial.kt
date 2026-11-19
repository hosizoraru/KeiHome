package os.kei.hyper.home.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.ViewGroup
import android.view.ViewOverlay
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.view.View
import androidx.core.graphics.ColorUtils

/** A pill-sized, non-interactive child of the OEM handle overlay. Vendor flags belong to this view alone. */
internal class HyperOsHandleMaterial private constructor(context: Context, val api: Api) : View(context) {
  private val surface = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x63eff2f7 }
  // SystemUI composites the MIUI surface in its own render pass. Put the readable edge
  // in the host overlay's drawable pass, which Android draws after the material child.
  private val finish = GestureGlassFinish()
  private var curvature = 0f

  init {
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, curvature)
      }
    }
    clipToOutline = true
    api.glassPaint.invoke(surface, true)
  }

  override fun onDraw(canvas: Canvas) {
    canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), curvature, curvature, surface)
  }

  fun update(rect: RectF, radius: Float, intensity: Float, widthScale: Float) {
    val l = Math.round(rect.left)
    val t = Math.round(rect.top)
    val r = Math.round(rect.right)
    val b = Math.round(rect.bottom)
    if (left != l || top != t || right != r || bottom != b) {
      layout(l, t, r, b)
      api.clip.invoke(this, 0f, 0f, width.toFloat(), height.toFloat())
    }
    val color = ColorUtils.blendARGB(0x21ffffff, 0x140f1519, intensity)
    if (curvature != radius || surface.color != color) {
      if (curvature != radius) {
        curvature = radius
        invalidateOutline()
      }
      surface.color = color
      invalidate()
    }
    finish.update(l, t, r, b, radius, intensity, widthScale)
  }

  fun removeFrom(host: View) {
    host.overlay.remove(finish)
    (parent as? ViewGroup)?.removeView(this)
  }

  class Api(loader: ClassLoader) {
    private val overlay = ViewOverlay::class.java.getDeclaredMethod("getOverlayView").apply { isAccessible = true }
    fun attach(host: View, child: HyperOsHandleMaterial) {
      (overlay.invoke(host.overlay) as ViewGroup).addView(child)
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
    private val glass = View::class.java.getMethod("setMiGlass", FloatArray::class.java)
    val clip = View::class.java.getMethod("setMiGlassClip", Float::class.javaPrimitiveType,
      Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    val glassPaint = Paint::class.java.getMethod("setGlassEffect", Boolean::class.javaPrimitiveType)
    private var parameters: FloatArray? = null

    fun create(context: Context): HyperOsHandleMaterial? {
      if (supports.invoke(null) != true || android.provider.Settings.Secure.getInt(
          context.contentResolver, "high_text_contrast_enabled", 0) != 0) return null
      val params = parameters ?: (preset.invoke(constructor.newInstance(context)) as FloatArray)
        .takeIf { it.size == 42 && it.all(Float::isFinite) }?.copyOf()?.also { parameters = it } ?: return null
      val view = HyperOsHandleMaterial(context, this)
      val density = context.resources.displayMetrics.density
      backgroundBlur.invoke(view, 1)
      backgroundRadius.invoke(view, Math.round(4 * density))
      viewBlur.invoke(view, 3)
      colorType.invoke(view, 16)
      material.invoke(view, 1)
      radii.invoke(view, Math.round(2 * density), Math.round(6 * density))
      glass.invoke(view, params)
      return view
    }
  }

  companion object {
    fun resolve(loader: ClassLoader) = runCatching { Api(loader) }.onFailure {
      android.util.Log.d("KeiHomeGestureGlass", "HyperOS glass unavailable: ${it.javaClass.simpleName}")
    }.getOrNull()
  }
}
