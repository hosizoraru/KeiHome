package os.kei.hyper.home.xposed

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertedIconGrantsTest {
  private val proof = ConvertedIconArchive.Proof("CONVERTED_APK",
    ConvertedIconArchive.Digests("a".repeat(40), "b".repeat(64)),
    ConvertedIconArchive.Digests("c".repeat(40), "d".repeat(64)))
  private val grant = ConvertedIconGrant(proof, "icon-local", "parent-local")
  private val icon = ConvertedIconResource(grant.localId, proof.icons.sha1, null, null, null)
  private val parent = ConvertedIconResource(grant.parentLocalId, proof.archive.sha1, null, null, null)
  private val supplied = ConvertedIconSupplied(icon, "/native/icon.json", "/native/icons", "LOCAL",
    ConvertedIconParent(parent.localId, "theme", "/native/parent.json", "/native/parent.mtz", "LOCAL"))
  private val association = ConvertedIconAssociation(supplied, parent, "/native/icons")

  @Test fun coldAndCorruptStoresCannotApproveButAValidatedWarmAssociationLimitsScopeWithoutRights() {
    val state = ConvertedIconApprovals()
    assertFalse(state.iconsOnly(supplied))
    assertThrows(IOException::class.java) { state.load { throw IOException("Corrupt AtomicFile") } }
    assertFalse(state.iconsOnly(supplied))
    val rows = mutableMapOf(grant.localId to grant)
    state.load { rows }
    rows.clear()
    val entry = checkNotNull(state.entry(grant.localId))
    assertFalse(state.iconsOnly(supplied)) // A persisted ID/hash alone is insufficient.
    assertTrue(state.publish(entry, association))
    // A cold rights cache must not make a known matching conversion relate wallpaper.
    assertTrue(state.iconsOnly(supplied))
    state.load { error("Loaded snapshots must not reopen the store") }
  }

  @Test fun stalledCommitCannotBlockUiOrLoseConcurrentAssociationPublication() {
    val state = registered()
    val entry = checkNotNull(state.entry(grant.localId))
    val other = grant.copy(localId = "other-icon")
    val otherAssociation = association.copy(supplied = supplied.copy(icon = icon.copy(localId = other.localId)))
    val writing = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val commit = FutureTask {
      state.register(other, otherAssociation, { error("Already loaded") }) {
        writing.countDown()
        check(finish.await(5, TimeUnit.SECONDS))
      }
    }
    val writer = Thread(commit).apply { start() }
    try {
      assertTrue(writing.await(2, TimeUnit.SECONDS))
      val ui = FutureTask { state.iconsOnly(supplied) to state.iconsOnly(otherAssociation.supplied) }
      val reader = Thread(ui).apply { start() }
      assertEquals(true to false, ui.get(1, TimeUnit.SECONDS))
      reader.join(1_000)
      val refreshed = association.copy(supplied = supplied.copy(metaPath = "/native/new-icon.json"))
      assertTrue(state.publish(entry, refreshed, preflight = true))
      assertTrue(state.iconsOnly(refreshed.supplied))
      finish.countDown()
      assertTrue(commit.get(2, TimeUnit.SECONDS))
      assertTrue(state.iconsOnly(refreshed.supplied))
      assertTrue(state.iconsOnly(otherAssociation.supplied))
    } finally {
      finish.countDown()
      writer.join(2_000)
    }
  }

  @Test fun failedPersistenceCannotReplacePreviouslyPublishedApproval() {
    val state = registered()
    val changed = association.copy(supplied = supplied.copy(metaPath = "/native/new-icon.json"))
    assertThrows(IOException::class.java) {
      state.register(grant, changed, { error("Already loaded") }) { throw IOException("fsync failed") }
    }
    assertTrue(state.iconsOnly(supplied))
    assertFalse(state.iconsOnly(changed.supplied))
  }

  @Test fun uiRequiresExactSourceAndParentFieldsAndAllStoreIdentifiersRemainExcluded() {
    val state = registered()
    val mismatches = listOf(
      supplied.copy(icon = icon.copy(localId = "other")),
      supplied.copy(icon = icon.copy(hash = proof.archive.sha1)),
      supplied.copy(icon = icon.copy(onlineId = "store")),
      supplied.copy(icon = icon.copy(productId = "store")),
      supplied.copy(icon = icon.copy(assemblyId = "store")),
      supplied.copy(metaPath = "/other/icon.json"),
      supplied.copy(contentPath = "/other/icons"),
      supplied.copy(storageType = "SYSTEM"),
      supplied.copy(parent = null),
      supplied.copy(parent = supplied.parent!!.copy(localId = "old-parent")),
      supplied.copy(parent = supplied.parent.copy(metaPath = "/old/parent.json")),
      supplied.copy(parent = supplied.parent.copy(contentPath = "/old/parent.mtz")),
      supplied.copy(parent = supplied.parent.copy(code = "wallpaper")),
      supplied.copy(parent = supplied.parent.copy(storageType = "SYSTEM")),
    )
    for (mismatch in mismatches) assertFalse(mismatch.toString(), state.iconsOnly(mismatch))
    val entry = checkNotNull(state.entry(grant.localId))
    for (invalidParent in listOf(parent.copy(localId = "old-parent"), parent.copy(hash = "old-hash"),
      parent.copy(onlineId = "store"), parent.copy(productId = "store"), parent.copy(assemblyId = "store"))) {
      assertThrows(IllegalArgumentException::class.java) {
        state.publish(entry, association.copy(parent = invalidParent))
      }
    }
  }

  @Test fun concurrentRegistrationsSerializeWithoutDroppingAnotherReceipt() {
    val state = registered()
    val writing = CountDownLatch(1)
    val finish = CountDownLatch(1)
    var durable = emptyMap<String, ConvertedIconGrant>()
    fun task(id: String, stall: Boolean) = FutureTask {
      val next = grant.copy(localId = id)
      val matched = association.copy(supplied = supplied.copy(icon = icon.copy(localId = id)))
      state.register(next, matched, { error("Already loaded") }) {
        if (stall) { writing.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        durable = it
      }
    }
    val first = task("first", true)
    val second = task("second", false)
    val firstThread = Thread(first).apply { start() }
    var secondThread: Thread? = null
    try {
      assertTrue(writing.await(2, TimeUnit.SECONDS))
      secondThread = Thread(second).apply { start() }
      finish.countDown()
      assertTrue(first.get(2, TimeUnit.SECONDS))
      assertTrue(second.get(2, TimeUnit.SECONDS))
      assertEquals(setOf(grant.localId, "first", "second"), durable.keys)
      assertEquals(durable.keys, state.entries().map { it.grant.localId }.toSet())
    } finally {
      finish.countDown()
      firstThread.join(2_000)
      secondThread?.join(2_000)
    }
  }

  @Test fun replacedReceiptAndRetiredGenerationRejectLatePreflightResults() {
    val state = registered()
    val old = checkNotNull(state.entry(grant.localId))
    val newGrant = grant.copy(parentLocalId = "new-parent")
    val newAssociation = association.copy(parent = parent.copy(localId = newGrant.parentLocalId),
      supplied = supplied.copy(parent = supplied.parent!!.copy(localId = newGrant.parentLocalId)))
    assertTrue(state.register(newGrant, newAssociation, { error("Already loaded") }) {})
    assertFalse(state.publish(old, association))
    state.invalidate(old)
    assertTrue(state.iconsOnly(newAssociation.supplied))
    assertFalse(state.iconsOnly(supplied))
    val current = checkNotNull(state.entry(grant.localId))
    state.retire()
    assertFalse(state.iconsOnly(newAssociation.supplied))
    assertFalse(state.publish(current, newAssociation))
    val reloaded = ConvertedIconApprovals()
    reloaded.load { mapOf(newGrant.localId to newGrant) }
    assertFalse(reloaded.publish(current, newAssociation))
    assertFalse(reloaded.iconsOnly(newAssociation.supplied))
    assertNotNull(reloaded.entry(grant.localId))
  }

  @Test fun lateColdReadCannotRepublishAfterRetirement() {
    val state = ConvertedIconApprovals()
    val reading = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val load = FutureTask {
      assertThrows(IllegalStateException::class.java) {
        state.load {
          reading.countDown()
          check(finish.await(5, TimeUnit.SECONDS))
          mapOf(grant.localId to grant)
        }
      }
    }
    val worker = Thread(load).apply { start() }
    try {
      assertTrue(reading.await(2, TimeUnit.SECONDS))
      state.retire()
      finish.countDown()
      load.get(2, TimeUnit.SECONDS)
      assertFalse(state.iconsOnly(supplied))
      assertTrue(state.entries().isEmpty())
    } finally { finish.countDown(); worker.join(2_000) }
  }

  @Test fun startupCannotOverwriteCompletedPreflightOrRestoreARevokedAssociation() {
    val state = ConvertedIconApprovals()
    state.load { mapOf(grant.localId to grant) }
    val startup = checkNotNull(state.entry(grant.localId))
    val refreshed = association.copy(supplied = supplied.copy(metaPath = "/native/new-icon.json"))
    assertTrue(state.publish(startup, refreshed, preflight = true))
    assertFalse(state.publish(startup, association))
    assertTrue(state.iconsOnly(refreshed.supplied))
    state.invalidate(checkNotNull(state.entry(grant.localId)))
    assertFalse(state.publish(startup, association))
    assertFalse(state.publish(startup, refreshed, preflight = true))
    assertFalse(state.iconsOnly(refreshed.supplied))
    assertTrue(state.publish(checkNotNull(state.entry(grant.localId)), refreshed, preflight = true))
    assertTrue(state.iconsOnly(refreshed.supplied))
  }

  private fun registered() = ConvertedIconApprovals().apply {
    assertTrue(register(grant, association, { emptyMap() }) {})
  }
}
