package os.kei.hyper.home.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.ui.components.Os4Motion
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MetadataPill(label: String, color: Color, modifier: Modifier = Modifier) {
  val animatedColor by animateColorAsState(
    targetValue = color,
    animationSpec = Os4Motion.colorSpring(),
    label = "metadata-pill-color",
  )
  Text(
    label,
    modifier = modifier
      .clip(CircleShape)
      .background(animatedColor.copy(alpha = 0.12f))
      .animateContentSize(Os4Motion.revealSpring())
      .padding(horizontal = 8.dp, vertical = 4.dp),
    style = MiuixTheme.textStyles.footnote2,
    color = animatedColor,
  )
}
