package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.roundToInt
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

internal data class BiliImageAbi(
  val requestIndex: Int,
  val modifierIndex: Int,
  val onStateIndex: Int,
  val composerIndex: Int,
  val changedStartIndex: Int,
  val changedCount: Int,
  val defaultIndex: Int,
) {
  fun clearDirtyBits(args: Array<Any?>, paramIndex: Int) {
    val slot = changedStartIndex + (paramIndex / 10)
    if (slot >= defaultIndex) return
    val current = args[slot] as? Int ?: return
    val shift = 1 + (paramIndex % 10) * 3
    args[slot] = current and (0x7 shl shift).inv()
  }

  fun clearDefaultBit(args: Array<Any?>, paramIndex: Int) {
    if (paramIndex !in 0..30) return
    val current = args[defaultIndex] as? Int ?: return
    args[defaultIndex] = current and (1 shl paramIndex).inv()
  }

  fun hasDefaultFlag(args: Array<Any?>, paramIndex: Int): Boolean {
    if (paramIndex !in 0..30) return false
    val defaults = args[defaultIndex] as? Int ?: return false
    return (defaults and (1 shl paramIndex)) != 0
  }
}

internal fun parseBiliImageAbi(parameterTypeNames: List<String>): BiliImageAbi? {
  if (parameterTypeNames.size < 8) return null
  if (parameterTypeNames[0] != "kntr.base.imageloader.ImmutableImageRequest") return null
  if (parameterTypeNames[1] != "androidx.compose.ui.Modifier") return null
  val composerIndex = parameterTypeNames.indexOfLast { it == "androidx.compose.runtime.Composer" }
  if (composerIndex < 5) return null
  val trailingInts = parameterTypeNames.size - 1 - composerIndex
  if (trailingInts !in 2..3) return null
  for (i in (composerIndex + 1) until parameterTypeNames.size) {
    if (parameterTypeNames[i] != "int") return null
  }
  val lifecycleIndex = parameterTypeNames.indexOfFirst { it == "androidx.lifecycle.Lifecycle" }
  val searchStart = if (lifecycleIndex in 2 until composerIndex) lifecycleIndex + 1 else 2
  val onStateIndex = (searchStart until composerIndex).firstOrNull {
    parameterTypeNames[it] == "kotlin.jvm.functions.Function1"
  } ?: return null
  if (onStateIndex > 30) return null
  val changedStartIndex = composerIndex + 1
  val changedCount = trailingInts - 1
  if (onStateIndex / 10 >= changedCount) return null
  return BiliImageAbi(
    requestIndex = 0,
    modifierIndex = 1,
    onStateIndex = onStateIndex,
    composerIndex = composerIndex,
    changedStartIndex = changedStartIndex,
    changedCount = changedCount,
    defaultIndex = parameterTypeNames.lastIndex,
  )
}

internal fun resolveInvalidateMethod(scopeClass: Class<*>): Method? =
  runCatching { scopeClass.getMethod("invalidate") }.getOrNull()
    ?: scopeClass.declaredMethods.singleOrNull { method ->
      !Modifier.isStatic(method.modifiers) &&
        method.parameterCount == 0 &&
        method.returnType == Void.TYPE
    }?.apply { isAccessible = true }

internal fun isNearIntegerPixel(value: Float): Boolean =
  value.isFinite() && abs(value - value.roundToInt()) <= 0.05f

