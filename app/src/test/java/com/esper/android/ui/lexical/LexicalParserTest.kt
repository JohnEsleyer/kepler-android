package com.esper.android.ui.lexical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block-level behaviour of the markdown parser.
 *
 * The first two groups are the regression tests for the two problems the old
 * parser had: every source line became its own paragraph, and continuation
 * lines of quotes and list items were split off into separate blocks.
 */
class LexicalParserTest {

    private fun blocks(markdown: String): List<LexicalBlockNode> =
        LexicalParser.parseToLexicalAst(markdown)

    private fun text(inlines: List<LexicalInlineNode>): String =
        inlines.joinToString("") { it.plainText }

    // ------------------------------------------------------- continuing lines

    @Test
    fun `wrapped lines stay in one paragraph`() {
        val result = blocks("This is a long line\nthat continues here.\nAnd a third.")

        assertEquals(1, result.size)
        val paragraph = result.single() as LexicalParagraphNode
        assertEquals(
            "This is a long line\nthat continues here.\nAnd a third.",
            text(paragraph.inlines)
        )
        // Two line breaks inside the paragraph, none between paragraphs.
        assertEquals(2, paragraph.inlines.count { it is LexicalLineBreakNode })
    }

    @Test
    fun `blank line splits paragraphs`() {
        val result = blocks("First paragraph.\n\nSecond paragraph.")

        assertEquals(2, result.size)
        assertTrue(result[0] is LexicalParagraphNode)
        assertTrue(result[1] is LexicalParagraphNode)
    }

    @Test
    fun `runs of blank lines keep the extra vertical space`() {
        val result = blocks("A\n\n\n\nB")

        assertEquals(3, result.size)
        assertTrue(result[0] is LexicalParagraphNode)
        val gap = result[1] as LexicalBlankLineNode
        // Three blank source lines = one separator plus two extra gaps.
        assertEquals(2, gap.count)
        assertTrue(result[2] is LexicalParagraphNode)
    }

    @Test
    fun `a single blank line does not add a blank block`() {
        val result = blocks("A\n\nB")

        assertFalse(result.any { it is LexicalBlankLineNode })
    }

    @Test
    fun `two trailing spaces are a hard break`() {
        val paragraph = blocks("line one  \nline two").single() as LexicalParagraphNode

        val breaks = paragraph.inlines.filterIsInstance<LexicalLineBreakNode>()
        assertEquals(1, breaks.size)
        assertTrue(breaks.single().isHard)
    }

    @Test
    fun `a plain newline is a soft break`() {
        val paragraph = blocks("line one\nline two").single() as LexicalParagraphNode

        assertFalse(paragraph.inlines.filterIsInstance<LexicalLineBreakNode>().single().isHard)
    }

    @Test
    fun `paragraph records the lines it came from`() {
        val paragraph = blocks("intro\nline two\nline three\n\nafter").first()

        assertEquals(0, paragraph.lineStart)
        assertEquals(2, paragraph.lineEnd)
    }

    // ------------------------------------------------------------- blockquotes

    @Test
    fun `consecutive quote lines form one block`() {
        val quote = blocks("> first\n> second\n> third").single() as LexicalQuoteNode

        assertEquals("first\nsecond\nthird", text(quote.inlines))
        assertEquals(2, quote.inlines.count { it is LexicalLineBreakNode })
    }

    @Test
    fun `a lazy line continues the quote`() {
        val quote = blocks("> first\nsecond line of quote").single() as LexicalQuoteNode

        assertEquals("first\nsecond line of quote", text(quote.inlines))
    }

    // ------------------------------------------------------------------- lists

    @Test
    fun `indented lines continue a list item`() {
        val result = blocks("- item one\n  continued text\n- item two")

        assertEquals(2, result.size)
        val first = result[0] as LexicalListItemNode
        assertEquals("item one\ncontinued text", text(first.inlines))
        assertEquals(1, first.inlines.count { it is LexicalLineBreakNode })
        assertEquals("item two", text((result[1] as LexicalListItemNode).inlines))
    }

    @Test
    fun `a lazy line continues a list item`() {
        val first = blocks("- item one\ncontinued\n- item two")
            .first() as LexicalListItemNode

        assertEquals("item one\ncontinued", text(first.inlines))
    }

    @Test
    fun `two space nesting produces incrementing levels`() {
        val items = blocks("- a\n  - b\n    - c").filterIsInstance<LexicalListItemNode>()

        assertEquals(listOf(0, 1, 2), items.map { it.indentLevel })
    }

    @Test
    fun `four space nesting produces the same levels`() {
        val items = blocks("- a\n    - b\n        - c").filterIsInstance<LexicalListItemNode>()

        assertEquals(listOf(0, 1, 2), items.map { it.indentLevel })
    }

