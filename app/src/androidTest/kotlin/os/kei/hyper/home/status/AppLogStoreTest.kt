package os.kei.hyper.home.status

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import os.kei.hyper.home.BuildConfig
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLogStoreTest {
  @Test fun emptyExportsHaveEnvironmentAndProviderUsesEachActualFilename() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(target.cacheDir, "share/app-log-share-test").apply { deleteRecursively(); mkdirs() }
    val context = object : ContextWrapper(target) {
      override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
      override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
    try {
      val first = AppLogStore.export(context, 2, 1)
      val firstBytes = first.readBytes()
      val second = AppLogStore.export(context, 2, 1)
      assertNotEquals(first, second)
      assertTrue(firstBytes.contentEquals(first.readBytes()))
      listOf(first, second).forEach { file ->
        assertTrue(file.name.startsWith("KeiHome-AppLogs-"))
        val uri = FileProvider.getUriForFile(target, "${target.packageName}.fileprovider", file)
        target.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
          null, null, null)!!.use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals(file.name, cursor.getString(0))
          assertEquals(file.length(), cursor.getLong(1))
        }
        assertTrue(target.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(file.readBytes()))
        ZipFile(file).use { zip ->
          assertEquals(setOf("README-KeiHome.txt", "environment.txt"),
            zip.entries().asSequence().map { it.name }.toSet())
          val environment = zip.getInputStream(zip.getEntry("environment.txt")).bufferedReader().use { it.readText() }
          assertTrue(environment.contains("phone.codename=${Build.DEVICE}\n"))
          assertTrue(environment.contains("system.build.incremental=${Build.VERSION.INCREMENTAL}\n"))
          assertTrue(environment.contains("launcher.package=com.miui.home\n"))
          assertTrue(environment.contains("module.versionName=${BuildConfig.VERSION_NAME}\n"))
          assertTrue(environment.contains("module.versionCode=${BuildConfig.VERSION_CODE}\n"))
          assertTrue(environment.contains("collected.timezone="))
        }
      }
    } finally {
      AppLogStore.closeArchive(context)
      root.deleteRecursively()
    }
  }

  @Test fun rangeStatsExportAndSelectiveClear() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(target.cacheDir, "app-log-range-test").apply { deleteRecursively(); mkdirs() }
    val context = object : ContextWrapper(target) {
      override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
      override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
    val dir = File(context.filesDir, "app-logs").apply { deleteRecursively(); mkdirs() }
    val now = System.currentTimeMillis()
    val recent = now - 60_000
    val old = now - 6 * 3_600_000
    File(dir, "${recent / 3_600_000}.log").writeText("$recent\tINFO recent\n")
    File(dir, "${old / 3_600_000}.log").writeText("$old\tINFO old\n")
    val since = now - 3 * 3_600_000
    assertEquals(1, AppLogStore.stats(context, since, now).second)
    ZipFile(AppLogStore.export(context, since, now)).use { zip ->
      assertTrue(zip.entries().asSequence().any { it.name.endsWith(".log") &&
        zip.getInputStream(it).bufferedReader().readText().contains("INFO recent") })
    }
    assertEquals(1, AppLogStore.clear(context, since, now))
    assertEquals(0, AppLogStore.stats(context, since, now).second)
    assertEquals(1, AppLogStore.stats(context, old - 1, now).second)
    AppLogStore.closeArchive(context)
    root.deleteRecursively()
  }
}
