package os.kei.hyper.home.xposed

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class SystemUiIslandAppIconProfileTest {
  private val contract = SystemUiIslandAppIconContract
  private val publicFinal = Modifier.PUBLIC or Modifier.FINAL
  private val privateFinal = Modifier.PRIVATE or Modifier.FINAL
  private fun method(id: String, result: String, parameters: List<String>,
    calls: Set<String>, reads: Set<String> = emptySet(), public: Boolean = false): MaterialMethod =
    MaterialMethod(id, if (id == contract.CALLER) contract.CALLER_OWNER else contract.OWNER,
      id.substringAfter("->").substringBefore("("), result, parameters,
      if (public) publicFinal else privateFinal, calls, reads)

  /** Calls and types transcribed from the retained plugin AppIconsManager/DynamicIslandUtils. */
  private fun graph(): SystemUiIslandAppIconGraph = SystemUiIslandAppIconGraph(
    method(contract.FACTORY, "android.graphics.drawable.Drawable", contract.PARAMETERS,
      setOf(contract.LOAD), public = true),
    method(contract.LOAD, "android.graphics.drawable.Drawable", contract.PARAMETERS,
      setOf(contract.CACHE, contract.NATIVE_LOAD, contract.CACHE_BITMAP, contract.WRAP)),
    method(contract.CACHE, "android.graphics.Bitmap", listOf("int", "java.lang.String"),
      setOf(contract.SPARSE_GET, contract.WEAK_GET, contract.MAP_GET),
      setOf(contract.CACHE_FIELD)),
    method(contract.CACHE_BITMAP, "void",
      listOf("int", "java.lang.String", "android.graphics.drawable.Drawable"),
      setOf(contract.BITMAP, contract.PUT)),
    method(contract.PUT, "void", listOf("int", "java.lang.String", "android.graphics.Bitmap"),
      setOf(contract.SPARSE_GET, contract.WEAK_PUT, contract.MAP_PUT),
      setOf(contract.CACHE_FIELD)),
    method(contract.CALLER, "android.graphics.drawable.Drawable",
      listOf("android.content.Context", "java.lang.String", "java.lang.Integer"),
      setOf(contract.FACTORY, contract.PACKAGE_INFO), setOf(contract.INSTANCE), public = true),
    MaterialField(contract.CACHE_FIELD, contract.OWNER, "android.util.SparseArray",
      Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL),
  )

  private fun reject(graph: SystemUiIslandAppIconGraph) {
    assertThrows(IllegalStateException::class.java) { contract.prove(graph) }
  }

  @Test fun provesRetainedNativeFactoryCallerAndWeakBitmapCache() {
    contract.prove(graph())
  }

  @Test fun factoryNameAloneAndAnUnrelatedPictureLoaderAreInsufficient() {
    val g = graph()
    reject(g.copy(factory = g.factory.copy(calls = emptySet())))
    reject(g.copy(load = g.load.copy(calls = g.load.calls - contract.NATIVE_LOAD)))
    reject(g.copy(load = g.load.copy(calls = g.load.calls - contract.CACHE)))
    reject(g.copy(load = g.load.copy(calls = g.load.calls - contract.CACHE_BITMAP)))
    reject(g.copy(load = g.load.copy(calls = g.load.calls - contract.WRAP)))
    reject(g.copy(caller = g.caller.copy(calls = g.caller.calls - contract.FACTORY)))
    reject(g.copy(caller = g.caller.copy(calls = g.caller.calls - contract.PACKAGE_INFO)))
    reject(g.copy(caller = g.caller.copy(reads = emptySet())))
  }

  @Test fun rejectsDrawableCacheAssumptionsAndUnlinkedBitmapWriters() {
    val g = graph()
    reject(g.copy(cache = g.cache.copy(result = "android.graphics.drawable.Drawable")))
    reject(g.copy(cache = g.cache.copy(calls = g.cache.calls - contract.WEAK_GET)))
    reject(g.copy(cache = g.cache.copy(calls = g.cache.calls - contract.WEAK_GET +
      "Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;")))
    reject(g.copy(cache = g.cache.copy(calls = g.cache.calls - contract.MAP_GET)))
    reject(g.copy(cache = g.cache.copy(reads = emptySet())))
    reject(g.copy(cacheBitmap = g.cacheBitmap.copy(calls = g.cacheBitmap.calls - contract.BITMAP)))
    reject(g.copy(cacheBitmap = g.cacheBitmap.copy(calls = g.cacheBitmap.calls - contract.PUT)))
    reject(g.copy(put = g.put.copy(parameters =
      listOf("int", "java.lang.String", "android.graphics.drawable.Drawable"))))
    reject(g.copy(put = g.put.copy(reads = emptySet())))
    reject(g.copy(put = g.put.copy(calls = g.put.calls - contract.WEAK_PUT)))
    reject(g.copy(put = g.put.copy(calls = g.put.calls - contract.MAP_PUT)))
  }

  @Test fun requiresExactOwnersInstanceSignaturesAndGlobalCacheField() {
    val g = graph()
    reject(g.copy(factory = g.factory.copy(owner = "com.miui.systemui.graphics.AppIconsManager")))
    for (modifiers in listOf(Modifier.PUBLIC, publicFinal or Modifier.STATIC,
        publicFinal or Modifier.NATIVE, publicFinal or Modifier.ABSTRACT)) {
      reject(g.copy(factory = g.factory.copy(modifiers = modifiers)))
    }
    reject(g.copy(load = g.load.copy(modifiers = publicFinal)))
    reject(g.copy(caller = g.caller.copy(parameters =
      listOf("android.content.Context", "java.lang.String", "int"))))
    reject(g.copy(cacheField = g.cacheField.copy(modifiers = Modifier.PRIVATE or Modifier.FINAL)))
    reject(g.copy(cacheField = g.cacheField.copy(id = "LOther;->mIconsCache:Landroid/util/SparseArray;")))
  }

  @Test fun targetIdentityMayDifferFromProxyButMustMatchAuthoritativeApplicationInfo() {
    assertEquals("com.example.target",
      islandAppIconPackage("com.example.target", 0, "com.example.target", 10123, 0))
    assertNull(islandAppIconPackage("com.example.target", 0, "com.xiaomi.xmsf", 10123, 0))
    assertNull(islandAppIconPackage("com.xiaomi.xmsf", 0, "com.example.target", 10123, 0))
    assertEquals("android", islandAppIconPackage("android", 0, "android", 1000, 0))
  }

  @Test fun refusesCrossUserMissingOrUnsafeIdentityWithoutLookupFallback() {
    assertEquals("os.kei", islandAppIconPackage("os.kei", 1, "os.kei", 110123, 1))
    assertNull(islandAppIconPackage("os.kei", 1, "os.kei", 110123, 0))
    assertNull(islandAppIconPackage("os.kei", 0, "os.kei", 110123, 0))
    assertNull(islandAppIconPackage("os.kei", null, "os.kei", 10123, 0))
    assertNull(islandAppIconPackage("os.kei", 0, null, 10123, 0))
    assertNull(islandAppIconPackage(null, 0, null, 10123, 0))
    assertNull(islandAppIconPackage("os.kei", 0, "os.kei", 10123, -1))
    for (uid in listOf(-1, 0, 999, 100001)) {
      assertNull(islandAppIconPackage("os.kei", 0, "os.kei", uid, 0))
    }
    for (name in listOf("../os.kei", "os.kei/Art", "os.kei\$Art", "os..kei", "os.kei.",
        "os.\u00e9", "a".repeat(241))) {
      assertNull(islandAppIconPackage(name, 0, name, 10123, 0))
    }
  }
}
