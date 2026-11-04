package os.kei.hyper.home.status

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLogStoreTest {
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
