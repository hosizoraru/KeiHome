package os.kei.hyper.home.ui.main

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.os4Clickable
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val storageColors = listOf(Color(0xFFFFC72C), Color(0xFFFF913B), Color(0xFF44C9A1),
  Color(0xFF8593AB), Color(0xFF508EF2), Color(0xFFDF6B85), Color(0xFF26A6B6), Color(0xFFC6A755))
private val storageLabels = listOf(R.string.storage_icons, R.string.storage_builtin,
  R.string.storage_transfer, R.string.storage_temporary, R.string.storage_apple, R.string.storage_perfect,
  R.string.storage_logs, R.string.storage_imported)
private val storageDescriptions = mapOf(0 to R.string.storage_icons_info, 1 to R.string.storage_builtin_info,
  2 to R.string.storage_transfer_info, 3 to R.string.storage_temporary_info, 7 to R.string.storage_imported_info)

@Composable
internal fun CacheStorageChart(bytes: List<Long>, budget: Long, ready: Boolean, onOpenPack: (String) -> Unit,
  onOpenLogs: () -> Unit = {}) {
  val context = LocalContext.current
  var infoCategory by rememberSaveable { mutableIntStateOf(0) }
  var showingInfo by rememberSaveable { mutableStateOf(false) }
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
        val destination = when (index) { 4 -> "apple"; 5 -> "perfect"; 7 -> "library"; else -> null }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
          .then(if (index == 6) Modifier.os4Clickable(role = Role.Button, onClick = onOpenLogs)
            else if (destination != null) Modifier.os4Clickable(role = Role.Button) { onOpenPack(destination) }
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
          if (index in storageDescriptions) {
            KeiHomeIconButton(
              onClick = { infoCategory = index; showingInfo = true },
              modifier = Modifier.testTag("cache.category.info.$index"),
              minHeight = 48.dp, minWidth = 48.dp,
            ) {
              Icon(MiuixIcons.Glass.Info,
                stringResource(R.string.storage_category_info, stringResource(storageLabels[index])),
                Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
          } else if (destination != null || index == 6) Icon(MiuixIcons.Glass.ChevronForward, null,
            Modifier.size(14.dp), tint = scheme.onSurfaceVariantSummary)
        }
      }
    }
  }
  Os4DetailsSheet(showingInfo, { showingInfo = false }, title = stringResource(storageLabels[infoCategory]),
    summary = stringResource(storageDescriptions.getValue(infoCategory)),
    modifier = Modifier.testTag("cache.category.info.card")) {
    if (infoCategory == 7) Os4DialogButton(stringResource(R.string.icon_pack_library),
      { showingInfo = false; onOpenPack("library") }, Modifier.fillMaxWidth())
    Os4DialogButton(stringResource(R.string.pack_guidance_done), { showingInfo = false },
      Modifier.fillMaxWidth().testTag("cache.category.info.close"))
  }
}

@Composable
private fun Cylinder(fractions: List<State<Float>>, empty: Color, modifier: Modifier) {
  Spacer(modifier.drawWithCache {
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
    val shading = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .14f),
      Color.White.copy(alpha = .12f), Color.Transparent, Color.Black.copy(alpha = .13f)))
    onDrawBehind { clipPath(outline) {
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
      drawRect(shading)
    } }
  })
}
