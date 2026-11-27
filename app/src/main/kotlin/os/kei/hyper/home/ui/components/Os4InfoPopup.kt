package os.kei.hyper.home.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassPopupAnchor
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

private val Os4InfoPopupSizing = GlassPopupSizing(
  minWidth = 240.dp,
  maxWidth = 308.dp,
  safeMargin = 16.dp,
)

/**
 * An information icon that morphs into a short, read-only explanation via [GlassTransformPopup],
 * or escalates to a structured [Os4DetailsSheet] when [presentation] selects a sheet.
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
  icon: ImageVector = MiuixIcons.Glass.Info,
  presentation: Os4InfoPresentation = Os4InfoPresentation.Popup,
  leadSummary: String? = null,
  horizontalAlignment: Alignment.Horizontal = Alignment.End,
  helpTag: String? = null,
) {
  val buttonSize = keiHomeIconButtonSize()
  val iconColor = MiuixTheme.colorScheme.onSurfaceSecondary
  Os4InfoTriggerCore(
    title = title,
    summary = summary,
    contentDescription = contentDescription,
    enabled = enabled,
    backdrop = backdrop,
    icon = icon,
    iconSize = 24.dp,
    iconTint = iconColor,
    activeIconTint = MiuixTheme.colorScheme.primary,
    presentation = presentation,
    leadSummary = leadSummary,
    horizontalAlignment = horizontalAlignment,
    helpTag = helpTag,
    onOpenChange = null,
  ) { state ->
    CompositionLocalProvider(LocalKeiHomeBackdrop provides backdrop) {
      KeiHomeIconButton(
        onClick = state.onTriggerClick,
        enabled = enabled,
        minHeight = buttonSize,
        minWidth = buttonSize,
        modifier = modifier
          .then(state.anchorModifier(buttonSize / 2))
          .then(state.triggerSemanticsModifier),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.size(24.dp).then(state.anchorContentModifier),
          tint = state.animatedIconTint,
        )
      }
    }
  }
}

/**
 * Compact title-side `(i)` button for preference and card headers.
 *
 * Concise-to-medium explanations morph directly out of the `(i)` anchor via [GlassTransformPopup];
 * genuinely long or multi-paragraph explanations open a structured [Os4DetailsSheet].
 */
@Composable
internal fun Os4TitleInfoButton(
  title: String,
  explanation: String,
  modifier: Modifier = Modifier,
  leadSummary: String? = null,
  enabled: Boolean = true,
  tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
  activeTint: Color = MiuixTheme.colorScheme.primary,
  iconSize: Dp = 18.dp,
  presentation: Os4InfoPresentation = Os4InfoPresentation.Auto,
  helpTag: String? = null,
  onOpenChange: ((Boolean) -> Unit)? = null,
) {
  val contentDescription = stringResource(R.string.preference_help_for, title)
  Os4InfoTriggerCore(
    title = title,
    summary = explanation,
    contentDescription = contentDescription,
    enabled = enabled,
    backdrop = LocalKeiHomeBackdrop.current,
    icon = MiuixIcons.Glass.Info,
    iconSize = iconSize,
    iconTint = tint,
    activeIconTint = activeTint,
    presentation = presentation,
    leadSummary = leadSummary,
    horizontalAlignment = Alignment.Start,
    helpTag = helpTag,
    onOpenChange = onOpenChange,
  ) { state ->
    IconButton(
      onClick = state.onTriggerClick,
      enabled = enabled,
      minWidth = 30.dp,
      minHeight = 28.dp,
      holdDownState = state.isOpen,
      modifier = modifier
        .then(if (helpTag != null) Modifier.testTag("$helpTag.open") else Modifier)
        .then(state.anchorModifier(15.dp))
        .then(state.triggerSemanticsModifier),
    ) {
      Icon(
        imageVector = MiuixIcons.Glass.Info,
        contentDescription = null,
        modifier = Modifier.size(iconSize).then(state.anchorContentModifier),
        tint = state.animatedIconTint,
      )
    }
  }
}

/**
 * Title row placing a compact [Os4TitleInfoButton] snugly beside [title].
 * Uses `weight(1f, fill = false)` so short titles keep the button adjacent while long titles wrap
 * cleanly without pushing the button off-screen.
 */
