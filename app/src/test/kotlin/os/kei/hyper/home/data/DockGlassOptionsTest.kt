package os.kei.hyper.home.data

import org.junit.Assert.*
import org.junit.Test

class DockGlassOptionsTest {
  @Test fun defaultsKeepTheLegacyWordsAndUnusedRadiusDoesNotChangeIdentity() {
    assertEquals(0, DockGlassChoice(cornerStyle = DockGlassCornerStyle.ROUNDED).options)
    assertEquals(1, DockGlassChoice().options)
    for (style in DockGlassCornerStyle.entries.filter { it != DockGlassCornerStyle.CUSTOM }) {
      assertEquals(DockGlassChoice(cornerStyle = style).options,
        DockGlassChoice(cornerStyle = style, customRadiusDp = 64).options)
    }
    assertEquals(DockGlassChoice(), DockGlassOptions.decode(1))
    assertEquals(DockGlassChoice(cornerStyle = DockGlassCornerStyle.ROUNDED), DockGlassOptions.decode(0))
  }

  @Test fun everyStyleAndEveryValidPaddingOffsetAndCustomRadiusRoundTrips() {
    // Sweep each entire bounded field, including signed byte boundaries, with distinct companions.
    for (style in DockGlassCornerStyle.entries) {
      val choices = (0..32).map { DockGlassChoice(false, style, it, -48, 64) } +
        (-48..96).map { DockGlassChoice(false, style, 32, it, 0) } +
        (0..64).map { DockGlassChoice(false, style, 0, 96, it) }
      for (choice in choices) {
        val decoded = DockGlassOptions.decode(choice.options)!!
        assertEquals(choice.copy(customRadiusDp = if (style == DockGlassCornerStyle.CUSTOM) choice.customRadiusDp else 24), decoded)
        assertEquals(choice.options, decoded.options)
        assertEquals(style, DockGlassCornerStyle.from(choice.options))
      }
    }
    val custom = DockGlassChoice(false, DockGlassCornerStyle.CUSTOM, 32, -48, 64)
    assertEquals(4 or (33 shl 3) or (64 shl 9) or (208 shl 16), custom.options)
    assertEquals(18, DockGlassOptions.decode(1 or (19 shl 3))!!.verticalPaddingDp)
  }

  @Test fun rejectsEveryReservedFieldValueAndHighBits() {
    val invalid = (5..7).toList() + (34..63).map { 1 or (it shl 3) } +
      (65..127).map { 4 or (it shl 9) } +
      (0..3).flatMap { style -> (1..64).map { style or (it shl 9) } } +
      ((-128..-49) + (97..127)).map { 1 or ((it and 255) shl 16) } +
      (24..31).map { 1 or (1 shl it) } + listOf(-1)
    for (options in invalid) {
      assertFalse("accepted $options", DockGlassOptions.isValid(options))
      assertNull(DockGlassOptions.decode(options))
    }
  }
}
