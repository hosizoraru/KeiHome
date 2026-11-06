package os.kei.hyper.home.status

import org.junit.Assert.*
import org.junit.Test

class IconImportLocalStateTest {
  private fun request(token: String = "new", source: String = "source") = IconPackImportRequest(token, 0, 10, source)
  private fun result(token: String = "new", source: String = "source", status: String = "IMPORTED", id: String = "component") =
    NativeIconImportReport(token, 0, source, status, id, autoApplyToken = token)

  @Test fun terminalReceiptCommitsPendingAndBothOwnershipDirectionsTogether() {
    val initial = IconImportLocalState(associations = mapOf("source" to "old")).begin(request())
    val mutation = initial.accept(result())
    assertTrue(mutation.publish)
    assertTrue(mutation.complete)
    assertNull(mutation.state.pendingSource)
    assertNull(mutation.state.request)
    assertEquals("component", mutation.state.associations["source"])
    assertEquals("source", mutation.state.sources["old"])
    assertEquals("source", mutation.state.sources["component"])
    assertEquals("old", initial.associations["source"])
    assertEquals("new", initial.pendingToken)
  }

  @Test fun staleReceiptRetainsHistoricalOwnershipWithoutRegressingNewerSourceOrPending() {
    val completed = IconImportLocalState().begin(request()).accept(result()).state
    val pending = completed.begin(request("next"))
    val stale = pending.accept(result("older", id = "historical"))
    assertFalse(stale.publish)
    assertFalse(stale.complete)
    assertEquals("next", stale.state.pendingToken)
    assertEquals("component", stale.state.associations["source"])
    assertEquals("source", stale.state.sources["historical"])
    assertNull(stale.state.result)
  }

  @Test fun staleSourceCannotReassignAnAlreadyOwnedNativeComponent() {
    val completed = IconImportLocalState().begin(request()).accept(result()).state
    val stale = completed.accept(result("older", "old-source"))
    assertFalse(stale.publish)
    assertEquals("source", stale.state.sources["component"])
    assertNull(stale.state.associations["old-source"])
  }

  @Test fun importingCannotReplaceCompletedReceiptAndSameTokenMustMatchSource() {
    val completed = IconImportLocalState().begin(request()).accept(result()).state
    val late = completed.accept(result(status = "IMPORTING"))
    assertFalse(late.publish)
    assertEquals("IMPORTED", late.state.result!!.status)
    val wrong = IconImportLocalState().begin(request()).accept(result(source = "wrong"))
    assertFalse(wrong.publish)
    assertFalse(wrong.complete)
    assertEquals("source", wrong.state.pendingSource)
    assertTrue(wrong.state.associations.isEmpty())
    assertTrue(wrong.state.sources.isEmpty())
    assertFalse(wrong.accepted)
  }

  @Test fun uncertainReceiptKeepsOriginalRequestForJournalReconciliation() {
    val request = request()
    val uncertain = IconImportLocalState().begin(request).accept(result(status = "UNCERTAIN"))
    assertTrue(uncertain.publish)
    assertFalse(uncertain.complete)
    assertEquals(request, uncertain.state.request)
    assertEquals("source", uncertain.state.pendingSource)
  }

  @Test fun automaticNavigationRequiresCurrentDurableReceiptAndIsNotReplayedAfterAnotherToken() {
    val a = result()
    val completed = IconImportLocalState().begin(request()).accept(a).state
    val consumed = requireNotNull(completed.consumeNavigation(a))
    assertNull(consumed.consumeNavigation(a))
    val b = result("next")
    val next = consumed.begin(request("next")).accept(b).state
    val consumedNext = requireNotNull(next.consumeNavigation(b))
    assertNull(consumedNext.consumeNavigation(a))
    assertTrue(consumedNext.navigated.containsAll(listOf("new", "next")))
    assertNull(IconImportLocalState().consumeNavigation(a))
  }

  @Test fun unfinishedRequestsOfferRecoveryWithoutInventingAConfirmedReceipt() {
    val pending = IconImportLocalState().begin(request())
    assertEquals("UNCERTAIN", pending.presentationResult(0)!!.status)
    assertNull(pending.result)
    val importing = pending.accept(result(status = "IMPORTING")).state
    assertEquals("IMPORTING", importing.presentationResult(0)!!.status)
    assertEquals("IMPORTING", importing.result!!.status)
  }

  @Test fun pendingPresentationRemainsNeutralUntilARealTerminalReceipt() {
    val initial = IconImportLocalState().begin(request())
    assertTrue(initial.presentationResult(0)!!.pending)
    val uncertain = initial.accept(result(status = "UNCERTAIN")).state
    assertTrue(uncertain.presentationResult(0)!!.pending)
    val delayed = uncertain.accept(result()).state
    assertTrue(delayed.presentationResult(0)!!.imported)
    assertNull(delayed.pendingReceiptOperation(0))
    val failed = uncertain.accept(result(status = "FAILED")).state
    assertFalse(failed.presentationResult(0)!!.pending)
    assertEquals("FAILED", failed.presentationResult(0)!!.status)
    assertNull(failed.pendingReceiptOperation(0))
  }

  @Test fun queryIdentityRequiresTheOriginalTokenSourceAndUser() {
    val initial = IconImportLocalState().begin(request())
    val expected = initial.pendingReceiptOperation(0)!!
    assertEquals(expected, initial.accept(result(status = "IMPORTING")).state.pendingReceiptOperation(0))
    assertEquals(expected, initial.accept(result(status = "UNCERTAIN")).state.pendingReceiptOperation(0))
    assertNull(initial.pendingReceiptOperation(10))
    assertNotEquals(expected, initial.begin(request("next")).pendingReceiptOperation(0))
    assertNull(initial.copy(pendingSource = "other").pendingReceiptOperation(0))
    assertNull(initial.copy(pendingToken = "other").pendingReceiptOperation(0))
  }

  @Test fun delayedSuccessAndItsReplayCannotApplyTheSameOperationTwice() {
    val pending = IconImportLocalState().begin(request().copy(autoApply = true))
      .accept(result(status = "UNCERTAIN")).state
    val receipt = result()
    val completed = pending.accept(receipt).state
    val navigated = requireNotNull(completed.consumeNavigation(receipt))
    val replayed = navigated.accept(receipt).state
    assertEquals("component", replayed.associations["source"])
    assertNull(replayed.pendingReceiptOperation(0))
    assertNull(replayed.consumeNavigation(receipt))
  }

  @Test fun paletteMetadataBelongsToTheAcceptedOperationAndSurvivesLaterPreparation() {
    val first = IconImportLocalState().begin(request().copy(preparedPalette = true)).accept(result()).state
    assertEquals(true, first.componentPalette["component"])
    val preparingAnother = first.begin(request("next").copy(preparedPalette = false))
    assertEquals(true, preparingAnother.componentPalette["component"])
    val second = preparingAnother.accept(result("next", id = "second")).state
    assertEquals(true, second.componentPalette["component"])
    assertEquals(false, second.componentPalette["second"])
  }
}
