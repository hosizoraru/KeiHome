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

  override fun onModuleLoaded(param: ModuleLoadedParam) = Unit

  override fun onPackageLoaded(param: PackageLoadedParam) = Unit

  override fun onSystemServerStarting(param: SystemServerStartingParam) {
    installSystemServerHooks(param.classLoader, null)
  }

  override fun onHotReloading(param: HotReloadingParam): Boolean {
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
    if (!param.isSystemServer) return

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
