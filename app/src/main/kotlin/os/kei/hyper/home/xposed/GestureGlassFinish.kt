package os.kei.hyper.home.xposed

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/** Soft grazing light across a shallow capsule. The center transmits the compositor backdrop. */
internal class GestureGlassFinish : Drawable() {
  private val body = Paint(Paint.ANTI_ALIAS_FLAG)
  private val shoulder = reflection(0xfffbfdff.toInt())
  private val returnLight = reflection(0xfff7faff.toInt())
  private val underside = reflection(0xff333940.toInt())
  private val transform = Matrix()
  private var darkness = -1f
  private var curvature = -1f
  private var stretch = Float.NaN
  private var opacity = 255

  fun update(left: Int, top: Int, right: Int, bottom: Int, radius: Float, intensity: Float, widthScale: Float) {
    val dark = intensity.coerceIn(0f, 1f)
    val r = radius.coerceIn(0f, (bottom - top) * .5f)
    val expansion = ((widthScale - 1f) * 4f).coerceIn(-1f, 1f)
    val moved = bounds.left != left || bounds.top != top || bounds.right != right || bounds.bottom != bottom ||
      curvature != r || stretch != expansion
    if (!moved && darkness == dark) return
    if (moved) {
      setBounds(left, top, right, bottom)
      curvature = r
      stretch = expansion
      positionReflections()
    }
    darkness = dark
    updatePaints()
    invalidateSelf()
  }

  private fun positionReflections() {
    if (bounds.isEmpty) return
    val height = bounds.height().toFloat()
    val width = bounds.width().toFloat()
    // Keep the light in window space as the OEM stretches the handle. A steep ellipse
    // crosses this thin capsule in just a few pixels and looks like a painted dot.
    val reach = minOf(width * .56f, height * (12f + stretch * 1.6f))
    val shoulderX = bounds.left + curvature + width * (.16f + .05f * stretch)
    position(shoulder, shoulderX, bounds.top - height * .13f, reach, height * .85f, -1.2f)
    position(returnLight, bounds.right - width * (.21f - .04f * stretch),
      bounds.bottom + height * .19f, reach * .72f, height * .72f, -1.6f)
    position(underside, shoulderX, bounds.bottom + height * .21f, reach, height * .88f, 1.2f)
  }

  private fun position(paint: Paint, x: Float, y: Float, rx: Float, ry: Float, angle: Float) {
    transform.setScale(rx, ry)
    transform.postRotate(angle)
    transform.postTranslate(x, y)
    paint.shader!!.setLocalMatrix(transform)
  }

  private fun updatePaints() {
    if (darkness < 0f) return
    body.color = ColorUtils.blendARGB(0x18ffffff, 0x130f1519, darkness)
    body.alpha = body.alpha * opacity / 255
    shoulder.alpha = ((116f + 20f * darkness + 8f * stretch) * opacity / 255).roundToInt()
    returnLight.alpha = ((65f + 18f * darkness) * opacity / 255).roundToInt()
    underside.alpha = ((20f + 70f * darkness) * opacity / 255).roundToInt()
  }

  override fun draw(canvas: Canvas) {
    if (bounds.isEmpty || darkness < 0f) return
    drawPill(canvas, body)
    drawPill(canvas, underside)
    drawPill(canvas, shoulder)
    drawPill(canvas, returnLight)
  }

  private fun drawPill(canvas: Canvas, paint: Paint) {
    canvas.drawRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
      bounds.bottom.toFloat(), curvature, curvature, paint)
  }

  override fun setAlpha(alpha: Int) {
    opacity = alpha
    updatePaints()
    invalidateSelf()
  }

  override fun setColorFilter(filter: ColorFilter?) {
    body.colorFilter = filter
    shoulder.colorFilter = filter
    returnLight.colorFilter = filter
    underside.colorFilter = filter
    invalidateSelf()
  }

  @Deprecated("Deprecated in Java")
  override fun getOpacity() = PixelFormat.TRANSLUCENT

  private fun reflection(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    val colors = intArrayOf(color, ColorUtils.setAlphaComponent(color, 160),
      ColorUtils.setAlphaComponent(color, 50), ColorUtils.setAlphaComponent(color, 0))
    shader = RadialGradient(0f, 0f, 1f, colors, floatArrayOf(0f, .32f, .66f, 1f), Shader.TileMode.CLAMP)
  }
}
