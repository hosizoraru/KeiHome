package os.kei.hyper.home.xposed

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** The compositor owns the live transmitted image. Only the in-app sample supplies a local backdrop. */
internal class GestureGlassFinish(backdrop: Shader? = null) : Drawable() {
  private val optics = RuntimeShader(if (backdrop == null) REFLECTION else TRANSMISSION).apply {
    if (backdrop != null) setInputShader("scene", backdrop)
  }
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = optics }
  private var curvature = -1f
  private var darkness = -1f
  private var stretch = Float.NaN

  fun update(left: Int, top: Int, right: Int, bottom: Int, radius: Float, intensity: Float, widthScale: Float) {
    if (right <= left || bottom <= top) {
      setBounds(0, 0, 0, 0)
      return
    }
    val r = radius.coerceIn(.01f, (bottom - top) * .5f)
    val movement = ((widthScale - 1f) * 3f).coerceIn(-1f, 1f)
    val dark = intensity.coerceIn(0f, 1f)
    val moved = bounds.left != left || bounds.top != top || bounds.right != right || bounds.bottom != bottom || curvature != r
    if (!moved && darkness == dark && stretch == movement) return
    if (moved) {
      setBounds(left, top, right, bottom)
      optics.setFloatUniform("center", bounds.exactCenterX(), bounds.exactCenterY())
      optics.setFloatUniform("halfSize", bounds.width() * .5f, bounds.height() * .5f)
      optics.setFloatUniform("radius", r)
      curvature = r
    }
    optics.setFloatUniform("dark", dark)
    optics.setFloatUniform("motion", movement)
    darkness = dark
    stretch = movement
    invalidateSelf()
  }

  override fun draw(canvas: Canvas) {
    if (bounds.isEmpty || curvature < 0f || !canvas.isHardwareAccelerated) return
    canvas.drawRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
      bounds.bottom.toFloat(), curvature, curvature, paint)
  }

  override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
  override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
  @Deprecated("Deprecated in Java")
  override fun getOpacity() = PixelFormat.TRANSLUCENT

  private companion object {
    const val OPTICS = """
      uniform float2 center, halfSize;
      uniform float radius, dark, motion;
      float2 slopeAt(float2 p) {
        float2 core = max(halfSize - radius, float2(0.0));
        return clamp((p - clamp(p, -core, core)) / radius, -1.0, 1.0);
      }
      half4 reflection(float2 p, float2 slope) {
        float x = p.x / halfSize.x;
        float grazing = smoothstep(0.16, 0.91, length(slope));
        float key = max(dot(slope, float2(-0.55, -0.83)), 0.0);
        float bounce = max(dot(slope, float2(0.65, 0.76)), 0.0);
        float left = 1.0 - smoothstep(0.02, 0.72, abs(x + 0.59 - 0.13 * motion));
        float right = 1.0 - smoothstep(0.03, 0.66, abs(x - 0.65 - 0.08 * motion));
        float glint = grazing * (0.78 * key * left + 0.52 * bounce * right);
        float shade = grazing * (0.06 + 0.21 * dark) * (bounce * left + key * right);
        float base = mix(0.105, 0.085, dark);
        float alpha = glint + (1.0-glint) * (shade + (1.0-shade)*base);
        float light = glint + (1.0-glint)*(1.0-shade)*base*(1.0-dark);
        return half4(half3(light), alpha);
      }
    """
    const val REFLECTION = OPTICS + """
      half4 main(float2 pos) {
        float2 p = pos - center;
        return reflection(p, slopeAt(p));
      }
    """
    const val TRANSMISSION = OPTICS + """
      uniform shader scene;
      half4 main(float2 pos) {
        float2 p = pos - center;
        float2 slope = slopeAt(p);
        float depth = dot(slope, slope);
        half4 under = scene.eval(pos - slope * radius * (0.85 + depth * 1.35));
        half4 light = reflection(p, slope);
        return light + (1.0 - light.a) * under;
      }
    """
  }
}
