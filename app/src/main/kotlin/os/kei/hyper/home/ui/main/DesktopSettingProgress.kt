package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator

internal const val DESKTOP_OPERATION_TIMEOUT_MS = 30_000L

@Composable
internal fun rememberDesktopOperationStatus(
  status: DesktopFeatureStatus,
  choiceKey: Any?,
  refreshKey: Any? = null,
): DesktopFeatureStatus {
  var timedOut by remember(status, choiceKey, refreshKey) { mutableStateOf(false) }
  LaunchedEffect(status, choiceKey, refreshKey) {
    if (status == DesktopFeatureStatus.PENDING) {
      delay(DESKTOP_OPERATION_TIMEOUT_MS)
      timedOut = true
    }
  }
  return if (timedOut && status == DesktopFeatureStatus.PENDING) DesktopFeatureStatus.TIMED_OUT else status
}

internal fun desktopSettingProgressVisible(status: DesktopFeatureStatus): Boolean =
  status == DesktopFeatureStatus.SAVING || status == DesktopFeatureStatus.PENDING

@Composable
internal fun DesktopSettingProgress(
  status: DesktopFeatureStatus,
  modifier: Modifier = Modifier,
  tag: String? = null,
) {
  if (desktopSettingProgressVisible(status)) {
    LinearProgressIndicator(modifier = modifier
      .then(if (tag != null) Modifier.testTag(tag) else Modifier)
      .fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
  }
}
