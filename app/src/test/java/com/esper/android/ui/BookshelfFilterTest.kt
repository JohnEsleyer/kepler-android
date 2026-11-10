package com.esper.android.ui

import com.esper.android.ui.bookshelf.BookshelfFiltering
import com.esper.android.data.model.NotebookEntity
import org.junit.Assert.*
import org.junit.Test

class BookshelfFilterTest {

    private fun book(
        id: String,
        title: String = "Book $id",
        description: String = "",
        tags: String = "General"
    ) = NotebookEntity(
        id = id,
        title = title,
        description = description,
        ownerSub = "u1",
        tagsCsv = tags
    )

    private val book1 = book("book-1", tags = "Physics,Quantum")
    private val book10 = book("book-10", tags = "Physics")
    private val book2 = book("book-2", tags = "Mechanics")
    private val allBooks = listOf(book1, book10, book2)

    @Test
    fun `tag matching is exact and case insensitive`() {
        assertTrue(BookshelfFiltering.matchesTag("Physics,Quantum", "Physics"))
        assertTrue(BookshelfFiltering.matchesTag("Physics,Quantum", "quantum"))
        assertTrue(BookshelfFiltering.matchesTag("Physics,Quantum", "Quantum"))
        assertFalse(BookshelfFiltering.matchesTag("Physics,Quantum", "Phys"))
        assertFalse(BookshelfFiltering.matchesTag("Physics,Quantum", "Mechanics"))
    }

    @Test
    fun `the all tag matches everything`() {
        assertTrue(BookshelfFiltering.matchesTag("", "All"))
        assertTrue(BookshelfFiltering.matchesTag("Anything", "All"))
    }

    @Test
    fun `bookmark filtering matches local bookmarked set`() {
        val bookmarks = setOf("book-10")
        val result = BookshelfFiltering.filter(
            books = allBooks,
            tagFilter = "Bookmarks",
            searchQuery = "",
            bookmarkIds = bookmarks
        )
        assertEquals(listOf(book10), result)
    }

    @Test
    fun `search matches the title and the description`() {
        val b = book("x", title = "Orbital Mechanics", description = "About orbits")
        assertTrue(BookshelfFiltering.matchesSearch(b.title, b.description, "orbital"))
        assertTrue(BookshelfFiltering.matchesSearch(b.title, b.description, "ORBITS"))
        assertFalse(BookshelfFiltering.matchesSearch(b.title, b.description, "relativity"))
        assertTrue(BookshelfFiltering.matchesSearch(b.title, b.description, "  "))
    }

    @Test
    fun `the library view returns every notebook`() {
        val result = BookshelfFiltering.filter(
            books = allBooks,
            tagFilter = "All",
            searchQuery = "",
            bookmarkIds = emptySet()
        )
        assertEquals(allBooks, result)
    }
}
