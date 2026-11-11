package os.kei.hyper.home.ui.main

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.data.ReleaseNotesBlock
import os.kei.hyper.home.data.ReleaseNotesInline
import os.kei.hyper.home.data.parseReleaseNotesMarkdown
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Call only from the expanded release body; disposal also releases its parsed content. */
@Composable
internal fun ReleaseNotesMarkdown(markdown: String) {
  val blocks = remember(markdown) { parseReleaseNotesMarkdown(markdown) }
  MarkdownBlocks(blocks)
}

@Composable
private fun MarkdownBlocks(
  blocks: List<ReleaseNotesBlock>,
  modifier: Modifier = Modifier,
  quoted: Boolean = false,
  tight: Boolean = false,
) {
  val colors = MiuixTheme.colorScheme
  val styles = MiuixTheme.textStyles
  val textColor = if (quoted) colors.onSurfaceSecondary else colors.onSurface
  Column(modifier, verticalArrangement = Arrangement.spacedBy(if (tight) 4.dp else 8.dp)) {
    blocks.forEach { block ->
      when (block) {
        is ReleaseNotesBlock.Paragraph -> Text(
          text = markdownText(block.content),
          style = styles.body2,
          color = textColor,
        )
        is ReleaseNotesBlock.Heading -> Text(
          text = markdownText(block.content),
          modifier = Modifier.semantics { heading() },
          style = when (block.level) {
            1 -> styles.title3
            2 -> styles.title4
            else -> styles.body1
          },
          fontWeight = FontWeight.SemiBold,
          color = textColor,
        )
        is ReleaseNotesBlock.CodeBlock -> Text(
          text = AnnotatedString(block.text),
          modifier = Modifier.fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(6.dp)).padding(8.dp),
          style = styles.body2,
          fontFamily = FontFamily.Monospace,
          color = textColor,
        )
        is ReleaseNotesBlock.Quote -> Row(
          Modifier.fillMaxWidth().height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Box(Modifier.width(2.dp).fillMaxHeight().background(colors.outline))
          MarkdownBlocks(block.blocks, Modifier.weight(1f), quoted = true, tight = tight)
        }
        is ReleaseNotesBlock.ListBlock -> {
          val measurer = rememberTextMeasurer()
          val density = LocalDensity.current
          val markerWidth = remember(block.start, block.items.size, styles.body2, density, measurer) {
            with(density) {
              measurer.measure(block.marker((block.items.size - 1).coerceAtLeast(0)),
                style = styles.body2).size.width.toDp()
            }.coerceAtLeast(20.dp)
          }
          Column(
            verticalArrangement = Arrangement.spacedBy(if (block.tight) 4.dp else 8.dp),
          ) {
            block.items.forEachIndexed { index, item ->
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  text = block.marker(index),
                  modifier = Modifier.width(markerWidth),
                  style = styles.body2,
                  color = textColor,
                  textAlign = TextAlign.End,
                )
                MarkdownBlocks(item, Modifier.weight(1f), quoted = quoted, tight = block.tight)
              }
            }
          }
        }
        ReleaseNotesBlock.Rule -> Spacer(
          Modifier.fillMaxWidth().height(1.dp).background(colors.outline),
        )
      }
    }
  }
}

@Composable
private fun markdownText(content: List<ReleaseNotesInline>): AnnotatedString {
  val codeBackground = MiuixTheme.colorScheme.surfaceContainer
  val linkColor = MiuixTheme.colorScheme.primary
  return remember(content, codeBackground, linkColor) {
    buildAnnotatedString {
      content.forEach { inline ->
        withStyle(
          SpanStyle(
            fontWeight = if (inline.strong) FontWeight.Bold else null,
            fontStyle = if (inline.emphasis) FontStyle.Italic else null,
            fontFamily = if (inline.code) FontFamily.Monospace else null,
            background = if (inline.code) codeBackground else Color.Unspecified,
          ),
        ) {
          val url = inline.url
          if (url == null || inline.text.isEmpty()) append(inline.text)
          else withLink(LinkAnnotation.Url(url, styles = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
          ))) { append(inline.text) }
        }
      }
    }
  }
}
