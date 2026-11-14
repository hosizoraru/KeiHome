package os.kei.hyper.home.xposed

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.SparseArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData

internal data class SystemUiIslandAppIconGraph(
  val factory: MaterialMethod,
  val load: MaterialMethod,
  val cache: MaterialMethod,
  val cacheBitmap: MaterialMethod,
  val put: MaterialMethod,
  val caller: MaterialMethod,
  val cacheField: MaterialField,
)

/** Exact retained plugin chain; neither a familiar factory name nor a Drawable return proves it. */
internal object SystemUiIslandAppIconContract {
  const val OWNER = "miui.systemui.drawable.AppIconsManager"
  const val CALLER_OWNER = "miui.systemui.dynamicisland.DynamicIslandUtils"
  private const val OWNER_ID = "Lmiui/systemui/drawable/AppIconsManager;"
  private const val ARGS = "Ljava/lang/String;ILandroid/content/pm/ApplicationInfo;Landroid/content/pm/PackageManager;Landroid/content/Context;"
  private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
  const val FACTORY = "$OWNER_ID->getAppIcon($ARGS)$DRAWABLE"
  const val LOAD = "$OWNER_ID->loadAppIcon($ARGS)$DRAWABLE"
  const val CACHE = "$OWNER_ID->getBitmapFromCache(ILjava/lang/String;)Landroid/graphics/Bitmap;"
  const val CACHE_BITMAP = "$OWNER_ID->cacheBitmap(ILjava/lang/String;$DRAWABLE)V"
  const val PUT = "$OWNER_ID->putBitmapToCache(ILjava/lang/String;Landroid/graphics/Bitmap;)V"
  const val CALLER = "Lmiui/systemui/dynamicisland/DynamicIslandUtils;->getAppIcon(Landroid/content/Context;Ljava/lang/String;Ljava/lang/Integer;)$DRAWABLE"
  const val CACHE_FIELD = "$OWNER_ID->mIconsCache:Landroid/util/SparseArray;"
  const val INSTANCE = "$OWNER_ID->INSTANCE:Lmiui/systemui/drawable/AppIconsManager;"
  const val NATIVE_LOAD = "Lcom/miui/maml/util/AppIconsHelper;->getIconDrawable(Landroid/content/Context;Landroid/content/pm/PackageItemInfo;Landroid/content/pm/PackageManager;)$DRAWABLE"
  const val PACKAGE_INFO = "Landroid/content/pm/PackageManager;->getApplicationInfo(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;"
  const val BITMAP = "Landroid/graphics/drawable/BitmapDrawable;->getBitmap()Landroid/graphics/Bitmap;"
  const val WRAP = "Landroid/graphics/drawable/BitmapDrawable;-><init>(Landroid/content/res/Resources;Landroid/graphics/Bitmap;)V"
  const val WEAK_GET = "Ljava/lang/ref/Reference;->get()Ljava/lang/Object;"
  const val WEAK_PUT = "Ljava/lang/ref/WeakReference;-><init>(Ljava/lang/Object;)V"
  const val SPARSE_GET = "Landroid/util/SparseArray;->get(I)Ljava/lang/Object;"
  const val MAP_GET = "Ljava/util/concurrent/ConcurrentHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;"
  const val MAP_PUT = "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
  val PARAMETERS = listOf("java.lang.String", "int", "android.content.pm.ApplicationInfo",
    "android.content.pm.PackageManager", "android.content.Context")

