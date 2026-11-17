package os.kei.hyper.home.ui.main

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import os.kei.hyper.home.ui.components.IconPreviewMode
import os.kei.hyper.home.ui.components.IconPreviewModeTabs
import os.kei.hyper.home.ui.components.os4Clickable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetActionStatus
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetReadState
import os.kei.hyper.home.data.MonetSchemeStyle
import os.kei.hyper.home.data.MonetSchemes
import os.kei.hyper.home.data.MonetSeedSource
import os.kei.hyper.home.data.WallpaperColorSnapshot
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.RadioButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Theme
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class MonetSection(val title: Int) {
  OVERVIEW(R.string.navigation_monet),
  DESIGN(R.string.monet_page_design),
  SOURCE(R.string.monet_page_source),
  STYLE(R.string.monet_page_style),
  COLORS(R.string.monet_page_colors),
  PREVIEW(R.string.monet_page_preview),
  DETAILS(R.string.monet_page_details);

  val isSheet get() = this in listOf(SOURCE, STYLE, PREVIEW)
  val page get() = if (isSheet) OVERVIEW else this
}

internal data class MonetApplyUiState(
  val choice: MonetCustomization? = null, val enabled: Boolean = false, val busy: Boolean = false,
  val notice: Int? = null, val warning: Boolean = false,
)

