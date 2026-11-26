package os.kei.hyper.home.ui.components

import androidx.compose.animation.CapturedAnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Copy
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared MIUIX OS4 motion vocabulary powered by Folme springs and Compose 1.13 captured transitions. */
internal object Os4Motion {
  fun sizeSpring(): SpringSpec<IntSize> =
    folmeSpring(damping = 0.9f, response = 0.38f, visibilityThreshold = IntSize(1, 1))

  fun revealSpring(): SpringSpec<IntSize> =
    folmeSpring(damping = 0.92f, response = 0.34f, visibilityThreshold = IntSize(1, 1))

  fun colorSpring(): SpringSpec<Color> =
    folmeSpring(damping = 0.95f, response = 0.32f)

  fun stateSpring(): SpringSpec<Float> =
    folmeSpring(damping = 0.88f, response = 0.32f, visibilityThreshold = 0.001f)

  fun expandVertical(): EnterTransition =
    expandVertically(revealSpring(), expandFrom = Alignment.Top) + fadeIn(tween(180))

  fun shrinkVertical(): ExitTransition =
    shrinkVertically(revealSpring(), shrinkTowards = Alignment.Top) + fadeOut(tween(140))
}

/** Smooth vertical disclosure backed by CapturedAnimatedVisibility to avoid exit recomposition flicker. */
@Composable
internal fun Os4AnimatedVisibility(
  visible: Boolean,
  modifier: Modifier = Modifier,
  enter: EnterTransition = Os4Motion.expandVertical(),
  exit: ExitTransition = Os4Motion.shrinkVertical(),
  label: String = "os4-visibility",
  content: @Composable () -> Unit,
) {
  CapturedAnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter = enter,
    exit = exit,
    label = label,
    content = content,
  )
}

/** Smooth container height reflow aligned with MIUIX sheet/card Folme physics. */
internal fun Modifier.os4ContentSize(): Modifier = animateContentSize(Os4Motion.sizeSpring())

/** Subtle theme-aware pulsing squircle placeholder for async artwork and card skeletons. */
@Composable
internal fun Os4ShimmerPlaceholder(
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 10.dp,
) {
  val transition = rememberInfiniteTransition(label = "os4-shimmer")
  val alpha by transition.animateFloat(
    initialValue = 0.055f,
    targetValue = 0.135f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "os4-shimmer-alpha",
  )
  Box(modifier.squircleBackground(MiuixTheme.colorScheme.onSurface.copy(alpha = alpha), cornerRadius))
}

/** Top-bar refresh icon button with smooth Crossfade between refresh glyph and busy indicator. */
@Composable
internal fun Os4RefreshIconButton(
  busy: Boolean,
  onClick: () -> Unit,
  contentDescription: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val progress = stringResource(R.string.refresh_in_progress)
  KeiHomeIconButton(
    onClick = onClick,
    enabled = enabled && !busy,
    modifier = modifier.semantics {
      this.contentDescription = contentDescription
      if (busy) stateDescription = progress
    },
  ) {
    Crossfade(
      targetState = busy,
      modifier = Modifier.size(28.dp),
      animationSpec = tween(160),
      label = "os4-refresh-action",
    ) { loading ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp)
        else Icon(
          MiuixIcons.Glass.Refresh,
          contentDescription = null,
          modifier = Modifier.size(26.dp),
          tint = MiuixTheme.colorScheme.onBackground,
        )
      }
    }
  }
}

internal class CopyFeedbackState(
  val copied: Boolean,
  val trigger: () -> Unit,
)

/** Transient confirmation state for copy-to-clipboard actions with subtle haptic feedback. */
@Composable
internal fun rememberCopyFeedback(durationMillis: Long = 1500L): CopyFeedbackState {
  val haptic = LocalHapticFeedback.current
  var nonce by remember { mutableIntStateOf(0) }
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(nonce) {
    if (nonce > 0) {
      copied = true
      delay(durationMillis)
      copied = false
    }
  }
  return CopyFeedbackState(copied) {
    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
    nonce++
  }
}

/** Top-bar copy icon button that crossfades briefly into a checkmark on copy. */
@Composable
internal fun Os4CopyIconButton(
  onClick: () -> Unit,
  contentDescription: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  minHeight: Dp = keiHomeIconButtonSize(),
  minWidth: Dp = keiHomeIconButtonSize(),
) {
  val feedback = rememberCopyFeedback()
  KeiHomeIconButton(
    onClick = {
      onClick()
      feedback.trigger()
    },
    enabled = enabled,
    minHeight = minHeight,
    minWidth = minWidth,
    modifier = modifier.semantics { this.contentDescription = contentDescription },
  ) {
    Crossfade(
      targetState = feedback.copied,
      modifier = Modifier.size(28.dp),
      animationSpec = tween(180),
      label = "os4-copy-action",
    ) { done ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
          imageVector = if (done) MiuixIcons.Glass.Ok else MiuixIcons.Glass.Copy,
          contentDescription = null,
          modifier = Modifier.size(if (done) 22.dp else 26.dp),
          tint = if (done) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
        )
      }
    }
  }
}
