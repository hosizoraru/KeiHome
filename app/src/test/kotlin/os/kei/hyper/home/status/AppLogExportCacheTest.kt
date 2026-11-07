package os.kei.hyper.home.status

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLogExportCacheTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun localTimeCrossesDateBoundaryAndLocaleCannotChangeAsciiDigitsOrCalendar() {
    val locale = Locale.getDefault()
    val timeZone = TimeZone.getDefault()
    try {
      val instant = Instant.parse("2026-11-06T16:15:30.123Z").toEpochMilli()
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Singapore"))
      listOf(Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("th-TH"), Locale.ROOT).forEach {
        Locale.setDefault(it)
        assertEquals("KeiHome-AppLogs-20261107-001530-123-v1.4.0-b0227.zip",
          appLogExportName(instant, "1.4.0+g59ea509.b0227.dirty", VERSION_CODE))
      }
      TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
      assertEquals("KeiHome-AppLogs-20261106-081530-123-v1.4.0-b0227.zip",
        appLogExportName(instant, VERSION, VERSION_CODE))
    } finally {
      Locale.setDefault(locale)
      TimeZone.setDefault(timeZone)
    }
  }

  @Test fun versionMetadataIsOmittedAndInvalidSeriesCannotInjectPathsOrIdentifiers() {
    val now = Instant.parse("2026-11-07T02:15:30.123Z").toEpochMilli()
    val utc = TimeZone.getTimeZone("UTC")
    assertEquals("KeiHome-AppLogs-20261107-021530-123-v1.4.0-b0227.zip",
      appLogExportName(now, "1.4.0+gprivate/account:serial.b9999.override.dirty", VERSION_CODE, utc))
    listOf("../../account", "1.4.0/serial", "1.4.0\nserial", "\u0661.4.0", "", "1.4.0-serial",
      "1".repeat(300)).forEach { invalid ->
      val name = appLogExportName(now, invalid, VERSION_CODE, utc)
      assertEquals("KeiHome-AppLogs-20261107-021530-123-vunknown-b0227.zip", name)
      assertEquals(name, File(name).name)
      assertTrue(name.all { it.code in 32..126 })
    }
    assertThrows(IllegalArgumentException::class.java) {
      appLogExportName(now, VERSION, -1, utc)
    }
  }

  @Test fun sameMillisecondConcurrentExportsAreDistinctCompleteAndNeverOverwriteEarlierShares() {
    val now = 20 * DAY
    val directory = temporary.newFolder("share")
    val cache = AppLogExportCache(7 * DAY, clock = { now })
    val executor = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    try {
      val futures = (0 until 24).map { index ->
        executor.submit<File> {
          check(start.await(5, TimeUnit.SECONDS))
          HourlyAppLogArchive(temporary.newFolder("logs-$index"), clock = { now }).use { archive ->
            archive.append(now, "export $index")
            cache.export(directory, VERSION, VERSION_CODE) { archive.export(now, now, it) }
          }
        }
      }
      start.countDown()
      val files = futures.map { it.get(10, TimeUnit.SECONDS) }
      assertEquals(24, files.toSet().size)
      files.forEachIndexed { index, file ->
        assertTrue(file.name.startsWith(appLogExportName(now, VERSION, VERSION_CODE).removeSuffix(".zip")))
        assertEquals("$now\texport $index\n", logContents(file, now))
      }
      assertEquals(files.map { it.name }.toSet(), directory.list()!!.toSet())
    } finally {
      start.countDown()
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test fun existingNamesSurviveCollisionsIncludingAnotherCacheOwner() {
    val now = 20 * DAY
    val directory = temporary.newFolder("collisions")
    val preferred = File(directory, appLogExportName(now, VERSION, VERSION_CODE)).apply { writeText("earlier share") }
    val first = AppLogExportCache(7 * DAY, clock = { now })
      .export(directory, VERSION, VERSION_CODE) { it.writeText("first") }
    val second = AppLogExportCache(7 * DAY, clock = { now })
      .export(directory, VERSION, VERSION_CODE) { it.writeText("second") }
    assertEquals(3, setOf(preferred, first, second).size)
    assertEquals("earlier share", preferred.readText())
    assertEquals("first", first.readText())
    assertEquals("second", second.readText())
  }

  @Test fun cleanupExpiresOnlyOwnedOldFilesAndAbandonedTempsWhileKeepingRecentShares() {
    val now = 20 * DAY
    val directory = temporary.newFolder("retention")
    val name = appLogExportName(now, VERSION, VERSION_CODE)
    val expired = listOf("KeiHome-logs.zip", name, name.removeSuffix(".zip") + "-123.zip",
      ".KeiHome-logs.zip-123.tmp").map {
      File(directory, it).apply { writeText("expired"); assertTrue(setLastModified(now - 7 * DAY - 1)) }
    } + File.createTempFile(".$name-", ".tmp", directory).apply {
      writeText("abandoned archive"); assertTrue(setLastModified(now - 7 * DAY - 1))
    }
    val boundary = File(directory, name.removeSuffix(".zip") + "-456.zip").apply {
      writeText("share at boundary"); assertTrue(setLastModified(now - 7 * DAY))
    }
    val recent = File(directory, name.removeSuffix(".zip") + "-789.zip").apply {
      writeText("recent share"); assertTrue(setLastModified(now - 1))
    }
    val unrelated = listOf("other.zip", "KeiHome-AppLogs-not-owned.zip", ".$name-bad.tmp").map {
      File(directory, it).apply { writeText("keep"); assertTrue(setLastModified(now - 8 * DAY)) }
    }
    val subdirectory = File(directory, name.removeSuffix(".zip") + "-321.zip").apply { mkdir() }
    val external = temporary.newFile("external").apply { writeText("external"); setLastModified(now - 8 * DAY) }
    val link = File(directory, name.removeSuffix(".zip") + "-654.zip")
    Files.createSymbolicLink(link.toPath(), external.toPath())
    val result = AppLogExportCache(7 * DAY, clock = { now })
      .export(directory, VERSION, VERSION_CODE) { it.writeText("new export") }

    expired.filter { it.canonicalFile != result }.forEach { assertFalse(it.exists()) }
    assertEquals("new export", result.readText())
    assertEquals("share at boundary", boundary.readText())
    assertEquals("recent share", recent.readText())
    unrelated.forEach { assertEquals("keep", it.readText()) }
    assertTrue(subdirectory.isDirectory)
    assertTrue(Files.isSymbolicLink(link.toPath()))
    assertEquals("external", external.readText())
  }

  @Test fun repeatedExportsPrunePriorAgesWithoutEvictingTheRecentWindowByCount() {
    var now = 20 * DAY
    val directory = temporary.newFolder("rolling")
    val cache = AppLogExportCache(7 * DAY, clock = { now })
    repeat(30) { day ->
      cache.export(directory, VERSION, VERSION_CODE) { it.writeText("day $day") }
      assertTrue(directory.listFiles()!!.size <= 8)
      now += DAY
    }
    val recent = (0 until 40).map { index ->
      cache.export(directory, VERSION, VERSION_CODE) { it.writeText("recent $index") }
    }
    assertEquals(40, recent.toSet().size)
    recent.forEachIndexed { index, file -> assertEquals("recent $index", file.readText()) }
  }

  @Test fun cleanupPreservesAgedActiveWriterAndItsTempAndCompletionRestartsRetention() {
    val now = AtomicLong(20 * DAY)
    val directory = temporary.newFolder("active")
    val cache = AppLogExportCache(7 * DAY, clock = now::get)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    try {
      val exporting = executor.submit<File> {
        cache.export(directory, VERSION, VERSION_CODE) { target ->
          val temp = File.createTempFile(".${target.name}-", ".tmp", directory).apply {
            writeText("writing"); assertTrue(setLastModified(now.get()))
          }
          assertTrue(target.setLastModified(now.get()))
          entered.countDown()
          check(release.await(10, TimeUnit.SECONDS))
          assertTrue(target.exists())
          assertEquals("writing", temp.readText())
          temp.delete()
          target.writeText("finished")
        }
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      now.addAndGet(8 * DAY)
      cache.export(directory, VERSION, VERSION_CODE) { it.writeText("parallel") }
      release.countDown()
      val completed = exporting.get(5, TimeUnit.SECONDS)
      assertEquals(now.get(), completed.lastModified())
      now.incrementAndGet()
      cache.export(directory, VERSION, VERSION_CODE) { it.writeText("later") }
      assertEquals("finished", completed.readText())
    } finally {
      release.countDown()
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test fun failedArchiveExportRemovesItsReservationAndDoesNotAffectPreviousShares() {
    val now = 20 * DAY
    val directory = temporary.newFolder("failure")
    val cache = AppLogExportCache(7 * DAY, clock = { now })
    val archive = HourlyAppLogArchive(temporary.newFolder("closed"), clock = { now })
    val previous = cache.export(directory, VERSION, VERSION_CODE) { archive.export(now, now, it) }
    val previousBytes = previous.readBytes()
    archive.close()
    assertThrows(IllegalStateException::class.java) {
      cache.export(directory, VERSION, VERSION_CODE) { archive.export(now, now, it) }
    }
    assertThrows(IOException::class.java) {
      cache.export(directory, VERSION, VERSION_CODE) {
        it.writeText("partial")
        throw IOException("output failed")
      }
    }
    assertEquals(setOf(previous.name), directory.list()!!.toSet())
    assertTrue(previousBytes.contentEquals(previous.readBytes()))
    assertEquals("", logContents(previous, now))
  }

  private fun logContents(file: File, time: Long): String = ZipFile(file).use { zip ->
    assertTrue(zip.getEntry("README-KeiHome.txt") != null)
    zip.getEntry("${time / 3_600_000}.log")?.let { entry ->
      zip.getInputStream(entry).bufferedReader().use { it.readText() }
    } ?: ""
  }

  private companion object {
    const val DAY = 24 * 3_600_000L
    const val VERSION = "1.4.0+g59ea509.b0227.dirty"
    const val VERSION_CODE = 104_000_227
  }
}
