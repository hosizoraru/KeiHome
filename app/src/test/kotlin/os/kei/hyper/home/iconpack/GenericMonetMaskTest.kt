package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class GenericMonetMaskTest {
  @Test fun fullBleedArtworkGetsSymmetricSpaceOnItsCompletedCanvas() {
    val size = 100
    val original = IntArray(size * size) { 0xffffffff.toInt() }
    val result = GenericMonetMask.insetFullBleed(original, size)
    val xs = (0 until size).filter { result[50 * size + it] ushr 24 > 0 }
    val ys = (0 until size).filter { result[it * size + 50] ushr 24 > 0 }
    assertEquals((20 until 80).toList(), xs)
    assertEquals(xs, ys)
    assertTrue(original.all { it == 0xffffffff.toInt() })
  }

  @Test fun existingWhitespaceAndThinProtrusionsDoNotTriggerAnotherInset() {
    val size = 100
    val center = IntArray(size * size) { i ->
      if (i % size in 24..75 && i / size in 24..75) -1 else 0x00ffffff
    }
    assertSame(center, GenericMonetMask.insetFullBleed(center, size))
    val thin = center.copyOf()
    for (x in 0 until size) thin[50 * size + x] = -1
    assertSame(thin, GenericMonetMask.insetFullBleed(thin, size))
  }

  @Test fun transparentCornerPlatesStillCountAsFullBleed() {
    val size = 100
    val plate = GenericMonetMask.background(IntArray(size * size) { -1 }, size)
    val result = GenericMonetMask.insetFullBleed(plate, size)
    assertNotSame(plate, result)
    assertEquals(0, result[50 * size + 19] ushr 24)
    assertEquals(255, result[50 * size + 20] ushr 24)
    assertEquals(255, result[50 * size + 79] ushr 24)
    assertEquals(0, result[50 * size + 80] ushr 24)
  }

  @Test fun insetKeepsPartialAlphaAndCanCancelDuringResampling() {
    val mask = IntArray(100 * 100) { 0x80ffffff.toInt() }
    val result = GenericMonetMask.insetFullBleed(mask, 100)
    assertEquals(128, result[50 * 100 + 50] ushr 24)
    var checks = 0
    assertThrows(IllegalStateException::class.java) {
      GenericMonetMask.insetFullBleed(mask, 100) {
        if (++checks > 100) error("cancel during resize")
      }
    }
  }

  @Test fun paddingIdentityRoundTripsAndHistoricalOutputDefaultsToOff() {
    for (generic in listOf(false, true)) for (padding in listOf(false, true)) {
      val value = IconGenerationManifest(schemaVersion = 15, monetUnadaptedIcons = generic,
        monetFullBleedIconPadding = padding)
      assertEquals(value, IconGenerationManifest.decode(value.encode()))
      val missing = value.encode().toString(Charsets.UTF_8).lines()
        .filterNot { it.startsWith("monet-inset") }.joinToString("\n").toByteArray()
      assertThrows(IllegalArgumentException::class.java) { IconGenerationManifest.decode(missing) }
    }
    val old = IconGenerationManifest(schemaVersion = 14, monetUnadaptedIcons = true)
    assertFalse(IconGenerationManifest.decode(old.encode()).monetFullBleedIconPadding)
    assertThrows(IllegalArgumentException::class.java) {
      IconGenerationManifest(schemaVersion = 14, monetFullBleedIconPadding = true).encode()
    }
  }

  @Test fun broadArtworkFillingTheLowerCanvasAlsoGetsInset() {
    val size = 100
    val original = IntArray(size * size) { if (it / size >= 50) 0xffffffff.toInt() else 0 }
    val padded = GenericMonetMask.insetFullBleed(original, size)
    assertNotSame(original, padded)
    assertTrue((0 until size).all { x -> (padded[99 * size + x] ushr 24) == 0 })
    assertEquals(255, padded[65 * size + 50] ushr 24)
  }

  @Test fun insetCarriesTheCompletedOuterSilhouetteWithoutMultiplyingEdgeAlpha() {
    val size = 100
    val original = IntArray(size * size) { 0x80ffffff.toInt() }
    val coverage = IntArray(size * size) { index ->
      val x = index % size; val y = index / size
      if (x < 15 && y < 15 || x >= 85 && y < 15 || x < 15 && y >= 85 || x >= 85 && y >= 85) 0 else 128
    }
    val padded = GenericMonetMask.insetFullBleed(original, size, coverage)
    assertEquals(0, padded[20 * size + 20] ushr 24)
    assertEquals(128, padded[50 * size + 50] ushr 24)
    assertEquals(128, original[0] ushr 24)
  }

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
