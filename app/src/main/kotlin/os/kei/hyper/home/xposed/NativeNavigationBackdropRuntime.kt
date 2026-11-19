package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.BILIBILI_PACKAGE
import os.kei.hyper.home.data.NavigationBackdropChoice
import os.kei.hyper.home.data.NavigationBackdropPreferences

internal class NativeNavigationBackdropRuntime(
  private val xposed: XposedInterface,
  private val onChoiceChanged: () -> Unit,
) {
  @Volatile private var active = false
  @Volatile private var choice = NavigationBackdropChoice()
  private var preferences: SharedPreferences? = null
  private val reported = ConcurrentHashMap.newKeySet<String>()
  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { source, _ ->
    refresh(source)
  }

  fun install(
    loader: ClassLoader,
    oldHooks: List<XposedInterface.HookHandle>,
    runActive: ((() -> Any?) -> Any?),
  ): Pair<XposedInterface.HookHandle, XposedInterface.HookHandle?> {
    val type = Class.forName("com.android.nbi.MiuiNBIManagerService\$Inner", false, loader)
    val method = type.getDeclaredMethod("getSystemNBIRules", String::class.java)
    require(method.returnType == Bundle::class.java)
    val context = type.getDeclaredField("mContext").apply { isAccessible = true }
    return installOrReplaceIconHook(xposed, findMatchingIconHook(oldHooks, HOOK_ID, method),
      HOOK_ID, method, XposedInterface.Hooker { chain ->
        runActive trackNavigation@{
          val original = chain.proceed()
          val selected = choice
          val packageName = chain.getArg(0) as? String
          if (!active || packageName == null || !selected.selects(packageName) ||
            packageName == BuildConfig.APPLICATION_ID || packageName == "com.android.systemui") original
          else try {
            val uid = Binder.getCallingUid()
            if (!selected.includes(packageName, uid)) return@trackNavigation original
            val host = context.get(chain.thisObject) as Context
            val pm = host.packageManager
            val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
              ?.activityInfo?.packageName
            if (packageName == home || pm.getPackagesForUid(uid)?.contains(packageName) != true) original
            else {
              val artworkActivity = if (packageName == BILIBILI_PACKAGE) runCatching {
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
                pm.resolveActivity(launch, PackageManager.ResolveInfoFlags.of(0))?.activityInfo?.let {
                  it.targetActivity ?: it.name
                }
              }.getOrNull() else null
              NavigationRuleOverlay.apply(original as? Bundle, artworkActivity).also {
                if (it !== original && reported.add(packageName))
                  xposed.log(Log.INFO, TAG, "Native rule delivery enabled for $packageName; OEM table unchanged")
              }
            }
          } catch (failure: Exception) {
            if (reported.add(packageName))
              xposed.log(Log.WARN, TAG, "Native rule delivery left unchanged for $packageName", failure)
            original
          }
        }
      })
  }

  @Synchronized fun start() {
    if (active) return
    active = true
    try {
      val source = xposed.getRemotePreferences(NavigationBackdropPreferences.GROUP)
      preferences = source
      source.registerOnSharedPreferenceChangeListener(listener)
      refresh(source)
    } catch (failure: Exception) {
      active = false
      choice = NavigationBackdropChoice()
      runCatching { preferences?.unregisterOnSharedPreferenceChangeListener(listener) }
      preferences = null
      xposed.log(Log.WARN, TAG, "Navigation backdrop preferences unavailable; keeping OEM behavior", failure)
    }
  }

  @Synchronized fun stop() {
    active = false
    choice = NavigationBackdropChoice()
    runCatching { preferences?.unregisterOnSharedPreferenceChangeListener(listener) }
    preferences = null
    reported.clear()
  }

  @Synchronized private fun refresh(source: SharedPreferences) {
    if (!active || preferences !== source) return
    val updated = try { NavigationBackdropPreferences.read(source) }
      catch (_: Exception) { NavigationBackdropChoice() }
    if (choice != updated) {
      choice = updated
      reported.clear()
      onChoiceChanged()
    }
  }

  fun fingerprint(): String = choice.fingerprint()

  companion object {
    const val HOOK_ID = "keihome_native_navigation_rules"
    const val TAG = "KeiHomeNavBackdrop"
  }
}
