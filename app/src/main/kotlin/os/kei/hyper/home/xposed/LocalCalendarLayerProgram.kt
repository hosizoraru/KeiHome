package os.kei.hyper.home.xposed

import os.kei.hyper.home.iconpack.BoundedPackXml
import os.kei.hyper.home.iconpack.IconPackLimits

/** Local date digits, weekday images and finite refresh controllers; no data providers or commands outside this scene. */
internal object LocalCalendarLayerProgram {
  private data class Node(val tag: String, val attrs: Map<String, String>, val children: MutableList<Node> = mutableListOf())
  private val lifecycle = setOf("init", "present", "back_home_start", "back_home_finish", "back_home_cancel")
  private val image = Regex("[A-Za-z0-9_-]+\\.png")
  private val identifier = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
  private val geometry = setOf("x", "y", "w", "h", "alpha", "align", "alignV")

  fun resources(bytes: ByteArray, base: String, paths: Set<String>): Set<String>? {
    val stack = mutableListOf<Node>()
    var root: Node? = null
    var hasDate = false
    BoundedPackXml.read(bytes, IconPackLimits(maxXmlBytes = 64 * 1024, maxMappings = 1024),
      start = { tag, attrs, _ ->
        val node = Node(tag, attrs)
        if (stack.isEmpty()) { require(root == null); root = node } else stack.last().children += node
        stack += node
        if (tag == "Time") hasDate = true
      }, end = { _, _ -> stack.removeAt(stack.lastIndex) }, text = { text, _ -> require(text.isBlank()) })
    if (!hasDate) return null
    val icon = requireNotNull(root)
    require(icon.tag == "Icon" && icon.attrs.keys.all {
      it in setOf("version", "width", "height", "screenWidth", "frameRate", "useVariableUpdater")
    })
    require(icon.attrs["version"] == "1" && icon.attrs["frameRate"] == "0" &&
      icon.attrs["useVariableUpdater"] == "DateTime.Day")
    for (axis in listOf("width", "height")) integer(icon.attrs.getValue(axis), 1..2048)
    icon.attrs["screenWidth"]?.let { integer(it, 1..4096) }
    val assets = linkedSetOf<String>()
    val controllers = linkedSetOf<String>()
    val played = linkedSetOf<String>()
    val broadcasts = linkedSetOf<String>()
    var binders = 0
    var external = 0
    var dates = 0
    var visuals = 0
    fun frames(src: String, range: IntRange) {
      require(image.matches(src))
      for (index in range) {
        val path = "$base${src.removeSuffix(".png")}_$index.png"
        require(path in paths) { "Missing local calendar frame: $path" }
        assets += path
      }
    }
    fun drawing(node: Node, depth: Int) {
      require(depth <= 8 && node.tag in setOf("Group", "Image", "Time") && ++visuals <= 32)
      if (node.tag == "Group") {
        require(node.attrs.isEmpty())
        node.children.forEach { drawing(it, depth + 1) }
        return
      }
      require(node.children.isEmpty() && node.attrs.keys.all {
        it in geometry || it == "src" || it == if (node.tag == "Time") "format" else "srcid"
      })
      node.attrs.forEach { (key, value) -> when (key) {
        "x", "y" -> integer(value, -4096..4096)
        "w", "h" -> integer(value, 1..2048)
        "alpha" -> integer(value, 0..255)
        "align" -> require(value in setOf("left", "center", "right"))
        "alignV" -> require(value in setOf("top", "center", "bottom"))
      } }
      if (node.tag == "Time") {
        require(node.attrs["format"] == "dd" && ++dates == 1)
        frames(node.attrs.getValue("src"), 0..9)
      } else {
        require(node.attrs["srcid"] == "#day_of_week")
        frames(node.attrs.getValue("src"), 1..7)
      }
    }
    for (node in icon.children) when (node.tag) {
      "VariableBinders" -> {
        require(node.attrs.isEmpty() && ++binders == 1)
        node.children.forEach {
          require(it.tag == "BroadcastBinder" && it.children.isEmpty() &&
            LocalNativeIconValidator.validClockBinding(it.attrs) && broadcasts.add(it.attrs.getValue("action")))
        }
      }
      "FramerateController" -> {
        require(node.attrs.keys == setOf("name", "initPause", "loop") &&
          node.attrs["initPause"] == "true" && node.attrs["loop"] == "false")
        val name = node.attrs.getValue("name")
        require(identifier.matches(name) && !name.equals("root", ignoreCase = true) &&
          controllers.add(name) && controllers.size <= 4)
        require(node.children.size in 2..64)
        var last = -1
        node.children.forEachIndexed { index, point ->
          require(point.tag == "ControlPoint" && point.children.isEmpty() && point.attrs.keys == setOf("frameRate", "time"))
          val time = integer(point.attrs.getValue("time"), 0..60_000)
          val rate = integer(point.attrs.getValue("frameRate"), 0..120)
          require(if (index == 0) time == 0 else time > last)
          if (index == node.children.lastIndex) require(rate == 0)
          last = time
        }
      }
      "ExternalCommands" -> {
        require(node.attrs.isEmpty() && ++external == 1 && node.children.size in 1..16)
        node.children.forEach { trigger ->
          require(trigger.tag == "Trigger" && trigger.attrs.keys == setOf("action") &&
            trigger.attrs.getValue("action").split(',').all { it in lifecycle } && trigger.children.size in 1..4)
          trigger.children.forEach {
            require(it.tag == "AnimationCommand" && it.children.isEmpty() &&
              it.attrs.keys == setOf("target", "command") && it.attrs["command"] == "play")
            played += it.attrs.getValue("target")
          }
        }
      }
      "Group", "Image", "Time" -> drawing(node, 1)
      else -> error("Unsupported local calendar element: ${node.tag}")
    }
    require(dates == 1 && controllers.containsAll(played))
    return assets
  }

  private fun integer(value: String, range: IntRange): Int =
    requireNotNull(value.toIntOrNull()).also { require(it in range) }
}
