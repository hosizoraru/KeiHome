package os.kei.hyper.home.iconpack

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.util.TypedValue
import java.io.IOException
import java.util.concurrent.CancellationException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** Proves source palette references; never classifies an icon from its rendered RGB values. */
internal object AndroidPaletteIcon {
  /** Selected configuration; at most 8 resource frames, XML depth 8, 32 resources and 256 nodes. */
  fun foreground(resources: Resources, id: Int, check: () -> Unit = {}): Boolean = try {
    Proof(resources, check).foreground(id)
  } catch (failure: CancellationException) {
    throw failure
  } catch (_: RuntimeException) {
    false
  } catch (_: XmlPullParserException) {
    false
  } catch (_: IOException) {
    false
  }

  private class Unsupported : RuntimeException()
  private data class Attribute(val reference: Int, val literal: TypedValue?)
  private data class Node(
    val name: String, val attributes: Map<String, Attribute>, val children: List<Node>, val depth: Int,
  )

  private class Proof(private val resources: Resources, private val check: () -> Unit) {
    private val seen = mutableSetOf<Int>()
    private val active = mutableSetOf<Int>()
    private var nodes = 0
    private var events = 0
    private var role: Int? = null
    private var visible = false

    fun foreground(id: Int): Boolean {
      val root = drawable(id, 0)
      requireSupported(root.name == "adaptive-icon" && root.attributes.isEmpty())
      requireSupported(root.children.size == 2)
      val background = root.children.singleOrNull { it.name == "background" } ?: throw Unsupported()
      val foreground = root.children.singleOrNull { it.name == "foreground" } ?: throw Unsupported()
      attributes(background, setOf("drawable"))
      requireSupported(background.children.isEmpty())
      requireSupported(color(required(background, "drawable"), background.depth + 1) != null)
      layer(foreground)
      return role != null && visible
    }

    private fun tick(depth: Int) {
      check()
      // XML wrappers, scalar reads and aliases all contribute to this implementation stack.
      requireSupported(depth <= 64)
    }

    private fun <T> resource(id: Int, depth: Int, read: () -> T): T {
      tick(depth)
      requireSupported(id != 0 && active.add(id))
      try {
        requireSupported(active.size <= 8)
        seen += id
        requireSupported(seen.size <= 32)
        return read()
      } finally { active.remove(id) }
    }

    private fun value(id: Int): TypedValue = TypedValue().also { resources.getValue(id, it, false) }

    private fun next(xml: XmlResourceParser) {
      check()
      requireSupported(++events <= 1024)
      xml.next()
    }

    private fun drawable(id: Int, depth: Int): Node = resource(id, depth) {
      requireSupported(resources.getResourceTypeName(id) in setOf("drawable", "mipmap"))
      val value = value(id)
      if (value.type == TypedValue.TYPE_REFERENCE) return@resource drawable(value.data, depth + 1)
      requireSupported(value.type == TypedValue.TYPE_STRING && value.string?.endsWith(".xml") == true)
      resources.getXml(id).use { xml ->
        while (xml.eventType != XmlPullParser.START_TAG) {
          tick(depth)
          requireSupported(xml.eventType == XmlPullParser.START_DOCUMENT)
          next(xml)
        }
        val root = node(xml, depth)
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
          tick(depth)
          requireSupported(xml.eventType == XmlPullParser.TEXT && xml.isWhitespace)
          next(xml)
        }
        root
      }
    }

    /** Leaves the parser on the event following this element's closing tag. */
    private fun node(xml: XmlResourceParser, depth: Int): Node {
      tick(depth)
      requireSupported(xml.depth in 1..8 && ++nodes <= 256 && xml.namespace.orEmpty().isEmpty())
      val name = xml.name
      val allowed = allowed(name)
      requireSupported(xml.attributeCount <= 16)
      val attributes = linkedMapOf<String, Attribute>()
      for (i in 0 until xml.attributeCount) {
        tick(depth)
        val key = xml.getAttributeName(i)
        val attributeId = xml.getAttributeNameResource(i)
        requireSupported(xml.getAttributeNamespace(i) == ANDROID && key in allowed &&
          attributeId ushr 24 == 1 && key !in attributes)
        val reference = xml.getAttributeResourceValue(i, 0)
        val literal = if (reference != 0) null else {
          val array = resources.obtainAttributes(xml, intArrayOf(attributeId))
          try {
            TypedValue().also { requireSupported(array.getValue(0, it)) }
          } finally { array.recycle() }
        }
        requireSupported(literal?.type != TypedValue.TYPE_ATTRIBUTE)
        requireSupported(literal?.string?.length?.let { it <= 65536 } != false)
        attributes[key] = Attribute(reference, literal)
      }
      val children = mutableListOf<Node>()
      next(xml)
      while (xml.eventType != XmlPullParser.END_TAG) {
        tick(depth)
        when (xml.eventType) {
          XmlPullParser.START_TAG -> children += node(xml, depth + 1)
          XmlPullParser.TEXT -> { requireSupported(xml.isWhitespace); next(xml) }
          else -> throw Unsupported()
        }
      }
      requireSupported(xml.name == name)
      next(xml)
      return Node(name, attributes, children, depth)
    }

