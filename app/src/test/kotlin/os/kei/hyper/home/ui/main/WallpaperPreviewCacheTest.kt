package os.kei.hyper.home.ui.main

import android.app.WallpaperManager.FLAG_LOCK
import android.app.WallpaperManager.FLAG_SYSTEM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.status.WallpaperPreviewProtocol.Preview
import os.kei.hyper.home.status.WallpaperPreviewProtocol.ReadResult

class WallpaperPreviewCacheTest {
  @Test fun validatesBothCachedPreviewsInSelectedOrder() = runTest {
    val home = preview("home")
    val lock = preview("lock")
    val calls = mutableListOf<Pair<Int, Preview?>>()
    val cache = WallpaperPreviewCache { screen, cached, force ->
      assertFalse(force)
      calls += screen to cached
      loaded(if (screen == FLAG_LOCK) lock else home)
    }
    cache.refresh(FLAG_SYSTEM)
    cache.refresh(FLAG_LOCK)
    assertEquals(listOf(FLAG_SYSTEM to null, FLAG_LOCK to null, FLAG_LOCK to lock, FLAG_SYSTEM to home), calls)
  }

  @Test fun forcedRefreshPreservesDisplayedPreviewWhileLoadingAndOnFailure() = runTest {
    val oldHome = preview("home before")
    val oldLock = preview("lock before")
    val latestLock = preview("lock after")
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val cache = WallpaperPreviewCache { screen, cached, force ->
      if (force) {
        assertSame(if (screen == FLAG_LOCK) oldLock else oldHome, cached)
        started.complete(Unit)
        release.await()
        ReadResult(if (screen == FLAG_LOCK) latestLock else null, separateDimming = true)
      } else loaded(if (screen == FLAG_LOCK) oldLock else oldHome)
    }

    cache.refresh(FLAG_SYSTEM)
    val job = launch { cache.refresh(FLAG_LOCK, force = true) }
    started.await()
    assertTrue(cache.state.value.refreshing)
    assertSame(oldLock, cache.state.value.previews[FLAG_LOCK])
    release.complete(Unit)
    job.join()
    assertFalse(cache.state.value.refreshing)
    assertSame(latestLock, cache.state.value.previews[FLAG_LOCK])
    assertSame(oldHome, cache.state.value.previews[FLAG_SYSTEM])
    assertEquals(setOf(FLAG_SYSTEM), cache.state.value.failed)
  }

  @Test fun sharedEngineChecksOnlyTheDesktop() = runTest {
    val shared = preview("shared")
    val cache = WallpaperPreviewCache { screen, _, _ ->
      assertEquals(FLAG_SYSTEM, screen)
      ReadResult(shared, separateDimming = false)
    }
    cache.refresh(FLAG_SYSTEM)
    cache.refresh(FLAG_LOCK, force = true)
    assertEquals(false, cache.state.value.separate)
    assertNull(cache.state.value.previews[FLAG_LOCK])
  }

  @Test fun previewFailureStillReportsWhetherScreenStrengthsAreIndependent() = runTest {
    val cache = WallpaperPreviewCache { _, _, _ -> ReadResult(null, separateDimming = true) }
    cache.refresh(FLAG_SYSTEM)
    assertEquals(true, cache.state.value.separate)
    assertEquals(setOf(FLAG_SYSTEM, FLAG_LOCK), cache.state.value.failed)
  }

  @Test fun closingDuringARefreshLeavesTheCacheAvailableForReopen() = runTest {
    val first = preview("before")
    val second = preview("after")
    val started = CompletableDeferred<Unit>()
    var revision = first
    val cache = WallpaperPreviewCache { _, _, force ->
      if (force) {
        started.complete(Unit)
        awaitCancellation()
      }
      loaded(revision)
    }
    cache.refresh(FLAG_SYSTEM)
    val refresh = launch { cache.refresh(FLAG_SYSTEM, force = true) }
    started.await()
    refresh.cancelAndJoin()
    assertFalse(cache.state.value.refreshing)
    assertSame(first, cache.state.value.previews[FLAG_SYSTEM])
    revision = second
    cache.refresh(FLAG_SYSTEM)
    assertSame(second, cache.state.value.previews[FLAG_SYSTEM])
  }

  private fun loaded(preview: Preview) = ReadResult(preview, separateDimming = true)

  private fun preview(revision: String) = Preview(image = null, revision = revision)
}
