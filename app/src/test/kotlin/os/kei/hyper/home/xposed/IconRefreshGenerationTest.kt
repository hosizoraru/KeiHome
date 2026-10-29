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

  @Test
  fun themeTransitionsCarryAnUnfinishedPaletteRestoration() {
    val generations = IconRefreshGeneration()
    val restore = generations.nextFullRefresh()!!
    val theme = generations.next(light, dark)!!
    generations.complete(restore)
    assertTrue(generations.needsFullRefresh(theme))
    assertFalse(generations.needsFullRefresh(restore))
    val latest = generations.next(dark, light)!!
    assertTrue(generations.needsFullRefresh(latest))
    generations.complete(latest)
    assertFalse(generations.needsFullRefresh(latest))
    assertFalse(generations.needsFullRefresh(generations.next(light, dark)!!))
    generations.retire()
    assertNull(generations.nextFullRefresh())
  }
}
