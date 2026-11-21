package os.kei.hyper.home.iconpack

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Generates local MAML resources from a pair already rendered by [IconPackExporter].
 *
 * The name is an exact native filename stem, without ".png". Activity/package alias
 * decisions belong to the caller. Write each returned path once into the icons ZIP;
 * the normal fallback entry is included and its artwork is preserved byte for byte.
 * This checks PNG headers and dimensions; it is not an arbitrary-image decoder.
 */
internal object NativeDayNightIcon {
  /** Ordinary artwork needs a live selector only if its two appearances differ. */
  fun artworkEntries(
    nativeName: String, lightPng: ByteArray, darkPng: ByteArray,
    requiresNativeDescriptor: Boolean = false,
  ): Map<String, ByteArray> {
    if (needsSelector(lightPng, darkPng, requiresNativeDescriptor)) {
      return entries(nativeName, lightPng, darkPng)
    }
    validateName(nativeName)
    pngSize(lightPng)
    val image = lightPng.copyOf()
    return mapOf(
      "res/drawable-xxhdpi/$nativeName.png" to image,
      "nightmode/res/drawable-xxhdpi/$nativeName.png" to image,
    )
  }

  fun needsSelector(lightPng: ByteArray, darkPng: ByteArray, requiresNativeDescriptor: Boolean): Boolean =
    requiresNativeDescriptor || !lightPng.contentEquals(darkPng)

  fun entries(
    nativeName: String, lightPng: ByteArray, darkPng: ByteArray,
    legacyExpanded: Boolean = false,
  ): Map<String, ByteArray> {
    validateName(nativeName)
    val size = pngSize(lightPng)
    require(pngSize(darkPng) == size) { "Day/night PNG dimensions must match" }
    val root = "fancy_icons/$nativeName"
    val identical = lightPng.contentEquals(darkPng)
    val compact = !legacyExpanded && identical
    val light = lightPng.copyOf()
    val dark = if (identical) light else darkPng.copyOf()
    val result = linkedMapOf(
      "res/drawable-xxhdpi/$nativeName.png" to light,
      "nightmode/res/drawable-xxhdpi/$nativeName.png" to dark,
      "$root/manifest.xml" to manifest(size, compact),
      "$root/day.png" to light,
    )
    if (!compact) result["$root/night.png"] = dark
    return result
  }

  internal fun manifest(size: Int, compact: Boolean): ByteArray {
    val dayVisibility = if (compact) "1" else "1-#__darkmode"
    val nightImage = if (compact) "" else
      "\n        <Image x=\"0\" y=\"0\" w=\"$size\" h=\"$size\" src=\"night.png\" visibility=\"#__darkmode\" />"
    // The native root always installs DarkModeVariableUpdater, even with "none".
    // Its notification requests a redraw, so these images need no frame-rate loop.
    val manifest = """
      <?xml version="1.0" encoding="utf-8"?>
      <Icon version="1" width="$size" height="$size" screenWidth="1080" resDensity="480" frameRate="0" useVariableUpdater="none">
        <Image x="0" y="0" w="$size" h="$size" src="day.png" visibility="$dayVisibility" />$nightImage
      </Icon>
    """.trimIndent() + "\n"
    return manifest.toByteArray(Charsets.UTF_8)
  }

  internal fun validateName(nativeName: String) {
    require(validNativeIconName(nativeName) && nativeName.toByteArray(Charsets.UTF_8).size <= 240) {
      "Invalid native icon filename stem"
    }
  }

  internal fun pngSize(png: ByteArray): Int {
    require(png.size in 33..(4 * 1024 * 1024)) { "Invalid rendered PNG size" }
    val header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
    require(header.int == 0x89504e47.toInt() && header.int == 0x0d0a1a0a &&
      header.int == 13 && header.int == 0x49484452) { "Expected a PNG with an IHDR header" }
    val width = header.int
    val height = header.int
    require(width in 1..512 && height == width) { "Expected a square PNG no larger than 512 pixels" }
    return width
  }
}
