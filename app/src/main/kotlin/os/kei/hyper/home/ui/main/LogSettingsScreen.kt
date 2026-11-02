package os.kei.hyper.home.ui.main

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.status.AppLogStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference

@Composable
internal fun LogSettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var level by remember { mutableStateOf(AppLogStore.level(context)) }
  var rangeIndex by rememberSaveable { mutableIntStateOf(3) }
  var customHours by rememberSaveable { mutableStateOf("24") }
  var refresh by remember { mutableIntStateOf(0) }
  var stats by remember { mutableStateOf(0L to 0) }
  var confirmClear by remember { mutableStateOf(false) }
  val hours = when (rangeIndex) { 0 -> 3L; 1 -> 6L; 2 -> 12L; 3 -> 24L
    else -> customHours.toLongOrNull()?.takeIf { it in 1..168 } ?: 0L }
  LaunchedEffect(hours, refresh) {
    stats = if (hours == 0L) 0L to 0 else withContext(Dispatchers.IO) {
      AppLogStore.stats(context, System.currentTimeMillis() - hours * 3_600_000)
    }
  }
  val labels = listOf(R.string.logs_three_hours, R.string.logs_six_hours, R.string.logs_twelve_hours,
    R.string.logs_one_day, R.string.logs_custom)
  val levelLabels = listOf(R.string.logs_level_verbose, R.string.logs_level_debug, R.string.logs_level_info,
    R.string.logs_level_warn, R.string.logs_level_error, R.string.logs_level_off)
  Scaffold(topBar = {
    TopAppBar(title = stringResource(R.string.settings_log_section), navigationIcon = {
      IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.pack_back)) }
    }, actions = {
      IconButton(onClick = { refresh++ }) { Icon(MiuixIcons.Refresh, stringResource(R.string.refresh)) }
    })
  }) { padding ->
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
      .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
        bottom = padding.calculateBottomPadding() + 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(title = stringResource(R.string.logs_source),
          summary = stringResource(R.string.logs_scope), onClick = {
            runCatching {
              // LSPosed Manager's installed "logs" launcher shortcut uses this exact data URI.
              context.startActivity(Intent(Intent.ACTION_MAIN).setClassName("org.lsposed.manager",
                "org.lsposed.manager.ui.activity.MainActivity").setData(Uri.parse("logs")))
            }.onFailure {
              Toast.makeText(context, R.string.logs_lsposed_unavailable, Toast.LENGTH_SHORT).show()
            }
          }, endActions = { Icon(MiuixIcons.Basic.ArrowRight, null) })
      }
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        OverlaySpinnerPreference(title = stringResource(R.string.logs_level),
          items = levelLabels.map { DropdownItem(text = stringResource(it)) },
          selectedIndex = level.ordinal, onSelectedIndexChange = { index ->
            level = AppLogStore.Level.entries[index]
            AppLogStore.setLevel(context, level)
          })
        OverlaySpinnerPreference(title = stringResource(R.string.logs_window),
          items = labels.map { DropdownItem(text = stringResource(it)) }, selectedIndex = rangeIndex,
          onSelectedIndexChange = { rangeIndex = it })
      }
      if (rangeIndex == 4) Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(8.dp)) {
        BasicTextField(value = customHours, onValueChange = { input ->
          if (input.length <= 3 && input.all(Char::isDigit)) customHours = input
        }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          decorationBox = { inner ->
            Column {
              Text(stringResource(R.string.logs_hours_custom), style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
              inner()
            }
          })
      }
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(title = stringResource(R.string.logs_size),
          summary = stringResource(R.string.logs_lines, stats.second), endActions = {
            Text(Formatter.formatShortFileSize(context, stats.first))
          })
        BasicComponent(title = stringResource(R.string.logs_export), onClick = {
          if (hours == 0L) {
            Toast.makeText(context, R.string.logs_invalid_range, Toast.LENGTH_SHORT).show()
            return@BasicComponent
          }
          scope.launch {
            val since = System.currentTimeMillis() - hours * 3_600_000
            val file = withContext(Dispatchers.IO) { runCatching { AppLogStore.export(context, since) }.getOrNull() }
            if (file == null) Toast.makeText(context, R.string.logs_export_failed, Toast.LENGTH_SHORT).show()
            else {
              val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
              context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }, context.getString(R.string.logs_export)))
            }
          }
        })
        BasicComponent(title = stringResource(R.string.logs_clear), onClick = {
          if (hours == 0L) Toast.makeText(context, R.string.logs_invalid_range, Toast.LENGTH_SHORT).show()
          else confirmClear = true
        })
      }
    }
  }
  if (confirmClear) Dialog(onDismissRequest = { confirmClear = false }) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      Text(stringResource(R.string.logs_clear_confirm), modifier = Modifier.padding(16.dp),
        style = MiuixTheme.textStyles.title3)
      BasicComponent(title = stringResource(R.string.logs_cancel), onClick = { confirmClear = false })
      BasicComponent(title = stringResource(R.string.logs_confirm), onClick = {
        confirmClear = false
        scope.launch {
          val removed = withContext(Dispatchers.IO) {
            AppLogStore.clear(context, System.currentTimeMillis() - hours * 3_600_000)
          }
          refresh++
          Toast.makeText(context, context.getString(R.string.logs_cleared, removed), Toast.LENGTH_SHORT).show()
        }
      })
    }
  }
}
