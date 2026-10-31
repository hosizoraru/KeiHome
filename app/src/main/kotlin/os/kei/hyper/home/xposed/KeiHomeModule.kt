package os.kei.hyper.home.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import android.util.Log

class KeiHomeModule : XposedModule() {
  private var launcherRefresh: LauncherIconRefreshRuntime? = null
  private var themeIconImport: ThemeIconImportRuntime? = null

  override fun onModuleLoaded(param: ModuleLoadedParam) = Unit

  override fun onPackageLoaded(param: PackageLoadedParam) {
    if (param.isFirstPackage && param.packageName == ThemeIconImportRuntime.PACKAGE_NAME && themeIconImport == null) {
      installThemeImportHooks(param.defaultClassLoader)
    }
  }

  override fun onSystemServerStarting(param: SystemServerStartingParam) {
    installSystemServerHooks(param.classLoader, null)
  }

  override fun onHotReloading(param: HotReloadingParam): Boolean {
    themeIconImport?.let { runtime ->
      return runtime.retire { param.setSavedInstanceState(runtime.classLoader) }
    }
    val runtime = launcherRefresh ?: return true
    return runtime.retire {
      try {
        param.setSavedInstanceState(null)
      } catch (failure: Throwable) {
        log(Log.WARN, "KeiHomeIconRefresh", "Could not save reload state", failure)
        throw failure
      }
    }
  }

  override fun onHotReloaded(param: HotReloadedParam) {
    if (!param.isSystemServer) {
      if (param.processName.substringBefore(':') == ThemeIconImportRuntime.PACKAGE_NAME) {
        // A failed previous install may leave neither saved state nor a live hook handle.
        val applicationLoader = runCatching {
          val app = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            .invoke(null) as? android.app.Application
          app?.takeIf { it.packageName == ThemeIconImportRuntime.PACKAGE_NAME }?.classLoader
        }.getOrNull()
        val preferred = listOfNotNull(param.savedInstanceState as? ClassLoader) +
          param.oldHookHandles.mapNotNull { it.executable.declaringClass.classLoader } +
          listOfNotNull(applicationLoader, Thread.currentThread().contextClassLoader, javaClass.classLoader)
        val loader = preferred.distinct().firstOrNull {
          runCatching { Class.forName(ThemeIconImportRuntime.SERVICE_CLASS, false, it) }.isSuccess
        }
        if (loader != null) installThemeImportHooks(loader, param.oldHookHandles)
        else log(Log.WARN, "KeiHomeThemeImport", "Themes class loader unavailable after reload")
      }
      return
    }

    val loader = findServerClassLoader(
      param.oldHookHandles.mapNotNull {
        try {
          it.executable.declaringClass.classLoader
        } catch (_: Throwable) {
          null
        }
      } + listOfNotNull(param.savedInstanceState?.javaClass?.classLoader),
    )
    if (loader != null) {
      installSystemServerHooks(loader, param.savedInstanceState, param.oldHookHandles, true)
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
  }

  private fun installThemeImportHooks(loader: ClassLoader, oldHooks: List<XposedInterface.HookHandle> = emptyList()) {
    val runtime = ThemeIconImportRuntime(this, loader)
    runtime.install(oldHooks)
    themeIconImport = runtime
  }

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
