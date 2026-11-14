package os.kei.hyper.home.ui.main

import os.kei.hyper.home.ui.components.os4Clickable

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import os.kei.hyper.home.R
import os.kei.hyper.home.status.ScopeRequestFeedback
import os.kei.hyper.home.ui.components.Os4DisclosureContent
import os.kei.hyper.home.ui.components.Os4DisclosureHeader
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OnboardingHero(page: Int) {
  val primary = MiuixTheme.colorScheme.primary
  var entered by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { entered = true }
  val reveal by animateFloatAsState(if (entered) 1f else 0f, tween(280), label = "setup-art")
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(if (LocalDensity.current.fontScale > 1.3f) 100.dp else 156.dp)
      .graphicsLayer { alpha = reveal; translationY = (1f - reveal) * 12.dp.toPx() }
      .background(Brush.radialGradient(listOf(primary.copy(alpha = 0.14f), primary.copy(alpha = 0.02f), Color.Transparent)), CircleShape),
      contentAlignment = Alignment.Center) {
      if (page == 0) Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(156.dp)
        .clip(RoundedCornerShape(40.dp))
        .background(androidx.compose.ui.res.colorResource(R.color.ic_launcher_background)))
      else Icon(setupHeroIcon(page), null, Modifier.size(76.dp), tint = primary)
    }
    val title = when (page) {
      0 -> R.string.setup_os4_welcome_title
      1 -> R.string.setup_os4_framework_title
      2 -> R.string.setup_os4_icons_title
      3 -> R.string.setup_os4_desktop_title
      else -> R.string.setup_os4_finish_title
    }
    val body = when (page) {
      0 -> R.string.setup_os4_welcome_body
      1 -> R.string.setup_os4_framework_body
      2 -> R.string.setup_os4_icons_body
      3 -> R.string.setup_os4_desktop_body
      else -> R.string.setup_os4_finish_body
    }
    Text(stringResource(title), Modifier.fillMaxWidth().semantics { heading() },
      style = MiuixTheme.textStyles.title1.copy(fontSize = 32.sp, lineHeight = 40.sp), textAlign = TextAlign.Center)
    Text(stringResource(body), Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary, textAlign = TextAlign.Center)
  }
}

@Composable
private fun setupHeroIcon(page: Int): ImageVector = when (page) {
  1 -> ImageVector.vectorResource(LucideR.drawable.lucide_ic_shield_check)
  2 -> actionIcon(PageActionIcon.PACKS)
  3 -> actionIcon(PageActionIcon.DESKTOP)
  else -> actionIcon(PageActionIcon.APPLY)
}

