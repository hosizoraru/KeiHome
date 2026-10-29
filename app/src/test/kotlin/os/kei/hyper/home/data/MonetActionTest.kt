package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import os.kei.hyper.home.data.MonetAction.ApplyResult
import os.kei.hyper.home.data.MonetAction.Setting

class MonetActionTest {
  private val request = MonetActionRequest(100_000L, 12)
  private val initial = MonetAction.Selection("-1", null, "0", null, null)
  private val color = 0xff332d41.toInt()

  @Test
  fun submitsOneAtomicCommitWithUserDerivedFromAppUid() {
    val remote = Preferences()
    assertEquals(request, MonetAction.submit(remote.preferences, 1_210_141, 100_000L))
    assertEquals(1, remote.commits)
    assertEquals(mapOf<String, Any>("request_revision" to 100_000L, "request_user" to 12), remote.values)
    assertEquals(100_001L, MonetAction.submit(remote.preferences, 1_210_141, 100_000L).revision)
    assertEquals(100_002L, MonetAction.submit(remote.preferences, 1_210_141, 90_000L).revision)
  }

  @Test
  fun failedCommitCannotBeMistakenForSuccessEvenWhenServiceCacheWasUpdated() {
    val remote = Preferences(commitSucceeds = false)
    assertThrows(IllegalStateException::class.java) {
      MonetAction.submit(remote.preferences, 1_210_141, request.revision)
    }
    assertEquals(request, MonetAction.decode(remote.values))
    assertEquals(1, remote.commits)
  }

  @Test
  fun readOnlyPreferencesFailWithoutSendingARequest() {
    val remote = Preferences(readOnly = true)
    assertThrows(UnsupportedOperationException::class.java) {
      MonetAction.submit(remote.preferences, 1_210_141, request.revision)
    }
    assertEquals(0, remote.commits)
    assertEquals(emptyMap<String, Any>(), remote.values)
  }

  @Test
  fun malformedOrTornRequestFieldsAreNotAccepted() {
    for (values in listOf(
      emptyMap(), mapOf("request_revision" to request.revision),
      mapOf("request_revision" to "100000", "request_user" to 12),
      mapOf("request_revision" to 100_000, "request_user" to 12),
      mapOf("request_revision" to 100_000L, "request_user" to 12L),
      mapOf("request_revision" to 0L, "request_user" to 12),
      mapOf("request_revision" to 100_000L, "request_user" to -1),
    )) assertNull(MonetAction.decode(values))
    assertThrows(IllegalArgumentException::class.java) { MonetAction.nextRevision(Long.MAX_VALUE, 1L) }
  }

  @Test
  fun foregroundUserFreshnessAndDurableReceiptsGateExplicitOverride() {
    assertEquals(request, MonetAction.pending(request, 12, initial, 100_750L))
    assertNull(MonetAction.pending(request, 0, initial, 100_750L))
    assertNull(MonetAction.pending(request, 12, initial, 160_001L))
    assertNull(MonetAction.pending(request, 12, initial, 99_999L))
    assertNull(MonetAction.pending(request, 12, initial.copy(applied = "100000"), 100_750L))
    assertNull(MonetAction.pending(request, 12, initial.copy(handled = "100001"), 100_750L))
    assertNull(MonetAction.pending(request, 12, initial.copy(handled = "broken"), 100_750L))
    assertNull(MonetAction.pending(request, 12, initial.copy(applied = "-1"), 100_750L))
  }

  @Test
  fun nativeUnavailableWritesNeitherSettingsNorReceipt() {
    val store = Store(initial)
    assertEquals(ApplyResult.NATIVE_UNAVAILABLE, apply(store, acknowledged = false))
    assertEquals(initial, store.selection)
    assertEquals(emptyList<String>(), store.events)
  }

  @Test
  fun writesBackgroundBeforeEnablingAndAcknowledgesOnlyAfterNotification() {
    val store = Store(initial)
    assertEquals(ApplyResult.APPLIED, apply(store))
    assertEquals(listOf("HANDLED", "BACKGROUND", "AUTO_COLOR", "ENABLED", "notify", "APPLIED"), store.events)
    assertEquals(color.toString(), store.selection.background)
    assertEquals(color.toString(), store.selection.autoColor)
    assertEquals("1", store.selection.enabled)
    assertEquals(request.revision.toString(), store.selection.applied)
    assertNull(MonetAction.pending(request, 12, store.selection, 101_000L))
  }

  @Test
  fun retiredGenerationOrChangedRequestDoesNotConsumeOrApply() {
    val store = Store(initial)
    assertEquals(ApplyResult.SELECTION_CHANGED, apply(store) { false })
    assertEquals(emptyList<String>(), store.events)
    assertNotNull(MonetAction.pending(request, 12, store.selection, 101_000L))
  }

