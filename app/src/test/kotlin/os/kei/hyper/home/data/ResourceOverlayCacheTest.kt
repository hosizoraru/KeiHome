package os.kei.hyper.home.data

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ResourceOverlayCacheTest {
  @Test fun cachesPositiveAndNegativeVerdictsForRepeatedLargeInventories() {
    for (count in listOf(100, 500, 1000)) {
      val cache = ResourceOverlayCache(count)
      val identities = (0 until count).map { identity("app.$it") }
      var reads = 0
      repeat(3) {
        identities.forEachIndexed { index, id ->
          assertEquals(index % 2 == 0, cache.resolve(id) { reads++; index % 2 == 0 })
        }
      }
      assertEquals(count, reads)
    }
  }

  @Test fun changedApkSplitOrProfileCannotReuseOldVerdict() {
    val cache = ResourceOverlayCache()
    val original = identity("app.one")
    val changed = listOf(
      original.copy(uid = original.uid + 100_000),
      original.copy(files = original.files.map { it.copy(size = it.size + 1) }),
      original.copy(files = original.files.map { it.copy(modifiedNanos = it.modifiedNanos + 1) }),
      original.copy(files = original.files.map { it.copy(fileKey = "new-inode") }),
      original.copy(files = original.files + OverlayFileIdentity("/split.apk", 1, 2, "split")),
    )
    assertTrue(cache.resolve(original) { true })
    changed.forEach { assertFalse(cache.resolve(it) { false }) }
    assertTrue(cache.resolve(original) { fail("Unchanged APK should remain cached"); false })
  }

  @Test fun readFailureAndUnknownIdentityAreNotCached() {
    val cache = ResourceOverlayCache()
    val id = identity("app.one")
    assertFalse(cache.resolve(id) { null })
    assertTrue(cache.resolve(id) { true })
    var reads = 0
    repeat(2) { assertFalse(cache.resolve(null) { reads++; false }) }
    assertEquals(2, reads)
  }

  @Test fun cancellationPropagatesAndDoesNotPublishAVerdict() {
    val cache = ResourceOverlayCache()
    val id = identity("app.one")
    try {
      cache.resolve(id) { throw CancellationException("cancel") }
      fail("Cancellation must escape")
    } catch (_: CancellationException) { }
    assertTrue(cache.resolve(id) { true })
  }

  @Test fun evictsLeastRecentlyUsedVerdictAtItsBound() {
    val cache = ResourceOverlayCache(2)
    val first = identity("app.one")
    val second = identity("app.two")
    val third = identity("app.three")
    cache.resolve(first) { true }
    cache.resolve(second) { true }
    cache.resolve(first) { fail("Cache hit should not read"); false }
    cache.resolve(third) { true }
    assertFalse(cache.resolve(second) { false })
    assertTrue(cache.resolve(third) { fail("Newest verdict should remain cached"); false })
  }

  @Test fun fileIdentityTracksReplacementModificationAndMissingSplits() {
    val directory = Files.createTempDirectory("overlay-identity")
    try {
      val base = directory.resolve("base.apk")
      Files.write(base, byteArrayOf(1))
      val original = overlayApkIdentity("app.one", 10001, listOf(base.toString()))
      assertNotNull(original)
      assertEquals(original, overlayApkIdentity("app.one", 10001, listOf(base.toString())))
      Files.setLastModifiedTime(base, FileTime.fromMillis(12345))
      assertNotEquals(original, overlayApkIdentity("app.one", 10001, listOf(base.toString())))
      assertNull(overlayApkIdentity("app.one", 10001, listOf(base.toString(), directory.resolve("missing.apk").toString())))
      assertNull(overlayApkIdentity("app.one", 10001, emptyList()))
    } finally { directory.toFile().deleteRecursively() }
  }

  private fun identity(name: String) = OverlayApkIdentity(name, 10001,
    listOf(OverlayFileIdentity("/$name/base.apk", 10, 20, "inode")))
}
