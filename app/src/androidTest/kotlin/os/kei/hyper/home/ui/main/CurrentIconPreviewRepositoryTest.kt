package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackPreviews
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.StoredIconPack

@RunWith(AndroidJUnit4::class)
class CurrentIconPreviewRepositoryTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun coalescesRequestsAndKeepsAllMaskLayersOnHits() = runBlocking {
    fixture { source, entry ->
      val calls = AtomicInteger()
      val repository = CurrentIconPreviewRepository(context, { _, _ ->
        calls.incrementAndGet()
        delay(30)
        IconPackPreviews()
      })
      val results = coroutineScope { List(8) { async { repository.load(source, entry) } }.awaitAll() }
      assertEquals(1, calls.get())
      results.forEach { assertSame(results.first(), it) }
      assertTrue(results.first().lightMonochrome != null && results.first().backgroundMask != null)
      assertSame(results.first().lightMonochrome, results.first().darkMonochrome)
      repository.clear()
      val changed = repository.load(source, entry)
      assertEquals(2, calls.get())
      assertNotSame(results.first(), changed)
    }
  }

  @Test fun invalidationDuringLoadingCannotRepublishTheOldResult() = runBlocking {
    fixture { source, entry ->
      val calls = AtomicInteger()
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val repository = CurrentIconPreviewRepository(context, { _, _ ->
        if (calls.incrementAndGet() == 1) {
          started.complete(Unit)
          release.await()
        }
        IconPackPreviews()
      })
      coroutineScope {
        val old = async { repository.load(source, entry) }
        started.await()
        repository.clear()
        release.complete(Unit)
        old.await()
        repository.load(source, entry)
        assertEquals(2, calls.get())
        repository.load(source, entry)
        assertEquals(2, calls.get())
      }
    }
  }

  @Test fun boundsInFlightLoadsAcrossSuspension() = runBlocking {
    fixture { source, entry ->
      val active = AtomicInteger()
      val peak = AtomicInteger()
      val repository = CurrentIconPreviewRepository(context, { _, _ ->
        val count = active.incrementAndGet()
        peak.updateAndGet { maxOf(it, count) }
        try {
          delay(30)
          IconPackPreviews()
        } finally { active.decrementAndGet() }
      })
      coroutineScope {
        List(8) { index -> async { repository.load(source, entry.copy(key = "probe-$index")) } }.awaitAll()
      }
      assertEquals(2, peak.get())
      assertEquals(0, active.get())
    }
  }

  private suspend fun fixture(block: suspend (IconBrowserSource, IconPackEntry) -> Unit) {
    val file = File.createTempFile("preview-cache-probe", ".zip", context.cacheDir)
    try {
      val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
      val bytes = try { ByteArrayOutputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
      } } finally { bitmap.recycle() }
      ZipOutputStream(file.outputStream()).use { zip ->
        for (part in listOf("mask", "bg")) {
          zip.putNextEntry(ZipEntry("fancy_icons/probe/$part.png"))
          zip.write(bytes)
          zip.closeEntry()
        }
      }
      val entry = IconPackEntry("probe", nativeName = "probe")
      val record = StoredIconPack("probe", "probe", 0, IconPackFormat.MIUI_ICONS, file, file, file.length(), null)
      val pack = IconPackDescriptor("probe", "probe", IconPackFormat.MIUI_ICONS, IconPackSource.Imported(record), listOf(entry))
      val source = IconBrowserSource("probe", pack, mapOf("probe" to entry), setOf("probe"), emptySet())
      block(source, entry)
    } finally { file.delete() }
  }
}