@Composable
internal fun Os4ExplainedTitleRow(
  title: String,
  explanation: String,
  modifier: Modifier = Modifier,
  explanationTitle: String = title,
  leadSummary: String? = null,
  titleColor: Color = MiuixTheme.colorScheme.onSurface,
  titleStyle: TextStyle = MiuixTheme.textStyles.headline1,
  titleFontWeight: FontWeight = FontWeight.Medium,
  titleMaxLines: Int = Int.MAX_VALUE,
  infoTint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
  infoActiveTint: Color = MiuixTheme.colorScheme.primary,
  infoIconSize: Dp = 18.dp,
  presentation: Os4InfoPresentation = Os4InfoPresentation.Auto,
  helpTag: String? = null,
  onOpenChange: ((Boolean) -> Unit)? = null,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      modifier = Modifier.weight(1f, fill = false),
      style = titleStyle,
      fontWeight = titleFontWeight,
      color = titleColor,
      maxLines = titleMaxLines,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.width(2.dp))
    Os4TitleInfoButton(
      title = explanationTitle,
      explanation = explanation,
      leadSummary = leadSummary,
      tint = infoTint,
      activeTint = infoActiveTint,
      iconSize = infoIconSize,
      presentation = presentation,
      helpTag = helpTag,
      onOpenChange = onOpenChange,
    )
  }
}

private class Os4InfoTriggerState(
  val isOpen: Boolean,
  val animatedIconTint: Color,
  val onTriggerClick: () -> Unit,
  val anchorModifier: (Dp) -> Modifier,
  val anchorContentModifier: Modifier,
  val triggerSemanticsModifier: Modifier,
)

