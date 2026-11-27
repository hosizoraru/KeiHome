package os.kei.hyper.home.data

import com.materialkolor.hct.Hct

internal enum class MonetPaletteFamily(val key: String) {
  PRIMARY("primary"), SECONDARY("secondary"), TERTIARY("tertiary"),
  NEUTRAL("neutral"), NEUTRAL_VARIANT("neutral-variant"),
}

/** A tonal palette keeps hue/chroma fixed; its roles choose the tone for each light/dark surface. */
internal data class MonetTonalSpec(val hue: Double, val chroma: Double) {
  init {
    require(hue.isFinite() && hue in 0.0..360.0)
    require(chroma.isFinite() && chroma in 0.0..200.0)
  }

  fun sample(tone: Double = 50.0): Int = Hct.from(hue, chroma, tone).toInt()

  companion object {
    fun fromColor(color: Int): MonetTonalSpec = Hct.fromInt(color).let { MonetTonalSpec(it.hue, it.chroma) }
  }
}

internal enum class MonetIconRole(val palette: MonetPaletteFamily, val tone: Int, val background: Boolean) {
  LIGHT_FOREGROUND(MonetPaletteFamily.PRIMARY, 30, false),
  LIGHT_BACKGROUND(MonetPaletteFamily.PRIMARY, 90, true),
  DARK_FOREGROUND(MonetPaletteFamily.PRIMARY, 80, false),
  DARK_BACKGROUND(MonetPaletteFamily.SECONDARY, 20, true);

  fun family(choice: MonetCustomization?): MonetPaletteFamily =
    if (background) choice?.backgroundFamily ?: palette else palette

  fun effectiveFamily(choice: MonetCustomization?): MonetPaletteFamily =
    if (choice?.style == MonetSchemeStyle.COLOROS_INSPIRATION &&
      this == DARK_BACKGROUND && choice.backgroundFamily == null
    ) MonetPaletteFamily.PRIMARY
    else family(choice)

  fun effectiveTone(choice: MonetCustomization?): Int =
    if (choice?.style == MonetSchemeStyle.COLOROS_INSPIRATION) when (this) {
      LIGHT_FOREGROUND -> 40
      LIGHT_BACKGROUND -> 95
      DARK_FOREGROUND -> 80
      DARK_BACKGROUND -> 20
    } else tone

  fun color(choice: MonetCustomization?, sample: (String, Int) -> Int?): Int? {
    choice?.iconOverrides?.get(this)?.let { return it }
    return sample(effectiveFamily(choice).key, effectiveTone(choice))
  }
}
