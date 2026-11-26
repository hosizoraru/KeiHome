package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import top.yukonga.miuix.kmp.squircle.squircleBackground
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
  listItem: Boolean = false,
) {
  val colors = MiuixTheme.colorScheme
  val styles = MiuixTheme.textStyles
  val textColor = if (quoted) colors.onSurfaceSecondary else colors.onSurface
  val spacing = when {
    tight || listItem -> 2.dp
    else -> 10.dp
  }
  val hasLeadHeading = listItem && !quoted && blocks.size > 1 &&
    (blocks.firstOrNull() as? ReleaseNotesBlock.Paragraph)?.isStrongLead() == true
  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
    blocks.forEachIndexed { index, block ->
      when (block) {
        is ReleaseNotesBlock.Paragraph -> {
          val isLead = hasLeadHeading && index == 0
          val isLeadBody = hasLeadHeading && index > 0
          Text(
            text = markdownText(block.content),
            style = styles.body2,
            fontWeight = if (isLead) FontWeight.SemiBold else null,
            color = if (isLeadBody) colors.onSurfaceSecondary else textColor,
          )
        }
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
            .squircleBackground(colors.surfaceVariant.copy(alpha = 0.6f), 8.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
          style = styles.body2,
          fontFamily = FontFamily.Monospace,
          color = textColor,
        )
        is ReleaseNotesBlock.Quote -> Row(
          Modifier.fillMaxWidth()
            .squircleBackground(colors.surfaceVariant.copy(alpha = 0.55f), 12.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Box(
            Modifier.width(3.dp).fillMaxHeight()
              .clip(CircleShape)
              .background(colors.primary.copy(alpha = 0.65f)),
          )
          MarkdownBlocks(block.blocks, Modifier.weight(1f), quoted = true, tight = true)
        }
        is ReleaseNotesBlock.ListBlock -> {
          val ordered = block.start != null
          val structuredItems = block.items.any { item ->
            item.size > 1 && (item.firstOrNull() as? ReleaseNotesBlock.Paragraph)?.isStrongLead() == true
          }
          val measurer = rememberTextMeasurer()
          val density = LocalDensity.current
          val bulletWidth = if (ordered) 0.dp else remember(block.items.size, styles.body2, density, measurer) {
            with(density) {
              measurer.measure("\u2022", style = styles.body2).size.width.toDp()
            }.coerceAtLeast(14.dp)
          }
          Column(
            verticalArrangement = Arrangement.spacedBy(when {
              block.tight -> 4.dp
              structuredItems -> 12.dp
              else -> 8.dp
            }),
          ) {
            block.items.forEachIndexed { itemIndex, item ->
              Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
              ) {
                if (ordered) {
                  val numberLabel = "${block.start.toLong() + itemIndex}"
                  Box(
                    modifier = Modifier
                      .padding(top = 1.dp)
                      .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                      .clip(CircleShape)
                      .background(colors.primary.copy(alpha = 0.12f))
                      .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                  ) {
                    Text(
                      text = numberLabel,
                      style = styles.footnote2,
                      fontWeight = FontWeight.SemiBold,
                      color = colors.primary,
                      textAlign = TextAlign.Center,
                    )
                  }
                } else {
                  Text(
                    text = block.marker(itemIndex),
                    modifier = Modifier.width(bulletWidth),
                    style = styles.body2,
                    color = colors.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                  )
                }
                MarkdownBlocks(item, Modifier.weight(1f), quoted = quoted, tight = block.tight, listItem = true)
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

private fun ReleaseNotesBlock.Paragraph.isStrongLead(): Boolean =
  content.isNotEmpty() && content.any { it.text.isNotBlank() } &&
    content.all { it.strong || it.text.isBlank() }

@Composable
private fun markdownText(content: List<ReleaseNotesInline>): AnnotatedString {
  val codeBackground = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
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
