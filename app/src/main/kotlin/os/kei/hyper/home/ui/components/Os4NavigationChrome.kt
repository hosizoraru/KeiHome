package os.kei.hyper.home.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope

@Immutable
internal data class KeiHomeNavChromeState(
  val isCurrentDestination: Boolean = true,
  val scope: NavTransitionScope? = null,
)

/**
 * Safe bridge over MIUIX [LocalNavTransitionScope]: defaults to an active standalone state
 * outside [NavDisplay] so isolated previews and UI tests never throw.
 */
internal val LocalKeiHomeNavChrome = compositionLocalOf { KeiHomeNavChromeState() }

@Composable
internal fun ProvideKeiHomeNavChrome(
  isCurrentDestination: Boolean,
  content: @Composable () -> Unit,
) {
  val scope = LocalNavTransitionScope.current
  val state = remember(isCurrentDestination, scope) {
    KeiHomeNavChromeState(isCurrentDestination = isCurrentDestination, scope = scope)
  }
  CompositionLocalProvider(LocalKeiHomeNavChrome provides state, content = content)
}

internal fun shouldSeedNavChromeEntry(
  isCurrentDestination: Boolean,
  isRunning: Boolean,
  change: NavChange?,
): Boolean = isCurrentDestination && isRunning && change != null && change != NavChange.None

internal fun navBottomBarGestureDissolveFraction(
  role: NavRole?,
  gestureProgress: Float?,
): Float {
  if (role != NavRole.Outgoing || gestureProgress == null) return 0f
  return (gestureProgress / 0.55f).coerceIn(0f, 1f)
}

/**
 * Shared visibility state for floating bottom navigation bars across MainScreen and PackTabScaffold.
 *
 * - Cold start at rest: starts visible immediately at 1.0f scale (no unwanted startup bloom).
 * - Push to child: parent flips `false` on frame 0 and stows via MIUIX `GlassMotion.navShowHide()`.
 * - Push into a bar-owning destination or recreated pop target: seeds frame 0 to `false` (0.6f)
 *   and flips `true` on frame 1 so the bar blooms smoothly into place after `NAV_SHOW_DELAY_MS`.
 * - Predictive-back swipe: stays `true` on the swiped child and `false` on the covered parent
 *   until commit so cancelling never causes a stow/restore twitch.
 */
@Composable
internal fun rememberNavBottomBarVisible(enabled: Boolean = true): Boolean {
  val chrome = LocalKeiHomeNavChrome.current
  val seedHiddenOnEntry = remember {
    shouldSeedNavChromeEntry(
      isCurrentDestination = chrome.isCurrentDestination,
      isRunning = chrome.scope?.isRunning == true,
      change = chrome.scope?.change,
    )
  }
  var hasEntered by remember { mutableStateOf(!seedHiddenOnEntry) }
  LaunchedEffect(Unit) {
    if (!hasEntered) hasEntered = true
  }
  val visible by remember(enabled, chrome, hasEntered) {
    derivedStateOf { enabled && chrome.isCurrentDestination && hasEntered }
  }
  return visible
}

/**
 * Finger-coupled draw-phase dissolve while an outgoing bar-owning route is being dragged back.
 * Reads [NavTransitionScope.gesture] strictly in `graphicsLayer` to avoid 120Hz recomposition.
 */
internal fun Modifier.navBottomBarGestureDissolve(): Modifier = composed {
  val scope = LocalKeiHomeNavChrome.current.scope
  if (scope == null) return@composed this
  graphicsLayer {
    val dissolve = navBottomBarGestureDissolveFraction(scope.role, scope.gesture?.progress)
    if (dissolve > 0.001f) {
      val s = lerp(1f, 0.86f, dissolve)
      scaleX = s
      scaleY = s
      alpha = lerp(1f, 0.35f, dissolve)
    }
  }
}

/**
 * Layout-preserving stow/restore for FABs anchored above a floating bottom bar.
 * Preserves exact measured bounds so `Scaffold` insets and `onSizeChanged` heights never jump.
 */
@Composable
internal fun Os4ChromeStowContainer(
  visible: Boolean,
  modifier: Modifier = Modifier,
  showDelayMillis: Long = GlassMotion.NAV_SHOW_DELAY_MS + 30L,
  content: @Composable () -> Unit,
) {
  val chrome = LocalKeiHomeNavChrome.current
  val progress = remember { Animatable(if (visible) 1f else 0f) }
  LaunchedEffect(visible) {
    if (visible) {
      if (progress.value < 0.999f && showDelayMillis > 0L) delay(showDelayMillis)
      progress.animateTo(1f, GlassMotion.navShowHide())
    } else {
      progress.animateTo(0f, GlassMotion.navShowHide())
    }
  }
  Box(
    modifier = modifier
      .focusProperties { canFocus = visible }
      .then(
        if (!visible) {
          Modifier
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
              awaitPointerEventScope {
                while (true) {
                  val event = awaitPointerEvent(PointerEventPass.Initial)
                  event.changes.forEach { it.consume() }
                }
              }
            }
        } else Modifier,
      )
      .graphicsLayer {
        val base = progress.value
        val dissolve = navBottomBarGestureDissolveFraction(chrome.scope?.role, chrome.scope?.gesture?.progress)
        val effective = base * lerp(1f, 0.35f, dissolve)
        val s = lerp(0.82f, 1f, base) * lerp(1f, 0.88f, dissolve)
        scaleX = s
        scaleY = s
        alpha = effective
      },
  ) {
    content()
  }
}