    private fun scalar(attribute: Attribute, depth: Int): TypedValue {
      tick(depth)
      if (attribute.reference == 0) return attribute.literal ?: throw Unsupported()
      return scalar(attribute.reference, depth)
    }

    private fun scalar(id: Int, depth: Int): TypedValue = resource(id, depth) {
      val value = value(id)
      if (value.type == TypedValue.TYPE_REFERENCE) scalar(value.data, depth + 1) else {
        requireSupported(value.type != TypedValue.TYPE_ATTRIBUTE)
        value
      }
    }

    private fun color(attribute: Attribute, depth: Int): Int? {
      tick(depth)
      if (attribute.reference != 0) return color(attribute.reference, depth)
      val literal = attribute.literal ?: throw Unsupported()
      requireSupported(literal.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT &&
        literal.data ushr 24 == 0)
      return null
    }

    private fun color(id: Int, depth: Int): Int? = resource(id, depth) {
      requireSupported(resources.getResourceTypeName(id) == "color")
      if (id ushr 24 == 1 && resources.getResourcePackageName(id) == "android" &&
        PALETTE.matches(resources.getResourceEntryName(id))) {
        opaque(value(id), depth)
        return@resource id
      }
      val value = value(id)
      if (value.type == TypedValue.TYPE_REFERENCE) color(value.data, depth + 1) else {
        requireSupported(value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT &&
          value.data ushr 24 == 0)
        null
      }
    }

    private fun opaque(color: TypedValue, depth: Int) {
      if (color.type == TypedValue.TYPE_REFERENCE) {
        resource(color.data, depth + 1) { opaque(value(color.data), depth + 1) }
      } else {
        requireSupported(color.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT &&
          color.data ushr 24 == 255)
      }
    }

    private fun layer(node: Node) {
      tick(node.depth)
      when (node.name) {
        "foreground", "item", "inset" -> {
          val geometry = when (node.name) {
            "foreground" -> emptySet()
            "item" -> ITEM
            else -> INSETS
          }
          attributes(node, geometry + "drawable")
          for (key in geometry) node.attributes[key]?.let {
            if (key == "gravity") integer(it, node.depth + 1, 0..Int.MAX_VALUE)
            else dimension(it, node.depth + 1, fractions = key !in setOf("width", "height"))
          }
          val reference = node.attributes["drawable"]
          if (reference != null) {
            requireSupported(reference.reference != 0 && node.children.isEmpty())
            layer(drawable(reference.reference, node.depth + 1))
          } else {
            requireSupported(node.children.size == 1)
            layer(node.children.single())
          }
        }
        "layer-list" -> {
          requireSupported(node.attributes.isEmpty() && node.children.isNotEmpty())
          requireSupported(node.children.all { it.name == "item" })
          node.children.forEach(::layer)
        }
        "vector" -> {
          attributes(node, VECTOR)
          dimension(required(node, "width"), node.depth + 1, positive = true)
          dimension(required(node, "height"), node.depth + 1, positive = true)
          requireSupported(number(required(node, "viewportWidth"), node.depth + 1) > 0)
          requireSupported(number(required(node, "viewportHeight"), node.depth + 1) > 0)
          alpha(node, "alpha")
          node.attributes["autoMirrored"]?.let {
            requireSupported(scalar(it, node.depth + 1).type == TypedValue.TYPE_INT_BOOLEAN)
          }
          name(node)
          requireSupported(node.children.isNotEmpty())
          node.children.forEach(::vectorChild)
        }
        else -> throw Unsupported()
      }
    }

