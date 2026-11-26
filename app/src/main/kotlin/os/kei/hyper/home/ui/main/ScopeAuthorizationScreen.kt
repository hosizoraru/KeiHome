package os.kei.hyper.home.ui.main

import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4RefreshIconButton
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import top.yukonga.miuix.kmp.glass.glassPress

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.composables.icons.lucide.R as LucideR
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.status.StaticScopeUiState
import os.kei.hyper.home.status.readStaticScopeDeclaration
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ScopeAuthorizationScreen(model: HomeViewModel, onBack: () -> Unit) {
  val state by model.scopeRequests.state.collectAsStateWithLifecycle()
  val refreshing by model.refreshing.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current
  var refreshKey by remember { mutableIntStateOf(0) }
  var declared by remember { mutableStateOf(emptyList<String>()) }
  var versions by remember { mutableStateOf(emptyMap<String, ScopeTargetVersion>()) }
  var reading by remember { mutableStateOf(true) }
  var metadataFailed by remember { mutableStateOf(false) }
  LaunchedEffect(model, lifecycle) {
    lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeScopeStatus() }
  }
  LaunchedEffect(context, lifecycle, refreshKey) {
    lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      reading = true
      metadataFailed = false
      try {
        val names = withContext(Dispatchers.IO) { readStaticScopeDeclaration(context) }
        val current = readScopeTargetVersions(context, names)
        declared = names
        versions = current
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        metadataFailed = true
      } finally {
        reading = false
      }
      awaitCancellation()
    }
  }
  ScopeAuthorizationPage(state, state.plan?.declared ?: declared, versions,
    reading || 4 in refreshing, metadataFailed, onBack, onRefresh = {
      refreshKey++
      model.refresh(4)
    })
}

@Composable
internal fun ScopeAuthorizationPage(
  state: StaticScopeUiState,
  declared: List<String>,
  versions: Map<String, ScopeTargetVersion>,
  reading: Boolean,
  metadataFailed: Boolean,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onOpenManager: (() -> Unit)? = null,
) {
  val scroll = rememberScrollState()
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
    TopAppBar(stringResource(R.string.settings_hook_section), navigationIcon = {
      IconButton(onClick = onBack) { Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back)) }
    }, actions = {
      Os4RefreshIconButton(
        busy = reading || state.requesting,
        onClick = onRefresh,
        contentDescription = stringResource(R.string.refresh),
        modifier = Modifier.testTag("scope.page.refresh"),
      )
    }, scrollBehavior = behavior,
      color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface)
    }
  }) { padding ->
    val managerAction = onOpenManager ?: rememberFrameworkManagerAction()
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
    Column(Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
      .verticalScroll(scroll, overscrollEffect = null).padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp,
        bottom = padding.calculateBottomPadding() + 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)) {
      ScopeAuthorizationSummary(state, declared, reading, metadataFailed)
      val primary = declared.filter { it == "system" || it == "com.miui.home" || it == "com.android.thememanager" }
      val surfaces = declared.filterNot { it in primary }
      ScopeTargetGroup(R.string.scope_page_primary, primary, state, versions, reading)
      ScopeTargetGroup(R.string.scope_page_surfaces, surfaces, state, versions, reading)
      Card(modifier = Modifier.fillMaxWidth().testTag("scope.page.manager")) {
        val managerPress = rememberOs4PressSource()
        BasicComponent(title = stringResource(R.string.scope_open_manager),
          summary = stringResource(R.string.scope_page_manager_summary),
          interactionSource = managerPress,
          modifier = Modifier.glassPress(managerPress),
          startAction = { Icon(ImageVector.vectorResource(LucideR.drawable.lucide_ic_shield), null,
            Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary) },
          endActions = { Os4TrailingChevron() },
          onClick = managerAction)
      }
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.scope_page_loading_title), modifier = Modifier.semantics { heading() },
            style = MiuixTheme.textStyles.subtitle)
          Text(stringResource(R.string.scope_runtime_note), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    }
    }
  }
}

internal fun scopeStatusPresentation(state: StaticScopeUiState): ModuleStatusPresentation {
  val plan = state.plan
  val pending = !state.readFailed && (state.checking || (state.connected && plan == null))
  val tone = when {
    pending -> StatusTone.CHECKING
    !state.connected || state.readFailed || plan?.unsupported?.isNotEmpty() == true -> StatusTone.ERROR
    plan?.complete == true -> StatusTone.READY
    else -> StatusTone.WARNING
  }
  val title = when {
    pending -> R.string.runtime_checking
    !state.connected -> R.string.scope_page_disconnected
    state.readFailed -> R.string.scope_page_read_failed
    plan == null -> R.string.scope_page_unconfirmed
    plan.unsupported.isNotEmpty() -> R.string.scope_page_unsupported
    plan.complete -> R.string.scope_page_ready
    else -> R.string.scope_page_incomplete
  }
  return ModuleStatusPresentation(title, tone)
}

