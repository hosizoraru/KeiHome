package os.kei.hyper.home.xposed

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.view.View
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Direct provenance/binding tests, not evidence of a live Xposed/SystemUI installation. */
@RunWith(AndroidJUnit4::class)
class SystemUiAppIconBindingsDeviceTest {
  private fun onMain(block: () -> Unit) =
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

  @Test fun authenticatedBitmapSurvivesWrappersButUnrelatedCopiesClearTheBinding() = onMain {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    try {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val bindings = SystemUiAppIconBindings()
      val view = ImageView(context)
      bindings.remember(bitmap, "os.kei")
      assertEquals("os.kei", requireNotNull(bindings.bind(view, bitmap)).name)
      assertEquals("os.kei", requireNotNull(bindings.bind(view,
        android.graphics.drawable.BitmapDrawable(context.resources, bitmap))).name)
      val icon = Icon.createWithBitmap(bitmap)
      bindings.rememberCreated(bitmap, icon)
      val drawable = requireNotNull(icon.loadDrawable(context))
      bindings.rememberLoaded(icon, drawable)
      val bound = requireNotNull(bindings.bind(view, drawable))
      assertEquals("os.kei", bound.name)
      assertNull(bindings.bind(view, copy))
      assertNull(bindings.current(bound))
      assertNull(bindings.bind(view, android.graphics.drawable.BitmapDrawable(context.resources, copy)))
      val unrelated = Icon.createWithBitmap(copy)
      bindings.rememberCreated(copy, unrelated)
      assertNull(bindings.bind(view, unrelated))
    } finally { bitmap.recycle(); copy.recycle() }
  }

  @Test fun frameworkApplicationInfoFallbackRequiresMatchingUserUidAndCustomOwner() {
    fun info(name: String = "os.kei", uid: Int = 10123) = ApplicationInfo().apply {
      packageName = name
      this.uid = uid
    }
    assertEquals("os.kei", SystemUiAppIconIdentity.name(info(), 0, "os.kei"))
    assertEquals("os.kei", SystemUiAppIconIdentity.name(info(), 0, null))
    assertEquals("os.kei", SystemUiAppIconIdentity.name(info(uid = 110123), 1, "os.kei"))
    assertNull(SystemUiAppIconIdentity.name(null, 0, "os.kei"))
    assertNull(SystemUiAppIconIdentity.name(info(), 1, "os.kei"))
    assertNull(SystemUiAppIconIdentity.name(info(uid = 110123), 0, "os.kei"))
    assertNull(SystemUiAppIconIdentity.name(info(uid = -1), 0, "os.kei"))
    assertNull(SystemUiAppIconIdentity.name(info(uid = 0), 0, "os.kei"))
    assertNull(SystemUiAppIconIdentity.name(info(uid = 100001), 1, "os.kei"))
    assertNull(SystemUiAppIconIdentity.name(info(), 0, "another.app"))
    assertNull(SystemUiAppIconIdentity.name(info(name = "../outside"), 0, null))
    assertNull(SystemUiAppIconIdentity.name(info(name = "a".repeat(241)), 0, null))
  }

  @Test fun earlyHeaderReturnCanBeMarkedWithoutBitmapFrameAndOtherIconsRemainUnknown() = onMain {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    try {
      val info = ApplicationInfo().apply { packageName = "os.kei"; uid = 10123 }
      val name = requireNotNull(SystemUiAppIconIdentity.name(info, 0, "os.kei"))
      val returnedHeader = Icon.createWithBitmap(bitmap)
      val unrelatedAvatar = Icon.createWithBitmap(bitmap)
      val bindings = SystemUiAppIconBindings()
      bindings.remember(returnedHeader, name)
      val view = ImageView(InstrumentationRegistry.getInstrumentation().targetContext)
      assertNull(bindings.bind(view, unrelatedAvatar))
      assertEquals("os.kei", requireNotNull(bindings.bind(view, returnedHeader)).name)
    } finally { bitmap.recycle() }
  }

  @Test fun onlyTheExactAuthenticatedIconInstanceCanAssociateAView() = onMain {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    try {
      val known = Icon.createWithBitmap(bitmap)
      val copy = Icon.createWithBitmap(bitmap)
      val view = ImageView(InstrumentationRegistry.getInstrumentation().targetContext)
      val bindings = SystemUiAppIconBindings()
      bindings.remember(known, "os.kei")
      assertNull(bindings.bind(view, copy))
      val bound = requireNotNull(bindings.bind(view, known))
      assertSame(view, bindings.current(bound))
      assertEquals("os.kei", bound.name)
      assertNull(bindings.bind(view, Icon.createWithResource("android", android.R.drawable.ic_delete)))
      assertNull(bindings.current(bound))
    } finally { bitmap.recycle() }
  }

