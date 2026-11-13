package os.kei.hyper.home.iconpack.perfect

import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativeLayerConfig
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram

/** Resource roles, not package names or filenames such as "bg", determine the tint operation. */
internal object PerfectDynamicNight {
  const val REVISION = "perfect-dynamic-night-7"

  data class Group(
    val files: Map<String, PerfectIconBlob>,
    val background: String,
    val foreground: Set<String>,
    val bgAssets: Set<String> = setOf(background),
    val compositeQuiet: String? = null,
  ) {
    val nightPaths: Set<String> get() = bgAssets + foreground + listOfNotNull(compositeQuiet)
  }

  fun group(
    files: Map<String, PerfectIconBlob>,
    read: (PerfectIconBlob) -> ByteArray,
    check: () -> Unit = {},
  ): Group? {
    check()
    if (files.size > 256 || files.values.sumOf { it.size } > 32L * 1024 * 1024) return null
    val config = files["config.xml"] ?: return null
    val layers = NativeLayerConfig.parse(read(config), IconPackLimits(), check)
    if (layers.layers.size !in 2..4) return null
    val backgroundDirectory = layers.layers.first()
    val backgroundQuiet = "$backgroundDirectory/quiet/quietImage.png"
    val compositeQuiet = "quiet/quietImage.png".takeIf { it in files }
    val rasters = files.keys.filter { it.endsWith(".png", true) }.toSet()
    if (rasters.size > 128) return null
    val bgAssets = linkedSetOf<String>()
    val textDirectories = mutableSetOf<String>()
    if (backgroundQuiet in files) bgAssets += backgroundQuiet
    val visualTags = setOf("Icon", "Image", "Group", "Text")
    val controlTags = setOf(
      "ExternalCommands", "Trigger", "FolmeCommand", "FolmeState", "FolmeConfig",
      "PositionAnimation", "SizeAnimation", "ScaleAnimation", "RotationAnimation",
      "AlphaAnimation", "Item", "VariableAnimation", "AniFrame", "Var", "Variables",
      "Variable", "VariableCommand", "AnimationCommand", "FrameRateCommand",
      "Function", "FunctionCommand", "IfCommand", "Consequent", "ExternCommand",
    )
    for ((path, blob) in files) {
      check()
      if (path == "config.xml" || path in rasters) continue
      val directory = path.removeSuffix("/fancy/manifest.xml")
      if (path != "$directory/fancy/manifest.xml" || directory !in layers.layers) return null
      val manifest = read(blob)
      val xml = PerfectIconFormat.xml(manifest)
      check()
      if (xml.elements.any { element ->
          element.attributes.any { (key, value) ->
            key == "useVariableUpdater" && value.split(',').any { it.trim().equals("DarkMode", true) } ||
              value.contains("__darkmode", true)
          }
        }) return null
      // Provider IO is admitted only by the shared exact source schema. This
      // does not add any binder/command tags to the generic program whitelist.
      if (directory != backgroundDirectory &&
        NativeWeatherLayerProgram.resources(manifest, "$directory/fancy/", files.keys) != null) continue
      if (xml.root != "Icon" || xml.elements.any { element ->
          element.name !in visualTags && element.name !in controlTags ||
            element.name == "ExternCommand" &&
              (element.attributes["command"] != "__datatimeVarUpdate" ||
                element.attributes["strPara"] !in setOf("'DateTime.Second'", "'DateTime.Minute'")) ||
            element.attributes.any { (key, value) ->
              key in setOf("tint", "tintExp", "colorExp", "colorFilter") ||
                key == "color" && !(element.name == "Text" &&
                  value.matches(Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))) ||
                key == "xfermodeExp" ||
                key == "layered" && value.equals("true", true) ||
                key == "useVariableUpdater" && value.split(',').any { it.trim().equals("DarkMode", true) } ||
                value.contains("__darkmode", true) ||
                key == "xfermode" && value !in setOf("src_over", "src_atop")
            }
        }) return null
      if (xml.elements.any { it.name == "Text" }) textDirectories += directory
      if (directory == backgroundDirectory) {
        // Only the semantic background slot may supply background textures. Its existing
        // single-image scene is preserved, rather than treating a foreground "bg.png" as a plate.
        if (xml.elements.map { it.name } != listOf("Icon", "Image")) return null
        val image = xml.elements.last().attributes
        if (listOf("srcExp", "srcid", "srcidExp").any { it in image }) return null
        val source = image["src"] ?: return null
        if (!source.matches(Regex("[A-Za-z0-9_./-]+"))) return null
        PerfectIconFormat.safePath(source)
        val asset = "$directory/fancy/$source"
        if (asset !in rasters) return null
        bgAssets += asset
      }
    }
    if (bgAssets.isEmpty() ||
      "$backgroundDirectory/fancy/manifest.xml" !in files &&
      (backgroundDirectory !in layers.quietOnly || backgroundQuiet !in files)) return null
    for (directory in layers.layers.drop(1)) {
      val quiet = "$directory/quiet/quietImage.png" in files
      val fancy = "$directory/fancy/manifest.xml" in files
      if (!quiet && !fancy || directory in layers.fancyOnly && !fancy ||
        directory in layers.quietOnly && !quiet) return null
      if (!quiet && directory !in textDirectories &&
        rasters.none { it.startsWith("$directory/fancy/") }) return null
    }
    val foreground = rasters.filterTo(linkedSetOf()) { path ->
      layers.layers.drop(1).any { directory ->
        path == "$directory/quiet/quietImage.png" || path.startsWith("$directory/fancy/")
      }
    }
    // The root quiet image is the entire scene and needs static composition policy,
    // never the background-only recoloring operation.
    if (rasters != bgAssets + foreground + listOfNotNull(compositeQuiet)) return null
    return Group(files, backgroundQuiet.takeIf { it in bgAssets } ?: bgAssets.first(),
      foreground, bgAssets, compositeQuiet)
  }
}
