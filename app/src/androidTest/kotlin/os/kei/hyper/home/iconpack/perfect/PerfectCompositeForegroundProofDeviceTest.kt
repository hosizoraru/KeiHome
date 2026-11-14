package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline source fixture; no downloads, pack generation, installation or launcher operations. */
@RunWith(AndroidJUnit4::class)
class PerfectCompositeForegroundProofDeviceTest {
  @Test fun retainedGameCenterCenteredCropRestoresControllerIncludingAntialiasedBottom() {
    val fixture = retainedGameCenter()
    val inputsBefore = listOf(fixture.flat.clone(), fixture.background.clone(), fixture.foreground.clone())
    val generic = PerfectStaticNightRenderer.render(listOf(fixture.flat), null)
    val result = PerfectStaticNightRenderer.render(
      listOf(fixture.flat), null, equivalentLayers = listOf(fixture.background, fixture.foreground),
    )
    assertArrayEquals("Source-proven night repair must leave day bytes unchanged", generic.day, result.day)

    // The retained native pair is 351 square; the authored preview is its exact central 235 square.
    // Fixed source geometry, independent of the proof's computed crop and output selections.
    val crop = Rect(58, 58, 293, 293)
    val reference = PerfectStaticNightRenderer.render(
      listOf(croppedPng(fixture.background, crop), croppedPng(fixture.foreground, crop)), null,
    )
    val expected = pixels(reference.night)
    val actual = pixels(result.night)
    val before = pixels(generic.night)
    val repaired = before.clone()
    assertTrue("The exact native crop must pass the foreground-only proof",
      PerfectCompositeForegroundProof.repair(
        fixture.flat, fixture.background, fixture.foreground, repaired, {},
      ))
    val coverage = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    val foreground = requireNotNull(BitmapFactory.decodeByteArray(
      fixture.foreground, 0, fixture.foreground.size))
    val foregroundPixels = try {
      assertEquals(351, foreground.width)
      assertEquals(351, foreground.height)
      assertEquals("Fixed faint bottom edge from upstream", 60, Color.alpha(foreground.getPixel(91, 234)))
      assertEquals("The controller's lower body is intentionally translucent",
        Color.argb(139, 255, 255, 255), foreground.getPixel(175, 200))
      Canvas(coverage).drawBitmap(foreground, crop, Rect(0, 0, SIZE, SIZE),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
      IntArray(SIZE * SIZE).also { coverage.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
    } finally {
      foreground.recycle()
      coverage.recycle()
    }
    var changed = 0
    var bottomEdges = 0
    var uncovered = 0
    for (i in actual.indices) {
      assertEquals("Caller alpha at $i", before[i] ushr 24, actual[i] ushr 24)
      assertEquals("Raw partial repair preserves caller alpha at $i",
        before[i] ushr 24, repaired[i] ushr 24)
      for (shift in 0..2) {
        assertTrue("Whole native crop RGB at $i, channel $shift",
          kotlin.math.abs((actual[i] ushr (shift * 8) and 255) -
            (expected[i] ushr (shift * 8) and 255)) <= 2)
      }
      if (foregroundPixels[i] ushr 24 == 0) {
        uncovered++
        assertEquals("Partial repair preserves unmodeled background at $i", before[i], repaired[i])
        assertNeutralDark("Every uncovered native background pixel at $i", actual[i])
      } else {
        // Authored lower controller edge is at native y234..249, flat y176..191.
        if (i / SIZE in 384..418 && (foregroundPixels[i] ushr 24) in 1..254) bottomEdges++
      }
      if (actual[i] != before[i]) changed++
    }
    assertTrue("Exercise the whole native background, not only controller coverage", uncovered > 100_000)
    val gap = 370 * SIZE + 256 // Native (175,228), below the center and between the controller tips.
    assertEquals("Fixed lower central gap is uncovered", 0, foregroundPixels[gap] ushr 24)
    assertNeutralDark("The visible blue lower-gap patch must disappear", actual[gap])
    assertTrue("The lower gap must retain the dark end of the background gradient",
      Color.red(actual[gap]) in 20..32)
    assertTrue("Exercise the actual antialiased lower edge", bottomEdges > 100)
    assertTrue("Must replace the jagged generic classification", changed > 100)
    assertEquals("Fixed translucent body landmark must use the native crop's composition",
      expected[310 * SIZE + 256] and 0xffffff, actual[310 * SIZE + 256] and 0xffffff)
    assertArrayEquals(inputsBefore[0], fixture.flat)
    assertArrayEquals(inputsBefore[1], fixture.background)
    assertArrayEquals(inputsBefore[2], fixture.foreground)

    val customAlpha = before.clone()
    for (i in customAlpha.indices step 97) {
      customAlpha[i] = (customAlpha[i] and 0xffffff) or (((i / 97) % 256) shl 24)
    }
    val callerBefore = customAlpha.clone()
    assertTrue(PerfectCompositeForegroundProof.repair(
      fixture.flat, fixture.background, fixture.foreground, customAlpha, {},
    ))
    for (i in customAlpha.indices) {
      assertEquals("Arbitrary caller alpha at $i", callerBefore[i] ushr 24, customAlpha[i] ushr 24)
    }
    val output = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    File(output, "gamecenter-proof-day.png").writeBytes(result.day)
    File(output, "gamecenter-proof-night.png").writeBytes(result.night)
    File(output, "gamecenter-proof-before.png").writeBytes(generic.night)
  }

  @Test fun broadMultihueSmoothDitheredBackgroundIsOneNeutralNightPlate() {
    val width = 96
    val source = multihueBackground(width)
    val before = source.clone()
    val mask = PerfectNightPixels.backgroundPlate(source, width, width, {})
    assertTrue("Wide hue gradients must be admitted as a complete native plate",
      mask != null && mask.all { it })
    assertArrayEquals("Classification must not alter source artwork", before, source)
    PerfectNightPixels.recolorPlate(source, width, width, requireNotNull(mask), {})
    for (i in source.indices) {
      assertEquals(before[i] ushr 24, source[i] ushr 24)
      assertEquals(Color.red(source[i]), Color.green(source[i]))
      assertEquals(Color.red(source[i]), Color.blue(source[i]))
      assertTrue("Retain the full existing dark gradient", Color.red(source[i]) in 21..56)
    }
    assertEquals(56, Color.red(source.first()))
    assertEquals(21, Color.red(source.last()))
  }

  @Test fun multihueTexturedAndSmallHardEdgedArtworkAreNotWholeBackgroundPlates() {
    val width = 96
    val textured = IntArray(width * width) { i ->
      val x = i % width
      val y = i / width
      val texture = (x * 73) xor (y * 151) xor (x * y * 19)
      Color.rgb(texture and 255, (texture * 37) and 255, (texture * 17 + 113) and 255)
    }
    val hardEdge = multihueBackground(width)
    // Only 32 edge pairs: removing the range guard with the old 0.5% roughness budget
    // would swallow this authored mark. The new full-gradient gate rejects every hard edge.
    for (y in 44..51) for (x in 44..51) hardEdge[y * width + x] = Color.RED
    for ((name, source) in listOf("textured color artwork" to textured, "small hard edge" to hardEdge)) {
      val before = source.clone()
      val mask = PerfectNightPixels.backgroundPlate(source, width, width, {})
      assertFalse("$name must not be reclassified as a complete smooth plate",
        mask != null && mask.all { it })
      assertArrayEquals("Rejected classification must preserve $name", before, source)
    }
  }

  @Test fun retainedGameCenterDisagreeingFaintBottomEdgeRejectsTheWholeCropRepair() {
    val fixture = retainedGameCenter()
    val flat = requireNotNull(BitmapFactory.decodeByteArray(fixture.flat, 0, fixture.flat.size))
    val mutable = requireNotNull(flat.copy(Bitmap.Config.ARGB_8888, true))
    flat.recycle()
    val changed = try {
      // (91,234) in the native foreground maps exactly to this alpha-60 edge in the flat grid.
      assertEquals(235, mutable.width)
      assertEquals(235, mutable.height)
      mutable.setPixel(33, 176, Color.MAGENTA)
      png(mutable)
    } finally { mutable.recycle() }
    val pixels = existingNight(fixture.flat)
    val before = pixels.clone()
    assertFalse(PerfectCompositeForegroundProof.repair(
      changed, fixture.background, fixture.foreground, pixels, {},
    ))
    assertArrayEquals(before, pixels)
  }

  @Test fun nonIntegralCenteredCropRejectsWithoutRoundingOrResampling() {
    val fixture = retainedGameCenter()
    // 351 - 234 is odd: no single integer centered source crop is authorized.
    val changed = croppedPng(fixture.flat, Rect(0, 0, 234, 234))
    val pixels = existingNight(changed)
    val before = pixels.clone()
    assertFalse(PerfectCompositeForegroundProof.repair(
      changed, fixture.background, fixture.foreground, pixels, {},
    ))
    assertArrayEquals(before, pixels)
  }

  @Test fun retainedCalendarRestoresDateAndPreservesUnmodeledWeekday() {
    val fixture = retainedCalendar()
    val pixels = existingNight(fixture.flat)
    val before = pixels.clone()
    val flat = requireNotNull(BitmapFactory.decodeByteArray(fixture.flat, 0, fixture.flat.size))
    try {
      // Independently located in the authored "24" and blue weekday, not sampled from the repair.
      assertEquals(Color.BLACK, flat.getPixel(194, 202))
      assertEquals(Color.rgb(52, 130, 255), flat.getPixel(162, 103))
    } finally { flat.recycle() }

    assertTrue(PerfectCompositeForegroundProof.repair(
      fixture.flat, fixture.background, fixture.foreground, pixels, {},
    ))
    val date = pixels[295 * SIZE + 283]
    assertTrue("The date interior must become readable", Color.red(date) >= 190 &&
      Color.green(date) >= 190 && Color.blue(date) >= 190)
    assertEquals("The unrelated weekday must survive byte-for-byte",
      before[150 * SIZE + 236], pixels[150 * SIZE + 236])
    // Host-measured source date extent: (104,147)..(250,244) on a 351px canvas.
    // This generous 512px rectangle includes the filtering footprint without deriving a proof mask.
    val dateBounds = Rect(149, 212, 369, 359)
    var changed = 0
    for (i in pixels.indices) {
      assertEquals("Alpha must remain unchanged at $i", before[i] ushr 24, pixels[i] ushr 24)
      if (!dateBounds.contains(i % SIZE, i / SIZE)) {
        assertEquals("Artwork outside the authored date must remain unchanged at $i", before[i], pixels[i])
      }
      if (pixels[i] != before[i]) changed++
    }
    assertTrue("The proof must repair actual date pixels", changed > 1000)
  }

  @Test fun dynamicCalendarCoverHasCleanDateAndWeekdayCoverage() {
    val fixture = retainedCalendar()
    val before = existingNight(fixture.flat)
    val actual = before.clone()
    assertTrue(PerfectCompositeForegroundProof.repair(
      fixture.flat, fixture.background, fixture.foreground, actual, {},
      nativeNightComposition = true,
    ))
    val flat = requireNotNull(BitmapFactory.decodeByteArray(fixture.flat, 0, fixture.flat.size))
    val background = requireNotNull(BitmapFactory.decodeByteArray(fixture.background, 0, fixture.background.size))
    val foreground = requireNotNull(BitmapFactory.decodeByteArray(
      fixture.foreground, 0, fixture.foreground.size))
    val nightBackgroundBytes = requireNotNull(PerfectDynamicNightRenderer.render(fixture.background, true, {}))
    val nightBackground = requireNotNull(BitmapFactory.decodeByteArray(
      nightBackgroundBytes, 0, nightBackgroundBytes.size))
    val nativeNight = Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888)
    val expected = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    val coverage = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    try {
      // Independent fixture oracle: opaque neutral date tones are one ink. The weekday's
      // known blue ink is unmatted using its red channel, not the production coverage solver.
      fun mix(plate: Int, ink: Int, coverage: Double): Int {
        var result = 255 shl 24
        for (shift in intArrayOf(0, 8, 16)) {
          val channel = ((plate ushr shift and 255) * (1 - coverage) +
            (ink ushr shift and 255) * coverage + 0.5).toInt().coerceIn(0, 255)
          result = result or (channel shl shift)
        }
        return result
      }
      for (y in 0 until flat.height) for (x in 0 until flat.width) {
        val plate = nightBackground.getPixel(x, y)
        val alpha = Color.alpha(foreground.getPixel(x, y))
        val source = flat.getPixel(x, y)
        val backgroundPixel = background.getPixel(x, y)
        val result = if (alpha > 0) {
          mix(plate, 0xffd6d6d6.toInt(), alpha / 255.0)
        } else if (source != backgroundPixel) {
          val coverage = ((Color.red(backgroundPixel) - Color.red(source)).toDouble() /
            (Color.red(backgroundPixel) - 52)).coerceIn(0.0, 1.0)
          mix(plate, 0xff2c6dd6.toInt(), coverage)
        } else plate
        nativeNight.setPixel(x, y, result)
      }
      val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      val bounds = Rect(0, 0, SIZE, SIZE)
      Canvas(expected).drawBitmap(nativeNight, null, bounds, paint)
      Canvas(coverage).drawBitmap(foreground, null, bounds, paint)
      var covered = 0
      var edges = 0
      var weekdayFringe = 0
      for (i in actual.indices) {
        val x = i % SIZE
        val y = i / SIZE
        val alpha = Color.alpha(coverage.getPixel(x, y))
        assertEquals("Caller alpha at $i", before[i] ushr 24, actual[i] ushr 24)
        if (alpha > 0) {
          covered++
          if (alpha < 255) edges++
          if (alpha == 255) {
            for (shift in intArrayOf(0, 8, 16)) {
              assertTrue("No opaque date rim at $i", (actual[i] ushr shift and 255) in 213..214)
            }
          }
        }
        val reference = expected.getPixel(x, y)
        for (shift in 0..2) {
          assertTrue("Independent unmatted text oracle at $i, channel $shift",
            kotlin.math.abs((actual[i] ushr (shift * 8) and 255) -
              (reference ushr (shift * 8) and 255)) <= 2)
        }
        if (x in 190..320 && y in 120..185) {
          assertTrue("No white fragment in weekday area at $i", Color.red(actual[i]) <= 56)
          if (Color.blue(actual[i]) > Color.red(actual[i]) + 8) weekdayFringe++
        }
      }
      assertTrue("Exercise the date interior", covered > 1000)
      assertTrue("Exercise the date's original partial alpha", edges > 100)
      assertTrue("Exercise the unmatted blue weekday", weekdayFringe > 1000)
      val rendered = PerfectStaticNightRenderer.render(listOf(fixture.flat), null,
        equivalentLayers = listOf(fixture.background, fixture.foreground), nativeNightComposition = true)
      assertArrayEquals("The exported root must retain the independently verified clean snapshot",
        actual, pixels(rendered.night))
      if (InstrumentationRegistry.getArguments().getString("exportPerfectCoverProof") == "true") {
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "qa-cover-proof")
        output.mkdirs()
        File(output, "calendar-day.png").writeBytes(rendered.day)
        File(output, "calendar-night.png").writeBytes(rendered.night)
        val old = Bitmap.createBitmap(before, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
          File(output, "calendar-night-before.png").outputStream().use {
            old.compress(Bitmap.CompressFormat.PNG, 100, it)
          }
        } finally { old.recycle() }
      }
    } finally {
      listOf(flat, background, foreground, nightBackground, nativeNight, expected, coverage)
        .forEach { it.recycle() }
    }
  }

