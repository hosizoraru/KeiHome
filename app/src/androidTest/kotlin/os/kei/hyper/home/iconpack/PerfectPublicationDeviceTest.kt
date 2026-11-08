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

  private fun writePack(file: File, transform: ByteArray? = null) {
    file.parentFile?.mkdirs()
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val pixels = try {
      bitmap.eraseColor(0xff245688.toInt())
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
}
