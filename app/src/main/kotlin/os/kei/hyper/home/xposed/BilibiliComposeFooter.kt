package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Resolves the home footer's loaded image, placement and draw phase. */
internal class BilibiliComposeFooter private constructor(
  val drawFooter: Method,
  val image: Method,
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
      val observer = positioned.invoke(null, args[1], placement)
      copy[1] = drawing.drawWithContent.invoke(null, observer, redrawArtwork)
      copy[4] = onState
      // Let Compose recompute the dirty bits for the two observed arguments.
      copy[11] = (args[11] as Int) and (0x70 or 0xe000).inv()
      copy[12] = (args[12] as Int) and (1 shl 4).inv()
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
      if (!x.isFinite() || !y.isFinite() || x % 1f != 0f || y % 1f != 0f) return false
      out.set(x.toInt(), y.toInt(), x.toInt() + (dimensions shr 32).toInt(),
        y.toInt() + dimensions.toInt())
      return !out.isEmpty
    }
  }

  fun matches(args: Array<Any?>): Boolean {
    val defaults = args[12] as Int
    if (args[1] == null || args[4] != null ||
      defaults and (1 shl 1) != 0 || defaults and (1 shl 4) == 0
    ) return false
    val requestOptions = options.invoke(args[0])
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
    val type = image.parameterTypes[4]
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

    fun resolve(apk: String, loader: ClassLoader, cacheDir: File?): BilibiliComposeFooter {
      val methods = SystemUiMaterialDexKit.open(apk, cacheDir).use { dex ->
        dex.setThreadNum(1)
        val candidates = dex.findMethod {
          matcher { usingStrings("tv.danmaku.bili.khome.widget.bottomtab.HomeBottomTabContainer.") }
        }.filter { data ->
          data.name == "invoke" && data.paramTypeNames.size == 3 &&
            data.invokes.any { it.className == IMAGE && it.name == "BiliImage" } &&
            data.invokes.any { it.className == "$LOADER.ImageRequest" && it.name == "contentScale" } &&
            data.invokes.any { it.className == "$LOADER.ImageRequest" && it.name == "align" }
        }
        val footer = candidates.single()
        val image = footer.invokes.filter {
          it.className == IMAGE && it.name == "BiliImage"
        }.distinctBy { it.descriptor }.single()
        check(image.paramTypeNames == listOf(
          "$LOADER.ImmutableImageRequest", "androidx.compose.ui.Modifier", "java.lang.String",
          "androidx.lifecycle.Lifecycle", "kotlin.jvm.functions.Function1",
          "kotlin.jvm.functions.Function1", "kotlin.jvm.functions.Function1",
          "kotlin.jvm.functions.Function1", "kotlin.jvm.functions.Function2",
          "kotlin.jvm.functions.Function4", "androidx.compose.runtime.Composer", "int", "int",
        )) { "Unrecognized Compose image ABI" }
        footer.getMethodInstance(loader) to image.getMethodInstance(loader)
      }
      fun type(name: String) = Class.forName(name, false, loader)
      fun companion(owner: String, getter: String): Any {
        val instance = type(owner).getField("Companion").get(null)
        return checkNotNull(instance.javaClass.getMethod(getter).invoke(instance))
      }
      val options = methods.second.parameterTypes[0].getMethod("getOptions")
      val coordinates = type("$LAYOUT.LayoutCoordinates")
      val source = type("$LOADER.ImageState\$Success").getMethod("getSource")
      val bitmap = type("$LOADER.StaticImage").getMethod("getBitmap")
      return BilibiliComposeFooter(
        methods.first, methods.second, options,
        options.returnType.getMethod("getContentScale"), options.returnType.getMethod("getAlign"),
        options.returnType.methods.single {
          it.name.startsWith("getAlpha-") && it.parameterCount == 0 && it.returnType == Float::class.javaPrimitiveType
        }, options.returnType.getMethod("getColorFilter"),
        companion("$LAYOUT.ContentScale", "getCrop"),
        companion("androidx.compose.ui.Alignment", "getTopCenter"),
        type("$LAYOUT.OnGloballyPositionedModifierKt").getMethod(
          "onGloballyPositioned", methods.second.parameterTypes[1], methods.second.parameterTypes[4],
        ),
        coordinates.getMethod("isAttached"),
        type("$LAYOUT.LayoutCoordinatesKt").getMethod("positionInWindow", coordinates),
        coordinates.methods.single { it.name.startsWith("getSize-") && it.parameterCount == 0 },
        source, bitmap, type("androidx.compose.ui.graphics.AndroidImageBitmap_androidKt")
          .getMethod("asAndroidBitmap", bitmap.returnType),
        Drawing(loader, methods.second.parameterTypes[1], methods.second.parameterTypes[4]),
        checkNotNull(type("kotlin.Unit").getField("INSTANCE").get(null)),
      )
    }
  }
}
