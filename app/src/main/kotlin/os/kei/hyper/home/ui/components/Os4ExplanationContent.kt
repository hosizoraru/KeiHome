package os.kei.hyper.home.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class Os4InfoPresentation {
  /** Morphing OS4 Glass popup for concise/medium tips; scrollable OS4 Glass sheet for long guides. */
  Auto,
  Popup,
  Sheet,
}

private const val SHEET_VISUAL_UNITS_THRESHOLD = 200
private const val SHEET_PARAGRAPH_BREAK_WEIGHT = 36
private const val SHEET_MULTI_PARAGRAPH_MIN_UNITS = 160
private const val SHEET_DENSE_POINTS_MIN_UNITS = 175
private const val COHESIVE_TWO_SENTENCE_MAX_UNITS = 130
private val LATIN_ABBREVIATIONS = setOf("e.g", "i.e", "etc", "vs", "dr", "mr", "ms", "no")

/**
 * Estimates horizontal reading weight in Latin-character equivalents: CJK/fullwidth glyphs take
 * roughly 2× the horizontal width of Latin characters, keeping popup-vs-sheet decisions balanced
 * across Chinese, Japanese, and English locales.
 */
internal fun explanationVisualUnits(text: String): Int {
  val normalized = text.replace("\r\n", "\n").trim()
  if (normalized.isEmpty()) return 0
  var units = 0
  var previousWasSpace = false
  var newlineRun = 0
  for (ch in normalized) {
    when {
      ch == '\n' -> {
        newlineRun++
        previousWasSpace = true
      }
      ch.isWhitespace() -> {
        if (newlineRun >= 2) units += SHEET_PARAGRAPH_BREAK_WEIGHT
        else if (newlineRun == 1) units += 18
        newlineRun = 0
        if (!previousWasSpace) {
          units += 1
          previousWasSpace = true
        }
      }
      else -> {
        if (newlineRun >= 2) units += SHEET_PARAGRAPH_BREAK_WEIGHT
        else if (newlineRun == 1) units += 18
        newlineRun = 0
        previousWasSpace = false
        units += if (isWideReadingGlyph(ch)) 2 else 1
      }
    }
  }
  return units
}

internal fun shouldUseSheetForExplanation(
  explanation: String,
  presentation: Os4InfoPresentation,
): Boolean = when (presentation) {
  Os4InfoPresentation.Popup -> false
  Os4InfoPresentation.Sheet -> true
  Os4InfoPresentation.Auto -> {
    val normalized = explanation.replace("\r\n", "\n").trim()
    val explicitParagraphs = normalized.split(Regex("\\n\\s*\\n+"))
      .count { it.isNotBlank() }
    val visualUnits = explanationVisualUnits(normalized)
    val parsedPoints = parseOs4ExplanationItems(normalized)
    visualUnits > SHEET_VISUAL_UNITS_THRESHOLD ||
      (explicitParagraphs >= 3 && visualUnits > SHEET_MULTI_PARAGRAPH_MIN_UNITS) ||
      (parsedPoints.size >= 4 && visualUnits > SHEET_DENSE_POINTS_MIN_UNITS)
  }
}

/**
 * Splits dense explanation copy into clean, scannable reading points across CJK and Latin locales:
 * - Preserves explicit paragraph/line breaks.
 * - Keeps brief 1–2 sentence tips as one cohesive paragraph instead of over-bulleting short hints.
 * - Protects decimals/versions (`18.01.07`), package names (`com.miui.home`), and abbreviations (`e.g.`).
 * - Merges tiny trailing/leading stubs (`默认关闭。`) into their neighbor so bullets stay balanced.
 */
internal fun parseOs4ExplanationItems(text: String): List<String> {
  val normalized = text.replace("\r\n", "\n").trim()
  if (normalized.isEmpty()) return emptyList()
  val explicitBlocks = normalized.split(Regex("\\n+"))
    .map { it.trim() }
    .filter { it.isNotEmpty() }
  if (explicitBlocks.size == 1) {
    val singleBlock = explicitBlocks.first()
    val sentences = splitExplanationSentences(singleBlock)
    if (sentences.size <= 1) return sentences
    if (sentences.size == 2 && explanationVisualUnits(singleBlock) <= COHESIVE_TWO_SENTENCE_MAX_UNITS) {
      return listOf(singleBlock)
    }
    return mergeShortStubs(sentences)
  }
  val rawPoints = mutableListOf<String>()
  for (block in explicitBlocks) {
    if (explanationVisualUnits(block) <= 180) {
      rawPoints += block
    } else {
      rawPoints += splitExplanationSentences(block)
    }
  }
  return mergeShortStubs(rawPoints)
}

