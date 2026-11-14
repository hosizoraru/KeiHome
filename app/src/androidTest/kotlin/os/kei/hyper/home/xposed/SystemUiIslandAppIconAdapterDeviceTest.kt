package os.kei.hyper.home.xposed

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Direct adapter/provenance tests; not evidence of a live OEM hook or existing-island bootstrap. */
@RunWith(AndroidJUnit4::class)
class SystemUiIslandAppIconAdapterDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private fun info(name: String = "os.kei", uid: Int = 10123) = ApplicationInfo().apply {
    packageName = name
    this.uid = uid
  }
  private fun onMain(block: () -> Unit) =
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

  @Test fun hitReturnsSnapshotAndAuthenticatesOnlyTheExactAppDrawable() = onMain {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val bindings = SystemUiAppIconBindings()
    var nativeCalls = 0
    val lookups = ArrayList<String>()
    val adapter = SystemUiIslandAppIconAdapter(0, ThemeCompatibilityLifecycle(),
      { name -> lookups += name; bitmap }, bindings::remember, { throw AssertionError(it) })
    try {
      val result = adapter.intercept("os.kei", 0, info(), context) { nativeCalls++; null }
      assertTrue(result is BitmapDrawable)
      assertSame(bitmap, (result as BitmapDrawable).bitmap)
      assertEquals(listOf("os.kei"), lookups)
      assertEquals(0, nativeCalls)
      val view = ImageView(context)
      assertEquals("os.kei", requireNotNull(bindings.bind(view, result)).name)
      // Even pixel-identical art from outside this producer has no application identity.
      assertNull(bindings.bind(view, BitmapDrawable(context.resources, bitmap)))
      assertNull(bindings.bind(view, ColorDrawable(Color.RED)))
      adapter.stop()
      assertFalse("Adapter never owns/recycles shared cache pixels", bitmap.isRecycled)
    } finally { adapter.stop(); bindings.clear(); bitmap.recycle() }
  }

  @Test fun missPreservesNativeDrawableAndItsLateBindingProvenance() = onMain {
    val native = ColorDrawable(Color.BLUE)
    val bindings = SystemUiAppIconBindings()
    var nativeCalls = 0
    var lookups = 0
    val adapter = SystemUiIslandAppIconAdapter(0, ThemeCompatibilityLifecycle(),
      { lookups++; null }, bindings::remember, { throw AssertionError(it) })
    try {
      val result = adapter.intercept("os.kei", 0, info(), context) { nativeCalls++; native }
      assertSame(native, result)
      assertEquals(1, nativeCalls)
      assertEquals(1, lookups)
      val view = ImageView(context)
      val binding = requireNotNull(bindings.bind(view, native))
      assertSame(view, bindings.current(binding))
      assertEquals("os.kei", binding.name)
      assertNull(bindings.bind(view, ColorDrawable(Color.BLUE)))
      assertNull(bindings.current(binding))
    } finally { adapter.stop(); bindings.clear() }
  }

  @Test fun missingInfoMismatchedTargetAndForeignUsersStayEntirelyNative() = onMain {
    var lookups = 0
    var remembered = 0
    var nativeCalls = 0
    val native = ColorDrawable(Color.RED)
    val adapter = SystemUiIslandAppIconAdapter(0, ThemeCompatibilityLifecycle(),
      { lookups++; null }, { _, _ -> remembered++ }, { throw AssertionError(it) })
    try {
      for ((user, info) in listOf(0 to null, 0 to info("another.app"),
          0 to info(uid = 110123), 1 to info(uid = 110123), 0 to info(uid = 0))) {
        assertSame(native, adapter.intercept("os.kei", user, info, context) { nativeCalls++; native })
      }
      assertSame(native, adapter.intercept("os.kei", 0, info(), null) { nativeCalls++; native })
      assertEquals(6, nativeCalls)
      assertEquals(0, lookups)
      assertEquals(0, remembered)
    } finally { adapter.stop() }
  }

  @Test fun nativeFailurePropagatesOnceAndReleasesLifecycleAdmission() {
    val lifecycle = ThemeCompatibilityLifecycle()
    val expected = IllegalStateException("native failure")
    var nativeCalls = 0
    val adapter = SystemUiIslandAppIconAdapter(0, lifecycle, { null }, { _, _ -> },
      { throw AssertionError(it) })
    val actual = assertThrows(IllegalStateException::class.java) {
      adapter.intercept("os.kei", 0, info(), context) { nativeCalls++; throw expected }
    }
    assertSame(expected, actual)
    assertEquals(1, nativeCalls)
    adapter.stop()
    assertTrue(lifecycle.retire(saveState = {}))
  }

  @Test fun optionalLookupFailureDisablesOnlyAdapterWorkAndNeverRetriesNative() {
    var failures = 0
    var lookups = 0
    var nativeCalls = 0
    var remembered = 0
    val native = ColorDrawable(Color.BLUE)
    val adapter = SystemUiIslandAppIconAdapter(0, ThemeCompatibilityLifecycle(),
      { lookups++; error("optional snapshot failure") }, { _, _ -> remembered++ }, { failures++ })
    try {
      repeat(2) {
        assertSame(native, adapter.intercept("os.kei", 0, info(), context) { nativeCalls++; native })
      }
      assertEquals(1, failures)
      assertEquals(1, lookups)
      assertEquals(2, nativeCalls)
      assertEquals(0, remembered)
    } finally { adapter.stop() }
  }

  @Test fun snapshotCancellationKeepsNativeBindWithoutTokenPublicationOrDisablingAdapter() {
    val lifecycle = ThemeCompatibilityLifecycle()
    val expected = CancellationException("retiring snapshot")
    var nativeCalls = 0
    var remembered = 0
    var failures = 0
    var lookups = 0
    val native = ColorDrawable(Color.BLUE)
    val adapter = SystemUiIslandAppIconAdapter(0, lifecycle, {
      if (++lookups == 1) throw expected else null
    },
      { _, _ -> remembered++ }, { failures++ })
    assertSame(native, adapter.intercept("os.kei", 0, info(), context) { nativeCalls++; native })
    assertEquals(1, nativeCalls)
    assertEquals(0, remembered)
    assertEquals(0, failures)
    assertSame(native, adapter.intercept("os.kei", 0, info(), context) { nativeCalls++; native })
    assertEquals(2, lookups)
    assertEquals(2, nativeCalls)
    assertEquals(1, remembered)
    adapter.stop()
    assertTrue(lifecycle.retire(saveState = {}))
  }

  @Test fun retirementDuringLookupPreventsReplacementAndLateAuthentication() {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val native = ColorDrawable(Color.BLUE)
    var remembered = 0
    var lookups = 0
    lateinit var adapter: SystemUiIslandAppIconAdapter
    adapter = SystemUiIslandAppIconAdapter(0, ThemeCompatibilityLifecycle(),
      { lookups++; adapter.stop(); bitmap }, { _, _ -> remembered++ }, { throw AssertionError(it) })
    try {
      repeat(2) {
        assertSame(native, adapter.intercept("os.kei", 0, info(), context) { native })
      }
      assertEquals(1, lookups)
      assertEquals(0, remembered)
      assertFalse(bitmap.isRecycled)
    } finally { adapter.stop(); bitmap.recycle() }
  }

  @Test fun retirementDuringNativeMissDoesNotAuthenticateTheFinishingDrawable() {
    var remembered = 0
    val native = ColorDrawable(Color.BLUE)
    val adapter = SystemUiIslandAppIconAdapter(0, ThemeCompatibilityLifecycle(), { null },
      { _, _ -> remembered++ }, { throw AssertionError(it) })
    assertSame(native, adapter.intercept("os.kei", 0, info(), context) { adapter.stop(); native })
    assertEquals(0, remembered)
  }

  @Test fun closedLifecycleKeepsLaterCallsNativeWithoutCacheOrTokens() {
    val lifecycle = ThemeCompatibilityLifecycle()
    assertTrue(lifecycle.retire(saveState = {}))
    var lookups = 0
    var remembered = 0
    val native = ColorDrawable(Color.BLUE)
    val adapter = SystemUiIslandAppIconAdapter(0, lifecycle, { lookups++; null },
      { _: Drawable, _: String -> remembered++ }, { throw AssertionError(it) })
    assertSame(native, adapter.intercept("os.kei", 0, info(), context) { native })
    assertEquals(0, lookups)
    assertEquals(0, remembered)
    adapter.stop()
  }
}
