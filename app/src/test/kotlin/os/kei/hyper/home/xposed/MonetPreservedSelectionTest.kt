package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.MonetAction
import os.kei.hyper.home.data.MonetAction.ApplyResult
import os.kei.hyper.home.data.MonetAction.Setting
import os.kei.hyper.home.data.MonetActionRequest
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetSeedSource

class MonetPreservedSelectionTest {
  private val color = 0xff332d41.toInt()
  private val request = MonetActionRequest(100L, 0,
    customization = MonetCustomization(MonetSeedSource.BASIC_COLOR, color = color), preserveEnabled = true)

  @Test fun offAndAbsentRemainOffWithoutAnyNativeOrMamlEnable() {
    for (raw in listOf("0", null)) {
      val store = Store(raw)
      assertEquals(ApplyResult.APPLIED, apply(store))
      assertEquals(raw, store.selection.enabled)
      assertEquals(request.customization!!.encode(), store.selection.customization)
      assertEquals(color.toString(), store.selection.background)
      assertEquals(color.toString(), store.selection.autoColor)
      assertEquals(request.revision.toString(), store.selection.applied)
      assertEquals("withdraw-native-and-maml", store.events.first())
      assertEquals("write:APPLIED", store.events.last())
      assertFalse(store.events.contains("native:on"))
      assertFalse(store.events.contains("maml:on"))
      assertFalse(store.events.contains("write:ENABLED"))
    }
  }

  @Test fun onKeepsEnabledAndAcknowledgesOnlyAfterMamlPublication() {
    val store = Store("1")
    assertEquals(ApplyResult.APPLIED, apply(store, palette = {
      assertNull(store.selection.applied)
      assertEquals(request.revision.toString(), store.selection.handled)
      assertEquals("1", store.selection.enabled)
      ApplyResult.APPLIED
    }))
    assertEquals("native:on", store.events.first())
    assertTrue(store.events.indexOf("maml:on") < store.events.indexOf("write:APPLIED"))
    assertEquals("write:APPLIED", store.events.last())
    assertFalse(store.events.contains("write:ENABLED"))
  }

  @Test fun offNeedsItsOwnSuccessfulWithdrawalBeforeWritingAnyColorOrReceipt() {
    val store = Store("0")
    assertEquals(ApplyResult.NATIVE_UNAVAILABLE, apply(store, native = { false }))
    assertEquals(listOf("withdraw-native-and-maml"), store.events)
    assertNull(store.selection.handled)
    assertNull(store.selection.applied)
    assertEquals("old", store.selection.background)
  }

  @Test fun failedNativeOrMamlDoesNotProduceAnAppliedReceipt() {
    val native = Store("1")
    assertEquals(ApplyResult.NATIVE_UNAVAILABLE, apply(native, native = { false }))
    assertNull(native.selection.handled)
    assertNull(native.selection.applied)
    for (failure in listOf(ApplyResult.WRITE_FAILED, ApplyResult.NATIVE_UNAVAILABLE,
      ApplyResult.SELECTION_CHANGED)) {
      val store = Store("1")
      assertEquals(failure, apply(store, palette = { failure }))
      assertNull(store.selection.applied)
      assertFalse(store.events.contains("write:APPLIED"))
    }
  }

  @Test fun eitherConcurrentToggleDuringNativeExchangeWinsWithoutAnySettingsOverwrite() {
    for ((initial, concurrent) in listOf("0" to "1", "1" to "0")) {
      val store = Store(initial)
      assertEquals(ApplyResult.SELECTION_CHANGED, apply(store, native = {
        store.selection = store.selection.copy(enabled = concurrent)
        true
      }))
      assertEquals(concurrent, store.selection.enabled)
      assertNull(store.selection.applied)
      assertEquals(listOf("write:HANDLED"), store.events.filter { it.startsWith("write:") })
      assertFalse(store.events.contains("maml:on"))
    }
  }

  @Test fun togglesDuringSettingsNotificationAndMamlPublicationCannotBeAcknowledged() {
    for ((initial, concurrent) in listOf("0" to "1", "1" to "0")) {
      val store = Store(initial)
      store.onNotify = { store.selection = store.selection.copy(enabled = concurrent) }
      assertEquals(ApplyResult.SELECTION_CHANGED, apply(store))
      assertEquals(concurrent, store.selection.enabled)
      assertNull(store.selection.applied)
      assertFalse(store.events.contains("maml:on"))
      assertFalse(store.events.contains("write:ENABLED"))
    }
    val store = Store("1")
    assertEquals(ApplyResult.SELECTION_CHANGED, apply(store, palette = {
      store.selection = store.selection.copy(enabled = "0")
      ApplyResult.APPLIED
    }))
    assertEquals("0", store.selection.enabled)
    assertNull(store.selection.applied)
  }

  @Test fun newerRemoteRequestSupersedesColorsWithoutConsumptionOrFalseAck() {
    var current = true
    val store = Store("0")
    assertEquals(ApplyResult.SELECTION_CHANGED, apply(store,
      native = { current = false; true }, current = { current }))
    assertNull(store.selection.handled)
    assertNull(store.selection.applied)
    assertFalse(store.events.any { it.startsWith("write:") })
  }

  @Test fun settingsFailureDoesNotEnableOrAcknowledgeAndRequiresAnotherPress() {
    val store = Store("0")
    store.fail = Setting.CUSTOMIZATION
    assertEquals(ApplyResult.WRITE_FAILED, apply(store))
    assertEquals("0", store.selection.enabled)
    assertEquals(request.revision.toString(), store.selection.handled)
    assertNull(store.selection.applied)
    assertFalse(store.events.contains("write:ENABLED"))
    store.events.clear()
    assertEquals(ApplyResult.SELECTION_CHANGED, apply(store))
    assertTrue(store.events.isEmpty())
  }

  private fun apply(
    store: Store, native: () -> Boolean = { true },
    palette: () -> ApplyResult = { ApplyResult.APPLIED }, current: () -> Boolean = { true },
  ): ApplyResult = applyPreservedMonetSelection(request, color, store.selection, store,
    publishNative = { store.events += "native:on"; native() },
    withdrawNative = { store.events += "withdraw-native-and-maml"; native() },
    publishPalette = { store.events += "maml:on"; palette() }, isCurrent = current)

  private class Store(enabled: String?) : MonetAction.Store {
    var selection = MonetAction.Selection("old", "auto", enabled, null, null)
    val events = mutableListOf<String>()
    var fail: Setting? = null
    var onNotify: () -> Unit = {}
    override fun read() = selection
    override fun write(setting: Setting, value: String): Boolean {
      events += "write:$setting"
      if (setting == fail) return false
      selection = selection.updated(setting, value)
      return true
    }
    override fun notifyChanged() { events += "notify"; onNotify() }
  }
}
