package os.kei.hyper.home.ui.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.perfect.PerfectCustomStatic
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.os4Clickable
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ImageOutline
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class StageBackdrop(val labelRes: Int) {
  GRID(R.string.perfect_custom_backdrop_grid),
  LIGHT(R.string.perfect_custom_backdrop_light),
  DARK(R.string.perfect_custom_backdrop_dark),
}

/**
 * Bounded LRU preview cache for user-authored custom static PNG pairs.
 * Eviction and clear only drop references (never recycle) because Compose may still hold ImageBitmaps.
 */
internal class PerfectCustomStaticPreviewCache(
  private val read: suspend (String) -> PerfectCustomStatic,
  private val decode: (ByteArray) -> Bitmap? = ::decodeCustomStaticPreview,
  maxBytes: Int = 4 * 1024 * 1024,
) {
  private val lock = Any()
  private var generation = 0L
  private val idVersions = HashMap<String, Long>()
  private val cache = object : LruCache<String, List<Bitmap>>(maxBytes) {
    override fun sizeOf(key: String, value: List<Bitmap>): Int =
      value.sumOf { it.allocationByteCount.coerceAtLeast(1) }.coerceAtLeast(1)
  }

  suspend fun load(id: String): List<Bitmap> {
    val (requestedGeneration, requestedIdVersion, cached) = synchronized(lock) {
      Triple(generation, idVersions[id] ?: 0L, cache.get(id))
    }
    if (cached != null) return cached
    val pair = read(id)
    val decoded = withContext(Dispatchers.Default) {
      pair.layers().mapNotNull(decode)
    }
    synchronized(lock) {
      if (generation == requestedGeneration && (idVersions[id] ?: 0L) == requestedIdVersion) {
        cache.put(id, decoded)
      }
    }
    return decoded
  }

  fun invalidate(id: String) {
    synchronized(lock) {
      idVersions[id] = (idVersions[id] ?: 0L) + 1L
      cache.remove(id)
    }
  }

  fun clear() {
    synchronized(lock) {
      generation++
      idVersions.clear()
      cache.evictAll()
    }
  }

  fun sizeBytes(): Long = synchronized(lock) { cache.size().toLong() }
}

internal fun decodeCustomStaticPreview(bytes: ByteArray): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  val sample = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / 192).coerceAtLeast(1))
  return BitmapFactory.decodeByteArray(
    bytes,
    0,
    bytes.size,
    BitmapFactory.Options().apply { inSampleSize = sample },
  )
}

@Composable
internal fun rememberCustomStaticThumbnail(
  id: String,
  revision: Long,
  cache: PerfectCustomStaticPreviewCache,
): List<ImageBitmap> {
  var layers by remember(id) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
  LaunchedEffect(id, revision, cache) {
    try {
      layers = cache.load(id).map { it.asImageBitmap() }
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
    }
  }
  return layers
}

@Composable
internal fun InteractiveLayeredStage(
  background: ImageBitmap?,
  foreground: ImageBitmap?,
  backdropMode: StageBackdrop,
  onBackdropChange: (StageBackdrop) -> Unit,
  idReady: Boolean,
  bgReady: Boolean,
  fgReady: Boolean,
) {
  val scheme = MiuixTheme.colorScheme
  var rawDrag by remember { mutableStateOf(Offset.Zero) }
  val parallax by animateOffsetAsState(
    targetValue = rawDrag,
    animationSpec = folmeSpring(damping = 0.72f, response = 0.36f),
    label = "custom-layer-parallax",
  )
  val bothLayers = background != null && foreground != null

  GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          stringResource(R.string.perfect_custom_composite),
          style = MiuixTheme.textStyles.footnote1,
          fontWeight = FontWeight.Medium,
          color = scheme.onSurfaceSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          StageBackdrop.entries.forEach { option ->
            val selected = option == backdropMode
            val chipBg by animateColorAsState(
              if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.onSurface.copy(alpha = 0.06f),
              Os4Motion.colorSpring(),
              label = "stage-bg-chip",
            )
            val chipText by animateColorAsState(
              if (selected) scheme.primary else scheme.onSurfaceSecondary,
              Os4Motion.colorSpring(),
              label = "stage-bg-text",
            )
            Box(
              Modifier.squircleBackground(chipBg, 10.dp)
                .os4Clickable(cornerRadius = 10.dp) { onBackdropChange(option) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
              Text(stringResource(option.labelRes), style = MiuixTheme.textStyles.footnote2, color = chipText)
            }
          }
        }
      }

      Box(
        Modifier.size(116.dp)
          .pointerInput(bothLayers) {
            if (!bothLayers) return@pointerInput
            detectDragGestures(
              onDragEnd = { rawDrag = Offset.Zero },
              onDragCancel = { rawDrag = Offset.Zero },
            ) { change, dragAmount ->
              change.consume()
              val maxPx = 12.dp.toPx()
              rawDrag = Offset(
                x = (rawDrag.x + dragAmount.x * 0.45f).coerceIn(-maxPx, maxPx),
                y = (rawDrag.y + dragAmount.y * 0.45f).coerceIn(-maxPx, maxPx),
              )
            }
          },
        contentAlignment = Alignment.Center,
      ) {
        StaticArtwork(
          modifier = Modifier.fillMaxSize(),
          layers = listOfNotNull(background, foreground),
          description = stringResource(R.string.perfect_custom_composite),
          backdropMode = backdropMode,
          cornerRadius = 24.dp,
          layerOffsets = if (bothLayers) {
            listOf(
              Offset(parallax.x * 0.25f, parallax.y * 0.25f),
              parallax,
            )
          } else emptyList(),
        )
      }

      Os4AnimatedVisibility(bothLayers) {
        Text(
          stringResource(R.string.perfect_custom_parallax_hint),
          style = MiuixTheme.textStyles.footnote2,
          color = scheme.primary,
          textAlign = TextAlign.Center,
        )
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        ReadinessChip(stringResource(R.string.perfect_custom_id), idReady)
        ReadinessChip("0.png", bgReady)
        ReadinessChip("1.png", fgReady)
      }
    }
  }
}

