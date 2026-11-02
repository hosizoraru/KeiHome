// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package os.kei.hyper.home.ui.components.about

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.floor

// Adapted from the MIUIX sample's BgEffectBackground; its draw node owns frame updates.
@Composable
internal fun AboutBackground(content: @Composable BoxScope.() -> Unit) {
  val dark = LocalKeiHomeDarkTheme.current
  val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
  val playing = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && ValueAnimator.areAnimatorsEnabled()
  val shaderSupported = remember { isRuntimeShaderSupported() }
  val deviceType = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) DeviceType.PAD else DeviceType.PHONE
  val preset = remember(deviceType, dark) { BgEffectConfig.get(deviceType, dark, isOs3 = true) }
  val stage = remember { Animatable(0f) }
  LaunchedEffect(playing, preset) {
    if (!playing || !shaderSupported) return@LaunchedEffect
    var target = floor(stage.value) + 1f
    while (isActive) {
      delay((preset.colorInterpPeriod * 500).toLong())
      stage.animateTo(target, spring(dampingRatio = 0.9f, stiffness = 35f))
      target += 1f
    }
  }
  val surface = MiuixTheme.colorScheme.surface
  Box(Modifier.fillMaxSize()) {
    if (shaderSupported) {
      val painter = remember { BgEffectPainter(isOs3 = true) }
      Spacer(Modifier.fillMaxSize().bgEffectDraw(painter, preset, deviceType, dark, surface,
        effectBackground = true, isFullSize = true, playing = playing, colorStage = { stage.value }, alpha = { 1f }))
    } else {
      val colors = if (dark) listOf(Color(0xff102b58), Color(0xff281737), Color(0xff07152f))
        else listOf(Color(0xffd7e9ff), Color(0xffffe4eb), Color(0xffe5e8ff))
      Spacer(Modifier.fillMaxSize().background(Brush.linearGradient(colors)))
    }
    content()
  }
}
