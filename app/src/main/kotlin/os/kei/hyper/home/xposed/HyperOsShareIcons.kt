package os.kei.hyper.home.xposed

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.FieldUsingType

/** Preserve action-specific artwork, direct-share avatars and other users' profile badges. */
internal fun shareAppIdentity(info: ResolveInfo, user: Int): Pair<String, String>? {
  val activity = info.activityInfo ?: return null
  val app = activity.applicationInfo ?: return null
  if (app.uid < 0 || app.uid / 100_000 != user || !info.resolvePackageName.isNullOrEmpty()) return null
  if (info.icon != 0 && info.icon != app.icon) return null
  if (activity.icon != 0 && activity.icon != app.icon) return null
  return app.packageName?.takeIf { it.isNotBlank() }?.let {
    it to (activity.name ?: return null)
  }
}

/** Reuse the wrapper and avoid invalidating ImageView when its image is unchanged. */
internal class ShareIconPresentation(val original: Drawable?) {
  private var bitmap: Bitmap? = null
  private var replacement: BitmapDrawable? = null

  fun apply(view: ImageView, next: Bitmap?, pending: Boolean = false) {
    if (next == null && pending && replacement != null) {
      if (view.drawable !== replacement) view.setImageDrawable(replacement)
      return
    }
    if (next !== bitmap) {
      bitmap = next
      replacement = next?.let { BitmapDrawable(view.resources, it) }
    }
    val desired = replacement ?: original
    if (view.drawable !== desired) view.setImageDrawable(desired)
  }
}

internal data class HyperOsShareProfile(
  val bind: Method,
  val icon: Field,
  val boundInfo: Field,
  val ordinaryTarget: Class<*>,
  val groupedTarget: Class<*>?,
  val resolveInfo: Method,
  val displayHolder: Method,
  val displayIcon: Method,
) {
  fun accepts(type: Class<*>?): Boolean = type != null && (type == ordinaryTarget || type == groupedTarget)
  companion object {
    const val PACKAGE = "com.android.intentresolver"
    const val HOLDER = "com.android.intentresolver.adapter.GroupTargetAdapter\$IconViewHolder"
    private const val TARGET = "Lcom/android/intentresolver/chooser/TargetInfo;"
    private const val HOLDER_D = "Lcom/android/intentresolver/adapter/GroupTargetAdapter\$IconViewHolder;"

    @OptIn(DexKitExperimentalApi::class)
    fun resolve(context: Context, loader: ClassLoader): HyperOsShareProfile {
      check(Looper.myLooper() != Looper.getMainLooper())
      val holder = Class.forName(HOLDER, false, loader)
      val target = Class.forName("com.android.intentresolver.chooser.TargetInfo", false, loader)
      val ordinary = Class.forName("com.android.intentresolver.chooser.DisplayResolveInfo", false, loader)
      val grouped = runCatching {
        Class.forName("com.android.intentresolver.chooser.MultiDisplayResolveInfo", false, loader)
          .takeIf {
            it.superclass == ordinary &&
              it.getMethod("getResolveInfo").declaringClass == ordinary &&
              it.getMethod("getDisplayIconHolder").declaringClass == ordinary
          }
      }.getOrNull()
      val adapter = Class.forName("com.android.intentresolver.adapter.GroupTargetAdapter", false, loader)
      val recyclerHolder = Class.forName("androidx.recyclerview.widget.RecyclerView\$ViewHolder", false, loader)
      val bind = adapter.getDeclaredMethod("onBindGroupTargetItem", recyclerHolder, Int::class.javaPrimitiveType)
      val bound = holder.getDeclaredField("mBoundInfo")
      require(bound.type == target)
      val icon = holder.getDeclaredField("icon")
      val info = ordinary.getDeclaredMethod("getResolveInfo")
      val displayHolder = ordinary.getDeclaredMethod("getDisplayIconHolder")
      val displayIcon = displayHolder.returnType.getMethod("getDisplayIcon")
      require(bind.returnType == Void.TYPE && !Modifier.isStatic(bind.modifiers))
      require(icon.type == ImageView::class.java && !Modifier.isStatic(icon.modifiers))
      require(info.returnType == ResolveInfo::class.java && target.isAssignableFrom(ordinary))
      SystemUiMaterialDexKit.open(context.applicationInfo.sourceDir, context.codeCacheDir).use { dex ->
        dex.setThreadNum(1)
        val binding = checkNotNull(dex.getMethodData("Lcom/android/intentresolver/adapter/GroupTargetAdapter;->onBindGroupTargetItem(Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;I)V"))
        val calls = binding.invokes.map { it.descriptor }.toSet()
        require(calls.containsAll(setOf(
          "$TARGET->getDisplayIconHolder()Lcom/android/intentresolver/chooser/TargetInfo\$IconHolder;",
          "Lcom/android/intentresolver/chooser/TargetInfo\$IconHolder;->getDisplayIcon()Landroid/graphics/drawable/Drawable;",
          "Landroid/widget/ImageView;->setImageDrawable(Landroid/graphics/drawable/Drawable;)V",
        )))
        require(binding.usingFields.any {
          it.usingType == FieldUsingType.Read && it.field.descriptor == "$HOLDER_D->icon:Landroid/widget/ImageView;"
        })
        require(binding.usingFields.any {
          it.usingType == FieldUsingType.Write && it.field.descriptor == "$HOLDER_D->mBoundInfo:$TARGET"
        })
        // This OEM role distinguishes MiIntentResolver from the AOSP chooser.
        val oem = checkNotNull(dex.getMethodData(
          "Lcom/android/intentresolver/TargetPresentationGetterStubImpl;->getBadgedIcon(" +
            "Landroid/content/pm/PackageManager;Landroid/content/pm/PackageItemInfo;" +
            "Landroid/content/pm/ApplicationInfo;)Landroid/graphics/drawable/Drawable;",
        ))
        require(oem.invokes.map { it.descriptor }.containsAll(listOf(
          "Landroid/content/pm/PackageManager;->loadUnbadgedItemIcon(Landroid/content/pm/PackageItemInfo;Landroid/content/pm/ApplicationInfo;)Landroid/graphics/drawable/Drawable;",
          "Landroid/content/pm/PackageManager;->getUserBadgedIcon(Landroid/graphics/drawable/Drawable;Landroid/os/UserHandle;)Landroid/graphics/drawable/Drawable;",
        )))
        val getter = checkNotNull(dex.getMethodData(
          "Lcom/android/intentresolver/chooser/DisplayResolveInfo;->getResolveInfo()Landroid/content/pm/ResolveInfo;",
        ))
        require(getter.usingFields.any {
          it.usingType == FieldUsingType.Read && it.field.typeName == "android.content.pm.ResolveInfo"
        })
        for (descriptor in listOf(
          "Lcom/android/intentresolver/chooser/DisplayResolveInfo;->getDisplayIconHolder()Lcom/android/intentresolver/chooser/TargetInfo\$IconHolder;",
          "Lcom/android/intentresolver/chooser/TargetInfo\$SettableIconHolder;->getDisplayIcon()Landroid/graphics/drawable/Drawable;",
        )) {
          val nativeGetter = checkNotNull(dex.getMethodData(descriptor))
          require(nativeGetter.invokes.isEmpty() && nativeGetter.usingFields.any { it.usingType == FieldUsingType.Read })
        }
      }
      icon.isAccessible = true
      info.isAccessible = true
      bound.isAccessible = true
      displayHolder.isAccessible = true
      displayIcon.isAccessible = true
      return HyperOsShareProfile(bind, icon, bound, ordinary, grouped, info, displayHolder, displayIcon)
    }
  }
}

