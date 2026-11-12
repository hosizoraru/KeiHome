package os.kei.hyper.home.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.glassPopupAnchorValue
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

/** The string-only native preference cannot represent individually unavailable choices. */
@Composable
internal fun Os4ItemDropdownPreference(
  items: List<DropdownItem>,
  selectedIndex: Int,
  title: String,
  modifier: Modifier,
  summary: String?,
  enabled: Boolean,
  insideMargin: PaddingValues,
  onSelectedIndexChange: ((Int) -> Unit)?,
) {
  val actualEnabled = enabled && items.any { it.enabled }
  var expanded by remember { mutableStateOf(false) }
  val mounted = remember { mutableStateOf(false) }
  var bounds by remember { mutableStateOf(Rect.Zero) }
  val anchor = rememberGlassPopupAnchor()
  val backdrop = LocalKeiHomeBackdrop.current
  val locals = currentCompositionLocalContext
  val haptics = LocalHapticFeedback.current
  val visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(LocalKeiHomeDarkTheme.current))
  val actionColor = if (actualEnabled) MiuixTheme.colorScheme.onSurfaceVariantActions
    else MiuixTheme.colorScheme.disabledOnSecondaryVariant
  LaunchedEffect(actualEnabled) { if (!actualEnabled) expanded = false }

  BasicComponent(
    title = title, summary = summary, enabled = actualEnabled, insideMargin = insideMargin,
    modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
      .semantics(mergeDescendants = true) {
        role = Role.DropdownList
        if (!actualEnabled) disabled()
      },
    role = Role.DropdownList, holdDownState = mounted.value,
    onClick = {
      expanded = !expanded
      if (expanded) {
        mounted.value = true
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
      }
    },
    endActions = {
      Row(Modifier.glassPopupAnchorValue(anchor), verticalAlignment = Alignment.CenterVertically) {
        items.getOrNull(selectedIndex)?.let {
          Text(it.text, Modifier.padding(end = 8.dp).weight(1f, fill = false),
            fontSize = MiuixTheme.textStyles.body2.fontSize, color = actionColor, textAlign = TextAlign.End)
        }
        DropdownArrowEndAction(actionColor = actionColor)
      }
    },
  )
  if (!mounted.value) return
  PopupLayout(visible = mounted, enableWindowDim = false, enableBackHandler = false,
    enterTransition = EnterTransition.None, exitTransition = ExitTransition.None,
    renderInRootScaffold = true) {
    CompositionLocalProvider(locals) {
      val show = expanded && actualEnabled
      val lifetime = remember { Animatable(0f) }
      // Retain the portal through the dropdown spring with a conservative settle threshold.
      LaunchedEffect(show) {
        val motion = GlassMotion.arcBounds<Float>(show)
        lifetime.animateTo(if (show) 1f else 0f,
          spring(motion.dampingRatio, motion.stiffness, visibilityThreshold = 0.001f))
        if (!show) mounted.value = false
      }
      Box(Modifier.fillMaxSize()) {
        GlassDropdownPopup(show, { expanded = false }, bounds, backdrop,
          anchor = anchor, visuals = visuals) {
          items.forEachIndexed { index, item ->
            GlassPopupItem(text = item.text, selected = selectedIndex == index,
              enabled = show && item.enabled, onClick = {
                if (show && item.enabled) {
                  expanded = false
                  haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                  onSelectedIndexChange?.invoke(index)
                  item.onClick?.invoke()
                }
              })
          }
        }
      }
    }
  }
}