@Composable
internal fun MonetContent(
  snapshot: MonetPaletteSnapshot?,
  readFailed: Boolean,
  actionAvailable: Boolean = false,
  actionStatus: MonetActionStatus = MonetActionStatus.IDLE,
  submittedChoice: MonetCustomization? = null,
  applyState: MutableState<MonetApplyUiState>,
  preferredStyle: MonetSchemeStyle? = null,
  preferredAppearance: os.kei.hyper.home.data.IconPackAppearance? = null,
  section: MonetSection = MonetSection.OVERVIEW,
  onNavigate: (MonetSection) -> Unit = {},
  onDismissPanel: () -> Unit = {},
) {
  var draft by rememberSaveable { mutableStateOf<String?>(null) }
  val sectionState = rememberSaveableStateHolder()
  var previewView by rememberSaveable { mutableIntStateOf(IconPreviewMode.COMPARE.ordinal) }
  val storedChoice = MonetCustomization.decode(draft) ?: snapshot?.customization ?: MonetCustomization()
  val choice = storedChoice.copy(style = preferredStyle ?: storedChoice.style.takeUnless {
    it == MonetSchemeStyle.COLOROS_INSPIRATION
  } ?: MonetSchemeStyle.TONAL_SPOT,
    iconAppearance = preferredAppearance ?: storedChoice.iconAppearance)
  val systemSeed = snapshot?.frameworkPalette?.families?.firstOrNull { it.name == "primary" }
    ?.colors?.minByOrNull { kotlin.math.abs((it.tone ?: 50) - 50) }?.value
  val wallpaper = snapshot?.wallpaper ?: WallpaperColorSnapshot(MonetReadState.NOT_PROVIDED, emptyList())
  val colors = when (choice.source) {
    MonetSeedSource.HOME_WALLPAPER -> wallpaper.colors
    MonetSeedSource.LOCK_WALLPAPER -> wallpaper.lockColors
    MonetSeedSource.BASIC_COLOR -> MonetCustomization.BASIC_COLORS
    MonetSeedSource.SYSTEM_COLOR -> listOfNotNull(systemSeed)
  }
  val seed = if (choice.source == MonetSeedSource.SYSTEM_COLOR) systemSeed else choice.seed(wallpaper)
  val candidatePairs by produceState<Map<Int, os.kei.hyper.home.data.MonetIconRoles>>(emptyMap(), colors, preferredStyle) {
    value = withContext(Dispatchers.Default) {
      if (preferredStyle != MonetSchemeStyle.COLOROS_INSPIRATION) emptyMap()
      else colors.mapNotNull { candidate ->
        ensureActive()
        MonetSchemes.iconRoles(MonetSchemes.generate(candidate, preferredStyle), false,
          MonetCustomization(style = preferredStyle))?.let { candidate to it }
      }.toMap()
    }
  }
  val generated by produceState<Pair<Int?, Map<MonetSchemeStyle, Map<String, Int>>>>(
    initialValue = null to emptyMap(), key1 = seed, key2 = preferredStyle,
  ) {
    value = seed to withContext(Dispatchers.Default) {
      if (seed == null) emptyMap() else (preferredStyle?.let { listOf(it) } ?:
        MonetSchemeStyle.entries.filterNot { it == MonetSchemeStyle.COLOROS_INSPIRATION }).associateWith {
        ensureActive()
        MonetSchemes.generate(seed, it)
      }
    }
  }
  val schemes = generated.second.takeIf { generated.first == seed }.orEmpty()
  val customKey = Triple(seed, choice.style, choice.paletteOverrides)
  val customPalette by produceState<Pair<Any?, Map<String, Int>?>>(null to null, customKey) {
    value = customKey to withContext(Dispatchers.Default) {
      if (seed == null || choice.paletteOverrides.isEmpty()) null
      else MonetSchemes.generate(seed, choice.style, choice.paletteOverrides)
    }
  }
  val palette = if (choice.source == MonetSeedSource.SYSTEM_COLOR && choice.paletteOverrides.isEmpty()) {
    snapshot?.frameworkPalette?.families?.flatMap { family ->
      family.colors.mapNotNull { color -> color.tone?.let { "${family.name}$it" to color.value } }
    }?.toMap()
  } else if (choice.paletteOverrides.isEmpty()) schemes[choice.style]
    else customPalette.second.takeIf { customPalette.first == customKey }
  val legible = remember(palette, choice.backgroundFamily, choice.iconOverrides) {
    palette != null && listOf(false, true).all { dark ->
      MonetSchemes.iconRoles(palette, dark, choice)?.contrastRatio?.let { it >= 4.5 } == true
    }
  }
  val applying = actionStatus == MonetActionStatus.APPLYING
  val change: (MonetCustomization) -> Unit = { if (!applying) draft = it.encode() }
  val sourceNames = listOf(
    stringResource(R.string.monet_source_home), stringResource(R.string.monet_source_lock),
    stringResource(R.string.monet_source_basic),
    stringResource(R.string.pack_source_system),
  )

  val currentRequest = submittedChoice == null || submittedChoice == choice
  val notice = when {
    applying -> R.string.monet_action_applying
    actionStatus == MonetActionStatus.FAILED ->
      if (currentRequest) R.string.monet_action_failed else R.string.monet_action_previous_failed
    actionStatus == MonetActionStatus.UNCONFIRMED ->
      if (currentRequest) R.string.monet_action_unconfirmed else R.string.monet_action_previous_unconfirmed
    readFailed -> R.string.monet_unavailable
    seed == null || palette == null -> R.string.monet_seed_waiting
    !actionAvailable || actionStatus == MonetActionStatus.UNAVAILABLE -> R.string.monet_action_unavailable
    !legible -> R.string.monet_custom_low_contrast
    actionStatus == MonetActionStatus.APPLIED && choice == snapshot?.customization ->
      if (snapshot?.monochrome?.enabled == true) R.string.monet_action_applied else R.string.monet_action_saved_off
    else -> null
  }
  SideEffect { applyState.value = MonetApplyUiState(choice,
    actionAvailable && !applying && legible && seed != null, applying, notice,
    notice == R.string.monet_action_failed || notice == R.string.monet_action_previous_failed ||
      notice == R.string.monet_custom_low_contrast || notice == R.string.monet_unavailable) }
  var lastSheet by rememberSaveable { mutableStateOf(MonetSection.SOURCE) }
  if (section.isSheet) SideEffect { lastSheet = section }
  val activeSheet = if (section.isSheet) section else lastSheet
  val sheetScrollStates = MonetSection.entries.map { rememberScrollState() }
  val renderSection: @Composable (MonetSection) -> Unit = { visibleSection ->
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (visibleSection == MonetSection.OVERVIEW) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
      Text(stringResource(R.string.monet_current_theme), style = MiuixTheme.textStyles.body1)
      val current = snapshot?.customization?.takeIf { snapshot.customizationActive }
      Text(
        when {
          readFailed -> stringResource(R.string.monet_unavailable)
          snapshot == null -> stringResource(R.string.monet_loading)
          current != null -> "${sourceNames[current.source.ordinal]} · ${styleName(current.style)}" +
            if (current.hasCustomColors) " · ${stringResource(R.string.monet_custom_override)}" else ""
          snapshot.monochrome.enabled == true -> stringResource(R.string.monet_launcher_selection)
          else -> stringResource(R.string.monet_icons_disabled)
        },
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
      )
    }
    }
    if (visibleSection == MonetSection.OVERVIEW) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        val sections = listOf(MonetSection.SOURCE) +
          (if (preferredStyle == null) listOf(MonetSection.STYLE) else emptyList()) +
          listOf(MonetSection.COLORS, MonetSection.PREVIEW, MonetSection.DETAILS)
        for (destination in sections) BasicComponent(
          title = stringResource(destination.title),
          summary = when (destination) {
            MonetSection.SOURCE -> sourceNames[choice.source.ordinal]
            MonetSection.STYLE -> styleName(choice.style)
            MonetSection.COLORS -> stringResource(R.string.monet_page_colors_summary)
            MonetSection.PREVIEW -> stringResource(R.string.monet_page_preview_summary)
            MonetSection.DETAILS -> stringResource(R.string.monet_page_details_summary)
            else -> stringResource(R.string.monet_page_design_summary)
          },
          endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) },
          modifier = Modifier.testTag("monet.open.${destination.name}"),
          insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
          onClick = { onNavigate(destination) },
        )
      }
    }
    if (visibleSection == MonetSection.SOURCE) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      OverlaySpinnerPreference(
        title = stringResource(R.string.monet_seed_source),
        items = sourceNames.map { DropdownItem(text = it) },
        selectedIndex = choice.source.ordinal,
        onSelectedIndexChange = { change(choice.copy(source = MonetSeedSource.entries[it], colorIndex = 0)) },
      )
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          when {
            choice.source == MonetSeedSource.BASIC_COLOR -> stringResource(R.string.monet_basic_hint)
            choice.source == MonetSeedSource.SYSTEM_COLOR -> stringResource(R.string.pack_system_palette_hint)
            colors.isNotEmpty() -> stringResource(R.string.monet_seed_count, colors.size)
            else -> stringResource(R.string.monet_seed_waiting)
          },
          style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        FlowRow(modifier = Modifier.fillMaxWidth(),
          maxItemsInEachRow = if (choice.source == MonetSeedSource.BASIC_COLOR) 3 else 4,
          horizontalArrangement = if (choice.source == MonetSeedSource.BASIC_COLOR)
            Arrangement.SpaceEvenly else Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
          colors.forEachIndexed { index, color ->
            val pair = if (preferredStyle != null && choice.source == MonetSeedSource.SYSTEM_COLOR)
              palette?.let { MonetSchemes.iconRoles(it, false, choice) } else candidatePairs[color]
            val hex = "#%06X".format(color and 0xFFFFFF)
            val selectedColor = seed == color
            Card(
              modifier = Modifier.testTag("monet.seed.$index").semantics {
                contentDescription = hex
                selected = selectedColor
              },
              insideMargin = PaddingValues(6.dp),
              onClick = { change(choice.copy(colorIndex = index,
                color = if (choice.source == MonetSeedSource.BASIC_COLOR) color else choice.color)) },
              showIndication = true,
            ) {
              Box(Modifier.size(40.dp).clip(CircleShape).background(Color(pair?.background ?: color)),
                contentAlignment = Alignment.Center) {
                if (pair != null) Canvas(Modifier.size(40.dp)) {
                  drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                  }, Color(pair.foreground))
                }
                RadioButton(selectedColor, onClick = null,
                  colors = RadioButtonDefaults.radioButtonColors(
                    selectedColor = if (Color(color).luminance() > 0.5f) Color.Black else Color.White))
              }
            }
          }
        }
      }
      if (choice.source == MonetSeedSource.BASIC_COLOR) {
        MonetSeedColorField(choice.color, enabled = !applying) { change(choice.copy(color = it)) }
      }
    }
    }
    if (visibleSection == MonetSection.STYLE && preferredStyle == null) {
    SmallTitle(stringResource(R.string.monet_scheme_section))
    val columns = if (LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.15f) 1 else 2
    (preferredStyle?.let { listOf(it) } ?: MonetSchemeStyle.entries.filterNot {
      it == MonetSchemeStyle.COLOROS_INSPIRATION
    }).chunked(columns).forEach { styles ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        styles.forEach { style ->
          val sample = schemes[style]
          val isSelected = choice.style == style
          Card(
            modifier = Modifier.weight(1f).testTag("monet.style.${style.name}")
              .semantics { selected = isSelected }
              .os4Clickable(enabled = !applying, cornerRadius = CardDefaults.CornerRadius, role = androidx.compose.ui.semantics.Role.RadioButton) {
                change(choice.copy(style = style))
              },
            insideMargin = PaddingValues(12.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              val accent = sample?.get("primary60")?.let(::Color) ?: MiuixTheme.colorScheme.primary
              val secondary = sample?.get("secondary60")?.let(::Color) ?: MiuixTheme.colorScheme.surface
              val tertiary = sample?.get("tertiary60")?.let(::Color) ?: MiuixTheme.colorScheme.surface
              val neutral = sample?.get("neutral90")?.let(::Color) ?: MiuixTheme.colorScheme.surface
              Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(30.dp)) {
                  drawArc(accent, 0f, 90f, useCenter = true)
                  drawArc(secondary, 90f, 90f, useCenter = true)
                  drawArc(tertiary, 180f, 90f, useCenter = true)
                  drawArc(neutral, 270f, 90f, useCenter = true)
                }
              }
              Text(styleName(style), Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
              RadioButton(isSelected, onClick = null, modifier = Modifier.size(20.dp))
            }
          }
        }
        if (styles.size < columns) Spacer(Modifier.weight(1f))
      }
    }
    Text(
      text = styleDescription(choice.style),
      modifier = Modifier.padding(horizontal = 16.dp),
      style = MiuixTheme.textStyles.footnote1,
      color = MiuixTheme.colorScheme.onSurfaceSecondary,
    )
    }
    if (visibleSection == MonetSection.STYLE && choice.source != MonetSeedSource.BASIC_COLOR &&
      choice.style in listOf(MonetSchemeStyle.RAINBOW, MonetSchemeStyle.FRUIT_SALAD)
    ) {
      Text(stringResource(R.string.monet_details_basic_recommended),
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
    if (visibleSection == MonetSection.COLORS && seed != null) {
      // Keep editor expansion state while the new palette is generated off the UI thread.
      MonetAdvancedColors(choice, seed, palette.orEmpty(), enabled = !applying && palette != null, onChange = change)
    }
    if (visibleSection == MonetSection.COLORS && seed == null) {
      Text(stringResource(if (readFailed) R.string.monet_unavailable else R.string.monet_seed_waiting),
        modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
      TextButton(stringResource(R.string.monet_page_source), onClick = { onNavigate(MonetSection.SOURCE) })
    }
    if (visibleSection == MonetSection.PREVIEW && palette != null) {
      SmallTitle(stringResource(R.string.monet_preview_title))
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
        IconPreviewModeTabs(IconPreviewMode.entries[previewView], { previewView = it.ordinal },
          modifier = Modifier.testTag("monet.preview.views"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          if (previewView != IconPreviewMode.DARK.ordinal)
            IconPalettePreview(palette, dark = false, choice = choice, modifier = Modifier.weight(1f))
          if (previewView != IconPreviewMode.LIGHT.ordinal)
            IconPalettePreview(palette, dark = true, choice = choice, modifier = Modifier.weight(1f))
        }
      }
    }
    if (visibleSection == MonetSection.OVERVIEW && draft != null) {
      TextButton(stringResource(R.string.monet_custom_discard),
        onClick = { draft = null }, enabled = !applying,
        modifier = Modifier.fillMaxWidth().testTag("monet.draft.discard"))
    }
    if (visibleSection == MonetSection.DETAILS) {
      SmallTitle(stringResource(R.string.monet_details_draft))
      if (palette != null && seed != null) {
        MonetPaletteDetails(seed, palette, choice = choice)
      } else {
        Text(stringResource(if (seed == null) R.string.monet_seed_waiting else R.string.monet_loading),
          modifier = Modifier.padding(horizontal = 16.dp), style = MiuixTheme.textStyles.body2)
      }
      SmallTitle(stringResource(R.string.monet_details_reported))
      MonetDetailCard(snapshot, readFailed)
    }
    }
  }
  AnimatedContent(section.page, transitionSpec = {
    (fadeIn(tween(180, delayMillis = 50)) + slideInHorizontally(tween(230)) { it / 12 }) togetherWith
      (fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 18 }) using null
  }, label = "monet.section") { visibleSection ->
    sectionState.SaveableStateProvider("page.${visibleSection.name}") { renderSection(visibleSection) }
  }
  Os4DetailsSheet(show = section.isSheet, onDismissRequest = onDismissPanel,
    title = stringResource(activeSheet.title), scrollContent = false,
    modifier = Modifier.testTag("monet.panel.${activeSheet.name}")) {
    sectionState.SaveableStateProvider("sheet.${activeSheet.name}") {
      Column(Modifier.fillMaxWidth().verticalScroll(sheetScrollStates[activeSheet.ordinal])) {
        renderSection(activeSheet)
      }
    }
  }
}

