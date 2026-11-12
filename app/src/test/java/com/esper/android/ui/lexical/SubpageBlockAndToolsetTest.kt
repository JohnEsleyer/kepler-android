package com.esper.android.ui.lexical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubpageBlockAndToolsetTest {

    @Test
    fun `subpage link on its own line parses into a distinct SubpageBlockNode`() {
        val markdown = "# Welcome\n\n[Quantum Mechanics](kepler-page://page-a1b2)\n\nIntro."
        val blocks = LexicalParser.parseToLexicalAst(markdown)

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is LexicalHeadingNode)
        assertTrue(blocks[1] is LexicalSubpageBlockNode)
        assertTrue(blocks[2] is LexicalParagraphNode)

        val subpageNode = blocks[1] as LexicalSubpageBlockNode
        assertEquals("Quantum Mechanics", subpageNode.title)
        assertEquals("page-a1b2", subpageNode.pageId)
    }

    @Test
    fun `subpage block node round trips through editable text`() {
        val markdown = "[Wave Duality](kepler-page://page-9f8a)"
        val block = LexicalParser.parseToLexicalAst(markdown).single()

        assertEquals("[Wave Duality](kepler-page://page-9f8a)", LexicalSource.editableText(markdown, block))

        val updated = LexicalSource.withEditableText(markdown, block, "[Updated Title](kepler-page://page-9f8a)")
        assertEquals("[Updated Title](kepler-page://page-9f8a)", updated)
    }

    @Test
    fun `moveBlockUp swaps the block with its preceding sibling`() {
        val markdown = "# Heading 1\n\nSecond paragraph.\n\nThird paragraph."
        val blocks = LexicalParser.parseToLexicalAst(markdown)

        val secondBlock = blocks[1]
        val swapped = LexicalSource.moveBlockUp(markdown, secondBlock)

        val newBlocks = LexicalParser.parseToLexicalAst(swapped)
        assertEquals(3, newBlocks.size)
        assertEquals("Second paragraph.", newBlocks[0].plainText)
        assertEquals("Heading 1", newBlocks[1].plainText)
        assertEquals("Third paragraph.", newBlocks[2].plainText)
    }

    @Test
    fun `moveBlockDown swaps the block with its succeeding sibling`() {
        val markdown = "# Heading 1\n\nSecond paragraph.\n\nThird paragraph."
        val blocks = LexicalParser.parseToLexicalAst(markdown)

        val firstBlock = blocks[0]
        val swapped = LexicalSource.moveBlockDown(markdown, firstBlock)

        val newBlocks = LexicalParser.parseToLexicalAst(swapped)
        assertEquals(3, newBlocks.size)
        assertEquals("Second paragraph.", newBlocks[0].plainText)
        assertEquals("Heading 1", newBlocks[1].plainText)
        assertEquals("Third paragraph.", newBlocks[2].plainText)
    }

    @Test
    fun `transformBlock converts between block types correctly`() {
        val markdown = "Simple note"
        val block = LexicalParser.parseToLexicalAst(markdown).single()

        val asH1 = LexicalSource.transformBlock(markdown, block, "h1")
        assertEquals("# Simple note", asH1)

        val asQuote = LexicalSource.transformBlock(markdown, block, "quote")
        assertEquals("> Simple note", asQuote)

        val asTask = LexicalSource.transformBlock(markdown, block, "task")
        assertEquals("- [ ] Simple note", asTask)
    }
}