  @Test fun oneDisagreeingFaintSourceEdgeRejectsTheWholeRepair() {
    val fixture = retainedCalendar()
    val flat = requireNotNull(BitmapFactory.decodeByteArray(fixture.flat, 0, fixture.flat.size))
    val mutable = requireNotNull(flat.copy(Bitmap.Config.ARGB_8888, true))
    flat.recycle()
    val changedFlat = try {
      // Host measurement: this authored date edge has alpha 16, its lowest nonzero alpha.
      mutable.setPixel(127, 147, Color.MAGENTA)
      png(mutable)
    } finally { mutable.recycle() }
    val pixels = existingNight(fixture.flat)
    val before = pixels.clone()
    for (native in listOf(false, true)) {
      assertFalse(PerfectCompositeForegroundProof.repair(
        changedFlat, fixture.background, fixture.foreground, pixels, {},
        nativeNightComposition = native,
      ))
      assertArrayEquals("One mismatched covered source pixel must reject every replacement", before, pixels)
    }
  }

  @Test fun differentAuthoredGridRejectsWithoutNormalizingItIntoAProof() {
    val fixture = retainedCalendar()
    val source = requireNotNull(BitmapFactory.decodeByteArray(fixture.foreground, 0, fixture.foreground.size))
    val resized = Bitmap.createScaledBitmap(source, 350, 350, true)
    val foreground = try { png(resized) } finally {
      if (resized !== source) resized.recycle()
      source.recycle()
    }
    val pixels = existingNight(fixture.flat)
    val before = pixels.clone()
    assertFalse(PerfectCompositeForegroundProof.repair(
      fixture.flat, fixture.background, foreground, pixels, {},
    ))
    assertArrayEquals(before, pixels)
  }

