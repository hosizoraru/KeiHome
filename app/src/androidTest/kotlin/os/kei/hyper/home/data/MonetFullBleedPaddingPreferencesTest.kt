package os.kei.hyper.home.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MonetFullBleedPaddingPreferencesTest {
  @Test fun newOptionDefaultsOffPersistsAndDoesNotEnableItsParent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val name = "monet-padding-test-${UUID.randomUUID()}"
    try {
      val storage = context.getSharedPreferences(name, Context.MODE_PRIVATE)
      val preferences = AppPreferences(storage, SystemIconFallback.entries)
      assertFalse(preferences.state.value.monetFullBleedIconPadding)
      preferences.setMonetFullBleedIconPadding(true)
      assertTrue(AppPreferences(storage, SystemIconFallback.entries).state.value.monetFullBleedIconPadding)
      assertFalse(preferences.state.value.monetUnadaptedIcons)
      preferences.setMonetUnadaptedIcons(true)
      preferences.setMonetUnadaptedIcons(false)
      assertTrue(preferences.state.value.monetFullBleedIconPadding)
      preferences.setMonetFullBleedIconPadding(false)
      assertFalse(AppPreferences(storage, SystemIconFallback.entries).state.value.monetFullBleedIconPadding)
    } finally { context.deleteSharedPreferences(name) }
  }
}
