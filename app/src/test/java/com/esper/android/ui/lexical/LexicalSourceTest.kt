package com.esper.android.ui.lexical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Reading and writing one block in place.
 *
 * These are the tests that keep live WYSIWYG editing from corrupting the
 * document: every edit must round-trip through the exact same markdown shape.
 */
class LexicalSourceTest {

    private fun blockOf(markdown: String, index: Int = 0): LexicalBlockNode =
        LexicalParser.parseToLexicalAst(markdown)[index]

    /** The empty paragraph the editor keeps at the end of a document. */
    private fun tailParagraph(markdown: String) = LexicalParagraphNode(
        key = "p@tail",
        inlines = emptyList(),
        lineStart = markdown.split('\n').size,
        lineEnd = markdown.split('\n').size
    )

    // ------------------------------------------------------------- reading

    @Test
    fun `heading text excludes the marker`() {
        val markdown = "## Orbital mechanics"
        assertEquals(
            "Orbital mechanics",
            LexicalSource.editableText(markdown, blockOf(markdown))
        )
    }

    @Test
    fun `quote text excludes every chevron`() {
        val markdown = "> first\n> second"
        assertEquals("first\nsecond", LexicalSource.editableText(markdown, blockOf(markdown)))
    }

    @Test
    fun `list item text excludes marker and continuation indent`() {
        val markdown = "- item one\n  continued"
        assertEquals("item one\ncontinued", LexicalSource.editableText(markdown, blockOf(markdown)))
    }

    @Test
    fun `task item text excludes the checkbox`() {
        val markdown = "- [x] done"
        assertEquals("done", LexicalSource.editableText(markdown, blockOf(markdown)))
    }

    @Test
    fun `code block text is the body without fences`() {
        val markdown = "```cpp\nint x = 1;\n```"
        assertEquals("int x = 1;", LexicalSource.editableText(markdown, blockOf(markdown)))
    }

    @Test
    fun `math block text is the latex without delimiters`() {
        val markdown = "$$\nE = mc^2\n$$"
        assertEquals("E = mc^2", LexicalSource.editableText(markdown, blockOf(markdown)))
    }

    // ------------------------------------------------------------- writing

    @Test
    fun `editing a paragraph leaves the rest of the document alone`() {
        val markdown = "# Title\n\nold body\n\n---"
        val paragraph = LexicalParser.parseToLexicalAst(markdown)
            .filterIsInstance<LexicalParagraphNode>()
            .single()

        val updated = LexicalSource.withEditableText(markdown, paragraph, "new body")

        assertEquals("# Title\n\nnew body\n\n---", updated)
    }

    @Test
    fun `editing a heading restores its marker`() {
        val markdown = "## Old"
        val updated = LexicalSource.withEditableText(markdown, blockOf(markdown), "New")

        assertEquals("## New", updated)
    }

    @Test
    fun `editing a quote restores every chevron`() {
        val markdown = "> one\n> two"
        val updated = LexicalSource.withEditableText(markdown, blockOf(markdown), "a\nb")

        assertEquals("> a\n> b", updated)
    }

    @Test
    fun `editing a list item keeps its marker and continuation indent`() {
        val markdown = "- one\n  two"
        val block = blockOf(markdown) as LexicalListItemNode
        val updated = LexicalSource.withEditableText(markdown, block, "alpha\nbeta")

        assertEquals("- alpha\n  beta", updated)
    }

    @Test
    fun `editing a task item keeps its state`() {
        val markdown = "- [x] done"
        val updated = LexicalSource.withEditableText(markdown, blockOf(markdown), "still done")

        assertEquals("- [x] still done", updated)
    }

    @Test
    fun `editing a code block keeps the fences and language`() {
        val markdown = "```js\nconst x = 1;\n```"
        val updated = LexicalSource.withEditableText(markdown, blockOf(markdown), "const y = 2;\nconst z = 3;")

        assertEquals("```js\nconst y = 2;\nconst z = 3;\n```", updated)
    }

    @Test
    fun `editing a single line math block keeps the delimiters`() {
        val markdown = "\$\$x^2\$\$"
        val updated = LexicalSource.withEditableText(markdown, blockOf(markdown), "y^2")

        assertEquals("\$\$y^2\$\$", updated)
    }

    @Test
    fun `editing the synthetic trailing paragraph appends a line`() {
        val markdown = "- a"
        val line = markdown.split('\n').size
        val synthetic = LexicalParagraphNode(
            key = "p@tail",
            inlines = emptyList(),
            lineStart = line,
            lineEnd = line
        )

        val updated = LexicalSource.withEditableText(markdown, synthetic, "after the list")

        assertEquals("- a\n\nafter the list", updated)
    }

    @Test
    fun `removing a block removes its fences too`() {
        val markdown = "intro\n\n```js\nx\n```\n\noutro"
        val code = LexicalParser.parseToLexicalAst(markdown)
            .filterIsInstance<LexicalCodeBlockNode>()
            .single()

        assertEquals("intro\n\n\noutro", LexicalSource.removeBlock(markdown, code))
    }