  @Test fun recycledOrUnknownBindingsInvalidatePendingAsyncWork() = onMain {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    try {
      val icon = Icon.createWithBitmap(bitmap)
      val view = ImageView(InstrumentationRegistry.getInstrumentation().targetContext)
      val bindings = SystemUiAppIconBindings()
      bindings.remember(icon, "os.kei")
      val old = requireNotNull(bindings.bind(view, icon))
      var nativeRuns = 0
      var readyRuns = 0
      val pending = bindings.afterNative(old, Runnable { nativeRuns++ }) { readyRuns++ }
      val fresh = requireNotNull(bindings.bind(view, icon))
      pending.run()
      assertEquals(0, nativeRuns)
      assertEquals(0, readyRuns)
      assertSame(view, bindings.current(fresh))
      bindings.clear(view)
      assertNull(bindings.current(fresh))
      assertTrue(bindings.snapshot().isEmpty())
    } finally { bitmap.recycle() }
  }

  @Test fun headerIconDrawableLoadingPreservesIdentityWithoutClaimingUnrelatedDrawables() = onMain {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    try {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val icon = Icon.createWithBitmap(bitmap)
      val unknown = Icon.createWithBitmap(bitmap)
      val bindings = SystemUiAppIconBindings()
      bindings.remember(icon, "os.kei")
      val loaded = requireNotNull(icon.loadDrawable(context))
      val unrelated = requireNotNull(unknown.loadDrawable(context))
      bindings.rememberLoaded(unknown, unrelated)
      val view = ImageView(context)
      assertNull(bindings.bind(view, unrelated))
      bindings.rememberLoaded(icon, loaded)
      assertEquals("os.kei", requireNotNull(bindings.bind(view, loaded)).name)
      assertNull(bindings.bind(view, requireNotNull(icon.loadDrawable(context))))
    } finally { bitmap.recycle() }
  }

  @Test fun asyncRefreshRunsAfterNativeBindingAndKeepsHiddenShadeSlots() = onMain {
    val native = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    val themed = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
    try {
      val icon = Icon.createWithBitmap(native)
      val view = ImageView(InstrumentationRegistry.getInstrumentation().targetContext).apply {
        visibility = View.GONE
      }
      val bindings = SystemUiAppIconBindings()
      bindings.remember(icon, "os.kei")
      val bound = requireNotNull(bindings.bind(view, icon))
      val events = mutableListOf<String>()
      bindings.afterNative(bound, Runnable {
        events += "native"
        view.setImageIcon(icon)
      }) {
        events += "ready"
        bindings.current(it)?.setImageBitmap(themed)
      }.run()
      assertEquals(listOf("native", "ready"), events)
      assertEquals(1, bindings.snapshot().size)
      assertSame(themed, (view.drawable as android.graphics.drawable.BitmapDrawable).bitmap)
      view.setImageDrawable(null)
    } finally { native.recycle(); themed.recycle() }
  }

  @Test fun nativeHeaderBoundaryAcceptsItsCacheAndPreservesCustomAndHybridImages() = onMain {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    try {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val cached = requireNotNull(Icon.createWithBitmap(bitmap).loadDrawable(context))
      val custom = requireNotNull(Icon.createWithBitmap(bitmap).loadDrawable(context))
      val view = ImageView(context)
      val bindings = SystemUiAppIconBindings()
      view.setImageDrawable(cached)
      val old = requireNotNull(bindings.bindNative(view, cached, "os.kei"))
      assertSame(view, bindings.current(old))
      view.setImageDrawable(custom)
      assertNull(bindings.bindNative(view, cached, "os.kei"))
      assertSame(custom, view.drawable)
      assertNull(bindings.current(old))
    } finally { bitmap.recycle() }
  }

  @Test fun nativeRebindDuringAsyncCompletionCannotPublishTheOldAssociation() = onMain {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    try {
      val icon = Icon.createWithBitmap(bitmap)
      val view = ImageView(InstrumentationRegistry.getInstrumentation().targetContext)
      val bindings = SystemUiAppIconBindings()
      bindings.remember(icon, "os.kei")
      val bound = requireNotNull(bindings.bind(view, icon))
      var callbacks = 0
      bindings.afterNative(bound, Runnable { bindings.clear(view) }) { callbacks++ }.run()
      assertEquals(0, callbacks)
      assertNull(bindings.current(bound))
    } finally { bitmap.recycle() }
  }

  @Test fun tokenAndViewBoundsEvictOldIdentityAndClearRetiresPendingBindings() = onMain {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    try {
      val bindings = SystemUiAppIconBindings()
      val icons = (0..96).map { Icon.createWithBitmap(bitmap) }
      icons.forEachIndexed { index, icon -> bindings.remember(icon, "os.kei.app$index") }
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      assertNull(bindings.bind(ImageView(context), icons.first()))
      val views = (0..64).map { ImageView(context) }
      val records = views.map { requireNotNull(bindings.bind(it, icons.last())) }
      assertEquals(64, bindings.snapshot().size)
      assertNull(bindings.current(records.first()))
      assertSame(views.last(), bindings.current(records.last()))
      bindings.clear()
      assertTrue(bindings.snapshot().isEmpty())
      assertNull(bindings.current(records.last()))
      assertNull(bindings.bind(views.last(), icons.last()))
    } finally { bitmap.recycle() }
  }
}