    private fun vectorChild(node: Node) {
      tick(node.depth)
      when (node.name) {
        "group" -> {
          attributes(node, GROUP + "name")
          name(node)
          GROUP.forEach { key -> node.attributes[key]?.let { number(it, node.depth + 1) } }
          node.children.forEach(::vectorChild)
        }
        "clip-path", "path" -> {
          attributes(node, if (node.name == "path") PATH else setOf("name", "pathData", "fillType"))
          requireSupported(node.children.isEmpty())
          name(node)
          val path = scalar(required(node, "pathData"), node.depth + 1)
          requireSupported(path.type == TypedValue.TYPE_STRING && !path.string.isNullOrBlank() &&
            path.string.length <= 65536)
          node.attributes["fillType"]?.let { integer(it, node.depth + 1, 0..1) }
          if (node.name == "path") {
            val fillAlpha = alpha(node, "fillAlpha")
            val strokeAlpha = alpha(node, "strokeAlpha")
            val strokeWidth = node.attributes["strokeWidth"]?.let { number(it, node.depth + 1) } ?: 0f
            requireSupported(strokeWidth >= 0)
            for ((key, opacity) in listOf("fillColor" to fillAlpha,
              "strokeColor" to (if (strokeWidth > 0) strokeAlpha else 0f))) {
              node.attributes[key]?.let { attribute ->
                color(attribute, node.depth + 1)?.let { palette ->
                  requireSupported(role == null || role == palette)
                  role = palette
                  if (opacity > 0) visible = true
                }
              }
            }
            node.attributes["strokeLineCap"]?.let { integer(it, node.depth + 1, 0..2) }
            node.attributes["strokeLineJoin"]?.let { integer(it, node.depth + 1, 0..2) }
            for (key in setOf("strokeMiterLimit", "trimPathStart", "trimPathEnd", "trimPathOffset")) {
              node.attributes[key]?.let {
                val number = number(it, node.depth + 1)
                requireSupported(key == "trimPathOffset" || number >= 0)
              }
            }
          }
        }
        else -> throw Unsupported()
      }
    }

    private fun name(node: Node) {
      node.attributes["name"]?.let {
        val value = scalar(it, node.depth + 1)
        requireSupported(value.type == TypedValue.TYPE_STRING && value.string.length <= 256)
      }
    }

    private fun alpha(node: Node, name: String): Float =
      node.attributes[name]?.let { number(it, node.depth + 1).also { requireSupported(it in 0f..1f) } } ?: 1f

    private fun dimension(attribute: Attribute, depth: Int, fractions: Boolean = false, positive: Boolean = false) {
      val value = scalar(attribute, depth)
      val amount = when (value.type) {
        TypedValue.TYPE_DIMENSION -> TypedValue.complexToFloat(value.data)
        TypedValue.TYPE_FRACTION -> {
          requireSupported(fractions)
          TypedValue.complexToFloat(value.data)
        }
        else -> throw Unsupported()
      }
      requireSupported(amount.isFinite() && if (positive) amount > 0 else amount >= 0)
    }

    private fun number(attribute: Attribute, depth: Int): Float {
      val value = scalar(attribute, depth)
      val number = when (value.type) {
        TypedValue.TYPE_FLOAT -> value.float
        TypedValue.TYPE_INT_DEC, TypedValue.TYPE_INT_HEX -> value.data.toFloat()
        else -> throw Unsupported()
      }
      requireSupported(number.isFinite())
      return number
    }

    private fun integer(attribute: Attribute, depth: Int, range: IntRange) {
      val value = scalar(attribute, depth)
      requireSupported(value.type in setOf(TypedValue.TYPE_INT_DEC, TypedValue.TYPE_INT_HEX) && value.data in range)
    }

    private fun attributes(node: Node, allowed: Set<String>) = requireSupported(node.attributes.keys.all { it in allowed })
    private fun required(node: Node, key: String): Attribute = node.attributes[key] ?: throw Unsupported()
  }

  private fun requireSupported(value: Boolean) { if (!value) throw Unsupported() }
  private const val ANDROID = "http://schemas.android.com/apk/res/android"
  private val PALETTE = Regex("system_(?:accent[123]|neutral[12])_(?:0|10|50|[1-9]00|1000)")
  private val INSETS = setOf("inset", "insetLeft", "insetTop", "insetRight", "insetBottom")
  private val ITEM = setOf("left", "top", "right", "bottom", "start", "end", "width", "height", "gravity")
  private val VECTOR = setOf("name", "width", "height", "viewportWidth", "viewportHeight", "alpha", "autoMirrored")
  private val GROUP = setOf("rotation", "pivotX", "pivotY", "scaleX", "scaleY", "translateX", "translateY")
  private val PATH = setOf("name", "pathData", "fillColor", "strokeColor", "strokeWidth", "fillAlpha",
    "strokeAlpha", "strokeLineCap", "strokeLineJoin", "strokeMiterLimit", "trimPathStart", "trimPathEnd",
    "trimPathOffset", "fillType")

  private fun allowed(name: String): Set<String> = when (name) {
    "adaptive-icon", "layer-list" -> emptySet()
    "background", "foreground" -> setOf("drawable")
    "inset" -> INSETS + "drawable"
    "item" -> ITEM + "drawable"
    "vector" -> VECTOR
    "group" -> GROUP + "name"
    "path" -> PATH
    "clip-path" -> setOf("name", "pathData", "fillType")
    else -> throw Unsupported()
  }
}
