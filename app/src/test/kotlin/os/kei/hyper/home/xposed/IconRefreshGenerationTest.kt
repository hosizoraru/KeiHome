package os.kei.hyper.home.xposed

import android.content.res.Configuration
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconRefreshGenerationTest {
  @Test fun labelOnlyNotificationAvoidsAnIconSweepWithoutLosingPendingResourceWork() {
    val generations = IconRefreshGeneration()
    val label = generations.nextLauncherLabelRefresh("module")!!
    assertEquals(setOf("module"), generations.packages(label))
    assertEquals(IconRefreshCause.LAUNCHER_LABEL_ACK, generations.cause(label))
    val packageChange = generations.nextPackageRefresh("changed.app")!!
    val merged = generations.nextLauncherLabelRefresh("module")!!
    generations.complete(packageChange)
    assertEquals(setOf("module", "changed.app"), generations.packages(merged))
    assertEquals(IconRefreshCause.PACKAGE_CHANGE, generations.cause(merged))
    generations.next(light, dark)
    val duringNight = generations.nextLauncherLabelRefresh("module")!!
    assertNull(generations.packages(duringNight))
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(duringNight))
    generations.complete(duringNight)
    generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)
    val duringPalette = generations.nextLauncherLabelRefresh("module")!!
    assertTrue(generations.needsFullRefresh(duringPalette))
    assertEquals(IconRefreshCause.PALETTE_ACK, generations.cause(duringPalette))
    generations.complete(duringPalette)
    val fresh = generations.nextLauncherLabelRefresh("module")!!
    assertEquals(IconRefreshCause.LAUNCHER_LABEL_ACK, generations.cause(fresh))
    generations.retire()
    assertNull(generations.nextLauncherLabelRefresh("module"))
  }

  @Test fun resourceQualifierChangesRefreshIconsWithoutTreatingEveryUiChangeAsAnIconChange() {
    for (change in listOf(ActivityInfo.CONFIG_LOCALE, ActivityInfo.CONFIG_LAYOUT_DIRECTION,
      ActivityInfo.CONFIG_DENSITY, ActivityInfo.CONFIG_COLOR_MODE, ActivityInfo.CONFIG_ASSETS_PATHS))
      assertTrue(iconResourceConfigurationChanged(change))
    assertFalse(iconResourceConfigurationChanged(ActivityInfo.CONFIG_FONT_SCALE or
      ActivityInfo.CONFIG_KEYBOARD or ActivityInfo.CONFIG_ORIENTATION))
    assertFalse(iconResourceConfigurationChanged(ActivityInfo.CONFIG_UI_MODE))
  }
  @Test fun packageChangesCoalesceWithoutDiscardingAnUnfinishedNightOrSourceRefresh() {
    val scoped = IconRefreshGeneration()
    val old = scoped.nextPackageRefresh("com.test.first")!!
    val latest = scoped.nextPackageRefresh("com.test.second")!!
    scoped.complete(old)
    assertEquals(setOf("com.test.first", "com.test.second"), scoped.packages(latest))
    assertEquals(IconRefreshCause.PACKAGE_CHANGE, scoped.cause(latest))
    val night = scoped.next(light, dark)!!
    assertNull(scoped.packages(night))
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, scoped.cause(night))
    val duringNight = scoped.nextPackageRefresh("com.test.third")!!
    assertNull(scoped.packages(duringNight))
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, scoped.cause(duringNight))
    scoped.complete(duringNight)
    val fresh = scoped.nextPackageRefresh("com.test.fourth")!!
    assertEquals(setOf("com.test.fourth"), scoped.packages(fresh))
    val source = scoped.nextFullRefresh()!!
    assertNull(scoped.packages(source))
    assertTrue(scoped.needsFullRefresh(source))
    scoped.retire()
    assertNull(scoped.nextPackageRefresh("com.test.late"))
  }
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
    val restore = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
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

  @Test
  fun paletteAckWithoutPendingResourcesUsesBoundedFasterPacing() {
    val generations = IconRefreshGeneration()
    val first = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
    val second = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
    assertFalse(generations.isCurrent(first))
    assertTrue(generations.needsFullRefresh(second))
    assertEquals(IconRefreshCause.PALETTE_ACK, generations.cause(second))
    assertEquals(100L, generations.cause(second).initialDelayMs)
    assertEquals(60L, generations.cause(second).packageIntervalMs)
  }

  @Test
  fun fullResourceRefreshRemainsConservativeByDefault() {
    val generations = IconRefreshGeneration()
    val source = generations.nextFullRefresh()!!
    assertTrue(generations.needsFullRefresh(source))
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(source))
    assertEquals(1500L, generations.cause(source).initialDelayMs)
    assertEquals(220L, generations.cause(source).packageIntervalMs)
  }

  @Test
  fun paletteAckCannotAccelerateAnUnfinishedNightRefresh() {
    val generations = IconRefreshGeneration()
    val night = generations.next(light, dark)!!
    assertFalse(generations.needsFullRefresh(night))
    val ack = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
    assertFalse(generations.isCurrent(night))
    assertTrue(generations.needsFullRefresh(ack))
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(ack))
    assertEquals(220L, generations.cause(ack).packageIntervalMs)
    // A cancelled worker finishing late cannot erase the merged resource cause.
    generations.complete(night)
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(ack))
  }

  @Test
  fun sourceChangeDominatesPaletteAcksInEitherOrder() {
    for (sourceFirst in listOf(false, true)) {
      val generations = IconRefreshGeneration()
      if (!sourceFirst) generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)
      val source = generations.nextFullRefresh()!!
      val ack = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
      assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(ack))
      generations.complete(source)
      val repeatedAck = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
      assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(repeatedAck))
    }
  }

  @Test
  fun nightChangeCancelsFastPaletteWorkAndCarriesFullRefreshForward() {
    val generations = IconRefreshGeneration()
    val palette = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
    val night = generations.next(dark, light)!!
    assertFalse(generations.isCurrent(palette))
    assertTrue(generations.needsFullRefresh(night))
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(night))
    val newerNight = generations.next(light, dark)!!
    generations.complete(night)
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(newerNight))
    assertTrue(generations.needsFullRefresh(newerNight))
  }

  @Test
  fun onlyCompletionOfTheCurrentResourceWorkAllowsAnotherFastPaletteSweep() {
    val generations = IconRefreshGeneration()
    val source = generations.nextFullRefresh()!!
    val merged = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
    generations.complete(source)
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(merged))
    generations.complete(merged)
    val stableModeAck = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
    assertEquals(IconRefreshCause.PALETTE_ACK, generations.cause(stableModeAck))
    assertTrue(generations.needsFullRefresh(stableModeAck))
  }

  @Test
  fun unchangedOrUnknownNightModeDoesNotRetagPaletteWork() {
    val generations = IconRefreshGeneration()
    val palette = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
    assertNull(generations.next(dark, dark or Configuration.UI_MODE_TYPE_CAR))
    assertNull(generations.next(Configuration.UI_MODE_NIGHT_UNDEFINED, dark))
    assertTrue(generations.isCurrent(palette))
    assertEquals(IconRefreshCause.PALETTE_ACK, generations.cause(palette))
  }

  @Test
  fun retiredAndStaleGenerationsCannotSelectFastPacing() {
    val generations = IconRefreshGeneration()
    val stale = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
    val current = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK)!!
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(stale))
    generations.retire()
    generations.complete(current)
    assertFalse(generations.isCurrent(current))
    assertEquals(IconRefreshCause.RESOURCE_CHANGE, generations.cause(current))
    assertNull(generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK))
  }
}
