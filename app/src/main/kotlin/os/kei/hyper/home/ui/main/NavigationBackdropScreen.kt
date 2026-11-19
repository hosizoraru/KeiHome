package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.status.BilibiliArtworkInbox
import os.kei.hyper.home.status.BilibiliInstallation
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.NavigationBackdropInbox

@Composable
internal fun NavigationBackdropScreen(
  model: HomeViewModel,
  onBack: () -> Unit,
  page: NavigationBackdropPage = NavigationBackdropPage.OVERVIEW,
  onOpen: (NavigationBackdropPage) -> Unit = {},
) {
  val state by model.navigationBackdrop.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val service by FrameworkConnection.service.collectAsStateWithLifecycle()
  val backend by NavigationBackdropInbox.status.collectAsStateWithLifecycle()
  val packageRevision by model.packageInventoryRevision.collectAsStateWithLifecycle()
  val context = LocalContext.current.applicationContext
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val scope = rememberCoroutineScope()
  val openManager = rememberFrameworkManagerAction()
  val includeApps = page == NavigationBackdropPage.APPS
  val installation = if (!includeApps) {
    produceState<BilibiliInstallation?>(null, context, lifecycle, packageRevision) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        value = withContext(Dispatchers.IO) { BilibiliInstallation.read(context) }
      }
    }.value
  } else null
  val artwork = if (!includeApps) BilibiliArtworkInbox.status.collectAsStateWithLifecycle().value else null
  LaunchedEffect(model, lifecycle, service, framework.connected, framework.readFailed,
    framework.snapshot?.systemInScope, framework.snapshot?.grantedScope, includeApps, packageRevision) {
    // The framework can open over this activity without stopping it.
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      model.navigationBackdrop.read(includeApps = includeApps, revision = packageRevision)
    }
  }
  if (includeApps) LaunchedEffect(model, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeCurrentIconAppearance() }
  }
  val appIcon: @Composable (InstalledIconCapability?) -> Unit = if (includeApps) {
    val source by model.browserSource.collectAsStateWithLifecycle()
    val monet by model.monet.collectAsStateWithLifecycle()
    val snapshot = monet.snapshot
    val colors = remember(snapshot) { previewColors(snapshot) }
    val monetEnabled = snapshot?.monochrome?.enabled == true
    val render: @Composable (InstalledIconCapability?) -> Unit =
      { app -> NavigationBackdropAppIcon(model, app, source, monetEnabled, colors) }
    render
  } else { _ -> }
  NavigationBackdropContent(page, state, backend, onBack, onOpen,
    onEnabled = model.navigationBackdrop::enabled, onToggle = model.navigationBackdrop::toggle,
    onRefresh = { scope.launch {
      model.navigationBackdrop.read(includeApps = includeApps, refreshApps = includeApps, revision = packageRevision)
    } },
    onBilibiliArtwork = model.navigationBackdrop::bilibiliArtwork, onOpenManager = openManager,
    bilibili = installation, artworkReceipt = artwork,
    appIcon = appIcon)
}

@Composable
private fun NavigationBackdropAppIcon(model: HomeViewModel, app: InstalledIconCapability?,
  source: IconBrowserSource?, monetEnabled: Boolean, colors: IconPreviewColors?) {
  val configuration = LocalConfiguration.current
  val previews by key(app, configuration, source?.revision) {
    produceState<AppIconPreviews?>(null) {
      if (app != null) value = browserPreview(model, currentIconBrowserRow(app, source), source)
    }
  }
  CurrentAppearanceIcon(previews, monetEnabled, colors, Modifier.size(44.dp))
}