@Composable
internal fun OnboardingStepContent(model: HomeViewModel, page: Int, onOpenPack: (String) -> Unit,
  onOpenDesktop: () -> Unit, onOpenSettings: (String) -> Unit, onOpenDesktopSection: (String) -> Unit) {
  when (page) {
    0 -> {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupTile(R.string.setup_os4_feature_icons, PageActionIcon.PACKS, Modifier.weight(1f))
        SetupTile(R.string.setup_os4_feature_desktop, PageActionIcon.DESKTOP, Modifier.weight(1f))
        SetupTile(R.string.setup_os4_feature_control, PageActionIcon.SETTINGS, Modifier.weight(1f))
      }
    }
    1 -> {
      SetupConnectionPanel(model)
      val manager = rememberFrameworkManagerAction()
      Card(Modifier.fillMaxWidth()) {
        Column {
          SetupLink(R.string.setup_os4_framework_manager, R.string.setup_os4_framework_action_summary, PageActionIcon.OPEN_EXTERNAL, onClick = manager)
          SetupDivider()
          SetupLink(R.string.setup_os4_scope, R.string.setup_os4_scope_body, PageActionIcon.MATCH) { onOpenSettings("hook") }
        }
      }
      SetupNote(R.string.setup_os4_activation)
    }
    2 -> {
      Card(Modifier.fillMaxWidth()) {
        Column {
          SetupLink(R.string.icon_pack_library, R.string.setup_library_body, PageActionIcon.IMPORT) { onOpenPack("library") }
          SetupDivider()
          SetupLink(R.string.perfect_icons_title, R.string.setup_perfect_body, PageActionIcon.FETCH) { onOpenPack("perfect") }
          SetupDivider()
          SetupLink(R.string.perfect_git_resources, R.string.setup_perfect_resources_body, PageActionIcon.FETCH) { onOpenPack("perfect-sources") }
          SetupDivider()
          SetupLink(R.string.apple_pack_title, R.string.setup_apple_body, PageActionIcon.SEARCH) { onOpenPack("apple") }
        }
      }
      SetupNote(R.string.pack_system_appearance_note, R.string.setup_native_icons_title)
      Card(Modifier.fillMaxWidth()) {
        Column {
          SetupLink(R.string.navigation_monet, R.string.setup_monet_body,
            onClick = { onOpenPack("monet") }, icon = ImageVector.vectorResource(LucideR.drawable.lucide_ic_palette))
          SetupDivider()
          SetupLink(R.string.pack_completion_title, R.string.setup_fallback_body, PageActionIcon.MATCH) { onOpenPack("fallback") }
          SetupDivider()
          SetupLink(R.string.icon_mask_entry_title, R.string.setup_mask_body, PageActionIcon.MATCH) { onOpenPack("icon-mask") }
          SetupDivider()
          SetupLink(R.string.preserved_icons_title, R.string.setup_preserved_body, PageActionIcon.APPS) { onOpenPack("preserved-icons") }
          SetupDivider()
          SetupLink(R.string.recommended_mappings_title, R.string.setup_recommended_mappings_body, PageActionIcon.MATCH) { onOpenPack("recommended-mappings") }
          SetupDivider()
          SetupLink(R.string.setup_os4_icons_more, R.string.setup_os4_icons_more_body, PageActionIcon.SETTINGS) { onOpenPack("settings") }
        }
      }
      SetupNote(R.string.setup_icon_tip_body, R.string.setup_icon_tip_title)
      SetupStorageDisclosure()
    }
    3 -> {
      Card(Modifier.fillMaxWidth()) {
        Column {
          SetupLink(R.string.setup_os4_desktop_dock, DesktopSection.DOCK.summary,
            onClick = { onOpenDesktopSection(DesktopSection.DOCK.route) },
            icon = ImageVector.vectorResource(LucideR.drawable.lucide_ic_panel_bottom))
          SetupDivider()
          SetupLink(R.string.setup_os4_desktop_appearance, DesktopSection.APPEARANCE.summary, PageActionIcon.SETTINGS) {
            onOpenDesktopSection(DesktopSection.APPEARANCE.route)
          }
          SetupDivider()
          SetupLink(R.string.setup_os4_desktop_launch, DesktopSection.ICONS.summary, PageActionIcon.APPS) {
            onOpenDesktopSection(DesktopSection.ICONS.route)
          }
          SetupDivider()
          SetupLink(R.string.setup_open_desktop, R.string.desktop_overview_summary, PageActionIcon.DESKTOP, onClick = onOpenDesktop)
        }
      }
      SetupNote(R.string.setup_os4_desktop_note)
    }
    else -> {
      SetupConnectionPanel(model)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupTile(R.string.settings_hook_section, PageActionIcon.MATCH, Modifier.weight(1f),
          icon = ImageVector.vectorResource(LucideR.drawable.lucide_ic_shield_check)) { onOpenSettings("hook") }
        SetupTile(R.string.settings_log_section, PageActionIcon.STATUS, Modifier.weight(1f),
          icon = ImageVector.vectorResource(LucideR.drawable.lucide_ic_file_text)) { onOpenSettings("log") }
        SetupTile(R.string.settings_cache_section, PageActionIcon.CLEAR, Modifier.weight(1f),
          icon = ImageVector.vectorResource(LucideR.drawable.lucide_ic_hard_drive)) { onOpenSettings("cache") }
      }
      SetupNote(R.string.setup_os4_help_body, R.string.setup_os4_help)
      SetupNote(R.string.setup_cache_body)
    }
  }
}

