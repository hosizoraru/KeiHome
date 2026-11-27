package os.kei.hyper.home.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.HandlerThread
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteCallbackList
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.MonetAction
import os.kei.hyper.home.data.ThemeEffectsPreferences
import os.kei.hyper.home.data.WallpaperDimmingPreferences
import os.kei.hyper.home.data.WallpaperPresetPreferences
import os.kei.hyper.home.status.IconRefreshReceipt
import os.kei.hyper.home.status.RuntimeStatusProtocol
import os.kei.hyper.home.status.IconPackImportProtocol

internal class LauncherIconRefreshRuntime(private val xposed: XposedInterface) {
  private val lifecycle = IconRefreshReloadLifecycle()
  private val frameworkManager = FrameworkManagerReceiver(
    runActive = { work -> lifecycle.withActiveRuntime { work() } },
    onFailure = { failure -> logFailure("could not open framework manager", failure) },
  )
  private val lock = lifecycle.lock
  private val generations = IconRefreshGeneration()
  private val iconTheme = ActiveIconTheme()
  private val pending = PendingIconRefresh()
  private var lastMonetState: String? = null
  private var nativeBrokerReady = false
  private var nativeBrokerInstallFailure: String? = null
  private var convertedIconRuntime: ConvertedIconRuntime? = null
  private val navigationBackdrop: NativeNavigationBackdropRuntime =
    NativeNavigationBackdropRuntime(xposed) { launcherControl.requestNavigationStatus() }
  @Volatile private var navigationBackdropReady = false
  private val wallpaperPresets = WallpaperPresetMonitor(
    openPreferences = { xposed.getRemotePreferences(WallpaperPresetPreferences.GROUP) },
    onFailure = { logFailure("Wallpaper preset operation failed", it) },
  )
  private val launcherControl = LauncherControlMonitor(
    navigationStatus = { navigationBackdropReady to navigationBackdrop.fingerprint() },
    notifyLauncher = ::notifyLauncherOfAppearanceChange,
    wallpaperPreferences = { xposed.getRemotePreferences(WallpaperDimmingPreferences.GROUP) },
    wallpaperSurfaces = { wallpaperSurfaces },
    onReady = { context, handler ->
      synchronized(lock) {
        if (hooksReady && !lifecycle.retiring && !lifecycle.retired) {
          frameworkManager.start(context, handler)
        }
      }
    },
    onFailure = { logFailure("launcher control operation failed", it) },
  )
  @Volatile private var wallpaperSurfaces: WallpaperSurfaceDimming? = null
  @Volatile private var lastRefreshReceipt: IconRefreshReceipt? = null
  private val monetColorSynchronizer = MonetColorSynchronizer(iconTheme) { recentTaskLoader }
  /** Pre-dispatch rename of the Themes-staged fixed-appearance copy; see [AppearanceFastSwap]. */
  private val appearanceFastSwap = AppearanceFastSwap(
    stat = { path ->
      try {
        android.system.Os.lstat(path).takeIf { android.system.OsConstants.S_ISREG(it.st_mode) }
          ?.let(AppearanceFileKey::of)
      } catch (_: android.system.ErrnoException) { null }
    },
    rename = { from, to -> android.system.Os.rename(from, to) },
  )
  private val iconBatch = LauncherIconBatch()
  /** Home's uid for [LauncherThemePoke]; resolved on a worker, read from the UiMode hook. */
  @Volatile private var launcherUid = -1

  private fun pokeLauncher(reason: String): Boolean = try {
    val result = LauncherThemePoke.poke(launcherUid)
    xposed.log(Log.INFO, TAG, "Launcher icon theme poke ($reason): $result")
    result.startsWith("sent")
  } catch (failure: Throwable) {
    logFailure("launcher icon theme poke failed", failure)
    false
  }

  /** Home's theme sequence may be ahead after a poke; repeat so Home sees a real theme change. */
  private val launcherRepoke = Runnable { pokeLauncher("after icon theme change") }

  /** Themes replaced the active archive after Home may have drawn the old one. */
  private fun onArchiveReplaced() {
    pokeLauncher("archive replaced")
    // Reopening the archive does not redraw mounted icons; the batched refresh does.
    scheduleArchiveRefresh()
    // The import worker also schedules the Home rebuild for drawer items and unmounted entries.
  }

  private val iconPackImportMonitor = IconPackImportMonitor(
    { xposed.getRemotePreferences(IconPackImportProtocol.GROUP) },
    { failure -> logFailure("native icon import failed", failure) },
    { report, proof ->
      lifecycle.withActiveRuntime {
        check(hooksReady) { "Converted icon runtime is unavailable" }
        checkNotNull(convertedIconRuntime) { "Converted icon validation hook is unavailable" }
          .register(report, proof)
      }
    },
    onAppearanceVariants = { variants ->
      lifecycle.withActiveRuntime {
        check(hooksReady) { "Converted icon runtime is unavailable" }
        checkNotNull(convertedIconRuntime) { "Converted icon validation hook is unavailable" }
          .registerAppearanceVariants(variants)
      }
    },
    onAppearanceVariantsRemoved = { primary ->
      lifecycle.withActiveRuntime { convertedIconRuntime?.unregisterAppearanceVariants(primary) }
    },
    hasAppearanceVariants = { convertedIconRuntime?.hasAppearanceVariants() == true },
    onAppearanceStatus = { message -> xposed.log(Log.INFO, TAG, message) },
    onAppearancePrepared = { prepared -> appearanceFastSwap.publish(prepared) },
    onAppearanceReplaced = ::onArchiveReplaced,
    onLauncherRebuild = { user -> LauncherThemePoke.restart(launcherUid, user, "KeiHome fixed-appearance icons") },
  )
  private val monetColorMonitor = MonetColorMonitor(
    monetColorSynchronizer,
    onResult = { result ->
      val state = result.diagnosticName
      val reasons = if (result is MonetColorSynchronizer.SynchronizationResult.NativeUnavailable) {
        listOfNotNull(
          NativeMonetClient.lastTransportFailure,
          NativeMonetSocketBroker.lastFailure,
          nativeBrokerInstallFailure,
        ).distinct().joinToString("; ")
      } else ""
      val diagnostic = "Monet state=$state${if (reasons.isEmpty()) "" else "; $reasons"}"
      if (diagnostic != lastMonetState) {
        lastMonetState = diagnostic
        xposed.log(Log.INFO, TAG, diagnostic)
      }
      when (result) {
        is MonetColorSynchronizer.SynchronizationResult.Applied -> {
          xposed.log(
            Log.INFO, TAG,
            "Monet icon palette acknowledged: ${result.color.toUInt().toString(16)} " +
              "packageRefresh=${result.refreshPackages}",
          )
          if (result.refreshPackages) schedulePaletteRefresh()
          iconPackImportMonitor.requestAppearanceSync()
        }
        is MonetColorSynchronizer.SynchronizationResult.WriteFailed ->
          xposed.log(Log.WARN, TAG, "Monet icon background could not be written")
        is MonetColorSynchronizer.SynchronizationResult.Restored -> {
          schedulePaletteRefresh()
          iconPackImportMonitor.requestAppearanceSync()
        }
        else -> Unit
      }
    },
    onFailure = { failure ->
      xposed.log(Log.WARN, TAG, "Monet icon background synchronization failed", failure)
    },
    requestPreferences = { xposed.getRemotePreferences(MonetAction.GROUP) },
    statusPreferences = { xposed.getRemotePreferences(RuntimeStatusProtocol.GROUP) },
    onStatusDiagnostic = { message -> xposed.log(Log.INFO, TAG, "Runtime status: $message") },
    onUserUnlocked = {
      launcherControl.requestNavigationStatus()
      iconPackImportMonitor.request()
      iconPackImportMonitor.requestAppearanceSync()
    },
    refreshReceipt = { lastRefreshReceipt },
    themePreferences = { xposed.getRemotePreferences(ThemeEffectsPreferences.GROUP) },
    onThemeMaterialsChanged = {
      xposed.log(Log.INFO, TAG, "Theme material change acknowledged; reconciling mounted icons")
      schedulePaletteRefresh()
    },
    onLauncherLabelsChanged = {
      xposed.log(Log.INFO, TAG, "Launcher label mode acknowledged; notifying mounted titles")
      launcherControl.requestLauncherUpdate()
    },
    onDockGlassChanged = {
      xposed.log(Log.INFO, TAG, "Dock glass choice acknowledged; notifying native home screen")
      launcherControl.requestLauncherUpdate()
    },
    onLauncherGlassChanged = {
      xposed.log(Log.INFO, TAG, "Launcher wallpaper glass material accepted; notifying native home screen")
      launcherControl.requestLauncherUpdate()
    },
    onPackageResourcesChanged = { name ->
      synchronized(lock) {
        if (!lifecycle.retiring && !lifecycle.retired && hooksReady) {
          generations.nextPackageRefresh(name)?.let { pending.replace(IconRefreshRequest(it, 0)) }
          postPendingLocked()
        }
      }
    },
    onOverlayResourcesChanged = { target ->
      synchronized(lock) {
        if (!lifecycle.retiring && !lifecycle.retired && hooksReady) {
          val next = if (target == "android") generations.nextFullRefresh()
            else generations.nextPackageRefresh(target)
          next?.let { pending.replace(IconRefreshRequest(it, 0)) }
          postPendingLocked()
        }
      }
    },
    onTimeResourcesChanged = { calendarOnly -> scheduleTimeRefresh(calendarOnly) },
  )
  private var worker: HandlerThread? = null
  private var handler: Handler? = null
  private val closingWorkers = RetiringIconRefreshThreads()
  private var hooksReady = false
  private lateinit var profile: ServerProfile
  private var recentTaskLoader: ClassLoader? = null
  private var recentTaskIcons: RecentTaskAppIconRuntime? = null

