package os.kei.hyper.home.xposed

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.WindowManager
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** The blur drawable belongs to the navigation window's compositor. Its bounds are the actual animated pill. */
internal class GestureHandleGlass(handle: Class<*>) : View.OnAttachStateChangeListener {
  private class Material {
    var native: HyperOsHandleMaterial? = null
    var triedNative = false
    var backdrop: Drawable? = null
    var triedBlur = false
    val finish = GestureGlassFinish()
  }

  private val pill = handle.getMethod("getPillRect")
  private val radius = handle.getMethod("getPillRadius")
  private fun floatField(handle: Class<*>, name: String) = runCatching {
    handle.getDeclaredField(name).takeIf { it.type == Float::class.javaPrimitiveType }?.apply { isAccessible = true }
  }.getOrNull()
  private val darkIntensity = floatField(handle, "mDarkIntensity")
  private val restingWidth = floatField(handle, "mHandleWidth")
  private val materials = IdentityHashMap<View, Material>()
  // The ranchu renderer advertises the MIUI API but draws a flat mask in both day and night mode.
  private val vendor = if (android.os.Build.HARDWARE == "ranchu") null
    else handle.classLoader?.let(HyperOsHandleMaterial::resolve)
  var allowVendor = false
  private val blur = runCatching { BlurApi() }.getOrNull()

  private class BlurApi {
    private val root = View::class.java.getDeclaredMethod("getViewRootImpl")
    private val create = root.returnType.getDeclaredMethod("createBackgroundBlurDrawable")
    private val blurRadius = create.returnType.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
    private val corners = create.returnType.getMethod("setCornerRadius", Float::class.javaPrimitiveType)

    fun create(view: View): Drawable = (create.invoke(root.invoke(view)) as Drawable).also {
      blurRadius.invoke(it, (4 * view.resources.displayMetrics.density).roundToInt())
    }
    fun round(drawable: Drawable, radius: Float) { corners.invoke(drawable, radius) }
  }

  fun draw(view: View, canvas: Canvas, nativeColor: Int): Boolean {
    val rect = pill.invoke(view) as RectF
    if (rect.isEmpty || !canvas.isHardwareAccelerated) return false
    val r = radius.invoke(view) as Float
    val intensity = darkIntensity?.getFloat(view)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
      ?: (1f - Color.red(nativeColor) / 255f)
    val base = restingWidth?.getFloat(view) ?: 0f
    val widthScale = if (base > 0f) rect.width() / base else 1f
    val material = materials.getOrPut(view) { view.addOnAttachStateChangeListener(this); Material() }
    val blurEnabled = view.context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
    if (blurEnabled && allowVendor && !material.triedNative) {
      material.triedNative = true
      material.native = runCatching { vendor?.create(view.context)?.also { child ->
        child.update(rect, r, intensity, widthScale)
        child.api.attach(view, child)
        material.backdrop?.setVisible(false, false)
        android.util.Log.i("KeiHomeGestureGlass", "Attached HyperOS pill material ${child.width}x${child.height}")
      } }.getOrNull()
    }
    material.native?.let { child ->
      if (blurEnabled && allowVendor) {
        child.update(rect, r, intensity, widthScale)
        return true
      }
      child.removeFrom(view)
      material.native = null
      material.triedNative = false
    }
    if (blurEnabled && !material.triedBlur) {
      material.triedBlur = true
      material.backdrop = runCatching { blur?.create(view) }.getOrNull()
    }
    material.backdrop?.let { drawable ->
      drawable.setVisible(blurEnabled, false)
      if (blurEnabled) {
        drawable.setBounds(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        blur?.round(drawable, r)
        drawable.draw(canvas)
        val bounds = drawable.bounds
        material.finish.update(bounds.left, bounds.top, bounds.right, bounds.bottom, r, intensity, widthScale)
        material.finish.draw(canvas)
        return true
      }
    }
    // Let SystemUI choose its contrast paint when the compositor cannot supply a backdrop.
    return false
  }

  override fun onViewAttachedToWindow(view: View) = Unit
  override fun onViewDetachedFromWindow(view: View) { release(view) }

  private fun release(view: View) {
    val material = materials.remove(view)
    material?.backdrop?.setVisible(false, false)
    material?.native?.removeFrom(view)
    view.removeOnAttachStateChangeListener(this)
  }

  fun clear() { materials.keys.toList().forEach(::release) }
}
