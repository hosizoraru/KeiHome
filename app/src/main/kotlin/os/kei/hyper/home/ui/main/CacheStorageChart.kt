package os.kei.hyper.home.ui.main

import android.text.format.Formatter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.os4Clickable
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val storageColors = listOf(
  Color(0xFFFCD231), // current icons
  Color(0xFFFF963A), // builtin copy
  Color(0xFF38E191), // transfer
  Color(0xFF8D96B2), // temporary
  Color(0xFF3193FB), // Apple resources
  Color(0xFFE95C88), // Perfect icons
  Color(0xFF14CBCB), // logs
  Color(0xFFC8A654), // imported packs
)
private val storageLabels = listOf(R.string.storage_icons, R.string.storage_builtin,
  R.string.storage_transfer, R.string.storage_temporary, R.string.storage_apple, R.string.storage_perfect,
  R.string.storage_logs, R.string.storage_imported)
private val storageDescriptions = mapOf(0 to R.string.storage_icons_info, 1 to R.string.storage_builtin_info,
  2 to R.string.storage_transfer_info, 3 to R.string.storage_temporary_info, 7 to R.string.storage_imported_info)

private const val ELLIPSE_CONTROL_POINT = 0.5522848f
private const val ELLIPSE_HEIGHT_RATIO = 0.19f
private const val MIN_VISIBLE_BAND_RATIO = 0.036f

private data class StorageBand(val index: Int, val top: Float, val bottom: Float) {
  val centerY: Float get() = (top + bottom) / 2f
}

private data class StorageCylinderMetrics(
  val left: Float,
  val right: Float,
  val width: Float,
  val bodyTop: Float,
  val bodyBottom: Float,
  val ellipseHeight: Float,
  val bands: List<StorageBand>,
) {
  fun indexAt(y: Float): Int? = bands.firstOrNull { y in it.top..it.bottom }?.index
}

private enum class ScrubAxis { UNDECIDED, VERTICAL, HORIZONTAL }

