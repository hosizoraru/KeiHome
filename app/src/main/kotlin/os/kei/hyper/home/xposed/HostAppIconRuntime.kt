package os.kei.hyper.home.xposed

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageItemInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Only the owner's URI scheme is unambiguous; cloned/work-profile requests stay native. */
internal fun sidebarIconPackage(uri: String?): String? {
  val name = uri?.takeIf { it.startsWith("pkg_icon://") }?.removePrefix("pkg_icon://")
    ?: return null
  return name.takeIf {
    it.length <= 240 && it.split('.').let { parts ->
      parts.size >= 2 && parts.all { part ->
        part.isNotEmpty() && (part.first().isLetter() || part.first() == '_') &&
          part.all { c -> c.isLetterOrDigit() || c == '_' }
      }
    }
  }
}

/** Native views/animations remain owned by Sidebar; only their completed image is replaced. */
internal class HostAppIconRuntime(
  private val xposed: XposedInterface,
  val classLoader: ClassLoader,
  private val packageName: String,
) {
  private val lifecycle = ThemeCompatibilityLifecycle()
  private val main = Handler(Looper.getMainLooper())
  private val handles = mutableListOf<XposedInterface.HookHandle>()
  private val writing = ThreadLocal<Boolean>()
  private val requesting = ThreadLocal<ImageView>()
  private val started = AtomicBoolean()
  private val replayQueued = AtomicBoolean()
  private val views = WeakHashMap<ImageView, SidebarIconPresentation>()
  private val currentUser = Process.myUid() / 100000
  private val identities = AppIconIdentityRegistry()
  @Volatile private var cache: CrossSurfaceIconCache? = null
  @Volatile private var stopped = false
  @Volatile private var worker: Thread? = null
  private val replay = Runnable {
    replayQueued.set(false)
    if (!lifecycle.enter()) return@Runnable
    try {
      if (!stopped) views.entries.toList().forEach { (view, bound) ->
        if (view.isShown && view.windowVisibility == View.VISIBLE) apply(view, bound)
      }
    } finally { lifecycle.leave() }
  }

  fun install(oldHooks: List<XposedInterface.HookHandle> = emptyList()) {
    synchronized(handles) { handles.addAll(oldHooks) }
    replace(Application::class.java.getDeclaredMethod("onCreate"), "application") { chain ->
      if (!lifecycle.enter()) return@replace chain.proceed()
      try {
        val result = chain.proceed()
        start(chain.thisObject as Application)
        result
      } finally { lifecycle.leave() }
    }
    val application = Class.forName("android.app.ActivityThread")
      .getDeclaredMethod("currentApplication").invoke(null) as? Application
    if (application != null) start(application)
  }

  private fun start(application: Application) {
    if (!started.compareAndSet(false, true)) return
    val task = Thread({
      if (!lifecycle.enter()) return@Thread
      try {
        if (stopped) return@Thread
        val profile = if (packageName.substringBefore(':') == SIDEBAR_PACKAGE) runCatching {
          HyperOsSidebarProfile.resolve(application, classLoader)
        }.onFailure {
          xposed.log(Log.WARN, TAG, "Sidebar-specific path unavailable; public icon path retained", it)
        }.getOrNull() else null
        if (stopped) return@Thread
        val icons = CrossSurfaceIconCache(
          application, maxEntries = 48, eagerReload = false, refreshOnMinute = true,
          onReady = ::queueReplay,
        )
        cache = icons
        icons.start()
        installPublicIconMethods(application)
        profile?.grid?.let { grid ->
          replace(grid.bind, "grid.bind") { chain ->
            if (Looper.myLooper() != Looper.getMainLooper() ||
              !lifecycle.enter()) return@replace chain.proceed()
            try {
              val view = chain.thisObject?.let(grid::imageView)
              val identity = chain.getArg(0)?.let(grid::identity)
              if (view != null) capture(view, identity?.takeIf { it.second / 100000 == currentUser }
                ?.let { "pkg_icon://${it.first}" })
              val result = chain.proceed()
              if (view != null) views[view]?.let { apply(view, it) }
              result
            } finally { lifecycle.leave() }
          }
        }
        for ((index, method) in profile?.bindMethods.orEmpty()
          .filter { it == profile?.rail?.bind }.withIndex()) {
          replace(method, "bind.$index") { chain ->
            if (!lifecycle.enter()) return@replace chain.proceed()
            try {
              if (method == profile?.rail?.bind && Looper.myLooper() == Looper.getMainLooper()) {
                val holder = chain.getArg(0)
                val view = holder?.let(profile.rail::imageView)
                if (view != null) {
                  val identity = chain.thisObject?.let(profile.rail::identity)
                  capture(view, identity?.takeIf { it.second / 100000 == currentUser }
                    ?.let { "pkg_icon://${it.first}" })
                }
              }
              chain.proceed()
            } finally {
              lifecycle.leave()
            }
          }
        }
        for ((index, method) in profile?.requestMethods.orEmpty().withIndex()) {
          replace(method, "request.$index") { chain ->
            if (Looper.myLooper() != Looper.getMainLooper() ||
              !lifecycle.enter()) return@replace chain.proceed()
            try {
              val view = chain.getArg(1) as? ImageView
              if (view != null) capture(view, chain.getArg(0) as? String)
              val prior = requesting.get()
              if (view != null) requesting.set(view)
              try {
                val result = chain.proceed()
                if (view != null) views[view]?.let { apply(view, it) }
                result
              } finally {
                if (prior == null) requesting.remove() else requesting.set(prior)
              }
            } finally { lifecycle.leave() }
          }
        }
        replace(ImageView::class.java.getDeclaredMethod("setImageDrawable", Drawable::class.java),
          "drawable") { chain ->
          val view = chain.thisObject as ImageView
          if (writing.get() == true || Looper.myLooper() != Looper.getMainLooper() ||
            !lifecycle.enter()) return@replace chain.proceed()
          try {
            val original = chain.getArg(0) as? Drawable
            associate(view, original, null)
            val bound = views[view] ?: return@replace chain.proceed()
            bound.original = original
            save(view, bound)
            val bitmap = cache?.get(bound.name) ?: return@replace chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = bound.drawable(view, bitmap)
            chain.proceed(args)
          } finally { lifecycle.leave() }
        }
        replace(ImageView::class.java.getDeclaredMethod("setImageBitmap", Bitmap::class.java),
          "bitmap") { chain ->
          val view = chain.thisObject as ImageView
          if (writing.get() == true || Looper.myLooper() != Looper.getMainLooper() ||
            !lifecycle.enter()) return@replace chain.proceed()
          try {
            val original = chain.getArg(0) as? Bitmap
            associate(view, null, original)
            val bound = views[view] ?: return@replace chain.proceed()
            bound.original = original?.let { BitmapDrawable(view.resources, it) }
            save(view, bound)
            val bitmap = cache?.get(bound.name) ?: return@replace chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = bitmap
            chain.proceed(args)
          } finally { lifecycle.leave() }
        }
        for ((name, type) in listOf("setImageResource" to Int::class.javaPrimitiveType,
          "setImageIcon" to Icon::class.java, "setImageURI" to Uri::class.java)) {
          replace(ImageView::class.java.getDeclaredMethod(name, type), name) { chain ->
            val view = chain.thisObject as ImageView
            val onMain = Looper.myLooper() == Looper.getMainLooper()
            if (!onMain || !lifecycle.enter()) return@replace chain.proceed()
            try {
              val nativeLoadingPlaceholder = name == "setImageResource" && chain.getArg(0) == 0 &&
                views[view]?.sticky == true
              if (!nativeLoadingPlaceholder && requesting.get() !== view) {
                views.remove(view)
                clearTag(view)
              }
              chain.proceed()
            } finally { lifecycle.leave() }
          }
        }
        for (name in listOf("onAttachedToWindow", "onWindowVisibilityChanged", "onVisibilityChanged")) {
          val method = when (name) {
            "onAttachedToWindow" -> View::class.java.getDeclaredMethod(name)
            "onWindowVisibilityChanged" -> View::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            else -> View::class.java.getDeclaredMethod(name, View::class.java, Int::class.javaPrimitiveType)
          }
          replace(method, name) { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as? ImageView
            if (view != null && Looper.myLooper() == Looper.getMainLooper() &&
              view.getTag(TAG_KEY) != null) queueReplay()
            result
          }
        }
        main.post {
          if (!lifecycle.enter()) return@post
          try { if (!stopped) adoptWindows() } finally { lifecycle.leave() }
        }
        xposed.log(Log.INFO, TAG,
          "Shared app-icon pipeline installed in $packageName; ${profile?.bindMethods?.size ?: 0} sidebar binds")
      } catch (failure: Exception) {
        xposed.log(Log.WARN, TAG, "Sidebar icon profile unavailable; retaining native images", failure)
      } finally { lifecycle.leave() }
    }, "KeiHomeHostIconProfiles")
    worker = task
    task.start()
  }

  private fun installPublicIconMethods(application: Application) {
    val pmType = Class.forName("android.app.ApplicationPackageManager", false, classLoader)
    val methods = pmType.declaredMethods.filter {
      it.name == "loadUnbadgedItemIcon" && it.returnType == Drawable::class.java &&
        it.parameterTypes.contentEquals(arrayOf(PackageItemInfo::class.java, ApplicationInfo::class.java))
    }.toMutableList()
    runCatching { Class.forName("com.miui.maml.util.AppIconsHelper", false, classLoader) }
      .getOrNull()?.declaredMethods?.filterTo(methods) {
        it.name == "getIconDrawable" && it.returnType == Drawable::class.java &&
          it.parameterTypes.contentEquals(arrayOf(android.content.Context::class.java,
            PackageItemInfo::class.java, android.content.pm.PackageManager::class.java))
      }
    check(methods.isNotEmpty()) { "No authenticated application-icon API available" }
    for ((index, method) in methods.distinct().withIndex()) {
      replace(method, "public.$index") { chain ->
        if (!lifecycle.enter()) return@replace chain.proceed()
        try {
          val item = chain.args.filterIsInstance<PackageItemInfo>().firstOrNull()
          val info = chain.args.filterIsInstance<ApplicationInfo>().firstOrNull()
            ?: (item as? ComponentInfo)?.applicationInfo ?: item as? ApplicationInfo
          val original = chain.proceed()
          if (item == null || info == null || info.uid / 100000 != currentUser ||
            sidebarIconPackage("pkg_icon://${item.packageName}") == null ||
            original !is Drawable) return@replace original
          val name = item.packageName
          val bitmap = cache?.get(name)
          val replacement = bitmap?.let { BitmapDrawable(application.resources, it) } ?: try {
            original.constantState?.newDrawable(application.resources)?.mutate() ?: original
          } catch (_: Exception) { original }
          identities.remember(replacement, name)
          if (bitmap != null) identities.remember(bitmap, name)
          replacement
        } finally { lifecycle.leave() }
      }
    }
  }

  private fun associate(view: ImageView, drawable: Drawable?, bitmap: Bitmap?) {
    if (views[view]?.sticky == true) return
    val name = identities.find(drawable, bitmap)
    if (name != null) {
      val prior = views[view]
      if (prior?.name != name) {
        if (views.size >= 96) {
          val oldest = views.entries.iterator().let { it.next().also { _ -> it.remove() } }
          clearTag(oldest.key)
        }
        views[view] = SidebarIconPresentation(name, drawable)
      }
    } else if (views[view]?.sticky == false) {
      views.remove(view)
      clearTag(view)
    }
  }

  private fun capture(view: ImageView, uri: String?) {
    val name = sidebarIconPackage(uri)
    val prior = views.remove(view)
    if (name == null) {
      clearTag(view)
      return
    }
    if (views.size >= 96) {
      val oldest = views.entries.iterator().let { it.next().also { _ -> it.remove() } }
      clearTag(oldest.key)
    }
    val bound = if (prior?.name == name && prior.sticky) prior
      else SidebarIconPresentation(name, view.drawable, true)
    views[view] = bound
    save(view, bound)
  }

  private fun save(view: ImageView, bound: SidebarIconPresentation) {
    // Bootstrap containers and the native fallback only; no module callback or runtime.
    val prior = view.getTag(TAG_KEY)
    if (prior == null || (prior as? Array<*>)?.firstOrNull() == TAG_MAGIC)
      view.setTag(TAG_KEY, arrayOf(TAG_MAGIC, bound.name, bound.original, bound.sticky))
  }

  private fun clearTag(view: ImageView) {
    if ((view.getTag(TAG_KEY) as? Array<*>)?.firstOrNull() == TAG_MAGIC)
      view.setTag(TAG_KEY, null)
  }

  private fun apply(view: ImageView, bound: SidebarIconPresentation) {
    val bitmap = cache?.get(bound.name)
    val desired = bound.image(view, bitmap, cache?.isMissing(bound.name) == false)
    if (view.drawable !== desired) {
      writing.set(true)
      try { view.setImageDrawable(desired) } finally { writing.remove() }
    }
  }

  private fun queueReplay() {
    if (!stopped && replayQueued.compareAndSet(false, true) && !main.post(replay))
      replayQueued.set(false)
  }

  private fun adoptWindows() {
    try {
      val type = Class.forName("android.view.WindowManagerGlobal")
      val owner = type.getDeclaredMethod("getInstance").invoke(null)
      val roots = type.getDeclaredField("mViews").apply { isAccessible = true }.get(owner) as? List<*>
        ?: return
      var budget = 768
      fun walk(view: View) {
        if (--budget < 0) return
        if (view is ImageView && views.size < 96) {
          val state = view.getTag(TAG_KEY) as? Array<*>
          if (state?.size == 4 && state[0] == TAG_MAGIC) {
            val name = state[1] as? String
            if (sidebarIconPackage("pkg_icon://$name") == name && name != null) {
              val bound = SidebarIconPresentation(name, state[2] as? Drawable, state[3] == true)
              views[view] = bound
              if (view.isShown) apply(view, bound)
            }
          }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
          if (budget <= 0) break
          walk(view.getChildAt(index))
        }
      }
      roots.take(16).filterIsInstance<View>().forEach(::walk)
    } catch (failure: Exception) {
      xposed.log(Log.INFO, TAG, "Visible rows will refresh on native rebind", failure)
    }
  }

  private fun replace(method: Method, name: String, hooker: XposedInterface.Hooker) {
    val id = "keihome.host.appicons.$name.v1"
    val old = synchronized(handles) { handles.singleOrNull { it.id == id && it.executable == method } }
    val installed = old?.replaceHook(hooker) ?: xposed.hook(method).setId(id).intercept(hooker)
    synchronized(handles) { handles.remove(old); handles += installed }
  }

  fun retire(publish: () -> Unit): Boolean = lifecycle.retire(workersStopped = {
    stopped = true
    main.removeCallbacksAndMessages(null)
    val task = worker
    if (task?.isAlive == true) task.join(1_000)
    val complete = task?.isAlive != true && (cache?.stop() ?: true)
    main.removeCallbacksAndMessages(null)
    complete
  }) {
    for (handle in synchronized(handles) { handles.toList() }) {
      handle.unhook()
      synchronized(handles) { handles.remove(handle) }
    }
    views.clear()
    identities.clear()
    publish()
  }

  fun retirementStatus(): String = lifecycle.retirementFailure ?: "cleanup pending"

  companion object {
    const val SIDEBAR_PACKAGE = "com.miui.securitycenter"
    const val SETTINGS_PACKAGE = "com.android.settings"
    fun supports(packageName: String) = packageName == SIDEBAR_PACKAGE ||
      packageName == "$SIDEBAR_PACKAGE:ui" || packageName == SETTINGS_PACKAGE
    private const val TAG = "KeiHomeHostIcons"
    // Outside the host's 0x7f resource namespace. Preserve an unexpected existing value.
    private const val TAG_KEY = 0x4b480001
    private const val TAG_MAGIC = "keihome.sidebar.icon.v1"
  }
}

internal class SidebarIconPresentation(
  val name: String, var original: Drawable?, val sticky: Boolean = false,
) {
  private var bitmap: Bitmap? = null
  private var replacement: BitmapDrawable? = null

  fun drawable(view: ImageView, next: Bitmap): BitmapDrawable {
    if (bitmap !== next) {
      bitmap = next
      replacement = BitmapDrawable(view.resources, next)
    }
    return checkNotNull(replacement)
  }

  fun image(view: ImageView, next: Bitmap?, pending: Boolean): Drawable? =
    next?.let { drawable(view, it) } ?: if (pending) replacement ?: original else original
}
