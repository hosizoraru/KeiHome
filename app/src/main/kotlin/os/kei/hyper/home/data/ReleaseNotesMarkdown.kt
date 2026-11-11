package os.kei.hyper.home.data

import java.net.URI
import java.util.Locale
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal sealed interface ReleaseNotesBlock {
  data class Paragraph(val content: List<ReleaseNotesInline>) : ReleaseNotesBlock
  data class Heading(val level: Int, val content: List<ReleaseNotesInline>) : ReleaseNotesBlock
  data class CodeBlock(val text: String) : ReleaseNotesBlock
  data class Quote(val blocks: List<ReleaseNotesBlock>) : ReleaseNotesBlock
  data class ListBlock(
    val start: Int?,
    val tight: Boolean,
    val items: List<List<ReleaseNotesBlock>>,
  ) : ReleaseNotesBlock {
    fun marker(index: Int): String = start?.let { "${it.toLong() + index}." } ?: "\u2022"
  }
  data object Rule : ReleaseNotesBlock
}

internal data class ReleaseNotesInline(
  val text: String,
  val strong: Boolean = false,
  val emphasis: Boolean = false,
  val code: Boolean = false,
  val url: String? = null,
)

/** No shared parser/cache: the expanded composable owns the returned document. */
internal fun parseReleaseNotesMarkdown(markdown: String): List<ReleaseNotesBlock> =
  markdownBlocks(Parser.builder().build().parse(markdown))

private fun Node.children(): Sequence<Node> =
  generateSequence(firstChild) { it.next }

private fun markdownBlocks(parent: Node): List<ReleaseNotesBlock> =
  parent.children().flatMap { node ->
    val block = when (node) {
      is Paragraph -> ReleaseNotesBlock.Paragraph(markdownInlines(node))
      is Heading -> ReleaseNotesBlock.Heading(node.level, markdownInlines(node))
      is FencedCodeBlock -> ReleaseNotesBlock.CodeBlock(node.literal.removeSuffix("\n"))
      is IndentedCodeBlock -> ReleaseNotesBlock.CodeBlock(node.literal.removeSuffix("\n"))
      is BlockQuote -> ReleaseNotesBlock.Quote(markdownBlocks(node))
      is OrderedList -> ReleaseNotesBlock.ListBlock(
        node.markerStartNumber, node.isTight, node.children().map { markdownBlocks(it) }.toList(),
      )
      is BulletList -> ReleaseNotesBlock.ListBlock(
        null, node.isTight, node.children().map { markdownBlocks(it) }.toList(),
      )
      is ThematicBreak -> ReleaseNotesBlock.Rule
      is HtmlBlock -> ReleaseNotesBlock.Paragraph(listOf(ReleaseNotesInline(node.literal)))
      else -> null
    }
    if (block != null) sequenceOf(block) else markdownBlocks(node).asSequence()
  }.toList()

private fun markdownInlines(parent: Node): List<ReleaseNotesInline> = buildList {
  fun visit(node: Node, style: ReleaseNotesInline) {
    when (node) {
      is Text -> add(style.copy(text = node.literal))
      is Code -> add(style.copy(text = node.literal, code = true))
      is SoftLineBreak -> add(style.copy(text = " "))
      is HardLineBreak -> add(style.copy(text = "\n"))
      is HtmlInline -> add(style.copy(text = node.literal))
      is Image -> {
        // Never carry an image destination or clickable markup into its alternative text.
        val alt = markdownInlines(node).joinToString("") { it.text }
        add(style.copy(text = alt))
      }
      else -> {
        val nested = when (node) {
          is StrongEmphasis -> style.copy(strong = true)
          is Emphasis -> style.copy(emphasis = true)
          is Link -> style.copy(url = safeReleaseNotesUrl(node.destination))
          else -> style
        }
        node.children().forEach { visit(it, nested) }
      }
    }
  }
  parent.children().forEach { visit(it, ReleaseNotesInline("")) }
}

private fun safeReleaseNotesUrl(destination: String): String? {
  val uri = runCatching { URI(destination) }.getOrNull() ?: return null
  val allowed = when (uri.scheme?.lowercase(Locale.ROOT)) {
    "https", "http" -> !uri.rawAuthority.isNullOrBlank()
    "mailto" -> !uri.rawSchemeSpecificPart.isNullOrBlank()
    else -> false
  }
  return destination.takeIf { allowed }
}
