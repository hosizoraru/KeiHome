// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

// Adapted from compose-miuix-ui/miuix's example BlurredBar and rememberBlurBackdrop.

package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
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
  val blurActive = blurEnabled && backdrop != null
  Box {
    if (blurActive) {
      Box(
        modifier = Modifier
          .matchParentSize()
          .progressiveTextureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
            blurRadius = 10f,
            colors = barBlurColors(progressive = true),
          ),
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