    @Test
    fun `toggling a task rewrites only the checkbox`() {
        val markdown = "- [ ] todo"
        val block = blockOf(markdown) as LexicalListItemNode

        assertEquals("- [x] todo", LexicalSource.setTaskChecked(markdown, block, true))
    }

    // -------------------------------------------------------------- splitting

    @Test
    fun `splitting a paragraph creates a second paragraph`() {
        val markdown = "hello world"
        val block = blockOf(markdown)

        val result = LexicalSource.splitBlock(markdown, block, "hello world", 5)
        assertNotNull(result)
        // A paragraph break needs a blank line between the two paragraphs.
        assertEquals("hello\n\n world", result!!.markdown)
        assertEquals(2, result.focusLine)

        val parsed = LexicalParser.parseToLexicalAst(result.markdown)
        assertEquals(2, parsed.count { it is LexicalParagraphNode })
    }

    @Test
    fun `splitting a heading produces a plain paragraph`() {
        val markdown = "## Title"
        val block = blockOf(markdown)

        val result = LexicalSource.splitBlock(markdown, block, "Title", 3)!!
        val parsed = LexicalParser.parseToLexicalAst(result.markdown)

        assertEquals(1, parsed.count { it is LexicalHeadingNode })
        assertEquals(1, parsed.count { it is LexicalParagraphNode })
    }

    @Test
    fun `splitting a list item continues the list`() {
        val markdown = "- one"
        val block = blockOf(markdown)

        val result = LexicalSource.splitBlock(markdown, block, "one", 3)!!
        val items = LexicalParser.parseToLexicalAst(result.markdown)
            .filterIsInstance<LexicalListItemNode>()

        assertEquals(2, items.size)
        assertEquals("one", items[0].plainText)
    }

    @Test
    fun `enter on an empty list item leaves the list`() {
        val markdown = "- one\n- "
        val block = LexicalParser.parseToLexicalAst(markdown)
            .filterIsInstance<LexicalListItemNode>()
            .last()

        val result = LexicalSource.splitBlock(markdown, block, "", 0)!!
        val parsed = LexicalParser.parseToLexicalAst(result.markdown)

        assertEquals(1, parsed.count { it is LexicalListItemNode })
    }

    @Test
    fun `code blocks absorb the newline instead of splitting`() {
        val markdown = "```js\nx\n```"
        val block = blockOf(markdown)

        assertNull(LexicalSource.splitBlock(markdown, block, "x", 1))
        assertTrue(LexicalSource.keepsNewlines(block))
    }

    // --------------------------------------------------------- markdown paste

    @Test
    fun `markdown formatting is detected for block and inline syntax`() {
        assertTrue(LexicalSource.isMarkdownFormatting("# Title"))
        assertTrue(LexicalSource.isMarkdownFormatting("- a\n- b"))
        assertTrue(LexicalSource.isMarkdownFormatting("```kotlin\nval x = 1\n```"))
        assertTrue(LexicalSource.isMarkdownFormatting("| a | b |\n|---|---|"))
        assertTrue(LexicalSource.isMarkdownFormatting("> quoted"))
        assertTrue(LexicalSource.isMarkdownFormatting("$$\nx^2\n$$"))
        assertTrue(LexicalSource.isMarkdownFormatting("---"))
        assertTrue(LexicalSource.isMarkdownFormatting("- [x] done"))
        assertTrue(LexicalSource.isMarkdownFormatting("1. first"))
        assertTrue(LexicalSource.isMarkdownFormatting("a **bold** word"))
        assertTrue(LexicalSource.isMarkdownFormatting("a `code` word"))
        assertTrue(LexicalSource.isMarkdownFormatting("[x](https://e.co)"))
        assertTrue(LexicalSource.isMarkdownFormatting("energy is ${'$'}E = mc^2${'$'} here"))
        assertTrue(LexicalSource.isMarkdownFormatting("plain\nmultiline"))
    }

    @Test
    fun `plain prose is not treated as markdown`() {
        assertFalse(LexicalSource.isMarkdownFormatting("Just a sentence."))
        assertFalse(LexicalSource.isMarkdownFormatting(""))
        assertFalse(LexicalSource.isMarkdownFormatting("   "))
    }

    @Test
    fun `pasting into an empty note replaces it`() {
        val markdown = ""
        val block = tailParagraph(markdown)

        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = markdown,
            block = block,
            pastedMarkdown = "# Title\n\nBody"
        )