  @Volatile
  var service: Any? = null
    private set

  private fun scheduleTimeRefresh(calendarOnly: Boolean) {
    synchronized(lock) {
      if (lifecycle.retiring || lifecycle.retired || !hooksReady) return
      handler?.post {
        try {
          if (lifecycle.retiring || lifecycle.retired) return@post
          val context = service?.let { profile.context.get(it) } as? Context ?: return@post
          val launcher = context.getSystemService(LauncherApps::class.java) ?: return@post
          val activities = launcher.profiles.flatMap { launcher.getActivityList(null, it) }
          val packages = context.packageManager.getInstalledApplications(0).mapTo(hashSetOf()) { it.packageName }
          packages += activities.map { it.componentName.packageName }
          val components = activities.groupBy { it.componentName.packageName }
            .mapValues { (_, entries) -> entries.mapTo(hashSetOf()) { it.componentName.className } }
          val targets = iconTheme.timeIconPackages(packages, components).filterValues {
            !calendarOnly || it == os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon.Kind.CALENDAR
          }.keys
          synchronized(lock) {
            if (!lifecycle.retiring && !lifecycle.retired) {
              for (name in targets) generations.nextPackageRefresh(name)?.let {
                pending.replace(IconRefreshRequest(it, 0))
              }
              if (targets.isNotEmpty()) postPendingLocked()
            }
          }
        } catch (failure: Exception) { logFailure("could not refresh time-dependent icons", failure) }
      }
    }
  }

