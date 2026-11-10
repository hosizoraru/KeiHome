package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class PerfectDynamicNightRendererDeviceTest {
  @Test fun foregroundKeepsTextureDimensionsAndEveryAlphaValue() {
    val bytes = image(256, 2) { x, _ -> Color.argb(x, 255, 128, 64) }
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val night = requireNotNull(PerfectDynamicNightRenderer.render(bytes, false) {})
    val decoded = BitmapFactory.decodeByteArray(night, 0, night.size)
    try {
      assertEquals(source.width, decoded.width)
      assertEquals(source.height, decoded.height)
      for (x in 0..255) {
        assertEquals(Color.alpha(source.getPixel(x, 0)), Color.alpha(decoded.getPixel(x, 0)))
      }
      val opaque = decoded.getPixel(255, 0)
      assertEquals(214, Color.red(opaque))
      assertEquals(107, Color.green(opaque))
      assertEquals(54, Color.blue(opaque))
    } finally { source.recycle(); decoded.recycle() }
  }

  @Test fun plainPlateGetsVerticalGradientWithoutAddingAnOpaqueSquare() {
    val bytes = image(32, 32) { x, y ->
      Color.argb(if (x == 0 || y == 0) 0 else 255, 240 - y, 240 - y, 240 - y)
    }
    val night = requireNotNull(PerfectDynamicNightRenderer.render(bytes, true) {})
    val decoded = BitmapFactory.decodeByteArray(night, 0, night.size)
    try {
      assertEquals(0, Color.alpha(decoded.getPixel(0, 16)))
      assertEquals(0, Color.alpha(decoded.getPixel(16, 0)))
      assertEquals(46, Color.red(decoded.getPixel(16, 1)))
      assertEquals(18, Color.red(decoded.getPixel(16, 31)))
    } finally { decoded.recycle() }
  }

  @Test fun bakedMarksArePreservedByDecliningTheWholePlate() {
    val bytes = image(32, 32) { x, _ -> if (x > 10 && x < 20) Color.BLACK else Color.WHITE }
    assertNull(PerfectDynamicNightRenderer.render(bytes, true) {})
  }

  @Test fun blackStencilsAreNotDimmedIntoAnUnreadableDarkPlate() {
    val bytes = image(32, 32) { x, _ -> if (x in 10..20) Color.BLACK else Color.TRANSPARENT }
    assertNull(PerfectDynamicNightRenderer.render(bytes, false) {})
  }

  @Test fun cancellationStopsBeforeAllocation() {
    var called = false
    try {
      PerfectDynamicNightRenderer.render(byteArrayOf(), false) {
        called = true
        throw kotlinx.coroutines.CancellationException()
      }
      fail()
    } catch (_: kotlinx.coroutines.CancellationException) { assertTrue(called) }
  }

  private fun image(width: Int, height: Int, color: (Int, Int) -> Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, color(x, y))
      return ByteArrayOutputStream().also {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }.toByteArray()
    } finally { bitmap.recycle() }
  }
}