        assertEquals("# Title\n\nBody", updated)
    }

    @Test
    fun `pasting into a paragraph keeps the surrounding text as its own blocks`() {
        val markdown = "Intro."
        val block = blockOf(markdown)

        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = markdown,
            block = block,
            pastedMarkdown = "## Heading\n\n- item",
            currentBlockText = "Intro."
        )

        val parsed = LexicalParser.parseToLexicalAst(updated)
        assertEquals(3, parsed.size)
        assertTrue(parsed[0] is LexicalParagraphNode)
        assertTrue(parsed[1] is LexicalHeadingNode)
        assertTrue(parsed[2] is LexicalListItemNode)
    }

    @Test
    fun `pasting an ordered list item becomes its own list`() {
        val markdown = "Intro."

        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = markdown,
            block = blockOf(markdown),
            pastedMarkdown = "1. first\n2. second",
            currentBlockText = "Intro."
        )

        val parsed = LexicalParser.parseToLexicalAst(updated)
        assertTrue(parsed[0] is LexicalParagraphNode)
        assertEquals(2, parsed.count { it is LexicalListItemNode })
    }

    @Test
    fun `pasting inline math keeps the sentence and renders a chip`() {
        val markdown = "Energy is TBD."

        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = markdown,
            block = blockOf(markdown),
            pastedMarkdown = "${'$'}E = mc^2${'$'}",
            currentSelection = TextRange(10, 13),
            currentBlockText = "Energy is TBD."
        )

        assertEquals("Energy is ${'$'}E = mc^2${'$'}.", updated)
        val parsed = LexicalParser.parseToLexicalAst(updated)
        assertTrue(
            (parsed.first() as LexicalParagraphNode).inlines.any {
                it is LexicalInlineMathNode
            }
        )
    }

    @Test
    fun `pasting over a selection replaces only that part`() {
        val markdown = "Keep THIS tail."
        val block = blockOf(markdown)

        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = markdown,
            block = block,
            pastedMarkdown = "new",
            currentSelection = TextRange(5, 9),
            currentBlockText = "Keep THIS tail."
        )

        assertEquals("Keep new tail.", updated)
    }

    @Test
    fun `pasting into a list item does not inherit the bullet`() {
        val markdown = "- existing"
        val block = blockOf(markdown)

        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = markdown,
            block = block,
            pastedMarkdown = "# Heading\n\n```js\nx\n```"
        )

        val parsed = LexicalParser.parseToLexicalAst(updated)
        assertFalse(parsed.any { it is LexicalListItemNode })
        assertTrue(parsed.any { it is LexicalHeadingNode })
        assertTrue(parsed.any { it is LexicalCodeBlockNode })
    }

    @Test
    fun `pasting into the trailing paragraph appends after a table`() {
        val markdown = "| a | b |\n|---|---|\n| 1 | 2 |"

        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = markdown,
            block = tailParagraph(markdown),
            pastedMarkdown = "## Notes\n\nDone."
        )

        assertEquals("$markdown\n## Notes\n\nDone.", updated)
        val parsed = LexicalParser.parseToLexicalAst(updated)
        assertTrue(parsed.any { it is LexicalTableNode })
        assertTrue(parsed.any { it is LexicalHeadingNode && it.level == 2 })
    }

    @Test
    fun `pasting into a trailing paragraph keeps it below a list`() {
        val markdown = "- a\n- b"

        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = markdown,
            block = tailParagraph(markdown),
            pastedMarkdown = "```kotlin\nval x = 1\n```"
        )

        val parsed = LexicalParser.parseToLexicalAst(updated)
        assertEquals(2, parsed.count { it is LexicalListItemNode })
        assertTrue(parsed.any { it is LexicalCodeBlockNode })
    }

    @Test
    fun `appending to an empty document drops the separator`() {
        assertEquals("# Title", LexicalSource.appendMarkdown("", "# Title"))
        assertEquals("# A\n\n# B", LexicalSource.appendMarkdown("# A\n", "\n# B\n"))
    }

    @Test
    fun `a chatgpt shaped answer parses into rich blocks`() {
        val answer = """
            ## Why Kotlin Coroutines Beat Threads

            Coroutines are **cooperative**, so they do not block a thread.

            ### Key points

            1. Cheap to create
            2. Structured concurrency
            3. Cancellation is cooperative

            - [x] suspending functions
            - [ ] `Flow` backpressure

            ```kotlin
            suspend fun greet() = println("hi")
            ```

            | Concept | Cost |
            |:---|---:|
            | Thread | 1 MB |
            | Coroutine | ~100 B |

            > Structured concurrency keeps children scoped.

            ${'$'}${'$'}
            O(n \log n)
            ${'$'}${'$'}
        """.trimIndent()

        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = "",
            block = tailParagraph(""),
            pastedMarkdown = answer
        )
        val parsed = LexicalParser.parseToLexicalAst(updated)

        assertTrue(parsed.any { it is LexicalHeadingNode && it.level == 2 })
        assertTrue(parsed.any { it is LexicalHeadingNode && it.level == 3 })
        assertTrue(parsed.count { it is LexicalListItemNode && it.isTask && it.isChecked } == 1)
        assertTrue(parsed.any { it is LexicalCodeBlockNode && it.language == "kotlin" })
        assertTrue(parsed.any { it is LexicalTableNode })
        assertTrue(parsed.any { it is LexicalQuoteNode })
        assertTrue(parsed.any { it is LexicalMathBlockNode })
        assertTrue(parsed.any { it is LexicalParagraphNode })
    }
}
