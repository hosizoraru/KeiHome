package os.kei.hyper.home.ui.main

import android.text.format.DateFormat
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Date
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RefreshFeedback(model: HomeViewModel, page: Int) {
  val result by remember(model, page) { model.refreshResults.map { it[page] }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = null)
  val value = result ?: return
  val context = LocalContext.current
  val time = remember(value.completedAt, context) { DateFormat.getTimeFormat(context).format(Date(value.completedAt)) }
  val message = if (value.failed) R.string.refresh_partial else when (page) {
    0 -> R.string.refresh_status_completed
    1 -> R.string.refresh_apps_completed
    else -> R.string.refresh_monet_completed
  }
  Text(stringResource(message, time),
    modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    style = MiuixTheme.textStyles.footnote2,
    color = if (value.failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceSecondary)
}
