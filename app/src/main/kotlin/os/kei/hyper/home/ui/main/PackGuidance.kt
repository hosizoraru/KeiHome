package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.ui.components.Os4DisclosureHeader
import os.kei.hyper.home.ui.components.Os4DisclosureContent
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.Text
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.components.Os4DialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Informational notes only; errors, progress and actionable warnings stay in the page. */
@Composable
internal fun PackHelpAction(notes: List<String>) {
  if (notes.isEmpty()) return
  if (notes.size == 1) {
    Os4InfoPopup(
      title = stringResource(R.string.pack_guidance_title),
      summary = notes.single(),
    )
    return
  }
  var showing by rememberSaveable { mutableStateOf(false) }
  val title = stringResource(R.string.pack_guidance_title)
  IconButton(onClick = { showing = true }) { Icon(MiuixIcons.Glass.Info, title) }
  Os4DetailsSheet(show = showing, title = title, scrollContent = false, onDismissRequest = { showing = false }) {
    GuidanceNotes(notes, Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
      .verticalScroll(rememberScrollState()))
    Os4DialogButton(stringResource(R.string.pack_guidance_done), onClick = { showing = false },
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      role = GlassDialogButtonRole.Primary)
  }
}

@Composable
internal fun PackGuidanceCard(notes: List<String>, modifier: Modifier = Modifier) {
  if (notes.isEmpty()) return
  var expanded by rememberSaveable { mutableStateOf(false) }
  val state = stringResource(if (expanded) R.string.pack_guidance_expanded else R.string.pack_guidance_collapsed)
  Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    Os4DisclosureHeader(
      title = stringResource(R.string.pack_guidance_title), summary = notes.firstOrNull(),
      expanded = expanded, stateLabel = state, onToggle = { expanded = !expanded },
      actionLabel = stringResource(if (expanded) R.string.pack_guidance_collapse else R.string.pack_guidance_expand),
    )
    Os4DisclosureContent(expanded) {
      GuidanceNotes(notes.drop(1), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
    }
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
