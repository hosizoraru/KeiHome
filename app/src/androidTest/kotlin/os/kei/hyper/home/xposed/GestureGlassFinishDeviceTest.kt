package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.RenderNode
import android.graphics.HardwareBufferRenderer
import android.hardware.HardwareBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureGlassFinishDeviceTest {
  @Test fun previewBendsTheSceneAtTheShoulderAndKeepsTheCenterRegistered() {
    val stripe = Bitmap.createBitmap(80, 30, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.BLUE)
      for (x in 0 until width) for (y in 0 until 13) setPixel(x, y, Color.RED)
    }
    val glass = GestureGlassFinish(BitmapShader(stripe, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    glass.update(4, 10, 76, 20, 5f, 0f, 1f)
    val copy = render(80, 30, glass) { drawBitmap(stripe, 0f, 0f, null) }
    assertEquals(Color.RED, copy.getPixel(0, 11))
    val bent = copy.getPixel(40, 11)
    assertTrue("The upper shoulder samples the blue side of the stripe", Color.blue(bent) > Color.red(bent))
    val center = copy.getPixel(40, 15)
    assertTrue("Transmission dominates in the center", Color.blue(center) - Color.red(center) > 180)
  }

  @Test fun lightSpreadsAlongTheShoulderAndFollowsTheAnimatedBounds() {
    val glass = GestureGlassFinish()
    lateinit var bitmap: Bitmap
    fun render(left: Int, right: Int, scale: Float) {
      glass.update(left, 12, right, 32, 10f, 0f, scale)
      bitmap = render(440, 44, glass) { drawColor(Color.BLACK) }
    }
    render(35, 405, 1f)
    val shoulder = bitmap.getPixel(105, 14)
    val core = Color.red(bitmap.getPixel(220, 22))
    assertTrue(core < 56)
    assertTrue(Color.red(shoulder) > core + 20)
    assertTrue("The highlight peaks inside the shoulder", Color.red(shoulder) >
      Color.red(bitmap.getPixel(105, 12)) + 12)
    assertTrue((50..210).count { Color.red(bitmap.getPixel(it, 14)) > core + 20 } >= 65)
    assertTrue(Color.red(bitmap.getPixel(220, 14)) < Color.red(shoulder))
    val outer = bitmap.getPixel(42, 14)
    render(17, 423, 1.1f)
    assertNotEquals(outer, bitmap.getPixel(42, 14))
    assertTrue(Color.red(bitmap.getPixel(220, 22)) < 56)
    render(35, 405, 1f)
    assertEquals(shoulder, bitmap.getPixel(105, 14))
    assertEquals(Color.BLACK, bitmap.getPixel(34, 22))
    glass.alpha = 0
    render(35, 405, 1f)
    assertEquals(Color.BLACK, bitmap.getPixel(42, 14))
  }

  private fun render(w: Int, h: Int, glass: GestureGlassFinish, background: android.graphics.Canvas.() -> Unit): Bitmap {
    val node = RenderNode("gesture glass sample").apply { setPosition(0, 0, w, h) }
    val canvas = node.beginRecording()
    canvas.background()
    glass.draw(canvas)
    node.endRecording()
    val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
    HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1, usage).use { buffer ->
      HardwareBufferRenderer(buffer).use { renderer ->
        renderer.setContentRoot(node)
        val frame = CompletableFuture<HardwareBufferRenderer.RenderResult>()
        renderer.obtainRenderRequest().draw({ it.run() }, frame::complete)
        val result = frame.get(5, TimeUnit.SECONDS)
        assertEquals(HardwareBufferRenderer.RenderResult.SUCCESS, result.status)
        result.fence.use { assertTrue(it.await(java.time.Duration.ofSeconds(5))) }
        val image = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, null))
        return image.copy(Bitmap.Config.ARGB_8888, false).also { image.recycle() }
      }
    }
  }
}
