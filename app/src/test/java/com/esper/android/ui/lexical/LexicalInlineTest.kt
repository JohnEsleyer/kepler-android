package com.esper.android.ui.lexical

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inline scanner and the visual transformation that powers live editing.
 *
 * These tests pin down the contract that made WYSIWYG possible: what the user
 * sees is the source with markers removed, and every display offset maps back
 * to a real offset in the stored markdown.
 */
class LexicalInlineTest {

    private val colors = LexicalInlineColors(
        text = Color.White,
        muted = Color.Gray,
        code = Color.Green,
        codeBackground = Color.DarkGray,
        link = Color.Cyan,
        math = Color.Yellow,
        mathBackground = Color.Black,
        timestamp = Color.Magenta,
        timestampBackground = Color.DarkGray,
        highlightBackground = Color.Yellow
    )

    private fun transform(raw: String, caret: Int = raw.length): TransformedText =
        InlineMarkdownTransformation(caret, SpanStyle(fontSize = 14.sp), colors)
            .filter(AnnotatedString(raw))

    // ------------------------------------------------------------- tokenising

    @Test
    fun `the token stream tiles the source exactly`() {
        val raw = "a **b** [c](https://d) `e`"
        val scan = LexicalInline.scan(raw)

        assertEquals(0, scan.spans.first().rawStart)
        assertEquals(raw.length, scan.spans.last().rawEnd)
        scan.spans.zipWithNext { left, right ->
            assertEquals("gap between ${left.kind} and ${right.kind}", left.rawEnd, right.rawStart)
        }
    }

    @Test
    fun `markers are their own tokens`() {
        val scan = LexicalInline.scan("**bold**")

        assertEquals(
            listOf(LexicalInlineKind.MARKER, LexicalInlineKind.TEXT, LexicalInlineKind.MARKER),
            scan.spans.map { it.kind }
        )
        assertEquals("bold", scan.displayText)
    }

    @Test
    fun `styled runs keep their flags`() {
        val scan = LexicalInline.scan("**b** and *i* and ~~s~~")

        assertTrue(scan.spans.any { it.text == "b" && it.bold })
        assertTrue(scan.spans.any { it.text == "i" && it.italic })
        assertTrue(scan.spans.any { it.text == "s" && it.strike })
    }

    // --------------------------------------------------------- latex symbols

    @Test
    fun `inline latex arrows and operators become unicode symbols`() {
        assertEquals("\u2192", LatexSymbols.toInlineText("""\rightarrow"""))
        assertEquals("\u2264 5 \u00D7 2", LatexSymbols.toInlineText("""\leq 5 \times 2"""))
        assertEquals("IDENTIFIER", LatexSymbols.toInlineText("""\text{IDENTIFIER}"""))
    }

    @Test
    fun `rendered inline math uses the translated symbol`() {
        val inlines = LexicalInline.toNodes("""`total` $\rightarrow$ `IDENTIFIER`""")
        val rendered = buildInlineText(
            inlines = inlines,
            base = SpanStyle(fontSize = 14.sp),
            colors = colors,
            clickable = false
        )

        assertEquals("total \u2192 IDENTIFIER", rendered.text)
    }

    // --------------------------------------------------------- transformation

