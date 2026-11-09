package os.kei.hyper.home.iconpack

/** Recognizes a closed, image-only day/night crossfade; this never executes source MAML. */
internal data class NativeMamlPair(val light: String, val dark: String) {
  companion object {
    private data class Element(val tag: String, val attrs: Map<String, String>, val depth: Int)

    fun parse(bytes: ByteArray, check: () -> Unit = {}): NativeMamlPair? {
      val elements = mutableListOf<Element>()
      var hasText = false
      var proofLimitExceeded = false
      BoundedPackXml.read(bytes, IconPackLimits(maxXmlBytes = 64 * 1024), check,
        start = { tag, attrs, depth ->
          // Bound retained proof state, but finish XML validation even after a non-match.
          if (elements.size >= 40) proofLimitExceeded = true
          else elements += Element(tag, attrs, depth)
        }, text = { value, _ -> if (value.isNotBlank()) hasText = true })
      if (proofLimitExceeded || hasText) return null
      val root = elements.firstOrNull() ?: return null
      if (root.tag != "Icon" || root.depth != 1) return null
      val attrs = root.attrs
      val width = attrs["width"]?.toIntOrNull() ?: return null
      // Recognize the closed, zero-ticker template emitted by NativeDayNightIcon too.
      // This is preview selection only; arbitrary source programs are never executed.
      if (width in 32..512 && attrs == linkedMapOf(
          "version" to "1", "width" to "$width", "height" to "$width",
          "screenWidth" to "1080", "resDensity" to "480", "frameRate" to "0",
          "useVariableUpdater" to "none")) {
        fun image(path: String, visibility: String) = Element("Image", linkedMapOf(
          "x" to "0", "y" to "0", "w" to "$width", "h" to "$width",
          "src" to path, "visibility" to visibility), 2)
        if (elements.drop(1) == listOf(image("day.png", "1-#__darkmode"),
            image("night.png", "#__darkmode"))) return NativeMamlPair("day.png", "night.png")
        if (elements.drop(1) == listOf(image("day.png", "1"))) return NativeMamlPair("day.png", "day.png")
      }
      if (width !in 32..512 || attrs["height"] != "$width" ||
        attrs["version"] !in setOf("1", "2") || attrs.keys.any {
          it !in setOf("version", "width", "height", "screenWidth", "resDensity", "extraResources",
            "scaleByDensity", "useVariableUpdater", "type")
        } || attrs["screenWidth"]?.toIntOrNull()?.let { it !in 320..8192 } == true ||
        attrs["resDensity"]?.toIntOrNull()?.let { it !in 120..1280 } == true ||
        attrs["scaleByDensity"] !in setOf(null, "false") ||
        attrs["useVariableUpdater"] !in setOf(null, "none", "DateTime.Day") ||
        attrs["type"] !in setOf(null, "awesome") ||
        attrs["extraResources"]?.matches(Regex("sw[0-9]{3,4}:den[0-9]{3,4}:1")) == false) return null
      val images = elements.filter { it.tag == "Image" }
      if (images.size != 2) return null
      val src = images[0].attrs["src"] ?: return null
      if (!src.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,95}\\.png")) ||
        images[1].attrs["src"] != src) return null
      fun e(tag: String, depth: Int, vararg values: Pair<String, String>) =
        Element(tag, linkedMapOf(*values), depth)
      val expected = listOf(
        e("ExternalCommands", 2),
        e("Trigger", 3, "action" to "init,resume"),
        e("AnimationCommand", 4, "target" to "framerateAni", "command" to "play"),
        e("FolmeCommand", 4, "target" to "lightIcon", "states" to "'lightIconState'", "command" to "to"),
        e("FolmeCommand", 4, "target" to "darkIcon", "states" to "'darkIconState'", "command" to "to"),
        e("FramerateController", 2, "name" to "framerateAni", "initPause" to "true", "loop" to "false"),
        e("ControlPoint", 3, "frameRate" to "10", "time" to "0"),
        e("ControlPoint", 3, "frameRate" to "10", "time" to "100"),
        e("ControlPoint", 3, "frameRate" to "0", "time" to "200"),
        e("FramerateController", 2, "name" to "folmeFramerateAni", "initPause" to "true", "loop" to "false"),
        e("ControlPoint", 3, "frameRate" to "60", "time" to "0"),
        e("ControlPoint", 3, "frameRate" to "60", "time" to "500"),
        e("ControlPoint", 3, "frameRate" to "0", "time" to "600"),
        e("FolmeState", 2, "name" to "lightIconState", "alpha" to "255-#__darkmode*255"),
        e("FolmeState", 2, "name" to "darkIconState", "alpha" to "#__darkmode*255"),
        e("FolmeConfig", 2, "name" to "config", "property" to "'alpha'", "ease" to "6,500", "fromSpeed" to "0"),
        e("Var", 2, "name" to "threshold", "expression" to "#__darkmode", "threshold" to "1", "type" to "number"),
        e("Trigger", 3),
        e("AnimationCommand", 4, "target" to "folmeFramerateAni", "command" to "play"),
        e("FolmeCommand", 4, "target" to "lightIcon", "states" to "'lightIconState'", "command" to "to",
          "config" to "'config'"),
        e("FolmeCommand", 4, "target" to "darkIcon", "states" to "'darkIconState'", "command" to "to",
          "config" to "'config'"),
        e("Image", 2, "name" to "lightIcon", "x" to "0", "y" to "0", "w" to "$width", "h" to "$width",
          "src" to src, "srcid" to "0", "alpha" to "255", "folmeMode" to "true"),
        e("Image", 2, "name" to "darkIcon", "x" to "0", "y" to "0", "w" to "$width", "h" to "$width",
          "src" to src, "srcid" to "1", "alpha" to "255", "folmeMode" to "true"),
      )
      if (elements.drop(1) != expected) return null
      val stem = src.removeSuffix(".png")
      return NativeMamlPair("${stem}_0.png", "${stem}_1.png")
    }
  }
}
