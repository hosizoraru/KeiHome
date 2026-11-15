package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
  val hiddenByDefault: Boolean = false,
)

internal data class IconInventoryVisibility(
  val showHidden: Boolean = false,
  val retainedPackages: Set<String> = emptySet(),
) {
  fun includes(packageName: String, hidden: Boolean) =
    showHidden || !hidden || packageName in retainedPackages
}

internal fun hiddenIconInventoryPackage(
  packageName: String, hasLauncher: Boolean, iconResource: Int, overlay: Boolean,
): Boolean = overlay || isDevelopmentPackage(packageName) ||
  (!hasLauncher && (iconResource == 0 || iconResource == android.R.drawable.sym_def_app_icon))

internal interface PreservedIconInventorySource {
  suspend fun readAll(visibility: IconInventoryVisibility): List<PreservedIconApp>
  suspend fun readPackage(packageName: String, visibility: IconInventoryVisibility): PreservedIconApp?
}

private class AndroidPreservedIconInventorySource(context: Context) : PreservedIconInventorySource {
  private val pm = context.packageManager

  @Suppress("DEPRECATION")
  private suspend fun launchers(packageName: String? = null): Map<String, String?> =
    pm.queryIntentActivities(
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName), 0,
    ).mapNotNull { resolved ->
      currentCoroutineContext().ensureActive()
      val activity = resolved.activityInfo ?: return@mapNotNull null
      IconPackComponent.parse("${activity.packageName}/${activity.name}")
    }.groupBy { it.packageName }.mapValues { (_, components) ->
      components.mapNotNull { it.className }.distinct().singleOrNull()
    }

  private fun app(pkg: PackageInfo, launcher: String?, hasLauncher: Boolean,
    visibility: IconInventoryVisibility): PreservedIconApp? {
    val info = pkg.applicationInfo ?: return null
    val hidden = hiddenIconInventoryPackage(pkg.packageName, hasLauncher, info.icon, false) ||
      os.kei.hyper.home.data.isResourceOverlay(pm, info)
    // Skip labels and all preview work for irrelevant components, before creating a row.
    if (!visibility.includes(pkg.packageName, hidden)) return null
    val label = try {
      info.loadLabel(pm).toString().trim().ifEmpty { pkg.packageName }
    } catch (_: RuntimeException) {
      pkg.packageName
    }
    return PreservedIconApp(pkg.packageName, label, PackageInfoCompat.getLongVersionCode(pkg),
      pkg.lastUpdateTime, info.sourceDir.orEmpty(), info.icon, launcher, pkg.firstInstallTime,
      systemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0, hiddenByDefault = hidden)
  }

  @Suppress("DEPRECATION")
  override suspend fun readAll(visibility: IconInventoryVisibility): List<PreservedIconApp> {
    val launchers = launchers()
    return pm.getInstalledPackages(0).mapNotNull { pkg ->
      currentCoroutineContext().ensureActive()
      app(pkg, launchers[pkg.packageName], pkg.packageName in launchers, visibility)
    }
  }

  @Suppress("DEPRECATION")
  override suspend fun readPackage(packageName: String, visibility: IconInventoryVisibility): PreservedIconApp? {
    val pkg = try { pm.getPackageInfo(packageName, 0) }
    catch (_: PackageManager.NameNotFoundException) { return null }
    currentCoroutineContext().ensureActive()
    checkNotNull(pkg.applicationInfo) { "Missing package metadata" }
    val launchers = launchers(packageName)
    return app(pkg, launchers[packageName], packageName in launchers, visibility)
  }
}

/** Package resources only: never use the selected theme or its preview renderer. */
internal class PreservedIconInventory(
  context: Context,
  private val source: PreservedIconInventorySource = AndroidPreservedIconInventorySource(context.applicationContext),
) {
  private val appContext = context.applicationContext
  private val inventoryLock = Mutex()
  private var previous: List<PreservedIconApp>? = null
  private var previousConfiguration: Configuration? = null
  private var previousLocale: Locale? = null
  private var previousVisibility: IconInventoryVisibility? = null
  private val iconWorkers = Semaphore(2)
  private val icons = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
  }

  /** Null requests an authoritative read; package events update only their named rows. */
  suspend fun read(locale: Locale, packages: Set<String>? = null,
    visibility: IconInventoryVisibility = IconInventoryVisibility()): List<PreservedIconApp> =
    withContext(Dispatchers.IO) {
      inventoryLock.withLock {
        val retainedChanges = previousVisibility?.retainedPackages.orEmpty().let { old ->
          (old - visibility.retainedPackages) + (visibility.retainedPackages - old)
        }
        val targets = packages?.toSet()?.plus(retainedChanges)
        val configuration = Configuration(appContext.resources.configuration)
        val old = previous
        val full = targets == null || old == null || previousLocale != locale ||
          previousConfiguration != configuration || previousVisibility?.showHidden != visibility.showHidden
        if (!full && targets.isNullOrEmpty()) return@withLock old.orEmpty()
        val replacements = (if (full) source.readAll(visibility) else targets.orEmpty().mapNotNull {
          currentCoroutineContext().ensureActive()
          source.readPackage(it, visibility)?.also { app -> require(app.packageName == it) }
        }).filter { visibility.includes(it.packageName, it.hiddenByDefault) }
        val candidates = if (full) replacements
          else old.orEmpty().filterNot { it.packageName in targets.orEmpty() } + replacements
        val oldByName = old.orEmpty().associateBy { it.packageName }
        val collator = Collator.getInstance(locale)
        val merged = candidates.map { app ->
          currentCoroutineContext().ensureActive()
          oldByName[app.packageName]?.takeIf { it == app } ?: app
        }.sortedWith { a, b ->
          collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName)
        }
        currentCoroutineContext().ensureActive()
        val result = if (old != null && old.size == merged.size && old.indices.all { old[it] === merged[it] })
          old else merged
        previous = result
        previousConfiguration = configuration
        previousLocale = locale
        previousVisibility = visibility
        result
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
