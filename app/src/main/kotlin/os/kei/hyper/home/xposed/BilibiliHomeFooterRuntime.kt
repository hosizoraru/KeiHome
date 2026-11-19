package os.kei.hyper.home.xposed

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.NavigationBackdropPreferences
import os.kei.hyper.home.data.BILIBILI_PACKAGE
import os.kei.hyper.home.status.BilibiliArtworkInbox
import os.kei.hyper.home.status.BilibiliArtworkOutcome
import os.kei.hyper.home.status.BilibiliInstallation

/** Owns only the extra drawing in Bilibili's main window. */
internal class BilibiliHomeFooterRuntime(
  private val xposed: XposedInterface,
  private val home: Class<out Activity>,
  private val loader: ClassLoader,
  apk: String,
) {
  private val main = Handler(Looper.getMainLooper())
  private var preferences: SharedPreferences? = null
  private var session: FooterSession? = null
  @Volatile private var retired = false
  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
    main.post { if (!retired) session?.updateSelection() }
  }

  private var composing: FooterSession? = null
  private var composeProfile: BilibiliComposeFooter? = null
  private var composeScope: java.lang.ref.WeakReference<Any>? = null
  private var matchedImages = 0
  private val reporter = BilibiliArtworkReporter(apk)

  fun install(compose: BilibiliComposeFooter?, oldHooks: List<XposedInterface.HookHandle>) {
    fun hook(id: String, method: Method, callback: XposedInterface.Hooker) {
      installOrReplaceIconHook(xposed, findMatchingIconHook(oldHooks, id, method),
        id, method, callback).first
    }
    val resume = home.getDeclaredMethod("onResume")
    hook(HOOK, resume) { chain ->
      chain.proceed().also {
        val activity = chain.thisObject as? Activity
        if (activity != null && home.isInstance(activity)) attachSafely(activity)
      }
    }
    if (compose != null) try {
      composeProfile = compose
      hook("$HOOK.compose", compose.drawFooter) { chain ->
        if (Looper.myLooper() != main.looper || retired || composing != null) return@hook chain.proceed()
        val current = session?.takeIf { it.observesCompose } ?: return@hook chain.proceed()
        composing = current
        matchedImages = 0
        try {
          chain.proceed()
        } finally {
          composing = null
          if (matchedImages > 1) current.releaseCompose()
        }
      }
      hook("$HOOK.image", compose.image) { chain ->
        val current = composing
        val observed = if (Looper.myLooper() == main.looper && !retired && current != null) {
          runCatching {
            val args = chain.args.toTypedArray()
            if (!compose.matches(args) || ++matchedImages != 1) null
            else {
              runCatching { compose.observeScope(args[10]) }.getOrNull()?.let {
                if (composeScope?.get() !== it) composeScope = java.lang.ref.WeakReference(it)
              }
              current.observeCompose(compose, args)
            }
          }.onFailure {
            current.releaseCompose()
            Log.w(TAG, "Compose footer observation unavailable", it)
          }.getOrNull()
        } else null
        if (observed == null) chain.proceed() else chain.proceed(observed)
      }
      Log.i(TAG, "Resolved Compose footer image in ${compose.drawFooter.declaringClass.name}")
    } catch (error: Exception) {
      xposed.log(Log.INFO, TAG, "Compose footer profile unavailable", error)
    } catch (error: LinkageError) {
      xposed.log(Log.INFO, TAG, "Compose footer linkage unavailable", error)
    }
  }

  /** API 102 does not replay onResume. Adopt the visible host decor after a generation change. */
  fun restoreVisibleHome(previous: Array<*>?) {
    main.post {
      if (retired) return@post
      val prior = (previous?.getOrNull(2) as? java.lang.ref.WeakReference<*>)?.get() as? Activity
      val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
      val activity = prior?.takeIf { home.isInstance(it) && it.window.peekDecorView() in roots }
        ?: roots.firstNotNullOfOrNull { root ->
          if (!root.isAttachedToWindow || !root.isShown) return@firstNotNullOfOrNull null
          // DecorContext can wrap the application rather than the owner. PhoneWindow keeps
          // the activity token in the top-level layout params even behind the system shade.
          val token = (root.layoutParams as? android.view.WindowManager.LayoutParams)?.token
          val owner = runCatching {
            val thread = Class.forName("android.app.ActivityThread")
            thread.getMethod("getActivity", android.os.IBinder::class.java)
              .invoke(thread.getMethod("currentActivityThread").invoke(null), token) as? Activity
          }.getOrNull()
          val content = root.findViewById<android.view.ViewGroup>(android.R.id.content)
          listOfNotNull(owner, contextActivity(root.context), content?.getChildAt(0)?.context?.let(::contextActivity))
            .firstOrNull { home.isInstance(it) && it.window.peekDecorView() === root }
        }
      if (activity != null) {
        attachSafely(activity)
        val scope = (previous?.getOrNull(3) as? java.lang.ref.WeakReference<*>)?.get()
        if (scope != null && activity === prior) runCatching { composeProfile?.refreshScope(scope) }
        if (composeProfile != null) session?.rebindExistingFooter()
      }
    }
  }

  private fun contextActivity(start: Context): Activity? {
    var context = start
    while (context is android.content.ContextWrapper) {
      if (context is Activity) return context
      val base = context.baseContext ?: return null
      if (base === context) return null
      context = base
    }
    return null
  }

  private fun attachSafely(activity: Activity) {
    if (retired || activity.packageName != PACKAGE || activity.isDestroyed || activity.isFinishing) return
    if (session?.activity === activity) {
      session?.repeatReceipt()
      return
    }
    try {
      val source = preferences ?: xposed.getRemotePreferences(NavigationBackdropPreferences.GROUP).also {
        it.registerOnSharedPreferenceChangeListener(listener)
        preferences = it
      }
      session?.close()
      composeScope = null
      session = FooterSession(activity, source, { outcome ->
        if (!retired) reporter.report(activity.applicationContext, outcome)
      }) { session = null }
      Log.i(TAG, "Attached to home window")
    } catch (failure: Exception) {
      xposed.log(Log.WARN, TAG, "Home footer unavailable; retaining the app's drawing", failure)
    }
  }

  fun retire(publish: (ClassLoader, Array<Any?>) -> Unit): Boolean {
    val cleanup = FutureTask {
      retired = true
      val handoff = arrayOf<Any?>(HANDOFF, null, java.lang.ref.WeakReference(session?.activity), composeScope)
      main.removeCallbacksAndMessages(null)
      preferences?.unregisterOnSharedPreferenceChangeListener(listener)
      preferences = null
      session?.close()
      composeScope = null
      handoff
    }
    if (Looper.myLooper() == main.looper) cleanup.run()
    else if (!main.postAtFrontOfQueue(cleanup)) return false
    val handoff = try {
      cleanup.get(6, TimeUnit.SECONDS)
    } catch (_: Exception) {
      cleanup.cancel(false)
      main.removeCallbacks(cleanup)
      return false
    }
    publish(loader, handoff)
    return true
  }

  private class FooterSession(
    val activity: Activity,
    private val preferences: SharedPreferences,
    private val report: (BilibiliArtworkOutcome) -> Unit,
    private val onClose: () -> Unit,
  ) : ViewTreeObserver.OnPreDrawListener, Application.ActivityLifecycleCallbacks {
    private val decor = activity.window.decorView
    private val backgroundId = activity.resources.getIdentifier("tab_background", "id", PACKAGE)
    private val overlay = FooterOverlay({ setOutcome(BilibiliArtworkOutcome.DRAWN) }) {
      // Defer removal until decor has finished iterating its drawables.
      decor.post { failDrawing() }
    }
    private val sampling = BilibiliFooterSampling(activity) {
      cancelFallback()
      setOutcome(BilibiliArtworkOutcome.SOLID)
    }
    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
      if (focused) decor.invalidate() else sampling.clear()
    }
    private val sourcePosition = IntArray(2)
    private val decorPosition = IntArray(2)
    private val visibleRect = Rect()
    private val artworkRect = RectF()
    private val imageMatrix = Matrix()
    private val legacyMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private var legacyPlacement: LegacyPlacement? = null
    private var observing = false
    private var started = true
    private var image: ImageView? = null
    private var binding: BilibiliComposeFooter.Binding? = null
    private var composeArtwork: BitmapDrawable? = null
    private var closed = false
    private var active = false
    private var drawingFailed = false
    private var lastDecision = ""
    private var outcome: BilibiliArtworkOutcome? = null
    private var fallbackPosted = false
    private val fallback = Runnable {
      fallbackPosted = false
      if (active && started) setOutcome(BilibiliArtworkOutcome.FALLBACK)
    }

    private fun cancelFallback() {
      if (!fallbackPosted) return
      decor.removeCallbacks(fallback)
      fallbackPosted = false
    }

    private fun setOutcome(value: BilibiliArtworkOutcome) {
      cancelFallback()
      if (outcome == value) return
      outcome = value
      report(value)
    }

    fun repeatReceipt() { outcome?.takeIf { active }?.let(report) }

    init {
      activity.registerActivityLifecycleCallbacks(this)
      updateSelection()
    }

    fun updateSelection() {
      if (closed) return
      val wasActive = active
      active = runCatching {
        NavigationBackdropPreferences.read(preferences).also { choice ->
          sampling.handleSampling = !choice.glassHandle
        }.includesBilibiliArtwork(Process.myUid())
      }.getOrDefault(false)
      if (wasActive != active) setOutcome(BilibiliArtworkOutcome.WAITING)
      Log.d(TAG, "Home artwork enabled=$active")
      if (active && started && !observing && !drawingFailed) {
        decor.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        decor.viewTreeObserver.addOnPreDrawListener(this)
        observing = true
        decor.invalidate()
      } else if (!active) {
        stopDrawing()
        cancelFallback()
        releaseCompose()
      }
    }

    val observesCompose: Boolean get() = active && !closed && !drawingFailed

    fun rebindExistingFooter() {
      if (!observesCompose) return
      // A pre-handoff module (or a footer without an image) has no restart scope.
      // Give Compose its normal invalidate pass first, then rebind only Bilibili's tab strip.
      decor.postOnAnimation {
        decor.postOnAnimation rebind@{
          if (!observesCompose || binding != null || !decor.isAttachedToWindow) return@rebind
          val id = activity.resources.getIdentifier("tab_host", "id", PACKAGE)
          val tabs = decor.findViewById<View>(id) ?: return@rebind
          if (tabs.javaClass.name != "tv.danmaku.bili.home.components.bottomtab.WindowAwareComposeView" ||
            !tabs.isAttachedToWindow || !tabs.isShown) return@rebind
          runCatching {
            val owner = activity.classLoader.loadClass("androidx.compose.ui.platform.AbstractComposeView")
            val dispose = owner.getMethod("disposeComposition")
            val create = owner.getMethod("createComposition")
            if (owner.isInstance(tabs) && owner.getMethod("getHasComposition").invoke(tabs) == true) {
              dispose.invoke(tabs)
              create.invoke(tabs)
            }
          }.onFailure { Log.d(TAG, "Existing tab composition could not be rebound", it) }
        }
      }
      decor.invalidate()
    }

    fun observeCompose(profile: BilibiliComposeFooter, args: Array<Any?>): Array<Any?>? {
      if (!observesCompose) return null
      val request = args[0] ?: return null
      val observed = binding?.takeIf { it.request == request }
        ?: profile.Binding(request, decor::invalidate) { decor.post { failDrawing() } }.also {
          releaseCompose()
          binding = it
        }
      return observed.observe(args)
    }

    fun releaseCompose() {
      binding?.release()
      binding = null
      composeArtwork = null
      decor.invalidate()
    }

    private fun failDrawing() {
      if (closed || drawingFailed) return
      drawingFailed = true
      stopDrawing()
      releaseCompose()
      setOutcome(BilibiliArtworkOutcome.FALLBACK)
    }

    override fun onPreDraw(): Boolean {
      if (!closed) {
        try {
          updateArtwork()
        } catch (failure: Exception) {
          failDrawing()
          Log.w(TAG, "Home footer drawing stopped", failure)
        }
      }
      return true
    }

    private fun updateArtwork() {
      val insets = decor.rootWindowInsets ?: return clearArtwork()
      val nav = insets.getInsets(WindowInsets.Type.navigationBars())
      val reason = when {
        activity.isInMultiWindowMode || activity.isInPictureInPictureMode -> "Multi-window"
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 -> "Secure window"
        nav.bottom <= 0 || nav.left != 0 || nav.right != 0 -> "No bottom navigation area"
        insets.isVisible(WindowInsets.Type.ime()) -> "IME visible"
        insets.getInsets(WindowInsets.Type.tappableElement()).bottom != 0 -> "Tappable navigation area"
        insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom <= 0 -> "No mandatory gesture inset"
        else -> null
      }
      if (reason != null) {
        trace(reason)
        return clearArtwork()
      }

      val top = decor.height - nav.bottom
      decor.getLocationInWindow(decorPosition)
      val compose = binding
      val bitmap = compose?.bitmap
      if (compose?.wasDetached() == true) releaseCompose()
      if (compose === binding && compose != null && bitmap != null &&
        !bitmap.isRecycled && compose.bounds(visibleRect)
      ) {
        restoreLegacyArtwork()
        visibleRect.offset(-decorPosition[0], -decorPosition[1])
        if (visibleRect.left != 0 || visibleRect.right != decor.width ||
          visibleRect.bottom != top
        ) {
          trace("Compose footer placement=$visibleRect, navigation starts at $top")
          return clearArtwork()
        }

        val scale = visibleRect.width().toFloat() / bitmap.width
        // Crop would scale by height if width-fit left vertical space.
        if (bitmap.height * scale < visibleRect.height()) return clearArtwork()
        val imageTop = bottomAlignedFooterTop(decor.height, top, visibleRect.top - nav.bottom,
          bitmap.height * scale) ?: run {
          trace("Cannot fit complete Compose artwork in footer and gesture area")
          return clearArtwork()
        }
        val artwork = composeArtwork?.takeIf { it.bitmap === bitmap }
          ?: BitmapDrawable(decor.resources, bitmap).apply {
            setBounds(0, 0, bitmap.width, bitmap.height)
            isFilterBitmap = true
          }.also { composeArtwork = it }
        imageMatrix.setScale(scale, scale)
        imageMatrix.postTranslate(0f, imageTop)
        if (showArtwork(artwork, top)) {
          compose.showArtwork(artwork, scale, imageTop - visibleRect.top, visibleRect)
        }
        return
      }
      binding?.clearArtwork()
      composeArtwork = null
      val source = image?.takeIf { it.isAttachedToWindow && it.isShown }
        ?: (decor.findViewById<View>(backgroundId) as? ImageView).also { image = it }
        ?: run {
          trace("No active artwork (Compose binding=${compose != null}, bitmap=${bitmap != null})")
          return sampleFooter(top)
        }
      val artwork = source.drawable ?: return sampleFooter(top)
      if (artwork is Animatable || !artwork.isVisible || artwork.alpha == 0 ||
        !source.isShown || !source.getLocalVisibleRect(visibleRect)
      ) return clearArtwork()
      if (artwork is BitmapDrawable && artwork.bitmap.isRecycled) return clearArtwork()

      // An embedded or transformed tab strip has a different compositing contract.
      var ancestor: View = source
      var opacity = 1f
      while (ancestor !== decor) {
        if (!ancestor.matrix.isIdentity) return clearArtwork()
        opacity *= ancestor.alpha
        ancestor = ancestor.parent as? View ?: return clearArtwork()
      }
      val layerAlpha = Math.round(opacity * 255).coerceIn(0, 255)
      if (layerAlpha == 0) return clearArtwork()
      source.getLocationInWindow(sourcePosition)
      val x = sourcePosition[0] - decorPosition[0]
      val y = sourcePosition[1] - decorPosition[1]
      if (x + visibleRect.left != 0 || x + visibleRect.right != decor.width ||
        y + visibleRect.bottom != top
      ) return clearArtwork()

      val applied = legacyPlacement
      if (applied != null && (applied.view !== source || applied.artwork !== artwork ||
          applied.view.imageMatrix != applied.adjusted)
      ) {
        restoreLegacyArtwork()
      }
      val original = legacyPlacement?.original ?: source.imageMatrix
      if (source.scaleType != ImageView.ScaleType.MATRIX) return sampleFooter(top)
      val originX = x + source.paddingLeft
      val originY = y + source.paddingTop
      imageMatrix.set(original)
      imageMatrix.postTranslate(originX.toFloat(), originY.toFloat())
      artworkRect.set(artwork.bounds)
      if (!imageMatrix.mapRect(artworkRect)) return clearArtwork()
      original.getValues(matrixValues)
      val scale = matrixValues[Matrix.MSCALE_X]
      if (!scale.isFinite() || scale <= 0f || matrixValues[Matrix.MSCALE_Y] != scale ||
        matrixValues[Matrix.MSKEW_X] != 0f || matrixValues[Matrix.MSKEW_Y] != 0f ||
        matrixValues[Matrix.MPERSP_0] != 0f || matrixValues[Matrix.MPERSP_1] != 0f ||
        kotlin.math.abs(artworkRect.left) > 0.5f || kotlin.math.abs(artworkRect.right - decor.width) > 0.5f
      ) return clearArtwork()
      if (bottomAlignedFooterTop(decor.height, top, y + visibleRect.top, artworkRect.height()) == null) {
        trace("Cannot fit complete legacy artwork in footer and gesture area")
        return clearArtwork()
      }
      val delta = decor.height - artworkRect.bottom
      legacyMatrix.set(original)
      legacyMatrix.postTranslate(0f, delta)
      imageMatrix.postTranslate(0f, delta)
      if (!showArtwork(artwork, top, layerAlpha)) return
      if (source.imageMatrix != legacyMatrix) {
        legacyPlacement = LegacyPlacement(source, artwork, Matrix(original), Matrix(legacyMatrix))
        source.imageMatrix = legacyMatrix
        Log.i(TAG, "Aligned legacy footer artwork to window bottom, offset=$delta")
      }
    }

    private fun showArtwork(artwork: Drawable, top: Int, alpha: Int = 255): Boolean {
      artworkRect.set(artwork.bounds)
      if (!imageMatrix.mapRect(artworkRect) || artworkRect.left > 0.01f ||
        artworkRect.right + 0.01f < decor.width || artworkRect.top > top ||
        artworkRect.bottom + 0.01f < decor.height
      ) {
        trace("Artwork coverage=$artworkRect, window=${decor.width}x${decor.height}, inset top=$top")
        clearArtwork()
        return false
      }
      cancelFallback()

      val attach = overlay.artwork == null
      overlay.update(artwork, imageMatrix, decor.width, top, decor.height, alpha)
      if (attach) {
        lastDecision = ""
        decor.overlay.add(overlay)
        Log.i(TAG, "Drawing home artwork through ${decor.height - top}px navigation inset")
      }
      val generation = (artwork as? BitmapDrawable)?.bitmap?.generationId ?: 0
      val revision = ((System.identityHashCode(artwork).toLong() * 31 + generation) * 31 +
        imageMatrix.hashCode()) * 31 + alpha
      sampling.update(top, revision)
      return true
    }

    private fun sampleFooter(top: Int) {
      clearArtwork(trySolid = true)
      sampling.update(top, null)
    }

    private fun trace(decision: String) {
      if (decision == lastDecision) return
      lastDecision = decision
      Log.d(TAG, decision)
    }

    private fun clearArtwork(trySolid: Boolean = false) {
      if (!trySolid) sampling.clear()
      binding?.clearArtwork()
      restoreLegacyArtwork()
      if (!(trySolid && sampling.hasSolidColor) && active && started && outcome != BilibiliArtworkOutcome.FALLBACK && !fallbackPosted) {
        fallbackPosted = true
        decor.postDelayed(fallback, 500L)
      }
      if (overlay.artwork == null) return
      decor.overlay.remove(overlay)
      overlay.artwork = null
    }

    private fun restoreLegacyArtwork() {
      val placement = legacyPlacement ?: return
      legacyPlacement = null
      if (placement.view.scaleType == ImageView.ScaleType.MATRIX &&
        placement.view.imageMatrix == placement.adjusted
      ) placement.view.imageMatrix = placement.original
    }

    private fun stopObserving() {
      if (observing) {
        decor.viewTreeObserver.takeIf { it.isAlive }?.let {
          it.removeOnWindowFocusChangeListener(focus)
          it.removeOnPreDrawListener(this)
        }
        observing = false
      }
    }

    private fun stopDrawing() {
      stopObserving()
      clearArtwork()
      image = null
    }

    fun close() {
      if (closed) return
      closed = true
      stopDrawing()
      cancelFallback()
      if (active) setOutcome(BilibiliArtworkOutcome.WAITING)
      releaseCompose()
      activity.unregisterActivityLifecycleCallbacks(this)
      onClose()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) {
      started = true
      updateSelection()
    }
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
      started = false
      sampling.clear()
      cancelFallback()
      // Keep the last validated frame in its own decor for task transitions.
      // A start revalidates its geometry before the next draw.
      stopObserving()
      image = null
    }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { close() }
  }

  private data class LegacyPlacement(
    val view: ImageView, val artwork: Drawable, val original: Matrix, val adjusted: Matrix,
  )

  private class FooterOverlay(private val onDrawn: () -> Unit, private val onDrawFailure: () -> Unit) : Drawable() {
    var artwork: Drawable? = null
    private val matrix = Matrix()
    private var layerAlpha = 255
    private var failed = false

    fun update(source: Drawable, transform: Matrix, width: Int, top: Int, bottom: Int, alpha: Int) {
      if (artwork === source && matrix == transform && bounds.right == width &&
        bounds.top == top && bounds.bottom == bottom && layerAlpha == alpha
      ) return
      artwork = source
      matrix.set(transform)
      layerAlpha = alpha
      setBounds(0, top, width, bottom)
      invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
      val source = artwork?.takeUnless { failed } ?: return
      val count = canvas.save()
      try {
        canvas.clipRect(bounds)
        if (layerAlpha != 255) {
          canvas.saveLayerAlpha(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            layerAlpha,
          )
        }
        canvas.concat(matrix)
        source.draw(canvas)
        onDrawn()
      } catch (failure: RuntimeException) {
        failed = true
        Log.w(TAG, "Gesture artwork draw failed", failure)
        onDrawFailure()
      } finally {
        canvas.restoreToCount(count)
      }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
  }

  companion object {
    const val HANDOFF = "keihome.bilibili.window.v1"
    const val PACKAGE = BILIBILI_PACKAGE
    const val TAG = "KeiHomeBiliFooter"
    private const val HOOK = "keihome.bilibili.home.footer.v1"

    fun resolveHome(dex: DexKitBridge, loader: ClassLoader): Class<out Activity> {
      // These extras are the home tab-routing protocol, shared even when classes are renamed.
      val home = dex.findMethod {
        matcher {
          name("onNewIntent")
          paramTypes(android.content.Intent::class.java)
          returnType(Void.TYPE)
          usingEqStrings("bottom_tab_id", "bottom_tab_name")
        }
      }.single().getMethodInstance(loader).declaringClass.asSubclass(Activity::class.java)
      // AppComponentFactory can resolve an Activity through a delegated class loader.
      check(home.getDeclaredMethod("onResume").returnType == Void.TYPE) { "Unrecognized home lifecycle method" }
      return home
    }
  }
}

internal class BilibiliArtworkReporter(private val apk: String) {
  private val processToken = Binder()
  private val reports = CoroutineScope(Dispatchers.IO.limitedParallelism(1))

  fun report(context: Context, outcome: BilibiliArtworkOutcome) {
    reports.launch {
      try {
        val info = context.packageManager.getPackageInfo(BILIBILI_PACKAGE,
          android.content.pm.PackageManager.PackageInfoFlags.of(0))
        if (info.applicationInfo?.sourceDir != apk) return@launch
        context.contentResolver.call(BilibiliArtworkInbox.uri, BilibiliArtworkInbox.METHOD, null, Bundle().apply {
          putInt("module", BuildConfig.VERSION_CODE)
          putLong("version", info.longVersionCode)
          putLong("updated", info.lastUpdateTime)
          putBinder("process", processToken)
          putString("outcome", outcome.name)
        })
      } catch (failure: Exception) {
        Log.d(BilibiliHomeFooterRuntime.TAG, "Artwork status could not be reported", failure)
      }
    }
  }
}
