package os.kei.hyper.home.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData

/**
 * Small SystemUI boundary for app icons.
 *
 * The hook only replaces a memory-cache hit from [CrossSurfaceIconCache]. A miss always
 * executes the original SystemUI path, so notification badges, work-profile styling, and
 * OEM-specific icon fallbacks remain owned by SystemUI.
 */
internal class SystemUiAppIconRuntime(
  private val context: Context,
  private val loader: ClassLoader,
  private val lifecycle: ThemeCompatibilityLifecycle,
  private val replace: (Method, String, XposedInterface.Hooker) -> Unit,
  private val reportFailure: (String, Throwable) -> Unit,
  private val report: (Int, String, String) -> Unit = { priority, tag, message ->
    Log.println(priority, tag, message)
    Unit
  },
) {
  @Volatile private var cache: CrossSurfaceIconCache? = null
  @Volatile private var stopped = false
  private val currentUser = Process.myUid() / 100000
  private val guard = SystemUiHookGuard { reportFailure("notification app icon lookup", it) }

  @SuppressLint("BlockedPrivateApi")
  fun start() {
    check(Looper.myLooper() != Looper.getMainLooper())
    check(cache == null && !stopped) { "SystemUI app icon runtime already started or stopped" }
    val method = resolveProfile()
    // Notification app icons are demand-loaded. Do not prewarm unrelated apps or
    // schedule empty ready callbacks; native rows bind again on their own lifecycle.
    val iconCache = CrossSurfaceIconCache(context, maxEntries = 32)
    cache = iconCache
    replace(method, HOOK_ID) { chain -> intercept(chain) }
    iconCache.start()
    try {
      report(Log.INFO, TAG, "Narrow app-icon bridge enabled: $BITMAP_METHOD")
    } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
      Log.w(TAG, "App-icon bridge activation reporter unavailable", failure)
    }
  }

  @OptIn(DexKitExperimentalApi::class)
  private fun resolveProfile(): Method {
    // Reuse the running application's loader. createPackageContext(INCLUDE_CODE) can
    // resolve a second, unused copy of AppIconsManager with a different class identity.
    val hostLoader = context.applicationContext?.classLoader ?: context.classLoader
    fun existingOwner(candidate: ClassLoader): Class<*>? = try {
      Class.forName(OWNER, false, candidate)
    } catch (_: ClassNotFoundException) {
      null
    }
    val hostOwner = existingOwner(hostLoader)
    val suppliedOwner = if (loader === hostLoader) hostOwner else existingOwner(loader)
    check(hostOwner == null || suppliedOwner == null || hostOwner === suppliedOwner) {
      "SystemUI app icon class identity differs between host and supplied loader"
    }
    val owner = checkNotNull(hostOwner ?: suppliedOwner) {
      "SystemUI app icon class unavailable in existing host loaders"
    }
    val method = checkNotNull(owner.declaredMethods.singleOrNull {
      it.name == "getAppIconBitmap" &&
        it.returnType == Bitmap::class.java &&
        it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, String::class.java))
    }) { "SystemUI app icon bitmap signature unavailable" }
    checkInstanceMethod(method.modifiers)

    // Authenticate the installed APK's app-icon role, not just the familiar name.
    // All DEX, package-manager and reflection work stays on the startup worker.
    val apkPath = context.packageManager.getApplicationInfo(HOST_PACKAGE, 0).sourceDir
    SystemUiMaterialDexKit.open(apkPath, context.codeCacheDir).use { dex ->
      dex.setThreadNum(1)
      fun required(id: String): MethodData = checkNotNull(dex.getMethodData(id)) {
        "Missing SystemUI app icon contract: $id"
      }
      fun MethodData.calls(vararg ids: String) {
        check(invokes.map { it.descriptor }.toSet().containsAll(ids.toSet())) {
          "SystemUI app icon call contract changed: $descriptor"
        }
      }
      fun MethodData.reads(vararg ids: String) {
        val reads = usingFields.filter { it.usingType == FieldUsingType.Read }
          .map { it.field.descriptor }.toSet()
        check(reads.containsAll(ids.toSet())) {
          "SystemUI app icon field contract changed: $descriptor"
        }
      }
      val bitmap = required(BITMAP_METHOD)
      checkInstanceMethod(bitmap.modifiers)
      bitmap.calls(CACHE_METHOD, LOAD_METHOD,
        "Landroid/graphics/drawable/BitmapDrawable;->getBitmap()Landroid/graphics/Bitmap;",
        CONVERT_METHOD)
      bitmap.reads(ICONS_FIELD, QUIET_ICONS_FIELD)
      val cached = required(CACHE_METHOD)
      checkInstanceMethod(cached.modifiers)
      cached.reads(ICONS_FIELD, QUIET_ICONS_FIELD)
      cached.calls(
        "Landroid/util/SparseArray;->get(I)Ljava/lang/Object;",
        "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;",
        "Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;",
        "Landroid/graphics/Bitmap;->isRecycled()Z")
      val load = required(LOAD_METHOD)
      checkInstanceMethod(load.modifiers)
      load.calls(
        "Landroid/content/pm/IPackageManager;->getApplicationInfo(Ljava/lang/String;JI)Landroid/content/pm/ApplicationInfo;",
        "Lcom/miui/maml/util/AppIconsHelper;->getIconDrawable(Landroid/content/Context;Landroid/content/pm/PackageItemInfo;Landroid/content/pm/PackageManager;)Landroid/graphics/drawable/Drawable;",
        CONVERT_METHOD)
      val consumer = required(APP_ICON_CONSUMER)
      check(Modifier.isPublic(consumer.modifiers) && Modifier.isStatic(consumer.modifiers) &&
        !Modifier.isNative(consumer.modifiers) && !Modifier.isAbstract(consumer.modifiers))
      consumer.calls(BITMAP_METHOD,
        "Landroid/os/UserHandle;->myUserId()I",
        "Landroid/graphics/drawable/Icon;->createWithBitmap(Landroid/graphics/Bitmap;)Landroid/graphics/drawable/Icon;")
      consumer.reads(
        "Landroid/app/Notification;->extras:Landroid/os/Bundle;",
        "Landroid/content/pm/ApplicationInfo;->packageName:Ljava/lang/String;")
      check(consumer.usingStrings.containsAll(listOf("android.appInfo", "miui.appIcon")))

      val hostLoader = checkNotNull(owner.classLoader)
      check(bitmap.getMethodInstance(hostLoader) == method)
      // The native notification consumer must resolve the very class being hooked.
      val caller = consumer.getMethodInstance(hostLoader)
      check(Class.forName(OWNER, false, caller.declaringClass.classLoader) === owner) {
        "SystemUI notification consumer uses a different app icon class"
      }
    }
    method.isAccessible = true
    return method
  }

  fun intercept(chain: XposedInterface.Chain): Any? {
    if (stopped || !lifecycle.enter()) return chain.proceed()
    try {
      val bitmap = guard.run<Bitmap?>(null) {
        val userId = chain.getArg(0) as? Int ?: return@run null
        val packageName = chain.getArg(1) as? String ?: return@run null
        if (stopped || userId != currentUser || packageName.isEmpty()) return@run null
        cache?.get(packageName)?.takeUnless { it.isRecycled }
      }
      // The declared return type is Bitmap. Native failures must escape unchanged.
      return bitmap ?: chain.proceed()
    } finally { lifecycle.leave() }
  }

  fun invalidate() = cache?.invalidate()

  fun retirementStatus(): String = cache?.retirementStatus() ?: "cache released"

  fun stop(deadline: Long = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)): Boolean {
    stopped = true
    val iconCache = cache ?: return true
    val stoppedCache = try {
      iconCache.requestStop()
      iconCache.awaitStopped(deadline)
    } catch (failure: Throwable) {
      val cause = recoverableSystemUiFailure(failure)
      reportFailure("notification app icon cache stop", cause)
      false
    }
    // A false result still owns a worker/receiver. Keep it reachable for retirement retry.
    if (stoppedCache) cache = null
    return stoppedCache
  }

  private fun checkInstanceMethod(modifiers: Int) {
    check(Modifier.isPublic(modifiers) && Modifier.isFinal(modifiers) &&
      !Modifier.isStatic(modifiers) && !Modifier.isNative(modifiers) &&
      !Modifier.isAbstract(modifiers)) { "SystemUI app icon method is not a concrete public final instance method" }
  }

  private companion object {
    const val TAG = "KeiHomeSystemUiIcons"
    const val HOST_PACKAGE = "com.android.systemui"
    const val OWNER = "com.miui.systemui.graphics.AppIconsManager"
    const val OWNER_DESCRIPTOR = "Lcom/miui/systemui/graphics/AppIconsManager;"
    const val BITMAP_METHOD = "$OWNER_DESCRIPTOR->getAppIconBitmap(ILjava/lang/String;)Landroid/graphics/Bitmap;"
    const val CACHE_METHOD = "$OWNER_DESCRIPTOR->getAppIconBitmapCache(Ljava/lang/String;IZ)Landroid/graphics/Bitmap;"
    const val LOAD_METHOD = "$OWNER_DESCRIPTOR->loadAppIcon(Ljava/lang/String;ILandroid/content/pm/ApplicationInfo;Landroid/content/pm/PackageManager;)Landroid/graphics/drawable/Drawable;"
    const val ICONS_FIELD = "$OWNER_DESCRIPTOR->mIconsCache:Landroid/util/SparseArray;"
    const val QUIET_ICONS_FIELD = "$OWNER_DESCRIPTOR->mQuietFancyIconsCache:Landroid/util/SparseArray;"
    const val CONVERT_METHOD = "Lcom/miui/utils/DrawableUtils;->drawable2ImmutableBitmap(Landroid/graphics/drawable/Drawable;II)Landroid/graphics/Bitmap;"
    const val APP_ICON_CONSUMER = "Lcom/android/systemui/statusbar/notification/row/NotificationRowContentBinderInjectorImpl;->getAppIcon(Landroid/app/Notification;)Landroid/graphics/drawable/Icon;"
    const val HOOK_ID = "systemui.app-icons.get-bitmap.v1"
  }
}
