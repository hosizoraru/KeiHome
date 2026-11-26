package os.kei.hyper.home.ui.main

import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.status.AppLogStore
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4RefreshIconButton
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Info
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LogSettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  var level by remember { mutableStateOf(AppLogStore.level(context)) }
  var savingLevel by remember { mutableStateOf(false) }
  var rangeIndex by rememberSaveable { mutableIntStateOf(3) }
  val customHours = rememberTextFieldState("24")
  var refresh by remember { mutableIntStateOf(0) }
  var loadingStats by remember { mutableStateOf(false) }
  var stats by remember { mutableStateOf(0L to 0) }
  var confirmClear by remember { mutableStateOf(false) }
  var exportHours by remember { mutableStateOf<Long?>(null) }
  var exporting by remember { mutableStateOf(false) }
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionBottomPadding = maxOf(100.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  val hours = when (rangeIndex) { 0 -> 3L; 1 -> 6L; 2 -> 12L; 3 -> 24L
    else -> customHours.text.toString().toLongOrNull()?.takeIf { it in 1..168 } ?: 0L }
  LaunchedEffect(hours, refresh) {
    loadingStats = true
    try {
      stats = if (hours == 0L) 0L to 0 else withContext(Dispatchers.IO) {
        AppLogStore.stats(context, System.currentTimeMillis() - hours * 3_600_000)
      }
    } finally {
      loadingStats = false
    }
  }
  val labels = listOf(R.string.logs_three_hours, R.string.logs_six_hours, R.string.logs_twelve_hours,
    R.string.logs_one_day, R.string.logs_custom)
  val levelLabels = listOf(R.string.logs_level_verbose, R.string.logs_level_debug, R.string.logs_level_info,
    R.string.logs_level_warn, R.string.logs_level_error, R.string.logs_level_off)
  val backdrop = rememberKeiHomeBlurBackdrop()
  val behavior = MiuixScrollBehavior()
  Scaffold(backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.settings_log_section), expandable = false,
        scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back)) }
        }, actions = {
          Os4RefreshIconButton(busy = loadingStats, onClick = { refresh++ },
            contentDescription = stringResource(R.string.refresh))
        })
    }
  }, floatingActionButton = {
    PageActionFab(stringResource(if (exporting) R.string.action_label_processing
      else R.string.action_label_export), actionIcon(PageActionIcon.EXPORT),
      description = stringResource(R.string.logs_export),
      onHeightChanged = { actionHeight = it },
      modifier = Modifier.testTag("settings.logs.export"),
      enabled = hours > 0 && exportHours == null, busy = exporting, onClick = {
        if (!exporting && exportHours == null && hours > 0) exportHours = hours
      })
  }) { padding ->
    exportHours?.let { selectedHours ->
      LogExportConfirmationDialog(onDismissed = { exportHours = null }, onExport = {
        val since = System.currentTimeMillis() - selectedHours * 3_600_000
        exporting = true
        scope.launch {
          try {
            val file = withContext(Dispatchers.IO) { AppLogStore.export(context, since) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
              type = "application/zip"
              putExtra(Intent.EXTRA_STREAM, uri)
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, resources.getString(R.string.logs_export)))
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            Toast.makeText(context, R.string.logs_export_failed, Toast.LENGTH_SHORT).show()
          } finally {
            exporting = false
          }
        }
      })
    }
    val openFrameworkLogs = rememberFrameworkManagerAction(os.kei.hyper.home.status.FrameworkManagerProtocol.LOGS)
    Column(Modifier.fillMaxSize()
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
      .keiHomeTopBarScroll(behavior)
      .verticalScroll(rememberScrollState(), overscrollEffect = null)
      .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
        bottom = padding.calculateBottomPadding() + actionBottomPadding),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Card(Modifier.fillMaxWidth().os4Clickable(
        cornerRadius = CardDefaults.CornerRadius,
        onClickLabel = stringResource(R.string.logs_source), onClick = openFrameworkLogs),
        insideMargin = PaddingValues(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(MiuixIcons.Glass.Info, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.logs_function_title), modifier = Modifier.semantics { heading() },
              style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
            Text(stringResource(R.string.logs_scope), style = MiuixTheme.textStyles.body2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Text(stringResource(R.string.logs_source), style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
              color = MiuixTheme.colorScheme.primary)
          }
          Os4TrailingChevron(tint = MiuixTheme.colorScheme.primary)
        }
      }
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(R.string.logs_app_title), modifier = Modifier.semantics { heading() },
            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
          Text(stringResource(R.string.logs_app_note), style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        OverlaySpinnerPreference(title = stringResource(R.string.logs_level),
          items = levelLabels.map { DropdownItem(text = stringResource(it)) },
          selectedIndex = level.ordinal, onSelectedIndexChange = { index ->
            if (!savingLevel) {
              savingLevel = true
              scope.launch {
                try {
                  withContext(Dispatchers.IO) { AppLogStore.setLevel(context, AppLogStore.Level.entries[index]) }
                  level = AppLogStore.Level.entries[index]
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                  Toast.makeText(context, R.string.theme_effects_save_failed, Toast.LENGTH_SHORT).show()
                } finally { savingLevel = false }
              }
            }
          })
        OverlaySpinnerPreference(title = stringResource(R.string.logs_window),
          items = labels.map { DropdownItem(text = stringResource(it)) }, selectedIndex = rangeIndex,
          onSelectedIndexChange = { rangeIndex = it })
      }
      Os4AnimatedVisibility(visible = rangeIndex == 4, label = "logs-custom-hours") {
        GlassTextField(state = customHours, inputTransformation = InputTransformation {
          if (length > 3 || !asCharSequence().all(Char::isDigit)) revertAllChanges()
        }, label = stringResource(R.string.logs_hours_custom), lineLimits = TextFieldLineLimits.SingleLine,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth())
      }
      Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(title = stringResource(R.string.logs_size),
          summary = stringResource(R.string.logs_lines, stats.second), endActions = {
            Text(Formatter.formatShortFileSize(context, stats.first))
          })
        BasicComponent(title = stringResource(R.string.logs_clear), enabled = !exporting,
          holdDownState = confirmClear, onClick = {
          if (hours == 0L) Toast.makeText(context, R.string.logs_invalid_range, Toast.LENGTH_SHORT).show()
          else confirmClear = true
        })
      }
    }
    Os4ActionDialog(show = confirmClear, title = stringResource(R.string.logs_clear),
      summary = stringResource(R.string.logs_clear_confirm), icon = actionIcon(PageActionIcon.CLEAR),
      onDismissRequest = { confirmClear = false }, actions = listOf(
        Os4DialogAction(stringResource(R.string.logs_cancel), onClick = { confirmClear = false }),
        Os4DialogAction(stringResource(R.string.logs_confirm), role = GlassDialogButtonRole.Destructive,
          enabled = !exporting, onClick = {
            confirmClear = false
            scope.launch {
              val removed = withContext(Dispatchers.IO) {
                AppLogStore.clear(context, System.currentTimeMillis() - hours * 3_600_000)
              }
              refresh++
              Toast.makeText(context, resources.getString(R.string.logs_cleared, removed), Toast.LENGTH_SHORT).show()
            }
          })))
  }
}
