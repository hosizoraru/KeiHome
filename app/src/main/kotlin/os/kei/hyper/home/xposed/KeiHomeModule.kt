package os.kei.hyper.home.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * API 102 entry point.
 *
 * Target scopes and hooks are intentionally unset until KeiHome's target is defined.
 */
class KeiHomeModule : XposedModule() {
  override fun onModuleLoaded(param: ModuleLoadedParam) = Unit

  override fun onPackageLoaded(param: PackageLoadedParam) = Unit

  override fun onSystemServerStarting(param: SystemServerStartingParam) = Unit
}
