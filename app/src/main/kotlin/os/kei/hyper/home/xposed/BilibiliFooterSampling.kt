package os.kei.hyper.home.xposed

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowManager
import kotlin.math.roundToInt

/** Samples only this activity's surface. The separate SystemUI handle is never part of the input. */
internal class BilibiliFooterSampling(
  private val activity: Activity,
  private val footerIds: List<Int> = listOf("tab_host", "bottom_navigation").map {
    activity.resources.getIdentifier(it, "id", activity.packageName)
  }.filter { it != 0 },
  private val onSolid: () -> Unit,
) {
  private val window = activity.window
  private val decor = window.decorView
  private val main = Handler(Looper.getMainLooper())
  private val tint = BilibiliGestureTint(activity)
  private val colorLayer = ColorDrawable()
  private var footer: View? = null
  private val bounds = Rect()
  private val decorBounds = Rect()
  private var layerAttached = false
  var handleSampling = true
    set(value) {
      if (field == value) return
      clear()
      field = value
      decor.invalidate()
    }
  val hasSolidColor: Boolean get() = layerAttached
  private var revision: Long? = null
  private var width = 0
  private var top = 0
  private var height = 0
  private var epoch = 0L
  private var pending = false
  private var sampled = false
  private var nextRead = 0L
  private var originalDark: Boolean? = null
  private var appliedDark: Boolean? = null
  private val read = Runnable { copyPixels(epoch) }

  private fun ownsWindow(): Boolean {
    val insets = decor.rootWindowInsets ?: return false
    val nav = insets.getInsets(WindowInsets.Type.navigationBars())
    return decor.isHardwareAccelerated && decor.hasWindowFocus() && decor.isShown && !activity.isInMultiWindowMode && !activity.isInPictureInPictureMode &&
      window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 &&
      !insets.isVisible(WindowInsets.Type.ime()) && nav.left == 0 && nav.right == 0 && nav.bottom > 0 &&
      nav.bottom == height - top && insets.getInsets(WindowInsets.Type.tappableElement()).bottom == 0 &&
      insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom > 0 && decor.height == height && decor.width == width
  }

  private fun hasFooter(): Boolean {
    val view = footer?.takeIf { it.isShown && it.isAttachedToWindow } ?: footerIds.firstNotNullOfOrNull {
      decor.findViewById<View>(it)?.takeIf(View::isShown)
    }.also { footer = it } ?: return false
    return view.getGlobalVisibleRect(bounds) && decor.getGlobalVisibleRect(decorBounds) &&
      kotlin.math.abs(bounds.left - decorBounds.left) <= 1 &&
      kotlin.math.abs(bounds.right - decorBounds.right) <= 1 &&
      kotlin.math.abs(bounds.bottom - (decorBounds.top + top)) <= 1 &&
      bounds.height() > height - top
  }

  /** Called before draw. Artwork is static until its transform or source changes. A flat tab bar can repaint in place. */
  fun update(insetTop: Int, artworkRevision: Long?) {
    if (!handleSampling && artworkRevision != null) return clear()
    if (width != decor.width || height != decor.height || top != insetTop || revision != artworkRevision) {
      invalidateRequests()
      removeColor()
      width = decor.width
      height = decor.height
      top = insetTop
      revision = artworkRevision
    }
    if (!ownsWindow() || (revision == null && !hasFooter())) return clear()
    if (sampled && revision != null) {
      // Target-SDK 35 edge-to-edge controllers (e.g. Bilibili's NavigationBarBackgroundController)
      // can overwrite APPEARANCE_LIGHT_NAVIGATION_BARS on tab or theme updates without changing
      // the static footer artwork. Re-assert our sampled polarity without re-running PixelCopy.
      reassertSampledAppearance()
      return
    }
    if (pending) return
    pending = true
    val token = epoch
    decor.viewTreeObserver.registerFrameCommitCallback {
      if (token == epoch) main.postAtTime(read, maxOf(SystemClock.uptimeMillis(), nextRead))
    }
  }

  private fun reassertSampledAppearance() {
    if (!handleSampling) return
    val desiredDark = appliedDark ?: return
    val controller = window.insetsController ?: return
    val current = controller.systemBarsAppearance and APPEARANCE_LIGHT_NAVIGATION_BARS != 0
    if (current != desiredDark) {
      originalDark = current
      controller.setSystemBarsAppearance(
        if (desiredDark) APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
        APPEARANCE_LIGHT_NAVIGATION_BARS,
      )
    }
  }

  private fun copyPixels(token: Long) {
    if (!ownsWindow() || (revision == null && !hasFooter())) return clear()
    val density = decor.resources.displayMetrics.density
    val halfWidth = (70 * density).roundToInt().coerceIn(1, width / 2)
    val band = (2 * density).roundToInt().coerceAtLeast(1)
    val mid = (top + height) / 2
    val region = if (revision != null) Rect(width / 2 - halfWidth, maxOf(top, mid - band),
      width / 2 + halfWidth, minOf(height, mid + band)) else Rect(0, maxOf(0, top - 3 * band), width, top)
    val bitmap = Bitmap.createBitmap(33, 3, Bitmap.Config.ARGB_8888)
    nextRead = SystemClock.uptimeMillis() + 650
    try {
      PixelCopy.request(window, region, bitmap, { result ->
        try {
          if (token != epoch) return@request
          pending = false
          if (!ownsWindow() || (revision == null && !hasFooter())) return@request clear()
          if (result != PixelCopy.SUCCESS) {
            removeColor()
            releaseHandle()
            return@request
          }
          val pixels = IntArray(bitmap.width * bitmap.height)
          bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
          if (revision == null) {
            val color = FooterPalette.solid(pixels)
            if (color == null) {
              removeColor()
              releaseHandle()
              return@request
            }
            if (colorLayer.color != color) colorLayer.color = color
            if (!layerAttached) {
              colorLayer.setBounds(0, top, width, height)
              decor.overlay.add(colorLayer)
              layerAttached = true
              Log.d(BilibiliHomeFooterRuntime.TAG, "Matched solid footer #%06x".format(color and 0xffffff))
            }
            onSolid()
          }
          if (!handleSampling) return@request
          val paint = FooterPalette.handlePaint(pixels, appliedDark) ?: return@request releaseHandle()
          val dark = (paint and 0xffffff) == 0
          val controller = window.insetsController ?: return@request
          val current = controller.systemBarsAppearance and APPEARANCE_LIGHT_NAVIGATION_BARS != 0
          if (current != dark) {
            if (originalDark == null || current != appliedDark) originalDark = current
            controller.setSystemBarsAppearance(if (dark) APPEARANCE_LIGHT_NAVIGATION_BARS else 0, APPEARANCE_LIGHT_NAVIGATION_BARS)
          }
          if (appliedDark != dark || !sampled) Log.d(BilibiliHomeFooterRuntime.TAG, "Sampled gesture background, darkHandle=$dark")
          tint.publish(paint)
          appliedDark = dark
          sampled = true
        } finally { bitmap.recycle() }
      }, main)
    } catch (_: IllegalArgumentException) {
      pending = false
      bitmap.recycle()
      removeColor()
      releaseHandle()
    }
  }

  private fun removeColor() {
    if (layerAttached) decor.overlay.remove(colorLayer)
    layerAttached = false
  }

  private fun releaseHandle() {
    tint.publish(null)
    val original = originalDark
    val controller = window.insetsController
    if (original != null && controller != null &&
      (controller.systemBarsAppearance and APPEARANCE_LIGHT_NAVIGATION_BARS != 0) == appliedDark) {
      controller.setSystemBarsAppearance(if (original) APPEARANCE_LIGHT_NAVIGATION_BARS else 0, APPEARANCE_LIGHT_NAVIGATION_BARS)
    }
    appliedDark = null
    originalDark = null
  }

  private fun invalidateRequests() {
    epoch++
    main.removeCallbacks(read)
    pending = false
    sampled = false
    nextRead = 0
  }

  fun clear() {
    if (pending || sampled) invalidateRequests()
    removeColor()
    releaseHandle()
    footer = null
  }
}
