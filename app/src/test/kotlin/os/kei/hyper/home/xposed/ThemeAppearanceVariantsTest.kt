package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeAppearanceVariantsTest {
  private fun digests(seed: Char) = ConvertedIconArchive.Digests("$seed".repeat(40), "$seed".repeat(64))

  private fun record(seed: Char) = ThemeAppearanceVariants.Record("pack-$seed", digests(seed),
    "/data/themes/$seed", digests(seed + 1), digests(seed + 2), paletteFlattened = false)

  private val first = record('a')
  private val second = record('d')
  private val third = record('g')

  @Test fun keepsTheNewestRecordsWhenNoneIsApplied() {
    assertEquals(listOf(second, third), ThemeAppearanceVariants.retain(listOf(first, second), null, third))
  }

  @Test fun anAppliedOlderImportOutlivesNewerOnes() {
    // Pack one is applied; packs two and three were only imported.
    val afterSecond = ThemeAppearanceVariants.retain(listOf(first), first, second)
    assertEquals(listOf(first, second), afterSecond)
    assertEquals(listOf(first, third), ThemeAppearanceVariants.retain(afterSecond, first, third))
  }

  @Test fun reimportReplacesTheEarlierRecordOfTheSamePrimary() {
    val refreshed = first.copy(localId = "pack-a-again")
    assertEquals(listOf(second, refreshed), ThemeAppearanceVariants.retain(listOf(first, second), first, refreshed))
  }
}
