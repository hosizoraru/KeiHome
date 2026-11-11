package os.kei.hyper.home.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassIconDropdownMenu
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

  if (backdrop == null) {
    KeiHomeIconButton(
      onClick = onClick,
      enabled = hasItems,
      modifier = modifier.glassPopupAnchor(anchor, cornerRadius = 20.dp),
    ) {
      Box(Modifier.glassPopupAnchorContent(anchor)) { content() }
    }
  } else {
    GlassIconButton(
      onClick = onClick,
      backdrop = backdrop,
      style = style,
      size = 40.dp,
      modifier = modifier.glassPopupAnchor(anchor, cornerRadius = 20.dp),
    ) {
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
      var hostOrigin by remember { mutableStateOf(Offset.Zero) }
      var rootSize by remember { mutableStateOf(IntSize.Zero) }
      var ready by remember { mutableStateOf(false) }
      val density = LocalDensity.current
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

      Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        hostOrigin = coordinates.positionInRoot()
        rootSize = coordinates.findRootCoordinates().size
        ready = true
      }) {
        // Anchor modifiers report boundsInRoot. Give the native menu that same origin and
        // viewport even when the outer Scaffold is inset; measure the untranslated parent.
        Box(Modifier.absoluteOffset { -hostOrigin.round() }
          .wrapContentSize(AbsoluteAlignment.TopLeft, unbounded = true).requiredSize(
          width = with(density) { rootSize.width.toDp() },
          height = with(density) { rootSize.height.toDp() },
        )) {
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
}
