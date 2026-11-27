package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class GenericMonetMaskTest {
  @Test fun flatStencilRecoversTheSameCoverageWithEitherColorOnThePlate() {
    val size = 64
    val white = 0xffffffff.toInt()
    val blue = 0xff1464f4.toInt()
    val half = 0xff8ab2fa.toInt()
    fun mask(plate: Int, ink: Int): IntArray {
      val pixels = IntArray(size * size) { plate }
      for (y in 22..41) {
        pixels[y * size + 21] = half
        for (x in 22..41) pixels[y * size + x] = ink
      }
      return requireNotNull(GenericMonetMask.foreground(pixels, size))
    }
    val positive = mask(white, blue)
    val negative = mask(blue, white)
    assertEquals(0, positive[32 * size + 20] ushr 24)
    assertEquals(255, positive[32 * size + 22] ushr 24)
    assertTrue(positive[32 * size + 21] ushr 24 in 127..129)
    assertTrue(negative[32 * size + 21] ushr 24 in 127..129)
  }

  @Test fun layeredCoveragePreservesFaintDetailsAndRejectsAnOpaqueOverlay() {
    val size = 64
    val pixels = IntArray(size * size)
    val plate = IntArray(pixels.size) { -1 }
    for (y in 20..43) for (x in 20..43) pixels[y * size + x] = 0xfff8f8f8.toInt()
    pixels[19 * size + 31] = 0x20112233
    plate[20 * size + 31] = 0x80ffffff.toInt()
    val mask = requireNotNull(GenericMonetMask.layeredForeground(pixels, plate, size) {})
    assertEquals(32, mask[19 * size + 31] ushr 24)
    assertEquals(128, mask[20 * size + 31] ushr 24)
    assertEquals(255, mask[21 * size + 31] ushr 24)
    assertNull(GenericMonetMask.layeredForeground(plate, plate, size) {})
    assertNull(GenericMonetMask.layeredForeground(IntArray(pixels.size), plate, size) {})
  }

  @Test fun layeredForegroundCarvesInteriorDetailsOutOfDominantBodyInsteadOfBlobbing() {
    val size = 64
    val fg = IntArray(size * size)
    val plate = IntArray(size * size) { -1 }
    // Green disc body (like WeType/Settings) with a white interior cross.
    for (y in 12..51) for (x in 12..51) fg[y * size + x] = 0xff00c080.toInt()
    for (y in 26..37) for (x in 22..41) fg[y * size + x] = 0xffffffff.toInt()
    val mask = requireNotNull(GenericMonetMask.layeredForeground(fg, plate, size) {})
    assertEquals(0, mask[4 * size + 4] ushr 24)
    assertEquals(255, mask[16 * size + 16] ushr 24)
    assertTrue("Interior high-contrast mark must be carved out", (mask[32 * size + 32] ushr 24) < 32)
  }

  @Test fun layeredForegroundExtractsMultiComponentAndCollageIconsCleanly() {
    val size = 64
    val plate = IntArray(size * size) { -1 }
    // Two separate components (like Calculator): solid left glyph + boxed right glyph with interior symbol.
    val fg = IntArray(size * size)
    for (y in 14..30) for (x in 10..26) fg[y * size + x] = 0xff555555.toInt()
    for (y in 14..46) for (x in 34..54) fg[y * size + x] = 0xffff6600.toInt()
    for (y in 26..34) for (x in 39..49) fg[y * size + x] = 0xffffffff.toInt()
    val multi = requireNotNull(GenericMonetMask.layeredForeground(fg, plate, size) {})
    assertEquals(255, multi[20 * size + 18] ushr 24)
    assertEquals(255, multi[18 * size + 44] ushr 24)
    assertTrue((multi[30 * size + 44] ushr 24) < 32)

    // Multi-color collage (like Gallery petals) with white composite background:
    val collageFg = IntArray(size * size)
    val composite = IntArray(size * size) { 0xffffffff.toInt() }
    val colors = intArrayOf(0xffff3b30.toInt(), 0xffffcc00.toInt(), 0xff34c759.toInt(), 0xff007aff.toInt())
    for (y in 16..47) for (x in 16..47) {
      val quadrant = (if (y >= 32) 2 else 0) + (if (x >= 32) 1 else 0)
      val color = colors[quadrant]
      collageFg[y * size + x] = color
      composite[y * size + x] = color
    }
    val collage = requireNotNull(GenericMonetMask.layeredForeground(collageFg, composite, plate, size) {})
    assertEquals(0, collage[4 * size + 4] ushr 24)
    val distinctAlphas = listOf(
      collage[24 * size + 24] ushr 24,
      collage[24 * size + 40] ushr 24,
      collage[40 * size + 24] ushr 24,
      collage[40 * size + 40] ushr 24,
    )
    assertTrue("Collage must preserve tonal separation instead of flattening to 255",
      distinctAlphas.max() - distinctAlphas.min() >= 32)
  }

  @Test fun layeredForegroundPreservesThirdAccentToneInsideDarkCutout() {
    val size = 64
    val fg = IntArray(size * size)
    val plate = IntArray(size * size) { -1 }
    // Cainiao-style 3-tone robot: white body + black visor + bright cyan smile & feet.
    for (y in 10..50) for (x in 12..51) fg[y * size + x] = 0xffffffff.toInt()
    for (y in 16..34) for (x in 18..45) fg[y * size + x] = 0xff000000.toInt()
    for (y in 22..28) for (x in 26..37) fg[y * size + x] = 0xff00e0c0.toInt()
    for (y in 51..57) for (x in 22..28) fg[y * size + x] = 0xff00e0c0.toInt()
    val mask = requireNotNull(GenericMonetMask.layeredForeground(fg, plate, size) {})
    assertEquals(255, mask[42 * size + 32] ushr 24)
    assertTrue("Dark visor cutout stays dark", (mask[18 * size + 20] ushr 24) < 32)
    assertTrue("Cyan smile inside visor stays visible", (mask[25 * size + 32] ushr 24) >= 180)
    assertTrue("Cyan feet stay visible", (mask[54 * size + 25] ushr 24) >= 180)
  }

  @Test fun multiColorMonochromeDetectorDistinguishesTrueStencilsFromFullColorDrawables() {
    val size = 64
    val stencil = IntArray(size * size)
    for (y in 16..47) for (x in 16..47) stencil[y * size + x] = 0xff000000.toInt()
    assertFalse(OriginalAppIcons.isMultiColorArtwork(stencil))

    val fullColor = IntArray(size * size)
    for (y in 12..51) for (x in 12..51) fullColor[y * size + x] = 0xff00c080.toInt()
    for (y in 24..39) for (x in 20..43) fullColor[y * size + x] = 0xffffffff.toInt()
    assertTrue(OriginalAppIcons.isMultiColorArtwork(fullColor))
  }
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
