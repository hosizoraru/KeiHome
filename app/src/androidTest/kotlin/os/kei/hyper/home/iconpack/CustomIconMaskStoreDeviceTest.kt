package os.kei.hyper.home.iconpack

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomIconMaskStoreDeviceTest {
  private val rectangle = "M0 0H100V100H0Z"
  private fun xml(path: String = rectangle) =
    """<IconTransform><Author>Test</Author><Config name="ConfigIconMask" value="$path"/></IconTransform>"""

  private class SourceProvider(val source: File) : ContentProvider() {
    var displayName = "mask.xml"
    var cursorClosed = false
    var pipe: Array<ParcelFileDescriptor>? = null
    var openedDescriptor: ParcelFileDescriptor? = null
    val opened = CountDownLatch(1)
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/xml"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
      selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
      object : MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)) {
        init { addRow(arrayOf(displayName)) }
        override fun close() { cursorClosed = true; super.close() }
      }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
      (pipe?.get(0) ?: ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).also {
        openedDescriptor = it
        opened.countDown()
      }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
  }

  private class Fixture : AutoCloseable {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(base.cacheDir, "custom-mask-test-${UUID.randomUUID()}").apply { mkdirs() }
    val source = File(root, "source.xml")
    val provider = SourceProvider(source).apply {
      attachInfo(base, ProviderInfo().apply { authority = "test.custom.mask" })
    }
    val resolver = ContentResolver.wrap(provider)
    val context = object : ContextWrapper(base) {
      override fun getFilesDir(): File = root
      override fun getApplicationContext(): Context = this
      override fun getContentResolver(): ContentResolver = resolver
    }
    val uri: Uri = Uri.parse("content://test.custom.mask/mask.xml")
    val store = CustomIconMaskStore(context)
    val record get() = File(root, "icon-shape/custom-mask.json")
    override fun close() {
      provider.pipe?.forEach { runCatching { it.close() } }
      root.deleteRecursively()
    }
  }

  @Test fun roundTripCanonicalDigestSafeNameAndResetStayInPrivateStorage() = runBlocking {
    Fixture().use { fixture ->
      fixture.source.writeText(xml())
      fixture.provider.displayName = " \u202eｍａｓｋ / \\\n" + "x".repeat(200)
      assertNull(fixture.store.read())
      val first = fixture.store.import(fixture.uri)
      assertTrue(fixture.provider.cursorClosed)
      assertFalse(fixture.provider.openedDescriptor!!.fileDescriptor.valid())
      assertEquals("M 0 0 H 100 V 100 H 0 Z", first.pathData)
      assertTrue(first.name.startsWith("mask "))
      assertFalse(first.name.any { it == '/' || it == '\\' || it == '\u202e' || it == '\n' })
      assertEquals(128, first.name.codePointCount(0, first.name.length))
      assertTrue(first.digest.matches(Regex("[0-9a-f]{64}")))
      assertEquals(first, CustomIconMaskStore(fixture.context).read())
      assertEquals(first, withContext(Dispatchers.IO) { fixture.store.readNow() })
      fixture.source.writeText(xml("M+0.0 0e0 H1e2 V100.00 H-0 Z"))
      assertEquals(first.digest, fixture.store.import(fixture.uri).digest)
      assertTrue(fixture.record.length() <= 16 * 1024)
      fixture.store.reset()
      assertFalse(fixture.record.exists())
      assertNull(fixture.store.read())
    }
  }

  @Test fun invalidXmlGeometryOverlimitAndWriteFailurePreserveTheGoodRecord() = runBlocking {
    Fixture().use { fixture ->
      fixture.source.writeText(xml())
      val good = fixture.store.import(fixture.uri)
      val before = fixture.record.readBytes()
      for (invalid in listOf("<Other/>", xml("M0 0L0 0Z"), xml("M0 0H101V100H0Z"),
        xml() + " ".repeat(16 * 1024),
        """<!DOCTYPE IconTransform [<!ENTITY x "bad">]>""" + xml())) {
        fixture.source.writeText(invalid)
        assertTrue(runCatching { fixture.store.import(fixture.uri) }.isFailure)
        assertArrayEquals(before, fixture.record.readBytes())
        assertEquals(good, fixture.store.read())
      }
      assertTrue(runCatching { fixture.store.import(Uri.fromFile(fixture.source)) }.isFailure)
      fixture.source.writeText(xml("M10 10H90V90H10Z"))
      val blocker = File("${fixture.record.path}.new").apply { mkdirs() }
      assertTrue(runCatching { fixture.store.import(fixture.uri) }.isFailure)
      assertArrayEquals(before, fixture.record.readBytes())
      assertTrue(blocker.delete())
      assertEquals(good, fixture.store.read())
    }
  }

  @Test fun cancellationClosesSourceAndPreservesPreviousRecord() = runBlocking {
    Fixture().use { fixture ->
      fixture.source.writeText(xml())
      val good = fixture.store.import(fixture.uri)
      val before = fixture.record.readBytes()
      // A fresh provider latch is needed after the first completed import.
      val provider = SourceProvider(fixture.source).apply {
        attachInfo(fixture.base, ProviderInfo().apply { authority = "test.custom.mask" })
        pipe = ParcelFileDescriptor.createPipe()
      }
      val context = object : ContextWrapper(fixture.context) {
        override fun getApplicationContext(): Context = this
        override fun getContentResolver(): ContentResolver = ContentResolver.wrap(provider)
      }
      val import = launch(Dispatchers.Default) { CustomIconMaskStore(context).import(fixture.uri) }
      try {
        assertTrue(withContext(Dispatchers.IO) { provider.opened.await(5, TimeUnit.SECONDS) })
        import.cancel()
        withContext(Dispatchers.IO) {
          // Cancellation may already have closed the read end before this write.
          runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(provider.pipe!![1]).use {
              it.write(xml("M10 10H90V90H10Z").toByteArray())
            }
          }
        }
        import.join()
        assertTrue(import.isCancelled)
        assertFalse(provider.openedDescriptor!!.fileDescriptor.valid())
        assertArrayEquals(before, fixture.record.readBytes())
        assertEquals(good, fixture.store.read())
      } finally {
        provider.pipe?.forEach { runCatching { it.close() } }
        import.cancelAndJoin()
      }
    }
  }

  @Test fun recoversAtomicBackupRejectsTamperingAndRejectsMainThreadRead() = runBlocking {
    Fixture().use { fixture ->
      fixture.source.writeText(xml())
      val good = fixture.store.import(fixture.uri)
      assertTrue(fixture.record.renameTo(File("${fixture.record.path}.bak")))
      File("${fixture.record.path}.new").writeText("incomplete")
      assertEquals(good, fixture.store.read())
      fixture.record.writeText(fixture.record.readText().replace(good.digest, "0".repeat(64)))
      assertNull(fixture.store.read())
      fixture.store.reset()
      assertFalse(fixture.record.exists())
      fixture.record.writeBytes(ByteArray(16 * 1024 + 1))
      assertNull(fixture.store.read())
      fixture.store.reset()
      assertFalse(fixture.record.exists())
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        assertThrows(IllegalStateException::class.java) { fixture.store.readNow() }
      }
    }
  }
}
