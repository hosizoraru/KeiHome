package os.kei.hyper.home.iconpack.perfect

import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativeLayerConfig

/** Resource roles, not package names or filenames such as "bg", determine the tint operation. */
internal object PerfectDynamicNight {
  const val REVISION = "perfect-dynamic-night-3"

  data class Group(
    val files: Map<String, PerfectIconBlob>,
    val background: String,
    val foreground: Set<String>,
  )

  fun group(
    files: Map<String, PerfectIconBlob>,
    read: (PerfectIconBlob) -> ByteArray,
    check: () -> Unit = {},
  ): Group? {
    check()
    if (files.size > 256 || files.values.sumOf { it.size } > 32L * 1024 * 1024) return null
    val config = files["config.xml"] ?: return null
    val layers = NativeLayerConfig.parse(read(config), IconPackLimits(), check)
    if (layers.layers.size != 2 || layers.layers.first() !in layers.quietOnly ||
      layers.layers.last() in layers.fancyOnly) return null
    val background = "${layers.layers.first()}/quiet/quietImage.png"
    // A composite root fallback or a second background program needs separate semantic proof.
    if (background !in files || "${layers.layers.last()}/quiet/quietImage.png" !in files || files.keys.any {
        it.startsWith("quiet/") || it.startsWith("${layers.layers.first()}/fancy/")
      }) return null
    val rasters = files.keys.filter { it.endsWith(".png", true) }.toSet()
    val foreground = rasters - background
    if (foreground.isEmpty() || rasters.size > 32 ||
      foreground.any { !it.startsWith("${layers.layers.last()}/") }) return null
    val visualTags = setOf("Icon", "Image", "Group")
    val controlTags = setOf(
      "ExternalCommands", "Trigger", "FolmeCommand", "FolmeState", "FolmeConfig",
      "PositionAnimation", "SizeAnimation", "ScaleAnimation", "RotationAnimation",
      "AlphaAnimation", "Item", "VariableAnimation", "AniFrame", "Var", "Variables",
      "Variable", "VariableCommand", "AnimationCommand", "FrameRateCommand",
    )
    for ((path, blob) in files) {
      check()
      if (path == "config.xml" || path in rasters) continue
      if (!path.endsWith("/manifest.xml")) return null
      val xml = PerfectIconFormat.xml(read(blob))
      if (xml.root != "Icon" || xml.elements.any { element ->
          element.name !in visualTags && element.name !in controlTags ||
            element.attributes.any { (key, value) ->
              key in setOf("tint", "tintExp", "color", "colorExp", "colorFilter") ||
                key == "xfermodeExp" ||
                key == "layered" && value.equals("true", true) ||
                key == "useVariableUpdater" && value.split(',').any { it.trim().equals("DarkMode", true) } ||
                value.contains("__darkmode", true) ||
                key == "xfermode" && value !in setOf("src_over", "src_atop")
            }
        }) return null
    }
    return Group(files, background, foreground)
  }
}
