package os.kei.hyper.home.status

import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object FrameworkConnection : XposedServiceHelper.OnServiceListener {
  private val lock = Any()
  private val live = mutableListOf<XposedService>()
  private val dead = Collections.newSetFromMap(IdentityHashMap<XposedService, Boolean>())
  private val mutableService = MutableStateFlow<XposedService?>(null)
  val service = mutableService.asStateFlow()

  override fun onServiceBind(service: XposedService) {
    synchronized(lock) {
      if (service in dead) return
      live.removeAll { it === service }
      live.add(service)
      mutableService.value = service
    }
  }

  override fun onServiceDied(service: XposedService) {
    synchronized(lock) {
      dead.add(service)
      live.removeAll { it === service }
      if (mutableService.value === service) mutableService.value = live.lastOrNull()
    }
  }
}

internal data class FrameworkSnapshot(
  val name: String,
  val apiVersion: Int,
  val systemInScope: Boolean,
  val target: HookedTarget?,
)

internal fun XposedService.readFrameworkSnapshot(): FrameworkSnapshot {
  val target = runningTargets.firstOrNull {
    it.uid == android.os.Process.SYSTEM_UID &&
      (it.processName == "system_server" || it.processName == "system")
  }
  return FrameworkSnapshot(
    name = frameworkName,
    apiVersion = apiVersion,
    systemInScope = "system" in scope,
    target = target,
  )
}