  @Test
  fun newerManualChoiceDuringApplyIsPreservedAndOldRequestCannotReplay() {
    val store = Store(initial)
    store.afterWrite = { key ->
      if (key == Setting.BACKGROUND) store.selection = store.selection.copy(background = "123")
    }
    assertEquals(ApplyResult.SELECTION_CHANGED, apply(store))
    assertEquals("123", store.selection.background)
    assertEquals("0", store.selection.enabled)
    assertNull(store.selection.applied)
    assertNull(MonetAction.pending(request, 12, store.selection, 101_000L))
    store.events.clear()
    assertEquals(ApplyResult.SELECTION_CHANGED, apply(store, snapshot = store.selection))
    assertEquals(emptyList<String>(), store.events)
  }

  @Test
  fun partialWriteFailureNeedsAFreshPressEvenAfterHotReload() {
    val store = Store(initial)
    store.failAt = Setting.BACKGROUND
    assertEquals(ApplyResult.WRITE_FAILED, apply(store))
    assertEquals(initial.background, store.selection.background)
    assertEquals("0", store.selection.enabled)
    assertNull(store.selection.applied)
    assertNull(MonetAction.pending(request, 12, store.selection.copy(), 101_000L))
    assertNotNull(MonetAction.pending(request.copy(revision = 101_000L), 12, store.selection, 101_001L))
  }

  @Test
  fun failedClaimHasNoEffectAndMayBeRetried() {
    val store = Store(initial)
    store.failAt = Setting.HANDLED
    assertEquals(ApplyResult.WRITE_FAILED, apply(store))
    assertEquals(initial, store.selection)
    assertNotNull(MonetAction.pending(request, 12, store.selection, 101_000L))
  }

  @Test
  fun notificationFailureDoesNotPublishASuccessReceipt() {
    val store = Store(initial)
    store.onNotify = { throw IllegalStateException("provider unavailable") }
    assertThrows(IllegalStateException::class.java) { apply(store) }
    assertNull(store.selection.applied)
    assertFalse("APPLIED" in store.events)
  }

  @Test
  fun userChangeDuringNotificationPreventsSuccessReceipt() {
    val store = Store(initial)
    var current = true
    store.onNotify = { current = false }
    assertEquals(ApplyResult.SELECTION_CHANGED, apply(store) { current })
    assertNull(store.selection.applied)
  }

  @Test
  fun manualChoiceBeforeClaimCancelsOnlyThatRevision() {
    for (changed in listOf(
      initial.copy(background = "123"), initial.copy(autoColor = "456"), initial.copy(enabled = "1"),
    )) {
      val store = Store(changed)
      assertEquals(ApplyResult.SELECTION_CHANGED, apply(store))
      assertEquals(listOf("HANDLED"), store.events)
      assertEquals(changed.copy(handled = request.revision.toString()), store.selection)
      assertNull(MonetAction.pending(request, 12, store.selection, 101_000L))
      val newer = request.copy(revision = request.revision + 1)
      assertEquals(newer, MonetAction.pending(newer, 12, store.selection, 101_000L))
    }
  }

  @Test
  fun selectionChangedDuringResourceScanIsCancelledBeforeNativePublication() {
    val store = Store(initial)
    assertNull(MonetAction.checkSelection(request, initial, store) { true })
    // The scan does not own Settings; simulate a native preset arriving while it runs.
    store.selection = store.selection.copy(background = "123", enabled = "1")
    val manual = store.selection
    assertEquals(ApplyResult.SELECTION_CHANGED, MonetAction.checkSelection(request, initial, store) { true })
    assertEquals(manual.copy(handled = request.revision.toString()), store.selection)
    assertEquals(listOf("HANDLED"), store.events)
    // Cancellation survives creation of a fresh Java-generation baseline.
    assertNull(MonetAction.Baseline().capture(request, 12, store.selection, 101_000L))
  }

  @Test
  fun cancellationReadsLatestHandledAndNeverOverwritesANewerReceipt() {
    val store = Store(initial.copy(background = "123"))
    var reads = 0
    store.beforeRead = {
      if (++reads == 2) store.selection = store.selection.copy(handled = "100005")
    }
    assertEquals(ApplyResult.SELECTION_CHANGED, MonetAction.checkSelection(request, initial, store) { true })
    assertEquals("100005", store.selection.handled)
    assertEquals("123", store.selection.background)
    assertEquals(emptyList<String>(), store.events)
  }

  @Test
  fun cancellationDoesNotAlterANewerAppliedRevisionOrMalformedLedger() {
    val newer = Store(initial.copy(background = "123", applied = "100005"))
    assertEquals(ApplyResult.SELECTION_CHANGED, MonetAction.checkSelection(request, initial, newer) { true })
    assertEquals("100005", newer.selection.applied)
    assertEquals(emptyList<String>(), newer.events)
    val malformed = Store(initial.copy(background = "123", handled = "broken"))
    assertEquals(ApplyResult.WRITE_FAILED, MonetAction.checkSelection(request, initial, malformed) { true })
    assertEquals(emptyList<String>(), malformed.events)
  }