    @Test
    fun `unordered bullets under an ordered item form a tabbed sub-list`() {
        val items = blocks("1. Recipe:\n- 2x egg\n- 1 cup flour")
            .filterIsInstance<LexicalListItemNode>()

        assertEquals(3, items.size)
        assertEquals(0, items[0].indentLevel)
        assertTrue(items[0].isOrdered)
        assertEquals(listOf(1, 1), items.drop(1).map { it.indentLevel })
        assertTrue(items.drop(1).all { !it.isOrdered })
    }

    @Test
    fun `a sibling ordered item returns to the parent level after a sub-list`() {
        val items = blocks("1. Recipe:\n- egg\n2. Method:")
            .filterIsInstance<LexicalListItemNode>()

        assertEquals(listOf(0, 1, 0), items.map { it.indentLevel })
        assertEquals(listOf(true, false, true), items.map { it.isOrdered })
    }

    @Test
    fun `ordered items resume the parent list after a mixed sub-list`() {
        val items = blocks("1. Recipe:\n- egg\n  - whisked\n2. Method:")
            .filterIsInstance<LexicalListItemNode>()

        assertEquals(listOf(0, 1, 2, 0), items.map { it.indentLevel })
        assertEquals(listOf(true, false, false, true), items.map { it.isOrdered })
    }

    @Test
    fun `nested bullets inside an ordered sub-list keep stepping in`() {
        val items = blocks("1. Recipe:\n- egg\n  - whisked\n- flour")
            .filterIsInstance<LexicalListItemNode>()

        assertEquals(listOf(0, 1, 2, 1), items.map { it.indentLevel })
    }

    @Test
    fun `a single blank line keeps loose list nesting`() {
        val items = blocks("- a\n\n  - b").filterIsInstance<LexicalListItemNode>()

        assertEquals(listOf(0, 1), items.map { it.indentLevel })
    }

    @Test
    fun `ordered list keeps its numbers`() {
        val items = blocks("3. three\n4. four").filterIsInstance<LexicalListItemNode>()

        assertTrue(items.all { it.isOrdered })
        assertEquals(listOf(3, 4), items.map { it.indexNumber })
    }

    @Test
    fun `task list items are detected`() {
        val items = blocks("- [ ] todo\n- [x] done").filterIsInstance<LexicalListItemNode>()

        assertTrue(items[0].isTask)
        assertFalse(items[0].isChecked)
        assertTrue(items[1].isChecked)
        assertEquals("todo", text(items[0].inlines))
    }

    // ------------------------------------------------------------------ colons

    @Test
    fun `table delimiter with alignment colons is recognised`() {
        val table = blocks("| Left | Center | Right | None |\n|:-----|:------:|------:|------|\n| a | b | c | d |")
            .single() as LexicalTableNode

        assertEquals(listOf("Left", "Center", "Right", "None"), table.headers)
        assertEquals(
            listOf(
                LexicalColumnAlignment.LEFT,
                LexicalColumnAlignment.CENTER,
                LexicalColumnAlignment.RIGHT,
                LexicalColumnAlignment.NONE
            ),
            table.alignments
        )
        assertEquals(listOf(listOf("a", "b", "c", "d")), table.rows)
    }

    @Test
    fun `table rows are padded to the header width`() {
        val table = blocks("| a | b |\n|---|---|\n| 1 |").single() as LexicalTableNode

        assertEquals(listOf(listOf("1", "")), table.rows)
    }

    @Test
    fun `mailto links are parsed`() {
        val paragraph = blocks("Contact [me](mailto:a@b.com) now.").single() as LexicalParagraphNode

        val link = paragraph.inlines.filterIsInstance<LexicalLinkNode>().single()
        assertEquals("me", link.text)
        assertEquals("mailto:a@b.com", link.url)
    }

    @Test
    fun `tel links are parsed`() {
        val paragraph = blocks("Call [us](tel:+12345).").single() as LexicalParagraphNode

        assertEquals("tel:+12345", paragraph.inlines.filterIsInstance<LexicalLinkNode>().single().url)
    }

    @Test
    fun `timestamp with hours minutes and seconds is parsed`() {
        val paragraph = blocks("skip to [1:02:03] please").single() as LexicalParagraphNode

        val stamp = paragraph.inlines.filterIsInstance<LexicalTimestampNode>().single()
        assertEquals(3723, stamp.seconds)
        assertEquals("1:02:03", stamp.label)
    }

    @Test
    fun `timestamp with only minutes and seconds is parsed`() {
        val paragraph = blocks("skip to [01:23] please").single() as LexicalParagraphNode

        assertEquals(83, paragraph.inlines.filterIsInstance<LexicalTimestampNode>().single().seconds)
    }

    @Test
    fun `emoji shortcode becomes an emoji node`() {
        val paragraph = blocks("great work :tada: and :smile:").single() as LexicalParagraphNode

        val emoji = paragraph.inlines.filterIsInstance<LexicalEmojiNode>()
        assertEquals(listOf("tada", "smile"), emoji.map { it.shortcode })
        assertEquals(2, emoji.size)
    }

