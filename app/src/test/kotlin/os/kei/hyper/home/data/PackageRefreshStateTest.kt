package os.kei.hyper.home.data

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PackageRefreshStateTest {
  @Test fun unchangedScanRetainsIdentityButChangedMetadataAndMembershipPublish() {
    data class App(val name: String, val version: Long, val capability: Boolean)
    val previous = listOf(App("a", 1, true), App("b", 1, false))
    assertSame(previous, previous.map { it.copy() }.preserveUnchangedReference(previous))
    val replacement = listOf(App("a", 2, true), previous[1])
    assertSame(replacement, replacement.preserveUnchangedReference(previous))
    val capability = listOf(previous[0], App("b", 1, true))
    assertSame(capability, capability.preserveUnchangedReference(previous))
    val removed = previous.take(1)
    assertSame(removed, removed.preserveUnchangedReference(previous))
    assertSame(previous, previous.preserveUnchangedReference(null))
  }

  @Test fun replacementCallbacksCoalesceButProfilesRemainDistinct() {
    val pending = PendingPackageRefresh()
    repeat(6) { pending.add(PackageRefreshTarget("example.app", 0)) }
    pending.add(PackageRefreshTarget("example.app", 10))
    assertEquals(7L, pending.revision)
    val batch = pending.drain()
    assertFalse(batch.global)
    assertEquals(setOf(PackageRefreshTarget("example.app", 0), PackageRefreshTarget("example.app", 10)), batch.targets)
    assertFalse(pending.isPending)
  }

  @Test fun eventsDuringProcessingBelongToTheNextBatch() {
    val pending = PendingPackageRefresh()
    val first = PackageRefreshTarget("example.first", 0)
    val second = PackageRefreshTarget("example.second", 0)
    pending.add(first)
    val processing = pending.drain()
    pending.add(second)
    pending.add(first)
    assertEquals(setOf(first), processing.targets)
    assertEquals(setOf(first, second), pending.drain().targets)
  }

  @Test fun globalEventsAndOverflowCollapseBoundedly() {
    val pending = PendingPackageRefresh(2)
    pending.add(PackageRefreshTarget("example.one"))
    pending.add(PackageRefreshTarget("example.two"))
    pending.add(PackageRefreshTarget("example.three"))
    repeat(4_000) { pending.add(PackageRefreshTarget("example.app$it")) }
    assertEquals(PackageRefreshBatch(emptySet(), true), pending.drain())
    pending.add(PackageRefreshTarget("example.one"))
    pending.add()
    assertEquals(PackageRefreshBatch(emptySet(), true), pending.drain())
  }

  @Test fun unknownProfileInvalidatesAllProfilesOfOnlyThatPackage() {
    val unknown = PackageRefreshTarget("example.app")
    val work = PackageRefreshTarget("example.app", 10)
    assertTrue(unknown.matches("example.app", 0))
    assertTrue(unknown.matches("example.app", 10))
    assertFalse(unknown.matches("example.other", 10))
    assertFalse(work.matches("example.app", 0))
    assertTrue(work.matches("example.app", 10))
  }

  @Test fun repeated314AppScansReuseUnchangedAssessments() {
    val cache = PackageAssessmentCache<PackageRefreshTarget, Any>()
    val apps = (0 until 314).map { PackageRefreshTarget("example.app$it", 0) }
    var evaluations = 0
    fun scan() = apps.map { cache.resolve(it, cache.revision) { evaluations++; Any() } }
    val first = scan()
    val second = scan()
    assertEquals(314, evaluations)
    first.zip(second).forEach { (before, after) -> assertSame(before, after) }
    cache.invalidate { it == apps[17] }
    val updated = scan()
    assertEquals(315, evaluations)
    assertNotSame(first[17], updated[17])
    assertSame(first[18], updated[18])
  }

  @Test fun profileInvalidationAndManualRefreshRetainTheirDifferentScopes() {
    val cache = PackageAssessmentCache<PackageRefreshTarget, Any>()
    val personal = PackageRefreshTarget("example.app", 0)
    val work = PackageRefreshTarget("example.app", 10)
    fun load(key: PackageRefreshTarget) = cache.resolve(key, cache.revision) { Any() }
    val personalBefore = load(personal)
    val workBefore = load(work)
    cache.invalidate { personal.matches(it.packageName, it.userSerial!!) }
    assertNotSame(personalBefore, load(personal))
    assertSame(workBefore, load(work))
    cache.invalidate()
    assertNotSame(workBefore, load(work))
  }

  @Test fun accessOrderEvictionIsBounded() {
    val cache = PackageAssessmentCache<Int, Any>(2)
    fun load(key: Int) = cache.resolve(key, cache.revision) { Any() }
    val first = load(1)
    val second = load(2)
    assertSame(first, load(1))
    load(3)
    assertSame(first, load(1))
    assertNotSame(second, load(2))
  }

  @Test fun cancelledOrUncertainAssessmentsAreNotCached() {
    val cache = PackageAssessmentCache<String, String>()
    try {
      cache.resolve("app", cache.revision) { throw CancellationException() }
      fail("Cancellation must escape")
    } catch (_: CancellationException) {}
    assertEquals("fresh", cache.resolve("app", cache.revision) { "fresh" })
    repeat(2) {
      var called = false
      cache.resolve("uncertain", cache.revision, cacheable = { false }) { called = true; "unknown" }
      assertTrue(called)
    }
  }

  @Test fun invalidationRejectsAnInFlightOldAssessment() {
    val cache = PackageAssessmentCache<String, Any>()
    val started = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val worker = Executors.newSingleThreadExecutor()
    try {
      val revision = cache.revision
      val result = worker.submit<Any> {
        cache.resolve("app", revision) {
          started.countDown()
          check(finish.await(3, TimeUnit.SECONDS))
          Any()
        }
      }
      assertTrue(started.await(3, TimeUnit.SECONDS))
      cache.invalidate { it == "app" }
      finish.countDown()
      val old = result.get(3, TimeUnit.SECONDS)
      val fresh = cache.resolve("app", cache.revision) { Any() }
      assertNotSame(old, fresh)
      assertSame(fresh, cache.resolve("app", cache.revision) { Any() })
    } finally {
      finish.countDown()
      worker.shutdownNow()
    }
  }

  @Test fun anOlderScanCannotCacheItsLaterAssessmentsAfterInvalidation() {
    val cache = PackageAssessmentCache<String, Any>()
    val oldRevision = cache.revision
    cache.invalidate()
    val old = cache.resolve("app", oldRevision) { Any() }
    val fresh = cache.resolve("app", cache.revision) { Any() }
    assertNotSame(old, fresh)
    cache.resolve("app", oldRevision) { Any() }
    assertSame(fresh, cache.resolve("app", cache.revision) { Any() })
  }
}