@Composable
private fun Os4InfoTriggerCore(
  title: String,
  summary: String,
  contentDescription: String,
  enabled: Boolean,
  backdrop: Backdrop?,
  icon: ImageVector,
  iconSize: Dp,
  iconTint: Color,
  activeIconTint: Color,
  presentation: Os4InfoPresentation,
  leadSummary: String?,
  horizontalAlignment: Alignment.Horizontal,
  helpTag: String?,
  onOpenChange: ((Boolean) -> Unit)?,
  trigger: @Composable (Os4InfoTriggerState) -> Unit,
) {
  val useSheet = remember(summary, presentation) {
    shouldUseSheetForExplanation(summary, presentation)
  }
  val expandedStateLabel = stringResource(R.string.info_os4_expanded)
  val collapsedStateLabel = stringResource(R.string.info_os4_collapsed)

  if (useSheet) {
    var showSheet by rememberSaveable(title, helpTag) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) showSheet = false }
    LaunchedEffect(showSheet) { onOpenChange?.invoke(showSheet) }
    val animatedTint by animateColorAsState(
      targetValue = if (showSheet) activeIconTint else iconTint,
      animationSpec = Os4Motion.colorSpring(),
      label = "os4-info-sheet-tint",
    )
    trigger(
      Os4InfoTriggerState(
        isOpen = showSheet,
        animatedIconTint = animatedTint,
        onTriggerClick = { showSheet = true },
        anchorModifier = { Modifier },
        anchorContentModifier = Modifier,
        triggerSemanticsModifier = Modifier.semantics {
          this.contentDescription = contentDescription
          stateDescription = if (showSheet) expandedStateLabel else collapsedStateLabel
        },
      ),
    )
    val sheetCardScroll = rememberScrollState()
    Os4DetailsSheet(
      show = showSheet,
      onDismissRequest = { showSheet = false },
      title = title,
      scrollContent = false,
      scrollOffset = { sheetCardScroll.value.toFloat() },
      modifier = if (helpTag != null) Modifier.testTag(helpTag) else Modifier,
    ) {
      Os4ExplanationSheetBody(
        explanation = summary,
        leadSummary = leadSummary,
        onDismiss = { showSheet = false },
        scrollState = sheetCardScroll,
      )
    }
    return
  }

  var expanded by remember { mutableStateOf(false) }
  val mounted = remember { mutableStateOf(false) }
  var nativeContentMounted by remember { mutableStateOf(false) }
  var restoreFocus by remember { mutableStateOf(false) }
  val anchor: GlassPopupAnchor = rememberGlassPopupAnchor()
  var anchorReady by remember { mutableStateOf(false) }
  val triggerFocus = remember { FocusRequester() }
  val popupFocus = remember { FocusRequester() }
  val latestExpanded by rememberUpdatedState(expanded)
  val requestDismiss = { expanded = false }

  // Capture the page's source before entering the root portal; never sample the outer page behind
  // an already-open glass sheet so nested floating tips stay legible.
  val onGlassSurface = LocalOs4GlassSurface.current
  val effectiveBackdrop = backdrop.takeUnless { onGlassSurface }
  val sourceLocals = currentCompositionLocalContext
  val style = GlassDefaults.style(LocalKeiHomeDarkTheme.current)
  val visuals = if (effectiveBackdrop == null) {
    GlassPopupDefaults.visuals(
      style = style,
      containerColor = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 1f),
    )
  } else {
    GlassPopupDefaults.visuals(style = style)
  }

  LaunchedEffect(enabled) { if (!enabled) expanded = false }
  LaunchedEffect(expanded) {
    onOpenChange?.invoke(expanded)
    // A close before native content ever mounted has no exit transition to await.
    if (!expanded && !nativeContentMounted) mounted.value = false
  }
  LaunchedEffect(mounted.value) {
    if (!mounted.value && restoreFocus) {
      restoreFocus = false
      triggerFocus.requestFocus()
    }
  }

  val animatedTint by animateColorAsState(
    targetValue = if (expanded) activeIconTint else iconTint,
    animationSpec = Os4Motion.colorSpring(),
    label = "os4-info-popup-tint",
  )

  trigger(
    Os4InfoTriggerState(
      isOpen = expanded,
      animatedIconTint = animatedTint,
      onTriggerClick = {
        if (!mounted.value && anchorReady) {
          restoreFocus = true
          mounted.value = true
          expanded = true
        }
      },
      anchorModifier = { cornerRadius ->
        Modifier
          .glassPopupAnchor(anchor, cornerRadius = cornerRadius)
          .onGloballyPositioned { anchorReady = it.size.width > 0 && it.size.height > 0 }
          .focusRequester(triggerFocus)
          // The native clickable otherwise rejects focus in touch mode, including restoration.
          .focusProperties { canFocus = enabled && !mounted.value }
      },
      anchorContentModifier = Modifier.glassPopupAnchorContent(anchor),
      triggerSemanticsModifier = if (mounted.value) {
        Modifier.clearAndSetSemantics {}
      } else {
        Modifier.semantics {
          this.contentDescription = contentDescription
          stateDescription = if (expanded) expandedStateLabel else collapsedStateLabel
        }
      },
    ),
  )

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
          Modifier
            .fillMaxSize()
            .testTag("info.popup.host")
            .onGloballyPositioned { ready = true }
            .onPreviewKeyEvent {
              if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                requestDismiss()
                true
              } else false
            },
        ) {
          val subtitleBase = MiuixTheme.textStyles.subtitle
          val titleStyle = subtitleBase.copy(
            fontWeight = FontWeight.SemiBold,
            lineHeight = if (subtitleBase.fontSize.isSpecified) {
              (subtitleBase.fontSize.value * 1.32f).sp
            } else {
              subtitleBase.lineHeight
            },
          )
          GlassTransformPopup(
            show = expanded && ready,
            onDismissRequest = requestDismiss,
            backdrop = effectiveBackdrop,
            anchor = anchor,
            visuals = visuals,
            sizing = Os4InfoPopupSizing,
            horizontalAlignment = horizontalAlignment,
            gap = 6.dp,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            anchorContent = {
              Icon(icon, null, Modifier.size(iconSize), tint = animatedTint)
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
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .then(
                  if (helpTag != null) {
                    Modifier.testTag(helpTag).semantics {
                      paneTitle = title
                      dismiss { requestDismiss(); true }
                    }
                  } else {
                    Modifier
                  },
                ),
            ) {
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
                style = titleStyle,
                color = MiuixTheme.colorScheme.onSurface,
              )
              HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
              )
              Os4ExplanationPoints(
                explanation = summary,
                baseStyle = MiuixTheme.textStyles.body2,
                lineHeightMultiplier = 1.54f,
                itemSpacing = 8.dp,
                dotSize = 4.5.dp,
                textAlpha = 0.86f,
                summaryTag = "info.popup.summary",
              )
            }
          }
        }
      }
    }
  }
}
