package os.kei.hyper.home.data

import com.materialkolor.dynamiccolor.ColorSpec.SpecVersion.SPEC_2021
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.DynamicScheme.Platform.PHONE
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.score.Score

internal enum class MonetSchemeStyle { TONAL_SPOT, VIBRANT, NEUTRAL, MONOCHROME }

/** Shared, Android-free color calculations for wallpaper previews and the injected runtime. */
internal object MonetSchemes {
  /**
   * Opaque ARGB tones 0..100 in the existing palette-key format, e.g. primary90.
   * SPEC_2021 palettes are independent of light/dark mode; pin it for repeatable selection.
   * MonetColorPolicy selects light primary90/30 and dark secondary20/primary80 from this map.
   */
  fun generate(seed: Int, style: MonetSchemeStyle): Map<String, Int> {
    val hct = Hct.fromInt(seed)
    val scheme = when (style) {
      MonetSchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, false, 0.0, SPEC_2021, PHONE)
      MonetSchemeStyle.VIBRANT -> SchemeVibrant(hct, false, 0.0, SPEC_2021, PHONE)
      MonetSchemeStyle.NEUTRAL -> SchemeNeutral(hct, false, 0.0, SPEC_2021, PHONE)
      MonetSchemeStyle.MONOCHROME -> SchemeMonochrome(hct, false, 0.0, SPEC_2021, PHONE)
    }
    return buildMap {
      for ((name, palette) in listOf(
        "primary" to scheme.primaryPalette,
        "secondary" to scheme.secondaryPalette,
        "tertiary" to scheme.tertiaryPalette,
        "neutral" to scheme.neutralPalette,
        "neutral-variant" to scheme.neutralVariantPalette,
      )) {
        for (tone in 0..100) put("$name$tone", palette.tone(tone))
      }
    }
  }

  /**
   * Takes thumbnail pixels; ignores non-opaque pixels and returns up to four ranked seeds.
   * If MCU filters every candidate (e.g. a gray wallpaper), use its most populous color.
   * An empty/transparent thumbnail has no seed. No image or mutable MCU palette is retained.
   */
  fun wallpaperSeeds(pixels: IntArray): List<Int> {
    val opaque = pixels.filter { it ushr 24 == 0xff }.toIntArray()
    if (opaque.isEmpty()) return emptyList()
    val populations = QuantizerCelebi.quantize(opaque, maxColors = 128)
    val dominant = populations.maxBy { it.value }.key
    return Score.score(populations, desired = 4, fallbackColorArgb = dominant)
  }
}