@Composable
internal fun CacheStorageChart(bytes: List<Long>, budget: Long, ready: Boolean, onOpenPack: (String) -> Unit,
  onOpenLogs: () -> Unit = {}) {
  val context = LocalContext.current
  var infoCategory by rememberSaveable { mutableIntStateOf(0) }
  var showingInfo by rememberSaveable { mutableStateOf(false) }
  var selectedIndex by remember(bytes) { mutableIntStateOf(-1) }
  var rootOrigin by remember { mutableStateOf(Offset.Zero) }
  var cylinderOrigin by remember { mutableStateOf(Offset.Zero) }
  var cylinderSize by remember { mutableStateOf(IntSize.Zero) }
  val legendDotCenters = remember { mutableStateMapOf<Int, Offset>() }
  val leaderProgress = remember { Animatable(0f) }

  val total = bytes.sum()
  val graphDescription = stringResource(R.string.storage_graph_description,
    Formatter.formatShortFileSize(context, total), Formatter.formatShortFileSize(context, budget))
  val capacity = maxOf(total, budget).coerceAtLeast(1L).toDouble()
  val fractions = bytes.map {
    animateFloatAsState(if (ready) (it / capacity).toFloat() else 0f,
      animationSpec = tween(720), label = "cache storage fill")
  }
  val scheme = MiuixTheme.colorScheme
  val darkSurface = scheme.surface.luminance() < 0.5f
  val focusAlphas = List(bytes.size) { index ->
    animateFloatAsState(
      targetValue = if (selectedIndex < 0 || selectedIndex == index) 1f else if (darkSurface) 0.22f else 0.16f,
      animationSpec = tween(140),
      label = "storageFocusAlpha",
    )
  }

  LaunchedEffect(selectedIndex >= 0) {
    if (selectedIndex < 0) leaderProgress.animateTo(0f, tween(100))
    else leaderProgress.animateTo(1f, tween(150))
  }

  val targetMetrics = remember(bytes, budget, ready, cylinderSize) {
    computeStorageCylinderMetrics(bytes.map { if (ready) (it / capacity).toFloat() else 0f }, cylinderSize)
  }

  Box(Modifier.fillMaxWidth().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      Box(Modifier.weight(1f).fillMaxHeight().testTag("cache.storage.graph-region")) {
        Cylinder(
          fractions = fractions,
          focusAlphas = focusAlphas,
          empty = scheme.surfaceContainerHigh,
          darkSurface = darkSurface,
          hitMetrics = targetMetrics,
          onSelect = { selectedIndex = it },
          modifier = Modifier.fillMaxWidth().fillMaxHeight()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .onSizeChanged { cylinderSize = it }
            .onGloballyPositioned { cylinderOrigin = it.positionInRoot() }
            .semantics { contentDescription = graphDescription }
            .testTag("cache.storage.cylinder"),
        )
      }
      Column(Modifier.weight(1f).testTag("cache.storage.legend"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        bytes.forEachIndexed { index, value ->
          val destination = when (index) { 4 -> "apple"; 5 -> "perfect"; 7 -> "library"; else -> null }
          val rowAlpha = focusAlphas[index].value
          Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).alpha(rowAlpha)
            .then(if (index == 6) Modifier.os4Clickable(role = Role.Button, onClick = onOpenLogs)
              else if (destination != null) Modifier.os4Clickable(role = Role.Button) { onOpenPack(destination) }
              else Modifier)
            .padding(vertical = 3.dp).testTag("cache.category.$index"),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  Modifier.size(8.dp)
                    .onGloballyPositioned { coords ->
                      val pos = coords.positionInRoot() - rootOrigin
                      legendDotCenters[index] = pos + Offset(coords.size.width / 2f, coords.size.height / 2f)
                    }
                    .background(storageColors[index], CircleShape),
                )
                Spacer(Modifier.width(7.dp))
                Text(stringResource(storageLabels[index]), style = MiuixTheme.textStyles.body2,
                  color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
              Text(if (ready) Formatter.formatShortFileSize(context, value) else "\u2014",
                modifier = Modifier.padding(start = 15.dp),
                style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium,
                color = scheme.onSurface, maxLines = 1)
            }
            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
              if (index in 0..3) {
                Os4InfoPopup(
                  title = stringResource(storageLabels[index]),
                  summary = stringResource(storageDescriptions.getValue(index)),
                  contentDescription = stringResource(R.string.storage_category_info, stringResource(storageLabels[index])),
                  modifier = Modifier.testTag("cache.category.info.$index"),
                )
              } else if (index in storageDescriptions) {
                KeiHomeIconButton(
                  onClick = { infoCategory = index; showingInfo = true },
                  modifier = Modifier.testTag("cache.category.info.$index"),
                  minHeight = 40.dp, minWidth = 40.dp,
                ) {
                  Icon(MiuixIcons.Glass.Info,
                    stringResource(R.string.storage_category_info, stringResource(storageLabels[index])),
                    Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
              } else if (destination != null || index == 6) {
                Os4TrailingChevron()
              }
            }
          }
        }
      }
    }

    // Animated elbow leader line drawn above the gap between the cylinder and the legend dots.
    Canvas(Modifier.matchParentSize()) {
      val active = selectedIndex
      val progress = leaderProgress.value
      val band = targetMetrics.bands.firstOrNull { it.index == active } ?: return@Canvas
      val dot = legendDotCenters[active] ?: return@Canvas
      if (progress <= 0f) return@Canvas
      val cylinderOffset = cylinderOrigin - rootOrigin
      val start = Offset(cylinderOffset.x + targetMetrics.right, cylinderOffset.y + band.centerY)
      val end = Offset(dot.x - 7.dp.toPx(), dot.y)
      val elbow = Offset(x = (end.x - 18.dp.toPx()).coerceAtLeast(start.x + 8.dp.toPx()), y = end.y)
      val color = storageColors[active]
      val stroke = 1.5.dp.toPx()
      val diagonal = (progress / 0.72f).coerceIn(0f, 1f)
      val horizontal = ((progress - 0.72f) / 0.28f).coerceIn(0f, 1f)
      drawLine(color, start, lerp(start, elbow, diagonal), strokeWidth = stroke, cap = StrokeCap.Round)
      if (diagonal >= 1f) {
        drawLine(color, elbow, lerp(elbow, end, horizontal), strokeWidth = stroke, cap = StrokeCap.Round)
      }
    }
  }
  var pendingOpenLibrary by remember { mutableStateOf(false) }
  Os4DetailsSheet(showingInfo, { showingInfo = false }, title = stringResource(storageLabels[infoCategory]),
    summary = stringResource(storageDescriptions.getValue(infoCategory)),
    onDismissFinished = {
      if (pendingOpenLibrary) {
        pendingOpenLibrary = false
        onOpenPack("library")
      }
    },
    modifier = Modifier.testTag("cache.category.info.card")) {
    if (infoCategory == 7) Os4DialogButton(stringResource(R.string.icon_pack_library),
      { pendingOpenLibrary = true; showingInfo = false },
      Modifier.fillMaxWidth().testTag("cache.category.info.imported"))
    Os4DialogButton(stringResource(R.string.pack_guidance_done), { showingInfo = false },
      Modifier.fillMaxWidth().testTag("cache.category.info.close"))
  }
}

