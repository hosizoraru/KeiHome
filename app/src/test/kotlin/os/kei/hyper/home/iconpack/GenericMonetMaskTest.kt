package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class GenericMonetMaskTest {
  @Test fun opaquePlateKeepsAVisibleMarkInsteadOfBecomingASolidSquare() {
    val pixels = IntArray(64 * 64) { 0xff222222.toInt() }
    for (y in 20..43) for (x in 20..43) pixels[y * 64 + x] = -1
    val mask = requireNotNull(GenericMonetMask.foreground(pixels, 64))
    assertEquals(0, mask[0] ushr 24)
    assertEquals(255, mask[32 * 64 + 32] ushr 24)
  }

  @Test fun darkMarkOnALightPlateAlsoSurvives() {
    val pixels = IntArray(32 * 32) { -1 }
    for (y in 8..23) for (x in 8..23) pixels[y * 32 + x] = 0xff102030.toInt()
    val mask = requireNotNull(GenericMonetMask.foreground(pixels, 32))
    assertEquals(0, mask[0] ushr 24)
    assertEquals(255, mask[16 * 32 + 16] ushr 24)
  }

  @Test fun transparentCutoutPreservesAntialiasedAlpha() {
    val pixels = IntArray(32 * 32)
    pixels[16 * 32 + 16] = 0x80335577.toInt()
    val mask = requireNotNull(GenericMonetMask.foreground(pixels, 32))
    assertEquals(0x80ffffff.toInt(), mask[16 * 32 + 16])
    assertEquals(0, mask[0] ushr 24)
  }

  @Test fun blankPlatesAreSkippedRatherThanInventingArtwork() {
    assertNull(GenericMonetMask.foreground(IntArray(1024) { 0xffeeeeee.toInt() }, 32))
  }

  @Test(expected = CancellationException::class)
  fun generationCanBeCancelled() {
    GenericMonetMask.foreground(IntArray(1024), 32) { throw CancellationException() }
  }

  @Test fun generationMetadataTracksBothSwitchStatesAndKeepsLegacyOff() {
    assertFalse(IconGenerationManifest.decode(IconGenerationManifest().encode()).monetUnadaptedIcons)
    for (schema in listOf(11, 12)) for (enabled in listOf(false, true)) {
      val value = IconGenerationManifest(schemaVersion = schema, monetUnadaptedIcons = enabled)
      assertEquals(value, IconGenerationManifest.decode(value.encode()))
      val missing = value.encode().toString(Charsets.UTF_8).lines()
        .filterNot { it.startsWith("monet-fallback") }.joinToString("\n").toByteArray()
      assertThrows(IllegalArgumentException::class.java) { IconGenerationManifest.decode(missing) }
    }
  }
}
