package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import os.kei.hyper.home.iconpack.perfect.HyperOs4IconModule

class PerfectPublicationDeviceTest {
  private fun fixture(test: suspend (IconPackRepository, File) -> Unit) = runBlocking {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    val root = java.nio.file.Files.createTempDirectory(base.cacheDir.toPath(), "perfect-publication-").toFile()
    val context = object : ContextWrapper(base) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
      override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
      override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
    try { test(IconPackRepository(context), context.filesDir) }
    finally { root.deleteRecursively() }
  }

  private fun writePack(file: File, transform: ByteArray? = null, shade: Int = 0xff245688.toInt()) {
    file.parentFile?.mkdirs()
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val pixels = try {
      bitmap.eraseColor(shade)
      ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
      }
    } finally { bitmap.recycle() }
    ZipOutputStream(file.outputStream()).use { zip ->
      if (transform != null) {
        zip.putNextEntry(ZipEntry("transform_config.xml"))
        zip.write(transform)
        zip.closeEntry()
      }
      for (layer in 0..1) {
        zip.putNextEntry(ZipEntry("res/drawable-xxhdpi/com.sample.perfect/$layer.png"))
        zip.write(pixels)
        zip.closeEntry()
      }
    }
  }

  @Test fun successfulReplacementCleansBothPrivateCopiesAndLeavesLatestUsable() = fixture { repository, files ->
    val old = File(files, "perfect-icons/generated/perfect-${"a".repeat(12)}-${"b".repeat(24)}.zip")
    val latest = File(files, "perfect-icons/generated/perfect-${"c".repeat(12)}-${"d".repeat(24)}.zip")
    writePack(old, HyperOs4IconModule.transform(false, null))
    val first = repository.importPerfectPublication(old, keepLatest = false) as IconPackImportResult.Success
    writePack(latest, HyperOs4IconModule.transform(false, null), 0xff668824.toInt())
    val next = repository.importPerfectPublication(latest, keepLatest = true) as IconPackImportResult.Success
    val oldRecord = (first.pack.source as IconPackSource.Imported).record
    val latestRecord = (next.pack.source as IconPackSource.Imported).record
    assertTrue(old.isFile && oldRecord.originalFile.isFile)
    repository.prunePerfectPublications(next.pack, latest)
    assertTrue(latest.isFile && latestRecord.originalFile.isFile)
    assertTrue(!old.exists() && !oldRecord.originalFile.exists())
    assertEquals(listOf(next.pack.id), repository.listImported().packs.map { it.id })
  }

  @Test fun independentModuleWithTheActualStockMaskIsAdmitted() = fixture { repository, files ->
    val mask = requireNotNull(NativeAdaptiveIconMask.load()) { "The acceptance target must expose its original stock shape" }
    val file = File(files, "perfect-icons/generated/perfect-stock-shape.zip")
    writePack(file, HyperOs4IconModule.transform(false, mask.pathData))
    val result = repository.importPerfectPublication(file)
    assertTrue(result.toString(), result is IconPackImportResult.Success)
    assertEquals(1, (result as IconPackImportResult.Success).pack.entries.size)
  }

  @Test fun independentModuleWithoutACustomMaskIsAdmitted() = fixture { repository, files ->
    val file = File(files, "perfect-icons/generated/perfect-native-default.zip")
    writePack(file, HyperOs4IconModule.transform(false, null))
    assertTrue(repository.importPerfectPublication(file) is IconPackImportResult.Success)
  }

  @Test fun ownedPublicationImportsWithoutRelaxingTheDocumentUriBoundary() = fixture { repository, files ->
    val file = File(files, "perfect-icons/generated/perfect-test.zip")
    writePack(file)
    assertTrue(repository.import(Uri.fromFile(file)) is IconPackImportResult.Failure)
    val result = repository.importPerfectPublication(file, "Perfect Icons test")
    assertTrue(result is IconPackImportResult.Success)
    val pack = (result as IconPackImportResult.Success).pack
    assertEquals(1, pack.entries.size)
    assertEquals(IconPackFormat.MIUI_ICONS, pack.format)
    assertEquals("com.sample.perfect", pack.entries.single().nativeName)
  }

  @Test fun publicationOutsideTheOwnedDirectoryIsRejected() = fixture { repository, files ->
    val file = File(files, "outside/perfect-test.zip")
    writePack(file)
    assertTrue(repository.importPerfectPublication(file) is IconPackImportResult.Failure)
  }

  @Test fun publicationSymlinkCannotImportAnArbitraryFile() = fixture { repository, files ->
    val outside = File(files, "outside/perfect-test.zip")
    writePack(outside)
    val link = File(files, "perfect-icons/generated/perfect-test.zip")
    link.parentFile?.mkdirs()
    java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
    assertTrue(repository.importPerfectPublication(link) is IconPackImportResult.Failure)
  }

  @Test fun disguisedWebpAndJpegImportAndPreviewWithoutRewritingBytes() = fixture { repository, files ->
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff245688.toInt()) }
    fun image(format: Bitmap.CompressFormat) = ByteArrayOutputStream().use {
      check(bitmap.compress(format, 100, it)); it.toByteArray()
    }
    val images = try { mapOf(
      "res/drawable-xxhdpi/com.sample.webp/0.png" to image(Bitmap.CompressFormat.PNG),
      "res/drawable-xxhdpi/com.sample.webp/1.png" to image(Bitmap.CompressFormat.WEBP_LOSSLESS),
      "res/drawable-xxhdpi/com.sample.jpeg.png" to image(Bitmap.CompressFormat.JPEG),
    ) } finally { bitmap.recycle() }
    val file = File(files, "perfect-icons/generated/perfect-raster-types.zip").apply { parentFile?.mkdirs() }
    ZipOutputStream(file.outputStream()).use { zip -> images.forEach { (name, bytes) ->
      zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    } }
    val result = repository.importPerfectPublication(file)
    assertTrue(result.toString(), result is IconPackImportResult.Success)
    val pack = (result as IconPackImportResult.Success).pack
    assertEquals(2, pack.entries.size)
    val stored = (pack.source as IconPackSource.Imported).record.originalFile
    java.util.zip.ZipFile(stored).use { zip ->
      images.forEach { (name, bytes) -> assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }) }
    }
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    IconPackRenderer(base).open(pack, false).use { session ->
      for (entry in pack.entries) {
        val preview = session.renderTarget(entry, IconPackComponent(requireNotNull(entry.nativeName)), 32) {}
        assertNotNull(entry.key, preview)
        preview?.recycle()
      }
    }
    assertTrue((repository.importPerfectPublication(file) as IconPackImportResult.Success).alreadyPresent)
  }

  @Test fun opaquePngTrailerDoesNotChangeAndroidDecodedPixels() {
    val expected = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    try {
      expected.eraseColor(0xff245688.toInt())
      val clean = ByteArrayOutputStream().use {
        check(expected.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
      }
      val tailed = clean + ByteArray(4008) { (it * 31).toByte() }
      val actual = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(tailed, 0, tailed.size))
      try { assertTrue(expected.sameAs(actual)) } finally { actual.recycle() }
    } finally { expected.recycle() }
  }

  @Test fun malformedPublicationRetainsItsUnderlyingImportFailure() = fixture { repository, files ->
    val file = File(files, "perfect-icons/generated/perfect-invalid.zip").apply { parentFile?.mkdirs() }
    ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("res/drawable-xxhdpi/com.sample.invalid.png"))
      zip.write("not an image".toByteArray()); zip.closeEntry()
    }
    val result = repository.importPerfectPublication(file)
    assertTrue(result is IconPackImportResult.Failure)
    val failure = result as IconPackImportResult.Failure
    assertNotNull(failure.cause)
    assertTrue(failure.message.contains("com.sample.invalid.png"))
  }

  @Test fun observedPhysicalPublicationCanRetryTheLocalImport() = fixture { repository, files ->
    val path = InstrumentationRegistry.getArguments().getString("perfectPublicationFixture")
    org.junit.Assume.assumeNotNull(path)
    val input = File(requireNotNull(path))
    assertTrue(input.isFile)
    val publication = File(files, "perfect-icons/generated/perfect-observed.zip").apply { parentFile?.mkdirs() }
    input.copyTo(publication)
    fun digest(file: File): ByteArray {
      val hash = java.security.MessageDigest.getInstance("SHA-256")
      file.inputStream().use { stream ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
          val count = stream.read(buffer)
          if (count < 0) break
          hash.update(buffer, 0, count)
        }
      }
      return hash.digest()
    }
    val originalHash = digest(input)
    repeat(2) { attempt ->
      val result = repository.importPerfectPublication(publication)
      assertTrue(result.toString(), result is IconPackImportResult.Success)
      result as IconPackImportResult.Success
      assertEquals(attempt != 0, result.alreadyPresent)
      assertTrue(result.pack.entries.size >= 117)
      assertEquals(IconPackFormat.MIUI_ICONS, result.pack.format)
      assertArrayEquals(originalHash, digest((result.pack.source as IconPackSource.Imported).record.originalFile))
      if (attempt == 0) {
        val exported = IconPackExporter(InstrumentationRegistry.getInstrumentation().targetContext,
          IconPackLimits()).export(result.pack, emptyList(), false, 64,
          preservedPackages = emptySet(), recommendedMappings = os.kei.hyper.home.data.RecommendedIconMappings.NONE) {}
        try {
          os.kei.hyper.home.xposed.LocalNativeIconValidator.validate(exported.archive)
        } finally { exported.archive.delete() }
      }
    }
  }
}
