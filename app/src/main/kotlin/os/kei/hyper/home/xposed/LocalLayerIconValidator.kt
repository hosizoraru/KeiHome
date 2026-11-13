package os.kei.hyper.home.xposed

import os.kei.hyper.home.iconpack.BoundedPackXml
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativeLayerConfig
import os.kei.hyper.home.iconpack.validNativeIconName

/** Narrow admission for native layer modules. Does not extend the legacy fancy-icon policy. */
internal object LocalLayerIconValidator {
  private val limits = IconPackLimits(maxXmlBytes = 64 * 1024, maxMappings = 1024)
  private val geometry = setOf("x", "y", "w", "h", "rotation", "alpha", "scaleX", "scaleY",
    "pivotX", "pivotY", "pivotZ", "rotationX", "rotationY")
  private val lifecycle = setOf("init", "present", "resume", "pause",
    "back_home_start", "back_home_finish", "back_home_cancel",
    "open_second", "close_second", "open_second_instant", "close_second_instant")
  private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
  private val visualIdentifier = Regex("[A-Za-z0-9_]{1,64}")
  private val imagePath = Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.(?:png|webp)")
  private val functions = setOf("ifelse", "eq", "ne", "gt", "ge", "lt", "le",
    "and", "or", "not", "min", "max", "abs", "int", "round", "floor", "ceil")

