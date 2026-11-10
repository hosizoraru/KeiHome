package os.kei.hyper.home.iconpack.perfect

import org.junit.Assert.*
import org.junit.Test

class PerfectSyncHistoryTest {
  private val values = mutableMapOf<String, String>()
  private var time = 100L
  private fun history(process: String = "live") =
    PerfectSyncHistory(values::get, { key, value -> values[key] = value }, { time }, process)

  @Test fun failedAttemptSurvivesReloadAndRetryUntilSuccess() {
    val history = history()
    history.seedLastSuccess(50)
    history.started()
    time = 200
    history.failed()
    assertEquals(PerfectSyncFailure.FAILED, history().current().issue)
    assertEquals(200L, history().current().issueAtMillis)
    time = 300
    history.started()
    assertEquals(PerfectSyncFailure.FAILED, history().current().issue)
    assertEquals(50L, history().current().lastSuccessfulSyncAtMillis)
    time = 400
    history.succeeded()
    assertEquals(PerfectSyncHistoryState(lastSuccessfulSyncAtMillis = 400), history().current())
  }

  @Test fun onlyANewProcessRecoversStartedAttemptAsInterrupted() {
    history().seedLastSuccess(50)
    history().started()
    time = 200
    repeat(3) {
      val live = history().current()
      assertEquals(100L, live.startedAtMillis)
      assertNull(live.issue)
    }
    val recovered = history("next-process").current()
    assertEquals(PerfectSyncFailure.INTERRUPTED, recovered.issue)
    assertEquals(100L, recovered.issueAtMillis)
    assertEquals(50L, recovered.lastSuccessfulSyncAtMillis)
    assertEquals(0L, recovered.startedAtMillis)
    assertEquals(recovered, history("third-process").current())
  }

  @Test fun explicitCancellationKeepsLastGoodTimestamp() {
    history().seedLastSuccess(50)
    history().started()
    time = 200
    history().interrupted()
    assertEquals(PerfectSyncFailure.INTERRUPTED, history().current().issue)
    assertEquals(200L, history().current().issueAtMillis)
    assertEquals(50L, history().current().lastSuccessfulSyncAtMillis)
  }

  @Test fun legacyTimestampSeedsOnceWithoutClearingFailureOrLiveAttempt() {
    history().started()
    time = 200
    history().failed()
    history().started()
    val seeded = history().seedLastSuccess(50)
    assertEquals(PerfectSyncFailure.FAILED, seeded.issue)
    assertEquals(200L, seeded.issueAtMillis)
    assertEquals(200L, seeded.startedAtMillis)
    assertEquals(50L, history().seedLastSuccess(999).lastSuccessfulSyncAtMillis)
  }

  @Test fun malformedHistoryRecoversWithoutInventingFailure() {
    values["syncHistoryV1"] = "{broken"
    assertEquals(PerfectSyncHistoryState(), history().current())
    assertEquals("{broken", values["syncHistoryV1"])
    assertEquals(50L, history().seedLastSuccess(50).lastSuccessfulSyncAtMillis)
    history().started()
    history().succeeded()
    assertNull(history().current().issue)
  }

  @Test fun unknownIssueRetainsLastSuccessAndLiveAttemptWithoutThrowing() {
    values["syncHistoryV1"] =
      """{"startedAt":10,"owner":"live","issue":"FUTURE_VALUE","issueAt":20,"lastSuccessAt":50}"""
    val saved = history().current()
    assertNull(saved.issue)
    assertEquals(0L, saved.issueAtMillis)
    assertEquals(50L, saved.lastSuccessfulSyncAtMillis)
    assertEquals(10L, saved.startedAtMillis)
    assertEquals(PerfectSyncFailure.INTERRUPTED, history("new-process").current().issue)
    assertEquals(50L, history("new-process").current().lastSuccessfulSyncAtMillis)
  }
}