@Composable
private fun IconPalettePreview(
  palette: Map<String, Int>, dark: Boolean, choice: MonetCustomization, modifier: Modifier = Modifier,
) {
  val roles = remember(palette, dark, choice) {
    MonetSchemes.iconRoles(palette, dark, choice)
  } ?: return
  val foreground = Color(roles.foreground)
  val background = Color(roles.background)
  val neutral = MonetSchemes.nearestTone(palette, "neutral", if (dark) 12 else 96)?.let(::Color)
    ?: MiuixTheme.colorScheme.surface
  val content = MonetSchemes.nearestTone(palette, "neutral", if (dark) 90 else 10)?.let(::Color)
    ?: MiuixTheme.colorScheme.onSurface
  Column(modifier.clip(RoundedCornerShape(16.dp)).background(neutral).padding(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(if (dark) R.string.monet_preview_dark else R.string.monet_preview_light),
      color = content, style = MiuixTheme.textStyles.footnote1)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(background),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_launcher_monochrome), contentDescription = null,
          tint = foreground, modifier = Modifier.size(40.dp))
      }
      Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(background),
        contentAlignment = Alignment.Center) {
        Icon(MiuixIcons.Glass.Theme, contentDescription = null, tint = foreground, modifier = Modifier.size(26.dp))
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(10.dp).clip(CircleShape).background(foreground))
      Text(stringResource(R.string.monet_accent_and_neutral), color = content,
        style = MiuixTheme.textStyles.footnote2)
    }
    Text(stringResource(R.string.monet_details_contrast, roles.contrastRatio),
      modifier = Modifier.testTag("monet.contrast.${if (dark) "dark" else "light"}"),
      color = content, style = MiuixTheme.textStyles.footnote2)
  }
}

