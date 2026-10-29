package os.kei.hyper.home.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonochromeMaskQualityTest {
  private fun mask(draw: (Int, Int) -> Int) = IntArray(96 * 96) { draw(it % 96, it / 96) }
  private fun unusable(pixels: IntArray, bitmap: Boolean = true) =
    MonochromeMaskQuality.isUnusable(pixels, 96, 96, bitmap)

  @Test fun rejectsEmptyAndUniformOpaqueLayers() {
    assertTrue(unusable(IntArray(96 * 96)))
    assertTrue(unusable(IntArray(96 * 96) { 0xff112233.toInt() }, bitmap = false))
  }

  @Test fun rejectsPaddedFullColorArtWhoseAlphaLosesAllInteriorDetail() {
    val pixels = mask { x, y ->
      if (x in 22..73 && y in 22..73 &&
        !(x < 27 && y < 27 || x > 68 && y < 27 || x < 27 && y > 68 || x > 68 && y > 68)
      ) 0xff000000.toInt() or (x * 3 shl 16) or (y * 3 shl 8) else 0
    }
    assertTrue(unusable(pixels))
    assertFalse(unusable(pixels, bitmap = false))
  }

  @Test fun preservesCutoutGlyphAndSingleColorSilhouette() {
    val shape = mask { x, y -> if (x in 22..73 && y in 22..73) 0xff000000.toInt() else 0 }
    assertFalse(unusable(shape))
    val cutout = mask { x, y ->
      if (x in 22..73 && y in 22..73 && !(x in 42..46 && y in 45..49))
        0xff000000.toInt() or (x * 3 shl 16) or (y * 3 shl 8) else 0
    }
    assertFalse(unusable(cutout))
  }

  @Test fun preservesSparseColoredLogoAndSmallAntialiasColorNoise() {
    assertFalse(unusable(mask { x, y ->
      if (x in 22..73 && y in 22..73 && (x + y) % 4 != 0)
        0xff000000.toInt() or (x * 3 shl 16) or (y * 3 shl 8) else 0
    }))
    assertFalse(unusable(mask { x, y ->
      if (x in 22..73 && y in 22..73) 0xff000000.toInt() or ((x + y) % 16) else 0
    }))
  }
}