private fun computeStorageCylinderMetrics(fractions: List<Float>, size: IntSize): StorageCylinderMetrics {
  if (size.width <= 0 || size.height <= 0) {
    return StorageCylinderMetrics(0f, 0f, 0f, 0f, 0f, 0f, emptyList())
  }
  val width = (size.width * 0.90f).coerceAtLeast(1f)
  val left = (size.width - width) / 2f
  val ellipseHeight = minOf(width * ELLIPSE_HEIGHT_RATIO, size.height * 0.10f)
  val bodyTop = ellipseHeight / 2f
  val bodyBottom = (size.height - ellipseHeight / 2f).coerceAtLeast(bodyTop + 1f)
  val bodyHeight = (bodyBottom - bodyTop).coerceAtLeast(1f)

  val positive = fractions.mapIndexedNotNull { index, value ->
    val clamped = value.coerceIn(0f, 1f)
    if (clamped > 0f) index to clamped else null
  }
  if (positive.isEmpty()) {
    return StorageCylinderMetrics(left, left + width, width, bodyTop, bodyBottom, ellipseHeight, emptyList())
  }
  val rawTotal = positive.sumOf { it.second.toDouble() }.toFloat().coerceAtMost(1f)
  val usedHeight = (bodyHeight * rawTotal).coerceAtMost(bodyHeight)
  val minBand = minOf(width * MIN_VISIBLE_BAND_RATIO, usedHeight / positive.size)
  val extraHeight = (usedHeight - minBand * positive.size).coerceAtLeast(0f)
  val weightSum = positive.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-6f)

  // Stack top-to-bottom in category order, resting cleanly on bodyBottom so unused budget sits above.
  var cursor = (bodyBottom - usedHeight).coerceAtLeast(bodyTop)
  val bands = positive.mapIndexed { pos, (index, weight) ->
    val h = if (pos == positive.lastIndex) (bodyBottom - cursor).coerceAtLeast(0f)
    else minBand + extraHeight * (weight / weightSum)
    StorageBand(index = index, top = cursor, bottom = (cursor + h).coerceAtMost(bodyBottom))
      .also { cursor = it.bottom }
  }
  return StorageCylinderMetrics(left, left + width, width, bodyTop, bodyBottom, ellipseHeight, bands)
}

@Composable
private fun Cylinder(
  fractions: List<State<Float>>,
  focusAlphas: List<State<Float>>,
  empty: Color,
  darkSurface: Boolean,
  hitMetrics: StorageCylinderMetrics,
  onSelect: (Int) -> Unit,
  modifier: Modifier,
) {
  Canvas(
    modifier.pointerInput(hitMetrics) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val capAllowance = hitMetrics.ellipseHeight / 2f
        val inCylinder = hitMetrics.bands.isNotEmpty() &&
          down.position.x in hitMetrics.left..hitMetrics.right &&
          down.position.y in (hitMetrics.bodyTop - capAllowance)..(hitMetrics.bodyBottom + capAllowance)
        if (!inCylinder) return@awaitEachGesture

        hitMetrics.indexAt(down.position.y.coerceIn(hitMetrics.bodyTop, hitMetrics.bodyBottom))
          ?.let(onSelect)
        var drag = Offset.Zero
        var axis = ScrubAxis.UNDECIDED
        try {
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            drag += change.position - change.previousPosition
            if (axis == ScrubAxis.UNDECIDED && maxOf(abs(drag.x), abs(drag.y)) > viewConfiguration.touchSlop) {
              axis = if (abs(drag.y) > abs(drag.x)) ScrubAxis.VERTICAL else ScrubAxis.HORIZONTAL
            }
            when (axis) {
              ScrubAxis.VERTICAL -> {
                hitMetrics.indexAt(change.position.y.coerceIn(hitMetrics.bodyTop, hitMetrics.bodyBottom))
                  ?.let(onSelect)
                change.consume()
              }
              ScrubAxis.HORIZONTAL -> onSelect(-1)
              ScrubAxis.UNDECIDED -> Unit
            }
          }
        } finally {
          onSelect(-1)
        }
      }
    },
  ) {
    val metrics = computeStorageCylinderMetrics(
      fractions.map { it.value },
      IntSize(size.width.toInt(), size.height.toInt()),
    )
    if (metrics.width <= 0f) return@Canvas

    val leftLighten = if (darkSurface) 0.04f else 0.05f
    val rightDarken = if (darkSurface) 0.08f else 0.06f
    val capDarken = if (darkSurface) 0.09f else 0.07f

    val topOfFilled = metrics.bands.firstOrNull()?.top ?: metrics.bodyBottom
    val hasEmptyChamber = topOfFilled > metrics.bodyTop + 1f
    if (hasEmptyChamber) {
      drawCylinderBand(
        left = metrics.left, right = metrics.right, width = metrics.width,
        top = metrics.bodyTop, bottom = metrics.bodyBottom,
        ellipseHeight = metrics.ellipseHeight,
        baseColor = empty, alpha = 1f,
        leftLighten = leftLighten * 0.7f, rightDarken = rightDarken * 0.7f,
      )
      drawCylinderCap(
        left = metrics.left, right = metrics.right, width = metrics.width,
        centerY = metrics.bodyTop, ellipseHeight = metrics.ellipseHeight,
        baseColor = empty, alpha = 1f, capDarken = capDarken * 0.6f,
      )
    }

    // Draw from bottom to top so each upper segment's curved bottom arc overlaps cleanly in 3D.
    metrics.bands.asReversed().forEach { band ->
      val color = storageColors[band.index]
      val alpha = focusAlphas[band.index].value
      drawCylinderBand(
        left = metrics.left, right = metrics.right, width = metrics.width,
        top = band.top, bottom = band.bottom,
        ellipseHeight = metrics.ellipseHeight,
        baseColor = color, alpha = alpha,
        leftLighten = leftLighten, rightDarken = rightDarken,
      )
      if (band.index == metrics.bands.first().index) {
        drawCylinderCap(
          left = metrics.left, right = metrics.right, width = metrics.width,
          centerY = band.top, ellipseHeight = metrics.ellipseHeight,
          baseColor = color, alpha = alpha, capDarken = capDarken,
        )
      }
    }
  }
}

