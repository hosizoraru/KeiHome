package os.kei.hyper.home.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import android.util.Log
import os.kei.hyper.home.BuildConfig

class KeiHomeModule : XposedModule() {
  private var launcherRefresh: LauncherIconRefreshRuntime? = null
  private var themeIconImport: ThemeIconImportRuntime? = null
  private var systemUiTheme: SystemUiThemeCompatibilityRuntime? = null
  private var shareIcons: HyperOsShareIcons? = null
  private var hostAppIcons: HostAppIconRuntime? = null
  private var processName: String = ""
  private val reloadState = ModuleReloadState(
    requireNotNull(KeiHomeModule::class.java.classLoader), BuildConfig.VERSION_NAME,
    ModulePlatformRoots::release,
    { message -> log(Log.INFO, "KeiHomeRetirement", message) },
  )

  override fun onModuleLoaded(param: ModuleLoadedParam) {
    processName = param.processName
  }

  override fun onPackageLoaded(param: PackageLoadedParam) {
    if (!param.isFirstPackage) return
    when (param.packageName) {
      HostAppIconRuntime.SIDEBAR_PACKAGE, HostAppIconRuntime.SETTINGS_PACKAGE ->
        if (HostAppIconRuntime.supports(processName) && hostAppIcons == null)
          installHostAppIcons(param.defaultClassLoader)
      HyperOsShareProfile.PACKAGE ->
        if (shareIcons == null) installShareIcons(param.defaultClassLoader)
      ThemeIconImportRuntime.PACKAGE_NAME ->
        if (themeIconImport == null) installThemeImportHooks(param.defaultClassLoader, info = param.applicationInfo)
      SystemUiThemeCompatibilityRuntime.PACKAGE_NAME -> {
        if (systemUiTheme == null) installSystemUiThemeHooks(param.defaultClassLoader)
      }
    }
  }

  override fun onPackageReady(param: PackageReadyParam) {
    when (param.packageName) {
      HostAppIconRuntime.SIDEBAR_PACKAGE, HostAppIconRuntime.SETTINGS_PACKAGE ->
        if (HostAppIconRuntime.supports(processName) && hostAppIcons == null)
          installHostAppIcons(param.classLoader)
      HyperOsShareProfile.PACKAGE ->
        if (shareIcons == null) installShareIcons(param.classLoader)
      ThemeIconImportRuntime.PACKAGE_NAME ->
        if (themeIconImport == null) installThemeImportHooks(param.classLoader, info = param.applicationInfo)
      SystemUiThemeCompatibilityRuntime.PACKAGE_NAME ->
        if (systemUiTheme == null) installSystemUiThemeHooks(param.classLoader)
    }
  }

  override fun onSystemServerStarting(param: SystemServerStartingParam) {
    installSystemServerHooks(param.classLoader, null)
  }

  override fun onHotReloading(param: HotReloadingParam): Boolean {
    if (processName.substringBefore(':') == "com.miui.home") return true
    return reloadState.retire(param::setSavedInstanceState) { publish ->
      val ui = systemUiTheme
      val themes = themeIconImport
      val server = launcherRefresh
      val share = shareIcons
      val sidebar = hostAppIcons
      when {
        sidebar != null -> sidebar.retire { publish(sidebar.classLoader) }
        share != null -> share.retire { publish(share.classLoader) }
        ui != null -> ui.retire { publish(ui.classLoader) }
        themes != null -> themes.retire { publish(themes.classLoader) }
        server != null -> server.retire { publish(null) }
        else -> { publish(null); true }
      }.also { ready ->
        if (!ready) log(Log.WARN, "KeiHomeRetirement",
          "Hot reload deferred in $processName: " +
            (sidebar?.retirementStatus() ?: share?.retirementStatus() ?:
              ui?.retirementStatus() ?: server?.retirementStatus() ?: "cleanup pending"))
      }
    }
  }

