package os.kei.hyper.home.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.util.SparseArray
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import os.kei.hyper.home.iconpack.validDottedName

internal object SystemUiStatusBarBitmapIdentity {
  fun <T : Any> match(bitmap: T, entries: List<Pair<String, T>>): String? {
    if (entries.size > 1_024) return null
    val names = entries.filter { it.second === bitmap }.map { it.first }.toSet()
    return names.singleOrNull()?.takeIf { it.length <= 240 && validDottedName(it) }
  }
}

/** At most one native cache probe per live owner; full capacity declines new probes. */
internal class SystemUiStatusBarProbeBudget(private val limit: Int = 64) {
  private val owners = ArrayList<WeakReference<Any>>()
  init { require(limit in 1..64) }
  @Synchronized fun claim(owner: Any): Boolean {
    owners.removeAll { it.get() == null }
    if (owners.any { it.get() === owner } || owners.size >= limit) return false
    owners += WeakReference(owner)
    return true
  }
  @Synchronized fun clear() = owners.clear()
  @Synchronized fun contains(owner: Any): Boolean = owners.any { it.get() === owner }
}

/** Worker-only inspection of initialized dependency values. Never calls get/provider/lazy APIs. */
internal object SystemUiStatusBarOwnerLookup {
  fun find(
    profile: SystemUiStatusBarEntryProfile, ownerType: Class<*>, application: Any? = null,
    cancelled: () -> Boolean = { false },
  ): Any? {
    val maps = ArrayList<Map<*, *>>()
    val registry = profile.registryRoots.firstOrNull()?.declaringClass ?: ownerType
    for (field in profile.registryRoots) {
      if (cancelled()) return null
      val value = field.get(null) ?: continue
      if (value is Map<*, *>) maps += value
      else if (registry.isInstance(value)) profile.registryMaps.forEach { map ->
        (map.get(value) as? Map<*, *>)?.let { maps += it }
      }
    }
    if (maps.size > 8) return null
    val queue = ArrayDeque<Pair<Any, Int>>()
    for (map in maps) {
      if (cancelled() || map.size > 512 || queue.size + map.size > 512) return null
      for (value in map.values) if (value != null) queue.addLast(value to 0)
    }
    val priority = SystemUiStatusBarOwnerPriority.inspect(ownerType, application,
      queue.map { it.first }, cancelled) { value ->
      value !is Context && value !is View && value !is Bitmap &&
        (value.javaClass.classLoader === ownerType.classLoader ||
          value.javaClass.classLoader === registry.classLoader)
    }
    if (priority.matched) return priority.owner
    if (application != null) queue.addFirst(application to -1)
    val seen = java.util.IdentityHashMap<Any, Boolean>()
    var found: Any? = null
    var fieldsRead = priority.fields
    fun adopt(value: Any?): Boolean {
      if (value == null || !ownerType.isInstance(value)) return true
      if (found != null && found !== value) return false
      found = value
      return true
    }
    fun fields(type: Class<*>): List<java.lang.reflect.Field> =
      generateSequence(type) { it.superclass }.takeWhile { it !== Any::class.java && it.classLoader != null }
        .flatMap { it.declaredFields.asSequence() }.toList()
    while (queue.isNotEmpty()) {
      if (cancelled() || seen.size >= 1_024) return null
      val (value, depth) = queue.removeFirst()
      if (seen.put(value, true) != null) continue
      if (ownerType.isInstance(value)) {
        if (found != null && found !== value) return null
        found = value
        continue
      }
      if (depth >= 3 || value is View || (value is Context && value !== application) || value is Bitmap) continue
      var type: Class<*>? = value.javaClass
      if (value !== application && type?.classLoader !== ownerType.classLoader &&
        type?.classLoader !== registry.classLoader) continue
      var fieldCount = 0
      while (type != null && type !== Any::class.java && fieldCount < 4_096) {
        if (type.classLoader == null) break
        for (field in type.declaredFields) {
          if (cancelled() || ++fieldCount > 4_096 || ++fieldsRead > 8_192) return null
          if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive || field.type === String::class.java) continue
          try { field.isAccessible = true } catch (failure: Throwable) {
            recoverableSystemUiFailure(failure)
            continue
          }
          val child = field.get(value) ?: continue
          if (ownerType.isInstance(child)) {
            if (!adopt(child)) return null
          } else if ((depth < 2 || value === application) && queue.size + seen.size < 1_024) {
            if (child is Context || child is View || child is Bitmap ||
              (child.javaClass.classLoader !== ownerType.classLoader &&
                child.javaClass.classLoader !== registry.classLoader)) continue
            // A DI provider may only reveal an already initialized volatile Object instance.
            val cached = fields(child.javaClass).filter {
              !Modifier.isStatic(it.modifiers) && Modifier.isVolatile(it.modifiers) &&
                it.type === Any::class.java
            }
            for (instance in cached.take(4)) {
              instance.isAccessible = true
              if (!adopt(instance.get(child))) return null
            }
            val factory = child.javaClass.interfaces.any { contract ->
              contract.methods.any { Modifier.isAbstract(it.modifiers) &&
                it.parameterCount == 0 && it.returnType === Any::class.java }
            }
            if (!factory && cached.isEmpty()) queue.addLast(child to depth + 1)
          }
        }
        type = type.superclass
      }
    }
    return found
  }
}

