package os.kei.hyper.home.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheUsageTest {
  @get:Rule val temporary = TemporaryFolder()

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
}