  fun prove(g: SystemUiIslandAppIconGraph) {
    fun instance(m: MaterialMethod, id: String, owner: String, result: String,
      parameters: List<String>, public: Boolean) {
      check(m.id == id && m.owner == owner && m.result == result && m.parameters == parameters &&
        Modifier.isFinal(m.modifiers) && !Modifier.isStatic(m.modifiers) &&
        !Modifier.isAbstract(m.modifiers) && !Modifier.isNative(m.modifiers) &&
        (if (public) Modifier.isPublic(m.modifiers) else Modifier.isPrivate(m.modifiers))) {
        "Super Island application-icon signature changed: $id"
      }
    }
    instance(g.factory, FACTORY, OWNER, "android.graphics.drawable.Drawable", PARAMETERS, true)
    instance(g.load, LOAD, OWNER, "android.graphics.drawable.Drawable", PARAMETERS, false)
    instance(g.cache, CACHE, OWNER, "android.graphics.Bitmap", listOf("int", "java.lang.String"), false)
    instance(g.cacheBitmap, CACHE_BITMAP, OWNER, "void",
      listOf("int", "java.lang.String", "android.graphics.drawable.Drawable"), false)
    instance(g.put, PUT, OWNER, "void", listOf("int", "java.lang.String", "android.graphics.Bitmap"), false)
    instance(g.caller, CALLER, CALLER_OWNER, "android.graphics.drawable.Drawable",
      listOf("android.content.Context", "java.lang.String", "java.lang.Integer"), true)
    check(LOAD in g.factory.calls &&
      g.load.calls.containsAll(setOf(CACHE, NATIVE_LOAD, CACHE_BITMAP, WRAP)) &&
      g.caller.calls.containsAll(setOf(FACTORY, PACKAGE_INFO)) && INSTANCE in g.caller.reads) {
      "Super Island native application-icon producer/caller chain changed"
    }
    check(g.cacheField.id == CACHE_FIELD && g.cacheField.owner == OWNER &&
      g.cacheField.type == "android.util.SparseArray" && Modifier.isStatic(g.cacheField.modifiers) &&
      Modifier.isFinal(g.cacheField.modifiers) &&
      CACHE_FIELD in g.cache.reads && CACHE_FIELD in g.put.reads)
    check(g.cache.calls.containsAll(setOf(SPARSE_GET, WEAK_GET, MAP_GET)) &&
      g.cacheBitmap.calls.containsAll(setOf(BITMAP, PUT)) &&
      g.put.calls.containsAll(setOf(SPARSE_GET, WEAK_PUT, MAP_PUT))) {
      "Super Island per-user weak Bitmap cache contract changed"
    }
  }
}

/** Startup-only APK/reflection work. No native cache reads or new package lookups on a hook. */
internal class SystemUiIslandAppIconProfile private constructor(val factory: Method) {
  companion object {
    @OptIn(DexKitExperimentalApi::class)
    fun resolve(context: Context, loader: ClassLoader): SystemUiIslandAppIconProfile {
      val contract = SystemUiIslandAppIconContract
      val owner = Class.forName(contract.OWNER, false, loader)
      val caller = Class.forName(contract.CALLER_OWNER, false, loader)
      check(owner.classLoader === loader && caller.classLoader === loader) {
        "Super Island factory/caller must belong to the supplied existing plugin loader"
      }
      val info = context.packageManager.getApplicationInfo("miui.systemui.plugin", 0)
      check(info.packageName == "miui.systemui.plugin")
      return SystemUiMaterialDexKit.open(info.sourceDir, context.codeCacheDir).use { dex ->
        dex.setThreadNum(1)
        fun required(id: String): MethodData = checkNotNull(dex.getMethodData(id)) {
          "Missing Super Island application-icon contract: $id"
        }
        fun snapshot(m: MethodData): MaterialMethod = MaterialMethod(
          m.descriptor, m.className, m.name, m.returnTypeName, m.paramTypeNames, m.modifiers,
          m.invokes.map { it.descriptor }.toSet(),
          m.usingFields.filter { it.usingType == FieldUsingType.Read }.map { it.field.descriptor }.toSet(),
          m.usingFields.filter { it.usingType == FieldUsingType.Write }.map { it.field.descriptor }.toSet(),
        )
        val factory = required(contract.FACTORY)
        val load = required(contract.LOAD)
        val cache = required(contract.CACHE)
        val cacheBitmap = required(contract.CACHE_BITMAP)
        val put = required(contract.PUT)
        val nativeCaller = required(contract.CALLER)
        val field = cache.usingFields.first {
          it.usingType == FieldUsingType.Read && it.field.descriptor == contract.CACHE_FIELD
        }.field
        contract.prove(SystemUiIslandAppIconGraph(
          snapshot(factory), snapshot(load), snapshot(cache), snapshot(cacheBitmap), snapshot(put),
          snapshot(nativeCaller), MaterialField(field.descriptor, field.className, field.typeName, field.modifiers),
        ))
        // Reify the proven graph in this loader, not a second package-context copy of the plugin.
        for (data in listOf(factory, load, cache, cacheBitmap, put)) {
          check(data.getMethodInstance(loader).declaringClass === owner)
        }
        check(nativeCaller.getMethodInstance(loader).declaringClass === caller)
        val reflectedField = field.getFieldInstance(loader)
        check(reflectedField.declaringClass === owner && reflectedField.type == SparseArray::class.java)
        val method = factory.getMethodInstance(loader)
        check(method.returnType == Drawable::class.java &&
          method.parameterTypes.map { it.name } == contract.PARAMETERS)
        SystemUiIslandAppIconProfile(method.apply { isAccessible = true })
      }
    }
  }
}