/** Only the OEM app-target bind is intercepted. ZIP/palette/bitmap work stays off the UI thread. */
internal class HyperOsShareIcons(
  private val xposed: XposedInterface,
  val classLoader: ClassLoader,
) {
  private data class Bound(val name: String, val activity: String, val presentation: ShareIconPresentation)
  private val lifecycle = ThemeCompatibilityLifecycle()
  private val main = Handler(Looper.getMainLooper())
  private val views = WeakHashMap<ImageView, Bound>() // main-thread only
  private val handles = mutableListOf<XposedInterface.HookHandle>()
  private val initialized = AtomicBoolean()
  private val replayQueued = AtomicBoolean()
  @Volatile private var stopped = false
  @Volatile private var cache: CrossSurfaceIconCache? = null
  private val replay = Runnable {
    replayQueued.set(false)
    if (!lifecycle.enter()) return@Runnable
    try {
      views.forEach { (view, bound) ->
        if (view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE)
          apply(view, bound)
      }
    } finally { lifecycle.leave() }
  }

  fun install(oldHooks: List<XposedInterface.HookHandle> = emptyList()) {
    synchronized(handles) { handles.addAll(oldHooks) }
    replace(Application::class.java.getDeclaredMethod("attach", Context::class.java), "attach") { chain ->
      val result = chain.proceed()
      if (lifecycle.enter()) try {
        initialize(chain.thisObject as Application)
      } finally { lifecycle.leave() }
      result
    }
    replace(Activity::class.java.getDeclaredMethod("onPostResume"), "resume") { chain ->
      val result = chain.proceed()
      if (lifecycle.enter()) try { queueReplay() } finally { lifecycle.leave() }
      result
    }
    val application = Class.forName("android.app.ActivityThread")
      .getDeclaredMethod("currentApplication").invoke(null) as? Application
    application?.let(::initialize)
  }

  private fun initialize(application: Application) {
    if (application.packageName != HyperOsShareProfile.PACKAGE || stopped ||
      !initialized.compareAndSet(false, true)) return
    val worker = Thread({
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
      if (!lifecycle.enter()) return@Thread
      try {
        val profile = HyperOsShareProfile.resolve(application, classLoader)
        val icons = CrossSurfaceIconCache(application, maxEntries = 64, systemPaletteEvents = true,
          eagerReload = false, refreshOnMinute = true) {
          queueReplay()
        }
        cache = icons
        icons.start()
        replace(profile.bind, "bind") { chain ->
          val result = chain.proceed()
          if (lifecycle.enter()) try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
              val holder = chain.getArg(0) ?: return@replace result
              if (profile.icon.declaringClass.isInstance(holder)) capture(profile, holder)
            }
          } catch (failure: Exception) {
            Log.w(TAG, "Keeping native share-target icon", failure)
          } finally { lifecycle.leave() }
          result
        }
        main.post { adoptVisibleRows(profile, application) }
        xposed.log(Log.INFO, TAG, "HyperOS app-target icons enabled; bounded async cache, native actions retained")
      } catch (failure: Exception) {
        Log.w(TAG, "OEM share contract unavailable; retaining native behavior", failure)
      } finally { lifecycle.leave() }
    }, "KeiHomeShareProfile")
    worker.start()
  }

  private fun queueReplay() {
    if (!stopped && replayQueued.compareAndSet(false, true) && !main.post(replay))
      replayQueued.set(false)
  }

  private fun capture(profile: HyperOsShareProfile, holder: Any, preload: Boolean = true) {
    val view = profile.icon.get(holder) as ImageView
    val previous = views.remove(view)
    val target = profile.boundInfo.get(holder)
    if (!profile.accepts(target?.javaClass)) return
    val info = profile.resolveInfo.invoke(target) as? ResolveInfo ?: return
    val identity = shareAppIdentity(info, Process.myUid() / 100_000) ?: return
    // The native holder is left unchanged, so a successor can recover the true
    // original even when the visible ImageView still contains the retired bitmap.
    val original = profile.displayIcon.invoke(profile.displayHolder.invoke(target)) as? Drawable
    if (views.size >= 128) views.entries.iterator().let { it.next(); it.remove() }
    val bound = previous?.takeIf {
      it.name == identity.first && it.activity == identity.second && it.presentation.original === original
    } ?: Bound(identity.first, identity.second, ShareIconPresentation(original))
    views[view] = bound
    if ((preload && !view.isAttachedToWindow) ||
      (view.isShown && view.windowVisibility == View.VISIBLE)) apply(view, bound)
  }

  /** Adopt attached OEM rows after reload; do not retain old callbacks or scan the heap. */
  private fun adoptVisibleRows(profile: HyperOsShareProfile, application: Application) {
    if (!lifecycle.enter()) return
    try {
      val threadType = Class.forName("android.app.ActivityThread")
      val thread = threadType.getDeclaredMethod("currentActivityThread").invoke(null)
      val activities = threadType.getDeclaredField("mActivities").apply { isAccessible = true }
        .get(thread) as? Map<*, *> ?: return
      val recycler = Class.forName("androidx.recyclerview.widget.RecyclerView", false, classLoader)
      val holder = recycler.getMethod("getChildViewHolder", View::class.java)
      var budget = 512
      fun walk(view: View) {
        if (--budget < 0) return
        if (recycler.isInstance(view.parent)) {
          val row = holder.invoke(view.parent, view)
          if (profile.icon.declaringClass.isInstance(row)) capture(profile, row, preload = false)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) {
          if (budget <= 0) break
          walk(view.getChildAt(i))
        }
      }
      for (record in activities.values.take(8)) {
        if (record == null || budget <= 0) continue
        val activity = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
          .get(record) as? Activity ?: continue
        if (activity.packageName == application.packageName && !activity.isFinishing)
          activity.window?.decorView?.let(::walk)
      }
      xposed.log(Log.INFO, TAG, "Adopted ${views.size} visible OEM app-target rows")
    } catch (failure: Exception) {
      Log.w(TAG, "Visible OEM rows will refresh on their next native bind", failure)
    } finally { lifecycle.leave() }
  }

  private fun apply(view: ImageView, bound: Bound) {
    val icons = cache
    val bitmap = icons?.get(bound.name, bound.activity)
    val pending = bitmap == null && icons != null && !icons.isMissing(bound.name, bound.activity)
    bound.presentation.apply(view, bitmap, pending)
  }

  private fun replace(method: Method, name: String, hooker: XposedInterface.Hooker) {
    val id = "keihome.hyperos.share.$name.v1"
    val old = synchronized(handles) { handles.singleOrNull { it.id == id && it.executable == method } }
    val installed = old?.replaceHook(hooker) ?: xposed.hook(method).setId(id).intercept(hooker)
    synchronized(handles) { handles.remove(old); handles += installed }
  }

  fun retire(publish: () -> Unit): Boolean = lifecycle.retire(workersStopped = {
    stopped = true
    main.removeCallbacksAndMessages(null)
    val complete = cache?.stop() ?: true
    main.removeCallbacksAndMessages(null)
    complete
  }) {
    for (handle in synchronized(handles) { handles.toList() }) {
      handle.unhook()
      synchronized(handles) { handles.remove(handle) }
    }
    views.clear()
    publish()
  }

  fun retirementStatus(): String = lifecycle.retirementFailure ?: "cleanup pending"

  private companion object { const val TAG = "KeiHomeShareIcons" }
}