    @Test
    fun `unknown shortcodes stay literal`() {
        val paragraph = blocks("hello :definitely_not_an_emoji: there").single() as LexicalParagraphNode

        assertTrue(paragraph.inlines.none { it is LexicalEmojiNode })
        assertTrue(text(paragraph.inlines).contains(":definitely_not_an_emoji:"))
    }

    @Test
    fun `an unknown shortcode does not block a later valid one`() {
        val paragraph = blocks(":nope: :tada:").single() as LexicalParagraphNode

        assertEquals("tada", paragraph.inlines.filterIsInstance<LexicalEmojiNode>().single().shortcode)
    }

    @Test
    fun `a clock time is not mistaken for an emoji`() {
        val paragraph = blocks("at 10:30:45 exactly").single() as LexicalParagraphNode

        assertTrue(paragraph.inlines.none { it is LexicalEmojiNode })
        assertTrue(text(paragraph.inlines).contains("10:30:45"))
    }

    @Test
    fun `colons in prose are untouched`() {
        val paragraph = blocks("Ratio 3:1, note: this is fine.").single() as LexicalParagraphNode

        assertEquals("Ratio 3:1, note: this is fine.", text(paragraph.inlines))
    }

    // -------------------------------------------------------- inline markdown

    @Test
    fun `bold italic strike code and highlight are styled`() {
        val paragraph = blocks("**b** *i* ~~s~~ `c` ==h==").single() as LexicalParagraphNode
        val styled = paragraph.inlines.filterIsInstance<LexicalTextNode>()

        assertTrue(styled.any { it.text == "b" && it.isBold })
        assertTrue(styled.any { it.text == "i" && it.isItalic })
        assertTrue(styled.any { it.text == "s" && it.isStrikethrough })
        assertTrue(styled.any { it.text == "c" && it.isCode })
        assertTrue(styled.any { it.text == "h" && it.isHighlight })
    }

    @Test
    fun `markers are not part of the rendered text`() {
        val paragraph = blocks("This is **bold** text").single() as LexicalParagraphNode

        assertEquals("This is bold text", text(paragraph.inlines))
    }

    @Test
    fun `inline math is parsed`() {
        val paragraph = blocks("value \$x^2\$ here").single() as LexicalParagraphNode

        assertEquals("x^2", paragraph.inlines.filterIsInstance<LexicalInlineMathNode>().single().latex)
    }

    // ------------------------------------------------------------ other blocks

    @Test
    fun `headings carry their level`() {
        val result = blocks("# One\n\n### Three")

        assertEquals(listOf(1, 3), result.filterIsInstance<LexicalHeadingNode>().map { it.level })
    }

    @Test
    fun `a hash without a space is not a heading`() {
        val paragraph = blocks("#hashtag").single()

        assertTrue(paragraph is LexicalParagraphNode)
    }

    @Test
    fun `horizontal rules are recognised`() {
        assertTrue(blocks("---").single() is LexicalHorizontalRuleNode)
        assertTrue(blocks("***").single() is LexicalHorizontalRuleNode)
        assertTrue(blocks("___").single() is LexicalHorizontalRuleNode)
    }

    @Test
    fun `images accept any url scheme`() {
        val image = blocks("![chart](esper://asset/42)").single() as LexicalImageNode

        assertEquals("chart", image.altText)
        assertEquals("esper://asset/42", image.imageUrl)
    }

    @Test
    fun `code blocks keep blank lines and fences`() {
        val block = blocks("```python\ndef f():\n\n    return 1\n```")
            .single() as LexicalCodeBlockNode

        assertEquals("python", block.language)
        assertEquals("def f():\n\n    return 1", block.code)
        assertEquals(0, block.lineStart)
        assertEquals(4, block.lineEnd)
    }

    @Test
    fun `bracket math blocks are recognised`() {
        val block = blocks("\\[\nE = mc^2\n\\]").single() as LexicalMathBlockNode

        assertEquals("E = mc^2", block.latex)
    }

    @Test
    fun `an empty document produces no blocks`() {
        assertTrue(blocks("").isEmpty())
    }

    @Test
    fun `a document of blank lines produces no blocks`() {
        assertTrue(blocks("\n\n\n").isEmpty())
    }

    @Test
    fun `custom markdown round trips through the parser without losing lines`() {
        val markdown = """
            # Title
            first line
            second line

            > quoted
            > more

            - [ ] task
              continued

            ```js
            const x = 1;
            ```
            ---
        """.trimIndent()

        val parsed = blocks(markdown)
        assertEquals(1, parsed.count { it is LexicalHeadingNode })
        assertEquals(1, parsed.count { it is LexicalParagraphNode })
        assertEquals(1, parsed.count { it is LexicalQuoteNode })
        assertEquals(1, parsed.count { it is LexicalListItemNode })
        assertEquals(1, parsed.count { it is LexicalCodeBlockNode })
        assertEquals(1, parsed.count { it is LexicalHorizontalRuleNode })
    }
}