@Composable
private fun styleName(style: MonetSchemeStyle): String = stringResource(when (style) {
  MonetSchemeStyle.TONAL_SPOT -> R.string.monet_style_tonal
  MonetSchemeStyle.VIBRANT -> R.string.monet_style_vibrant
  MonetSchemeStyle.NEUTRAL -> R.string.monet_style_neutral
  MonetSchemeStyle.MONOCHROME -> R.string.monet_style_mono
  MonetSchemeStyle.EXPRESSIVE -> R.string.monet_style_expressive
  MonetSchemeStyle.RAINBOW -> R.string.monet_style_rainbow
  MonetSchemeStyle.FRUIT_SALAD -> R.string.monet_style_fruit_salad
  MonetSchemeStyle.COLOROS_INSPIRATION -> R.string.pack_style_inspiration
})

@Composable
private fun styleDescription(style: MonetSchemeStyle): String = stringResource(when (style) {
  MonetSchemeStyle.TONAL_SPOT -> R.string.monet_details_style_tonal
  MonetSchemeStyle.VIBRANT -> R.string.monet_details_style_vibrant
  MonetSchemeStyle.NEUTRAL -> R.string.monet_details_style_neutral
  MonetSchemeStyle.MONOCHROME -> R.string.monet_details_style_mono
  MonetSchemeStyle.EXPRESSIVE -> R.string.monet_details_style_expressive
  MonetSchemeStyle.RAINBOW -> R.string.monet_details_style_rainbow
  MonetSchemeStyle.FRUIT_SALAD -> R.string.monet_details_style_fruit_salad
  MonetSchemeStyle.COLOROS_INSPIRATION -> R.string.pack_inspiration_summary
})