  fun install(
    loader: ClassLoader,
    oldService: Any?,
    oldHooks: List<XposedInterface.HookHandle> = emptyList(),
    reconcileAfterReload: Boolean = false,
  ): Boolean {
    val handles = mutableListOf<XposedInterface.HookHandle>()
    val replaced = Collections.newSetFromMap(
      IdentityHashMap<XposedInterface.HookHandle, Boolean>(),
    )
    try {
      val resolved = ServerProfile.resolve(loader)
      profile = resolved
      recentTaskLoader = loader
      val currentService = resolved.findExistingService(loader)
      if (currentService != null) {
        service = currentService
        xposed.log(Log.INFO, TAG, "Recovered existing LauncherApps service")
      } else if (oldService != null && resolved.launcherClass.isInstance(oldService)) {
        service = oldService
      }

      prepareRefreshWorker()
      try {
        val installed = navigationBackdrop.install(loader, oldHooks) { work ->
          lifecycle.trackHook { work() }
        }
        handles += installed.first
        installed.second?.let(replaced::add)
        navigationBackdropReady = true
        navigationBackdrop.start()
      } catch (failure: Throwable) {
        logFailure("native navigation rule compatibility unavailable", failure)
      }
      synchronized(lock) {
        if (reconcileAfterReload) {
          // Reconciliation reads the current configuration; it only needs a fresh token.
          val generation = generations.next(
            Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES,
          )!!
          pending.replace(IconRefreshRequest(generation, 0))
        }
      }

      val oldListener = findMatchingIconHook(
        oldHooks, LISTENER_HOOK_ID, resolved.registerListener,
      )
      val oldConfiguration = findMatchingIconHook(
        oldHooks, CONFIGURATION_HOOK_ID, resolved.updateConfiguration,
      )
      try {
        val drm = ConvertedIconRuntime(xposed)
        val installedDrm = drm.install(loader, oldHooks)
        installedDrm.second?.let(replaced::add)
        handles += installedDrm.first
        convertedIconRuntime = drm
      } catch (failure: Throwable) {
        logFailure("converted icon DRM validation unavailable", failure)
      }
      // The transport is optional. Missing OEM/hidden Binder APIs must not take down the
      // independently working night-mode and LauncherApps callbacks.
      var nativeHandle: XposedInterface.HookHandle? = null
      try {
        val nativeTransaction = NativeMonetSocketBroker.resolveTransaction(loader)
        val oldNativeBroker = findMatchingIconHook(
          oldHooks, NativeMonetSocketBroker.HOOK_ID, nativeTransaction,
        )
        val nativeBroker = installOrReplaceIconHook(
          xposed, oldNativeBroker, NativeMonetSocketBroker.HOOK_ID, nativeTransaction,
          XposedInterface.Hooker { chain ->
            // Normal generated PackageManager calls are never parsed or modified.
            if (chain.getArg(0) as? Int != NativeMonetSocketBroker.TRANSACTION) {
              chain.proceed()
            } else lifecycle.trackHook {
              startMonetMonitor()
              NativeMonetSocketBroker.onTransact(
                chain.getArg(1) as Parcel, chain.getArg(2) as? Parcel, chain.getArg(3) as Int,
              ) {
                try {
                  monetColorMonitor.requestLauncherMaterials()
                  monetColorMonitor.request()
                } catch (failure: Exception) {
                  logFailure("could not queue launcher material replay", failure)
                }
              }
            }
          },
        )
        nativeHandle = nativeBroker.first
        require(nativeHandle.id == NativeMonetSocketBroker.HOOK_ID &&
          nativeHandle.executable == nativeTransaction)
        nativeBroker.second?.let(replaced::add)
        handles += nativeHandle
        nativeBrokerReady = true
      } catch (failure: Throwable) {
        try { nativeHandle?.unhook() } catch (cleanup: Throwable) {
          logFailure("could not remove failed optional native broker hook", cleanup)
        }
        NativeMonetSocketBroker.stop()
        nativeBrokerInstallFailure = nativeMonetFailure("broker installation", failure)
        logFailure("Monet Binder transport unavailable; keeping night-mode callbacks", failure)
      }
      val listenerHooker = XposedInterface.Hooker { chain ->
        lifecycle.trackHook {
          val result = chain.proceed()
          try {
            if (chain.getArg(0) == LAUNCHER_PACKAGE) {
              synchronized(lock) {
                service = chain.thisObject
                postPendingLocked()
              }
              startMonetMonitor()
            }
          } catch (failure: Throwable) {
            logFailure("could not observe launcher listener", failure)
          }
          result
        }
      }
      val listener = installOrReplaceIconHook(
        xposed, oldListener, LISTENER_HOOK_ID, resolved.registerListener, listenerHooker,
      )
      listener.second?.let(replaced::add)
      handles += listener.first
      require(listener.first.id == LISTENER_HOOK_ID &&
        listener.first.executable == resolved.registerListener)

      val configurationHooker = XposedInterface.Hooker { chain ->
        android.os.Trace.beginSection("KeiHome:configuration-hook")
        try { lifecycle.trackHook {
          val previous = readUiMode(chain.thisObject)
          android.os.Trace.beginSection("KeiHome:original-configuration")
          val result = try { chain.proceed() } finally { android.os.Trace.endSection() }
          try {
            val changes = result as? Int ?: 0
            val themeChanged = changes and CONFIG_THEME != 0 && iconThemeChanged(chain.thisObject)
            val resourcesChanged = iconResourceConfigurationChanged(changes)
            if (themeChanged || resourcesChanged) {
              // Only revision/queue work belongs in the WM transaction; inspect files on workers.
              if (themeChanged) iconTheme.invalidate()
              if (themeChanged && LauncherThemePoke.ahead) handler?.post(launcherRepoke)
              monetColorSynchronizer.invalidateIconResources()
              synchronized(lock) {
                if (!lifecycle.retired) {
                  generations.nextFullRefresh()?.let { pending.replace(IconRefreshRequest(it, 0)) }
                  postPendingLocked()
                }
              }
              monetColorMonitor.request()
            }
            if (!themeChanged && !resourcesChanged && changes and ActivityInfo.CONFIG_UI_MODE != 0) {
              val current = readUiMode(chain.thisObject)
              if (previous != null && current != null) {
                android.os.Trace.beginSection("KeiHome:night-observer")
                try { schedule(previous, current) } finally { android.os.Trace.endSection() }
              }
            }
            if (changes and ActivityInfo.CONFIG_UI_MODE != 0) launcherControl.requestWallpaperRefresh()
            // Fixed-appearance copies follow the night bit and re-applications of their archive.
            if (themeChanged || changes and ActivityInfo.CONFIG_UI_MODE != 0) {
              iconPackImportMonitor.requestAppearanceSync()
            }
          } catch (failure: Throwable) {
            logFailure("could not schedule icon refresh", failure)
          }
          result
        } } finally { android.os.Trace.endSection() }
      }
      val configuration = installOrReplaceIconHook(
        xposed, oldConfiguration, CONFIGURATION_HOOK_ID, resolved.updateConfiguration,
        configurationHooker,
      )
      configuration.second?.let(replaced::add)
      handles += configuration.first
      require(configuration.first.id == CONFIGURATION_HOOK_ID &&
        configuration.first.executable == resolved.updateConfiguration)
      try {
        installAppearanceFastSwap(loader, oldHooks)?.let { installed ->
          installed.second?.let(replaced::add)
          handles += installed.first
        }
      } catch (failure: Throwable) {
        logFailure("fixed-appearance pre-dispatch swap unavailable", failure)
      }
      try {
        val wallpaper = Class.forName("com.android.server.wallpaper.WallpaperManagerService", false, loader)
        val setDim = wallpaper.getMethod("setWallpaperDimAmountForUid", Int::class.javaPrimitiveType,
          Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val wallpaperContext = wallpaper.getDeclaredField("mContext").apply { isAccessible = true }
        val signal = Handler(Looper.getMainLooper())
        val guard = SystemUiHookGuard { logFailure("wallpaper dim change notification unavailable", it) }
        val dim = installOrReplaceIconHook(xposed,
          findMatchingIconHook(oldHooks, WALLPAPER_DIM_HOOK_ID, setDim), WALLPAPER_DIM_HOOK_ID, setDim,
          XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val service = chain.thisObject
            signal.post {
              guard.run(Unit) {
                val context = wallpaperContext.get(service) as Context
                context.sendBroadcast(Intent(WALLPAPER_DIM_CHANGED).setPackage("com.android.systemui"),
                  WALLPAPER_DIM_PERMISSION)
              }
            }
            result
          })
        dim.second?.let(replaced::add)
        handles += dim.first
        val surfaces = WallpaperSurfaceDimming(loader)
        surfaces.install({ id, executable, hooker ->
          val hook = installOrReplaceIconHook(xposed, findMatchingIconHook(oldHooks, id, executable),
            id, executable, hooker)
          hook.second?.let(replaced::add)
          handles += hook.first
        }, { launcherControl.requestWallpaperRefresh() }, guard)
        wallpaperSurfaces = surfaces
      } catch (failure: Throwable) {
        logFailure("wallpaper dim integration unavailable", recoverableSystemUiFailure(failure))
      }
      check(removeOldHooks(oldHooks, replaced))
      synchronized(lock) {
        hooksReady = true
        postPendingLocked()
      }
      startMonetMonitor()
      xposed.log(Log.INFO, TAG, "System-server icon refresh hooks installed")
      return true
    } catch (failure: Throwable) {
      synchronized(lock) { hooksReady = false }
      NativeMonetSocketBroker.stop()
      monetColorMonitor.stop()
      navigationBackdrop.stop()
      launcherControl.stop()
      wallpaperPresets.stop()
      iconPackImportMonitor.stop()
      frameworkManager.stop()
      stopRefreshWorker()
      stopRecentTaskIcons()
      handles.forEach {
        try {
          it.unhook()
        } catch (_: Throwable) {
        }
      }
      removeOldHooks(oldHooks, replaced)
      logFailure("unsupported system-server profile; icon refresh disabled", failure)
      return false
    }
  }


