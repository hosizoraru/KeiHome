package os.kei.hyper.home.status

import com.sun.management.UnixOperatingSystemMXBean
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HourlyAppLogArchiveTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun inclusiveRangesCountUtf8AndExportOnlyNonemptySelectedHours() {
    val dir = temporary.newFolder("app-logs")
    val now = 100 * HOUR + 100
    val before = now - HOUR
    val selected = "$before\tINFO 中文🙂\n$now\tWARN café\n"
    HourlyAppLogArchive(dir, clock = { now }).use { archive ->
      assertTrue(archive.append(before - 1, "outside"))
      assertTrue(archive.append(before, "INFO 中文🙂"))
      assertTrue(archive.append(now, "WARN café"))
      assertTrue(archive.append(now + HOUR, "future"))
      // Damaged/foreign records must never acquire an implicit timestamp of zero.
      File(dir, "98.log").writeText("malformed\n0 no-tab\nnot-a-time\tbad\n")
      File(dir, "foreign.log").writeText("$now\tforeign\n")

      assertEquals(selected.toByteArray(Charsets.UTF_8).size.toLong() to 2, archive.stats(before, now))
      assertEquals(0L to 0, archive.stats(now, before))
      assertEquals(0L to 0, archive.stats(0, 0))
      val target = File(temporary.root, "share/logs.zip")
      assertEquals(target, archive.export(before, now, target))
      val entries = unzip(target.readBytes())
      assertEquals(setOf("README-KeiHome.txt", "environment.txt", "99.log", "100.log"), entries.keys)
      assertTrue(entries.getValue("README-KeiHome.txt").contains("Range:"))
      assertTrue(entries.getValue("README-KeiHome.txt").contains("UTC"))
      assertEquals("$before\tINFO 中文🙂\n", entries["99.log"])
      assertEquals("$now\tWARN café\n", entries["100.log"])

      val empty = File(dir, "empty.zip")
      archive.export(now, before, empty)
      assertEquals(setOf("README-KeiHome.txt", "environment.txt"), unzip(empty.readBytes()).keys)
      assertEquals(0, archive.clear(now, before))
      assertEquals(2, archive.clear(before, now))
      assertEquals(0L to 0, archive.stats(before, now))
      assertEquals(2, archive.stats(0, Long.MAX_VALUE).second)
      assertEquals("malformed\n0 no-tab\nnot-a-time\tbad\n", File(dir, "98.log").readText())
      assertEquals("$now\tforeign\n", File(dir, "foreign.log").readText())
    }
  }

  @Test fun utf8CapRejectsWholeLinesAndRotationReopeningAndClearRespectActualBytes() {
    val dir = temporary.newFolder("cap")
    val time = 50 * HOUR
    val line = "$time\t🙂汉\n"
    val cap = line.toByteArray(Charsets.UTF_8).size.toLong()
    HourlyAppLogArchive(dir, clock = { time }, maxFileBytes = cap).use { archive ->
      assertFalse(archive.append(time, "🙂汉a"))
      assertTrue(archive.append(time, "🙂汉"))
      // Every append is visible before rotation or close, including the final LF.
      assertEquals(line, File(dir, "50.log").readText())
      assertFalse(archive.append(time + 1, ""))
      assertTrue(archive.append(time + HOUR, "🙂汉"))
      assertFalse(archive.append(time, "x"))
      assertEquals(cap * 2 to 2, archive.stats(time, time + HOUR))
    }
    HourlyAppLogArchive(dir, clock = { time }, maxFileBytes = cap).use { archive ->
      assertFalse(archive.append(time, "x"))
      assertEquals(1, archive.clear(time, time))
      assertTrue(archive.append(time, "🙂汉"))
      assertEquals(line, File(dir, "50.log").readText())
      // Replacing the active writer must not send later writes to the unlinked old inode.
      assertEquals(1, archive.clear(time, time))
      assertTrue(archive.append(time, "new"))
      assertEquals("$time\tnew\n", File(dir, "50.log").readText())
    }
  }

  @Test fun hourPrefilterExcludesUnrelatedFilesEvenWithMatchingRecordsAndExtremeBounds() {
    val dir = temporary.newFolder("hour-prefilter")
    val time = 100 * HOUR
    HourlyAppLogArchive(dir, clock = { time }).use { archive ->
      val selectedLine = "$time\tselected\n"
      val malformed = "malformed\nnot-a-time\tkeep\n"
      val selected = File(dir, "100.log").apply { writeText(selectedLine + malformed) }
      // Matching record timestamps cannot make an unrelated filename part of the range.
      val unrelated = File(dir, "99.log").apply { writeText("$time\twrong hour\n") }
      val overflowingHour = File(dir, "${Long.MAX_VALUE}.log").apply {
        writeText("$time\tfilename hour cannot represent epoch milliseconds\n")
      }
      val preEpoch = File(dir, "0.log").apply { writeText("-1\twrong side of epoch\n") }
      val lastHour = File(dir, "${Long.MAX_VALUE / HOUR}.log").apply {
        writeText("${Long.MAX_VALUE}\tlast millisecond\n")
      }

      assertEquals(selectedLine.toByteArray(Charsets.UTF_8).size.toLong() to 1, archive.stats(time, time))
      val output = ByteArrayOutputStream()
      archive.exportTo(time, time, output)
      val entries = unzip(output.toByteArray())
      assertEquals(setOf("README-KeiHome.txt", "environment.txt", "100.log"), entries.keys)
      assertEquals(selectedLine, entries["100.log"])
      assertEquals(1, archive.clear(time, time))
      assertEquals(malformed, selected.readText())
      assertEquals("$time\twrong hour\n", unrelated.readText())

      assertEquals(0L to 0, archive.stats(-1, -1))
      assertEquals(0, archive.clear(-1, -1))
      assertEquals("-1\twrong side of epoch\n", preEpoch.readText())
      // Full Long bounds exclude impossible filename hours without overflow, while the
      // final representable millisecond is still selected in its actual hour.
      assertEquals(3, archive.stats(Long.MIN_VALUE, Long.MAX_VALUE).second)
      assertEquals(1, archive.stats(Long.MAX_VALUE, Long.MAX_VALUE).second)
      assertEquals(1, archive.clear(Long.MAX_VALUE, Long.MAX_VALUE))
      assertEquals("", lastHour.readText())
      assertEquals("$time\tfilename hour cannot represent epoch milliseconds\n", overflowingHour.readText())
    }
  }

  @Test fun defaultCapIsExactlyOneMebibyteAndRejectsEmbeddedLineBreaks() {
    val dir = temporary.newFolder("default-cap")
    val time = 20 * HOUR
    val cap = 1024 * 1024
    val prefixAndLf = "$time\t\n".toByteArray(Charsets.UTF_8).size
    HourlyAppLogArchive(dir, clock = { time }).use { archive ->
      assertThrows(IllegalArgumentException::class.java) { archive.append(time, "one\ntwo") }
      assertThrows(IllegalArgumentException::class.java) { archive.append(time, "one\rtwo") }
      assertTrue(archive.append(time, "x".repeat(cap - prefixAndLf)))
      assertFalse(archive.append(time, ""))
      assertEquals(cap.toLong(), File(dir, "20.log").length())
      assertEquals(cap.toLong() to 1, archive.stats(time, time))
    }
  }

  @Test fun retentionPrunesOnlyOwnedNumericRegularLogsAndRotatesAnExpiredWriter() {
    val dir = temporary.newFolder("retention")
    var now = 10 * DAY
    File(dir, "0.log").writeText("0\told\n")
    val unrelated = listOf("0.txt", "0.log.tmp", "foreign.log", "999999999999999999999999.log")
    unrelated.forEach { File(dir, it).writeText("preserve") }
    File(dir, "1.log").mkdir()
    val external = temporary.newFile("external.log").apply { writeText("preserve link target") }
    Files.createSymbolicLink(File(dir, "2.log").toPath(), external.toPath())
    val boundaryHour = (now - 7 * DAY) / HOUR
    val boundaryFile = File(dir, "$boundaryHour.log").apply { writeText("boundary\n") }
    HourlyAppLogArchive(dir, clock = { now }).use { archive ->
      assertFalse(archive.append(now - 7 * DAY - 1, "expired"))
      assertFalse(File(dir, "0.log").exists())
      assertTrue(boundaryFile.exists())
      assertTrue(archive.append(now, "active"))
      val oldActive = File(dir, "${now / HOUR}.log")
      now += 7 * DAY + HOUR
      assertTrue(archive.append(now, "new hour"))
      assertFalse(oldActive.exists())
      assertFalse(boundaryFile.exists())
      assertEquals(1, archive.stats(0, now).second)
      unrelated.forEach { assertEquals("preserve", File(dir, it).readText()) }
      assertTrue(File(dir, "1.log").isDirectory)
      assertTrue(Files.isSymbolicLink(File(dir, "2.log").toPath()))
      assertEquals("preserve link target", external.readText())
    }
  }

  @Test fun blockedExportKeepsItsSnapshotWhileAppendClearAndCloseComplete() {
    val dir = temporary.newFolder("snapshot")
    val time = 100 * HOUR
    val archive = HourlyAppLogArchive(dir, clock = { time })
    archive.append(time, "first snapshot line")
    archive.append(time + HOUR, "second snapshot line")
    val output = BlockingOutput()
    val executor = Executors.newFixedThreadPool(2)
    val exporting = executor.submit { archive.exportTo(time, Long.MAX_VALUE, output) }
    try {
      assertTrue("export did not reach output", output.entered.await(5, TimeUnit.SECONDS))
      val changing = executor.submit {
        // Appended bytes land on the captured inodes, beyond each snapshot's length limit.
        assertTrue(archive.append(time, "later first hour"))
        assertTrue(archive.append(time + HOUR, "later second hour"))
        assertTrue(archive.append(time + 2 * HOUR, "new file after capture"))
        assertEquals(2, archive.clear(time, time))
        assertEquals(2, archive.clear(time + HOUR, time + HOUR))
        assertTrue(archive.append(time, "replacement writer"))
        assertEquals(2, archive.stats(time, Long.MAX_VALUE).second)
        archive.close()
      }
      // A writer lock held across ZIP output would make this time out.
      changing.get(5, TimeUnit.SECONDS)
      output.release.countDown()
      exporting.get(5, TimeUnit.SECONDS)
      val entries = unzip(output.bytes.toByteArray())
      assertEquals(setOf("README-KeiHome.txt", "environment.txt", "100.log", "101.log"), entries.keys)
      assertEquals("$time\tfirst snapshot line\n", entries["100.log"])
      assertEquals("${time + HOUR}\tsecond snapshot line\n", entries["101.log"])
      assertFalse(output.closed)
    } finally {
      output.release.countDown()
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
      archive.close()
    }
  }

  @Test fun failedExportClosesSnapshotHandlesAndLeavesBorrowedStreamOpen() {
    val bean = ManagementFactory.getOperatingSystemMXBean()
    assumeTrue("FD accounting requires a Unix JVM", bean is UnixOperatingSystemMXBean)
    bean as UnixOperatingSystemMXBean
    val dir = temporary.newFolder("failed-export")
    val time = 100 * HOUR
    val archive = HourlyAppLogArchive(dir, clock = { time })
    archive.append(time, "one")
    archive.append(time + HOUR, "two")
    val failing = object : OutputStream() {
      var closed = false
      override fun write(byte: Int): Unit = throw IOException("blocked destination")
      override fun close() { closed = true }
    }
    try {
      // Warm up ZIP/JVM machinery before accounting for leaked input file descriptors.
      assertThrows(IOException::class.java) { archive.exportTo(time, Long.MAX_VALUE, failing) }
      val before = bean.openFileDescriptorCount
      repeat(24) {
        assertThrows(IOException::class.java) { archive.exportTo(time, Long.MAX_VALUE, failing) }
      }
      assertTrue("snapshot file descriptors leaked", bean.openFileDescriptorCount <= before + 2)
      assertFalse(failing.closed)
      assertTrue(archive.append(time + HOUR + 1, "still writable"))
      assertEquals(3, archive.stats(time, Long.MAX_VALUE).second)
      val withWriter = bean.openFileDescriptorCount
      archive.close()
      assertEquals("close must release the persistent writer", withWriter - 1, bean.openFileDescriptorCount)
    } finally {
      archive.close()
    }
  }

  @Test fun fileExportFailureCleansTemporaryFilesAndPreservesExistingDestination() {
    val dir = temporary.newFolder("export-failure")
    val time = 10 * HOUR
    HourlyAppLogArchive(dir, clock = { time }).use { archive ->
      archive.append(time, "keep")
      // An occupied directory cannot be replaced by the ZIP's atomic move.
      val destination = temporary.newFolder("destination")
      File(destination, "keep").writeText("existing")
      val before = temporary.root.list()!!.toSet()
      assertThrows(IOException::class.java) { archive.export(time, time, destination) }
      assertEquals(before, temporary.root.list()!!.toSet())
      assertEquals("existing", File(destination, "keep").readText())
      assertEquals(1, archive.stats(time, time).second)
      assertThrows(IllegalArgumentException::class.java) {
        archive.export(time, time, File(dir, "10.log"))
      }
      assertEquals("$time\tkeep\n", File(dir, "10.log").readText())
    }
  }

  @Test fun closeIsIdempotentReleasesWriterAndRejectsAllNewOperations() {
    val dir = temporary.newFolder("closed")
    val time = 50 * HOUR
    val archive = HourlyAppLogArchive(dir, clock = { time })
    archive.append(time, "visible")
    val borrowed = object : ByteArrayOutputStream() {
      var closed = false
      override fun close() { closed = true; super.close() }
    }
    archive.exportTo(time, time, borrowed)
    assertFalse(borrowed.closed)
    archive.close()
    archive.close()
    assertThrows(IllegalStateException::class.java) { archive.append(time, "closed") }
    assertThrows(IllegalStateException::class.java) { archive.stats(time, time) }
    assertThrows(IllegalStateException::class.java) { archive.clear(time, time) }
    assertThrows(IllegalStateException::class.java) { archive.exportTo(time, time, borrowed) }
    val target = File(temporary.root, "must-not-exist.zip")
    assertThrows(IllegalStateException::class.java) { archive.export(time, time, target) }
    assertFalse(target.exists())
    HourlyAppLogArchive(dir, clock = { time }).use { reopened ->
      assertTrue(reopened.append(time + 1, "reopened"))
      assertEquals(2, reopened.stats(time, time + 1).second)
    }
  }

  private fun unzip(bytes: ByteArray): Map<String, String> {
    val entries = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
        zip.closeEntry()
      }
    }
    return entries
  }

  private class BlockingOutput : OutputStream() {
    val bytes = ByteArrayOutputStream()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    var closed = false
    private var firstWrite = true

    override fun write(byte: Int) {
      if (firstWrite) {
        firstWrite = false
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "test did not release ZIP output" }
      }
      bytes.write(byte)
    }

    override fun close() { closed = true }
  }

  private companion object {
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR
  }
}
