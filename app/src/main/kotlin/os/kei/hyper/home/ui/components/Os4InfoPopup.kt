package os.kei.hyper.home.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

/**
 * An information icon that morphs into a short, read-only explanation.
 *
 * [backdrop] must record content below the root popup host, excluding this popup. `null` keeps
 * the native morph and uses MIUIX's opaque surface. Editors and multi-step help belong in sheets.
 */
@Composable
fun Os4InfoPopup(
  title: String,
  summary: String,
  modifier: Modifier = Modifier,
  contentDescription: String = stringResource(R.string.info_os4_show, title),
  enabled: Boolean = true,
  backdrop: Backdrop? = LocalKeiHomeBackdrop.current,
) {
  var expanded by remember { mutableStateOf(false) }
  val mounted = remember { mutableStateOf(false) }
  var nativeContentMounted by remember { mutableStateOf(false) }
  var restoreFocus by remember { mutableStateOf(false) }
  val anchor = rememberGlassPopupAnchor()
  var anchorReady by remember { mutableStateOf(false) }
  val triggerFocus = remember { FocusRequester() }
  val popupFocus = remember { FocusRequester() }
  val latestExpanded by rememberUpdatedState(expanded)
  val expandedDescription = stringResource(
    if (expanded) R.string.info_os4_expanded else R.string.info_os4_collapsed,
  )
  val requestDismiss = { expanded = false }
  // Capture the page's source before entering the root portal; never create a popup backdrop.
  val sourceBackdrop = backdrop
  val sourceLocals = currentCompositionLocalContext
  val style = GlassDefaults.style(LocalKeiHomeDarkTheme.current)
  val visuals = if (sourceBackdrop == null) {
    GlassPopupDefaults.visuals(
      style = style,
      containerColor = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 1f),
    )
  } else {
    GlassPopupDefaults.visuals(style = style)
  }
  val iconColor = MiuixTheme.colorScheme.onSurfaceSecondary

  LaunchedEffect(enabled) { if (!enabled) expanded = false }
  LaunchedEffect(expanded) {
    // A close before native content ever mounted has no exit transition to await.
    if (!expanded && !nativeContentMounted) mounted.value = false
  }
  LaunchedEffect(mounted.value) {
    if (!mounted.value && restoreFocus) {
      restoreFocus = false
      triggerFocus.requestFocus()
    }
  }

  CompositionLocalProvider(LocalKeiHomeBackdrop provides sourceBackdrop) {
    KeiHomeIconButton(
      onClick = {
        if (!mounted.value && anchorReady) {
          restoreFocus = true
          mounted.value = true
          expanded = true
        }
      },
      enabled = enabled,
      minHeight = 48.dp,
      minWidth = 48.dp,
      modifier = modifier
        .glassPopupAnchor(anchor, cornerRadius = 24.dp)
        .onGloballyPositioned { anchorReady = it.size.width > 0 && it.size.height > 0 }
        .focusRequester(triggerFocus)
        // The native clickable otherwise rejects focus in touch mode, including restoration.
        .focusProperties { canFocus = enabled && !mounted.value }
        .then(if (mounted.value) Modifier.clearAndSetSemantics {} else Modifier.semantics {
          this.contentDescription = contentDescription
          stateDescription = expandedDescription
        }),
    ) {
      Icon(MiuixIcons.Glass.Info, null,
        Modifier.size(24.dp).glassPopupAnchorContent(anchor), tint = iconColor)
    }
  }

  if (mounted.value) {
    MiuixPopupUtils.PopupLayout(
      visible = mounted,
      enableWindowDim = false,
      enableBackHandler = false,
      enterTransition = EnterTransition.None,
      exitTransition = ExitTransition.None,
      renderInRootScaffold = true,
    ) {
      CompositionLocalProvider(sourceLocals) {
        var ready by remember { mutableStateOf(false) }
        Box(
          Modifier.fillMaxSize().testTag("info.popup.host")
            .onGloballyPositioned { ready = true }
            .onPreviewKeyEvent {
              if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                requestDismiss()
                true
              } else false
            },
        ) {
          GlassTransformPopup(
            show = expanded && ready,
            onDismissRequest = requestDismiss,
            backdrop = sourceBackdrop,
            anchor = anchor,
            visuals = visuals,
            contentPadding = PaddingValues(20.dp),
            anchorContent = {
              Icon(MiuixIcons.Glass.Info, null, Modifier.size(24.dp), tint = iconColor)
            },
          ) {
            // Native content leaves composition only after the transform finishes closing.
            DisposableEffect(Unit) {
              nativeContentMounted = true
              onDispose {
                nativeContentMounted = false
                if (!latestExpanded) mounted.value = false
              }
            }
            LaunchedEffect(Unit) { popupFocus.requestFocus() }
            Text(
              title,
              modifier = Modifier
                .testTag("info.popup.title")
                .focusRequester(popupFocus)
                .focusable()
                .semantics {
                  heading()
                  paneTitle = title
                  dismiss { requestDismiss(); true }
                },
              style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(8.dp))
            Text(
              summary,
              modifier = Modifier.testTag("info.popup.summary"),
              style = MiuixTheme.textStyles.body2,
              color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
          }
        }
      }
    }
  }
}
