package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MetadataPill(label: String, color: Color, modifier: Modifier = Modifier) {
  Text(label, modifier = modifier.clip(CircleShape).background(color.copy(alpha = 0.12f))
    .padding(horizontal = 8.dp, vertical = 4.dp),
    style = MiuixTheme.textStyles.footnote2, color = color)
}
