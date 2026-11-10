package com.esper.android.remote

import com.esper.android.deriveChapterTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTitleTest {

    @Test
    fun `chapter title mirrors first markdown line`() {
        assertEquals("Hello", deriveChapterTitle("# Hello\n\nbody"))
        assertEquals("Hello", deriveChapterTitle("  Hello  "))
        assertEquals("Untitled Chapter", deriveChapterTitle(""))
        assertEquals("Untitled Chapter", deriveChapterTitle("   \n  "))
        assertEquals("Item", deriveChapterTitle("> Item"))
        assertEquals("Item", deriveChapterTitle("- Item"))
        assertTrue(deriveChapterTitle("# " + "x".repeat(200)).length <= 100)
    }
}
