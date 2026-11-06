package os.kei.hyper.home.data

import com.materialkolor.contrast.Contrast
import com.materialkolor.dynamiccolor.ColorSpec.SpecVersion.SPEC_2021
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.dynamiccolor.DynamicScheme.Platform.PHONE
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.score.Score
import com.materialkolor.utils.ColorUtils

internal enum class MonetSchemeStyle {
  TONAL_SPOT, VIBRANT, NEUTRAL, MONOCHROME, EXPRESSIVE, RAINBOW, FRUIT_SALAD, COLOROS_INSPIRATION,
}

internal data class MonetIconRoles(
  val foreground: Int,
  val background: Int,
  val contrastRatio: Double,
)

/** Shared, Android-free color calculations for wallpaper previews and the injected runtime. */
internal object MonetSchemes {
  /** Framework palettes expose a sparse set of tones. Preview surfaces use a reported neighbor. */
  fun nearestTone(palette: Map<String, Int>, family: String, tone: Int): Int? =
    palette["$family$tone"] ?: palette.entries.mapNotNull { (key, color) ->
      key.takeIf { it.startsWith(family) }?.removePrefix(family)?.toIntOrNull()
        ?.takeIf { it in 0..100 }?.let { it to color }
    }.minByOrNull { kotlin.math.abs(it.first - tone) }?.second

  /**
   * Opaque ARGB tones 0..100 in the existing palette-key format, e.g. primary90.
   * SPEC_2021 palettes are independent of light/dark mode; pin it for repeatable selection.
   * MonetColorPolicy selects light primary90/30 and dark secondary20/primary80 from this map.
   */
  private fun palettes(seed: Int, style: MonetSchemeStyle): List<TonalPalette> {
    val hct = Hct.fromInt(seed)
    val scheme = when (style) {
      MonetSchemeStyle.TONAL_SPOT, MonetSchemeStyle.COLOROS_INSPIRATION -> SchemeTonalSpot(hct, false, 0.0, SPEC_2021, PHONE)
      MonetSchemeStyle.VIBRANT -> SchemeVibrant(hct, false, 0.0, SPEC_2021, PHONE)
      MonetSchemeStyle.NEUTRAL -> SchemeNeutral(hct, false, 0.0, SPEC_2021, PHONE)
      MonetSchemeStyle.MONOCHROME -> SchemeMonochrome(hct, false, 0.0, SPEC_2021, PHONE)
      MonetSchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, false, 0.0, SPEC_2021, PHONE)
      MonetSchemeStyle.RAINBOW -> SchemeRainbow(hct, false, 0.0, SPEC_2021, PHONE)
      MonetSchemeStyle.FRUIT_SALAD -> SchemeFruitSalad(hct, false, 0.0, SPEC_2021, PHONE)
    }
    return listOf(scheme.primaryPalette, scheme.secondaryPalette, scheme.tertiaryPalette,
      scheme.neutralPalette, scheme.neutralVariantPalette)
  }

  fun familySpecs(seed: Int, style: MonetSchemeStyle): Map<MonetPaletteFamily, MonetTonalSpec> =
    MonetPaletteFamily.entries.zip(palettes(seed, style)).associate { (family, palette) ->
      family to MonetTonalSpec(palette.hue, palette.chroma)
    }

  fun generate(
    seed: Int, style: MonetSchemeStyle,
    overrides: Map<MonetPaletteFamily, MonetTonalSpec> = emptyMap(),
  ): Map<String, Int> = buildMap {
    for ((family, original) in MonetPaletteFamily.entries.zip(palettes(seed, style))) {
      val palette = overrides[family]?.let { TonalPalette.fromHueAndChroma(it.hue, it.chroma) } ?: original
      for (tone in 0..100) put("${family.key}$tone", palette.tone(tone))
    }
  }

  /**
   * Preview the AOSP icon roles used by MonetColorPolicy, without changing its acceptance policy.
   * This measures the actual opaque sRGB pair, not nominal HCT tones or rendered icon pixels.
   * Missing or translucent colors cannot provide this readout without a compositing background.
   */
  fun iconRoles(
    palette: Map<String, Int>, dark: Boolean, choice: MonetCustomization? = null,
  ): MonetIconRoles? {
    val sourceDark = choice?.iconAppearance?.sourceDarkMode(dark) ?: dark
    fun color(role: MonetIconRole) = role.color(choice) { family, tone -> palette["$family$tone"] }
      ?.let { choice?.iconAppearance?.tintColor(it, dark) ?: it }
    val foreground = color(if (sourceDark) MonetIconRole.DARK_FOREGROUND else MonetIconRole.LIGHT_FOREGROUND)
      ?: return null
    val background = color(if (sourceDark) MonetIconRole.DARK_BACKGROUND else MonetIconRole.LIGHT_BACKGROUND)
      ?: return null
    val ratio = opaqueContrastRatio(foreground, background) ?: return null
    return MonetIconRoles(foreground, background, ratio)
  }

  fun opaqueContrastRatio(first: Int, second: Int): Double? {
    if (first ushr 24 != 0xff || second ushr 24 != 0xff) return null
    return Contrast.ratioOfYs(ColorUtils.xyzFromArgb(first)[1], ColorUtils.xyzFromArgb(second)[1])
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