  fun validate(
    paths: Set<String>,
    read: (String, Long) -> ByteArray,
    image: (ByteArray) -> Unit,
  ): Set<String> {
    val consumed = linkedSetOf<String>()
    fun bitmap(path: String) {
      try {
        image(read(path, 8L * 1024 * 1024))
      } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid local layer image: $path", error)
      }
      consumed += path
    }
    val dynamic = paths.filter { it.removePrefix("nightmode/").startsWith("layer_animating_icons/") }.toSet()
    val fancyResources = dynamic.filter { "/fancy/" in it }.groupBy { it.substringBefore("/fancy/") + "/fancy/" }
    val configs = dynamic.filter { path ->
      val pieces = path.removePrefix("nightmode/").split('/')
      pieces.size == 3 && pieces[2] == "config.xml" && validNativeIconName(pieces[1]) && '.' in pieces[1]
    }
    for (configPath in configs) {
      val config = NativeLayerConfig.parse(read(configPath, limits.maxXmlBytes.toLong()), limits)
      consumed += configPath
      val base = configPath.removeSuffix("config.xml")
      val rootQuiet = base + "quiet/quietImage.png"
      if (rootQuiet in paths) bitmap(rootQuiet)
      for (layer in config.layers) {
        val prefix = "$base$layer/"
        val quiet = prefix + "quiet/quietImage.png"
        val manifest = prefix + "fancy/manifest.xml"
        if (quiet in paths) bitmap(quiet)
        require(quiet in paths || manifest in paths) { "Missing declared native layer: $prefix" }
        if (layer in config.quietOnly) require(quiet in paths) { "Missing quiet-only layer" }
        if (layer in config.fancyOnly) require(manifest in paths) { "Missing fancy-only layer" }
        if (manifest in paths) {
          val program = read(manifest, limits.maxXmlBytes.toLong())
          val weatherImages = LocalWeatherLayerProgram.resources(program, prefix + "fancy/", paths)
          if (weatherImages != null) weatherImages.forEach(::bitmap)
          else consumed += visual(program, prefix + "fancy/", paths, read)
          consumed += manifest
        }
        fancyResources[prefix + "fancy/"].orEmpty().filter {
          it !in consumed && imagePath.matches(it.removePrefix(prefix + "fancy/"))
        }
          .forEach(::bitmap)
      }
    }
    require(consumed.containsAll(dynamic)) { "Undeclared or unsupported dynamic layer resource" }
    // Nested drawable folders are native static pairs. Flat legacy resources retain their policy.
    val static = paths.filter {
      val pieces = it.removePrefix("nightmode/").split('/')
      pieces.size >= 4 && pieces[0] == "res" && pieces[1].substringBefore('-') == "drawable"
    }
    for (path in static) {
      val pieces = path.removePrefix("nightmode/").split('/')
      require(pieces.size == 4 && validNativeIconName(pieces[2]) &&
        pieces[3] in setOf("0.png", "1.png")) { "Unsupported static layer resource" }
      val prefix = path.substringBeforeLast('/')
      require("$prefix/0.png" in paths && "$prefix/1.png" in paths) { "Incomplete static layer pair" }
      bitmap(path)
    }
    return consumed
  }

  private val timeUpdaters = setOf("DateTime.Day", "DateTime.Hour", "DateTime.Minute", "DateTime.Second")
  private val systemNumbers = setOf("date", "month", "year", "day_of_week", "hour12", "hour24",
    "minute", "second", "time_sys", "ratio", "__darkmode")
  private val numberToken = Regex("#[A-Za-z_][A-Za-z0-9_]*(?:\\.(?:text_width|text_height))?|[A-Za-z_][A-Za-z0-9_]*")

  private fun numeric(value: String, references: MutableSet<String>) {
    require(value.isNotBlank() && value.matches(Regex("[\\s0-9A-Za-z_#()+*/%<>{}=!&|?:,\\-\\.]+")) &&
      !value.contains("..") && !value.contains("{{") && !value.contains("}}")) {
      "Unsupported visual expression: $value"
    }
    require(Regex("[A-Za-z_][A-Za-z0-9_]*\\s*\\(").findAll(value)
      .all { it.value.substringBefore('(').trim() in functions }) { "Unsupported visual function" }
    numberToken.findAll(value).forEach {
      require(it.value.startsWith('#') || it.value in functions) { "Unsupported visual variable" }
      if (it.value.startsWith('#')) references += it.value.removePrefix("#")
    }
  }

  private data class Element(
    val tag: String, val name: String?, var branches: Int = 0,
    var animationKinds: Int = 0, var lastKeyframe: Int = -1, val layered: Boolean = false,
  )

  private fun visual(
    bytes: ByteArray,
    base: String,
    paths: Set<String>,
    read: (String, Long) -> ByteArray,
  ): Set<String> {
    val stack = mutableListOf<Element>()
    val declared = mutableSetOf<String>()
    val targets = mutableSetOf<String>()
    val texts = mutableSetOf<String>()
    val lotties = mutableSetOf<String>()
    val jsonResources = linkedSetOf<String>()
    val localNumbers = mutableSetOf<String>()
    val numericReferences = mutableSetOf<String>()
    val states = mutableSetOf<String>()
    val configs = mutableSetOf<String>()
    val callable = mutableSetOf<String>()
    val references = mutableListOf<Pair<String, String>>()
    // Include Folme callback edges: a nonrecursive FunctionCommand graph alone is insufficient.
    // Keep repeated edges: calling one function twice must charge its expanded work twice.
    val edges = linkedMapOf<String, MutableList<String>>()
    val costs = mutableMapOf<String, Int>()
    var visuals = 0
    var observesDarkMode = false
    var tracks = 0
    var keyframes = 0
    val keyframeTargets = mutableSetOf<String>()
    val keyframeReferences = mutableSetOf<String>()
    fun name(value: String): String = value.also { require(identifier.matches(it)) { "Unsafe visual identifier" } }
    fun visualName(value: String): String = value.also {
      require(visualIdentifier.matches(it)) { "Unsafe visual target identifier" }
    }
    fun names(value: String): List<String> {
      require(value.matches(Regex("'[A-Za-z_][A-Za-z0-9_]*'(?:,'[A-Za-z_][A-Za-z0-9_]*')*")))
      return value.split(',').map { name(it.removeSurrounding("'")) }
    }
    fun edge(from: String, to: String) { edges.getOrPut(from) { mutableListOf() } += to }
    fun boundedInt(value: String, range: IntRange) { require(value.toIntOrNull() in range) }
    var layeredPixels = 0L
    var layeredGroups = 0
    val blendModes = setOf("clear", "src", "dst", "src_over", "dst_over", "src_in", "dst_in",
      "src_out", "dst_out", "src_atop", "dst_atop", "xor", "darken", "lighten",
      "multiply", "screen", "add", "overlay")
    BoundedPackXml.read(bytes, limits, start = { rawTag, attrs, depth ->
      // MAML canvas roots can use names such as bg/fg; their children retain exact roles.
      val tag = if (depth == 1) {
        require(rawTag.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,63}"))) { "Invalid visual canvas name" }
        "Icon"
      } else rawTag
      val parent = stack.lastOrNull()
      val owner = stack.lastOrNull { it.tag == "Function" }?.name?.let { "function:$it" } ?: "entry"
      val command = tag in setOf("FolmeCommand", "FrameRateCommand", "FunctionCommand", "LottieCommand", "AnimationCommand",
        "IfCommand", "VariableCommand", "ExternCommand")
      val inLottieCompletion = stack.any { it.tag == "Triggers" }
      require(if (depth == 1) tag == "Icon" else when {
        command -> parent?.tag in setOf("Trigger", "Function", "Consequent") &&
          (!inLottieCompletion ||
            (tag == "FrameRateCommand" && attrs == mapOf("rate" to "0")) ||
            (inLottieCompletion && tag == "VariableCommand" &&
              attrs == mapOf("name" to "useQuietImage", "type" to "number", "expression" to "1")))
        tag == "Consequent" -> parent?.tag == "IfCommand"
        tag == "Trigger" -> parent?.tag in setOf("ExternalCommands", "Triggers")
        tag == "Triggers" -> parent?.tag == "Lottie"
        tag == "Function" -> parent?.tag == "Icon"
        tag in setOf("PositionAnimation", "AlphaAnimation") -> parent?.tag == "Image"
        tag == "Item" -> parent?.tag in setOf("PositionAnimation", "AlphaAnimation")
        tag in setOf("Group", "Image", "Lottie", "Text", "Var", "FolmeState", "FolmeConfig", "ExternalCommands") ->
          parent?.tag in setOf("Icon", "Group")
        else -> false
      }) { "Unsupported layered visual element: $tag" }
      if (command) costs[owner] = (costs[owner] ?: 0) + 1
      val allowed = when (tag) {
        "Icon" -> setOf("version", "frameRate", "width", "height", "screenWidth", "resDensity", "useVariableUpdater")
        "Group" -> geometry + setOf("name", "align", "alignV", "visibility", "layered")
        "Image" -> geometry + setOf("name", "src", "srcid", "srcExp", "cornerRadius", "align", "alignV", "visibility", "folmeMode", "xfermode")
        "Lottie" -> setOf("name", "src", "x", "y", "w", "h", "align", "alignV", "autoplay", "loop")
        "LottieCommand" -> setOf("target", "command", "params")
        "AnimationCommand" -> setOf("target", "command")
        "PositionAnimation", "AlphaAnimation" -> emptySet()
        "Item" -> if (parent?.tag == "PositionAnimation") setOf("x", "y", "time") else setOf("value", "time")
        "Text" -> geometry + setOf("name", "scale", "align", "alignV", "size", "fontFamily", "color", "textExp")
        "Var" -> setOf("name", "expression", "type", "const")
        "FolmeState" -> geometry + "name"
        "FolmeConfig" -> setOf("name", "property", "ease", "fromSpeed", "onBegin", "onComplete")
        "ExternalCommands", "Triggers", "Consequent" -> emptySet()
        "Trigger" -> setOf("action")
        "Function" -> setOf("name")
        "FunctionCommand" -> setOf("target")
        "IfCommand" -> setOf("ifCondition")
        "VariableCommand" -> setOf("name", "type", "expression")
        "ExternCommand" -> setOf("command", "strPara")
        "FolmeCommand" -> setOf("target", "states", "command", "config")
        "FrameRateCommand" -> setOf("rate", "delay")
        else -> error("Unsupported visual tag")
      }
      require(attrs.keys.all { it in allowed }) { "Unsupported layered visual attribute" }
      attrs.forEach { (key, value) ->
        when (key) {
          "name" -> {
            if (tag in setOf("Image", "Group", "Text", "Lottie")) visualName(value) else name(value)
            if (tag == "VariableCommand") {
              require(value !in systemNumbers) { "Cannot assign a system variable" }
              localNumbers += value
            } else {
              require(declared.add(value)) { "Duplicate visual identifier" }
              when (tag) {
                "FolmeState" -> states += value
                "FolmeConfig" -> configs += value
                "Function" -> callable += value
                "Lottie" -> lotties += value
                "Var" -> {
                  require(value !in systemNumbers) { "Cannot shadow a system variable" }
                  localNumbers += value
                }
                else -> { targets += value; if (tag == "Text") texts += value }
              }
            }
          }
          "src" -> if (tag == "Lottie") {
            require(value.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}\\.json")) &&
              !value.contains("..") && base + value in paths) { "Missing or nonlocal Lottie JSON" }
          } else require(imagePath.matches(value)) { "Nonlocal layer image" }
          "srcid" -> require(value == "#date" && attrs["src"] == "num.png") { "Unsupported date image selector" }
          "type" -> require(if (tag == "VariableCommand") value == "number" else value in setOf("number", "float", "int"))
          "align" -> require(value in setOf("left", "center", "right"))
          "alignV" -> require(value in setOf("top", "center", "bottom"))
          "folmeMode" -> require(value in setOf("true", "false"))
          "layered" -> require(value in setOf("true", "false"))
          "xfermode" -> require(value in blendModes && stack.any { it.layered }) {
            "Unsupported or unisolated layer blend mode"
          }
          "autoplay" -> require(value in setOf("true", "false"))
          "loop" -> boundedInt(value, 0..16)
          "params" -> require(value.matches(Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?")) &&
            value.toDouble() in 0.0..36_000.0) { "Unsupported Lottie command parameter" }
          "useVariableUpdater" -> {
            val updaters = value.split(',')
            require(value == "none" || (updaters.size in 1..2 && updaters.distinct().size == updaters.size &&
              updaters.all { it in timeUpdaters || it == "DarkMode" } &&
              (updaters.size == 1 || "DarkMode" in updaters))) { "Unsupported time updater" }
            observesDarkMode = "DarkMode" in updaters
          }
          "srcExp" -> {
            val source = requireNotNull(attrs["src"])
            require(imagePath.matches(source) && attrs["srcid"] == null && observesDarkMode &&
              value == "ifelse(#__darkmode,'keihome_night/$source','$source')" &&
              base + "keihome_night/$source" in paths) { "Unsupported local appearance selector" }
          }
          "action" -> require(if (parent?.tag == "Triggers") value == "complete"
            else value.split(',').all { it in lifecycle }) { "Unsupported layer trigger action" }
          "target" -> {
            val kind = when (tag) { "FunctionCommand" -> "function"; "LottieCommand" -> "lottie"; else -> "target" }
            references += kind to (if (kind == "function") name(value) else visualName(value))
            if (tag == "AnimationCommand") keyframeReferences += value
            if (kind == "function") edge(owner, "function:$value")
          }
          "states" -> names(value).also {
            require(it.size in 1..2)
            // Native setTo returns without applying anything when its one literal state is absent.
            if (tag != "FolmeCommand" || attrs["command"] != "setTo" || it.size != 1) {
              it.forEach { state -> references += "state" to state }
            }
          }
          "config" -> names(value).also {
            require(it.size in 1..4)
            it.forEach { config -> references += "config" to config; edge(owner, "config:$config") }
          }
          "onBegin", "onComplete" -> {
            val callback = names(value).single()
            val config = attrs["name"] ?: error("Unnamed Folme callback config")
            references += "function" to callback
            edge("config:$config", "function:$callback")
          }
          "command" -> when (tag) {
            "ExternCommand" -> require(value == "__datatimeVarUpdate") { "Unsupported external command" }
            "LottieCommand" -> require(value in setOf("play", "goToAndStop")) { "Unsupported Lottie command" }
            "AnimationCommand" -> {
              val range = requireNotNull(Regex("play\\((0|[1-9][0-9]{0,4}),(0|[1-9][0-9]{0,4})\\)")
                .matchEntire(value)) { "Unsupported keyframe command" }
              val start = range.groupValues[1].toInt()
              val end = range.groupValues[2].toInt()
              require(start <= end && end <= 60_000) { "Unbounded keyframe playback" }
            }
            else -> require(value in setOf("setTo", "fromTo", "to", "cancel"))
          }
          "strPara" -> require(value in setOf("'DateTime.Second'", "'DateTime.Minute'")) { "Unsupported external parameter" }
          "property" -> require(value.split(',').all { it.trim().removeSurrounding("'") in geometry })
          "fontFamily" -> require(value == "mipro-semibold")
          "const" -> require(tag == "Var" && value in setOf("true", "false"))
          "color" -> require(value.matches(Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")))
          "textExp" -> require(value == "formatDate('E',#time_sys)") { "Unsupported date text expression" }
          "size" -> boundedInt(value, 1..256)
          "delay" -> boundedInt(value, 0..60_000)
          "time" -> boundedInt(value, 0..60_000)
          "value" -> boundedInt(value, 0..255)
          "ease" -> {
            val values = value.split(',')
            require(values.size in 1..8 && values.all {
              val number = it.trim().toDoubleOrNull()
              number != null && number.isFinite() && number in -10_000.0..10_000.0
            })
          }
          else -> numeric(value, numericReferences)
        }
      }
      when (tag) {
        "Group" -> if (attrs["layered"] == "true") {
          val width = attrs["w"]?.toIntOrNull()
          val height = attrs["h"]?.toIntOrNull()
          require(width in 1..2048 && height in 1..2048) { "Layered group needs bounded literal dimensions" }
          layeredPixels += requireNotNull(width).toLong() * requireNotNull(height)
          layeredGroups++
          require(layeredGroups <= 8 && stack.count { it.layered } < 4 &&
            layeredPixels <= 2048L * 2048) { "Layered group surface budget exceeded" }
        }
        "Icon" -> {
          boundedInt(attrs["width"].orEmpty(), 1..2048)
          boundedInt(attrs["height"].orEmpty(), 1..2048)
          attrs["frameRate"]?.let { boundedInt(it, 0..120) }
          attrs["screenWidth"]?.let { boundedInt(it, 1..8192) }
          attrs["resDensity"]?.let { boundedInt(it, 1..1280) }
          attrs["version"]?.let { require(it == "1") }
        }
        "Image" -> {
          val src = requireNotNull(attrs["src"])
          if (attrs["srcid"] == "#date") {
            require((1..31).all { base + "num_$it.png" in paths }) { "Incomplete date image frames" }
          } else require(base + src in paths) { "Missing layer image" }
          visuals++
        }
        "Lottie" -> {
          require(attrs.keys.containsAll(setOf("name", "src")))
          val json = base + requireNotNull(attrs["src"])
          if (jsonResources.add(json)) LocalLottieIconValidator.validate(read(json, 1024 * 1024))
          visuals++
        }
        "LottieCommand" -> {
          require(attrs.keys.containsAll(setOf("target", "command")))
          if (attrs["command"] == "goToAndStop") require(attrs["params"] != null)
        }
        "AnimationCommand" -> require(attrs.keys == setOf("target", "command"))
        "PositionAnimation", "AlphaAnimation" -> {
          val image = requireNotNull(parent)
          val bit = if (tag == "PositionAnimation") 1 else 2
          require(image.animationKinds and bit == 0 && ++tracks <= 32) { "Keyframe track limit" }
          image.animationKinds = image.animationKinds or bit
          image.name?.let(keyframeTargets::add)
        }
        "Item" -> {
          val track = requireNotNull(parent)
          val expected = if (track.tag == "PositionAnimation") setOf("x", "y", "time") else setOf("value", "time")
          require(attrs.keys == expected && ++track.branches <= 64 && ++keyframes <= 256)
          val time = attrs.getValue("time").toInt()
          require(time >= track.lastKeyframe) { "Keyframe times must be ordered" }
          track.lastKeyframe = time
          if (track.tag == "PositionAnimation") for (axis in listOf("x", "y")) {
            val value = attrs.getValue(axis)
            require(value.matches(Regex("-?[0-9]+(?:\\.[0-9]{1,6})?")) &&
              value.toDoubleOrNull()?.let { it in -4096.0..4096.0 } == true) { "Unsupported keyframe position" }
          }
        }
        "Text" -> {
          require(attrs.keys.containsAll(setOf("name", "textExp", "size", "fontFamily", "color")))
          visuals++
        }
        "Var", "VariableCommand" -> require(attrs.keys.containsAll(setOf("name", "expression")) &&
          (tag != "VariableCommand" || attrs["type"] == "number"))
        "Trigger" -> require(!attrs["action"].isNullOrBlank())
        "Function" -> require(attrs["name"] != null && callable.size <= 32) { "Function declaration limit" }
        "FunctionCommand" -> require(attrs["target"] != null)
        "IfCommand" -> require(attrs["ifCondition"] != null && stack.count { it.tag == "IfCommand" } < 2)
        "Consequent" -> require(requireNotNull(parent).let { ++it.branches } <= 2) { "Too many conditional branches" }
        "ExternCommand" -> require(attrs.keys == setOf("command", "strPara"))
        "FolmeCommand" -> require(attrs.keys.containsAll(setOf("target", "states", "command")))
        "FolmeConfig", "FolmeState" -> require(attrs["name"] != null)
        "FrameRateCommand" -> boundedInt(attrs["rate"].orEmpty(), 0..120)
      }
      stack += Element(tag, attrs["name"], layered = tag == "Group" && attrs["layered"] == "true")
    }, end = { _, _ ->
      val element = stack.removeAt(stack.lastIndex)
      if (element.tag == "IfCommand") require(element.branches in 1..2)
      if (element.tag in setOf("PositionAnimation", "AlphaAnimation")) {
        require(element.branches > 0) { "Empty keyframe track" }
      }
    }, text = { text, _ -> require(text.isBlank()) })
    require(visuals in 1..128 && texts.size <= 16 && states.size <= 64 && configs.size <= 64)
    require(keyframeTargets.containsAll(keyframeReferences)) { "Missing keyframe target" }
    require(localNumbers.none { it in targets || it in lotties || it in callable || it in states || it in configs })
    numericReferences.forEach { value ->
      if (value == "__darkmode") require(observesDarkMode) { "Missing DarkMode updater" }
      if ('.' in value) require(value.substringBefore('.') in texts) { "Unknown local text metric" }
      else require(value in systemNumbers || value in localNumbers) { "Unknown local numeric variable: $value" }
    }
    references.forEach { (kind, value) ->
      require(value in when (kind) {
        "state" -> states; "config" -> configs; "function" -> callable; "lottie" -> lotties; else -> targets
      }) { "Unknown local visual $kind: $value" }
    }
    // Bound both recursion depth and expanded work; an acyclic doubling graph can still be costly.
    val active = mutableSetOf<String>()
    val work = mutableMapOf<String, Int>()
    val heights = mutableMapOf<String, Int>()
    fun visit(node: String): Int {
      work[node]?.let { return it }
      require(active.add(node)) { "Recursive local visual function/callback" }
      var total = costs[node] ?: 1
      var height = 1
      for (child in edges[node].orEmpty()) {
        val childWork = visit(child)
        require(childWork <= 4096 - total) { "Local visual function expansion limit" }
        total += childWork
        height = maxOf(height, 1 + heights.getValue(child))
        require(height <= 16) { "Local visual function expansion limit" }
      }
      active.remove(node)
      heights[node] = height
      work[node] = total
      return total
    }
    (edges.keys + edges.values.flatten() + callable.map { "function:$it" }).forEach { visit(it) }
    return jsonResources
  }
}
