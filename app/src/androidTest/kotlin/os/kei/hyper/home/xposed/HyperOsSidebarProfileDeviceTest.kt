package os.kei.hyper.home.xposed

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HyperOsSidebarProfileDeviceTest {
  private fun withProfile(block: (Context, HyperOsSidebarProfile) -> Unit) {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val host = app.createPackageContext(HyperOsSidebarProfile.PACKAGE,
      Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
    val context = object : ContextWrapper(host) {
      override fun getCacheDir() = app.cacheDir
    }
    val worker = Executors.newSingleThreadExecutor()
    try {
      val profile = worker.submit<HyperOsSidebarProfile> {
        HyperOsSidebarProfile.resolve(context, host.classLoader)
      }.get(30, TimeUnit.SECONDS)
      block(host, profile)
    } finally { worker.shutdownNow() }
  }

  @Test fun resolvesSharedRequestsAndAuthenticatedGridAndRailBinders() = withProfile { _, profile ->
    assertEquals(2, profile.bindMethods.size)
    assertEquals(2, profile.requestMethods.size)
    assertEquals(1, profile.requestMethods.map { it.declaringClass }.distinct().size)
    profile.requestMethods.forEach { method ->
      assertTrue(Modifier.isStatic(method.modifiers))
      assertEquals(Void.TYPE, method.returnType)
      assertEquals(String::class.java, method.parameterTypes[0])
      assertEquals(ImageView::class.java, method.parameterTypes[1])
    }
    profile.bindMethods.forEach { method ->
      assertFalse(Modifier.isStatic(method.modifiers))
      assertEquals(Void.TYPE, method.returnType)
      assertEquals(1, method.parameterTypes.size)
      assertFalse(profile.requestMethods.contains(method))
    }
    val grid = profile.bindMethods.single { it != profile.rail.bind }
    assertTrue(grid.parameterTypes.single().name.startsWith("com.miui.dock.allapps."))
    assertEquals(grid, profile.grid.bind)
    assertEquals(grid.parameterTypes.single(), profile.grid.packageInfo.declaringClass)
    assertEquals(android.content.pm.PackageInfo::class.java, profile.grid.packageInfo.returnType)
    assertTrue(profile.grid.image.declaringClass.isAssignableFrom(grid.declaringClass))
    assertEquals(ImageView::class.java, profile.grid.image.returnType)
    assertNull(profile.grid.identity(Any()))
    assertNull(profile.grid.imageView(Any()))
    val cancel = requireNotNull(profile.cancel)
    assertEquals(profile.requestMethods.first().declaringClass, cancel.declaringClass)
    assertTrue(Modifier.isStatic(cancel.modifiers))
    assertEquals(listOf(ImageView::class.java), cancel.parameterTypes.toList())
  }

  @Test fun cachedRailAccessorsUseDiscoveredModelAndHostResource() = withProfile { host, profile ->
    val rail = profile.rail
    assertTrue(profile.bindMethods.contains(rail.bind))
    assertEquals(rail.bind.declaringClass, rail.model.declaringClass)
    assertEquals(rail.model.type, rail.packageName.declaringClass)
    assertEquals(rail.model.type, rail.uid.declaringClass)
    assertEquals(String::class.java, rail.packageName.type)
    assertEquals(Int::class.javaPrimitiveType, rail.uid.type)
    assertEquals(ImageView::class.java, rail.image.type)
    assertEquals(View::class.java, rail.holderRoot.type)
    assertTrue(rail.holderRoot.declaringClass.isAssignableFrom(rail.image.declaringClass))
    assertTrue(rail.alternateIconId != 0)
    assertEquals("icon_view", host.resources.getResourceEntryName(rail.alternateIconId))
    assertEquals(HyperOsSidebarProfile.PACKAGE,
      host.resources.getResourcePackageName(rail.alternateIconId))
    assertNull(rail.identity(Any()))
    assertNull(rail.imageView(Any()))
  }

  @Test fun railAccessorsReadIdentityBeforeBindAndSupportBothHolderForms() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
      val app = instrumentation.targetContext
      val root = FrameLayout(app)
      val icon = ImageView(app).apply { id = View.generateViewId() }
      root.addView(icon)
      fun field(owner: Class<*>, name: String) =
        owner.getDeclaredField(name).apply { isAccessible = true }
      val rail = HyperOsSidebarRailBinding(
        bind = RailItemFixture::class.java.getDeclaredMethod("bind", HolderFixture::class.java),
        model = field(RailItemFixture::class.java, "model"),
        packageName = field(AppFixture::class.java, "packageName"),
        uid = field(AppFixture::class.java, "uid"),
        image = field(ImageHolderFixture::class.java, "image"),
        holderRoot = field(HolderFixture::class.java, "root"),
        alternateIconId = icon.id,
      )
      val item = RailItemFixture()
      assertNull(rail.identity(item))
      item.model = AppFixture()
      assertEquals("example.app" to 10123, rail.identity(item))
      item.model!!.uid = 110123
      assertEquals("example.app" to 110123, rail.identity(item))
      item.model!!.uid = -1
      assertNull(rail.identity(item))
      item.model!!.uid = 10123
      item.model!!.packageName = ""
      assertNull(rail.identity(item))
      item.model!!.packageName = null
      assertNull(rail.identity(item))
      assertSame(icon, rail.imageView(ImageHolderFixture(root, icon)))
      assertSame(icon, rail.imageView(HolderFixture(root)))
      assertNull(rail.imageView(HolderFixture(FrameLayout(app))))
    }
  }

  @Test fun cacheRestoresAcrossModuleLoadersWithoutNativeDiscovery() = withProfile { host, profile ->
    val value = System.getProperties()[HyperOsSidebarProfileCache.KEY]
    assertTrue(value is String)
    assertTrue((value as String).toByteArray(Charsets.UTF_8).size <= HyperOsSidebarProfileCache.MAX_BYTES)
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val generation = PathClassLoader(app.applicationInfo.sourceDir, app.classLoader.parent)
    val cacheClass = Class.forName(HyperOsSidebarProfileCache::class.java.name, true, generation)
    assertNotSame(HyperOsSidebarProfileCache::class.java, cacheClass)
    val cache = cacheClass.declaredConstructors.single().apply { isAccessible = true }.newInstance()
    val restore = cacheClass.declaredMethods.single {
      it.parameterTypes.toList() == listOf(Context::class.java, ClassLoader::class.java, String::class.java)
    }.apply { isAccessible = true }
    val result = requireNotNull(restore.invoke(cache, host, host.classLoader,
      HyperOsSidebarProfileCache.sourceIdentity(host.applicationInfo.sourceDir)))
    assertSame(generation, result.javaClass.classLoader)
    val methods = result.javaClass.declaredFields.filter {
      !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
    }.flatMap {
      it.isAccessible = true
      (it.get(result) as List<*>).filterIsInstance<Method>()
    }
    assertEquals((profile.requestMethods + profile.bindMethods).toSet(), methods.toSet())
    assertTrue(methods.none { it.declaringClass.classLoader === generation })
    assertEquals(value, System.getProperty(HyperOsSidebarProfileCache.KEY))
  }

  @Test fun invalidCacheRemovesOnlyItsOwnProperty() = withProfile { host, _ ->
    val key = HyperOsSidebarProfileCache.KEY
    val original = requireNotNull(System.getProperty(key))
    val sentinel = "$key.test.sentinel"
    val properties = System.getProperties()
    val previousSentinel = properties.put(sentinel, "retained")
    try {
      val corruptions = listOf(
        JSONObject(original).put("schema", HyperOsSidebarProfileCache.SCHEMA + 1).toString(),
        JSONObject(original).put("source", "other APK").toString(),
        JSONObject(original).apply {
          val method = getJSONArray("methods").getJSONObject(0)
          method.put("m", method.getInt("m") xor Modifier.STATIC)
        }.toString(),
        JSONObject(original).apply {
          val field = getJSONArray("fields").getJSONObject(0)
          field.put("m", field.getInt("m") xor Modifier.STATIC)
        }.toString(),
        JSONObject(original).apply {
          val methods = getJSONArray("methods")
          (0 until methods.length()).map { methods.getJSONObject(it) }
            .single { it.getString("d") == getString("grid") }.put("c", JSONArray())
        }.toString(),
        JSONObject(original).apply { put("modelGetter", getString("packageGetter")) }.toString(),
        JSONObject(original).apply {
          getJSONArray("methods").getJSONObject(0).put("d", "invalid descriptor")
        }.toString(),
        "x".repeat(HyperOsSidebarProfileCache.MAX_BYTES + 1),
      )
      for (value in corruptions) {
        System.setProperty(key, value)
        assertNull(HyperOsSidebarProfileCache.restore(host, host.classLoader))
        assertFalse(properties.containsKey(key))
        assertEquals("retained", System.getProperty(sentinel))
      }
      properties[key] = 1
      assertNull(HyperOsSidebarProfileCache.restore(host, host.classLoader))
      assertFalse(properties.containsKey(key))
      System.setProperty(key, original)
      HyperOsSidebarProfileCache.invalidate()
      assertFalse(properties.containsKey(key))
      assertEquals("retained", System.getProperty(sentinel))
    } finally {
      System.setProperty(key, original)
      if (previousSentinel == null) properties.remove(sentinel)
      else properties[sentinel] = previousSentinel
    }
  }

  @Test fun sourceIdentityUsesCanonicalPathAndSortedManifestAndDexCrcAndSize() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File.createTempFile("sidebar-source-", ".apk", app.cacheDir)
    fun write(dex: String = "dexA", manifest: String = "manifestA", resource: String = "resA",
      reverse: Boolean = false, second: String = "second") {
      val entries = listOf("classes2.dex" to second, "AndroidManifest.xml" to manifest,
        "classes.dex" to dex, "res/test.txt" to resource)
      ZipOutputStream(file.outputStream()).use { zip ->
        for ((name, content) in if (reverse) entries.reversed() else entries) {
          zip.putNextEntry(ZipEntry(name))
          zip.write(content.toByteArray(Charsets.UTF_8))
          zip.closeEntry()
        }
      }
    }
    try {
      write()
      val original = HyperOsSidebarProfileCache.sourceIdentity(file.path)
      val entries = JSONObject(original).getJSONArray("entries")
      assertEquals(listOf("AndroidManifest.xml", "classes.dex", "classes2.dex"),
        (0 until entries.length()).map { entries.getJSONArray(it).getString(0) })
      assertEquals(original,
        HyperOsSidebarProfileCache.sourceIdentity(File(file.parentFile, "./${file.name}").path))
      write(resource = "unrelated resource", reverse = true)
      assertEquals(original, HyperOsSidebarProfileCache.sourceIdentity(file.path))
      write(dex = "dexB")
      assertNotEquals(original, HyperOsSidebarProfileCache.sourceIdentity(file.path))
      write(dex = "dexLonger")
      assertNotEquals(original, HyperOsSidebarProfileCache.sourceIdentity(file.path))
      write(second = "secondB")
      assertNotEquals(original, HyperOsSidebarProfileCache.sourceIdentity(file.path))
      write(manifest = "manifestB")
      assertNotEquals(original, HyperOsSidebarProfileCache.sourceIdentity(file.path))
    } finally { file.delete() }
  }

  private class AppFixture {
    @JvmField var packageName: String? = "example.app"
    @JvmField var uid: Int = 10123
  }

  private class RailItemFixture {
    @JvmField var model: AppFixture? = null
    fun bind(holder: HolderFixture) = Unit
  }

  private open class HolderFixture(@JvmField val root: View)
  private class ImageHolderFixture(root: View, @JvmField val image: ImageView) : HolderFixture(root)
}
