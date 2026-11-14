package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PerfectCompositeTextCoverageTest {
  @Test fun removesMatteFromColoredFringeAndNeutralizesOnlyTheProvenGlyphRim() {
    val f = fixture()
    val result = requireNotNull(f.render())
    assertEquals("Opaque gray rim is solid glyph coverage, not an inverted gray outline",
      gray(214), result[16 * W + 10])
    assertEquals(gray(214), result[20 * W + 12])
    assertEquals("Date edge uses the separately supplied alpha exactly once",
      mix(f.night[20 * W + 9], gray(214), 128), result[20 * W + 9])
    assertEquals(0xff2c6dd6.toInt(), result[5 * W + 12])
    val fringe = result[5 * W + 10]
    val expected = mix(f.night[5 * W + 10], 0xff2c6dd6.toInt(), 64)
    assertTrue("Blue antialias must not retain the white matte", channels(fringe).zip(channels(expected))
      .all { (a, b) -> kotlin.math.abs(a - b) <= 1 })
    assertTrue("White fragments have no place on the blue fringe", channels(fringe)[0] <= 44)
    assertEquals("Uncovered plate", f.night[0], result[0])
  }

  @Test fun rejectsColoredForegroundAndInteriorShadingInsteadOfFlatteningArtwork() {
    fixture().also {
      it.foreground[20 * W + 12] = 0xff203080.toInt()
      it.flat[20 * W + 12] = it.foreground[20 * W + 12]
      assertNull(it.render())
    }
    fixture().also {
      it.foreground[20 * W + 12] = gray(64)
      it.flat[20 * W + 12] = gray(64)
      assertNull("Gray shading inside the solid glyph is not antialias", it.render())
    }
  }

  @Test fun rejectsUnexplainedResidualAndDifferentSourcePlate() {
    fixture().also {
      it.flat[5 * W + 10] = 0xffe0c080.toInt()
      assertNull(it.render())
    }
    fixture().also {
      it.background[20 * W + 12] = gray(140)
      assertNull(it.render())
    }
    fixture().also {
      it.flat[20 * W + 12] = gray(50)
      assertNull("The independent date foreground must explain the source flat", it.render())
    }
  }

  @Test fun cancellationNeverChangesSourceArrays() {
    val f = fixture()
    val copies = listOf(f.flat, f.background, f.foreground, f.night).map { it.clone() }
    try {
      PerfectCompositeTextCoverage.night(f.flat, f.background, f.foreground, f.night, W, W) {
        throw CancellationException()
      }
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { }
    listOf(f.flat, f.background, f.foreground, f.night).zip(copies).forEach { (a, b) ->
      assertArrayEquals(b, a)
    }
  }

  @Test fun retainedCalendarHasNoWhiteWeekdayFringeOrInvertedOpaqueDateRim() {
    val directory = File(System.getenv("KEIHOME_PERFECT_COMPOSITE_FIXTURES")
      ?: ".artifacts/research/kfc-gamecenter-night-20261114/fixtures")
    fun image(hash: String): IntArray? {
      val file = listOf(File(directory, hash), File(directory, "$hash.png")).firstOrNull { it.isFile }
        ?: return null
      val bitmap = ImageIO.read(file) ?: return null
      assertEquals(351, bitmap.width)
      assertEquals(351, bitmap.height)
      return bitmap.getRGB(0, 0, 351, 351, null, 0, 351)
    }
    val flat = image("0dd07584a436420ca2c3d0ee686e0a223436c6edeb3f294856bd5f2a75d258e8")
    val background = image("be52e29a6a43eaa4a35376e670f6d117f86efb66bc5ab2ff80fa114d28b4e47f")
    val foreground = image("28dbe8937aa4546e528825bb468b2e967d6350e78d6739715b71e840957a1055")
    assumeTrue("Optional exact source PNG fixtures", flat != null && background != null && foreground != null)
    val source = requireNotNull(flat)
    val plate = requireNotNull(background)
    val glyph = requireNotNull(foreground)
    val night = IntArray(351 * 351) { gray(44 - (it / 351) * 26 / 350) }
    val result = requireNotNull(PerfectCompositeTextCoverage.night(source, plate, glyph, night, 351, 351) {})
    var blueFringe = 0
    var grayRim = 0
    var opaqueDate = 0
    for (i in result.indices) {
      val alpha = glyph[i] ushr 24
      if (alpha == 255) {
        opaqueDate++
        if (glyph[i] and 0xffffff != 0) grayRim++
        assertEquals("No internal date contour survives at $i", gray(214), result[i])
      } else if (alpha > 0) {
        assertEquals("Independent date alpha oracle at $i", mix(night[i], gray(214), alpha), result[i])
      } else if (source[i] != plate[i]) {
        val (r, g, b) = channels(result[i])
        assertTrue("No white matte fragment at weekday pixel $i", r <= 44 && g <= 109 && b <= 214)
        assertTrue("Weekday must retain blue hue at $i", b > g && g > r)
        if (source[i] and 0xffffff != 0x3482ff) blueFringe++
      } else {
        assertEquals("Plate must not be segmented around text at $i", night[i], result[i])
      }
    }
    assertEquals(4873, opaqueDate)
    assertEquals(1029, grayRim)
    assertEquals(480, blueFringe)
  }

  private data class Fixture(
    val flat: IntArray, val background: IntArray, val foreground: IntArray, val night: IntArray,
  ) {
    fun render() = PerfectCompositeTextCoverage.night(flat, background, foreground, night, W, W) {}
  }

  private fun fixture(): Fixture {
    val background = IntArray(W * W) { gray(240 - it / W / 2) }
    val foreground = IntArray(W * W)
    for (y in 16..25) for (x in 10..15) foreground[y * W + x] = gray(if (y == 16) 64 else 0)
    for (y in 17..24) foreground[y * W + 9] = 0x80000000.toInt()
    val flat = IntArray(W * W) { mix(background[it], foreground[it], foreground[it] ushr 24) }
    for (y in 4..7) for (x in 10..17) {
      flat[y * W + x] = mix(background[y * W + x], 0xff3482ff.toInt(),
        when (x) { 10 -> 64; 17 -> 128; else -> 255 })
    }
    return Fixture(flat, background, foreground, IntArray(W * W) { gray(40 - it / W / 2) })
  }

  private fun mix(background: Int, ink: Int, alpha: Int): Int {
    val channels = channels(background).zip(channels(ink)).map { (b, f) ->
      (b * (255 - alpha) + f * alpha + 127) / 255
    }
    return (255 shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
  }

  private fun gray(value: Int) = (255 shl 24) or (value shl 16) or (value shl 8) or value
  private fun channels(pixel: Int) = listOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255)
  private companion object { const val W = 32 }
}
