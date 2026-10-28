package os.kei.hyper.home.xposed

import io.github.libxposed.api.XposedModule
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
    if (!runtime.retire()) return false
    param.setSavedInstanceState(runtime.service)
    return true
  }

  override fun onHotReloaded(param: HotReloadedParam) {
    if (!param.isSystemServer) return

    val loader = findServerClassLoader(
      param.oldHookHandles.firstNotNullOfOrNull {
        it.executable.declaringClass.classLoader
      } ?: param.savedInstanceState?.javaClass?.classLoader,
    )
    param.oldHookHandles.forEach { it.unhook() }
    if (loader != null) {
      installSystemServerHooks(loader, param.savedInstanceState)
    } else {
      log(Log.WARN, "KeiHomeIconRefresh", "System-server class loader unavailable")
    }
  }

  private fun findServerClassLoader(preferred: ClassLoader?): ClassLoader? =
    listOf(
      preferred,
      Thread.currentThread().contextClassLoader,
      ClassLoader.getSystemClassLoader(),
      javaClass.classLoader,
    ).filterNotNull().firstOrNull { loader ->
      try {
        Class.forName("com.android.server.pm.LauncherAppsService\$LauncherAppsImpl", false, loader)
        Class.forName("com.android.server.wm.ActivityTaskManagerService", false, loader)
        true
      } catch (_: Throwable) {
        false
      }
    }

  private fun installSystemServerHooks(loader: ClassLoader, oldService: Any?) {
    val runtime = LauncherIconRefreshRuntime(this)
    if (runtime.install(loader, oldService)) launcherRefresh = runtime
  }
}
