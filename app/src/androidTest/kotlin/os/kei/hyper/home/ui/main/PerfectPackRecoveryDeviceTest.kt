package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.perfect.PerfectIconRepository
import os.kei.hyper.home.iconpack.perfect.PerfectIconSettings
import os.kei.hyper.home.iconpack.perfect.PerfectIconTransport
import os.kei.hyper.home.ui.perfect.PerfectPackController
import os.kei.hyper.home.ui.perfect.PerfectPackError

class PerfectPackRecoveryDeviceTest {
  @Test fun repairedPublicationRecoversOnSameControllerReentryOnlyOnce() = fixture { fixture ->
    val invalid = "not a ZIP".toByteArray(Charsets.US_ASCII)
    fixture.publication.writeBytes(invalid)
    fixture.savePublication()

    fixture.reenter()
    assertEquals(PerfectPackError.IMPORT, fixture.controller.state.value.error)
    assertFalse(fixture.controller.state.value.busy)
    assertNull(fixture.controller.state.value.pack)
    assertEquals(fixture.publication, fixture.controller.state.value.result?.file)
    assertArrayEquals(invalid, fixture.publication.readBytes())
    assertEquals("", fixture.savedRecordId())
    assertTrue(fixture.packs.listImported().packs.isEmpty())
    assertEquals(0, fixture.generated.get())
    assertEquals(0, fixture.network.get())

    // Repair the retained publication in place; no new controller or generation is involved.
    val repaired = fixture.writeValidPublication()
    fixture.reenter()
    fixture.assertImported(repaired)
    val recordId = fixture.savedRecordId()
    assertTrue(recordId.isNotEmpty())

    repeat(3) { fixture.reenter() }
    fixture.assertImported(repaired)
    assertEquals(recordId, fixture.savedRecordId())
  }

  @Test fun missingPublicationCanArriveBeforeNextLocalReentry() = fixture { fixture ->
    fixture.savePublication()
    fixture.reenter()
    assertEquals(PerfectPackError.LOCAL, fixture.controller.state.value.error)
    assertNull(fixture.controller.state.value.pack)
    assertFalse(fixture.controller.state.value.busy)
    assertEquals(0, fixture.generated.get())
    assertEquals(0, fixture.network.get())

    val repaired = fixture.writeValidPublication()
    fixture.reenter()
    fixture.assertImported(repaired)
    fixture.reenter()
    fixture.assertImported(repaired)
  }

  private fun fixture(test: suspend (Fixture) -> Unit) = runBlocking {
    val fixture = Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
    try {
      test(fixture)
    } finally {
      fixture.close()
    }
  }

  private class Fixture(base: Context) {
    private val storageId = "perfect_recovery_${UUID.randomUUID()}"
    private val root = Files.createTempDirectory(base.cacheDir.toPath(), "perfect-recovery-").toFile()
    private val context = object : ContextWrapper(base) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
      override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
      override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
    // Initialize MMKV with the real application context, but open only this test's unique ID.
    private val storage = AppLocalStorage.open(base, storageId)
    private val job = SupervisorJob()
    val network = AtomicInteger()
    val generated = AtomicInteger()
    val packs = IconPackRepository(context)
    val publication = File(context.filesDir, "perfect-icons/generated/perfect-recovery.zip").apply {
      parentFile!!.mkdirs()
    }
    private val repository = PerfectIconRepository(
      File(context.filesDir, "perfect-icons"),
      PerfectIconTransport { _, _, _, _, _ ->
        network.incrementAndGet()
        error("Local publication recovery must not request the network")
      },
      object : PerfectIconSettings {
        override fun read(key: String): String? = null
        override fun write(key: String, value: String) {
          error("Local publication recovery must not change repository settings")
        }
      },
    )
    val controller = PerfectPackController(
      context, CoroutineScope(job + Dispatchers.Main.immediate), repository, packs, storageId,
    ) { generated.incrementAndGet() }

    fun savePublication() {
      val saved = JSONObject()
        .put("recordId", "")
        .put("file", publication.absolutePath)
        .put("mapped", 1)
        .put("staticCount", 1)
        .put("dynamicCount", 0)
        .put("downloaded", 0)
        .put("reused", 0)
        .put("revision", "recovery-fixture")
        .put("contract", PerfectIconRepository.LAYER_CONTRACT)
        .put("style", "HyperOS4")
        .put("installedOnly", false)
        .put("dynamic", false)
        .put("staticNight", false)
      check(storage.encode("last_generated", saved.toString()))
      storage.sync()
    }

    fun savedRecordId(): String = JSONObject(
      requireNotNull(storage.getString("last_generated", null)),
    ).getString("recordId")

    suspend fun reenter() {
      val resumed = withContext(Dispatchers.Main.immediate) {
        val first = controller.onResourcesResumed(ensureCatalog = false)
        // Two entry notifications in one main-thread turn must share the active attempt.
        assertSame(first, controller.onResourcesResumed(ensureCatalog = false))
        first
      }
      withTimeout(30_000) { resumed.join() }
    }

    fun writeValidPublication(): ByteArray {
      val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
      val pixels = try {
        bitmap.eraseColor(0xff245688.toInt())
        ByteArrayOutputStream().use { output ->
          check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
          output.toByteArray()
        }
      } finally {
        bitmap.recycle()
      }
      ZipOutputStream(publication.outputStream()).use { zip ->
        for (layer in 0..1) {
          zip.putNextEntry(ZipEntry("res/drawable-xxhdpi/com.sample.recovery/$layer.png"))
          zip.write(pixels)
          zip.closeEntry()
        }
      }
      return publication.readBytes()
    }

    suspend fun assertImported(expected: ByteArray) {
      val state = controller.state.value
      assertFalse(state.busy)
      assertNull(state.error)
      assertNotNull(state.pack)
      assertEquals(publication, state.result?.file)
      val imported = packs.listImported().packs.single()
      assertEquals(imported.id, state.pack?.pack?.id)
      assertEquals(imported.id, savedRecordId())
      val record = (imported.source as IconPackSource.Imported).record
      assertArrayEquals(expected, publication.readBytes())
      assertArrayEquals(expected, record.originalFile.readBytes())
      assertEquals(1, generated.get())
      assertEquals(0, network.get())
    }

    suspend fun close() {
      try {
        job.cancelAndJoin()
      } finally {
        try {
          storage.close()
          MMKV.removeStorage(storageId)
        } finally {
          root.deleteRecursively()
        }
      }
    }
  }
}