internal class SystemUiStatusBarIconBindings(private val limit: Int = 64) {
  private data class Source(val icon: WeakReference<Icon>, val name: String)
  class Binding(
    val view: WeakReference<ImageView>, val icon: WeakReference<Icon>, val name: String,
    val applied: WeakReference<Bitmap>,
    val usedSnapshot: Boolean,
  )
  private val sources = ArrayList<Source>()
  private val views = ArrayList<Binding>()
  init { require(limit in 1..64) }

  @Synchronized fun remember(icon: Icon, name: String) {
    sources.removeAll { it.icon.get() == null || it.icon.get() === icon }
    sources += Source(WeakReference(icon), name)
    while (sources.size > limit) sources.removeAt(0)
  }
  @Synchronized fun name(icon: Icon): String? {
    sources.removeAll { it.icon.get() == null }
    return sources.firstOrNull { it.icon.get() === icon }?.name
  }
  @Synchronized fun forget(view: ImageView) {
    views.removeAll { it.view.get() == null || it.view.get() === view }
  }
  @Synchronized fun bind(view: ImageView, icon: Icon, applied: Bitmap?, maybeSnapshot: Boolean = false) {
    val retainedSnapshot = views.firstOrNull {
      it.view.get() === view && it.name == (name(icon) ?: "")
    }?.usedSnapshot == true
    forget(view)
    val name = name(icon) ?: return
    views += Binding(WeakReference(view), WeakReference(icon), name, WeakReference(applied),
      applied != null || maybeSnapshot || retainedSnapshot)
    while (views.size > limit) views.removeAt(0)
  }
  @Synchronized fun snapshot(): List<Binding> {
    views.removeAll { it.view.get() == null || it.icon.get() == null }
    return views.toList()
  }
  @Synchronized fun current(binding: Binding): ImageView? =
    binding.view.get()?.takeIf { view ->
      views.any { it === binding } && binding.icon.get()?.let { name(it) == binding.name } == true &&
        views.any { it.view.get() === view && it === binding }
    }
  @Synchronized fun clear() { sources.clear(); views.clear() }
}

/**
 * Only adopts the native colored-app branch after exact nested producer/returned-Icon proof.
 * UI callbacks read the shared cache; decoding, final masking and IO remain on its worker.
 */