@Composable
private fun SetupTile(title: Int, action: PageActionIcon, modifier: Modifier,
  icon: ImageVector = actionIcon(action), onClick: (() -> Unit)? = null) {
  Column(modifier.clip(RoundedCornerShape(20.dp)).then(if (onClick != null) Modifier.os4Clickable(role = androidx.compose.ui.semantics.Role.Button,
    onClick = onClick) else Modifier).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.size(48.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
      contentAlignment = Alignment.Center) {
      Icon(icon, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
    }
    Text(stringResource(title), style = MiuixTheme.textStyles.footnote1, textAlign = TextAlign.Center)
  }
}

@Composable
private fun SetupLink(title: Int, body: Int, action: PageActionIcon = PageActionIcon.OPEN_INTERNAL,
  icon: ImageVector = actionIcon(action), onClick: () -> Unit) {
  BasicComponent(title = stringResource(title), summary = stringResource(body), onClick = onClick,
    startAction = { Icon(icon, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary) },
    endActions = { Icon(MiuixIcons.Glass.ChevronForward, null, Modifier.size(20.dp),
      tint = MiuixTheme.colorScheme.onSurfaceSecondary) })
}

@Composable
private fun SetupDivider() { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }

@Composable
private fun SetupNote(body: Int, title: Int? = null) {
  Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (title != null) Text(stringResource(title), style = MiuixTheme.textStyles.footnote1,
      modifier = Modifier.semantics { heading() })
    Text(stringResource(body), style = MiuixTheme.textStyles.footnote1,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}

@Composable
private fun SetupConnectionPanel(model: HomeViewModel) {
  val state by model.scopeRequests.state.collectAsStateWithLifecycle()
  val plan = state.plan
  val trusted = state.connected && !state.readFailed && plan != null
  val connectionColor = desktopStatusColor(if (state.connected) StatusTone.READY else StatusTone.WARNING)
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetadataPill(stringResource(if (state.connected) R.string.setup_os4_module_ready else R.string.setup_os4_module_pending), connectionColor)
        val label = if (trusted && plan != null) stringResource(R.string.setup_os4_scopes_granted, plan.granted.size, plan.eligible.size)
          else stringResource(R.string.setup_os4_scopes_unknown)
        MetadataPill(label, desktopStatusColor(if (trusted && plan?.complete == true) StatusTone.READY else StatusTone.WARNING))
      }
      if (state.canRequest || state.requesting) TextButton(stringResource(R.string.scope_request),
        onClick = model.scopeRequests::request, enabled = !state.requesting, modifier = Modifier.fillMaxWidth())
      val feedback = when {
        state.readFailed -> R.string.scope_read_failed
        plan?.complete == true -> null
        state.feedback == ScopeRequestFeedback.APPROVED -> R.string.scope_partial
        state.feedback == ScopeRequestFeedback.FAILED -> R.string.scope_failed
        state.feedback == ScopeRequestFeedback.RETURNED -> R.string.scope_returned
        state.feedback == ScopeRequestFeedback.TIMEOUT -> R.string.scope_timeout
        else -> null
      }
      if (feedback != null) Text(stringResource(feedback), style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (state.requesting) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun SetupStorageDisclosure() {
  var expanded by rememberSaveable { mutableStateOf(false) }
  Card(Modifier.fillMaxWidth()) {
    Column {
      val action = stringResource(if (expanded) R.string.polish_details_collapse else R.string.polish_details_expand)
      Os4DisclosureHeader(stringResource(R.string.setup_local_icons_title), null, expanded,
        action, action, { expanded = !expanded })
      Os4DisclosureContent(expanded) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
          Text(stringResource(R.string.setup_local_icons_body), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    }
  }
}
