package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.NavigationBackdropChoice
import os.kei.hyper.home.data.NavigationBackdropPreferences
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.NavigationBackdropInbox

internal data class NavigationBackdropApp(
  val packageName: String,
  val label: String,
  val systemApp: Boolean = false,
  val artwork: PreservedIconApp? = null,
)
internal data class NavigationBackdropUiState(
  val choice: NavigationBackdropChoice? = null,
  val apps: List<NavigationBackdropApp> = emptyList(),
  val loading: Boolean = true,
  val saving: Boolean = false,
  val unavailable: Boolean = false,
  val failed: Boolean = false,
)

internal class NavigationBackdropSettings(
  private val context: Context,
  private val scope: CoroutineScope,
  private val inScope: () -> Boolean,
) {
  private val mutable = MutableStateFlow(NavigationBackdropUiState())
  val state = mutable.asStateFlow()
  private val mutex = Mutex()
  private var loadedApps: List<NavigationBackdropApp>? = null
  private var loadedLocale: String? = null
  fun reset() { mutable.value = NavigationBackdropUiState() }

  suspend fun read(refreshApps: Boolean = false) = mutex.withLock {
    val service = FrameworkConnection.service.value
    if (service == null || !inScope()) {
      mutable.value = NavigationBackdropUiState(loading = false, unavailable = true)
      return@withLock
    }
    mutable.update { it.copy(loading = true) }
    try {
      val choice = NavigationBackdropPreferences.read(service)
      val apps = withContext(Dispatchers.IO) {
        NavigationBackdropInbox.read(context)
        val locale = context.resources.configuration.locales.toLanguageTags()
        if (refreshApps || loadedLocale != locale) loadedApps = null
        loadedApps ?: loadApps().also { loadedApps = it; loadedLocale = locale }
      }
      if (FrameworkConnection.service.value === service)
        mutable.value = NavigationBackdropUiState(choice = choice, apps = apps, loading = false)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
      if (FrameworkConnection.service.value === service)
        mutable.update { it.copy(loading = false, failed = true) }
    }
  }

  fun enabled(value: Boolean) = save { it.copy(enabled = value) }
  fun toggle(packageName: String) = save {
    it.copy(packages = if (packageName in it.packages) it.packages - packageName else it.packages + packageName)
  }

  private fun save(change: (NavigationBackdropChoice) -> NavigationBackdropChoice) {
    val service = FrameworkConnection.service.value ?: return
    val current = mutable.value
    if (!inScope() || current.loading || current.saving || current.choice == null) return
    val choice = change(current.choice).copy(userId = Process.myUid() / 100_000)
    if (choice.packages.size > NavigationBackdropPreferences.MAX_PACKAGES) return
    val backend = NavigationBackdropInbox.status.value
    if (choice.enabled && (backend?.available != true || backend.version != BuildConfig.VERSION_CODE.toLong())) return
    mutable.update { it.copy(saving = true) }
    scope.launch {
      mutex.withLock {
        if (FrameworkConnection.service.value !== service || !inScope()) {
          if (FrameworkConnection.service.value === service)
            mutable.update { it.copy(saving = false, unavailable = true) }
          return@withLock
        }
        try {
          NavigationBackdropPreferences.save(service, choice)
          val confirmed = NavigationBackdropPreferences.read(service)
          check(confirmed == choice)
          if (FrameworkConnection.service.value === service)
            mutable.update { it.copy(choice = confirmed, saving = false, failed = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
          if (FrameworkConnection.service.value === service)
            mutable.update { it.copy(choice = null, saving = false, failed = true) }
        }
      }
    }
  }

  private fun loadApps(): List<NavigationBackdropApp> {
    val pm = context.packageManager
    val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
      ?.activityInfo?.packageName
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
      .asSequence()
      .filter { resolve ->
        val app = resolve.activityInfo?.applicationInfo ?: return@filter false
        app.uid % 100_000 >= 10_000 && app.packageName != home &&
          app.packageName != BuildConfig.APPLICATION_ID && app.packageName != "com.android.systemui"
      }
      .distinctBy { it.activityInfo.packageName }
      .mapNotNull { resolve ->
        val activity = resolve.activityInfo
        val app = activity.applicationInfo
        val info = try {
          pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) { return@mapNotNull null }
        val label = resolve.loadLabel(pm).toString()
        val system = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        NavigationBackdropApp(app.packageName, label, system, PreservedIconApp(app.packageName, label,
          info.longVersionCode, info.lastUpdateTime, app.sourceDir, activity.iconResource, activity.name))
      }.toList()
  }
}