  fun retire(saveState: () -> Unit): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RETIRE_TIMEOUT_MS)
    return lifecycle.retire(
      prepare = prepare@ {
        closingWorkers.pruneStopped()
        if (closingWorkers.hasLiveThreads()) return@prepare false
        synchronized(lock) { recentTaskIcons }?.requestStop()
        frameworkManager.stop()
        stopRefreshWorker()
        NativeMonetSocketBroker.stop()
        monetColorMonitor.stop()
        navigationBackdrop.stop()
        launcherControl.stop()
        wallpaperPresets.stop()
        iconPackImportMonitor.stop()
        true
      },
      await = { stopRecentTaskIcons(deadline) && awaitRetirement(deadline) },
      commit = {
        closingWorkers.pruneStopped()
        saveState()
        generations.retire()
      },
      rollback = {
        prepareRefreshWorker()
        if (navigationBackdropReady) navigationBackdrop.start()
        startMonetMonitor()
        synchronized(lock) { postPendingLocked() }
      },
      retireValidation = { convertedIconRuntime?.retire() ?: true },
      resumeValidation = { convertedIconRuntime?.resume() },
    )
  }

  private fun awaitRetirement(deadline: Long): Boolean {
    return closingWorkers.awaitStopped(deadline) &&
      frameworkManager.awaitStopped(deadline) &&
      monetColorMonitor.awaitStopped(deadline) &&
      launcherControl.awaitStopped(deadline) &&
      wallpaperPresets.awaitStopped(deadline) &&
      iconPackImportMonitor.awaitStopped(deadline) &&
      NativeMonetSocketBroker.awaitStopped(deadline) &&
      IconResourceProbeClient.awaitOwnedReplyThreadsStopped(deadline)
  }

  private fun startMonetMonitor() {
    try {
      val context = synchronized(lock) {
        if (!hooksReady || lifecycle.retiring || lifecycle.retired) return
        service?.let { profile.context.get(it) } as? Context
      } ?: return
      if (launcherUid < 0) launcherUid = try {
        context.packageManager.getPackageUid(LauncherThemePoke.LAUNCHER, 0)
      } catch (_: Exception) { -1 }
      launcherControl.start(context)
      wallpaperPresets.start(context)
      synchronized(lock) {
        if (!lifecycle.retiring && !lifecycle.retired && recentTaskIcons == null) {
          recentTaskLoader?.let { loader ->
            recentTaskIcons = RecentTaskAppIconRuntime(xposed, loader, context).also { it.start() }
          }
        }
      }
      if (nativeBrokerReady) NativeMonetSocketBroker.start(context)
      iconPackImportMonitor.start(context)
      monetColorMonitor.start(context)
      launcherControl.requestNavigationStatus()
    } catch (failure: Throwable) {
      logFailure("could not observe Monet colors", failure)
    }
  }

  private fun stopRecentTaskIcons(
    deadline: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RETIRE_TIMEOUT_MS),
  ): Boolean {
    val runtime = synchronized(lock) { recentTaskIcons } ?: return true
    if (!runtime.stop(deadline)) return false
    synchronized(lock) { if (recentTaskIcons === runtime) recentTaskIcons = null }
    return true
  }

  fun retirementStatus(): String {
    val callbacks = synchronized(lock) {
      "activeHooks=${lifecycle.activeHooks} activeJobs=${lifecycle.activeJobs}"
    }
    val tasks = synchronized(lock) { recentTaskIcons }?.retirementStatus() ?: "released"
    return "$callbacks closingWorkers=${closingWorkers.hasLiveThreads()} recentTasks=[$tasks]"
  }

  private fun removeOldHooks(
    oldHooks: List<XposedInterface.HookHandle>,
    replaced: Set<XposedInterface.HookHandle>,
  ): Boolean {
    var success = true
    oldHooks.forEach {
      if (!replaced.contains(it)) {
        try {
          it.unhook()
        } catch (failure: Throwable) {
          success = false
          logFailure("could not remove old hook", failure)
        }
      }
    }
    return success
  }

  private fun readUiMode(activityTaskManager: Any?): Int? = try {
    (profile.getGlobalConfiguration.invoke(activityTaskManager) as? Configuration)?.uiMode
  } catch (_: Throwable) {
    null
  }

  private fun iconThemeChanged(activityTaskManager: Any?): Boolean = try {
    val configuration = profile.getGlobalConfiguration.invoke(activityTaskManager)
    val extra = profile.extraConfiguration?.get(configuration)
    ((profile.themeChangedFlags?.get(extra) as? Number)?.toLong() ?: 0L) and 8L != 0L
  } catch (_: Exception) { false }

  /**
   * The active archive changed and Home was poked to reopen it ([LauncherThemePoke]), either by
   * the pre-dispatch swap or by a Themes-side replacement. Reopening does not redraw mounted
   * icons. A full refresh skips the resource probe; Home receives it as one [LauncherIconBatch]
   * and redraws every entry in place. No global theme change is sent, so Home keeps its page
   * and foreground apps keep running.
   */
  private fun scheduleArchiveRefresh() {
    iconTheme.invalidate()
    monetColorSynchronizer.invalidateIconResources()
    synchronized(lock) {
      if (lifecycle.retiring || lifecycle.retired || !hooksReady) return
      generations.nextFullRefresh()?.let { pending.replace(IconRefreshRequest(it, 0)) }
      postPendingLocked()
    }
  }

  /**
   * Hooks the UiModeManager commit, which holds only its own lock and runs before the new night
   * bit reaches any process. Two stats and one same-directory rename happen here; everything
   * else stays on the import worker.
   */
  private fun installAppearanceFastSwap(
    loader: ClassLoader,
    oldHooks: List<XposedInterface.HookHandle>,
  ): Pair<XposedInterface.HookHandle, XposedInterface.HookHandle?>? {
    val service = Class.forName("com.android.server.UiModeManagerService", false, loader)
    val commit = service.getDeclaredMethod("applyConfigurationExternallyLocked")
    val applied = service.getDeclaredField("mSetUiMode").apply { isAccessible = true }
    val pending = service.getDeclaredField("mConfiguration").apply { isAccessible = true }
    require(applied.type == Int::class.javaPrimitiveType && pending.type == Configuration::class.java)
    return installOrReplaceIconHook(xposed, findMatchingIconHook(oldHooks, APPEARANCE_SWAP_HOOK_ID, commit),
      APPEARANCE_SWAP_HOOK_ID, commit, XposedInterface.Hooker { chain ->
        lifecycle.trackHook {
          var swapped = false
          try {
            val instance = chain.thisObject
            val before = applied.getInt(instance)
            val after = (pending.get(instance) as? Configuration)?.uiMode
            if (!lifecycle.retired && after != null && isNightModeTransition(before, after)) {
              swapped = appearanceFastSwap.beforeNightChange(
                after and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
              // Home reopens its icon archive only on a theme event; send it to Home's process
              // alone before the night change, so both arrive in order. Lock order UiMode -> WM.
              if (swapped) pokeLauncher("before night change")
            }
          } catch (failure: Throwable) {
            // Never fail the platform commit; the worker's Themes path still converges.
            logFailure("fixed-appearance pre-dispatch swap failed", failure)
          }
          val result = chain.proceed()
          if (swapped) {
            xposed.log(Log.INFO, TAG, "Fixed-appearance icons swapped before the night change")
            // Every themed icon changed, so the night refresh needs no resource probe.
            scheduleArchiveRefresh()
            iconPackImportMonitor.requestLauncherRebuild()
          }
          result
        }
      })
  }

  private fun schedulePaletteRefresh() {
    synchronized(lock) {
      if (lifecycle.retiring || lifecycle.retired || !hooksReady) return
      // Palette publication/withdrawal or a changed material opt-in reached its native ACK.
      // Pending resource/night/source work keeps its slower pacing in generations.
      val generation = generations.nextFullRefresh(IconRefreshCause.PALETTE_ACK) ?: return
      pending.replace(IconRefreshRequest(generation, 0))
      postPendingLocked()
    }
  }

  private fun schedule(previous: Int, current: Int) {
    synchronized(lock) {
      if (lifecycle.retired) return
      val generation = generations.next(previous, current) ?: return
      pending.replace(IconRefreshRequest(generation, 0))
      monetColorMonitor.request()
      handler?.let { queue ->
        // Drop stale refresh work, but keep a queued poke for a real icon-theme change.
        val repoke = queue.hasCallbacks(launcherRepoke)
        queue.removeCallbacksAndMessages(null)
        if (repoke) queue.post(launcherRepoke)
      }
      postPendingLocked()
    }
  }

  private fun postPendingLocked() {
    if (!hooksReady || lifecycle.retiring || lifecycle.retired || service == null ||
      pending.posted
    ) return
    val request = pending.request ?: return
    // Installation/rollback own thread creation. A platform callback only queues work.
    val currentHandler = handler ?: return
    val ticket = pending.markPosted()
    val delay = if (request.attempt == 0) {
      generations.cause(request.generation).initialDelayMs
    } else SNAPSHOT_RETRY_MS
    if (!currentHandler.postDelayed({ refresh(request, ticket) }, delay)) {
      pending.postFailed(ticket)
      xposed.log(Log.WARN, TAG, "Could not queue pending icon refresh")
    }
  }

  private fun prepareRefreshWorker() {
    if (synchronized(lock) { handler != null }) return
    val thread = HandlerThread("KeiHomeIconRefresh", android.os.Process.THREAD_PRIORITY_DEFAULT)
    val interrupted = Thread.interrupted()
    try {
      thread.start()
      val ready = Handler(thread.looper)
      synchronized(lock) {
        worker = thread
        handler = ready
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt()
    }
  }

  private fun stopRefreshWorker() {
    val closing = synchronized(lock) {
      pending.invalidatePost()
      handler?.removeCallbacksAndMessages(null)
      handler = null
      worker?.also {
        worker = null
        closingWorkers.track(it)
      }
    }
    closing?.interrupt()
    closing?.quitSafely()
  }

  private fun refreshCurrent(generation: Long): Boolean = !Thread.currentThread().isInterrupted &&
    !lifecycle.retiring && !lifecycle.retired && generations.isCurrent(generation)

  private fun retryRefresh(request: IconRefreshRequest): Boolean = synchronized(lock) {
    if (!refreshCurrent(request.generation) || request.attempt >= MAX_SNAPSHOT_RETRIES) {
      return@synchronized false
    }
    pending.replace(request.copy(attempt = request.attempt + 1))
    postPendingLocked()
    true
  }

  private fun refresh(request: IconRefreshRequest, ticket: Long) {
    val generation = request.generation
    val cause = synchronized(lock) {
      if (lifecycle.retiring || lifecycle.retired || !pending.claim(request, ticket)) return
      if (!refreshCurrent(generation)) return
      lifecycle.activeJobs += 1
      generations.cause(generation)
    }
    val isCurrent = { refreshCurrent(generation) }
    var sent = 0
    var completed = false
    try {
      val launcherService = service ?: return
      val listeners = snapshotListeners(launcherService)
      if (listeners == null) {
        if (!retryRefresh(request) && refreshCurrent(generation)) {
          xposed.log(Log.WARN, TAG, "Registered launcher listener snapshot unavailable")
        }
        return
      }
      val context = profile.context.get(launcherService) as? Context ?: return
      val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
      val userManager = context.getSystemService(UserManager::class.java) ?: return
      val probe = IconResourceProbeClient(context)
      for (listener in listeners) {
        val profiles = enabledProfiles(userManager, listener.owner)
        if (profiles == null) {
          continue
        }
        for (user in profiles) {
          if (!refreshCurrent(generation)) {
            return
          }
          if (profile.isEnabledProfile.invoke(
              launcherService, listener.cookie, user, "KeiHome icon refresh",
            ) != true
          ) continue
          try {
            val requestedPackages = generations.packages(generation)
            val fullRefresh = generations.needsFullRefresh(generation)
            val singlePackage = requestedPackages?.singleOrNull()
            val activities = launcherApps.getActivityList(singlePackage, user).let { entries ->
              if (requestedPackages == null || singlePackage != null) entries
              else entries.filter { it.componentName.packageName in requestedPackages }
            }
            val activityPackages = activities.mapTo(hashSetOf()) { it.componentName.packageName }
            // Full and targeted refreshes already include every activity owner in scope.
            val activityOwners = if (fullRefresh || requestedPackages != null) {
              activityPackages
            } else {
              val probed = probe.packagesToRefresh(activities, isCurrent)
              if (!isCurrent()) return
              val theme = iconTheme.snapshot(
                activityPackages,
                activities.groupBy { it.componentName.packageName }
                  .mapValues { (_, entries) -> entries.mapTo(hashSetOf()) { it.componentName.className } },
                modEnabled = true,
              )
              if (theme.complete) probed + theme.coveredPackages else activityPackages
            }
            if (!refreshCurrent(generation)) return
            val shortcutsByPackage = shortcutsByPackage(launcherApps, user, singlePackage).let { shortcuts ->
              if (requestedPackages == null || singlePackage != null) shortcuts
              else shortcuts.filterKeys { it in requestedPackages }
            }
            // Native HyperOS frontends can own icon resources without an Android
            // MAIN/LAUNCHER activity. A targeted update must still reach their owner.
            val packages = (activityOwners + shortcutsByPackage.keys).toSortedSet()
            // A resource change or Monet palette toggle reloads many icons; Home takes them in one model update.
            val batched = if ((cause == IconRefreshCause.RESOURCE_CHANGE ||
                cause == IconRefreshCause.PALETTE_ACK) && requestedPackages == null) {
              val eligible = iconBatch.eligible(activities.map {
                LauncherIconBatch.Entry(it.componentName.packageName,
                  it.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED != 0)
              }).filterTo(sortedSetOf()) { it in activityOwners }
              dispatchBatchIfRegistered(launcherService, listener, user, eligible, isCurrent)
            } else emptySet()
            if (batched.isNotEmpty()) {
              sent += 1
              xposed.log(Log.INFO, TAG, "Icon batch generation=$generation " +
                "user=${profile.userId.invoke(user)} packages=${batched.size}")
              if (cause.packageIntervalMs > 0) {
                try {
                  Thread.sleep(cause.packageIntervalMs)
                } catch (_: InterruptedException) {
                  Thread.currentThread().interrupt()
                  return
                }
              }
            }
            for (name in requestedPackages.orEmpty()) {
              if (!isCurrent()) return
              if (name in packages) continue
              val installed = try {
                launcherApps.getApplicationInfo(name, 0, user) != null
              } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                false
              } catch (_: SecurityException) {
                false
              }
              if (installed) packages.add(name)
            }
            xposed.log(
              Log.INFO, TAG,
              "Icon probe generation=$generation user=${profile.userId.invoke(user)} " +
                "activities=${activities.size} packages=${packages.size} " +
                "shortcuts=${shortcutsByPackage.size} cause=$cause intervalMs=${cause.packageIntervalMs}",
            )
            for (packageName in packages) {
              if (!refreshCurrent(generation)) {
                return
              }
              if (!listener.callback.asBinder().isBinderAlive) {
                break
              }
              // Batched packages are paced only when they still need a shortcut callback.
              var paced = packageName !in batched
              try {
                if (packageName !in batched && dispatchIfRegistered(
                    launcherService, listener, user, packageName, isCurrent,
                  )
                ) {
                  sent += 1
                }
                val shortcuts = shortcutsByPackage[packageName]
                if (shortcuts != null && dispatchShortcutsIfRegistered(
                    launcherService,
                    listener,
                    user,
                    packageName,
                    shortcuts,
                    generation,
                  )
                ) {
                  sent += 1
                  paced = true
                }
              } catch (failure: Throwable) {
                logFailure("launcher callback failed for $packageName", failure)
                paced = true
              }
              if (paced && cause.packageIntervalMs > 0) {
                try {
                  Thread.sleep(cause.packageIntervalMs)
                } catch (_: InterruptedException) {
                  Thread.currentThread().interrupt()
                  return
                }
              }
            }
          } catch (_: IconProbeCancelledException) {
            return
          } catch (failure: Throwable) {
            logFailure(
              "icon refresh skipped user=${profile.userId.invoke(user)}",
              failure,
            )
          }
        }
      }
      xposed.log(Log.INFO, TAG, "Icon refresh generation=$generation callbacks=$sent")
      lastRefreshReceipt = IconRefreshReceipt(sent, System.currentTimeMillis())
      completed = true
      generations.complete(generation)
    } catch (_: IconProbeCancelledException) {
      // A newer configuration or retirement owns the next refresh.
    } catch (failure: Throwable) {
      logFailure("icon refresh stopped", failure)
    } finally {
      synchronized(lock) {
        lifecycle.activeJobs -= 1
        if (!completed && !lifecycle.retired && generations.isCurrent(generation) &&
          (lifecycle.retiring || Thread.currentThread().isInterrupted) && pending.request == null
        ) pending.replace(request.copy(attempt = 0))
        if (pending.request != null && !pending.posted) postPendingLocked()
      }
    }
  }

  private fun notifyLauncherOfAppearanceChange(context: Context, active: () -> Boolean): Int {
    val isCurrent = { active() && !lifecycle.retiring && !lifecycle.retired }
    if (!isCurrent()) return 0
    val launcherService = service ?: return 0
    val listeners = snapshotListeners(launcherService) ?: return 0
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return 0
    val users = context.getSystemService(UserManager::class.java) ?: return 0
    val packageName = BuildConfig.APPLICATION_ID
    var sent = 0
    for (listener in listeners) {
      for (user in enabledProfiles(users, listener.owner).orEmpty()) {
        if (!isCurrent()) return sent
        // The native item-change bridge updates the mounted titles and materials.
        val installed = try { launcherApps.getApplicationInfo(packageName, 0, user) != null }
          catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
          catch (_: SecurityException) { false }
        try {
          if (installed && dispatchIfRegistered(launcherService, listener, user, packageName, isCurrent)) {
            sent++
          }
        } catch (failure: Exception) {
          if (isCurrent()) logFailure("could not notify launcher of appearance change", failure)
        }
      }
    }
    if (sent > 0) xposed.log(Log.INFO, TAG, "Launcher appearance notification callbacks=$sent")
    return sent
  }

  private fun dispatchIfRegistered(
    launcherService: Any,
    listener: LauncherListener,
    user: UserHandle,
    packageName: String,
    isCurrent: () -> Boolean,
  ): Boolean {
    @Suppress("UNCHECKED_CAST")
    val callbacks = profile.listeners.get(launcherService) as? RemoteCallbackList<IInterface>
      ?: return false
    val callback = profile.registeredCallbacks.findRegistered(callbacks, listener.cookie) { entry ->
      entry is IInterface && profile.callbackClass.isInstance(entry) &&
        entry.asBinder() == listener.callback.asBinder()
    } as? IInterface ?: return false
    if (!isCurrent()) return false
    if (profile.isEnabledProfile.invoke(
        launcherService, listener.cookie, user, "KeiHome icon refresh",
      ) != true ||
      profile.isPackageVisible.invoke(
        launcherService, packageName, listener.cookie, user,
      ) != true ||
      !callback.asBinder().isBinderAlive
    ) return false
    if (!isCurrent()) return false
    profile.onPackageChanged.invoke(callback, user, packageName)
    return true
  }

  /**
   * Sends one [LauncherIconBatch] callback; returns the packages it covered. A failure returns
   * none, so the caller refreshes every package through the per-package path instead.
   */
  private fun dispatchBatchIfRegistered(
    launcherService: Any,
    listener: LauncherListener,
    user: UserHandle,
    packages: Set<String>,
    isCurrent: () -> Boolean,
  ): Set<String> = try {
    sendBatch(launcherService, listener, user, packages, isCurrent)
  } catch (failure: Throwable) {
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    logFailure("launcher batch callback failed; using per-package callbacks", failure)
    emptySet()
  }

  private fun sendBatch(
    launcherService: Any,
    listener: LauncherListener,
    user: UserHandle,
    packages: Set<String>,
    isCurrent: () -> Boolean,
  ): Set<String> {
    val method = profile.onPackagesUnsuspended ?: return emptySet()
    if (packages.size < LauncherIconBatch.MIN_SIZE) return emptySet()
    @Suppress("UNCHECKED_CAST")
    val callbacks = profile.listeners.get(launcherService) as? RemoteCallbackList<IInterface>
      ?: return emptySet()
    val callback = profile.registeredCallbacks.findRegistered(callbacks, listener.cookie) { entry ->
      entry is IInterface && profile.callbackClass.isInstance(entry) &&
        entry.asBinder() == listener.callback.asBinder()
    } as? IInterface ?: return emptySet()
    if (!isCurrent() || profile.isEnabledProfile.invoke(
        launcherService, listener.cookie, user, "KeiHome icon refresh",
      ) != true || !callback.asBinder().isBinderAlive
    ) return emptySet()
    val visible = packages.filterTo(linkedSetOf()) {
      profile.isPackageVisible.invoke(launcherService, it, listener.cookie, user) == true
    }
    if (visible.size < LauncherIconBatch.MIN_SIZE || !isCurrent()) return emptySet()
    method.invoke(callback, user, iconBatch.order(profile.userId.invoke(user) as Int, visible))
    return visible
  }

  private fun dispatchShortcutsIfRegistered(
    launcherService: Any,
    listener: LauncherListener,
    user: UserHandle,
    packageName: String,
    shortcuts: List<*>,
    generation: Long,
  ): Boolean {
    @Suppress("UNCHECKED_CAST")
    val callbacks = profile.listeners.get(launcherService) as? RemoteCallbackList<IInterface>
      ?: return false
    val callback = profile.registeredCallbacks.findRegistered(callbacks, listener.cookie) { entry ->
      entry is IInterface && profile.callbackClass.isInstance(entry) &&
        entry.asBinder() == listener.callback.asBinder()
    } as? IInterface ?: return false
    if (!refreshCurrent(generation)) return false
    if (profile.isEnabledProfile.invoke(
        launcherService, listener.cookie, user, "KeiHome shortcut refresh",
      ) != true ||
      profile.isPackageVisible.invoke(
        launcherService, packageName, listener.cookie, user,
      ) != true ||
      !callback.asBinder().isBinderAlive
    ) return false
    val slice = profile.shortcutSliceConstructor.newInstance(shortcuts)
    profile.onShortcutChanged.invoke(callback, user, packageName, slice)
    return true
  }

  private fun snapshotListeners(launcherService: Any): List<LauncherListener>? {
    @Suppress("UNCHECKED_CAST")
    val callbacks = profile.listeners.get(launcherService) as? RemoteCallbackList<IInterface>
      ?: return null
    val entries = profile.registeredCallbacks.snapshot(callbacks) ?: return null
    val result = mutableListOf<LauncherListener>()
    for (entry in entries) {
      val cookie = entry.cookie ?: continue
      if (!profile.cookieClass.isInstance(cookie)) continue
      if (profile.cookiePackage.get(cookie) != LAUNCHER_PACKAGE) continue
      val owner = profile.cookieUser.get(cookie) as? UserHandle ?: continue
      val uid = profile.cookieUid.getInt(cookie)
      val callbackUser = profile.userId.invoke(
        UserHandle.getUserHandleForUid(uid),
      ) as Int
      if (callbackUser != profile.userId.invoke(owner) as Int) continue
      val callback = entry.callback as? IInterface ?: continue
      if (!profile.callbackClass.isInstance(callback)) continue
      result += LauncherListener(cookie, owner, callback)
    }
    return result
  }

  private fun enabledProfiles(manager: UserManager, owner: UserHandle): List<UserHandle>? {
    val ownerId = profile.userId.invoke(owner) as Int
    val profiles = profile.getEnabledProfiles.invoke(manager, ownerId) as? List<*>
      ?: return null
    return profiles.mapNotNull { info ->
      if (info == null) null else profile.userHandle.invoke(info) as? UserHandle
    }
  }

  private fun shortcutsByPackage(
    launcherApps: LauncherApps,
    user: UserHandle,
    packageName: String?,
  ): Map<String, List<*>> =
    try {
      val query = LauncherApps.ShortcutQuery().setQueryFlags(
        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
          LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
          LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
          LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED,
      )
      if (packageName != null) query.setPackage(packageName)
      launcherApps.getShortcuts(
        query,
        user,
      ).orEmpty().groupBy { it.`package` }
    } catch (failure: Throwable) {
      xposed.log(
        Log.DEBUG, TAG,
        "Shortcut enumeration unavailable for user=${profile.userId.invoke(user)}",
        failure,
      )
      emptyMap()
    }

  private fun logFailure(message: String, failure: Throwable) {
    xposed.log(Log.WARN, TAG, message, failure)
  }

  private data class LauncherListener(
    val cookie: Any,
    val owner: UserHandle,
    val callback: IInterface,
  )

  private data class ServerProfile(
    val launcherClass: Class<*>,
    val cookieClass: Class<*>,
    val callbackClass: Class<*>,
    val registerListener: Method,
    val updateConfiguration: Method,
    val getGlobalConfiguration: Method,
    val extraConfiguration: Field?,
    val themeChangedFlags: Field?,
    val context: Field,
    val listeners: Field,
    val registeredCallbacks: RegisteredCallbackReader,
    val cookiePackage: Field,
    val cookieUser: Field,
    val cookieUid: Field,
    val isEnabledProfile: Method,
    val isPackageVisible: Method,
    val onPackageChanged: Method,
    /** Optional: a missing batch callback keeps the per-package refresh. */
    val onPackagesUnsuspended: Method?,
    val onShortcutChanged: Method,
    val shortcutSliceConstructor: Constructor<*>,
    val getEnabledProfiles: Method,
    val userId: Method,
    val userHandle: Method,
  ) {
    // This runs only in system_server; unavailable internals are treated as unsupported.
    @SuppressLint("BlockedPrivateApi")
    fun findExistingService(loader: ClassLoader): Any? = try {
      val internal = Class.forName(
        "com.android.server.pm.LauncherAppsService\$LauncherAppsServiceInternal", false, loader,
      )
      val localServices = Class.forName("com.android.server.LocalServices", false, loader)
      val local = localServices.getDeclaredMethod("getService", Class::class.java)
        .invoke(null, internal)
      val owner = local?.javaClass?.declaredFields?.singleOrNull {
        !Modifier.isStatic(it.modifiers) && it.type == launcherClass
      } ?: return null
      owner.isAccessible = true
      owner.get(local)?.takeIf { launcherClass.isInstance(it) }
    } catch (_: Throwable) {
      null
    }

    companion object {
      fun resolve(loader: ClassLoader): ServerProfile {
        val launcher = Class.forName(
          "com.android.server.pm.LauncherAppsService\$LauncherAppsImpl", false, loader,
        )
        val cookie = Class.forName(
          "com.android.server.pm.LauncherAppsService\$BroadcastCookie", false, loader,
        )
        val callback = Class.forName("android.content.pm.IOnAppsChangedListener", false, loader)
        val activityTaskManager = Class.forName(
          "com.android.server.wm.ActivityTaskManagerService", false, loader,
        )
        val userInfo = Class.forName("android.content.pm.UserInfo", false, loader)
        val shortcutSliceClass = Class.forName(
          "android.content.pm.ParceledListSlice",
          false,
          loader,
        )
        val shortcutSliceConstructor = shortcutSliceClass
          .getConstructor(List::class.java)
          .apply { isAccessible = true }
        fun Class<*>.method(name: String, vararg args: Class<*>): Method =
          getDeclaredMethod(name, *args).apply { isAccessible = true }
        fun Class<*>.field(name: String): Field =
          getDeclaredField(name).apply { isAccessible = true }

        val updateConfiguration = activityTaskManager.method(
          "updateGlobalConfigurationLocked",
          Configuration::class.java,
          Boolean::class.javaPrimitiveType!!,
          Boolean::class.javaPrimitiveType!!,
          Int::class.javaPrimitiveType!!,
        )
        require(updateConfiguration.returnType == Int::class.javaPrimitiveType)
        val listeners = launcher.field("mListeners")
        require(RemoteCallbackList::class.java.isAssignableFrom(listeners.type))
        val registeredCallbacks = RegisteredCallbackReader.resolve(
          RemoteCallbackList::class.java,
          Class.forName("android.util.ArrayMap", false, RemoteCallbackList::class.java.classLoader),
          IInterface::class.java,
          IBinder::class.java,
        )
        val extraConfiguration = runCatching { Configuration::class.java.field("extraConfig") }.getOrNull()
        val themeChangedFlags = runCatching { extraConfiguration?.type?.field("themeChangedFlags") }.getOrNull()
        return ServerProfile(
          launcherClass = launcher,
          cookieClass = cookie,
          callbackClass = callback,
          registerListener = launcher.method(
            "addOnAppsChangedListener", String::class.java, callback,
          ),
          updateConfiguration = updateConfiguration,
          getGlobalConfiguration = activityTaskManager.method("getGlobalConfiguration"),
          extraConfiguration = extraConfiguration,
          themeChangedFlags = themeChangedFlags,
          context = launcher.field("mContext"),
          listeners = listeners,
          registeredCallbacks = registeredCallbacks,
          cookiePackage = cookie.field("packageName"),
          cookieUser = cookie.field("user"),
          cookieUid = cookie.field("callingUid"),
          isEnabledProfile = launcher.method(
            "isEnabledProfileOf", cookie, UserHandle::class.java, String::class.java,
          ),
          isPackageVisible = launcher.method(
            "isPackageVisibleToListener", String::class.java, cookie, UserHandle::class.java,
          ),
          onPackageChanged = callback.method(
            "onPackageChanged", UserHandle::class.java, String::class.java,
          ),
          onPackagesUnsuspended = runCatching {
            callback.method("onPackagesUnsuspended", UserHandle::class.java, Array<String>::class.java)
          }.getOrNull(),
          onShortcutChanged = callback.method(
            "onShortcutChanged",
            UserHandle::class.java,
            String::class.java,
            shortcutSliceClass,
          ),
          shortcutSliceConstructor = shortcutSliceConstructor,
          getEnabledProfiles = UserManager::class.java.method(
            "getEnabledProfiles", Int::class.javaPrimitiveType!!,
          ),
          userId = UserHandle::class.java.method("getIdentifier"),
          userHandle = userInfo.method("getUserHandle"),
        )
      }
    }
  }

  private companion object {
    const val WALLPAPER_DIM_HOOK_ID = "keihome.wallpaper-dim-change"
    const val TAG = "KeiHomeIconRefresh"
    const val LAUNCHER_PACKAGE = "com.miui.home"
    const val LISTENER_HOOK_ID = "keihome_launcher_listener"
    const val CONFIGURATION_HOOK_ID = "keihome_night_configuration"
    const val APPEARANCE_SWAP_HOOK_ID = "keihome.appearance-fast-swap"
    const val SNAPSHOT_RETRY_MS = 250L
    const val MAX_SNAPSHOT_RETRIES = 3
    const val RETIRE_TIMEOUT_MS = 5_000L
    const val CONFIG_THEME = 0x00400000
  }
}

