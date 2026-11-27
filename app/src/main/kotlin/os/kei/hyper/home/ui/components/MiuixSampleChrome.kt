// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

// Adapted from compose-miuix-ui/miuix's example BlurredBar and rememberBlurBackdrop.

package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalKeiHomeBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalKeiHomeTopBarBlur = staticCompositionLocalOf { AppTopBarBlur.PROGRESSIVE }

@Composable
fun rememberKeiHomeBlurBackdrop(): LayerBackdrop? {
  val preferences = AppPreferences.get(LocalContext.current)
  val (styleFlow, initialStyle) = remember(preferences) {
    preferences.state.map { it.topBarBlur }.distinctUntilChanged() to preferences.state.value.topBarBlur
  }
  val style by styleFlow.collectAsStateWithLifecycle(initialValue = initialStyle)
  if (style == AppTopBarBlur.OFF) return null
  if (!isRuntimeShaderSupported()) return null
  return rememberLayerBackdrop(backgroundColor = MiuixTheme.colorScheme.surface)
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
  KeiHomeBarSurface(backdrop, blurEnabled, style, content)
}

@Composable
internal fun KeiHomeBarSurface(
  backdrop: LayerBackdrop?,
  blurEnabled: Boolean,
  style: AppTopBarBlur,
  content: @Composable () -> Unit,
) {
  val blurActive = blurEnabled && backdrop != null && style != AppTopBarBlur.OFF
  Box {
    if (blurActive && style == AppTopBarBlur.GAUSSIAN && backdrop != null) {
      val pipeline = rememberKeiHomeBackdropPipeline(colors = barBlurColors(), blurRadius = 10f)
      Box(
        modifier = Modifier.matchParentSize().drawBackdrop(
          backdrop = backdrop, pipeline = pipeline,
        ),
      )
    }
    CompositionLocalProvider(LocalKeiHomeBackdrop provides backdrop.takeIf { blurActive },
      LocalKeiHomeTopBarBlur provides style, content = content)
  }
}

@Composable
private fun barBlurColors(): BlurColors =
  BlurDefaults.blurColors(
    blendColors = listOf(
      BlendColorEntry(
        color = MiuixTheme.colorScheme.surface.copy(alpha = 0.8f),
      ),
    ),
  )
