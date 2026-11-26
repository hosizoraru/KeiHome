package os.kei.hyper.home.iconpack.perfect

import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativeLayerConfig
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram

/** Resource roles, not package names or filenames such as "bg", determine the tint operation. */
internal object PerfectDynamicNight {
  const val REVISION = "perfect-dynamic-night-11"

  data class Group(
    val files: Map<String, PerfectIconBlob>,
    val background: String,
    val foreground: Set<String>,
    val bgAssets: Set<String> = setOf(background),
    val compositeQuiet: String? = null,
    val compositeQuietLayers: List<String> = emptyList(),
    val compositeQuietProofLayers: List<String> = emptyList(),
    val alphaMasks: Set<String> = emptySet(),
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
    val availableLayers = NativeLayerConfig.availableLayerDirectories("", files.keys)
    val layers = NativeLayerConfig.parse(read(config), IconPackLimits(), check, availableLayers)
    if (layers.layers.size !in 2..4) return null
    val backgroundDirectory = layers.layers.first()
    val backgroundQuiet = "$backgroundDirectory/quiet/quietImage.png"
    val compositeQuiet = "quiet/quietImage.png".takeIf { it in files }
    val rasters = files.keys.filter { it.endsWith(".png", true) }.toSet()
    if (rasters.size > 128) return null
    val bgAssets = linkedSetOf<String>()
    val alphaMasks = linkedSetOf<String>()
    val textDirectories = mutableSetOf<String>()
    var weatherDirectory: String? = null
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
        NativeWeatherLayerProgram.resources(manifest, "$directory/fancy/", files.keys) != null) {
        weatherDirectory = directory
        continue
      }
      if (xml.root != "Icon") return null
      val masks = alphaMaskSources(xml.elements, directory != backgroundDirectory) ?: return null
      val maskPaths = masks.map { "$directory/fancy/$it" }
      if (!rasters.containsAll(maskPaths)) return null
      alphaMasks += maskPaths
      if (xml.elements.any { element ->
          element.name !in visualTags && element.name !in controlTags ||
            element.name == "ExternCommand" &&
              (element.attributes["command"] != "__datatimeVarUpdate" ||
                element.attributes["strPara"] !in setOf("'DateTime.Second'", "'DateTime.Minute'")) ||
            element.attributes.any { (key, value) ->
              key in setOf("tint", "tintExp", "colorExp", "colorFilter") ||
                key == "color" && !(element.name == "Text" &&
                  value.matches(Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))) ||
                key in setOf("xfermodeExp", "layeredExp") ||
                key == "useVariableUpdater" && value.split(',').any { it.trim().equals("DarkMode", true) } ||
                value.contains("__darkmode", true) ||
                key == "xfermode" && value !in setOf("src_over", "src_atop", "dst_out")
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
      path !in alphaMasks && layers.layers.drop(1).any { directory ->
        path == "$directory/quiet/quietImage.png" || path.startsWith("$directory/fancy/")
      }
    }
    // The root quiet image is the entire scene and needs static composition policy,
    // never the background-only recoloring operation.
    if (rasters != bgAssets + foreground + alphaMasks + listOfNotNull(compositeQuiet)) return null
    // This exact program places its artwork on the authored 351px canvas. Its
    // flat preview has baked sky/cloud gradients that cannot be segmented safely.
    val quietLayers = layers.layers.map { "$it/quiet/quietImage.png" }
    val compositeQuietLayers = if (layers.layers.size == 2 &&
      weatherDirectory == layers.layers.last() && compositeQuiet != null &&
      backgroundDirectory in layers.quietOnly && weatherDirectory in layers.fancyOnly &&
      "$backgroundDirectory/fancy/manifest.xml" in files &&
      layers.layers.all { directory ->
        files["$directory/quiet/quietImage.png"]?.sha?.let {
          it == files["$directory/fancy/bg.png"]?.sha
        } == true
      } &&
      PerfectIconFormat.xml(read(files.getValue("$backgroundDirectory/fancy/manifest.xml")))
        .elements.let { elements ->
          elements.size == 2 && elements[0].attributes["width"] == "351" &&
            elements[0].attributes["height"] == "351" && elements[1].name == "Image" &&
            elements[1].attributes == mapOf("x" to "351 / 2", "y" to "351 / 2",
              "align" to "center", "alignV" to "center", "src" to "bg.png")
        } &&
      (quietLayers + compositeQuiet).all { path ->
        check()
        val bytes = read(files.getValue(path))
        try {
          PerfectIconFormat.png(bytes, square = true)
          java.nio.ByteBuffer.wrap(bytes, 16, 8).let { it.int == 351 && it.int == 351 }
        } catch (_: IllegalArgumentException) { false }
      }) quietLayers else emptyList()
    // A modified Weather canvas has no proven composition. Retain its complete
    // authored preview rather than run the classifier known to fragment it.
    val safeCompositeQuiet = compositeQuiet.takeUnless {
      weatherDirectory != null && compositeQuietLayers.isEmpty()
    }
    val proofLayers = quietLayers.take(2).takeIf {
      safeCompositeQuiet != null && compositeQuietLayers.isEmpty() &&
        it.size == 2 && it.all(files::containsKey)
    }.orEmpty()
    return Group(files, backgroundQuiet.takeIf { it in bgAssets } ?: bgAssets.first(),
      foreground, bgAssets, safeCompositeQuiet, compositeQuietLayers, proofLayers, alphaMasks)
  }

  /**
   * Keep the original alpha texture for an isolated Porter-Duff cutout. A group
   * must draw one literal image, then erase from that image on a bounded canvas.
   */
  private fun alphaMaskSources(elements: List<PerfectIconFormat.XmlElement>, foreground: Boolean): Set<String>? {
    val cutouts = elements.filter { it.attributes["xfermode"] == "dst_out" }
    val groups = elements.withIndex().filter { (_, element) ->
      element.attributes["layered"]?.let { it != "false" } == true
    }
    if (groups.isEmpty() && cutouts.isEmpty()) return emptySet()
    if (!foreground || groups.size != 1 || cutouts.size != 1) return null
    val (index, group) = groups.single()
    if (group.name != "Group" || group.attributes.keys != setOf("layered", "w", "h") ||
      group.attributes["layered"] != "true") return null
    val width = group.attributes["w"]?.toIntOrNull() ?: return null
    val height = group.attributes["h"]?.toIntOrNull() ?: return null
    if (width !in 1..2048 || height !in 1..2048) return null
    val children = elements.drop(index + 1).takeWhile { it.depth > group.depth }
    if (children.size != 2 || children.any { it.depth != group.depth + 1 || it.name != "Image" }) return null
    val (paint, mask) = children
    if (mask !== cutouts.single() || paint.attributes["xfermode"] !in setOf(null, "src_over") ||
      children.any { it.attributes.keys.any { key -> key in setOf("srcExp", "srcid", "srcidExp") } }) return null
    val maskSource = mask.attributes["src"] ?: return null
    if ("src" !in paint.attributes ||
      elements.any { it !== mask && it.attributes["src"] == maskSource }) return null
    return setOf(maskSource)
  }
}