internal fun findMatchingIconHook(
  oldHooks: List<XposedInterface.HookHandle>,
  id: String,
  executable: Executable,
): XposedInterface.HookHandle? {
  val matches = oldHooks.filter { it.id == id && it.executable == executable }
  require(matches.size <= 1) { "Multiple old hooks match $id and $executable" }
  return matches.singleOrNull()
}

internal fun installOrReplaceIconHook(
  xposed: XposedInterface,
  previous: XposedInterface.HookHandle?,
  id: String,
  executable: Executable,
  hooker: XposedInterface.Hooker,
): Pair<XposedInterface.HookHandle, XposedInterface.HookHandle?> {
  return if (previous != null) {
    require(previous.id == id && previous.executable == executable)
    previous.replaceHook(hooker) to previous
  } else {
    xposed.hook(executable).setId(id).intercept(hooker) to null
  }
}

// Access is serialized by the lifecycle except while retirement waits.
internal class RetiringIconRefreshThreads {
  private val threads = mutableListOf<Thread>()

  fun track(thread: Thread) {
    threads += thread
  }

  fun pruneStopped() {
    threads.removeAll { !it.isAlive }
  }

  fun hasLiveThreads(): Boolean = threads.any { it.isAlive }

  fun awaitStopped(deadlineNanos: Long): Boolean {
    for (thread in threads) {
      if (thread === Thread.currentThread()) return false
      val remaining = TimeUnit.NANOSECONDS.toMillis(
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0),
      )
      if (thread.isAlive && remaining > 0) {
        try {
          thread.join(remaining)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return false
        }
      }
      if (thread.isAlive) return false
    }
    return true
  }
}