internal class SystemUiStatusBarIconRuntime(
  private val context: Context,
  private val profile: SystemUiStatusBarIconProfile,
  private val lifecycle: ThemeCompatibilityLifecycle,
  private val currentUser: Int,
  private val snapshot: (String) -> Bitmap?,
  onFailure: (Throwable) -> Unit,
  private val isMain: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
  private val ready: () -> Unit = {},
  private val nativeManagerInstance: Any? = null,
  private val onBootstrap: (String) -> Unit = {},
  private val snapshotMissing: (String) -> Boolean = { false },
  private val onExistingImage: (ImageView) -> Unit = {},
  private val isWeatherLauncherIcon: (StatusBarNotification, Icon) -> Boolean = { _, _ -> false },
  private val withWeatherPreload: ((Any, Drawable) -> Any)? = null,
) {
  class BitmapOverride(val bitmap: Bitmap?)
  private data class HeldBitmap(
    val name: String, val user: Int, val bitmap: Bitmap, val requireFactory: Boolean = false,
  )
  private class Request {
    var calls = 0
    var name: String? = null
    var bitmap: Bitmap? = null
    val icons = ArrayList<Icon>(1)
    var overflow = false
    var requireFactory = false
  }
  private class NativeDraw(val view: ImageView, val icon: Icon, val bitmap: Bitmap?) {
    var loaded = false
  }
  private val requests = ThreadLocal<Request>()
  fun buildingDescriptor(): Boolean = requests.get() != null
  fun ownsView(view: ImageView): Boolean = profile.drawable.declaringClass.isInstance(view)
  private val userHandle = UserHandle.getUserHandleForUid(currentUser * 100_000)
  private val recomputing = ThreadLocal<Boolean>()
  private val overrides = ThreadLocal<HeldBitmap>()
  private val nativeDraws = ThreadLocal<NativeDraw>()
  private val bindings = SystemUiStatusBarIconBindings()
  private val nativeDirty = java.util.concurrent.atomic.AtomicBoolean()
  private val probes = SystemUiStatusBarProbeBudget()
  private val descriptorGuard = SystemUiHookGuard(onFailure)
  private val drawableGuard = SystemUiHookGuard(onFailure)
  private val loadGuard = SystemUiHookGuard(onFailure)
  private val producerGuard = SystemUiHookGuard(onFailure)
  private val replayGuard = SystemUiHookGuard(onFailure)
  private val bootstrapGuard = SystemUiHookGuard(onFailure)
  private val main = Handler(Looper.getMainLooper())
  private val workerLock = Any()
  @Volatile private var bootstrapWorker: Thread? = null
  private data class Existing(val view: WeakReference<ImageView>, val icon: WeakReference<Icon>)
  private data class ExistingEntry(
    val manager: WeakReference<Any>, val entry: WeakReference<Any>, val pack: WeakReference<Any>,
    val view: WeakReference<ImageView>, val icon: WeakReference<Icon>,
    val notification: WeakReference<android.service.notification.StatusBarNotification>,
  )
  private val existingEntries = java.util.concurrent.CopyOnWriteArrayList<ExistingEntry>()
  private val witnessedOwners = SystemUiStatusBarProbeBudget()
  @Volatile private var witnessedOwner: WeakReference<Any>? = null
  private var bootstrapExisting: List<Existing> = emptyList()
  private var bootstrapRows: List<WeakReference<Any>> = emptyList()
  @Volatile private var stopped = false

  /** A non-null wrapper forbids the parent from falling through to the native IO producer. */
  fun probeProducer(user: Int, name: String): BitmapOverride? {
    val held = overrides.get() ?: return null
    val bitmap = held.bitmap.takeIf {
      !stopped && user == held.user && name == held.name && !it.isRecycled
    }
    produced(user, name, bitmap)
    return BitmapOverride(bitmap)
  }

  private fun cachedFor(descriptor: Any, sharedOnly: Boolean = false): HeldBitmap? {
    val name = profile.descriptorPackage?.get(descriptor) as? String ?: return null
    val user = profile.descriptorUser?.get(descriptor) as? UserHandle ?: return null
    if (user != userHandle || name.length > 240 || !validDottedName(name)) return null
    // The authenticated reader only inspects SparseArray/Map/WeakReference caches.
    overrides.get()?.takeIf { it.name == name && it.user == currentUser }?.let { return it }
    // The OEM quiet cache has no weather update clock. The app-icon descriptor
    // still needs to consume and return the shared frame through its own factory.
    val useShared = sharedOnly || name == WeatherIconTemperature.PACKAGE
    val manager = if (useShared) null else nativeManagerInstance
    val reader = profile.cacheReader
    val bitmap = if (manager != null && reader != null) {
      reader.invoke(manager, name, currentUser, true) as? Bitmap
    } else null
    if (bitmap != null && !bitmap.isRecycled) return HeldBitmap(name, currentUser, bitmap)
    // Prefer the descriptor's existing native bitmap as probe input so StatusBarIcon.icon
    // never permanently bakes a Monet snapshot that breaks Monet ON -> OFF revert.
    val existingNative = if (name == WeatherIconTemperature.PACKAGE) null else {
      (profile.descriptorIcon.get(descriptor) as? Icon)
        ?.takeIf { it.type == Icon.TYPE_BITMAP }
        ?.let { runCatching { profile.bitmap.invoke(it) as? Bitmap }.getOrNull() }
        ?.takeUnless { it.isRecycled }
    }
    // A ready shared snapshot is still checked so sharedOnly probes wait until artwork is warm.
    val ready = snapshot(name)?.takeUnless { it.isRecycled }
    val probeBitmap = existingNative?.takeIf { !sharedOnly || ready != null } ?: ready ?: return null
    return HeldBitmap(name, currentUser, probeBitmap, requireFactory = true)
  }

  fun produced(user: Int, name: String, bitmap: Any?) {
    if (stopped || !lifecycle.enter()) return
    try {
      producerGuard.run(Unit) {
        val request = requests.get() ?: return@run
        request.calls++
        if (request.calls == 1 && user == currentUser && validDottedName(name) && name.length <= 240 &&
          bitmap is Bitmap && !bitmap.isRecycled) {
          request.name = name
          request.bitmap = bitmap
        } else {
          request.name = null
          request.bitmap = null
        }
      }
    } finally { lifecycle.leave() }
  }

  /** Icon copies mutable input. Authenticate the factory's exact input and returned instance. */
  fun created(bitmap: Bitmap?, icon: Icon?) {
    if (stopped || !lifecycle.enter()) return
    try {
      producerGuard.run(Unit) {
        val request = requests.get() ?: return@run
        if (request.calls != 1 || bitmap == null || bitmap !== request.bitmap ||
          icon == null || icon.type != Icon.TYPE_BITMAP) return@run
        if (request.icons.size == 4) {
          request.overflow = true
          request.icons.clear()
        } else if (!request.overflow) request.icons += icon
      }
    } finally { lifecycle.leave() }
  }

  fun descriptor(owner: Any, entry: Any, people: Boolean, native: () -> Any?): Any? {
    if (stopped || people || recomputing.get() == true || !lifecycle.enter()) return native()
    witnessOwner(owner)
    val previous = requests.get()
    val request = Request().apply { requireFactory = overrides.get()?.requireFactory == true }
    requests.set(request)
    try {
      // Native exceptions on the ordinary path retain their original behavior.
      val result = native()
      return descriptorGuard.run(result) {
        if (stopped || result == null) return@run result
        if (adopt(result, request)) return@run result
        val icon = profile.descriptorIcon.get(result) as? Icon
        if (icon != null && bindings.name(icon) != null) return@run result
        if (!isMain() || request.calls != 0) return@run result
        val pack = profile.entryPack.get(entry) ?: return@run result
        // Live identity distinguishes small/people caches without relying on either field name.
        val field = profile.cached.filter { it.get(pack) === result }.singleOrNull() ?: return@run result
        val held = cachedFor(result) ?: return@run result
        if (!probes.claim(pack)) return@run result
        val saved = profile.cached.map { it.get(pack) }
        val previousOverride = overrides.get()
        var adopted = false
        try {
          field.set(pack, null)
          recomputing.set(true)
          overrides.set(held)
          request.requireFactory = held.requireFactory
          val fresh = profile.consumer.invoke(owner, entry, false)
          val otherCachesUnchanged = profile.cached.withIndex().all { (i, candidate) ->
            candidate == field || candidate.get(pack) === saved[i]
          }
          if (!stopped && fresh != null && field.get(pack) === fresh && otherCachesUnchanged &&
            adopt(fresh, request)) {
            adopted = true
            fresh
          } else result
        } finally {
          recomputing.remove()
          if (previousOverride == null) overrides.remove() else overrides.set(previousOverride)
          if (!adopted) profile.cached.forEachIndexed { i, candidate -> candidate.set(pack, saved[i]) }
        }
      }
    } finally {
      if (previous == null) requests.remove() else requests.set(previous)
      lifecycle.leave()
    }
  }

  private fun witnessOwner(owner: Any) {
    if (!isMain() || profile.bootstrap == null ||
      !profile.consumer.declaringClass.isInstance(owner) || !witnessedOwners.claim(owner)) return
    witnessedOwner = WeakReference(owner)
    post {
      if (stopped || !lifecycle.enter()) return@post
      try { replayGuard.run(Unit) {
        if (bootstrapExisting.isEmpty()) return@run
        onBootstrap("Status-bar bootstrap native-owner=true source=consumer")
        linkEntries(owner, bootstrapRows, bootstrapExisting)
        replayEntries()
        onBootstrap("Status-bar bootstrap entry-audit completed")
      } } finally { lifecycle.leave() }
    }
  }

  private fun adopt(descriptor: Any, request: Request): Boolean {
    val name = request.name ?: return false
    val bitmap = request.bitmap ?: return false
    if (request.calls != 1 || request.overflow || bitmap.isRecycled) return false
    val icon = profile.descriptorIcon.get(descriptor) as? Icon ?: return false
    val factoryWitness = request.icons.any { it === icon }
    if (icon.type != Icon.TYPE_BITMAP ||
      (!factoryWitness && (request.requireFactory || profile.bitmap.invoke(icon) !== bitmap))) return false
    bindings.remember(icon, name)
    return true
  }

  fun drawable(view: ImageView, descriptor: Any, native: (Any) -> Any?): Any? {
    if (stopped || !lifecycle.enter()) return native(descriptor)
    val previous = nativeDraws.get()
    try {
      // Clear an old association even if native binding subsequently fails.
      bindings.forget(view)
      val frame = drawableGuard.run<NativeDraw?>(null) {
        if (stopped || !isMain()) return@run null
        val icon = profile.descriptorIcon.get(descriptor) as? Icon ?: return@run null
        if (!owns(view, icon)) return@run null
        restoreIdentity(view, icon)
        val name = bindings.name(icon) ?: return@run null
        NativeDraw(view, icon, snapshot(name)?.takeUnless { it.isRecycled })
      }
      if (frame == null) nativeDraws.remove() else nativeDraws.set(frame)
      val preload = drawableGuard.run<Any?>(null) {
        val bitmap = frame?.bitmap ?: return@run null
        if (bindings.name(frame.icon) != WeatherIconTemperature.PACKAGE) return@run null
        withWeatherPreload?.invoke(descriptor, BitmapDrawable(context.resources, bitmap))
      }
      val result = native(preload ?: descriptor)
      return drawableGuard.run(result) {
        if (stopped || !isMain()) return@run result
        val icon = profile.descriptorIcon.get(descriptor) as? Icon ?: return@run result
        if (!owns(view, icon)) return@run result
        val name = bindings.name(icon) ?: return@run result
        bindings.bind(view, icon, frame?.bitmap?.takeIf { frame.loaded || preload != null })
        SystemUiStatusBarIconProof.write(view, icon, currentUser, name)
        result
      }
    } finally {
      if (previous == null) nativeDraws.remove() else nativeDraws.set(previous)
      lifecycle.leave()
    }
  }

  /** Replace only an authenticated leaf; OEM getIcon scaling/wrapping still executes afterward. */
  fun loaded(icon: Icon, native: Any?): Any? {
    if (stopped || !lifecycle.enter()) return native
    try {
      return loadGuard.run(native) {
        val frame = nativeDraws.get() ?: return@run native
        val bitmap = frame.bitmap ?: return@run native
        if (frame.icon !== icon || bitmap.isRecycled || !owns(frame.view, icon)) return@run native
        frame.loaded = true
        BitmapDrawable(context.resources, bitmap)
      }
    } finally { lifecycle.leave() }
  }

  private fun owns(view: ImageView, icon: Icon): Boolean {
    val current = profile.viewDescriptor.get(view) ?: return false
    return profile.descriptorIcon.get(current) === icon
  }

  private fun restoreIdentity(view: ImageView, icon: Icon): Boolean {
    if (!owns(view, icon)) return false
    val notice = profile.bootstrap?.viewNotification?.get(view) as? StatusBarNotification
    val name = if (notice != null && isWeatherLauncherIcon(notice, icon)) {
      WeatherIconTemperature.PACKAGE
    } else SystemUiStatusBarIconProof.read(view, icon, currentUser) ?: return false
    bindings.remember(icon, name)
    return true
  }

  /** Called by the parent's existing main-thread cache-ready/appearance replay. */
  fun replay() {
    if (stopped || !isMain() || !lifecycle.enter()) return
    try {
      replayGuard.run(Unit) {
        replayEntries()
        val refreshNativeBound = nativeDirty.getAndSet(false)
        var refreshed = 0
        for (binding in bindings.snapshot()) {
          if (stopped) break
          val view = bindings.current(binding) ?: continue
          val icon = binding.icon.get() ?: continue
          if (!owns(view, icon)) { bindings.forget(view); continue }
          val bitmap = snapshot(binding.name)?.takeUnless { it.isRecycled }
          if (bitmap == null) {
            val restoreMissed = binding.usedSnapshot && snapshotMissing(binding.name)
            if (!restoreMissed && !refreshNativeBound) continue
          } else if (binding.applied.get() === bitmap) continue
          // This re-enters drawable(), then preserves native scaling, tint and animation setup.
          profile.update.invoke(view, false)
          refreshed++
        }
        if (refreshed > 0) onBootstrap("Status-bar replay refreshed=$refreshed")
      }
    } finally { lifecycle.leave() }
  }

  fun requestStop() {
    synchronized(workerLock) {
      stopped = true
      main.removeCallbacksAndMessages(null)
    }
    bootstrapWorker?.interrupt()
    bindings.clear()
    probes.clear()
    existingEntries.clear()
    witnessedOwners.clear()
    witnessedOwner = null
    bootstrapExisting = emptyList()
    bootstrapRows = emptyList()
    // Admitted callbacks clear their own frames in finally; keep an in-flight IO gate closed.
  }

  /** One installation-triggered audit, also covering icons that receive no new notification. */
  fun bootstrap(roots: () -> List<View> = ::windowRoots) {
    if (stopped) return
    post {
      if (stopped || !lifecycle.enter()) return@post
      try {
        bootstrapGuard.run(Unit) {
          val windows = roots()
          if (windows.size > 16) return@run
          scanExisting(ArrayDeque(windows), ArrayList(), ArrayList(), 0)
        }
      } finally { lifecycle.leave() }
    }
  }

  /**
   * Main-thread rescan triggered on appearance/palette changes so already-visible status-bar
   * icons whose descriptors were not yet bound are linked and rebound through [replayEntries].
   */
  fun rescanUnbound(roots: () -> List<View> = ::windowRoots) {
    if (stopped || profile.bootstrap == null) return
    post {
      if (stopped || !lifecycle.enter()) return@post
      try {
        replayGuard.run(Unit) {
          val windows = roots()
          if (windows.size > 16) return@run
          val queue = ArrayDeque(windows)
          val unbound = ArrayList<Existing>()
          val rows = ArrayList<WeakReference<Any>>()
          var visited = 0
          var restored = 0
          while (queue.isNotEmpty() && visited < 4_096 && unbound.size < 64) {
            val view = queue.removeFirst()
            visited++
            if (view is ImageView) onExistingImage(view)
            if (rows.size < 64) profile.bootstrap.rowEntries.forEach { path ->
              if (path.first().declaringClass.isInstance(view)) {
                var entry: Any? = view
                for (field in path) entry = entry?.let { field.get(it) }
                if (entry != null && rows.none { it.get() === entry }) rows += WeakReference(entry)
              }
            }
            if (view is ImageView && profile.drawable.declaringClass.isInstance(view)) {
              val icon = profile.viewDescriptor.get(view)?.let { profile.descriptorIcon.get(it) as? Icon }
              if (icon != null && bindings.name(icon) == null) {
                if (restoreIdentity(view, icon)) {
                  bindings.bind(view, icon, null, maybeSnapshot = true)
                  restored++
                } else if (icon.type == Icon.TYPE_BITMAP || icon.type == Icon.TYPE_RESOURCE &&
                  icon.resPackage == WeatherIconTemperature.PACKAGE) {
                  unbound += Existing(WeakReference(view), WeakReference(icon))
                }
              }
            }
            if (view is ViewGroup && queue.size + view.childCount <= 4_096) {
              for (i in 0 until view.childCount) queue.addLast(view.getChildAt(i))
            }
          }
          val owner = witnessedOwner?.get()
          if (owner != null && unbound.isNotEmpty()) {
            linkEntries(owner, rows, unbound)
            replayEntries()
          }
          if (restored > 0) replay()
        }
      } finally { lifecycle.leave() }
    }
  }

  /**
   * Binds status-bar views that still draw a native bitmap SystemUI just dropped
   * ([SystemUiNativeIconCaches]). Their descriptors outlive the cache, so the bound views then
   * take the current snapshot through the ordinary [replay].
   */
  fun adoptNative(entries: List<Pair<String, Bitmap>>, roots: () -> List<View> = ::windowRoots) {
    if (stopped) return
    nativeDirty.set(true)
    if (entries.isEmpty() || entries.size > SystemUiNativeIconCaches.MAX_ENTRIES) return
    post {
      if (stopped || !lifecycle.enter()) return@post
      try {
        replayGuard.run(Unit) {
          val windows = roots()
          if (windows.size <= 16) adoptNativeBatch(ArrayDeque(windows), entries, 0, 0)
        }
      } finally { lifecycle.leave() }
    }
  }

  private fun adoptNativeBatch(queue: ArrayDeque<View>, entries: List<Pair<String, Bitmap>>, visited: Int, bound: Int) {
    if (stopped) return
    var count = visited
    var adopted = bound
    var batch = 0
    while (queue.isNotEmpty() && count < 2_048 && batch++ < 128) {
      val view = queue.removeFirst()
      count++
      if (view is ImageView && profile.drawable.declaringClass.isInstance(view)) {
        val icon = profile.viewDescriptor.get(view)?.let { profile.descriptorIcon.get(it) as? Icon }
        if (icon != null && icon.type == Icon.TYPE_BITMAP && bindings.name(icon) == null) {
          val bitmap = (profile.bitmap.invoke(icon) as? Bitmap)?.takeUnless { it.isRecycled }
          val name = bitmap?.let { SystemUiStatusBarBitmapIdentity.match(it, entries) }
          if (name != null && owns(view, icon)) {
            bindings.remember(icon, name)
            bindings.bind(view, icon, null)
            adopted++
          }
        }
      }
      if (view is ViewGroup && queue.size + view.childCount <= 2_048) {
        for (i in 0 until view.childCount) queue.addLast(view.getChildAt(i))
      }
    }
    if (queue.isNotEmpty() && count < 2_048) {
      post {
        if (stopped || !lifecycle.enter()) return@post
        try { replayGuard.run(Unit) { adoptNativeBatch(queue, entries, count, adopted) } }
        finally { lifecycle.leave() }
      }
    } else if (adopted > 0) {
      onBootstrap("Status-bar native icons adopted=$adopted")
      ready()
    }
  }

  private fun windowRoots(): List<View> {
    val type = Class.forName("android.view.WindowManagerGlobal")
    val global = type.getDeclaredMethod("getInstance").invoke(null)
    val field = type.getDeclaredField("mViews").apply { isAccessible = true }
    return (field.get(global) as? List<*>)?.filterIsInstance<View>() ?: emptyList()
  }

  private fun scanExisting(
    queue: ArrayDeque<View>, found: ArrayList<Existing>, rows: ArrayList<WeakReference<Any>>, visited: Int,
    restored: Int = 0,
  ) {
    if (stopped) return
    var count = visited
    var batch = 0
    var proofs = restored
    while (queue.isNotEmpty() && count < 4_096 && batch++ < 128 && found.size < 64) {
      val view = queue.removeFirst()
      count++
      if (view is ImageView) onExistingImage(view)
      if (rows.size < 64) profile.bootstrap?.rowEntries?.forEach { path ->
        if (path.first().declaringClass.isInstance(view)) {
          var entry: Any? = view
          for (field in path) entry = entry?.let { field.get(it) }
          if (entry != null && rows.none { it.get() === entry }) rows += WeakReference(entry)
        }
      }
      if (view is ImageView && profile.drawable.declaringClass.isInstance(view)) {
        val descriptor = profile.viewDescriptor.get(view)
        val icon = descriptor?.let { profile.descriptorIcon.get(it) as? Icon }
        if (icon != null && (icon.type == Icon.TYPE_BITMAP || icon.type == Icon.TYPE_RESOURCE &&
            icon.resPackage == WeatherIconTemperature.PACKAGE)) {
          found += Existing(WeakReference(view), WeakReference(icon))
        }
        if (icon != null && restoreIdentity(view, icon)) {
          bindings.bind(view, icon, null, maybeSnapshot = true)
          proofs++
        }
      }
      if (view is ViewGroup && queue.size + view.childCount <= 4_096) {
        for (i in 0 until view.childCount) queue.addLast(view.getChildAt(i))
      }
    }
    if (queue.isNotEmpty() && count < 4_096 && found.size < 64) {
      post {
        if (stopped || !lifecycle.enter()) return@post
        try { bootstrapGuard.run(Unit) { scanExisting(queue, found, rows, count, proofs) } }
        finally { lifecycle.leave() }
      }
    } else {
      onBootstrap("Status-bar bootstrap scanned=$count candidates=${found.size} rows=${rows.size}" +
        " ownerProfile=${profile.bootstrap != null}")
      onBootstrap("Status-bar bootstrap proof-tags restored=$proofs")
      if (proofs > 0) replay()
      bootstrapExisting = found.filter { it.icon.get()?.let(bindings::name) == null }
      bootstrapRows = rows.toList()
      if (bootstrapExisting.isNotEmpty()) auditExisting(bootstrapExisting, rows)
    }
  }

  private fun auditExisting(existing: List<Existing>, rows: List<WeakReference<Any>>) {
    val worker = Thread({
      if (stopped || !lifecycle.enter()) return@Thread
      try {
        bootstrapGuard.run(Unit) {
          val owner = witnessedOwner?.get() ?: profile.bootstrap?.let { SystemUiStatusBarOwnerLookup.find(it, profile.consumer.declaringClass,
            context.applicationContext) {
            stopped || Thread.currentThread().isInterrupted
          } }
          if (owner != null && witnessedOwner?.get() == null) witnessedOwner = WeakReference(owner)
          onBootstrap("Status-bar bootstrap native-owner=${owner != null}")
          if (owner != null) post {
            if (stopped || !lifecycle.enter()) return@post
            try { replayGuard.run(Unit) {
              linkEntries(owner, rows, existing)
              replayEntries()
              onBootstrap("Status-bar bootstrap entry-audit completed")
            } }
            finally { lifecycle.leave() }
          }
          val managerClass = profile.nativeCaches.firstOrNull()?.declaringClass ?: return@run
          val manager = nativeManagerInstance ?: return@run
          if (!managerClass.isInstance(manager)) return@run
          val entries = ArrayList<Pair<String, Bitmap>>()
          var inspected = 0
          for (field in profile.nativeCaches) {
            if (stopped || Thread.currentThread().isInterrupted) return@run
            val cache = field.get(manager) as? SparseArray<*> ?: return@run
            synchronized(cache) {
              val values = cache.get(currentUser) as? Map<*, *> ?: return@synchronized
              if (values.size > 1_024 || entries.size + values.size > 1_024) return@run
              for ((key, value) in values) {
                if (++inspected > 1_024) return@run
                val name = key as? String ?: continue
                val bitmap = (value as? WeakReference<*>)?.get() as? Bitmap ?: continue
                if (!bitmap.isRecycled) entries += name to bitmap
              }
            }
          }
          val matches = existing.mapNotNull { candidate ->
            val icon = candidate.icon.get() ?: return@mapNotNull null
            if (icon.type != Icon.TYPE_BITMAP) return@mapNotNull null
            val bitmap = profile.bitmap.invoke(icon) as? Bitmap ?: return@mapNotNull null
            if (bitmap.isRecycled) return@mapNotNull null
            SystemUiStatusBarBitmapIdentity.match(bitmap, entries)?.let { candidate to it }
          }
          onBootstrap("Status-bar bootstrap native-cache entries=${entries.size} matched=${matches.size}")
          post {
            if (stopped || !lifecycle.enter()) return@post
            try {
              bootstrapGuard.run(Unit) {
                var bound = 0
                var warm = 0
                for ((candidate, name) in matches) {
                  val view = candidate.view.get() ?: continue
                  val icon = candidate.icon.get() ?: continue
                  if (owns(view, icon)) {
                    bindings.remember(icon, name)
                    bindings.bind(view, icon, null)
                    bound++
                    if (snapshot(name) != null) warm++
                  }
                }
                onBootstrap("Status-bar bootstrap bound=$bound warm=$warm")
                // Covers both already-warm hits and worker completions racing this audit.
                ready()
              }
            } finally { lifecycle.leave() }
          }
        }
      } finally {
        lifecycle.leave()
      }
    }, "KeiHome-statusbar-bootstrap").apply { isDaemon = true }
    synchronized(workerLock) {
      if (!stopped && bootstrapWorker == null) {
        bootstrapWorker = worker
        worker.start()
      }
    }
  }

  private fun linkEntries(owner: Any, rows: List<WeakReference<Any>>, views: List<Existing>) {
    val entryProfile = profile.bootstrap ?: return
    for (candidate in views) {
      val view = candidate.view.get() ?: continue
      val icon = candidate.icon.get() ?: continue
      if (!owns(view, icon)) continue
      val notification = entryProfile.viewNotification.get(view) as?
        android.service.notification.StatusBarNotification ?: continue
      val byKey = entryProfile.entryMaps.mapNotNull { path ->
        var value: Any? = owner
        for (field in path) value = value?.takeIf { field.declaringClass.isInstance(it) }?.let { field.get(it) }
        (value as? Map<*, *>)?.takeIf { it.size <= 1_024 }?.get(notification.key)
      }
      val matches = (rows.mapNotNull { it.get() } + byKey).distinctByIdentity().filter { entry ->
        linked(owner, entry, view, notification, entryProfile)
      }
      val entry = matches.singleOrNull() ?: continue
      if (isWeatherLauncherIcon(notification, icon)) {
        bindings.remember(icon, WeatherIconTemperature.PACKAGE)
        bindings.bind(view, icon, null)
        snapshot(WeatherIconTemperature.PACKAGE)
      }
      if (icon.type != Icon.TYPE_BITMAP) continue
      val pack = profile.entryPack.get(entry) ?: continue
      if (existingEntries.size < 64 && existingEntries.none { it.view.get() === view }) {
        existingEntries += ExistingEntry(WeakReference(owner), WeakReference(entry), WeakReference(pack),
          WeakReference(view), WeakReference(icon), WeakReference(notification))
      }
    }
    onBootstrap("Status-bar bootstrap linked=${existingEntries.size}")
  }

  private fun linked(
    owner: Any, entry: Any, view: ImageView, notification: android.service.notification.StatusBarNotification,
    b: SystemUiStatusBarEntryProfile,
  ): Boolean {
    if (!profile.entryPack.declaringClass.isInstance(entry) || b.viewNotification.get(view) !== notification ||
      b.entryNotification.get(entry) !== notification) return false
    if (b.importantConversation.invoke(owner, entry) != false) return false
    val pack = profile.entryPack.get(entry) ?: return false
    if (b.packViews.any { it.get(pack) === view }) return true
    return b.stores.any { store ->
      val listeners = store.listeners.get(owner) as? Set<*> ?: return@any false
      if (listeners.size > 64) return@any false
      listeners.any inner@ { listener ->
        if (!store.captured.declaringClass.isInstance(listener)) return@inner false
        val nativeStore = store.captured.get(listener) ?: return@inner false
        val collection = store.collection.get(nativeStore) ?: return@inner false
        if (store.manager.get(nativeStore) !== owner ||
          b.entryMaps.none { path ->
            var value: Any? = owner
            path.dropLast(1).any { field ->
              value = value?.takeIf { field.declaringClass.isInstance(it) }?.let { field.get(it) }
              value === collection
            }
          }) return@inner false
        val views = store.views.get(nativeStore) as? Map<*, *> ?: return@inner false
        views.size <= 1_024 && views[notification.key] === view
      }
    }
  }

  /** Main-thread field reads and ready-cache lookups; native app producer IO stays intercepted. */
  private fun replayEntries() {
    val entryProfile = profile.bootstrap ?: return
    var rebound = 0
    for (pending in existingEntries) {
      if (stopped) break
      val owner = pending.manager.get()
      val entry = pending.entry.get()
      val pack = pending.pack.get()
      val view = pending.view.get()
      val expected = pending.icon.get()
      val notification = pending.notification.get()
      if (owner == null || entry == null || pack == null || view == null || expected == null ||
        notification == null || expected.type != Icon.TYPE_BITMAP || !owns(view, expected) ||
        profile.entryPack.get(entry) !== pack ||
        !linked(owner, entry, view, notification, entryProfile)) {
        existingEntries.remove(pending)
        continue
      }
      val cached = profile.cached.mapNotNull { it.get(pack) }.distinctByIdentity()
        .singleOrNull { profile.descriptorIcon.get(it) === expected }
      if (cached == null || probes.contains(pack)) { existingEntries.remove(pending); continue }
      val cachedFields = profile.cached.filter { it.get(pack) === cached }
      if (cachedFields.isEmpty()) { existingEntries.remove(pending); continue }
      val held = cachedFor(cached, sharedOnly = true) ?: continue
      if (!probes.claim(pack)) { existingEntries.remove(pending); continue }
      val before = profile.cached.map { it.get(pack) }
      val previous = overrides.get()
      overrides.set(held)
      try {
        // Clear the cached descriptor fields so updateEntry rebuilds the StatusBarIcon through
        // AppIconsManager.getAppIconBitmap (intercepted by overrides) instead of returning stale cache.
        cachedFields.forEach { it.set(pack, null) }
        entryProfile.updateEntry.invoke(owner, entry)
      } catch (failure: Throwable) {
        profile.cached.forEachIndexed { i, field -> field.set(pack, before[i]) }
        existingEntries.remove(pending)
        throw failure
      } finally {
        if (previous == null) overrides.remove() else overrides.set(previous)
      }
      // The native update retains people/sensitive selection, notification binding and listener fan-out.
      val current = profile.viewDescriptor.get(view)
      val icon = current?.let { profile.descriptorIcon.get(it) as? Icon }
      if (icon != null && icon !== expected && bindings.name(icon) != null &&
        profile.entryPack.get(entry) === pack && linked(owner, entry, view, notification, entryProfile)) {
        rebound++
      } else if (cachedFields.any { it.get(pack) == null }) {
        profile.cached.forEachIndexed { i, field -> field.set(pack, before[i]) }
      }
      existingEntries.remove(pending)
    }
    if (rebound > 0) onBootstrap("Status-bar bootstrap native-rebound=$rebound")
  }

  private fun List<Any>.distinctByIdentity(): List<Any> {
    val seen = java.util.IdentityHashMap<Any, Boolean>()
    return filter { seen.put(it, true) == null }
  }

  fun stop(deadline: Long): Boolean {
    requestStop()
    val worker = bootstrapWorker ?: return true
    if (worker === Thread.currentThread()) return false
    if (worker.isAlive && !isMain()) {
      val millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
      if (millis > 0) worker.join(millis)
    }
    return !worker.isAlive
  }

  fun retirementStatus(): String = "bootstrapAlive=${bootstrapWorker?.isAlive == true}"

  private fun post(block: () -> Unit) {
    synchronized(workerLock) {
      if (!stopped) main.post(block)
    }
  }
}
