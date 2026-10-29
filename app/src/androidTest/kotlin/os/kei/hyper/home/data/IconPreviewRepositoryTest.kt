package os.kei.hyper.home.data

import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconPreviewRepositoryTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val fixtures = instrumentation.context
  private val target = instrumentation.targetContext

  private fun resources(night: Int): Resources = fixtures.packageManager.getResourcesForApplication(
    fixtures.applicationInfo,
    Configuration(fixtures.resources.configuration).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    },
  )

  private fun icon(name: String): Int = fixtures.resources.getIdentifier(name, "drawable", fixtures.packageName)

  private fun app(): InstalledIconCapability = InstalledIconCapability(
    label = "KeiHome",
    packageName = target.packageName,
    userId = target.getSystemService(UserManager::class.java)!!.getSerialNumberForUser(Process.myUserHandle()),
    personalProfile = true,
    nightMode = IconCapabilityStatus.UNKNOWN,
    monochrome = IconCapabilityStatus.UNKNOWN,
  )

  @Test
  fun resolvesNightResourcesAndRejectsOnlyTheBrokenModeMask() {
    val light = resources(Configuration.UI_MODE_NIGHT_NO)
    val dark = resources(Configuration.UI_MODE_NIGHT_YES)
    assertEquals(0xff123456.toInt(), renderIconPreview(light, icon("fixture_night"), 64).color!!.getPixel(32, 32))
    assertEquals(0xfffedcba.toInt(), renderIconPreview(dark, icon("fixture_night"), 64).color!!.getPixel(32, 32))
    assertNotNull(renderIconPreview(light, icon("fixture_mono_bad_at_night"), 64).monochrome)
    val broken = renderIconPreview(dark, icon("fixture_mono_bad_at_night"), 64)
    assertNotNull(broken.color)
    assertNull(broken.monochrome)
  }

  @Test
  fun missingFlatAndEmptyLayersNeverBecomeInventedMonochromeIcons() {
    val resources = resources(Configuration.UI_MODE_NIGHT_NO)
    for (name in listOf("fixture_static", "fixture_mono_solid", "fixture_mono_empty")) {
      val result = renderIconPreview(resources, icon(name), 128)
      assertNotNull(name, result.color)
      assertNull(name, result.monochrome)
    }
  }

  @Test
  fun alphaMaskKeepsAdaptiveLayerExpansionWithoutTheOpaqueAdaptiveBackground() {
    val resources = resources(Configuration.UI_MODE_NIGHT_NO)
    val mask = renderIconPreview(resources, icon("fixture_mono_valid"), 128).monochrome!!
    assertEquals(Bitmap.Config.ALPHA_8, mask.config)
    assertEquals(128, mask.width)
    assertEquals(128, mask.height)
    assertEquals(0, mask.getPixel(64, 0) ushr 24)
    assertEquals(255, mask.getPixel(64, 64) ushr 24)

    // The fixture's 60/108 glyph expands with the adaptive viewport. Drawing the raw layer
    // into 128x128 directly would leave x=20 transparent; the real adaptive layer covers it.
    assertTrue(mask.getPixel(20, 64) ushr 24 > 0)
    val raw = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    val drawable = resources.getDrawable(icon("fixture_mono_shape"), null)!!
    drawable.setBounds(0, 0, 128, 128)
    drawable.draw(Canvas(raw))
    assertEquals(0, raw.getPixel(20, 64) ushr 24)
  }

  @Test
  @Suppress("DEPRECATION")
  fun paddedFullColorBitmapIsRejectedBeforeAdaptiveClipping() {
    val base = resources(Configuration.UI_MODE_NIGHT_NO)
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    for (y in 16 until 80) for (x in 16 until 80) {
      bitmap.setPixel(x, y, 0xff000000.toInt() or ((x * 3) shl 16) or ((y * 3) shl 8) or (x + y))
    }
    // Reuse the fixture XML's explicit <monochrome> declaration with a deliberately bad layer.
    val resources = object : Resources(base.assets, base.displayMetrics, base.configuration) {
      override fun getDrawableForDensity(id: Int, density: Int, theme: Resources.Theme?): Drawable =
        AdaptiveIconDrawable(ColorDrawable(0xff123456.toInt()), null, BitmapDrawable(this, bitmap))
    }
    val result = renderIconPreview(resources, icon("fixture_mono_valid"), 128)
    assertNotNull(result.color)
    assertNull(result.monochrome)
  }

  @Test
  fun usesTheProfileLauncherEntryAndNeverFallsBackToAnotherUser() = runBlocking {
    val repository = IconPreviewRepository(target)
    val capability = app()
    val user = target.getSystemService(UserManager::class.java)!!.getUserForSerialNumber(capability.userId)!!
    val expected = target.getSystemService(LauncherApps::class.java)!!
      .getActivityList(capability.packageName, user).first()
    val result = repository.load(capability, 64)
    assertEquals(expected.componentName, result.componentName)
    assertEquals(64, result.light!!.width)
    assertEquals(64, result.dark!!.width)
    assertEquals(result.lightMonochrome == null, result.lightMonochromeFallback)
    assertEquals(result.darkMonochrome == null, result.darkMonochromeFallback)
    assertNull(repository.load(capability.copy(userId = Long.MAX_VALUE)).light)
  }

  @Test
  fun clearPreventsAnOlderQueuedLoadFromRefillingCacheAndKeepsPublishedBitmapsAlive(): Unit = runBlocking {
    val dispatcher = QueuedDispatcher()
    val repository = IconPreviewRepository(target, dispatcher)
    val capability = app()
    val old = async(start = CoroutineStart.UNDISPATCHED) { repository.load(capability, 64) }
    repository.clear()
    dispatcher.drain()
    val before = old.await()
    assertNotNull(before.light)

    suspend fun load(): AppIconPreviews {
      val request = async(start = CoroutineStart.UNDISPATCHED) { repository.load(capability, 64) }
      dispatcher.drain()
      return request.await()
    }
    val after = load()
    assertNotSame(before.light, after.light)
    assertSame(after, load())
    repository.clear()
    assertFalse(before.light!!.isRecycled)
    assertFalse(after.light!!.isRecycled)
    before.light!!.getPixel(32, 32)
    after.light!!.getPixel(32, 32)
  }

  @Test
  fun cancelledQueuedItemDoesNotContinueItsLoad() = runBlocking {
    val dispatcher = QueuedDispatcher()
    val repository = IconPreviewRepository(target, dispatcher)
    var published = false
    val request = async(start = CoroutineStart.UNDISPATCHED) {
      repository.load(app(), 64)
      published = true
    }
    request.cancel()
    dispatcher.drain()
    request.join()
    assertTrue(request.isCancelled)
    assertFalse(published)
  }

  @Test
  fun versionAndSizeChangeTheCacheEntryAndEvictionDoesNotRecycle() = runBlocking {
    val repository = IconPreviewRepository(target)
    val capability = app()
    val first = repository.load(capability, 512)
    assertSame(first, repository.load(capability, 512))
    val newerCapability = capability.copy(versionCode = 1)
    val newer = repository.load(newerCapability, 512)
    assertNotSame(first, newer)
    val resized = repository.load(newerCapability, 513)
    assertEquals(513, resized.light!!.width)
    // These two pairs alone exceed the repository's 4 MiB bitmap budget.
    assertNotSame(newer.light, repository.load(newerCapability, 512).light)
    assertFalse(first.light!!.isRecycled)
    assertFalse(newer.light!!.isRecycled)
    assertFalse(resized.light!!.isRecycled)
  }

  private class QueuedDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
  }
}
