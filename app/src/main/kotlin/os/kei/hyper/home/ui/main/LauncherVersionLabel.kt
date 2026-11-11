package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

private val launcherBuildPattern = Regex("(?<![0-9])[0-9]{3,5}(?![0-9])")
private val shortLauncherVersionPattern = Regex("[0-9]{1,2}(?:\\.[0-9]{1,2}){1,3}")

/** Uses FrameworkHero's last 3..5 digit build token, with a bounded version-only fallback. */
internal fun compactLauncherVersion(versionName: String?): String? {
  val version = versionName?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val build = launcherBuildPattern.findAll(version).lastOrNull()?.value
    ?: version.takeIf { it.matches(shortLauncherVersionPattern) }
    ?: return null
  return "Home $build"
}

/** Page entry/resume and completed manual refreshes are the only PackageManager triggers. */
@Composable
internal fun rememberLauncherVersionLabel(refreshKey: Long?): State<String?> {
  val context = LocalContext.current.applicationContext
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  return produceState<String?>(null, context, lifecycle, refreshKey) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      value = withContext(Dispatchers.IO) {
        val version = try {
          context.packageManager.getPackageInfo("com.miui.home", 0).versionName
        } catch (_: Exception) {
          null
        }
        compactLauncherVersion(version)
      }
      awaitCancellation()
    }
  }
}
