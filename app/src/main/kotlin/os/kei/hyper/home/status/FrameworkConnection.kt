package os.kei.hyper.home.status

import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import os.kei.hyper.home.BuildConfig
import android.util.Log

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
  val target: FrameworkTarget?,
  val launcherTarget: FrameworkTarget?,
  val frameworkVersion: String? = null,
  val frameworkVersionCode: Long? = null,
  val themesInScope: Boolean = false,
)

internal data class FrameworkTarget(val state: HookedTarget.State, val loadedVersionCode: Long)

internal fun XposedService.readFrameworkSnapshot(): FrameworkSnapshot {
  val targets = runningTargets
  val target = targets.firstOrNull {
    it.uid == android.os.Process.SYSTEM_UID &&
      (it.processName == "system_server" || it.processName == "system")
  }
  val launcherTarget = targets.firstOrNull {
    // A launcher subprocess does not report the state of the visible launcher process.
    it.processName == "com.miui.home"
  }
  return FrameworkSnapshot(
    name = frameworkName,
    apiVersion = apiVersion,
    systemInScope = "system" in scope,
    target = target?.let { FrameworkTarget(it.state, it.loadedVersionCode) },
    launcherTarget = launcherTarget?.let { FrameworkTarget(it.state, it.loadedVersionCode) },
    // libxposed service 102 exposes these separately from the service API level.
    // Let a service read failure invalidate the snapshot instead of retaining stale identity.
    frameworkVersion = frameworkVersion.trim().takeIf { it.isNotEmpty() },
    frameworkVersionCode = frameworkVersionCode.takeIf { it >= 0 },
    themesInScope = "com.android.thememanager" in scope,
  )
}

/** Retry Java targets on app connection if an in-flight operation refused install-time reload. */
internal fun XposedService.upgradeOlderSystemTarget() {
  if (apiVersion < 102) return
  val user = android.os.Process.myUid() / 100_000
  val targets = runningTargets.filter {
    ((it.uid == android.os.Process.SYSTEM_UID && it.processName in setOf("system_server", "system")) ||
      (it.processName == "com.android.thememanager" && it.uid / 100_000 == user)) &&
      it.loadedVersionCode < BuildConfig.VERSION_CODE
  }
  for (target in targets) hotReloadModule(target, null) { _, result ->
    Log.i("KeiHomeReload", "API 102 upgrade ${target.processName}: ${result.status}, ${result.message}")
  }
}
