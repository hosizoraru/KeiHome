package os.kei.hyper.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonetCustomizationTest {
  private val wallpaper = WallpaperColorSnapshot(MonetReadState.AVAILABLE,
    listOf(0xff113355.toInt(), 0xff773355.toInt()), listOf(0xff337755.toInt()))

  @Test fun selectionSurvivesWireRoundTrip() {
    for (source in MonetSeedSource.entries) for (style in MonetSchemeStyle.entries) {
      val choice = MonetCustomization(source, style, 1, 0xff168c43.toInt())
      assertEquals(choice, MonetCustomization.decode(choice.encode()))
    }
    assertNull(MonetCustomization.decode(null))
    assertNull(MonetCustomization.decode("not-a-selection"))
  }

  @Test fun wallpaperIndexTracksNewWallpaperAndFallsBackWhenItHasFewerSeeds() {
    val choice = MonetCustomization(colorIndex = 1)
    assertEquals(wallpaper.colors[1], choice.seed(wallpaper))
    val replacement = wallpaper.copy(colors = listOf(0xff778811.toInt()))
    assertEquals(replacement.colors[0], choice.seed(replacement))
    assertNull(choice.seed(wallpaper.copy(colors = emptyList())))
  }

  @Test fun lockAndFixedColorsRemainIndependentOfHomeWallpaper() {
    assertEquals(wallpaper.lockColors[0], MonetCustomization(MonetSeedSource.LOCK_WALLPAPER).seed(wallpaper))
    val fixed = MonetCustomization(MonetSeedSource.BASIC_COLOR, color = 0xff771111.toInt())
    assertEquals(fixed.color, fixed.seed(wallpaper))
    assertEquals(fixed.color, fixed.seed(wallpaper.copy(colors = emptyList(), lockColors = emptyList())))
  }
}
