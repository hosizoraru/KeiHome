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

/** Two localized reflections on the curved ends. The middle carries only the translucent fill. */
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
    // Bound the footprint by cap height, not by pill width. Even on a short handle the reflections stay apart.
    val reach = minOf(width * .28f, height * (2.7f + .7f * stretch))
    val inset = curvature * (.62f + .32f * stretch)
    // Near the left shoulder the tangent turns upward; the lower right shoulder has the same slope.
    position(shoulder, bounds.left + inset, bounds.top + height * .07f, reach, height * .43f, -13f)
    position(returnLight, bounds.right - inset, bounds.bottom - height * .04f, reach * .81f, height * .48f, -12f)
    position(underside, bounds.left + curvature, bounds.bottom + height * .22f, reach * .93f, height * .66f, 6f)
  }

  private fun position(paint: Paint, x: Float, y: Float, rx: Float, ry: Float, angle: Float) {
    transform.setScale(rx, ry)
    transform.postRotate(angle)
    transform.postTranslate(x, y)
    paint.shader!!.setLocalMatrix(transform)
  }

  private fun updatePaints() {
    if (darkness < 0f) return
    body.color = ColorUtils.blendARGB(0x31ffffff, 0x162a2b2c, darkness)
    body.alpha = body.alpha * opacity / 255
    shoulder.alpha = ((137f + 46f * darkness + 14f * stretch) * opacity / 255).roundToInt()
    returnLight.alpha = ((83f + 32f * darkness + 12f * stretch) * opacity / 255).roundToInt()
    underside.alpha = ((22f + 59f * darkness) * opacity / 255).roundToInt()
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
