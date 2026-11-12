package os.kei.hyper.home.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class PagerGestureMigrationDeviceTest {
  @Test
  fun retiredGestureRestoresSupportedModeWithoutChangingAppearance() {
    val storage = InstrumentationRegistry.getInstrumentation().targetContext
      .getSharedPreferences("test.pager-gesture-migration", Context.MODE_PRIVATE)
    try {
      storage.edit().clear()
        .putString("pager_gesture", "IOS_LIKE")
        .putString("appearance", "DARK")
        .putString("navigation_bar_style", "IOS_LIKE")
        .commit()
      val preferences = AppPreferences(storage, SystemIconFallback.entries)
      assertEquals(AppPagerGesture.CROSS_AXIS, preferences.state.value.pagerGesture)
      assertEquals(AppAppearance.DARK, preferences.state.value.appearance)
      assertEquals(AppNavigationBarStyle.IOS_LIKE, preferences.state.value.navigationBarStyle)

      preferences.setPagerGesture(AppPagerGesture.NATIVE)
      val restored = AppPreferences(storage, SystemIconFallback.entries).state.value
      assertEquals(AppPagerGesture.NATIVE, restored.pagerGesture)
      assertEquals(AppAppearance.DARK, restored.appearance)
      assertEquals(AppNavigationBarStyle.IOS_LIKE, restored.navigationBarStyle)
    } finally {
      storage.edit().clear().commit()
    }
  }
}
