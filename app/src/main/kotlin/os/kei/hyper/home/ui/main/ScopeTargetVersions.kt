package os.kei.hyper.home.ui.main

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ScopeTargetVersion(
  val versionName: String?,
  val versionCode: Long?,
)

/**
 * Reads fresh display metadata only; the scope plan remains the installation/authorization authority.
 * No caching: OEM version names and APKs can change without a version code change.
 */
internal suspend fun readScopeTargetVersions(
  context: Context,
  declared: List<String>,
): Map<String, ScopeTargetVersion> = withContext(Dispatchers.IO) {
  buildMap {
    for (packageName in declared.distinct()) {
      ensureActive()
      val version = try {
        if (packageName == "system") {
          ScopeTargetVersion(
            versionName = Build.VERSION.RELEASE,
            versionCode = Build.VERSION.SDK_INT.toLong(),
          )
        } else {
          val info = context.packageManager.getPackageInfo(packageName, 0)
          ScopeTargetVersion(
            versionName = info.versionName?.trim()?.takeIf(String::isNotEmpty),
            versionCode = info.longVersionCode,
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        ScopeTargetVersion(versionName = null, versionCode = null)
      }
      put(packageName, version)
    }
  }
}
