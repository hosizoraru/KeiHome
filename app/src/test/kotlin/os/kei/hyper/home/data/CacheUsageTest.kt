package os.kei.hyper.home.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheUsageTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun persistentImportsAreCountedSeparatelyAndDeletionChangesTheTotal() {
    val cache = temporary.newFolder("cache")
    val imports = temporary.newFolder("icon-packs")
    val copy = File(imports, "id").apply { mkdirs() }
    copy.resolve("original.zip").writeBytes(ByteArray(31))
    copy.resolve("icons.zip").writeBytes(ByteArray(17))
    cache.resolve("scratch").writeBytes(ByteArray(7))
    val outside = temporary.newFile().apply { writeBytes(ByteArray(200)) }
    Files.createSymbolicLink(copy.resolve("link").toPath(), outside.toPath())
    val before = measureDiskCache(cache, importedRoot = imports)
    assertEquals(48L, before.bytes[CacheBucket.IMPORTED_PACKS])
    assertEquals(55L, before.total)
    assertTrue(before.complete)
    copy.deleteRecursively()
    val after = measureDiskCache(cache, importedRoot = imports)
    assertEquals(0L, after.bytes[CacheBucket.IMPORTED_PACKS])
    assertEquals(7L, after.total)
    assertEquals(200L, outside.length())
  }

  @Test fun importedRootInsideCacheIsNotCountedTwice() {
    val cache = temporary.newFolder("cache")
    val imports = File(cache, "icon-packs").apply { mkdirs() }
    imports.resolve("original.zip").writeBytes(ByteArray(37))
    assertEquals(37L, measureDiskCache(cache, importedRoot = imports).total)
    assertEquals(37L, measureDiskCache(cache, importedRoot = imports).bytes[CacheBucket.IMPORTED_PACKS])
  }

  @Test fun measuresEachCacheCategoryWithoutFollowingLinks() {
    val root = temporary.newFolder("cache")
    val outside = temporary.newFile("outside").apply { writeBytes(ByteArray(99)) }
    root.resolve("current-icons").apply { mkdir(); resolve("snapshot.zip").writeBytes(ByteArray(12)) }
    root.resolve("builtin-icon-packs").apply { mkdir(); resolve("pack.zip").writeBytes(ByteArray(23)) }
    root.resolve("native-icon-imports").apply { mkdir(); resolve("import.mtz").writeBytes(ByteArray(34)) }
    root.resolve("icon-pack-exports").apply { mkdir(); resolve("export.zip").writeBytes(ByteArray(45)) }
    root.resolve("other.tmp").writeBytes(ByteArray(56))
    Files.createSymbolicLink(root.resolve("linked").toPath(), outside.toPath())

    val usage = measureDiskCache(root)
    assertTrue(usage.complete)
    assertEquals(12L, usage.bytes.getValue(CacheBucket.CURRENT_ICONS))
    assertEquals(23L, usage.bytes.getValue(CacheBucket.BUILTIN_PACKS))
    assertEquals(79L, usage.bytes.getValue(CacheBucket.HANDOFF))
    assertEquals(56L, usage.bytes.getValue(CacheBucket.OTHER))
    assertEquals(170L, usage.total)
  }

  @Test fun logsAndOwnedExportsHaveOneCategoryAndAreCountedOnlyOnce() {
    val cache = temporary.newFolder("cache")
    val logs = temporary.newFolder("logs")
    File(logs, "app-20261112.log").writeBytes(ByteArray(11))
    val share = File(cache, "share").apply { mkdirs() }
    File(share, "KeiHome-logs.zip").writeBytes(ByteArray(13))
    File(share, "KeiHome-AppLogs-20261112-120000-123-v1.8.0-b0383.zip").writeBytes(ByteArray(17))
    File(share, ".KeiHome-logs.zip-123.tmp").writeBytes(ByteArray(19))
    File(share, "KeiHome-AppLogs-not-a-log.png").writeBytes(ByteArray(23))
    File(cache, "current-icons").apply { mkdirs(); resolve("icon.png").writeBytes(ByteArray(29)) }
    val usage = measureDiskCache(cache, logs)
    assertEquals(60L, usage.bytes[CacheBucket.LOGS])
    assertEquals(23L, usage.bytes[CacheBucket.OTHER])
    assertEquals(112L, usage.total)
    assertTrue(usage.complete)
  }

  @Test fun logsAreMeasuredEvenWhenCacheDoesNotExist() {
    val logs = temporary.newFolder("logs")
    File(logs, "app.log").writeBytes(ByteArray(31))
    val usage = measureDiskCache(File(temporary.root, "missing-cache"), logs)
    assertEquals(31L, usage.total)
    assertEquals(31L, usage.bytes[CacheBucket.LOGS])
    assertTrue(usage.complete)
  }

  @Test fun nestedLogRootAndSymbolicLinksCannotInflateUsage() {
    val cache = temporary.newFolder("cache")
    val logs = File(cache, "logs").apply { mkdirs() }
    File(logs, "app.log").writeBytes(ByteArray(37))
    val outside = temporary.newFile("outside").apply { writeBytes(ByteArray(43)) }
    Files.createSymbolicLink(File(logs, "linked.log").toPath(), outside.toPath())
    val usage = measureDiskCache(cache, logs)
    assertEquals(37L, usage.total)
    assertEquals(37L, usage.bytes[CacheBucket.LOGS])
    assertTrue(usage.complete)
  }

  @Test fun cancellationDuringLogTraversalIsPropagated() {
    val logs = temporary.newFolder("logs")
    assertThrows(java.util.concurrent.CancellationException::class.java) {
      measureDiskCache(File(temporary.root, "missing-cache"), logs) {
        throw java.util.concurrent.CancellationException()
      }
    }
  }
}
