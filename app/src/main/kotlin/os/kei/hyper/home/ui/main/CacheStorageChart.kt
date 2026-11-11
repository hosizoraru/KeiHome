package os.kei.hyper.home.ui.main

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val storageColors = listOf(Color(0xFFFFC72C), Color(0xFFFF913B), Color(0xFF44C9A1),
  Color(0xFF8593AB), Color(0xFF508EF2), Color(0xFFDF6B85))
private val storageLabels = listOf(R.string.storage_icons, R.string.storage_builtin,
  R.string.storage_transfer, R.string.storage_temporary, R.string.storage_apple, R.string.storage_perfect)

@Composable
internal fun CacheStorageChart(bytes: List<Long>, budget: Long, ready: Boolean, onOpenPack: (String) -> Unit) {
  val context = LocalContext.current
  val total = bytes.sum()
  val graphDescription = stringResource(R.string.storage_graph_description,
    Formatter.formatShortFileSize(context, total), Formatter.formatShortFileSize(context, budget))
  val capacity = maxOf(total, budget).coerceAtLeast(1L).toDouble()
  val fractions = bytes.map { animateFloatAsState(if (ready) (it / capacity).toFloat() else 0f,
    animationSpec = tween(800), label = "cache storage fill") }
  val scheme = MiuixTheme.colorScheme
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(32.dp)) {
    Box(Modifier.weight(1f).fillMaxHeight().testTag("cache.storage.graph-region")) {
      Cylinder(fractions, scheme.surfaceContainerHigh, Modifier.fillMaxWidth().fillMaxHeight()
      .padding(horizontal = 12.dp, vertical = 8.dp).semantics {
        contentDescription = graphDescription
      }.testTag("cache.storage.cylinder"))
    }
    Column(Modifier.weight(1f).testTag("cache.storage.legend"),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      bytes.forEachIndexed { index, value ->
        val destination = when (index) { 4 -> "apple"; 5 -> "perfect"; else -> null }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
          .then(if (destination != null) Modifier.clickable(role = Role.Button) { onOpenPack(destination) }
            else Modifier)
          .padding(vertical = 3.dp).testTag("cache.category.$index"),
          verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(8.dp).background(storageColors[index], CircleShape))
          Spacer(Modifier.width(8.dp))
          Column(Modifier.weight(1f)) {
            Text(stringResource(storageLabels[index]), style = MiuixTheme.textStyles.footnote1,
              color = scheme.onSurfaceVariantSummary)
            Text(if (ready) Formatter.formatShortFileSize(context, value) else "\u2014",
              style = MiuixTheme.textStyles.subtitle)
          }
          if (destination != null) Icon(MiuixIcons.Basic.ArrowRight, null,
            Modifier.size(14.dp), tint = scheme.onSurfaceVariantSummary)
        }
      }
    }
  }
}

@Composable
private fun Cylinder(fractions: List<State<Float>>, empty: Color, modifier: Modifier) {
  Canvas(modifier) {
    val left = size.width * .04f
    val width = size.width * .92f
    val cap = minOf(width * .26f, size.height * .1f)
    val bottom = size.height - cap / 2
    val body = size.height - cap
    val outline = Path().apply {
      addRect(Rect(left, cap / 2, left + width, bottom))
      addOval(Rect(left, 0f, left + width, cap))
      addOval(Rect(left, size.height - cap, left + width, size.height))
    }
    clipPath(outline) {
      drawRect(empty)
      drawOval(Color.White.copy(alpha = .05f), Offset(left, 0f), Size(width, cap))
      var cursor = bottom
      // Read animation state in Draw so the legend does not recompose per frame.
      fractions.indices.reversed().forEach { index ->
        val amount = fractions[index].value.coerceIn(0f, 1f)
        if (amount > 0f) {
          val top = cursor - body * amount
          val end = if (cursor == bottom) size.height else cursor
          drawRect(storageColors[index], Offset(left, top), Size(width, end - top))
          drawOval(storageColors[index], Offset(left, top - cap / 2), Size(width, cap))
          cursor = top
        }
      }
      drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .14f),
        Color.White.copy(alpha = .12f), Color.Transparent, Color.Black.copy(alpha = .13f))))
    }
  }
}
