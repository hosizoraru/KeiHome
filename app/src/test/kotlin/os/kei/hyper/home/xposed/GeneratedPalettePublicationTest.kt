package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.xposed.GeneratedPalettePublication.Result

class GeneratedPalettePublicationTest {
  private val colors = IconColors(0xff12ab34.toInt(), 0xff5678cd.toInt())
  private val fg = NativePaletteIcon.FOREGROUND_KEY
  private val bg = NativePaletteIcon.BACKGROUND_KEY
  private val enabled = NativePaletteIcon.ENABLED_KEY

  @Test fun missingAckOrRetiredUserOwnerCannotReadWriteOrNotify() {
    val store = Store()
    assertEquals(Result.NATIVE_UNAVAILABLE, GeneratedPalettePublication.publish(colors, false, store) { true })
    assertEquals(Result.SELECTION_CHANGED, GeneratedPalettePublication.publish(colors, true, store) { false })
    assertTrue(store.events.isEmpty())
  }

  @Test fun publishesCanonicalHexColorsBeforeEnablingAndRepeatsAreNoops() {
    val store = Store()
    assertEquals(Result.CHANGED, GeneratedPalettePublication.publish(colors, true, store) { true })
    assertEquals(listOf(fg to "#FF12AB34", bg to "#FF5678CD", enabled to "1"), store.writes)
    assertEquals(listOf(fg, bg, enabled), store.notifications)
    store.clearEvents()
    assertEquals(Result.UNCHANGED, GeneratedPalettePublication.publish(colors, true, store) { true })
    assertTrue(store.writes.isEmpty())
    assertTrue(store.notifications.isEmpty())
  }

  @Test fun publishesOnlyChangedKeysAndPreservesAllArgbBits() {
    val store = Store()
    GeneratedPalettePublication.publish(colors, true, store) { true }
    store.clearEvents()
    assertEquals(Result.CHANGED,
      GeneratedPalettePublication.publish(colors.copy(foreground = 0x00112233), true, store) { true })
    assertEquals(listOf(fg to "#00112233"), store.writes)
    assertEquals(listOf(fg), store.notifications)
  }

  @Test fun disableRequiresAckChangesOnlyEnabledAndRetainsColorsForReenable() {
    val store = Store()
    GeneratedPalettePublication.publish(colors, true, store) { true }
    store.clearEvents()
    assertEquals(Result.NATIVE_UNAVAILABLE, GeneratedPalettePublication.publish(null, false, store) { true })
    assertTrue(store.writes.isEmpty())
    assertEquals(Result.CHANGED, GeneratedPalettePublication.publish(null, true, store) { true })
    assertEquals(listOf(enabled to "0"), store.writes)
    assertEquals("#FF12AB34", store.values[fg])
    assertEquals("#FF5678CD", store.values[bg])
    store.clearEvents()
    assertEquals(Result.UNCHANGED, GeneratedPalettePublication.publish(null, true, store) { true })
    assertTrue(store.notifications.isEmpty())
    GeneratedPalettePublication.publish(colors, true, store) { true }
    assertEquals(listOf(enabled to "1"), store.writes)
  }

  @Test fun failedColorWriteDisablesAnOldEnabledPaletteAndDoesNotPublishSuccess() {
    val store = Store()
    GeneratedPalettePublication.publish(colors, true, store) { true }
    store.clearEvents()
    store.failedKey = bg
    assertEquals(Result.WRITE_FAILED,
      GeneratedPalettePublication.publish(IconColors(-1, -2), true, store) { true })
    assertEquals("0", store.values[enabled])
    assertEquals("#FF5678CD", store.values[bg])
    assertEquals(listOf(fg, enabled), store.notifications)
    assertTrue(store.writes.none { it == enabled to "1" })
  }

  @Test fun userOrGenerationChangeDuringPublicationStopsBeforeAnotherWriteOrNotification() {
    val store = Store()
    var current = true
    store.afterWrite = { current = false }
    assertEquals(Result.SELECTION_CHANGED, GeneratedPalettePublication.publish(colors, true, store) { current })
    assertEquals(listOf(fg to "#FF12AB34"), store.writes)
    assertTrue(store.notifications.isEmpty())
    assertEquals(null, store.values[enabled])
  }

  private class Store : GeneratedPalettePublication.Store {
    val values = mutableMapOf<String, String>()
    val writes = mutableListOf<Pair<String, String>>()
    val notifications = mutableListOf<String>()
    val events = mutableListOf<String>()
    var failedKey: String? = null
    var afterWrite: () -> Unit = {}
    override fun read(key: String): String? {
      events.add("read:$key")
      return values[key]
    }
    override fun write(key: String, value: String): Boolean {
      events.add("write:$key")
      if (key == failedKey) return false
      writes.add(key to value)
      values[key] = value
      afterWrite()
      return true
    }
    override fun notifyChanged(key: String) { events.add("notify:$key"); notifications.add(key) }
    fun clearEvents() { writes.clear(); notifications.clear(); events.clear() }
  }
}