private fun mergeShortStubs(rawSentences: List<String>): List<String> {
  if (rawSentences.size <= 1) return rawSentences
  val working = rawSentences.toMutableList()
  val merged = mutableListOf<String>()
  var index = 0
  while (index < working.size) {
    val current = working[index]
    val isShortStub = isUltraShortStub(current)
    if (isShortStub && merged.isEmpty() && index + 1 < working.size) {
      working[index + 1] = joinSentences(current, working[index + 1])
    } else if (isShortStub && merged.isNotEmpty()) {
      merged[merged.lastIndex] = joinSentences(merged.last(), current)
    } else {
      merged += current
    }
    index++
  }
  return merged
}

private fun splitExplanationSentences(block: String): List<String> {
  val results = mutableListOf<String>()
  var start = 0
  var i = 0
  while (i < block.length) {
    val ch = block[i]
    val isCjkEnd = ch == '。' || ch == '！' || ch == '？'
    val isLatinEnd = (ch == '.' || ch == '!' || ch == '?') &&
      i + 1 < block.length &&
      block[i + 1].isWhitespace() &&
      !isProtectedLatinPeriod(block, i)
    if (isCjkEnd || isLatinEnd) {
      val end = i + 1
      val piece = block.substring(start, end).trim()
      if (piece.isNotEmpty()) results += piece
      i = end
      while (i < block.length && block[i].isWhitespace()) i++
      start = i
    } else {
      i++
    }
  }
  if (start < block.length) {
    val tail = block.substring(start).trim()
    if (tail.isNotEmpty()) results += tail
  }
  return if (results.isEmpty()) listOf(block) else results
}

private fun isProtectedLatinPeriod(text: String, periodIndex: Int): Boolean {
  if (text[periodIndex] != '.') return false
  if (periodIndex > 0 && text[periodIndex - 1].isDigit()) return true
  var tokenStart = periodIndex
  while (tokenStart > 0 && (text[tokenStart - 1].isLetterOrDigit() || text[tokenStart - 1] == '.')) {
    tokenStart--
  }
  val token = text.substring(tokenStart, periodIndex).lowercase()
  if (token in LATIN_ABBREVIATIONS) return true
  var next = periodIndex + 1
  while (next < text.length && text[next].isWhitespace()) next++
  if (next < text.length && text[next].isLowerCase()) return true
  return false
}

private fun isWideReadingGlyph(ch: Char): Boolean {
  val script = Character.UnicodeScript.of(ch.code)
  if (script == Character.UnicodeScript.HAN ||
    script == Character.UnicodeScript.HIRAGANA ||
    script == Character.UnicodeScript.KATAKANA ||
    script == Character.UnicodeScript.HANGUL
  ) return true
  return ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF
}

private fun isUltraShortStub(sentence: String): Boolean {
  val hasWide = sentence.any(::isWideReadingGlyph)
  return if (hasWide) sentence.length <= 7 else sentence.length <= 16
}

private fun joinSentences(first: String, second: String): String {
  val endsCjk = first.lastOrNull()?.let { it == '。' || it == '！' || it == '？' } == true
  return if (endsCjk) "$first$second" else "$first $second"
}

private fun TextStyle.withEditorialLineHeight(multiplier: Float): TextStyle {
  val resolvedLineHeight: TextUnit = if (fontSize.isSpecified) {
    (fontSize.value * multiplier).sp
  } else {
    lineHeight
  }
  return copy(
    lineHeight = resolvedLineHeight,
    lineBreak = LineBreak.Paragraph,
    lineHeightStyle = LineHeightStyle(
      alignment = LineHeightStyle.Alignment.Center,
      trim = LineHeightStyle.Trim.None,
    ),
  )
}

/**
 * Editorial paragraph / bullet-point renderer shared by OS4 Glass popups and explanation sheets.
 *
 * Bullet markers compute their top offset from the actual resolved [TextStyle.lineHeight] in
 * [LocalDensity] so they stay optically centered on the first line across all font scales.
 */
@Composable
internal fun Os4ExplanationPoints(
  explanation: String,
  modifier: Modifier = Modifier,
  baseStyle: TextStyle = MiuixTheme.textStyles.body2,
  lineHeightMultiplier: Float = 1.54f,
  itemSpacing: Dp = 8.dp,
  dotSize: Dp = 4.5.dp,
  leadingSlotWidth: Dp? = null,
  textAlpha: Float = 0.86f,
  summaryTag: String? = null,
) {
  val items = remember(explanation) { parseOs4ExplanationItems(explanation) }
  Os4ExplanationPointList(
    items = items,
    fallbackText = explanation,
    modifier = modifier,
    baseStyle = baseStyle,
    lineHeightMultiplier = lineHeightMultiplier,
    itemSpacing = itemSpacing,
    dotSize = dotSize,
    leadingSlotWidth = leadingSlotWidth,
    textAlpha = textAlpha,
    summaryTag = summaryTag,
  )
}

