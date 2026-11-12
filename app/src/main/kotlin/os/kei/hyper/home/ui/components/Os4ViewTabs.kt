package os.kei.hyper.home.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassTabRow
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun Os4ViewTabs(
  items: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (items.isEmpty()) return
  // Inline tabs are inside the page recording. Sampling that recording creates
  // a RenderNode cycle; use a leaf surface backdrop for the native glass material.
  val surface = MiuixTheme.colorScheme.surfaceContainer
  val surfaceBackdrop = remember(surface) { TabSurfaceBackdrop(surface) }
  GlassTabRow(tabs = items, selectedIndex = selectedIndex.coerceIn(items.indices),
    onSelect = { if (it in items.indices && it != selectedIndex) onSelect(it) },
    backdrop = surfaceBackdrop.takeIf { LocalKeiHomeBackdrop.current != null }, modifier = modifier,
    style = GlassDefaults.style(LocalKeiHomeDarkTheme.current))
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