  @Test fun cancellationAtTheLastFenceDoesNotPublishStagedPixels() {
    assertLastFenceIsAtomic(retainedCalendar())
  }

  @Test fun centeredCropCancellationAtTheLastFenceDoesNotPublishStagedPixels() {
    assertLastFenceIsAtomic(retainedGameCenter())
  }

  private fun assertLastFenceIsAtomic(fixture: Fixture) {
    val before = existingNight(fixture.flat)
    var totalChecks = 0
    assertTrue(PerfectCompositeForegroundProof.repair(
      fixture.flat, fixture.background, fixture.foreground, before.clone(), { totalChecks++ },
    ))
    val pixels = before.clone()
    var checks = 0
    try {
      PerfectCompositeForegroundProof.repair(
        fixture.flat, fixture.background, fixture.foreground, pixels,
        { if (++checks == totalChecks) throw CancellationException("last publication fence") },
      )
      fail("Cancellation must propagate")
    } catch (_: CancellationException) {
      assertEquals(totalChecks, checks)
      assertArrayEquals("Staged repairs must not escape on cancellation", before, pixels)
    }
  }

  private data class Fixture(val flat: ByteArray, val background: ByteArray, val foreground: ByteArray)

  private fun retainedCalendar(): Fixture {
    // Exact PNG bodies from repository-complete.zip, themes/HyperOS4/layer_animating_icons/
    // com.android.calendar/{quiet,0/quiet,1/quiet}/quietImage.png. No synthetic substitute.
    return retainedFixture(listOf(
      "0dd07584a436420ca2c3d0ee686e0a223436c6edeb3f294856bd5f2a75d258e8",
      "be52e29a6a43eaa4a35376e670f6d117f86efb66bc5ab2ff80fa114d28b4e47f",
      "28dbe8937aa4546e528825bb468b2e967d6350e78d6739715b71e840957a1055",
    ))
  }

