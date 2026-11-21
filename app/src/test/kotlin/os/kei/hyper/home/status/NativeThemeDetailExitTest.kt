package os.kei.hyper.home.status

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeThemeDetailExitTest {
  @Test fun immediateCancellationSuggestsReimport() {
    assertTrue(needsThemeDetailRetry(Activity.RESULT_CANCELED, 180))
  }

  @Test fun applyingOrReturningAfterViewingDoesNotSuggestReimport() {
    assertFalse(needsThemeDetailRetry(Activity.RESULT_OK, 180))
    assertFalse(needsThemeDetailRetry(Activity.RESULT_CANCELED, 8_000))
  }
}
