package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class SetupLayer { Header, Footer, Content, Fade }

/** The scaffold hosts overlays; the guide measures its own floating chrome. */
@Composable
internal fun OnboardingPageLayout(
  modifier: Modifier = Modifier,
  backdrop: LayerBackdrop? = null,
  header: @Composable () -> Unit,
  footer: @Composable () -> Unit,
  content: @Composable (PaddingValues) -> Unit,
) {
  val surface = MiuixTheme.colorScheme.surface
  CompositionLocalProvider(LocalKeiHomeBackdrop provides backdrop) {
    Scaffold(modifier = modifier.fillMaxSize(), containerColor = surface,
      contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
      SubcomposeLayout(Modifier.fillMaxSize().background(surface)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .clipToBounds()) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val headerPlaceable = subcompose(SetupLayer.Header) {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { header() }
        }.single().measure(loose)
        val footerPlaceable = subcompose(SetupLayer.Footer) {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) { footer() }
        }.single().measure(loose)
        val clearance = PaddingValues(
          top = headerPlaceable.height.toDp() + 12.dp,
          bottom = footerPlaceable.height.toDp() + 16.dp,
        )
        val body = subcompose(SetupLayer.Content) {
          Box(Modifier.fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            contentAlignment = Alignment.TopCenter) { content(clearance) }
        }.single().measure(constraints)
        val fade = subcompose(SetupLayer.Fade) {
          Box(Modifier.fillMaxSize().drawWithCache {
            val topHeight = (headerPlaceable.height + 24.dp.toPx()).coerceAtMost(size.height)
            val bottomHeight = (footerPlaceable.height + 40.dp.toPx()).coerceAtMost(size.height)
            val bottomStart = size.height - bottomHeight
            val topBrush = Brush.verticalGradient(
              0f to surface, .65f to surface.copy(alpha = .98f), 1f to surface.copy(alpha = 0f),
              startY = 0f, endY = topHeight,
            )
            val bottomBrush = Brush.verticalGradient(
              listOf(surface.copy(alpha = 0f), surface.copy(alpha = .6f), surface),
              startY = bottomStart, endY = size.height,
            )
            onDrawBehind {
              drawRect(topBrush, size = Size(size.width, topHeight))
              drawRect(bottomBrush, topLeft = Offset(0f, bottomStart),
                size = Size(size.width, bottomHeight))
            }
          })
        }.single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
          body.placeRelative(0, 0)
          fade.placeRelative(0, 0)
          headerPlaceable.placeRelative(0, 0)
          footerPlaceable.placeRelative(0, constraints.maxHeight - footerPlaceable.height)
        }
      }
    }
  }
}
