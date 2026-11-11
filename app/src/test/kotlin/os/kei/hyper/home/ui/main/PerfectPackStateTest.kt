package os.kei.hyper.home.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.perfect.PerfectIconCatalog
import os.kei.hyper.home.iconpack.perfect.PerfectIconOptions
import os.kei.hyper.home.iconpack.perfect.PerfectIconRemote

class PerfectPackStateTest {
  private val catalog = PerfectIconCatalog(
    revision = "a".repeat(40),
    styles = listOf("default", "Rounded"),
    updatedAt = 1_000,
    iconCount = 10,
    downloadedStyles = listOf("default"),
  )

  @Test fun selectingAnAvailableButUndownloadedStyleDisablesLocalWorkUntilSyncCompletes() {
    val local = PerfectPackState(catalog = catalog)
    assertTrue(local.ready)
    val selected = local.copy(options = PerfectIconOptions(style = "Rounded"))
    assertFalse(selected.ready)
    val synced = selected.copy(catalog = catalog.copy(downloadedStyles = listOf("default", "Rounded")))
    assertTrue(synced.ready)
    assertTrue(synced.copy(options = PerfectIconOptions()).ready)
  }

  @Test fun checkingANewerRemoteKeepsTheDownloadedStyleUsableOfflineEvenIfCheckFailsLater() {
    val local = PerfectPackState(catalog = catalog)
    val checked = local.copy(remote = PerfectIconRemote(revision = "b".repeat(40)))
    assertTrue(checked.updateAvailable)
    assertTrue(checked.ready)
    val failed = checked.copy(error = PerfectPackError.CHECK)
    assertTrue(failed.ready)
    assertTrue(failed.updateAvailable)
    val synced = failed.copy(catalog = catalog.copy(revision = checked.remote!!.revision), error = null)
    assertTrue(synced.ready)
    assertFalse(synced.updateAvailable)
  }

  @Test fun remoteMetadataAloneCannotEnablePreviewOrGeneration() {
    val checked = PerfectPackState(remote = PerfectIconRemote(revision = "b".repeat(40),
      availableStyles = listOf("default", "Rounded")))
    assertFalse(checked.ready)
    assertFalse(checked.updateAvailable)
  }

  @Test fun deletingResourcesRemovesReadinessWhilePreservingTheSelectedStyle() {
    val selected = PerfectPackState(catalog = catalog, options = PerfectIconOptions(style = "default"))
    assertTrue(selected.ready)
    assertTrue(selected.copy(deletingResources = true).busy)
    val deleted = selected.copy(catalog = null, remote = null)
    assertFalse(deleted.ready)
    assertFalse(deleted.copy(options = PerfectIconOptions()).ready)
  }
}
