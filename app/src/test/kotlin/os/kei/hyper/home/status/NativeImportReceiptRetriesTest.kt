package os.kei.hyper.home.status

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeImportReceiptRetriesTest {
  private fun operation(token: String = "a", startedAt: Long? = 0) =
    NativeImportReceiptOperation(token, 0, "source", startedAt)

  @Test fun queriesBackOffForSixMinutesAndStopWithoutInventingFailure() = runTest {
    val queries = mutableListOf<Long>()
    val retries = NativeImportReceiptRetries { currentTime }
    retries.observe(operation()) { queries += currentTime }
    assertEquals(listOf(15_000L, 45_000L, 90_000L, 150_000L, 240_000L, 360_000L), queries)
    // Re-entering the foreground for this same operation cannot renew the exhausted budget.
    retries.observe(operation()) { fail("Exhausted operation was queried again") }
    advanceTimeBy(600_000)
    runCurrent()
    assertEquals(6, queries.size)
  }

  @Test fun backgroundCancellationAndLateResumeDoNotResetBudgetOrBurstMissedQueries() = runTest {
    val queries = mutableListOf<Long>()
    val retries = NativeImportReceiptRetries { currentTime }
    val foreground = launch { retries.observe(operation()) { queries += currentTime } }
    advanceTimeBy(10_000)
    foreground.cancelAndJoin()
    advanceTimeBy(200_000)
    runCurrent()
    assertTrue(queries.isEmpty())
    retries.observe(operation()) { queries += currentTime }
    assertEquals(listOf(210_000L, 240_000L, 285_000L, 345_000L), queries)
    assertTrue(currentTime <= 360_000)
  }

  @Test fun foregroundReentryRetainsConsumedAttemptAndMinimumSpacing() = runTest {
    val queries = mutableListOf<Long>()
    val retries = NativeImportReceiptRetries { currentTime }
    val foreground = launch { retries.observe(operation()) { queries += currentTime } }
    advanceTimeBy(16_000)
    foreground.cancelAndJoin()
    advanceTimeBy(1_000)
    val resumed = launch { retries.observe(operation()) { queries += currentTime } }
    advanceUntilIdle()
    resumed.join()
    assertEquals(listOf(15_000L, 45_000L, 90_000L, 150_000L, 240_000L, 360_000L), queries)
  }

  @Test fun delayedTerminalReceiptCancelsAllRemainingQueryTimers() = runTest {
    val queries = mutableListOf<Long>()
    val retries = NativeImportReceiptRetries { currentTime }
    val pending = MutableStateFlow<NativeImportReceiptOperation?>(operation())
    val observer = launch {
      pending.collectLatest { operation -> if (operation != null) retries.observe(operation) { queries += currentTime } }
    }
    advanceTimeBy(151_000)
    assertEquals(listOf(15_000L, 45_000L, 90_000L, 150_000L), queries)
    pending.value = null // Both success and confirmed failure clear pending in the receipt reducer.
    runCurrent()
    advanceTimeBy(600_000)
    runCurrent()
    assertEquals(4, queries.size)
    observer.cancelAndJoin()
  }

  @Test fun operationReplacementCancelsTheOldGenerationBeforeItCanQuery() = runTest {
    val queries = mutableListOf<Pair<String, Long>>()
    val retries = NativeImportReceiptRetries { currentTime }
    val pending = MutableStateFlow(operation())
    val observer = launch {
      pending.collectLatest { operation ->
        retries.observe(operation) { queries += operation.token to currentTime }
      }
    }
    advanceTimeBy(14_000)
    pending.value = operation("b", startedAt = currentTime)
    runCurrent()
    advanceUntilIdle()
    assertEquals(listOf(29_000L, 59_000L, 104_000L, 164_000L, 254_000L, 374_000L),
      queries.map { it.second })
    assertTrue(queries.all { it.first == "b" })
    observer.cancelAndJoin()
  }

  @Test fun cancellationDuringPublicationConsumesTheAttempt() = runTest {
    val queries = mutableListOf<Long>()
    val retries = NativeImportReceiptRetries { currentTime }
    val foreground = launch {
      retries.observe(operation()) { queries += currentTime; delay(100_000) }
    }
    advanceTimeBy(16_000)
    foreground.cancelAndJoin()
    val resumed = launch { retries.observe(operation()) { queries += currentTime } }
    advanceUntilIdle()
    resumed.join()
    assertEquals(listOf(15_000L, 45_000L, 90_000L, 150_000L, 240_000L, 360_000L), queries)
  }

  @Test fun slowQueriesNeverOverlapAndCannotExtendTheDeadline() = runTest {
    val queries = mutableListOf<Long>()
    var active = false
    val retries = NativeImportReceiptRetries { currentTime }
    retries.observe(operation()) {
      assertFalse(active)
      active = true
      queries += currentTime
      delay(130_000)
      active = false
    }
    assertEquals(listOf(15_000L, 145_000L, 275_000L), queries)
  }

  @Test fun expiredLegacyAndClockResetRequestsDoNotScheduleAutomaticWork() = runTest {
    val retries = NativeImportReceiptRetries { currentTime }
    retries.observe(operation(startedAt = null)) { fail("Legacy operation must use manual recovery") }
    retries.observe(operation(startedAt = 1)) { fail("Future timestamp must not start timers") }
    advanceTimeBy(390_001)
    retries.observe(operation("expired")) { fail("Expired operation must use manual recovery") }
    assertNull(nextNativeReceiptQueryDelay(0, 1, 50_000, 49_999))
    assertNull(nextNativeReceiptQueryDelay(0, 6, 360_000, 360_000))
    assertEquals(0L, nextNativeReceiptQueryDelay(0, 5, 240_000, 360_000))
  }

  @Test fun finalQuerySurvivesDispatcherJitterWithoutRenewingItsBudget() {
    assertEquals(0L, nextNativeReceiptQueryDelay(0, 5, 240_000, 360_017))
    assertEquals(0L, nextNativeReceiptQueryDelay(0, 5, 240_000, 389_999))
    assertNull(nextNativeReceiptQueryDelay(0, 5, 240_000, 390_001))
  }

  @Test fun queryNoncesAreStrictlyIncreasingEvenOnOneClockTickOrClockReset() {
    val first = nextNativeReceiptQueryNonce(100, 100, 0)!!
    val second = nextNativeReceiptQueryNonce(100, first, 0)!!
    assertEquals(101L, first)
    assertEquals(102L, second)
    assertEquals(103L, nextNativeReceiptQueryNonce(1, second, 0))
    assertEquals(501L, nextNativeReceiptQueryNonce(100, 0, 500))
    assertNull(nextNativeReceiptQueryNonce(100, Long.MAX_VALUE, 0))
  }
}