  override fun onHotReloaded(param: HotReloadedParam) {
    processName = param.processName
    if (processName.substringBefore(':') == "com.miui.home") return
    val savedState = reloadState.restore(param.savedInstanceState)
    if (!param.isSystemServer) {
      if (HostAppIconRuntime.supports(param.processName)) {
        val application = runCatching {
          Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            .invoke(null) as? android.app.Application
        }.getOrNull()
        val loader = application?.classLoader ?: savedState as? ClassLoader
        if (loader != null) installHostAppIcons(loader, param.oldHookHandles)
        else log(Log.WARN, "KeiHomeSidebarIcons", "Sidebar loader unavailable after reload")
        reloadState.startMaintenance()
        return
      }
      if (param.processName.substringBefore(':') == HyperOsShareProfile.PACKAGE) {
        val application = runCatching {
          Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            .invoke(null) as? android.app.Application
        }.getOrNull()
        val loader = (listOfNotNull(savedState as? ClassLoader, application?.classLoader) +
          param.oldHookHandles.mapNotNull { it.executable.declaringClass.classLoader })
          .firstOrNull { runCatching { Class.forName(HyperOsShareProfile.HOLDER, false, it) }.isSuccess }
        if (loader != null) installShareIcons(loader, param.oldHookHandles)
        else log(Log.WARN, "KeiHomeShareIcons", "OEM resolver loader unavailable after reload")
        reloadState.startMaintenance()
        return
      }
      if (param.processName.substringBefore(':') == SystemUiThemeCompatibilityRuntime.PACKAGE_NAME) {
        val loader = findSystemUiClassLoader(
          param.oldHookHandles.mapNotNull { it.executable.declaringClass.classLoader } +
            listOfNotNull(savedState as? ClassLoader),
        )
        if (loader != null) installSystemUiThemeHooks(loader, param.oldHookHandles)
        else log(Log.WARN, "KeiHomeSystemUiTheme", "SystemUI class loader unavailable after reload")
        reloadState.startMaintenance()
        return
      }
      if (param.processName.substringBefore(':') == ThemeIconImportRuntime.PACKAGE_NAME) {
        // A failed previous install may leave neither saved state nor a live hook handle.
        val applicationLoader = runCatching {
          val app = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            .invoke(null) as? android.app.Application
          app?.takeIf { it.packageName == ThemeIconImportRuntime.PACKAGE_NAME }?.classLoader
        }.getOrNull()
        val preferred = listOfNotNull(savedState as? ClassLoader) +
          param.oldHookHandles.mapNotNull { it.executable.declaringClass.classLoader } +
          listOfNotNull(applicationLoader, Thread.currentThread().contextClassLoader, javaClass.classLoader)
        val loader = preferred.distinct().firstOrNull {
          runCatching { Class.forName(ThemeIconImportRuntime.SERVICE_CLASS, false, it) }.isSuccess
        }
        if (loader != null) installThemeImportHooks(loader, param.oldHookHandles)
        else log(Log.WARN, "KeiHomeThemeImport", "Themes class loader unavailable after reload")
      }
      reloadState.startMaintenance()
      return
    }

    val loader = findServerClassLoader(
      param.oldHookHandles.mapNotNull {
        try {
          it.executable.declaringClass.classLoader
        } catch (_: Throwable) {
          null
        }
      } + listOfNotNull(savedState?.javaClass?.classLoader),
    )
    if (loader != null) {
      installSystemServerHooks(loader, savedState, param.oldHookHandles, true)
    } else {
      param.oldHookHandles.forEach {
        try {
          it.unhook()
        } catch (failure: Throwable) {
          log(Log.WARN, "KeiHomeIconRefresh", "Could not remove old hook", failure)
        }
      }
      log(Log.WARN, "KeiHomeIconRefresh", "System-server class loader unavailable")
      launcherRefresh = LauncherIconRefreshRuntime(this)
    }
    reloadState.startMaintenance()
  }

  private fun installHostAppIcons(loader: ClassLoader, oldHooks: List<XposedInterface.HookHandle> = emptyList()) {
    val runtime = HostAppIconRuntime(this, loader, processName)
    hostAppIcons = runtime
    try { runtime.install(oldHooks) } catch (failure: Exception) {
      log(Log.WARN, "KeiHomeSidebarIcons", "Sidebar icons unavailable; retaining native behavior", failure)
    }
  }

  private fun installShareIcons(loader: ClassLoader, oldHooks: List<XposedInterface.HookHandle> = emptyList()) {
    val runtime = HyperOsShareIcons(this, loader)
    shareIcons = runtime
    try { runtime.install(oldHooks) } catch (failure: Exception) {
      log(Log.WARN, "KeiHomeShareIcons", "OEM share icons unavailable; retaining native behavior", failure)
    }
  }

  private fun installThemeImportHooks(loader: ClassLoader, oldHooks: List<XposedInterface.HookHandle> = emptyList(),
    info: android.content.pm.ApplicationInfo? = null) {
    val hostInfo = info ?: (Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
      .invoke(null) as android.app.Application).applicationInfo
    val profile = ThemeImportProfile.resolve(loader, hostInfo)
    val runtime = ThemeIconImportRuntime(this, loader, profile)
    runtime.install(oldHooks)
    themeIconImport = runtime
  }

  private fun installSystemUiThemeHooks(
    loader: ClassLoader,
    oldHooks: List<XposedInterface.HookHandle> = emptyList(),
  ) {
    val runtime = SystemUiThemeCompatibilityRuntime(this, loader)
    systemUiTheme = runtime
    try {
      runtime.install(oldHooks)
    } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
      android.util.Log.w("KeiHomeMaterial", "SystemUI adaptation unavailable; keeping native behavior", failure)
    }
  }

  private fun findSystemUiClassLoader(preferred: List<ClassLoader>): ClassLoader? =
    (preferred + listOfNotNull(
      Thread.currentThread().contextClassLoader,
      ClassLoader.getSystemClassLoader(),
      javaClass.classLoader,
    )).distinct().firstOrNull(SystemUiThemeCompatibilityRuntime::isHostClassLoader)

  private fun findServerClassLoader(preferred: List<ClassLoader>): ClassLoader? =
    (preferred + listOfNotNull(
      Thread.currentThread().contextClassLoader,
      ClassLoader.getSystemClassLoader(),
      javaClass.classLoader,
    )).distinct().firstOrNull { loader ->
      try {
        Class.forName("com.android.server.pm.LauncherAppsService\$LauncherAppsImpl", false, loader)
        Class.forName("com.android.server.wm.ActivityTaskManagerService", false, loader)
        true
      } catch (_: Throwable) {
        false
      }
    }

  private fun installSystemServerHooks(
    loader: ClassLoader,
    oldService: Any?,
    oldHooks: List<XposedInterface.HookHandle> = emptyList(),
    reconcileAfterReload: Boolean = false,
  ) {
    val runtime = LauncherIconRefreshRuntime(this)
    launcherRefresh = runtime
    check(runtime.install(loader, oldService, oldHooks, reconcileAfterReload)) {
      "System-server icon refresh/native broker installation failed"
    }
  }
}
