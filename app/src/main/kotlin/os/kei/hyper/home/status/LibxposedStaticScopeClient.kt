package os.kei.hyper.home.status

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.libxposed.service.XposedService

internal class LibxposedStaticScopeClient(
  private val context: Context,
  private val service: XposedService,
) : StaticScopeClient {
  override val identity: Any get() = service
  private val declared by lazy { readStaticScopeDeclaration(context) }

  fun plan(granted: Set<String>, properties: Long): StaticScopePlan =
    planStaticScope(declared, granted, properties and XposedService.PROP_CAP_SYSTEM.toLong() != 0L) { name ->
      try {
        context.packageManager.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0))
          .flags and ApplicationInfo.FLAG_INSTALLED != 0
      } catch (_: PackageManager.NameNotFoundException) {
        false
      } catch (_: Exception) {
        null
      }
    }

  override fun readPlan(): StaticScopePlan = plan(service.scope.toSet(), service.frameworkProperties)

  override fun request(packages: List<String>, callback: ScopeDecision) {
    require(packages.isNotEmpty())
    require(packages.all { it in declared })
    service.requestScope(packages, object : XposedService.OnScopeEventListener {
      override fun onScopeRequestApproved(approved: List<String>) { callback.complete(true, null) }
      override fun onScopeRequestFailed(message: String) { callback.complete(false, message) }
    })
  }
}

internal fun readStaticScopeDeclaration(context: Context): List<String> =
  checkNotNull(context.classLoader.getResourceAsStream("META-INF/xposed/scope.list")) {
    "Missing static scope declaration"
  }.bufferedReader().use { reader ->
    reader.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
      .distinct().toList().also { require(it.isNotEmpty()) }
  }
