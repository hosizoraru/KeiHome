package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerfectSmallWordmarkDeviceTest {
  @Test fun retainedSmallCoffeeLettersLiftButRedBrandAndWhiteBadgeKeepTheirInk() {
    val directory = requireNotNull(
      InstrumentationRegistry.getArguments().getString("perfectSmallWordmarkFixtures"),
    ) { "Retained source fixtures are required" }
    val layers = (0..1).map { File(directory, "kfc-$it.png").readBytes() }
    val source = requireNotNull(BitmapFactory.decodeByteArray(layers[1], 0, layers[1].size))
    val foreground = Bitmap.createScaledBitmap(source, 512, 512, true)
    val original = IntArray(512 * 512)
    foreground.getPixels(original, 0, 512, 0, 0, 512, 512)
    val lifted = original.copyOf()
    assertTrue(PerfectWordmarkPixels.lift(
      lifted, IntArray(lifted.size) { Color.DKGRAY }, 512, 512,
    ) {})
    for (i in lifted.indices) {
      assertEquals(Color.alpha(original[i]), Color.alpha(lifted[i]))
      if (i / 512 !in 350..392) assertEquals("Unrelated artwork at $i", original[i], lifted[i])
    }
    val pair = PerfectStaticNightRenderer.render(layers, null)
    val day = requireNotNull(BitmapFactory.decodeByteArray(pair.day, 0, pair.day.size))
    val night = requireNotNull(BitmapFactory.decodeByteArray(pair.night, 0, pair.night.size))
    try {
      var letters = 0
      var readable = 0
      var protectedInk = 0
      var red = 0
      for (y in 0 until 512) for (x in 0 until 512) {
        val p = foreground.getPixel(x, y)
        assertEquals(Color.alpha(day.getPixel(x, y)), Color.alpha(night.getPixel(x, y)))
        if (Color.alpha(p) != 255) continue
        val hi = maxOf(Color.red(p), Color.green(p), Color.blue(p))
        val lo = minOf(Color.red(p), Color.green(p), Color.blue(p))
        if (hi <= 64 && hi - lo <= 16) {
          if (y in 350..392) {
            letters++
            if (Color.red(night.getPixel(x, y)) >= 190) readable++
          } else if (y < 335) {
            protectedInk++
            assertEquals("White badge ink at $x,$y",
              PerfectNightPixels.dim(day.getPixel(x, y)), night.getPixel(x, y))
          }
        } else if (Color.red(p) > 150 && Color.green(p) < 70 && Color.blue(p) < 70) {
          red++
          assertEquals("Red branding at $x,$y",
            PerfectNightPixels.dim(day.getPixel(x, y)), night.getPixel(x, y))
        }
      }
      assertTrue("Missing source COFFEE ink: $letters", letters > 1500)
      assertTrue("Readable ink $readable/$letters", readable * 100L >= letters * 98L)
      assertTrue("Missing protected portrait and lettering", protectedInk > 1000)
      assertTrue("Missing red K and badge", red > 1000)
      val output = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
      for ((name, bitmap) in listOf("day" to day, "night" to night)) {
        File(output, "kfc-wordmark-$name.png").outputStream().use {
          assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
      }
    } finally {
      day.recycle()
      night.recycle()
      if (foreground !== source) foreground.recycle()
      source.recycle()
    }
  }

  @Test fun threeAdjacentSmallLettersDoNotEstablishATextRow() {
    val pixels = IntArray(512 * 512)
    for (offset in listOf(140, 208, 276)) {
      for (y in 220..251) for (x in offset..offset + 59) {
        if (x <= offset + 5 || y <= 225 || y >= 246) pixels[y * 512 + x] = Color.BLACK
      }
    }
    val before = pixels.copyOf()
    PerfectWordmarkPixels.lift(pixels, IntArray(pixels.size) { Color.DKGRAY }, 512, 512) {}
    assertTrue(before.contentEquals(pixels))
  }

  @Test fun fourAdjacentSmallLettersEstablishATextRow() {
    val pixels = IntArray(512 * 512)
    for (offset in listOf(110, 178, 246, 314)) {
      for (y in 220..251) for (x in offset..offset + 59) {
        if (x <= offset + 5 || y <= 225 || y >= 246) pixels[y * 512 + x] = Color.BLACK
      }
    }
    val before = pixels.copyOf()
    assertTrue(PerfectWordmarkPixels.lift(
      pixels, IntArray(pixels.size) { Color.DKGRAY }, 512, 512,
    ) {})
    for (i in pixels.indices) {
      assertEquals(if (before[i] == Color.BLACK) Color.WHITE else before[i], pixels[i])
    }
  }

  @Test fun nearbySmallDecorationDoesNotInvalidateAnExistingLargeLetterRow() {
    val pixels = IntArray(512 * 512)
    for (offset in listOf(100, 200)) {
      for (y in 200..279) for (x in offset..offset + 79) {
        if (x <= offset + 5 || y <= 205 || y >= 274) pixels[y * 512 + x] = Color.BLACK
      }
    }
    for (y in 225..254) for (x in 300..329) {
      if (x <= 305 || y <= 230 || y >= 249) pixels[y * 512 + x] = Color.BLACK
    }
    val before = pixels.copyOf()
    assertTrue(PerfectWordmarkPixels.lift(
      pixels, IntArray(pixels.size) { Color.DKGRAY }, 512, 512,
    ) {})
    for (i in pixels.indices) {
      val expected = if (i % 512 < 280 && before[i] == Color.BLACK) Color.WHITE else before[i]
      assertEquals("Mixed row at $i", expected, pixels[i])
    }
  }

  @Test fun smallLetterShapesOnAnOpaqueBadgeKeepTheirBlackInk() {
    val pixels = IntArray(512 * 512)
    for (y in 200..280) for (x in 90..380) pixels[y * 512 + x] = Color.WHITE
    for (offset in listOf(110, 160, 210, 260, 310)) {
      for (y in 220..250) for (x in offset..offset + 25) {
        if (x <= offset + 5 || y <= 225 || y >= 245) pixels[y * 512 + x] = Color.BLACK
      }
    }
    val before = pixels.copyOf()
    PerfectWordmarkPixels.lift(pixels, IntArray(pixels.size) { Color.DKGRAY }, 512, 512) {}
    assertTrue(before.contentEquals(pixels))
  }
}
