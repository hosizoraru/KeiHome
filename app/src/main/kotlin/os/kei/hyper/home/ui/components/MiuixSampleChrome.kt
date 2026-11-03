// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

// Adapted from compose-miuix-ui/miuix's example BlurredBar and rememberBlurBackdrop.

package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.AppTopBarBlur
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberKeiHomeBlurBackdrop(): LayerBackdrop? {
  if (!isRuntimeShaderSupported()) return null
  val surfaceColor = MiuixTheme.colorScheme.surface
  return rememberLayerBackdrop {
    drawRect(surfaceColor)
    drawContent()
  }
}

@Composable
fun BlurredBar(
  backdrop: LayerBackdrop?,
  blurEnabled: Boolean,
  content: @Composable () -> Unit,
) {
  val preferences = AppPreferences.get(LocalContext.current)
  val (styleFlow, initialStyle) = remember(preferences) {
    preferences.state.map { it.topBarBlur }.distinctUntilChanged() to preferences.state.value.topBarBlur
  }
  val style by styleFlow.collectAsStateWithLifecycle(initialValue = initialStyle)
  val blurActive = blurEnabled && backdrop != null
  Box {
    if (blurActive) {
      Box(
        modifier = Modifier.matchParentSize().then(
          if (style == AppTopBarBlur.GAUSSIAN) Modifier.textureBlur(
            backdrop = backdrop, shape = RectangleShape, blurRadius = 10f,
            colors = barBlurColors(),
          ) else Modifier.progressiveTextureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
            blurRadius = 10f,
            colors = barBlurColors(progressive = true),
          )),
      )
    }
    content()
  }
}

@Composable
private fun barBlurColors(progressive: Boolean = false): BlurColors =
  BlurDefaults.blurColors(
    blendColors = listOf(
      BlendColorEntry(
        color = MiuixTheme.colorScheme.surface.copy(if (progressive) 0.3f else 0.8f),
      ),
    ),
  )
