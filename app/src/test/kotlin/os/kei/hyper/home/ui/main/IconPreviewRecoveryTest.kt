package os.kei.hyper.home.ui.main

import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import os.kei.hyper.home.data.AppIconPreviews

class IconPreviewRecoveryTest {
  @Test fun removedSnapshotProducesAnEmptyPreview() = runTest {
    var failure: Exception? = null
    val preview = loadPreviewOrEmpty(onFailure = { failure = it }) { throw FileNotFoundException("removed snapshot") }
    assertEquals(AppIconPreviews(), preview)
    assertTrue(failure is FileNotFoundException)
  }

  @Test fun cancellationStillStopsThePreviewJob() = runTest {
    try {
      loadPreviewOrEmpty { throw CancellationException("screen left") }
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { }
  }
}