internal data class IconRefreshRequest(val generation: Long, val attempt: Int)

// All operations are guarded by the runtime's lifecycle lock.
internal class PendingIconRefresh {
  var request: IconRefreshRequest? = null
    private set
  var posted = false
    private set
  private var ticket = 0L

  fun replace(value: IconRefreshRequest) {
    request = value
    invalidatePost()
  }

  fun invalidatePost() {
    ticket += 1
    posted = false
  }

  fun markPosted(): Long {
    check(request != null && !posted)
    ticket += 1
    posted = true
    return ticket
  }

  fun postFailed(value: Long) {
    if (ticket == value) posted = false
  }

  fun claim(value: IconRefreshRequest, postedTicket: Long): Boolean {
    if (!posted || ticket != postedTicket || request != value) return false
    request = null
    posted = false
    return true
  }
}

internal class IconRefreshReloadLifecycle {
  val lock = java.lang.Object()
  private val retirement = ReentrantLock()
  var activeHooks = 0
  var activeJobs = 0
  @Volatile var retiring = false
    private set
  @Volatile var retired = false
    private set

  fun <T> trackHook(block: () -> T): T {
    synchronized(lock) { activeHooks += 1 }
    try {
      return block()
    } finally {
      synchronized(lock) { activeHooks -= 1; lock.notifyAll() }
    }
  }

