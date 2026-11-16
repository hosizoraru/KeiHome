package os.kei.hyper.home.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.glass.GlassIconDropdownMenu
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

/**
 * Import-compatible with the existing entries/content menu calls.
 *
 * [content] supplies the trigger icon and its matching popup morph content. The native popup
 * uses its opaque fallback with a basic trigger when no page backdrop exists. The outermost
 * MIUIX Scaffold must be outside the page recording.
 */
@Composable
internal fun OverlayIconCascadingDropdownMenu(
  entries: List<DropdownEntry>,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val backdrop = LocalKeiHomeBackdrop.current
  val anchor = rememberGlassPopupAnchor()
  var expanded by remember { mutableStateOf(false) }
  val mounted = remember { mutableStateOf(false) }
  val hasItems = entries.any { it.items.isNotEmpty() }
  val buttonSize = keiHomeIconButtonSize()
  val haptics = LocalHapticFeedback.current
  val style = GlassDefaults.style(LocalKeiHomeDarkTheme.current)
  val visuals = if (backdrop == null) {
    GlassPopupDefaults.visuals(
      style = style,
      containerColor = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 1f),
    )
  } else {
    GlassPopupDefaults.visuals(style = style)
  }
  val sourceLocals = currentCompositionLocalContext
  val onClick = {
    if (hasItems) {
      expanded = !expanded
      if (expanded) {
        mounted.value = true
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
      }
    }
  }

  val anchorModifier = modifier.glassPopupAnchor(anchor, cornerRadius = buttonSize / 2)
  if (backdrop != null && hasItems) {
    GlassIconButton(onClick = onClick, modifier = anchorModifier.semantics { role = Role.Button },
      backdrop = backdrop, style = style, size = buttonSize) {
      Box(Modifier.glassPopupAnchorContent(anchor)) { content() }
    }
  } else {
    KeiHomeIconButton(onClick = onClick, enabled = hasItems, modifier = anchorModifier) {
      Box(Modifier.glassPopupAnchorContent(anchor)) { content() }
    }
  }

  if (!mounted.value) return

  PopupLayout(
    visible = mounted,
    enableWindowDim = false,
    enableBackHandler = false,
    enterTransition = EnterTransition.None,
    exitTransition = ExitTransition.None,
    renderInRootScaffold = true,
  ) {
    CompositionLocalProvider(sourceLocals) {
      var ready by remember { mutableStateOf(false) }
      val show = expanded && hasItems && ready
      val lifetime = remember { Animatable(0f) }

      // The native icon menu has no completion callback. Its closing centre spring is the
      // longest primary transform; retain the portal through it rather than imposing a timeout.
      LaunchedEffect(expanded, hasItems, ready) {
        if (expanded && hasItems && !ready) return@LaunchedEffect
        lifetime.animateTo(if (show) 1f else 0f, GlassMotion.transformCenter(show))
        if (!show) {
          expanded = false
          mounted.value = false
        }
      }

      // Native Glass menus now map window-space anchors into their host themselves.
      Box(Modifier.fillMaxSize().onGloballyPositioned { ready = true }) {
        GlassIconDropdownMenu(
          show = show,
          onDismissRequest = { expanded = false },
          anchor = anchor,
          anchorContent = content,
          entries = entries,
          backdrop = backdrop,
          visuals = visuals,
          // Unlike the native Glass default, existing grouped menus close on selection.
          collapseOnSelection = true,
        )
      }
    }
  }
}