@Composable
private fun ScopeAuthorizationSummary(
  state: StaticScopeUiState, declared: List<String>, reading: Boolean, metadataFailed: Boolean,
) {
  val plan = state.plan
  val trusted = state.connected && !state.readFailed && plan != null
  val (title, tone) = scopeStatusPresentation(state)
  val color = desktopStatusColor(tone)
  Card(Modifier.fillMaxWidth().os4ContentSize().testTag("scope.page.summary")) {
    Box(Modifier.fillMaxWidth().clipToBounds()) {
      StatusOverviewCornerSymbol(tone, color)
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(title), style = MiuixTheme.textStyles.title4,
            modifier = Modifier.semantics { heading() })
          FlowRow(Modifier.padding(end = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (trusted && plan != null) {
              MetadataPill(stringResource(R.string.scope_page_granted_count,
                plan.requiredGranted, plan.requiredEligible.size), color)
              if (plan.missing.isNotEmpty()) MetadataPill(stringResource(R.string.scope_page_missing_count,
                plan.missing.size), desktopStatusColor(StatusTone.WARNING))
              if (plan.absent.isNotEmpty()) MetadataPill(stringResource(R.string.scope_page_absent_count,
                plan.absent.size), MiuixTheme.colorScheme.onSurfaceSecondary)
              if (plan.requiredUnconfirmed.isNotEmpty()) MetadataPill(stringResource(R.string.scope_page_unknown_count,
                plan.requiredUnconfirmed.size), desktopStatusColor(StatusTone.WARNING))
            } else MetadataPill(stringResource(R.string.scope_page_unknown_count, declared.size), color)
          }
        }
        Os4AnimatedVisibility(metadataFailed) {
          Text(stringResource(R.string.scope_page_metadata_failed),
            modifier = Modifier.padding(end = 60.dp),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
        Os4AnimatedVisibility(reading || state.requesting || tone == StatusTone.CHECKING) {
          LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 60.dp))
        }
      }
    }
  }
}

@Composable
private fun ScopeTargetGroup(
  title: Int, names: List<String>, state: StaticScopeUiState,
  versions: Map<String, ScopeTargetVersion>, reading: Boolean,
) {
  if (names.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SmallTitle(stringResource(title), insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp))
    Card(Modifier.fillMaxWidth()) {
      Column {
        names.forEachIndexed { index, name ->
          if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
          ScopeTargetRow(name, state, versions[name], reading)
        }
      }
    }
  }
}

@Composable
private fun ScopeTargetRow(name: String, state: StaticScopeUiState, version: ScopeTargetVersion?, reading: Boolean) {
  val plan = state.plan
  val trusted = state.connected && !state.readFailed && plan != null
  val status = when {
    !trusted -> R.string.scope_unknown
    name in plan!!.unsupported -> R.string.scope_not_supported
    name in plan.granted -> R.string.scope_granted
    name in plan.absent -> R.string.scope_absent
    name in plan.unconfirmed -> R.string.scope_unknown
    else -> R.string.scope_not_granted
  }
  val color = when (status) {
    R.string.scope_granted -> desktopStatusColor(StatusTone.READY)
    R.string.scope_not_granted, R.string.scope_unknown -> desktopStatusColor(StatusTone.WARNING)
    else -> MiuixTheme.colorScheme.onSurfaceSecondary
  }
  BasicComponent(title = scopeTargetLabel(name), summary = stringResource(scopeTargetPurpose(name)),
    modifier = Modifier.testTag("scope.target.$name"), endActions = {
      MetadataPill(stringResource(status), color)
    }, bottomAction = {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        val label = version?.versionName?.let {
          if (name == "system") stringResource(R.string.scope_page_android_version, it) else it
        } ?: stringResource(if (reading) R.string.scope_page_version_loading else R.string.scope_page_version_unknown)
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary,
          modifier = Modifier.testTag("scope.version.$name"))
        version?.versionCode?.let {
          MetadataPill(stringResource(if (name == "system") R.string.scope_page_api else R.string.scope_page_build,
            it.toString()), MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    })
}

private fun scopeTargetPurpose(name: String): Int = when (name) {
  "system" -> R.string.scope_page_purpose_system
  "com.miui.home" -> R.string.scope_page_purpose_launcher
  "com.android.thememanager" -> R.string.scope_page_purpose_themes
  "com.android.systemui" -> R.string.scope_page_purpose_systemui
  "com.android.intentresolver" -> R.string.scope_page_purpose_share
  "com.miui.securitycenter" -> R.string.scope_page_purpose_security
  "com.android.settings" -> R.string.scope_page_purpose_settings
  "tv.danmaku.bili" -> R.string.scope_page_purpose_bilibili
  else -> R.string.scope_page_purpose_other
}
