package os.kei.hyper.home.data

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesMarkdownTest {
  private fun ReleaseNotesBlock.text(): String = when (this) {
    is ReleaseNotesBlock.Paragraph -> content.joinToString("") { it.text }
    is ReleaseNotesBlock.Heading -> content.joinToString("") { it.text }
    is ReleaseNotesBlock.CodeBlock -> text
    is ReleaseNotesBlock.Quote -> blocks.joinToString("|") { it.text() }
    is ReleaseNotesBlock.ListBlock -> items.joinToString("|") { item ->
      item.joinToString("|") { it.text() }
    }
    ReleaseNotesBlock.Rule -> "---"
  }

  @Test fun blocksKeepSourceOrderAndCodeLiteral() {
    val blocks = parseReleaseNotesMarkdown(
      """
      ## Changes

      A paragraph.

      > A footnote.

      ```kotlin
      val text = "**literal**"
      ```

      ---

          indented *literal*
      """.trimIndent(),
    )
    assertEquals(
      listOf("Changes", "A paragraph.", "A footnote.", "val text = \"**literal**\"",
        "---", "indented *literal*"),
      blocks.map { it.text() },
    )
    assertEquals(2, (blocks[0] as ReleaseNotesBlock.Heading).level)
    assertTrue(blocks[2] is ReleaseNotesBlock.Quote)
    assertTrue(blocks[3] is ReleaseNotesBlock.CodeBlock)
    assertTrue(blocks[5] is ReleaseNotesBlock.CodeBlock)
  }

  @Test fun nestedListsKeepStartAndGenerateExactlyOneNumberPerItem() {
    val blocks = parseReleaseNotesMarkdown(
      """
      3. **First**
         - child

           7. nested
           1. next
      1. Second
      """.trimIndent(),
    )
    val list = blocks.single() as ReleaseNotesBlock.ListBlock
    assertEquals(listOf("3.", "4."), list.items.indices.map { list.marker(it) })
    assertEquals("First", list.items[0][0].text())
    assertEquals("Second", list.items[1].single().text())
    val bullets = list.items[0][1] as ReleaseNotesBlock.ListBlock
    assertEquals("\u2022", bullets.marker(0))
    val nested = bullets.items.single()[1] as ReleaseNotesBlock.ListBlock
    assertEquals(listOf("7.", "8."), nested.items.indices.map { nested.marker(it) })
    assertEquals(listOf("nested", "next"), nested.items.map { it.single().text() })
  }

  @Test fun looseListContinuationStaysInItsItemBeforeTheNextMarker() {
    val list = parseReleaseNotesMarkdown(
      "1. First\n\n   Continuation.\n\n2. Second",
    ).single() as ReleaseNotesBlock.ListBlock
    assertFalse(list.tight)
    assertEquals(listOf("First", "Continuation."), list.items[0].map { it.text() })
    assertEquals("Second", list.items[1].single().text())
  }

  @Test fun inlineNestingEscapesEntitiesAndLineBreaksAreParsedByCommonMark() {
    val paragraph = parseReleaseNotesMarkdown(
      "**Title *emphasis* `a*b`** and \\*literal\\* &amp; a\\.\nsoft  \nhard",
    ).single() as ReleaseNotesBlock.Paragraph
    assertEquals("Title emphasis a*b and *literal* & a. soft\nhard", paragraph.text())
    assertTrue(paragraph.content.first { it.text == "emphasis" }.let { it.strong && it.emphasis })
    assertTrue(paragraph.content.first { it.text == "a*b" }.let { it.strong && it.code })
    assertFalse(paragraph.content.first { "*literal*" in it.text }.emphasis)
  }

  @Test fun linksAreNativeProtocolCandidatesAndImagesOnlyCarryAlternativeText() {
    val paragraph = parseReleaseNotesMarkdown(
      "[web](https://example.com) [http](http://example.com) [mail](mailto:a@example.com) " +
        "[bad](javascript:alert) [local](file:///tmp/a) [relative](/help) " +
        "![**Image** alt](https://example.com/image.png)",
    ).single() as ReleaseNotesBlock.Paragraph
    assertEquals(
      listOf("https://example.com", "http://example.com", "mailto:a@example.com"),
      paragraph.content.mapNotNull { it.url },
    )
    assertEquals("web http mail bad local relative Image alt", paragraph.text())
    assertFalse(paragraph.content.last().strong)
    assertNull(paragraph.content.last().url)
  }

  @Test fun htmlRemainsInertLiteralTextAndEmptyInputHasNoBlocks() {
    val blocks = parseReleaseNotesMarkdown("<script>alert('x')</script>\n\nA <b>word</b>.")
    assertEquals("<script>alert('x')</script>", blocks[0].text())
    assertEquals("A <b>word</b>.", blocks[1].text())
    assertTrue((blocks[1] as ReleaseNotesBlock.Paragraph).content.all { it.url == null })
    assertTrue(parseReleaseNotesMarkdown("").isEmpty())
  }
}