@Composable
internal fun Os4ExplanationPointList(
  items: List<String>,
  modifier: Modifier = Modifier,
  fallbackText: String = "",
  baseStyle: TextStyle = MiuixTheme.textStyles.body2,
  lineHeightMultiplier: Float = 1.54f,
  itemSpacing: Dp = 10.dp,
  dotSize: Dp = 5.dp,
  leadingSlotWidth: Dp? = null,
  textAlpha: Float = 0.88f,
  summaryTag: String? = null,
) {
  val cleaned = remember(items) { items.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
  val bodyStyle = baseStyle.withEditorialLineHeight(lineHeightMultiplier)
  val bodyColor = MiuixTheme.colorScheme.onSurface.copy(alpha = textAlpha)
  val dotColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.78f)
  val density = LocalDensity.current
  val dotTopOffset = remember(bodyStyle.lineHeight, dotSize, density.density, density.fontScale) {
    if (bodyStyle.lineHeight.isSpecified) {
      with(density) { ((bodyStyle.lineHeight.toDp() - dotSize) / 2f).coerceAtLeast(0.dp) }
    } else {
      7.dp
    }
  }

  if (cleaned.size <= 1) {
    Text(
      text = cleaned.firstOrNull() ?: fallbackText,
      style = bodyStyle,
      color = bodyColor,
      modifier = modifier
        .fillMaxWidth()
        .then(if (summaryTag != null) Modifier.testTag(summaryTag) else Modifier),
    )
  } else {
    Column(
      modifier = modifier
        .fillMaxWidth()
        .then(if (summaryTag != null) Modifier.testTag(summaryTag) else Modifier),
      verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
      cleaned.forEach { item ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Box(
            modifier = Modifier
              .then(if (leadingSlotWidth != null) Modifier.width(leadingSlotWidth) else Modifier)
              .padding(top = dotTopOffset),
            contentAlignment = Alignment.Center,
          ) {
            Box(
              modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor),
            )
          }
          Text(
            text = item,
            style = bodyStyle,
            color = bodyColor,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

/**
 * Structured reading layout for long explanations inside [Os4DetailsSheet]: wraps scannable
 * points inside a translucent [GlassCard], aligns the lead summary icon and bullet indicators
 * on a shared leading optical rail, and provides a clear primary dismiss button.
 */
@Composable
internal fun Os4ExplanationSheetBody(
  explanation: String,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  leadSummary: String? = null,
  dismissLabel: String = stringResource(R.string.pack_guidance_done),
  showDismissButton: Boolean = true,
  scrollState: ScrollState? = null,
) {
  val items = remember(explanation) { parseOs4ExplanationItems(explanation) }
  Os4ExplanationSheetBody(
    items = items,
    fallbackText = explanation,
    onDismiss = onDismiss,
    modifier = modifier,
    leadSummary = leadSummary,
    dismissLabel = dismissLabel,
    showDismissButton = showDismissButton,
    scrollState = scrollState,
  )
}

@Composable
internal fun Os4ExplanationSheetBody(
  items: List<String>,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  fallbackText: String = "",
  leadSummary: String? = null,
  dismissLabel: String = stringResource(R.string.pack_guidance_done),
  showDismissButton: Boolean = true,
  scrollState: ScrollState? = null,
) {
  val trimmedLead = leadSummary?.trim()?.takeIf { it.isNotEmpty() }
  val showLead = trimmedLead != null &&
    (items.size != 1 || trimmedLead != items.first().trim()) &&
    trimmedLead != fallbackText.trim()
  val leadingRailWidth = 18.dp
  val leadStyle = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium)
    .withEditorialLineHeight(1.45f)
  val cardMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).dp
  val effectiveScroll = scrollState ?: rememberScrollState()

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    GlassCard(
      modifier = Modifier.fillMaxWidth().heightIn(max = cardMaxHeight),
      cornerRadius = 20.dp,
      insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
      Column(Modifier.fillMaxWidth().verticalScroll(effectiveScroll)) {
        if (showLead) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier.width(leadingRailWidth),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = MiuixIcons.Glass.Info,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
              )
            }
            Text(
              text = trimmedLead,
              style = leadStyle,
              color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.92f),
              modifier = Modifier.weight(1f),
            )
          }
          HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f),
          )
        }
        Os4ExplanationPointList(
          items = items,
          fallbackText = fallbackText,
          baseStyle = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
          lineHeightMultiplier = 1.54f,
          itemSpacing = 12.dp,
          dotSize = 5.dp,
          leadingSlotWidth = if (showLead) leadingRailWidth else null,
          textAlpha = 0.88f,
        )
      }
    }
    if (showDismissButton) {
      Os4DialogButton(
        text = dismissLabel,
        onClick = onDismiss,
        role = GlassDialogButtonRole.Primary,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}