@Composable
internal fun ReadinessChip(label: String, ready: Boolean) {
  val scheme = MiuixTheme.colorScheme
  val color by animateColorAsState(
    if (ready) scheme.primary else scheme.onSurfaceSecondary,
    Os4Motion.colorSpring(),
    label = "readiness-chip-color",
  )
  Row(
    Modifier.squircleBackground(color.copy(alpha = 0.12f), 10.dp)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (ready) Icon(MiuixIcons.Glass.Ok, contentDescription = null, modifier = Modifier.size(12.dp), tint = color)
    Text(label, style = MiuixTheme.textStyles.footnote2, color = color, fontWeight = FontWeight.Medium)
  }
}

@Composable
internal fun StaticLayerCard(
  modifier: Modifier,
  title: String,
  file: String,
  bitmap: ImageBitmap?,
  loaded: Boolean,
  enabled: Boolean,
  tag: String,
  onPick: () -> Unit,
) {
  val scheme = MiuixTheme.colorScheme
  val accent by animateColorAsState(
    if (loaded) scheme.primary else scheme.onSurfaceSecondary,
    Os4Motion.colorSpring(),
    label = "layer-card-accent",
  )
  val border by animateColorAsState(
    if (loaded) scheme.primary.copy(alpha = 0.45f) else scheme.onSurface.copy(alpha = 0.12f),
    Os4Motion.colorSpring(),
    label = "layer-card-border",
  )
  GlassCard(
    modifier = modifier
      .squircleBorder(1.dp, border, 18.dp)
      .os4Clickable(enabled = enabled, role = Role.Button, cornerRadius = 18.dp, onClick = onPick)
      .testTag(tag),
    insideMargin = PaddingValues(12.dp),
  ) {
    Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(file, style = MiuixTheme.textStyles.footnote2, color = accent, fontWeight = FontWeight.Medium)
          if (loaded) Icon(MiuixIcons.Glass.Ok, contentDescription = null, modifier = Modifier.size(13.dp), tint = accent)
        }
      }
      StaticArtwork(
        modifier = Modifier.size(84.dp),
        layers = listOfNotNull(bitmap),
        description = title,
        backdropMode = StageBackdrop.GRID,
      )
      Text(
        stringResource(if (loaded) R.string.perfect_custom_replace_layer else R.string.perfect_custom_tap_to_pick),
        style = MiuixTheme.textStyles.footnote2,
        color = accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
internal fun StaticArtwork(
  modifier: Modifier,
  layers: List<ImageBitmap>,
  description: String?,
  backdropMode: StageBackdrop = StageBackdrop.GRID,
  cornerRadius: Dp = 18.dp,
  layerOffsets: List<Offset> = emptyList(),
) {
  val scheme = MiuixTheme.colorScheme
  val bg by animateColorAsState(
    when (backdropMode) {
      StageBackdrop.GRID -> scheme.surfaceContainerHigh
      StageBackdrop.LIGHT -> Color(0xFFF2F3F7)
      StageBackdrop.DARK -> Color(0xFF1E2026)
    },
    Os4Motion.colorSpring(),
    label = "static-artwork-bg",
  )
  Box(
    modifier.squircleClip(cornerRadius)
      .squircleBackground(bg, cornerRadius)
      .squircleBorder(1.dp, scheme.onSurface.copy(alpha = 0.08f), cornerRadius),
    contentAlignment = Alignment.Center,
  ) {
    if (backdropMode == StageBackdrop.GRID) {
      Canvas(Modifier.matchParentSize()) {
        val tile = 8.dp.toPx()
        val tint = scheme.onSurface.copy(alpha = 0.065f)
        for (row in 0..(size.height / tile).toInt()) {
          val y = row * tile
          val h = (size.height - y).coerceIn(0f, tile)
          if (h <= 0f) continue
          for (column in (row % 2)..(size.width / tile).toInt() step 2) {
            val x = column * tile
            val w = (size.width - x).coerceIn(0f, tile)
            if (w <= 0f) continue
            drawRect(tint, Offset(x, y), Size(w, h))
          }
        }
      }
    }
    Crossfade(targetState = layers, animationSpec = tween(180), label = "static-artwork") { activeLayers ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (activeLayers.isEmpty()) {
          Icon(
            MiuixIcons.Glass.ImageOutline,
            contentDescription = description,
            modifier = Modifier.size(28.dp),
            tint = if (backdropMode == StageBackdrop.LIGHT) Color(0xFF666B75) else scheme.onSurfaceSecondary,
          )
        } else {
          activeLayers.forEachIndexed { index, layer ->
            val shift = layerOffsets.getOrNull(index) ?: Offset.Zero
            Image(
              layer,
              contentDescription = description.takeIf { index == 0 },
              modifier = Modifier.fillMaxSize().padding(6.dp).offset {
                IntOffset(shift.x.roundToInt(), shift.y.roundToInt())
              }.graphicsLayer {
                if (layerOffsets.isNotEmpty() && index == activeLayers.lastIndex) {
                  scaleX = 1.02f
                  scaleY = 1.02f
                }
              },
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun StaticError(text: Int) {
  Text(stringResource(text), color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
}
