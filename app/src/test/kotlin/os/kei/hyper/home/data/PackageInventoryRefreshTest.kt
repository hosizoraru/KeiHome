package os.kei.hyper.home.data

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PackageInventoryRefreshTest {
  private data class Row(
    val name: String, val serial: Long = 0, val version: Int = 1, val overlay: Boolean = false,
  ) {
    val key get() = PackageRefreshTarget(name, serial)
  }

  private val scope = PackageInventoryScope("config", setOf(0L, 10L), 1)
  private val ordering = compareBy<Row> { it.name }.thenBy { it.serial }

  private inner class Reader(val previous: List<Row>) {
    val targets = mutableListOf<PackageRefreshTarget>()
    val updated = mutableMapOf<PackageRefreshTarget, Row?>()
    var fullCalls = 0
    var fullResult = previous
    var failTarget: PackageRefreshTarget? = null
    var fullFailure: Exception? = null

    fun refresh(
      request: PackageRefreshBatch,
      current: PackageInventoryScope<String> = scope,
      before: PackageInventoryScope<String>? = scope,
    ): List<Row> = refreshPackageInventory(previous, before, current, request,
      Row::key, Row::overlay, ordering,
      readTarget = {
        targets += it
        if (it == failTarget) throw IllegalStateException("package query failed")
        updated[it]
      },
      readFull = {
        fullCalls++
        fullFailure?.let { throw it }
        fullResult
      })
  }

  private fun request(vararg rows: Row) = PackageRefreshBatch(rows.map { it.key }.toSet(), false)

  @Test fun oneUpdateAmong314QueriesOnlyOnePackageAndRetainsOtherRows() {
    val previous = (0 until 314).map { Row("example.app%03d".format(it)) }
    val reader = Reader(previous)
    val changed = previous[128].copy(version = 2)
    reader.updated[changed.key] = changed
    val result = reader.refresh(request(changed))
    assertEquals(listOf(changed.key), reader.targets)
    assertEquals(0, reader.fullCalls)
    assertEquals(previous.size, result.size)
    assertSame(changed, result[128])
    previous.indices.filter { it != 128 }.forEach { assertSame(previous[it], result[it]) }
  }

  @Test fun installAndUninstallMergeOnlyTheAffectedProfile() {
    val personal = Row("example.app")
    val work = Row("example.app", 10)
    val unrelated = Row("example.other")
    val added = Row("example.new")
    val reader = Reader(listOf(personal, work, unrelated))
    reader.updated[personal.key] = null
    reader.updated[added.key] = added
    val result = reader.refresh(request(personal, added))
    assertEquals(listOf(work, added, unrelated), result)
    assertSame(work, result[0])
    assertSame(unrelated, result[2])
    assertEquals(0, reader.fullCalls)
  }

  @Test fun equalTargetMetadataRetainsBothRowAndListIdentity() {
    val row = Row("example.app")
    val previous = listOf(row)
    val reader = Reader(previous)
    reader.updated[row.key] = row.copy()
    assertSame(previous, reader.refresh(request(row)))
    assertEquals(listOf(row.key), reader.targets)
    assertEquals(0, reader.fullCalls)
  }

  @Test fun initialManualConfigResourceAndProfileChangesForceFullReads() {
    val row = Row("example.app")
    val cases = listOf(
      Triple<PackageInventoryScope<String>?, PackageInventoryScope<String>, PackageRefreshBatch>(
        null, scope, request(row)),
      Triple(scope, scope, PackageRefreshBatch(emptySet(), true)),
      Triple(scope, scope.copy(configuration = "new-config"), request(row)),
      Triple(scope, scope.copy(resourceRevision = 2), request(row)),
      Triple(scope, scope.copy(profiles = setOf(0)), request(row)),
      Triple(scope, scope.copy(profiles = setOf(0, 10, 11)), request(row)),
    )
    cases.forEach { (before, current, request) ->
      val reader = Reader(listOf(row))
      assertSame(reader.fullResult, reader.refresh(request, current, before))
      assertEquals(1, reader.fullCalls)
      assertTrue(reader.targets.isEmpty())
    }
  }

  @Test fun unknownOrMissingProfilesNeverUseTargetedQueries() {
    val row = Row("example.app")
    val requests = listOf(
      PackageRefreshBatch(setOf(PackageRefreshTarget(row.name)), false),
      PackageRefreshBatch(setOf(PackageRefreshTarget(row.name, 99)), false),
    )
    requests.forEach { request ->
      val reader = Reader(listOf(row))
      reader.refresh(request)
      assertEquals(1, reader.fullCalls)
      assertTrue(reader.targets.isEmpty())
    }
    val reader = Reader(listOf(row))
    val unknownScope = scope.copy(profiles = setOf(0, -1))
    reader.refresh(request(row), unknownScope, unknownScope)
    assertEquals(1, reader.fullCalls)
  }

  @Test fun existingAndNewOverlaysRequireAuthoritativeInventory() {
    val oldOverlay = Row("example.overlay", overlay = true)
    val existing = Reader(listOf(oldOverlay))
    existing.refresh(request(oldOverlay))
    assertEquals(1, existing.fullCalls)
    assertTrue(existing.targets.isEmpty())
    val added = Reader(emptyList())
    added.updated[oldOverlay.key] = oldOverlay
    added.refresh(request(oldOverlay))
    assertEquals(1, added.fullCalls)
    assertEquals(listOf(oldOverlay.key), added.targets)
  }

  @Test fun failedTargetQueryFallsBackOnceWithoutPublishingPartialRemovals() {
    val one = Row("example.one")
    val two = Row("example.two")
    val reader = Reader(listOf(one, two))
    reader.updated[one.key] = null
    reader.failTarget = two.key
    assertSame(reader.previous, reader.refresh(request(one, two)))
    assertEquals(1, reader.fullCalls)
    reader.fullFailure = IllegalStateException("full read failed")
    try {
      reader.refresh(request(one, two))
      fail("A failed recovery must not return a partial inventory")
    } catch (_: IllegalStateException) {}
    assertEquals(listOf(one, two), reader.previous)
    assertEquals(2, reader.fullCalls)
  }

  @Test fun cancellationDoesNotFallBackOrMutatePreviousInventory() {
    val row = Row("example.app")
    val previous = listOf(row)
    var fullCalls = 0
    try {
      refreshPackageInventory(previous, scope, scope, request(row), Row::key, Row::overlay,
        ordering, { throw CancellationException() }, { fullCalls++; emptyList() })
      fail("Cancellation must escape")
    } catch (_: CancellationException) {}
    assertSame(row, previous.single())
    assertEquals(0, fullCalls)
  }

  @Test fun cancelledBatchRestoresAlongsideNewerEventsAndGlobalRefreshWins() {
    val one = Row("example.one")
    val two = Row("example.two", 10)
    val pending = PendingPackageRefresh()
    pending.restore(request(one))
    val running = pending.drain()
    pending.restore(request(two))
    pending.restore(running)
    assertEquals(setOf(one.key, two.key), pending.drain().targets)
    pending.add()
    pending.restore(running)
    assertEquals(PackageRefreshBatch(emptySet(), true), pending.drain())
  }
}
