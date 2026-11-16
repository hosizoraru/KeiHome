package os.kei.hyper.home.ui.main

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import os.kei.hyper.home.R
import os.kei.hyper.home.data.PackGuidancePage
import os.kei.hyper.home.data.PackGuidancePreferences
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.Text
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Informational notes only; errors, progress and actionable warnings stay in the page. */
@Composable
internal fun PackHelpAction(
  notes: List<String>,
  page: PackGuidancePage,
  active: Boolean = true,
  preferences: PackGuidancePreferences? = null,
  revision: Int = PackGuidancePreferences.CURRENT_REVISION,
  onOpen: () -> Unit = {},
) {
  val guidance = remember(notes) { notes.filter { it.isNotBlank() }.distinct() }
  if (guidance.isEmpty()) return
  val context = LocalContext.current
  val progress = remember(context, preferences) {
    try {
      preferences ?: PackGuidancePreferences.get(context)
    } catch (failure: Exception) {
      Log.w("KeiHomePackGuidance", "Cannot open guidance progress; using this page session", failure)
      null
    }
  }
  val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
  // NavDisplay supplies an entry lifecycle; pager callers must additionally pass their active tab.
  val foreground = active && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
  var showing by rememberSaveable(page.storageKey, revision) { mutableStateOf(false) }
  var presented by remember(page.storageKey, revision) { mutableStateOf(false) }
  LaunchedEffect(progress, page, revision, foreground) {
    if (!foreground) showing = false
    else if (!presented) {
      val seen = try {
        progress?.hasSeen(page, revision) ?: false
      } catch (failure: Exception) {
        Log.w("KeiHomePackGuidance", "Cannot read guidance progress for ${page.storageKey}", failure)
        false
      }
      if (!seen) showing = true
    }
  }
  // Capture visibility so the key and guard agree even if the auto-open effect changes showing.
  val sheetVisible = foreground && showing
  LaunchedEffect(progress, page, revision, sheetVisible) {
    // Record only after a visible sheet has been composed, never during inactive precomposition.
    if (sheetVisible) {
      // A failed save must not reopen help in this mount; a fresh entry can retry.
      presented = true
      try {
        progress?.markSeen(page, revision)
      } catch (failure: Exception) {
        Log.w("KeiHomePackGuidance", "Cannot save guidance progress for ${page.storageKey}", failure)
      }
    }
  }
  val title = stringResource(R.string.pack_guidance_title)
  IconButton(onClick = { onOpen(); showing = true }, modifier = Modifier.testTag("pack-guidance.help.${page.storageKey}")) {
    Icon(MiuixIcons.Glass.UsageHelp, title)
  }
  // Keep the native sheet composed through its drag/back exit animation.
  Os4DetailsSheet(show = sheetVisible, title = title, scrollContent = false,
    modifier = Modifier.testTag("pack-guidance.sheet.${page.storageKey}"),
    onDismissRequest = { showing = false }) {
    GuidanceNotes(guidance, Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
      .verticalScroll(rememberScrollState()))
    Os4DialogButton(stringResource(R.string.pack_guidance_done), onClick = { showing = false },
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      role = GlassDialogButtonRole.Primary)
  }
}

@Composable
private fun GuidanceNotes(notes: List<String>, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    notes.filter { it.isNotBlank() }.distinct().forEach { note ->
      Text(note, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
internal fun packListGuidance(appearance: os.kei.hyper.home.data.IconPackAppearance): List<String> = buildList {
  add(stringResource(R.string.icon_pack_appearance_preview_note))
  add(stringResource(R.string.icon_pack_appearance_scope_note))
  if (appearance == os.kei.hyper.home.data.IconPackAppearance.SYSTEM)
    add(stringResource(R.string.icon_pack_appearance_system_note))
  if (appearance == os.kei.hyper.home.data.IconPackAppearance.DIM_AT_NIGHT)
    add(stringResource(R.string.icon_pack_appearance_dim_note))
  add(stringResource(R.string.icon_pack_appearance_snapshot_note))
  add(stringResource(R.string.icon_pack_native_note))
  add(stringResource(R.string.icon_pack_effects_note))
}

@Composable
internal fun colorOsPackGuidance(autoApply: Boolean): List<String> = listOf(
  stringResource(R.string.pack_coloros_plain),
  stringResource(if (autoApply) R.string.pack_action_hint_auto else R.string.pack_action_hint_manual),
)
