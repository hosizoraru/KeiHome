package os.kei.hyper.home.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PackFileImportAction(model: HomeViewModel, section: String, onHeightChanged: (Int) -> Unit) {
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) model.importIconPack(uri)
  }
  PackFileImportButton(section, busy = state.importing || state.nativeImporting != null || state.removing != null,
    hasThemeScope = framework.snapshot?.themesInScope == true, onHeightChanged = onHeightChanged,
    // Keep broad MIME support: document providers may label APK/MTZ/ZIP inconsistently.
    onChooseFile = { picker.launch(arrayOf("*/*")) })
}

@Composable
internal fun PackFileImportButton(
  section: String,
  busy: Boolean,
  hasThemeScope: Boolean,
  onChooseFile: () -> Unit,
  onHeightChanged: (Int) -> Unit = {},
) {
  var mounted by remember(section) { mutableStateOf(false) }
  var showing by remember(section) { mutableStateOf(false) }
  var chooseAfterExit by remember(section) { mutableStateOf(false) }
  val latestBusy by rememberUpdatedState(busy)
  val chooseFile by rememberUpdatedState(onChooseFile)
  val apk = section == "installed"
  val title = stringResource(if (apk) R.string.pack_file_import_apk_title else R.string.pack_file_import_archive_title)
  PageActionFab(stringResource(if (busy) R.string.action_label_processing else R.string.pack_action_import_short),
    actionIcon(PageActionIcon.IMPORT), description = title,
    modifier = Modifier.testTag("packs.file-import.action"),
    enabled = !busy && !mounted, busy = busy, onHeightChanged = onHeightChanged,
    onClick = {
      if (!latestBusy && !mounted) {
        chooseAfterExit = false
        mounted = true
        showing = true
      }
    })
  if (mounted) Os4DetailsSheet(show = showing, title = title,
    modifier = Modifier.testTag("packs.file-import.sheet"),
    onDismissRequest = { showing = false },
    onDismissFinished = {
      if (mounted) {
        mounted = false
        val choose = chooseAfterExit
        chooseAfterExit = false
        // The system picker must not compete with the sheet's exit transition.
        if (choose && !latestBusy) chooseFile()
      }
    }) {
    ImportGuideStep("1", stringResource(R.string.icon_pack_choose_file),
      stringResource(if (apk) R.string.pack_file_import_apk_file else R.string.pack_file_import_archive_file))
    ImportGuideStep("2", stringResource(R.string.pack_file_import_preview_title),
      stringResource(R.string.pack_file_import_preview_body))
    ImportGuideStep("3", stringResource(R.string.pack_file_import_apply_title),
      stringResource(R.string.pack_file_import_apply_body))
    if (apk) Text(stringResource(R.string.icon_pack_apk_file_guidance),
      style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    if (!hasThemeScope) Text(stringResource(R.string.icon_pack_scope_needed),
      style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    Os4DialogButton(stringResource(R.string.icon_pack_choose_file),
      modifier = Modifier.fillMaxWidth().testTag("packs.file-import.choose"),
      role = GlassDialogButtonRole.Primary, enabled = showing && !busy,
      onClick = {
        if (showing && !latestBusy && !chooseAfterExit) {
          chooseAfterExit = true
          showing = false
        }
      })
  }
}

@Composable
private fun ImportGuideStep(number: String, title: String, body: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(number, Modifier.width(24.dp), style = MiuixTheme.textStyles.body1,
      color = MiuixTheme.colorScheme.primary)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MiuixTheme.textStyles.body1)
      Text(body, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}