  /** Registration is owned work too; retirement cannot pass an in-flight durable grant write. */
  fun <T> withActiveRuntime(block: () -> T): T = trackHook {
    check(!retiring && !retired) { "Icon runtime is retiring" }
    block()
  }

  fun retire(
    prepare: () -> Boolean,
    await: () -> Boolean,
    commit: () -> Unit,
    rollback: () -> Unit,
    retireValidation: () -> Boolean = { true },
    resumeValidation: () -> Unit = {},
  ): Boolean {
    if (!retirement.tryLock()) return false
    try {
      synchronized(lock) {
        if (retired || retiring) return false
        retiring = true
        // Quiesce short in-flight callbacks instead of randomly rejecting reload
        // whenever a package/configuration callback overlaps the framework request.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750)
        while (activeHooks != 0) {
          val left = deadline - System.nanoTime()
          if (left <= 0 || Thread.currentThread().name == "main") {
            retiring = false
            return false
          }
          try { TimeUnit.NANOSECONDS.timedWait(lock, left) }
          catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            retiring = false
            return false
          }
        }
      }
      var validationRetired = false
      fun recover() {
        // Reopen the validator before admitting registrations or restarting the import worker.
        try { if (validationRetired) resumeValidation() } catch (_: Throwable) { return }
        synchronized(lock) { retiring = false }
        try { rollback() } catch (_: Throwable) { }
      }
      try {
        // Shutdown and Looper recovery must never hold the lock used by platform hooks.
        if (!prepare()) {
          synchronized(lock) { retiring = false }
          return false
        }
      } catch (_: Throwable) {
        recover()
        return false
      }
      val stopped = try { await() } catch (_: Throwable) { false }
      val committed = synchronized(lock) {
        if (!stopped || activeHooks != 0 || activeJobs != 0) false
        else try {
          // A slow/failed worker drain must leave periodic converted-icon validation active.
          if (!retireValidation()) false else {
            validationRetired = true
            commit()
            retired = true
            retiring = false
            true
          }
        } catch (_: Throwable) { false }
      }
      if (!committed) recover()
      return committed
    } finally {
      retirement.unlock()
    }
  }
}

