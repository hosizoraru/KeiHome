package os.kei.hyper.home.status

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StaticScopeRequestsTest {
  private val declared = listOf("system", "com.miui.home", "com.android.thememanager")
  private inner class Client : StaticScopeClient {
    override val identity: Any get() = this
    var granted = emptySet<String>()
    var readError = false
    var submitError = false
    var reads = 0
    var afterRead: (() -> Unit)? = null
    val submissions = mutableListOf<List<String>>()
    val callbacks = mutableListOf<ScopeDecision>()
    override fun readPlan(): StaticScopePlan {
      reads++
      check(!readError) { "read failed" }
      val result = planStaticScope(declared, granted, true) { true }
      afterRead?.invoke()
      return result
    }
    override fun request(packages: List<String>, callback: ScopeDecision) {
      check(!submitError) { "submission failed" }
      submissions += packages
      callbacks += callback
    }
  }
  private fun TestScope.requests(client: Client) =
    StaticScopeRequests(backgroundScope, StandardTestDispatcher(testScheduler), 100, 10).apply {
      connect(client)
      check()
    }

  @Test fun eligibleScopeIsDeclaredIntersectConfirmedInstalledPlusVirtualSystem() {
    val checked = mutableListOf<String>()
    val plan = planStaticScope(declared + "com.missing" + "com.unknown" + declared,
      setOf("system", "com.unrelated"), true) {
      checked += it
      when (it) { "com.missing" -> false; "com.unknown" -> null; else -> true }
    }
    assertFalse("system" in checked)
    assertEquals(declared, plan.eligible)
    assertEquals(declared.drop(1), plan.missing)
    assertEquals(listOf("com.missing"), plan.absent)
    assertEquals(listOf("com.unknown"), plan.unconfirmed)
    assertEquals(setOf("system"), plan.granted)
  }

  @Test fun unsupportedSystemIsNotSubmittedAndPreventsCompleteStatus() {
    val plan = planStaticScope(listOf("system", "com.absent"), emptySet(), false) { false }
    assertTrue(plan.eligible.isEmpty())
    assertTrue(plan.missing.isEmpty())
    assertFalse(plan.complete)
    assertEquals(listOf("system"), plan.unsupported)
  }

  @Test(expected = IllegalArgumentException::class)
  fun emptyDeclarationFailsClosed() { planStaticScope(emptyList(), emptySet(), true) { true } }

  @Test fun existingGrantsAndUnavailableTargetsAreNeverRequested() = runTest {
    val client = Client().apply { granted = setOf("system", "com.miui.home") }
    val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    assertEquals(listOf(listOf("com.android.thememanager")), client.submissions)
  }

  @Test fun repeatedTapsOnlySubmitOneRequest() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    repeat(5) { requests.request() }; runCurrent()
    assertEquals(1, client.submissions.size)
    assertTrue(requests.state.value.requesting)
  }

  @Test fun completedScopeDoesNotOpenEmptyAuthorization() = runTest {
    val client = Client().apply { granted = declared.toSet() }
    val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    assertTrue(client.submissions.isEmpty())
    assertTrue(requests.state.value.plan!!.complete)
  }

  @Test fun grantBetweenPollAndSubmissionAvoidsRequest() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    client.granted = declared.toSet()
    requests.request(); runCurrent()
    assertTrue(client.submissions.isEmpty())
    assertFalse(requests.state.value.requesting)
    assertTrue(requests.state.value.plan!!.complete)
  }

  @Test fun approvedCallbackUsesActualScopeAndSupportsPartialGrant() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    client.granted = setOf("system")
    client.callbacks.single().complete(true, null); runCurrent()
    assertFalse(requests.state.value.requesting)
    assertEquals(declared.drop(1), requests.state.value.plan!!.missing)
    assertTrue(requests.state.value.canRequest)
  }

  @Test fun approvalWithoutActualGrantDoesNotClaimComplete() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    client.callbacks.single().complete(true, null); runCurrent()
    assertFalse(requests.state.value.plan!!.complete)
    assertEquals(declared, requests.state.value.plan!!.missing)
  }

  @Test fun rejectionClearsBusyAndAllowsExplicitRetry() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    client.callbacks.single().complete(false, "denied"); runCurrent()
    assertEquals(ScopeRequestFeedback.FAILED, requests.state.value.feedback)
    assertEquals("denied", requests.state.value.error)
    assertTrue(requests.state.value.canRequest)
    requests.request(); runCurrent()
    assertEquals(2, client.submissions.size)
  }

  @Test fun manualGrantClearsAnEarlierRejectionMessage() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    client.callbacks.single().complete(false, "denied"); runCurrent()
    client.granted = declared.toSet()
    requests.check(); runCurrent()
    assertTrue(requests.state.value.plan!!.complete)
    assertEquals(ScopeRequestFeedback.NONE, requests.state.value.feedback)
    assertNull(requests.state.value.error)
  }

  @Test fun submissionExceptionDoesNotLeaveBusyState() = runTest {
    val client = Client().apply { submitError = true }
    val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    assertFalse(requests.state.value.requesting)
    assertEquals(ScopeRequestFeedback.FAILED, requests.state.value.feedback)
  }

  @Test fun failedReadCannotProduceAnAuthorizationRequest() = runTest {
    val client = Client().apply { readError = true }
    val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    assertTrue(client.submissions.isEmpty())
    assertTrue(requests.state.value.readFailed)
  }

  @Test fun readFailureDuringSubmissionRecoversBusyAndCanBeCheckedAgain() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    client.readError = true
    requests.request(); runCurrent()
    assertFalse(requests.state.value.requesting)
    assertTrue(requests.state.value.readFailed)
    client.readError = false
    requests.check(); runCurrent()
    assertTrue(requests.state.value.canRequest)
  }

  @Test fun disconnectInvalidatesPendingCallback() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    requests.connect(null)
    client.callbacks.single().complete(true, null); runCurrent()
    assertEquals(StaticScopeUiState(), requests.state.value)
  }

  @Test fun previousServiceCallbackCannotChangeReplacementState() = runTest {
    val first = Client(); val requests = requests(first); runCurrent()
    requests.request(); runCurrent()
    val second = Client()
    requests.connect(second); requests.check(); runCurrent()
    first.callbacks.single().complete(false, "obsolete"); runCurrent()
    assertEquals(ScopeRequestFeedback.NONE, requests.state.value.feedback)
    assertNull(requests.state.value.error)
  }

  @Test fun returningWithoutCallbackReleasesBusyState() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    requests.onResumed(); advanceTimeBy(10); runCurrent()
    assertEquals(ScopeRequestFeedback.RETURNED, requests.state.value.feedback)
    assertTrue(requests.state.value.canRequest)
  }

  @Test fun returningReconcilesManualGrantsWithoutCallback() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    client.granted = declared.toSet()
    requests.onResumed(); advanceTimeBy(10); runCurrent()
    assertTrue(requests.state.value.plan!!.complete)
    assertFalse(requests.state.value.requesting)
  }

  @Test fun timeoutRechecksScopeAndDoesNotCancelItsOwnRead() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    val before = client.reads
    client.granted = declared.toSet()
    advanceTimeBy(100); runCurrent()
    assertFalse(requests.state.value.requesting)
    assertTrue(client.reads > before)
    assertTrue(requests.state.value.plan!!.complete)
    assertEquals(ScopeRequestFeedback.NONE, requests.state.value.feedback)
  }

  @Test fun lateCallbackAfterTimeoutCannotOverrideRetry() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    requests.request(); runCurrent()
    advanceTimeBy(100); runCurrent()
    requests.request(); runCurrent()
    client.callbacks.first().complete(false, "old"); runCurrent()
    assertTrue(requests.state.value.requesting)
    assertNull(requests.state.value.error)
  }

  @Test fun stalePollingResultAndFailureCannotOverrideNewerGrantRead() = runTest {
    val client = Client(); val requests = requests(client); runCurrent()
    val old = requests.beginRead(client)!!
    val stale = client.readPlan()
    client.granted = declared.toSet()
    requests.check(); runCurrent()
    requests.observePlan(client, old, stale)
    requests.readFailed(client, old)
    assertTrue(requests.state.value.plan!!.complete)
    assertFalse(requests.state.value.readFailed)
  }

  @Test fun newerPartialGrantDuringSubmissionReadIsNotRequestedAgain() = runTest {
    val client = Client().apply { granted = setOf("system") }
    val requests = requests(client); runCurrent()
    client.afterRead = {
      client.afterRead = null
      client.granted += "com.miui.home"
      val sequence = requests.beginRead(client)!!
      requests.observePlan(client, sequence, client.readPlan())
    }
    requests.request(); runCurrent()
    assertEquals(listOf(listOf("com.android.thememanager")), client.submissions)
    assertEquals(setOf("system", "com.miui.home"), requests.state.value.plan!!.granted)
  }

  @Test fun replacementServiceRejectsOldReadHandlesAndFailures() = runTest {
    val old = Client()
    val requests = requests(old); runCurrent()
    val sequence = requests.beginRead(old)!!
    val replacement = Client()
    requests.connect(replacement); requests.check(); runCurrent()
    assertNull(requests.beginRead(old))
    assertFalse(requests.observePlan(old, sequence, planStaticScope(declared, declared.toSet(), true) { true }))
    assertFalse(requests.readFailed(old, sequence))
    assertFalse(requests.state.value.plan!!.complete)
    assertFalse(requests.state.value.readFailed)
    assertTrue(requests.state.value.canRequest)
  }

  @Test fun newerReadFailureDuringSubmissionCannotUseAnOlderPlan() = runTest {
    val client = Client()
    val requests = requests(client); runCurrent()
    client.afterRead = {
      client.afterRead = null
      requests.readFailed(client, requests.beginRead(client)!!)
    }
    requests.request(); runCurrent()
    assertTrue(client.submissions.isEmpty())
    assertTrue(requests.state.value.readFailed)
    assertFalse(requests.state.value.requesting)
  }

  @Test fun reconciliationUpdatesFrameworkAfterCallbackWithoutRecursing() = runTest {
    val client = Client()
    var reconciliations = 0
    lateinit var requests: StaticScopeRequests
    requests = StaticScopeRequests(backgroundScope, StandardTestDispatcher(testScheduler), 100, 10) {
      reconciliations++
      requests.observePlan(client, requests.beginRead(client)!!, client.readPlan())
    }
    requests.connect(client); requests.check(); runCurrent()
    requests.request(); runCurrent()
    val before = reconciliations
    client.granted = declared.toSet()
    client.callbacks.single().complete(true, null); runCurrent()
    assertEquals(before + 1, reconciliations)
    assertFalse(requests.state.value.requesting)
    assertTrue(requests.state.value.plan!!.complete)
  }
}
