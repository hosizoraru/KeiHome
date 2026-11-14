package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
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
    assertFalse(PerfectCompositeForegroundProof.repair(
      changedFlat, fixture.background, fixture.foreground, pixels, {},
    ))
    assertArrayEquals("One mismatched covered source pixel must reject every replacement", before, pixels)
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
    val fixture = retainedCalendar()
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
    val hashes = listOf(
      "0dd07584a436420ca2c3d0ee686e0a223436c6edeb3f294856bd5f2a75d258e8",
      "be52e29a6a43eaa4a35376e670f6d117f86efb66bc5ab2ff80fa114d28b4e47f",
      "28dbe8937aa4546e528825bb468b2e967d6350e78d6739715b71e840957a1055",
    )
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val files = hashes.map { File(context.filesDir, "perfect-icons/blobs/$it") }
    assumeTrue("Requires the retained upstream source fixture already cached locally; never downloads it",
      files.all { it.isFile })
    val bytes = files.mapIndexed { i, file ->
      file.readBytes().also { assertEquals("Retained source identity", hashes[i], PerfectIconFormat.sha(it)) }
    }
    return Fixture(bytes[0], bytes[1], bytes[2])
  }

  private fun existingNight(flat: ByteArray): IntArray {
    val bytes = PerfectStaticNightRenderer.render(listOf(flat), null).night
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    return try {
      assertEquals(SIZE, bitmap.width)
      assertEquals(SIZE, bitmap.height)
      IntArray(SIZE * SIZE).also { bitmap.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
    } finally { bitmap.recycle() }
  }

  private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
  }.toByteArray()

  private companion object {
    const val SIZE = 512
  }
}