/** Resolves the home footer's loaded image, placement and draw phase. */
internal class BilibiliComposeFooter private constructor(
  val drawFooter: Method,
  val image: Method,
  private val abi: BiliImageAbi,
  private val options: Method,
  private val contentScale: Method,
  private val alignment: Method,
  private val alpha: Method,
  private val colorFilter: Method,
  private val crop: Any,
  private val topCenter: Any,
  private val positioned: Method,
  private val attached: Method,
  private val position: Method,
  private val size: Method,
  private val source: Method,
  private val getBitmap: Method,
  private val asBitmap: Method,
  private val drawing: Drawing,
  private val unit: Any,
) {
  private val scope = runCatching { image.parameterTypes[abi.composerIndex].getMethod("getRecomposeScope") }.getOrNull()
  private val useScope = scope?.let {
    runCatching { it.declaringClass.getMethod("recordUsed", it.returnType) }.getOrNull()
  }
  private val invalidateScope = scope?.let { resolveInvalidateMethod(it.returnType) }

  fun observeScope(args: Array<Any?>): Any? {
    val composer = args.getOrNull(abi.composerIndex) ?: return null
    val getter = scope ?: return null
    if (!getter.declaringClass.isInstance(composer) || invalidateScope == null) return null
    return getter.invoke(composer)?.also { useScope?.invoke(composer, it) }
  }

  fun refreshScope(previous: Any) {
    invalidateScope?.takeIf { it.declaringClass.isInstance(previous) }?.invoke(previous)
  }

  private data class Artwork(
    val drawable: BitmapDrawable,
    val scale: Float,
    val top: Float,
    val width: Int,
    val height: Int,
  )

  inner class Binding(
    val request: Any,
    private var invalidate: (() -> Unit)?,
    private var onDrawFailure: (() -> Unit)?,
  ) {
    private var coordinates: Any? = null
    private val frame = drawing.newState()
    private var currentArtwork: Artwork? = null
    private val redrawArtwork = callback { scope ->
      if (scope != null) {
        val replaced = runCatching {
          val artwork = drawing.getValue.invoke(frame) as? Artwork ?: return@runCatching false
          if (artwork.drawable.bitmap.isRecycled) return@runCatching false
          val canvas = drawing.canvas(scope)
          val saved = canvas.save()
          try {
            canvas.clipRect(0f, minOf(0f, artwork.top), artwork.width.toFloat(), artwork.height.toFloat())
            canvas.translate(0f, artwork.top)
            canvas.scale(artwork.scale, artwork.scale)
            artwork.drawable.draw(canvas)
          } finally {
            canvas.restoreToCount(saved)
          }
          true
        }.getOrElse {
          onDrawFailure?.invoke()
          false
        }
        if (!replaced) drawing.drawContent.invoke(scope)
      }
    }
    var bitmap: Bitmap? = null
      private set
    private val placement = callback {
      invalidate?.let { redraw -> coordinates = it; redraw() }
    }
    private val onState = callback { state ->
      val redraw = invalidate ?: return@callback
      val loaded = runCatching {
        val value = state?.takeIf(source.declaringClass::isInstance)?.let { source.invoke(it) }
        value?.takeIf(getBitmap.declaringClass::isInstance)
          ?.let { asBitmap.invoke(null, getBitmap.invoke(it)) as? Bitmap }
      }.getOrNull()
      if (loaded !== bitmap) clearArtwork()
      bitmap = loaded
      redraw()
    }

    fun release() {
      clearArtwork()
      invalidate = null
      onDrawFailure = null
      coordinates = null
      bitmap = null
    }

    fun observe(args: Array<Any?>): Array<Any?> {
      val copy = args.copyOf()
      val observer = positioned.invoke(null, args[abi.modifierIndex], placement)
      copy[abi.modifierIndex] = drawing.drawWithContent.invoke(null, observer, redrawArtwork)
      copy[abi.onStateIndex] = onState
      // Let Compose recompute the dirty bits for the two observed arguments across $changed slots.
      abi.clearDirtyBits(copy, abi.modifierIndex)
      abi.clearDirtyBits(copy, abi.onStateIndex)
      abi.clearDefaultBit(copy, abi.onStateIndex)
      return copy
    }

    fun showArtwork(drawable: BitmapDrawable, scale: Float, top: Float, bounds: Rect) {
      val width = bounds.width()
      val height = bounds.height()
      val current = currentArtwork
      if (current?.drawable === drawable && current.scale == scale && current.top == top &&
        current.width == width && current.height == height
      ) return
      val next = Artwork(drawable, scale, top, width, height)
      drawing.setValue.invoke(frame, next)
      currentArtwork = next
    }

    fun clearArtwork() {
      if (currentArtwork == null) return
      drawing.setValue.invoke(frame, null)
      currentArtwork = null
    }

    fun wasDetached(): Boolean = coordinates?.let { attached.invoke(it) != true } ?: false

    fun bounds(out: Rect): Boolean {
      val node = coordinates ?: return false
      if (attached.invoke(node) != true) return false
      val xy = position.invoke(null, node) as Long
      val dimensions = size.invoke(node) as Long
      val x = Float.fromBits((xy shr 32).toInt())
      val y = Float.fromBits(xy.toInt())
      if (!isNearIntegerPixel(x) || !isNearIntegerPixel(y)) return false
      val left = x.roundToInt()
      val top = y.roundToInt()
      out.set(left, top, left + (dimensions shr 32).toInt(), top + dimensions.toInt())
      return !out.isEmpty
    }
  }

  fun matches(args: Array<Any?>): Boolean {
    if (args.size <= abi.defaultIndex) return false
    if (args[abi.modifierIndex] == null || args[abi.onStateIndex] != null ||
      abi.hasDefaultFlag(args, abi.modifierIndex) || !abi.hasDefaultFlag(args, abi.onStateIndex)
    ) return false
    val requestOptions = options.invoke(args[abi.requestIndex])
    return contentScale.invoke(requestOptions) == crop && alignment.invoke(requestOptions) == topCenter &&
      alpha.invoke(requestOptions) == 1f && colorFilter.invoke(requestOptions) == null
  }

  private class Drawing(private val loader: ClassLoader, modifier: Class<*>, callback: Class<*>) {
    private fun type(name: String) = Class.forName(name, false, loader)
    private val mutableState = type("androidx.compose.runtime.MutableState")
    private val factory = type("androidx.compose.runtime.SnapshotStateKt")
    private val policy = factory.getMethod("structuralEqualityPolicy").invoke(null)
    private val create = factory.getMethod(
      "mutableStateOf", Any::class.java, type("androidx.compose.runtime.SnapshotMutationPolicy"),
    )
    val getValue: Method = mutableState.getMethod("getValue")
    val setValue: Method = mutableState.getMethod("setValue", Any::class.java)
    val drawWithContent: Method = type("androidx.compose.ui.draw.DrawModifierKt")
      .getMethod("drawWithContent", modifier, callback)
    val drawContent: Method = type("androidx.compose.ui.graphics.drawscope.ContentDrawScope")
      .getMethod("drawContent")
    private val getDrawContext = type("androidx.compose.ui.graphics.drawscope.DrawScope")
      .getMethod("getDrawContext")
    private val getCanvas = getDrawContext.returnType.getMethod("getCanvas")
    private val getNativeCanvas = type("androidx.compose.ui.graphics.AndroidCanvas_androidKt")
      .getMethod("getNativeCanvas", getCanvas.returnType)

    fun newState(): Any = checkNotNull(create.invoke(null, null, policy))

    fun canvas(scope: Any): Canvas =
      getNativeCanvas.invoke(null, getCanvas.invoke(getDrawContext.invoke(scope))) as Canvas
  }

  private fun callback(action: (Any?) -> Unit): Any {
    val type = image.parameterTypes[abi.onStateIndex]
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
      when (method.name) {
        "invoke" -> {
          runCatching { action(args?.singleOrNull()) }
          unit
        }
        "equals" -> self === args?.singleOrNull()
        "hashCode" -> System.identityHashCode(self)
        "toString" -> "KeiHome footer observer"
        else -> null
      }
    }
  }

  companion object {
    private const val LOADER = "kntr.base.imageloader"
    private const val LAYOUT = "androidx.compose.ui.layout"
    private const val IMAGE = "$LOADER.BiliImageKt"

    private fun isHomeFooterLambda(data: MethodData, requireBottomCenter: Boolean = false): Boolean {
      if (data.name != "invoke" || data.paramTypeNames.size != 3) return false
      val invokes = data.invokes
      if (invokes.none { it.className == IMAGE && it.name == "BiliImage" }) return false
      if (invokes.none { it.className == "$LOADER.ImageRequest" && it.name == "contentScale" }) return false
      if (invokes.none { it.className == "$LOADER.ImageRequest" && it.name == "align" }) return false
      if (invokes.none { it.className == "$LAYOUT.ContentScale\$Companion" && it.name == "getCrop" }) return false
      if (invokes.none { it.className == "androidx.compose.ui.Alignment\$Companion" && it.name == "getTopCenter" }) return false
      if (requireBottomCenter && invokes.none {
          it.className == "androidx.compose.ui.Alignment\$Companion" && it.name == "getBottomCenter"
        }
      ) return false
      return data.declaredClass?.methods.orEmpty().filter { it.name == "<init>" }.any { constructor ->
        constructor.callers.any { parent ->
          parent.invokes.any {
            it.className == "androidx.compose.foundation.layout.BoxWithConstraintsKt" &&
              it.name == "BoxWithConstraints"
          }
        }
      }
    }

    fun resolve(dex: DexKitBridge, loader: ClassLoader): BilibiliComposeFooter {
      // Tier 1: Compose source-trace anchor (immune to localized A11y wording edits).
      val tier1 = dex.findMethod {
        matcher {
          name("invoke")
          paramTypes(Any::class.java, Any::class.java, Any::class.java)
          usingStrings("HomeBottomTabContainer")
        }
      }.filter { isHomeFooterLambda(it) }

      // Tier 2: Existing A11y string anchor (survives builds that strip Compose trace strings).
      val tier2 = if (tier1.size == 1) tier1 else dex.findMethod {
        matcher {
          name("invoke")
          paramTypes(Any::class.java, Any::class.java, Any::class.java)
          usingEqStrings("切换底部导航页")
        }
      }.filter { isHomeFooterLambda(it) }

      // Tier 3: Pure structural fingerprint (survives stripping both trace and A11y strings).
      val candidates = if (tier2.size == 1) tier2 else dex.findMethod {
        matcher {
          name("invoke")
          paramTypes(Any::class.java, Any::class.java, Any::class.java)
          addInvoke {
            declaredClass = IMAGE
            name = "BiliImage"
          }
          addInvoke {
            declaredClass = "$LOADER.ImageRequest"
            name = "contentScale"
          }
          addInvoke {
            declaredClass = "$LOADER.ImageRequest"
            name = "align"
          }
          addInvoke {
            declaredClass = "androidx.compose.ui.Alignment\$Companion"
            name = "getBottomCenter"
          }
        }
      }.filter { isHomeFooterLambda(it, requireBottomCenter = true) }

      val footer = candidates.single()
      val image = footer.invokes.filter {
        it.className == IMAGE && it.name == "BiliImage"
      }.distinctBy { it.descriptor }.single()
      return bind(loader, footer.getMethodInstance(loader), image.getMethodInstance(loader))
    }

    fun bind(loader: ClassLoader, footer: Method, image: Method): BilibiliComposeFooter {
      require(footer.name == "invoke" && footer.parameterTypes.toList() == List(3) { Any::class.java })
      require(image.declaringClass.name == IMAGE && image.name == "BiliImage") {
        "Unrecognized Compose image owner"
      }
      val abi = requireNotNull(parseBiliImageAbi(image.parameterTypes.map { it.name })) {
        "Unrecognized Compose image ABI"
      }
      fun type(name: String) = Class.forName(name, false, loader)
      fun companion(owner: String, getter: String): Any {
        val instance = type(owner).getField("Companion").get(null)
        return checkNotNull(instance.javaClass.getMethod(getter).invoke(instance))
      }
      val options = image.parameterTypes[abi.requestIndex].getMethod("getOptions")
      val coordinates = type("$LAYOUT.LayoutCoordinates")
      val source = type("$LOADER.ImageState\$Success").getMethod("getSource")
      val bitmap = type("$LOADER.StaticImage").getMethod("getBitmap")
      return BilibiliComposeFooter(
        footer, image, abi, options,
        options.returnType.getMethod("getContentScale"), options.returnType.getMethod("getAlign"),
        options.returnType.methods.single {
          it.name.startsWith("getAlpha-") && it.parameterCount == 0 && it.returnType == Float::class.javaPrimitiveType
        }, options.returnType.getMethod("getColorFilter"),
        companion("$LAYOUT.ContentScale", "getCrop"),
        companion("androidx.compose.ui.Alignment", "getTopCenter"),
        type("$LAYOUT.OnGloballyPositionedModifierKt").getMethod(
          "onGloballyPositioned", image.parameterTypes[abi.modifierIndex], image.parameterTypes[abi.onStateIndex],
        ),
        coordinates.getMethod("isAttached"),
        type("$LAYOUT.LayoutCoordinatesKt").getMethod("positionInWindow", coordinates),
        coordinates.methods.single { it.name.startsWith("getSize-") && it.parameterCount == 0 },
        source, bitmap, type("androidx.compose.ui.graphics.AndroidImageBitmap_androidKt")
          .getMethod("asAndroidBitmap", bitmap.returnType),
        Drawing(loader, image.parameterTypes[abi.modifierIndex], image.parameterTypes[abi.onStateIndex]),
        checkNotNull(type("kotlin.Unit").getField("INSTANCE").get(null)),
      )
    }
  }
}