  @Test
  fun receiptOnlyChangesDoNotBecomeManualCancellationOrSuccess() {
    val store = Store(initial.copy(handled = "99999", applied = "88888"))
    assertEquals(ApplyResult.SELECTION_CHANGED, MonetAction.checkSelection(request, initial, store) { true })
    assertEquals(emptyList<String>(), store.events)
    assertEquals("99999", store.selection.handled)
    assertEquals("88888", store.selection.applied)
  }

  @Test
  fun retirementDuringSelectionReadDoesNotWriteCancellationOrSuccess() {
    val store = Store(initial.copy(background = "123"))
    var current = true
    store.beforeRead = { current = false }
    assertEquals(ApplyResult.SELECTION_CHANGED, MonetAction.checkSelection(request, initial, store) { current })
    assertEquals(emptyList<String>(), store.events)
    assertNull(store.selection.handled)
    assertNull(store.selection.applied)
  }

  @Test
  fun supersedingRequestDoesNotGetConsumedByTheOldAttempt() {
    val store = Store(initial.copy(background = "123"))
    val latest = request.copy(revision = request.revision + 1)
    assertEquals(ApplyResult.SELECTION_CHANGED, apply(store) { latest == request })
    assertEquals(emptyList<String>(), store.events)
    assertEquals(latest, MonetAction.pending(latest, 12, store.selection, 101_000L))
  }

  @Test
  fun failedCancellationKeepsOriginalBaselineAcrossRetryAndUserSwitch() {
    val baseline = MonetAction.Baseline()
    val store = Store(initial)
    assertNotNull(baseline.capture(request, 12, store.selection, 100_100L))
    store.selection = initial.copy(background = "123")
    store.failAt = Setting.HANDLED
    val interrupted = baseline.capture(request, 12, store.selection, 100_200L)!!
    assertEquals(initial, interrupted.initial)
    assertEquals(ApplyResult.WRITE_FAILED,
      MonetAction.checkSelection(request, interrupted.initial, store) { true })
    assertNull(store.selection.handled)
    assertNull(baseline.capture(request, 0, store.selection, 100_300L))
    store.failAt = null
    val retry = baseline.capture(request, 12, store.selection, 100_400L)!!
    assertEquals(initial, retry.initial)
    assertEquals(ApplyResult.SELECTION_CHANGED,
      MonetAction.checkSelection(request, retry.initial, store) { true })
    assertEquals("123", store.selection.background)
    assertEquals(request.revision.toString(), store.selection.handled)
    assertNull(store.selection.applied)
  }

  @Test
  fun retryRefreshesOlderReceiptsButOnlyAFreshRequestRebasesTheChoice() {
    val baseline = MonetAction.Baseline()
    assertNotNull(baseline.capture(request, 12, initial, 100_100L))
    val manual = initial.copy(background = "123", handled = "99999", applied = "88888")
    val retry = baseline.capture(request, 12, manual, 100_200L)!!
    assertEquals(initial.copy(handled = "99999", applied = "88888"), retry.initial)
    val newer = request.copy(revision = 100_300L)
    val fresh = baseline.capture(newer, 12, manual, 100_400L)!!
    assertEquals(manual, fresh.initial)
    val store = Store(manual)
    assertEquals(ApplyResult.APPLIED,
      MonetAction.applyAcknowledged(newer, color, fresh.initial, store, true) { true })
    assertEquals(newer.revision.toString(), store.selection.applied)
  }

  private fun apply(
    store: Store, acknowledged: Boolean = true, snapshot: MonetAction.Selection = initial,
    current: () -> Boolean = { true },
  ) = MonetAction.applyAcknowledged(request, color, snapshot, store, acknowledged, current)

  private class Store(var selection: MonetAction.Selection) : MonetAction.Store {
    val events = mutableListOf<String>()
    var failAt: Setting? = null
    var afterWrite: (Setting) -> Unit = {}
    var onNotify: () -> Unit = {}
    var beforeRead: () -> Unit = {}
    override fun read(): MonetAction.Selection {
      beforeRead()
      return selection
    }
    override fun write(setting: Setting, value: String): Boolean {
      events += setting.name
      if (setting == failAt) return false
      selection = selection.updated(setting, value)
      afterWrite(setting)
      return true
    }
    override fun notifyChanged() {
      events += "notify"
      onNotify()
    }
  }

  private class Preferences(val commitSucceeds: Boolean = true, val readOnly: Boolean = false) {
    val values = mutableMapOf<String, Any>()
    var commits = 0
    val preferences = Proxy.newProxyInstance(
      SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "getAll" -> values.toMap()
        "edit" -> {
          if (readOnly) throw UnsupportedOperationException("read-only")
          val pending = mutableMapOf<String, Any>()
          Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
          ) { editor, operation, args ->
            when (operation.name) {
              "putLong", "putInt" -> { pending[args[0] as String] = args[1]; editor }
              "commit" -> { commits += 1; values.putAll(pending); commitSucceeds }
              else -> error("Unexpected editor call: ${operation.name}")
            }
          }
        }
        else -> error("Unexpected preference call: ${method.name}")
      }
    } as SharedPreferences
  }
}