  private fun retainedGameCenter(): Fixture {
    // Exact repository-complete.zip themes/HyperOS4/icons/com.xiaomi.gamecenter{.png,/0.png,/1.png}.
    return retainedFixture(listOf(
      "51c91a46aeb9f4f010b223725331f3e3182a88031afb7a6e8ddf24c1a6030d1a",
      "44d0e17820d03aeeeb29dd0b426496be6b029d2caf6c2bb6946b0c46af0d4fbe",
      "44053d9381325b522e3073cd1db7229281e69be6685df2ff307d937174cfea3f",
    ))
  }

  private fun retainedFixture(hashes: List<String>): Fixture {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val fixtureDirectory = InstrumentationRegistry.getArguments()
      .getString("perfectCompositeForegroundFixtures")
      ?.let(::File) ?: File(context.filesDir, "perfect-icons/blobs")
    val files = hashes.map { File(fixtureDirectory, it) }
    assumeTrue("Requires the retained upstream source fixture already cached locally; never downloads it",
      files.all { it.isFile })
    val bytes = files.mapIndexed { i, file ->
      file.readBytes().also { source ->
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source)
          .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("Retained source identity", hashes[i], sha256)
      }
    }
    return Fixture(bytes[0], bytes[1], bytes[2])
  }

  private fun existingNight(flat: ByteArray): IntArray {
    return pixels(PerfectStaticNightRenderer.render(listOf(flat), null).night)
  }

  private fun assertNeutralDark(message: String, pixel: Int) {
    val channels = intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
    assertTrue(message, channels.max() - channels.min() <= 1 && channels.all { it in 16..56 })
  }

  private fun multihueBackground(width: Int): IntArray = IntArray(width * width) { i ->
    val x = i % width
    val y = i / width
    val dither = if ((x + y) % 2 == 0) 2 else -2
    Color.argb(254 + (x + y) % 2,
      30 + 180 * x / (width - 1) + dither,
      40 + 160 * y / (width - 1) + dither,
      220 - 180 * (x + y) / (2 * (width - 1)) + dither)
  }

  private fun pixels(bytes: ByteArray): IntArray {
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    return try {
      assertEquals(SIZE, bitmap.width)
      assertEquals(SIZE, bitmap.height)
      IntArray(SIZE * SIZE).also { bitmap.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
    } finally { bitmap.recycle() }
  }

  private fun croppedPng(bytes: ByteArray, crop: Rect): ByteArray {
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    val cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
    return try { png(cropped) } finally {
      if (cropped !== bitmap) cropped.recycle()
      bitmap.recycle()
    }
  }

  private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
  }.toByteArray()

  private companion object {
    const val SIZE = 512
  }
}