internal data class RegisteredCallbackEntry(val callback: Any, val cookie: Any?)

internal class RegisteredCallbackReader private constructor(
  private val listClass: Class<*>,
  private val mapClass: Class<*>,
  private val callbackClass: Class<*>,
  private val binderClass: Class<*>,
  private val interfaces: Field,
  private val entryClass: Class<*>,
  private val entryBinder: Field,
  private val entryCallback: Field,
  private val entryCookie: Field,
  private val count: Method,
  private val item: Method,
  private val cookie: Method,
  private val keyAt: Method,
  private val valueAt: Method,
  private val baseMethods: List<Method>,
) {
  fun snapshot(callbacks: Any): List<RegisteredCallbackEntry>? = try {
    require(listClass.isInstance(callbacks))
    var currentClass = callbacks.javaClass
    while (currentClass != listClass) {
      require(currentClass.declaredFields.none { it.name == interfaces.name })
      currentClass = currentClass.superclass ?: error("Unsupported callback list")
    }
    for (method in baseMethods) {
      require(callbacks.javaClass.getMethod(method.name, *method.parameterTypes) == method)
    }
    val registry = requireNotNull(interfaces.get(callbacks))
    require(registry.javaClass == mapClass)
    // The verified platform layout uses this monitor for registration and binder death.
    synchronized(registry) {
      require(interfaces.get(callbacks) === registry)
      val size = count.invoke(callbacks) as Int
      require(size >= 0 && size == (registry as Map<*, *>).size)
      val entries = ArrayList<RegisteredCallbackEntry>(size)
      for (index in 0 until size) {
        val callback = requireNotNull(item.invoke(callbacks, index))
        val callbackCookie = cookie.invoke(callbacks, index)
        val key = keyAt.invoke(registry, index)
        val record = valueAt.invoke(registry, index)
        require(callbackClass.isInstance(callback) && binderClass.isInstance(key))
        require(entryClass.isInstance(record))
        require(entryCallback.get(record) === callback && entryCookie.get(record) === callbackCookie)
        require(entryBinder.get(record) === key)
        entries += RegisteredCallbackEntry(callback, callbackCookie)
      }
      require(interfaces.get(callbacks) === registry)
      entries
    }
  } catch (_: Throwable) {
    null
  }

  fun findRegistered(callbacks: Any, expectedCookie: Any, matches: (Any) -> Boolean): Any? =
    snapshot(callbacks)
      ?.firstOrNull { it.cookie === expectedCookie && matches(it.callback) }
      ?.callback

  companion object {
    fun resolve(
      listClass: Class<*>,
      mapClass: Class<*>,
      callbackClass: Class<*>,
      binderClass: Class<*>,
    ): RegisteredCallbackReader {
      require(callbackClass.isInterface && binderClass.isInterface)
      require(Modifier.isFinal(mapClass.modifiers) && Map::class.java.isAssignableFrom(mapClass))
      fun Class<*>.field(name: String, type: Class<*>, final: Boolean = false): Field =
        getDeclaredField(name).apply {
          require(this.type == type && !Modifier.isStatic(modifiers))
          require(!final || Modifier.isFinal(modifiers))
          isAccessible = true
        }
      fun Class<*>.method(name: String, result: Class<*>, vararg args: Class<*>): Method =
        getDeclaredMethod(name, *args).apply {
          require(returnType == result && Modifier.isPublic(modifiers))
          require(!Modifier.isStatic(modifiers) && !Modifier.isAbstract(modifiers))
          require(!Modifier.isNative(modifiers))
          isAccessible = true
        }

      val interfaces = listClass.field("mInterfaces", mapClass)
      val entryClass = listClass.declaredClasses.single { it.simpleName == "Interface" }
      require(entryClass.enclosingClass == listClass && !Modifier.isStatic(entryClass.modifiers))
      require(Modifier.isFinal(entryClass.modifiers))
      val count = listClass.method("getRegisteredCallbackCount", Int::class.javaPrimitiveType!!)
      val item = listClass.method(
        "getRegisteredCallbackItem", callbackClass, Int::class.javaPrimitiveType!!,
      )
      val cookie = listClass.method(
        "getRegisteredCallbackCookie", Any::class.java, Int::class.javaPrimitiveType!!,
      )
      val baseMethods = listOf(
        count, item, cookie,
        listClass.method("register", Boolean::class.javaPrimitiveType!!, callbackClass),
        listClass.method(
          "register", Boolean::class.javaPrimitiveType!!, callbackClass, Any::class.java,
        ),
        listClass.method("unregister", Boolean::class.javaPrimitiveType!!, callbackClass),
        listClass.method("kill", Void.TYPE),
      )
      return RegisteredCallbackReader(
        listClass = listClass,
        mapClass = mapClass,
        callbackClass = callbackClass,
        binderClass = binderClass,
        interfaces = interfaces,
        entryClass = entryClass,
        entryBinder = entryClass.field("mBinder", binderClass, final = true),
        entryCallback = entryClass.field("mInterface", callbackClass, final = true),
        entryCookie = entryClass.field("mCookie", Any::class.java, final = true),
        count = count,
        item = item,
        cookie = cookie,
        keyAt = mapClass.method("keyAt", Any::class.java, Int::class.javaPrimitiveType!!),
        valueAt = mapClass.method("valueAt", Any::class.java, Int::class.javaPrimitiveType!!),
        baseMethods = baseMethods,
      )
    }
  }
}
