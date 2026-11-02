package os.kei.hyper.home.iconpack

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Two fixed, local MAML templates; source manifests and font files are never inputs.
 *
 * [entries] preserves raster bytes and uses the target's date/time updater for live
 * fields. The caller owns aliases, source provenance, previews, and archive limits.
 * Optional fallback PNGs are snapshots only and are never read by the live template.
 */
internal object NativeColorOsDynamicIcon {
  enum class Kind { CALENDAR, CLOCK }
  private val manifests by lazy {
    Kind.entries.flatMap { kind -> listOf<Boolean?>(null, false, true).flatMap { fixed ->
      listOf(false, true).map { dim -> manifest(kind, fixed, dim) to kind }
    } }.toMap()
  }

  /** Classification only, not approval or an interpreter for foreign MAML. */
  fun kindOfManifest(bytes: ByteArray): Kind? = manifests[bytes.toString(Charsets.UTF_8)]

  /**
   * [fixedDark] selects artwork independently of the live date/time updater.
   * [dim] multiplies RGB by 214/255 only while the system is dark. For the
   * DIM_AT_NIGHT policy, pass fixedDark=false to retain day artwork at night.
   */
  fun entries(
    nativeName: String,
    kind: Kind,
    assets: Map<String, ByteArray>,
    fixedDark: Boolean? = null,
    dim: Boolean = false,
  ): Map<String, ByteArray> {
    require(validDottedName(nativeName) && nativeName.toByteArray(Charsets.UTF_8).size <= 240) {
      "Invalid native icon filename stem"
    }
    val required = backgroundNames + if (kind == Kind.CLOCK) clockNames else emptyList()
    val hasFallback = fallbackNames.any { it in assets }
    val expected = required.toSet() + if (hasFallback) fallbackNames else emptyList()
    require(assets.keys == expected) { "Expected exactly these dynamic PNG assets: $expected" }
    val sizes = assets.mapValues { (_, png) -> pngSize(png) }
    require(sizes.getValue("day.png") == sizes.getValue("night.png")) {
      "Day/night background PNG dimensions must match"
    }
    if (hasFallback) {
      require(sizes.getValue("fallback.png") == sizes.getValue("fallback-night.png")) {
        "Day/night fallback PNG dimensions must match"
      }
    }
    if (kind == Kind.CLOCK) {
      require(clockNames.map { sizes.getValue(it) }.distinct().size == 1) {
        "Clock face and full-canvas hand PNG dimensions must match"
      }
    }

    val root = "fancy_icons/$nativeName"
    val manifest = manifest(kind, fixedDark, dim)
    val dayFallback = assets.getValue(if (hasFallback) "fallback.png" else "day.png")
    val nightFallback = assets.getValue(if (hasFallback) "fallback-night.png" else "night.png")
    val result = linkedMapOf(
      "res/drawable-xxhdpi/$nativeName.png" to dayFallback.copyOf(),
      "nightmode/res/drawable-xxhdpi/$nativeName.png" to nightFallback.copyOf(),
      "$root/manifest.xml" to manifest.toByteArray(Charsets.UTF_8),
    )
    for (name in required) result["$root/$name"] = assets.getValue(name).copyOf()
    return result
  }

  private fun manifest(kind: Kind, fixedDark: Boolean?, dim: Boolean): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    append("<Icon version=\"1\" width=\"150\" height=\"150\" screenWidth=\"1080\" ")
    append("resDensity=\"480\" frameRate=\"0\" useVariableUpdater=\"DateTime.Minute\">\n")
    // Minute notifications request a redraw even at zero frame rate. Both mode
    // branches keep live expressions; forcing appearance never freezes the clock.
    for (systemDark in listOf(false, true)) {
      val artworkDark = fixedDark ?: systemDark
      val visibility = if (fixedDark == null || dim) {
        if (systemDark) "#__darkmode" else "1-#__darkmode"
      } else {
        if (systemDark == fixedDark) "1" else "0"
      }
      val dimmed = dim && systemDark
      val tint = if (dimmed) " tint=\"#FFD6D6D6\" tintmode=\"13\"" else ""
      val background = if (artworkDark) "night.png" else "day.png"
      append("  <Image x=\"0\" y=\"0\" w=\"150\" h=\"150\" src=\"$background\"")
      append(" visibility=\"$visibility\"$tint />\n")
      when (kind) {
        Kind.CALENDAR -> {
          val weekdayColor = calendarColor(0x0a7ffb, dimmed)
          val dateColor = calendarColor(if (artworkDark) 0xffffff else 0x000000, dimmed)
          append("  <DateTime x=\"75\" y=\"20\" align=\"center\" size=\"26\" bold=\"true\"")
          append(" color=\"$weekdayColor\" format=\"EEEE\" visibility=\"$visibility\" />\n")
          append("  <Text x=\"75\" y=\"43\" align=\"center\" size=\"76\" bold=\"true\"")
          append(" color=\"$dateColor\" textExp=\"#date\" visibility=\"$visibility\" />\n")
        }
        Kind.CLOCK -> {
          val prefix = if (artworkDark) "night_" else ""
          append("  <Image x=\"0\" y=\"0\" w=\"150\" h=\"150\" src=\"${prefix}clock_day_bg.png\"")
          append(" visibility=\"$visibility\"$tint />\n")
          for ((hand, angle) in listOf(
            "clock_day_hour_rectangle.png" to "#hour12*30+#minute/2",
            "clock_day_minute_rectangle.png" to "#minute*6",
          )) {
            append("  <Image x=\"0\" y=\"0\" w=\"150\" h=\"150\" src=\"$prefix$hand\"")
            append(" angle=\"$angle\" centerX=\"75\" centerY=\"75\"")
            append(" visibility=\"$visibility\"$tint />\n")
          }
        }
      }
    }
    append("</Icon>\n")
  }

  private fun calendarColor(rgb: Int, dimmed: Boolean): String = "#FF" +
    listOf(16, 8, 0).joinToString("") { shift ->
      val channel = (rgb ushr shift) and 0xff
      val adjusted = if (dimmed) (channel * 214 + 127) / 255 else channel
      adjusted.toString(16).padStart(2, '0').uppercase()
    }

  private fun pngSize(png: ByteArray): Int {
    require(png.size in 33..(4 * 1024 * 1024)) { "Invalid dynamic PNG size" }
    val header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
    require(header.int == 0x89504e47.toInt() && header.int == 0x0d0a1a0a &&
      header.int == 13 && header.int == 0x49484452) { "Expected a PNG with an IHDR header" }
    val width = header.int
    val height = header.int
    require(width in 1..512 && height == width) { "Expected a square PNG no larger than 512 pixels" }
    return width
  }

  private val backgroundNames = listOf("day.png", "night.png")
  private val fallbackNames = listOf("fallback.png", "fallback-night.png")
  private val clockNames = listOf(
    "clock_day_bg.png", "clock_day_hour_rectangle.png", "clock_day_minute_rectangle.png",
    "night_clock_day_bg.png", "night_clock_day_hour_rectangle.png", "night_clock_day_minute_rectangle.png",
  )
}
