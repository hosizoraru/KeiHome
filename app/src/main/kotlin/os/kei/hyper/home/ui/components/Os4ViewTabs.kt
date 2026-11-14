package os.kei.hyper.home.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRowDefaults
import top.yukonga.miuix.kmp.glass.GlassTabRow
import top.yukonga.miuix.kmp.glass.GlassTabRowDefaults
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Mutually exclusive views of the same content share the native joined track. */
@Composable
internal fun Os4ViewTabs(
  items: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Os4NativeTabs(items, selectedIndex, onSelect, modifier, joined = true)
}

/** Separate content categories retain independent pills rather than a joined view selector. */
@Composable
internal fun Os4CategoryTabs(
  items: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Os4NativeTabs(items, selectedIndex, onSelect, modifier, joined = false)
}

@Composable
private fun Os4NativeTabs(
  items: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier,
  joined: Boolean,
) {
  if (items.isEmpty()) return
  // Inline tabs are inside the page recording. Sampling that recording creates
  // a RenderNode cycle; use a leaf surface backdrop for the native glass material.
  val surface = MiuixTheme.colorScheme.surfaceContainer
  val surfaceBackdrop = remember(surface) { TabSurfaceBackdrop(surface) }
  val selected = selectedIndex.coerceIn(items.indices)
  val density = LocalDensity.current
  val measurer = rememberTextMeasurer()
  val selectedStyle = MiuixTheme.textStyles.subtitle
  val restingStyle = MiuixTheme.textStyles.body2
  val textSizes = remember(items, selectedStyle, restingStyle, density, measurer) {
    items.flatMap { label ->
      listOf(selectedStyle, restingStyle).map { style ->
        measurer.measure(label, style = style, maxLines = 1, softWrap = false).size
      }
    }
  }
  // Measure both native text styles, including font metrics and scaled line heights.
  // Reserve a physical pixel per edge for rounding of the native equal-width slots.
  val textHeight = with(density) { (textSizes.maxOf { it.height } + 2).toDp() }
  val textWidth = with(density) { (textSizes.maxOf { it.width } + 2).toDp() }
  val trackPadding = if (joined) GlassSegmentedTabRowDefaults.TrackPadding else 0.dp
  val tabGap = if (joined) GlassSegmentedTabRowDefaults.TabGap else GlassTabRowDefaults.TabGap
  val horizontalPadding = if (joined) GlassSegmentedTabRowDefaults.TabPaddingHorizontal
    else GlassTabRowDefaults.TabPaddingHorizontal
  val verticalPadding = if (joined) 0.dp else GlassTabRowDefaults.TabPaddingVertical
  val defaultHeight = if (joined) GlassSegmentedTabRowDefaults.Height else GlassTabRowDefaults.Height
  val height = maxOf(defaultHeight * density.fontScale.coerceAtLeast(1f),
    textHeight + (trackPadding + verticalPadding) * 2)
  val minimumWidth = (textWidth + horizontalPadding * 2) * items.size +
    tabGap * (items.size - 1) + trackPadding * 2
  val scroll = rememberScrollState()
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val trackWidth = if (constraints.hasBoundedWidth) maxOf(maxWidth, minimumWidth) else minimumWidth
    LaunchedEffect(selected, trackWidth, maxWidth, scroll.maxValue) {
      if (constraints.hasBoundedWidth) {
        val tabWidth = (trackWidth - trackPadding * 2 - tabGap * (items.size - 1)) / items.size
        val start = with(density) {
          (trackPadding + (tabWidth + tabGap) * selected).roundToPx()
        }
        val end = start + with(density) { tabWidth.roundToPx() }
        val viewport = with(density) { maxWidth.roundToPx() }
        val target = when {
          start < scroll.value -> start
          end > scroll.value + viewport -> end - viewport
          else -> scroll.value
        }.coerceIn(0, scroll.maxValue)
        if (target != scroll.value) scroll.animateScrollTo(target)
      }
    }
    // Keep full-size labels readable without letting a wide native track escape the page.
    Box(if (constraints.hasBoundedWidth) Modifier.fillMaxWidth().horizontalScroll(scroll) else Modifier) {
      if (joined) {
        GlassSegmentedTabRow(tabs = items, selectedIndex = selected,
          onSelect = { if (it in items.indices && it != selected) onSelect(it) },
          backdrop = surfaceBackdrop, modifier = Modifier.width(trackWidth),
          surfaceAlpha = if (LocalKeiHomeBackdrop.current == null) 0f else 1f,
          height = height, style = GlassDefaults.style(LocalKeiHomeDarkTheme.current))
      } else {
        GlassTabRow(tabs = items, selectedIndex = selected,
          onSelect = { if (it in items.indices && it != selected) onSelect(it) },
          backdrop = surfaceBackdrop.takeIf { LocalKeiHomeBackdrop.current != null },
          modifier = Modifier.width(trackWidth), height = height,
          style = GlassDefaults.style(LocalKeiHomeDarkTheme.current))
      }
    }
  }
}

private class TabSurfaceBackdrop(private val color: Color) : Backdrop {
  override val isCoordinatesDependent = false

  override fun DrawScope.drawBackdrop(
    density: Density,
    coordinates: LayoutCoordinates?,
    layerBlock: (GraphicsLayerScope.() -> Unit)?,
    downscaleFactor: Float,
  ) {
    drawRect(color)
  }
}
