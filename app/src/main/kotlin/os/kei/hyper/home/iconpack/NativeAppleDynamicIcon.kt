package os.kei.hyper.home.iconpack

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed local templates over caller-supplied Apple backgrounds and full-canvas hands.
 * PNG headers and bounds are checked here; decoding, source provenance, transparent
 * hand alignment, and static fallback snapshots belong to the asset renderer.
 * Runtime layers share one square size, at most 512px. Paired static snapshots
 * are independently bounded to 1024px and need not share the runtime dimensions.
 */
internal object NativeAppleDynamicIcon {
  enum class Kind { CALENDAR, CLOCK }

  private val backgroundNames = listOf("day.png", "night.png")
  private val fallbackNames = listOf("fallback.png", "fallback-night.png")
  private val clockNames = listOf(
    "hour.png", "minute.png", "second.png",
    "night_hour.png", "night_minute.png", "night_second.png",
  )
  private val manifests by lazy {
    Kind.entries.flatMap { kind ->
      listOf<Boolean?>(null, false, true).flatMap { fixed ->
        listOf(false, true).map { dim -> manifest(kind, fixed, dim) to kind }
      }
    }.toMap()
  }

  /** Exact classification of our finite templates, never an interpreter for supplied MAML. */
  fun kindOfManifest(bytes: ByteArray): Kind? =
    if (bytes.size > 8192) null else manifests[bytes.toString(Charsets.UTF_8)]

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
    val sizes = assets.mapValues { (leaf, png) ->
      pngSize(png, if (leaf in required) 512 else 1024)
    }
    require(required.map { sizes.getValue(it) }.distinct().size == 1) {
      "Apple backgrounds and full-canvas hands must have matching dimensions"
    }

    val root = "fancy_icons/$nativeName"
    val result = linkedMapOf(
      "res/drawable-xxhdpi/$nativeName.png" to
        assets.getValue(if (hasFallback) "fallback.png" else "day.png").copyOf(),
      "nightmode/res/drawable-xxhdpi/$nativeName.png" to
        assets.getValue(if (hasFallback) "fallback-night.png" else "night.png").copyOf(),
      "$root/manifest.xml" to manifest(kind, fixedDark, dim).toByteArray(Charsets.UTF_8),
    )
    for (leaf in required) result["$root/$leaf"] = assets.getValue(leaf).copyOf()
    return result
  }

  private fun manifest(kind: Kind, fixedDark: Boolean?, dim: Boolean): String = buildString {
    val updater = if (kind == Kind.CLOCK) "Second" else "Minute"
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    append("<Icon version=\"1\" width=\"150\" height=\"150\" screenWidth=\"1080\" ")
    append("resDensity=\"480\" frameRate=\"0\" useVariableUpdater=\"DateTime.$updater\">\n")
    for (systemDark in listOf(false, true)) {
      val artworkDark = fixedDark ?: systemDark
      val visibility = if (fixedDark == null || dim) {
        if (systemDark) "#__darkmode" else "1-#__darkmode"
      } else if (systemDark == fixedDark) "1" else "0"
      val dimmed = dim && systemDark
      val tint = if (dimmed) " tint=\"#FFD6D6D6\" tintmode=\"13\"" else ""
      val background = if (artworkDark) "night.png" else "day.png"
      append("  <Image x=\"0\" y=\"0\" w=\"150\" h=\"150\" src=\"$background\"")
      append(" visibility=\"$visibility\"$tint />\n")
      when (kind) {
        Kind.CALENDAR -> {
          val weekdayColor = calendarColor(0xff3b30, dimmed)
          val dateColor = calendarColor(if (artworkDark) 0xffffff else 0x000000, dimmed)
          // Both fields use the native formatter's locale and default time zone.
          // The smaller weekday leaves room for full English and CJK names.
          append("  <DateTime x=\"75\" y=\"20\" align=\"center\" size=\"20\" bold=\"false\"")
          append(" fontFamily=\"sans-serif-medium\"")
          append(" color=\"$weekdayColor\" format=\"EEEE\" visibility=\"$visibility\" />\n")
          append("  <DateTime x=\"75\" y=\"43\" align=\"center\" size=\"76\" bold=\"false\"")
          append(" fontFamily=\"sans-serif-light\"")
          append(" color=\"$dateColor\" format=\"d\" visibility=\"$visibility\" />\n")
        }
        Kind.CLOCK -> {
          val prefix = if (artworkDark) "night_" else ""
          for ((hand, angle) in listOf(
            "hour.png" to "#hour12*30+#minute/2+#second/120",
            "minute.png" to "#minute*6+#second/10",
            "second.png" to "#second*6",
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

  private fun pngSize(png: ByteArray, maxDimension: Int): Int {
    require(png.size in 33..(8 * 1024 * 1024)) { "Invalid dynamic PNG size" }
    val header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
    require(header.int == 0x89504e47.toInt() && header.int == 0x0d0a1a0a &&
      header.int == 13 && header.int == 0x49484452) { "Expected a PNG with an IHDR header" }
    val width = header.int
    val height = header.int
    require(width in 1..maxDimension && height == width) {
      "Expected a square PNG no larger than $maxDimension pixels"
    }
    return width
  }
}
