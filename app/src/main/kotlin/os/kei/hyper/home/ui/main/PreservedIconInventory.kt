package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.OriginalAppIcons

@Immutable
internal data class PreservedIconApp(
  val packageName: String,
  val label: String,
  val versionCode: Long,
  val updatedAt: Long,
  val sourcePath: String,
  val iconResource: Int,
  val activeLauncherClass: String? = null,
  val installedAt: Long = 0,
  val systemApp: Boolean = false,
)

/** Package resources only: never use the selected theme or its preview renderer. */
internal class PreservedIconInventory(context: Context) {
  private val appContext = context.applicationContext
  private val pm = appContext.packageManager
  private val iconWorkers = Semaphore(2)
  private val icons = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
  }

  @Suppress("DEPRECATION")
  suspend fun read(locale: Locale): List<PreservedIconApp> = withContext(Dispatchers.IO) {
    val collator = Collator.getInstance(locale)
    val launchers = pm.queryIntentActivities(
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
    ).mapNotNull { resolved ->
      currentCoroutineContext().ensureActive()
      val activity = resolved.activityInfo ?: return@mapNotNull null
      IconPackComponent.parse("${activity.packageName}/${activity.name}")
    }.groupBy { it.packageName }.mapValues { (_, components) ->
      // Duplicate filters are harmless; distinct visible launchers have no single selected artwork.
      components.mapNotNull { it.className }.distinct().singleOrNull()
    }
    pm.getInstalledPackages(0).mapNotNull { pkg ->
      currentCoroutineContext().ensureActive()
      val info = pkg.applicationInfo ?: return@mapNotNull null
      val label = try {
        info.loadLabel(pm).toString().trim().ifEmpty { pkg.packageName }
      } catch (_: RuntimeException) {
        pkg.packageName
      }
      PreservedIconApp(pkg.packageName, label, PackageInfoCompat.getLongVersionCode(pkg),
        pkg.lastUpdateTime, info.sourceDir.orEmpty(), info.icon, launchers[pkg.packageName], pkg.firstInstallTime,
        systemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
    }.sortedWith { a, b ->
      collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName)
    }
  }

  suspend fun icon(app: PreservedIconApp, configuration: String, size: Int, dark: Boolean): Bitmap? =
    withContext(Dispatchers.IO) {
      val key = "${app.packageName}:${app.versionCode}:${app.updatedAt}:${app.sourcePath}:" +
        "${app.iconResource}:${app.activeLauncherClass.orEmpty()}:$configuration:$size:$dark"
      icons.get(key)?.let { return@withContext it }
      iconWorkers.withPermit {
        icons.get(key)?.let { return@withPermit it }
        val job = currentCoroutineContext()
        job.ensureActive()
        try {
          val bitmap = OriginalAppIcons.render(appContext, IconPackComponent(app.packageName, app.activeLauncherClass),
            size, dark, check = { job.ensureActive() }) ?: return@withPermit null
          job.ensureActive()
          icons.put(key, bitmap)
          bitmap
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
          null
        } catch (_: RuntimeException) {
          null
        }
      }
    }
}
