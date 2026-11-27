package os.kei.hyper.home.xposed

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import os.kei.hyper.home.iconpack.validDottedName
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData

internal const val SYSTEM_UI_APP_ICON_HOOK_ID = "systemui.app-icons.get-bitmap.v1"

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
  private val deoptimize: (Method) -> Unit,
  private val reportFailure: (String, Throwable) -> Unit,
  private val report: (Int, String, String) -> Unit = { priority, tag, message ->
    Log.println(priority, tag, message)
    Unit
  },
) {
  @Volatile private var cache: CrossSurfaceIconCache? = null
  @Volatile private var statusBar: SystemUiStatusBarIconRuntime? = null
  @Volatile private var folded: SystemUiFoldedIconReplay? = null
  @Volatile private var nativeCaches: SystemUiNativeIconCaches? = null
  private val foldGuard = SystemUiHookGuard { reportFailure("native folded icon payload", it) }
  private val foldedRequest = ThreadLocal<Boolean>()
  private val foldedSources = LinkedHashMap<String, java.lang.ref.WeakReference<Bitmap>>()
  private var foldedInitialized = false
  @Volatile private var notificationRows: SystemUiNotificationRowReplay? = null
  @Volatile private var island: SystemUiIslandAppIconAdapter? = null
  private val islandLock = Any()
  @Volatile private var stopped = false
  private val currentUser = Process.myUid() / 100000
  private val guard = SystemUiHookGuard { reportFailure("notification app icon lookup", it) }
  private val rowGuard = SystemUiHookGuard { reportFailure("native notification row replay", it) }
  private class Request { var name: String? = null }
  private data class Profile(val bitmap: Method, val consumer: Method)
  private val request = ThreadLocal<Request>()
  private val nativeRequest = ThreadLocal<android.service.notification.StatusBarNotification>()
  private val writing = ThreadLocal<Boolean>()
  private val bindings = SystemUiAppIconBindings()
  private val main = Handler(Looper.getMainLooper())
  private val replayQueued = AtomicBoolean()
  private val boundLogged = AtomicBoolean()
  private val appliedLogged = AtomicBoolean()
  private val customLogged = AtomicBoolean()
  private val statusBarLogged = AtomicBoolean()
  private val replay = Runnable {
    replayQueued.set(false)
    if (stopped || !lifecycle.enter()) return@Runnable
    try {
      bindings.snapshot().forEach { bound -> guard.run(Unit) { apply(bound) } }
      statusBar?.replay()
      folded?.request()
      rowGuard.run(Unit) {
        val refreshed = notificationRows?.replay() ?: 0
        if (refreshed > 0) report(Log.INFO, TAG, "Native notification handoff rows refreshed=$refreshed")
      }
    } finally { lifecycle.leave() }
  }

  /**
   * Main thread, like the framework's own theme-change check: ThemeZipFile readers are not
   * synchronized with its reopen, and SystemUI reads native icons on this thread.
   */
  private val refreshNative = Runnable {
    if (stopped || !lifecycle.enter()) return@Runnable
    try {
      guard.run(Unit) {
        val dropped = nativeCaches?.refresh() ?: return@run
        report(Log.INFO, TAG, "Native app-icon caches refreshed; dropped=${dropped.size}")
        statusBar?.adoptNative(dropped)
      }
    } finally { lifecycle.leave() }
  }

  // Injected SystemUI hooks require LSPosed hidden-API access; the app UI does not call this path.
  @SuppressLint("BlockedPrivateApi", "SoonBlockedPrivateApi")
  fun start() {
    check(Looper.myLooper() != Looper.getMainLooper())
    check(cache == null && !stopped) { "SystemUI app icon runtime already started or stopped" }
    val profile = resolveProfile()
    // Match the tracked-view bound so replay cannot churn a smaller wanted set.
    val iconCache = CrossSurfaceIconCache(context, maxEntries = 64,
      systemPaletteEvents = true, refreshOnMinute = true, notifyOnMiss = true, watchThemeArchive = true,
      onReady = { queueReplay(refreshRows = true) },
      beforeAppearanceReload = { main.post(refreshNative) })
    cache = iconCache
    replace(profile.bitmap, HOOK_ID) { chain -> intercept(chain) }
    foldGuard.run(Unit) {
      val native = SystemUiFoldedIconProfile.resolve(context, profile.bitmap.declaringClass)
      report(Log.INFO, TAG, "Native folded icon graph verified; existing-owner lookup=${native.discoversExisting}")
      folded = SystemUiFoldedIconReplay(main) {
        if (!stopped && lifecycle.enter()) try {
          foldGuard.run(Unit) {
            val dependencies = synchronized(foldedSources) { foldedSources.toMap() }
            if (foldedInitialized && dependencies.none { (name, previous) ->
              cache?.get(name)?.let { !it.isRecycled && it !== previous.get() } == true
            }) return@run
            val count = native.rebuild()
            if (count > 0) {
              foldedInitialized = true
              report(Log.INFO, TAG, "Native folded icon payload refresh requested=$count")
            }
          }
        } finally { lifecycle.leave() }
      }
      replace(native.send, "$HOOK_ID.folded-native-owner.v1") { chain ->
        if (!stopped) foldGuard.run(Unit) { native.remember(chain.thisObject) }
        val previous = foldedRequest.get()
        foldedRequest.set(true)
        try { chain.proceed() } finally {
          if (previous == null) foldedRequest.remove() else foldedRequest.set(previous)
        }
      }
      replace(native.producer, "$HOOK_ID.drawable-producer.v1") { chain ->
        val original = chain.proceed()
        if (stopped || !lifecycle.enter()) original else try {
          guard.run(original) {
            val name = chain.getArg(0) as? String ?: return@run original
            val user = chain.getArg(1) as? Int ?: return@run original
            val info = chain.getArg(2) as? android.content.pm.ApplicationInfo ?: return@run original
            if (user != currentUser || info.packageName != name ||
              SystemUiAppIconIdentity.name(info, currentUser, name) == null) return@run original
            val snapshot = cache?.get(name)?.takeUnless { it.isRecycled }
            val result = snapshot?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) } ?: original
            if (result != null) bindings.remember(result, name)
            if (snapshot != null) bindings.remember(snapshot, name)
            if (foldedRequest.get() == true) synchronized(foldedSources) {
              foldedSources.remove(name)
              foldedSources[name] = java.lang.ref.WeakReference(snapshot)
              while (foldedSources.size > 64) foldedSources.remove(foldedSources.keys.first())
            }
            result
          }
        } finally { lifecycle.leave() }
      }
    }
    replace(profile.consumer, "$HOOK_ID.consumer", ::consume)
    val cachedProfile = guard.run<SystemUiNotificationAppIconProfile?>(null) {
      SystemUiNotificationAppIconProfile.resolve(context, loader)
    }
    if (cachedProfile != null) {
      cachedProfile.callers.forEach(deoptimize)
      notificationRows = rowGuard.run<SystemUiNotificationRowReplay?>(null) {
        val row = Class.forName(
          "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false,
          cachedProfile.method.declaringClass.classLoader)
        check(android.view.View::class.java.isAssignableFrom(row))
        val rebind = row.getDeclaredMethod("reInflateViews")
        check(!Modifier.isStatic(rebind.modifiers) && rebind.returnType == Void.TYPE)
        SystemUiNotificationRowReplay(row.asSubclass(android.view.View::class.java), rebind,
          currentUser, { cache?.isActive() == true })
      }
      cachedProfile.customMethod?.let { custom -> replace(custom, "$HOOK_ID.native-custom-header.v1") { chain ->
        val result = chain.proceed()
        if (!stopped && result is Drawable && lifecycle.enter()) {
          try { guard.run(Unit) {
            val notification = nativeRequest.get() ?: return@run
            if (notification.notification !== chain.getArg(0)) return@run
            cachedProfile.customIdentity(notification, currentUser)?.let {
              bindings.remember(result, it)
              logOnce(customLogged, "Authenticated custom application-header icon captured")
            }
          } } finally { lifecycle.leave() }
        }
        result
      } }
      replace(cachedProfile.method, "$HOOK_ID.native-cached-header.v1") {
        bindNative(it, cachedProfile)
      }
      report(Log.INFO, TAG, "Cached notification app-icon boundary enabled")
    }
    try {
      val statusLoader = requireNotNull(profile.bitmap.declaringClass.classLoader)
      val statusProfile = SystemUiStatusBarIconProfile.resolve(context, statusLoader)
      val nativeManager = requireNotNull(statusProfile.nativeManager).invoke(null,
        statusProfile.nativeCaches.first().declaringClass)
      val descriptorType = statusProfile.descriptorIcon.declaringClass
      val preload = guard.run<((Any, Drawable) -> Any)?>(null) {
        val method = descriptorType.getMethod("clone")
        val field = descriptorType.getField("preloadedIcon")
        check(method.returnType === descriptorType && field.type == Drawable::class.java &&
          !Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers))
        val copy: (Any, Drawable) -> Any = { descriptor, icon ->
          requireNotNull(method.invoke(descriptor)).also { field.set(it, icon) }
        }
        copy
      }
      val runtime = SystemUiStatusBarIconRuntime(context, statusProfile, lifecycle, currentUser,
        { name -> cache?.get(name) }, { failure -> reportFailure("status-bar app icon", failure) },
        ready = ::queueReplay, nativeManagerInstance = nativeManager,
        onBootstrap = { message -> report(Log.INFO, TAG, message) },
        snapshotMissing = { name -> cache?.isMissing(name) == true },
        withWeatherPreload = preload,
        isWeatherLauncherIcon = { notice, icon ->
          cachedProfile?.weatherLauncherIcon(notice, icon, currentUser) == true
        },
        onExistingImage = { view ->
          if (cachedProfile != null) rowGuard.run(Unit) {
            val notification = cachedProfile.existingNotification(view) ?: return@run
            bindWeatherHeader(view, notification, cachedProfile)
          }
        })
      statusBar = runtime
      guard.run(Unit) {
        nativeCaches = SystemUiNativeIconCaches(requireNotNull(nativeManager), statusProfile.nativeCaches, currentUser)
      }
      replace(statusProfile.consumer, "$HOOK_ID.statusbar-descriptor.v1") { chain ->
        val owner = chain.thisObject ?: return@replace chain.proceed()
        val entry = chain.getArg(0) ?: return@replace chain.proceed()
        runtime.descriptor(owner, entry, chain.getArg(1) as Boolean) { chain.proceed() }
      }
      replace(statusProfile.drawable, "$HOOK_ID.statusbar-drawable.v1") { chain ->
        val view = chain.thisObject as? ImageView ?: return@replace chain.proceed()
        val descriptor = chain.getArg(0) ?: return@replace chain.proceed()
        runtime.drawable(view, descriptor) {
          if (it === descriptor) chain.proceed() else chain.proceed(arrayOf(it))
        }
      }
      logOnce(statusBarLogged, "Status-bar app-icon descriptor and native refresh boundaries enabled")
    } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
      statusBar?.requestStop()
      statusBar = null
      reportFailure("status-bar app icon profile", failure)
    }
    replace(Icon::class.java.getDeclaredMethod("createWithBitmap", Bitmap::class.java),
      "$HOOK_ID.bitmap-provenance.v1") { chain ->
      val result = chain.proceed()
      val bitmap = chain.getArg(0) as? Bitmap
      val icon = result as? Icon
      if (!stopped && bitmap != null && icon != null && lifecycle.enter()) {
        try { guard.run(Unit) {
          bindings.rememberCreated(bitmap, icon)
          statusBar?.created(bitmap, icon)
        } } finally { lifecycle.leave() }
      }
      result
    }
    replace(Icon::class.java.getDeclaredMethod("loadDrawableAsUser", Context::class.java,
      Int::class.javaPrimitiveType), "$HOOK_ID.user-drawable-provenance.v1") { chain ->
      val result = chain.proceed()
      val icon = chain.thisObject as? Icon
      if (!stopped && icon != null && result is Drawable && lifecycle.enter()) {
        try { guard.run(Unit) {
          bindings.rememberLoaded(icon, result)
          statusBar?.loaded(icon, result)
        } } finally { lifecycle.leave() }
      }
      result
    }
    replace(ImageView::class.java.getDeclaredMethod("setImageBitmap", Bitmap::class.java),
      "$HOOK_ID.setImageBitmap-provenance.v1") { chain -> bind(chain, async = false) }
    for (name in listOf("setImageIcon", "setImageIconAsync")) {
      val method = ImageView::class.java.getDeclaredMethod(name, Icon::class.java)
      replace(method, "$HOOK_ID.$name") { chain -> bind(chain, name.endsWith("Async")) }
    }
    replace(ImageView::class.java.getDeclaredMethod("setImageDrawable", Drawable::class.java),
      "$HOOK_ID.setImageDrawable") { chain -> bind(chain, async = false) }
    replace(Icon::class.java.getDeclaredMethod("loadDrawable", Context::class.java),
      "$HOOK_ID.loadHeaderDrawable") { chain ->
      val result = chain.proceed()
      if (!stopped && lifecycle.enter()) {
        try { guard.run(Unit) {
          val icon = chain.thisObject as? Icon
          val drawable = result as? Drawable
          if (icon != null && drawable != null) bindings.rememberLoaded(icon, drawable)
        } } finally { lifecycle.leave() }
      }
      result
    }
    for ((name, type) in listOf(
      "setImageResource" to Int::class.javaPrimitiveType!!,
      "setImageURI" to android.net.Uri::class.java,
      "setImageResourceAsync" to Int::class.javaPrimitiveType!!,
      "setImageURIAsync" to android.net.Uri::class.java,
    )) {
      val method = ImageView::class.java.getDeclaredMethod(name, type)
      replace(method, "$HOOK_ID.$name") { chain ->
        if (!stopped && writing.get() != true) {
          (chain.thisObject as? ImageView)?.let { view -> guard.run(Unit) { bindings.clear(view) } }
        }
        chain.proceed()
      }
    }
    // Optional plugin capability. Its custom picture/media paths never enter this factory.
    var islandAdapter: SystemUiIslandAppIconAdapter? = null
    try {
      val islandProfile = SystemUiIslandAppIconProfile.resolve(context, loader)
      val adapter = SystemUiIslandAppIconAdapter(currentUser, lifecycle,
        snapshot = { name -> cache?.get(name) },
        remember = { drawable, name -> bindings.remember(drawable, name) },
        onFailure = { reportFailure("Super Island application icon lookup", it) })
      islandAdapter = adapter
      synchronized(islandLock) {
        check(!stopped) { "Application icon runtime retired during Super Island resolution" }
        island = adapter
      }
      replace(islandProfile.factory, "$HOOK_ID.island-app-factory.v1") { chain ->
        adapter.intercept(chain.getArg(0) as? String, chain.getArg(1) as? Int,
          chain.getArg(2) as? ApplicationInfo, chain.getArg(4) as? Context) { chain.proceed() }
      }
      guard.run(Unit) {
        val base = Class.forName("miui.systemui.dynamicisland.module.BaseIslandModuleViewHolder", false, loader)
        check(base.classLoader === loader)
        val pictures = base.getDeclaredMethod("getBitmapBundle").apply { isAccessible = true }
        val data = base.getDeclaredMethod("getDynamicIslandData").apply { isAccessible = true }
        val extras = data.returnType.getDeclaredMethod("getExtras").apply { isAccessible = true }
        check(pictures.returnType == android.os.Bundle::class.java &&
          extras.returnType == android.os.Bundle::class.java)
        replace(pictures, "$HOOK_ID.island-identity-pictures.v1") { chain ->
          val result = chain.proceed()
          if (!stopped && result is android.os.Bundle && lifecycle.enter()) try {
            guard.run(Unit) {
              val source = data.invoke(chain.thisObject) ?: return@run
              val values = extras.invoke(source) as? android.os.Bundle ?: return@run
              SystemUiIslandPictureBindings.remember(result, values, currentUser) { icon, name ->
                bindings.remember(icon, name)
                cache?.get(name)
              }
            }
          } finally { lifecycle.leave() }
          result
        }
      }
      report(Log.INFO, TAG, "Super Island native app-identity factory enabled; future binds only")
    } catch (failure: Throwable) {
      // Also fence a hook if registration succeeded before an optional reporter failed.
      islandAdapter?.stop()
      synchronized(islandLock) {
        if (island === islandAdapter) island = null
      }
      recoverableSystemUiFailure(failure)
      reportFailure("Super Island application icon profile", failure)
    }
    // Cold requests are retained without starting worker registration during bridge installation.
    iconCache.start()
    folded?.request()
    statusBar?.bootstrap()
    queueReplay()
    try {
      report(Log.INFO, TAG, "Narrow app-icon bridge enabled: $BITMAP_METHOD")
    } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
      Log.w(TAG, "App-icon bridge activation reporter unavailable", failure)
    }
  }

  @OptIn(DexKitExperimentalApi::class)
  private fun resolveProfile(): Profile {
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
    lateinit var consumerMethod: Method
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
      caller.isAccessible = true
      consumerMethod = caller
    }
    method.isAccessible = true
    return Profile(method, consumerMethod)
  }

  fun intercept(chain: XposedInterface.Chain): Any? {
    // An already-admitted cache probe must not resume native IO while retirement drains it.
    val status = statusBar
    if (status != null) {
      val user = chain.getArg(0) as? Int
      val name = chain.getArg(1) as? String
      if (user != null && name != null) status.probeProducer(user, name)?.let { return it.bitmap }
    }
    if (stopped || !lifecycle.enter()) return chain.proceed()
    try {
      val bitmap = guard.run<Bitmap?>(null) {
        val userId = chain.getArg(0) as? Int ?: return@run null
        val packageName = chain.getArg(1) as? String ?: return@run null
        if (stopped || userId != currentUser || !validDottedName(packageName) ||
          packageName.length > 240) return@run null
        request.get()?.name = packageName
        // The descriptor may outlive the theme. Keep its fallback sourced by SystemUI.
        if (status?.buildingDescriptor() == true) null
        else cache?.get(packageName)?.takeUnless { it.isRecycled }
      }
      // The declared return type is Bitmap. Native failures must escape unchanged.
      val result = bitmap ?: chain.proceed()
      val user = chain.getArg(0) as? Int
      val name = chain.getArg(1) as? String
      if (result is Bitmap && user == currentUser && name != null &&
        name.length <= 240 && validDottedName(name)) {
        guard.run(Unit) { bindings.remember(result, name) }
      }
      if (status != null) {
        if (user != null && name != null) status.produced(user, name, result)
      }
      return result
    } finally { lifecycle.leave() }
  }

  private fun consume(chain: XposedInterface.Chain): Any? {
    if (stopped || !lifecycle.enter()) return chain.proceed()
    val previous = request.get()
    val frame = Request()
    request.set(frame)
    try {
      val result = chain.proceed()
      return guard.run(result) optional@{
        val icon = result as? Icon ?: return@optional result
        if (stopped) return@optional result
        val name = frame.name ?: run {
          val notification = chain.getArg(0) as? Notification ?: return@optional result
          val extras = notification.extras ?: return@optional result
          SystemUiAppIconIdentity.name(
            extras.getParcelable("android.appInfo", ApplicationInfo::class.java),
            currentUser, extras.getString("miui.opPkg")) ?: return@optional result
        }
        // The native early custom-app-icon return never visits getAppIconBitmap.
        // Preserve it on a miss, but authenticate the exact Icon that will bind.
        val published = if (frame.name == null)
          cache?.get(name)?.takeUnless { it.isRecycled } else null
        val returned = if (published == null) icon else Icon.createWithBitmap(published)
        bindings.remember(returned, name)
        returned
      }
    } finally {
      if (previous == null) request.remove() else request.set(previous)
      lifecycle.leave()
    }
  }

  private fun <T> write(action: () -> T): T {
    val previous = writing.get()
    writing.set(true)
    return try { action() } finally {
      if (previous == null) writing.remove() else writing.set(previous)
    }
  }

  private fun bindNative(chain: XposedInterface.Chain, profile: SystemUiNotificationAppIconProfile): Any? {
    if (stopped || writing.get() == true || !lifecycle.enter()) return chain.proceed()
    val previous = nativeRequest.get()
    val notification = chain.getArg(1) as? android.service.notification.StatusBarNotification
    if (notification == null) nativeRequest.remove() else nativeRequest.set(notification)
    try {
      val result = chain.proceed()
      guard.run(Unit) {
        if (stopped) return@run
        val notification = chain.getArg(1) ?: return@run
        val view = chain.getArg(2) as? ImageView ?: return@run
        val frameworkNotice = notification as? android.service.notification.StatusBarNotification
        if (frameworkNotice != null && bindWeatherHeader(view, frameworkNotice, profile)) return@run
        val name = profile.identity(notification, currentUser) ?: return@run
        val cached = profile.cached(notification) ?: return@run
        if (view.drawable !== cached) {
          if (!bindings.associated(view, name)) bindings.clear(view)
          return@run
        }
        if (frameworkNotice != null &&
          notificationRows?.remember(view, frameworkNotice) == true) queueReplay()
        bindings.bindNative(view, cached, name)?.let(::boundReady)
      }
      return result
    } finally {
      if (previous == null) nativeRequest.remove() else nativeRequest.set(previous)
      lifecycle.leave()
    }
  }

  private fun bindWeatherHeader(
    view: ImageView, notification: android.service.notification.StatusBarNotification,
    profile: SystemUiNotificationAppIconProfile,
  ): Boolean {
    if (!profile.weatherHeader(notification, currentUser)) return false
    val original = view.drawable ?: return false
    val bound = bindings.bindNative(view, original, WeatherIconTemperature.PACKAGE) ?: return false
    boundReady(bound)
    return true
  }

  private fun bind(chain: XposedInterface.Chain, async: Boolean): Any? {
    if (stopped || writing.get() == true || !lifecycle.enter()) return chain.proceed()
    try {
      val view = chain.thisObject as? ImageView ?: return chain.proceed()
      val bound = guard.run<SystemUiAppIconBindings.Binding?>(null) {
        bindings.bind(view, chain.getArg(0))
      }
      // Keep native calls outside optional-work guards; native failures propagate.
      val result = if (bound == null) chain.proceed() else write { chain.proceed() }
      if (bound == null) return result
      if (async && result is Runnable) {
        val ready = bindings.afterNative(bound, Runnable { write { result.run() } }, ::boundReady)
        return Runnable {
          if (stopped || !lifecycle.enter()) {
            result.run()
          } else {
            try { ready.run() } finally { lifecycle.leave() }
          }
        }
      }
      if (!async) boundReady(bound)
      return result
    } finally { lifecycle.leave() }
  }

  private fun boundReady(bound: SystemUiAppIconBindings.Binding) {
    if (stopped || bindings.current(bound) == null) return
    logOnce(boundLogged, "Authenticated notification app-icon view bound")
    if (Looper.myLooper() == main.looper) guard.run(Unit) { apply(bound) } else queueReplay()
  }

  private fun apply(bound: SystemUiAppIconBindings.Binding) {
    if (stopped) return
    val view = bindings.current(bound) ?: return
    val bitmap = cache?.get(bound.name)?.takeUnless { it.isRecycled }
    if (bitmap == null) {
      // When Monet turns off for an uncovered app, trigger row re-inflation so native artwork returns.
      if (cache?.isMissing(bound.name) == true) notificationRows?.markPending()
      return
    }
    write { view.setImageBitmap(bitmap) }
    logOnce(appliedLogged, "Published notification icon snapshot applied")
  }

  private fun logOnce(flag: AtomicBoolean, message: String) {
    if (!flag.compareAndSet(false, true)) return
    try { report(Log.INFO, TAG, message) } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
    }
  }

  private fun queueReplay(refreshRows: Boolean = false) {
    if (refreshRows) notificationRows?.markPending()
    if (!stopped && replayQueued.compareAndSet(false, true) && !main.post(replay))
      replayQueued.set(false)
  }

  fun invalidate() {
    notificationRows?.markPending()
    cache?.invalidate()
  }

  fun retirementStatus(): String =
    "cache=${cache?.retirementStatus() ?: "released"}, statusbar=${statusBar?.retirementStatus() ?: "released"}"

  fun stop(deadline: Long = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)): Boolean {
    folded?.stop()
    folded = null
    synchronized(foldedSources) { foldedSources.clear() }
    synchronized(islandLock) {
      stopped = true
      island?.stop()
    }
    notificationRows?.clear()
    val stoppedStatus = try { statusBar?.stop(deadline) != false } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
      reportFailure("status-bar app icon stop", failure)
      false
    }
    // Keep the stopped probe gate until hook retirement drains already-admitted native calls.
    main.removeCallbacks(replay)
    main.removeCallbacks(refreshNative)
    nativeCaches = null
    replayQueued.set(false)
    bindings.clear()
    val iconCache = cache ?: return stoppedStatus
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
    return stoppedCache && stoppedStatus
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
    const val HOOK_ID = SYSTEM_UI_APP_ICON_HOOK_ID
  }
}
