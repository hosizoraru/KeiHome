package os.kei.hyper.home.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackPreviews
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.NativeIconImportReport
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun IconPacksPage(model: HomeViewModel, listState: LazyListState, padding: PaddingValues, modifier: Modifier) {
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val activeId = runtime.report?.takeIf { it.isFresh(android.os.SystemClock.elapsedRealtime()) &&
    !runtime.readFailed && !runtime.timedOut }?.iconTheme?.localId
  val importReady = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == os.kei.hyper.home.BuildConfig.VERSION_CODE.toLong() &&
    !runtime.readFailed && !runtime.timedOut
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) model.importIconPack(uri)
  }
  LazyColumn(state = listState, modifier = modifier, contentPadding = padding,
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item(key = "native", contentType = "native") { SystemIconThemeCard(runtime) }
    item(key = "import", contentType = "import") {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.icon_pack_import_title), style = MiuixTheme.textStyles.body1)
          Text(stringResource(R.string.icon_pack_import_summary), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
          if (framework.snapshot?.themesInScope != true) Text(stringResource(R.string.icon_pack_scope_needed),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
          TextButton(stringResource(if (state.importing) R.string.icon_pack_importing else R.string.icon_pack_choose_file),
            onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.importing)
          state.importName?.let { name ->
            Text(stringResource(R.string.icon_pack_imported_name, name), style = MiuixTheme.textStyles.footnote1)
          }
        }
      }
    }
    item(key = "summary", contentType = "summary") {
      Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.icon_pack_library), Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
          if (state.loading) CircularProgressIndicator(Modifier.size(18.dp))
        }
        if (state.scanned && state.packs.isEmpty()) Text(stringResource(R.string.icon_pack_empty),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (state.failedSources > 0) Text(stringResource(R.string.icon_pack_partial, state.failedSources),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        state.error?.let { Text(stringResource(R.string.icon_pack_read_failed, it),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
        RefreshFeedback(model, 3)
      }
    }
    items(state.packs, key = { it.pack.id }, contentType = { "pack" }) { card ->
      IconPackCard(card, model.packPreviews, state.nativeAssociations[card.pack.id],
        activeId, state.nativeImporting == card.pack.id,
        importReady && state.nativeImporting == null,
        state.nativeResult?.takeIf { it.sourceId == card.pack.id },
        onImport = { model.importNativeIconPack(card.pack) }, onRecheck = model::recheckNativeIconImport)
    }
  }
}

@Composable
private fun IconPackCard(card: IconPackCardData, previews: IconPackPreviewRepository,
  nativeId: String?, activeId: String?, importing: Boolean, enabled: Boolean,
  result: NativeIconImportReport?, onImport: () -> Unit, onRecheck: () -> Unit) {
  val context = LocalContext.current
  var openFailed by rememberSaveable(card.pack.id) { mutableStateOf(false) }
  var expanded by rememberSaveable(card.pack.id) { mutableStateOf(false) }
  val pack = card.pack
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(pack.displayName, style = MiuixTheme.textStyles.title4)
      Text(stringResource(if (pack.source is IconPackSource.Installed) R.string.icon_pack_installed else R.string.icon_pack_imported),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
      Text(stringResource(R.string.icon_pack_coverage, pack.entries.size, card.matchedApps),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (nativeId != null && nativeId == activeId) Text(stringResource(R.string.icon_pack_current),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
      if (card.previewEntries.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          card.previewEntries.forEach { entry ->
            PackIconPair(pack, entry, previews, Modifier.weight(1f))
          }
        }
      }
      TextButton(stringResource(when {
        importing -> R.string.icon_pack_importing
        nativeId != null -> R.string.icon_pack_native_open
        else -> R.string.icon_pack_native_import
      }), onClick = {
        if (nativeId == null) onImport() else {
          openFailed = runCatching { context.startActivity(IconPackImportProtocol.nativeDetail(nativeId)) }.isFailure
        }
      }, enabled = nativeId != null || (enabled && (card.matchedApps > 0 ||
        pack.format == os.kei.hyper.home.iconpack.IconPackFormat.MIUI_MTZ ||
        pack.format == os.kei.hyper.home.iconpack.IconPackFormat.MIUI_ICONS)))
      if (result != null && !result.imported) {
        Text(stringResource(when (result.status) {
          "UNCERTAIN" -> R.string.icon_pack_native_uncertain
          "UNSUPPORTED" -> R.string.icon_pack_native_unsupported
          else -> R.string.icon_pack_native_failed
        }), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
        if (result.status == "UNCERTAIN") TextButton(stringResource(R.string.icon_pack_recheck), onClick = onRecheck)
      }
      if (result?.imported == true && result.convertedReady == false) {
        Text(stringResource(R.string.icon_pack_native_eligibility_failed), style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.error)
      }
      if (openFailed) Text(stringResource(R.string.icon_pack_native_failed),
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
      TextButton(stringResource(if (expanded) R.string.icon_pack_hide_details else R.string.icon_pack_details),
        onClick = { expanded = !expanded })
      if (expanded) {
        if (nativeId != null) TextButton(stringResource(R.string.icon_pack_native_update),
          onClick = onImport, enabled = enabled)
        Text(stringResource(R.string.icon_pack_preview_note), style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(stringResource(R.string.icon_pack_native_note), style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
        pack.description.author?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
        if (pack.notices.isNotEmpty()) Text(stringResource(R.string.icon_pack_effects_note),
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
  }
}

@Composable
private fun PackIconPair(pack: IconPackDescriptor, entry: IconPackEntry, repository: IconPackPreviewRepository, modifier: Modifier) {
  val configuration = LocalConfiguration.current.toString()
  val preview by produceState<IconPackPreviews?>(null, pack.source, entry, configuration) {
    value = repository.load(pack, entry, 144)
  }
  val light = remember(preview?.light) { preview?.light?.asImageBitmap() }
  val dark = remember(preview?.dark) { preview?.dark?.asImageBitmap() }
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    listOf(light to false, dark to true).forEach { (bitmap, isDark) ->
      Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp))
        .background(if (isDark) Color(0xff232428) else Color(0xfff0f1f4)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, stringResource(if (isDark) R.string.icon_pack_dark_preview else R.string.icon_pack_light_preview),
          Modifier.size(40.dp))
        else Text("—", color = if (isDark) Color.White else Color.DarkGray)
      }
    }
  }
}