private fun DrawScope.drawCylinderBand(
  left: Float,
  right: Float,
  width: Float,
  top: Float,
  bottom: Float,
  ellipseHeight: Float,
  baseColor: Color,
  alpha: Float,
  leftLighten: Float,
  rightDarken: Float,
) {
  val curveDepth = ellipseHeight / 2f
  val centerX = (left + right) / 2f
  val radiusX = width / 2f
  val path = Path().apply {
    moveTo(left, top)
    lineTo(left, bottom)
    cubicTo(
      left, bottom + ELLIPSE_CONTROL_POINT * curveDepth,
      centerX - ELLIPSE_CONTROL_POINT * radiusX, bottom + curveDepth,
      centerX, bottom + curveDepth,
    )
    cubicTo(
      centerX + ELLIPSE_CONTROL_POINT * radiusX, bottom + curveDepth,
      right, bottom + ELLIPSE_CONTROL_POINT * curveDepth,
      right, bottom,
    )
    lineTo(right, top)
    cubicTo(
      right, top + ELLIPSE_CONTROL_POINT * curveDepth,
      centerX + ELLIPSE_CONTROL_POINT * radiusX, top + curveDepth,
      centerX, top + curveDepth,
    )
    cubicTo(
      centerX - ELLIPSE_CONTROL_POINT * radiusX, top + curveDepth,
      left, top + ELLIPSE_CONTROL_POINT * curveDepth,
      left, top,
    )
    close()
  }
  drawPath(
    path = path,
    brush = Brush.horizontalGradient(
      colors = listOf(
        lerp(baseColor, Color.White, leftLighten).copy(alpha = alpha),
        lerp(baseColor, Color.White, leftLighten * 0.55f).copy(alpha = alpha),
        baseColor.copy(alpha = alpha),
        lerp(baseColor, Color.Black, rightDarken * 0.45f).copy(alpha = alpha),
        lerp(baseColor, Color.Black, rightDarken).copy(alpha = alpha),
      ),
      startX = left,
      endX = right,
    ),
  )
}

private fun DrawScope.drawCylinderCap(
  left: Float,
  right: Float,
  width: Float,
  centerY: Float,
  ellipseHeight: Float,
  baseColor: Color,
  alpha: Float,
  capDarken: Float,
) {
  drawOval(
    brush = Brush.horizontalGradient(
      colors = listOf(
        lerp(baseColor, Color.Black, capDarken * 0.55f).copy(alpha = alpha),
        lerp(baseColor, Color.Black, capDarken * 0.72f).copy(alpha = alpha),
        lerp(baseColor, Color.Black, capDarken).copy(alpha = alpha),
      ),
      startX = left,
      endX = right,
    ),
    topLeft = Offset(left, centerY - ellipseHeight / 2f),
    size = Size(width, ellipseHeight),
  )
}
