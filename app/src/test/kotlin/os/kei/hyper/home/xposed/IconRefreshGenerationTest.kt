package os.kei.hyper.home.xposed

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconRefreshGenerationTest {
  private val light = Configuration.UI_MODE_NIGHT_NO
  private val dark = Configuration.UI_MODE_NIGHT_YES

  @Test
  fun ignoresChangesThatDoNotChangeKnownNightMode() {
    assertFalse(isNightModeTransition(light, light or Configuration.UI_MODE_TYPE_CAR))
    assertFalse(isNightModeTransition(Configuration.UI_MODE_NIGHT_UNDEFINED, dark))
    assertFalse(isNightModeTransition(light, Configuration.UI_MODE_NIGHT_UNDEFINED))
    assertTrue(isNightModeTransition(light, dark))
    assertTrue(isNightModeTransition(dark, light))
  }

  @Test
  fun newerModeCancelsOlderGenerationAndRetirementCancelsAll() {
    val generations = IconRefreshGeneration()
    val first = generations.next(light, dark)!!
    assertTrue(generations.isCurrent(first))
    assertNull(generations.next(dark, dark))
    val second = generations.next(dark, light)!!
    assertEquals(first + 1, second)
    assertFalse(generations.isCurrent(first))
    assertTrue(generations.isCurrent(second))
    generations.retire()
    assertFalse(generations.isCurrent(second))
    assertNull(generations.next(light, dark))
  }
}