    @Test
    fun `markers are preserved 1 to 1 and styled with dimmed syntax color`() {
        val out = transform("**bold**", caret = 4)
        assertEquals("**bold**", out.text.text)
        assertEquals(OffsetMapping.Identity, out.offsetMapping)

        // Opening marker (0..2) and closing marker (6..8) are styled with dimmed color
        assertTrue(out.text.spanStyles.any { it.start == 0 && it.end == 2 && it.item.color == colors.muted.copy(alpha = 0.65f) })
        assertTrue(out.text.spanStyles.any { it.start == 6 && it.end == 8 && it.item.color == colors.muted.copy(alpha = 0.65f) })
        // Content (2..6) is styled with bold
        assertTrue(out.text.spanStyles.any { it.start == 2 && it.end == 6 && it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `links keep full markdown syntax with 1 to 1 mapping`() {
        val raw = "see [the docs](https://example.com:8080/x) now"
        val out = transform(raw, caret = 0)
        assertEquals(raw, out.text.text)
        assertEquals(OffsetMapping.Identity, out.offsetMapping)
    }

    @Test
    fun `inline code keeps backticks with code styling and 1 to 1 mapping`() {
        val raw = "run `pnpm dev` first"
        val out = transform(raw, caret = 19)
        assertEquals(raw, out.text.text)
        assertEquals(OffsetMapping.Identity, out.offsetMapping)
        assertTrue(out.text.spanStyles.any { it.start == 4 && it.end == 5 && it.item.color == colors.muted.copy(alpha = 0.65f) })
        assertTrue(out.text.spanStyles.any { it.start == 5 && it.end == 13 && it.item.color == colors.code })
        assertTrue(out.text.spanStyles.any { it.start == 13 && it.end == 14 && it.item.color == colors.muted.copy(alpha = 0.65f) })
    }

    @Test
    fun `emoji shortcodes retain literal text in active field`() {
        val raw = ":tada: "
        val out = transform(raw, caret = 7)
        assertEquals(raw, out.text.text)
        assertEquals(OffsetMapping.Identity, out.offsetMapping)
    }

    @Test
    fun `plain text is unchanged`() {
        val text = "just words"
        val out = transform(text, caret = 0)
        assertEquals(text, out.text.text)
        assertEquals(OffsetMapping.Identity, out.offsetMapping)
    }

    // --------------------------------------------------------- offset mapping

    @Test
    fun `display offsets map past the opening marker in rendered view`() {
        // "**bold**": in rendered view, the visible "bold" starts at raw offset 2.
        assertEquals(2, LexicalInline.displayOffsetToRaw("**bold**", 0))
        assertEquals(4, LexicalInline.displayOffsetToRaw("**bold**", 2))
        assertEquals(6, LexicalInline.displayOffsetToRaw("**bold**", 4))
    }

    @Test
    fun `offset mapping is 1 to 1 identity across formatted text`() {
        val out = transform("**bold** and `code` and [link](url)")
        val mapping = out.offsetMapping
        val textLength = out.text.text.length

        assertEquals(OffsetMapping.Identity, mapping)
        for (offset in 0..textLength) {
            assertEquals(offset, mapping.originalToTransformed(offset))
            assertEquals(offset, mapping.transformedToOriginal(offset))
        }
    }

    @Test
    fun `identity mapping for unformatted text`() {
        val mapping = transform("plain", caret = 0).offsetMapping

        for (offset in 0..5) {
            assertEquals(offset, mapping.originalToTransformed(offset))
            assertEquals(offset, mapping.transformedToOriginal(offset))
        }
    }

    // ------------------------------------------------------------ emoji picker

    @Test
    fun `suggestions filter by prefix`() {
        val suggestions = LexicalInline.suggest("ta")

        assertTrue(suggestions.any { it.first == "tada" })
        assertFalse(suggestions.any { it.first == "smile" })
    }

    @Test
    fun `an empty prefix lists the most useful emoji first`() {
        val suggestions = LexicalInline.suggest("")

        assertEquals("smile", suggestions.first().first)
    }

    @Test
    fun `the active query is detected right after a colon`() {
        assertEquals("ta", activeEmojiQuery("nice :ta", androidx.compose.ui.text.TextRange(8)))
    }

    @Test
    fun `the active query ignores a clock time`() {
        assertEquals(null, activeEmojiQuery("10:30", androidx.compose.ui.text.TextRange(5)))
    }

    @Test
    fun `the active query survives a trailing colon`() {
        // Regression: splitting a block on a colon used to leave "…:" as the
        // last character and `activeEmojiQuery` read past the end of the string.
        assertEquals(null, activeEmojiQuery("note:", androidx.compose.ui.text.TextRange(5)))
        assertEquals(null, activeEmojiQuery("", androidx.compose.ui.text.TextRange(0)))
    }

    @Test
    fun `a bare colon is not a query`() {
        assertEquals(null, activeEmojiQuery("hi :", androidx.compose.ui.text.TextRange(4)))
        assertEquals(null, activeEmojiQuery("a : b", androidx.compose.ui.text.TextRange(4)))
    }

    @Test
    fun `the active query ignores non-collapsed selections`() {
        assertEquals(
            null,
            activeEmojiQuery(
                "nice :ta",
                androidx.compose.ui.text.TextRange(5, 8)
            )
        )
    }
}
