package os.kei.hyper.home.iconpack

/** Bounded reader for the native Config data carried by source layer resources, not MAML. */
internal data class NativeLayerConfig(
  val layers: List<String>,
  val quietOnly: Set<String>,
  val fancyOnly: Set<String>,
) {
  companion object {
    const val MAX_LAYERS = 8

    fun parse(bytes: ByteArray, limits: IconPackLimits, check: () -> Unit = {}): NativeLayerConfig {
      val variables = linkedMapOf<String, String>()
      var containers = 0
      BoundedPackXml.read(bytes, limits, check, start = { tag, attrs, depth ->
        val valid = when (depth) {
          1 -> tag == "Config" && attrs.isEmpty()
          2 -> tag == "Variables" && attrs.isEmpty() && ++containers == 1
          3 -> tag == "Variable" && attrs.keys.all { it in setOf("name", "value", "type") }
          else -> false
        }
        if (!valid) throw IconPackException("Unsupported native layer config")
        if (depth == 3) {
          val name = attrs["name"] ?: throw IconPackException("Missing layer variable name")
          val value = attrs["value"] ?: throw IconPackException("Missing layer variable value")
          if (attrs["type"] !in setOf(null, "string") || variables.put(name, value) != null)
            throw IconPackException("Invalid layer variable")
        }
      }, text = { text, _ ->
        if (text.isNotBlank()) throw IconPackException("Unexpected layer config text")
      })
      val slots = sortedMapOf<Int, String>()
      val quietSlots = mutableSetOf<Int>()
      val fancySlots = mutableSetOf<Int>()
      variables.forEach { (name, value) ->
        when {
          name.matches(Regex("layer[0-7]")) -> {
            if (!value.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")))
              throw IconPackException("Unsafe native layer directory")
            slots[name.removePrefix("layer").toInt()] = value
          }
          name == "fancy_work" -> {
            if (value !in setOf("true", "false")) throw IconPackException("Invalid fancy_work")
          }
          name.matches(Regex("use_(fancy_when_static|quiet_when_anim)_layer[0-7]")) -> {
            // Keep legacy source mode selectors; do not reinterpret them as exclusive layers.
            if (value !in setOf("true", "false")) throw IconPackException("Invalid native layer mode")
          }
          name.matches(Regex("layer_only_(quiet|fancy)[0-7]")) -> {
            if (value !in setOf("true", "false")) throw IconPackException("Invalid quiet-layer flag")
            if (value == "true") {
              if (name.startsWith("layer_only_quiet")) quietSlots += name.last().digitToInt()
              else fancySlots += name.last().digitToInt()
            }
          }
          else -> throw IconPackException("Unsupported native layer variable: $name")
        }
      }
      if (slots.isEmpty() || slots.keys.toList() != (0 until slots.size).toList() ||
        slots.values.distinct().size != slots.size ||
        quietSlots.intersect(fancySlots).isNotEmpty() ||
        variables.keys.filter { it.startsWith("layer_only_") || it.startsWith("use_fancy_when_static_layer") ||
          it.startsWith("use_quiet_when_anim_layer") }
          .any { it.last().digitToInt() !in slots })
        throw IconPackException("Invalid native layer declarations")
      return NativeLayerConfig(slots.values.toList(),
        quietSlots.mapTo(linkedSetOf()) { slots.getValue(it) },
        fancySlots.mapTo(linkedSetOf()) { slots.getValue(it) })
    }
  }
}
