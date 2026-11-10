package com.esper.android.ui.bookshelf

import com.esper.android.data.model.NotebookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSortingTest {

    private fun book(
        id: String,
        title: String,
        updatedAt: Long,
        createdAt: Long = updatedAt
    ) = NotebookEntity(
        id = id,
        title = title,
        description = "",
        ownerSub = "u1",
        tagsCsv = "",
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private val bookA = book("b1", title = "Alpha Book", updatedAt = 1000L)
    private val bookB = book("b2", title = "bravo book", updatedAt = 3000L)
    private val bookC = book("b3", title = "Charlie Book", updatedAt = 2000L)

    private val allBooks = listOf(bookA, bookB, bookC)

    private fun sort(books: List<NotebookEntity>, option: BookSortOption): List<NotebookEntity> {
        return when (option) {
            BookSortOption.RECENTLY_UPDATED -> books.sortedByDescending { it.updatedAt }
            BookSortOption.OLDEST_UPDATED -> books.sortedBy { it.updatedAt }
            BookSortOption.RECENTLY_CREATED -> books.sortedByDescending { it.createdAt }
            BookSortOption.OLDEST_CREATED -> books.sortedBy { it.createdAt }
            BookSortOption.TITLE_AZ -> books.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            BookSortOption.TITLE_ZA -> books.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
    }

    @Test
    fun `recently updated sorts by updatedAt descending`() {
        val result = sort(allBooks, BookSortOption.RECENTLY_UPDATED)
        assertEquals(listOf(bookB, bookC, bookA), result)
    }

    @Test
    fun `oldest updated sorts by updatedAt ascending`() {
        val result = sort(allBooks, BookSortOption.OLDEST_UPDATED)
        assertEquals(listOf(bookA, bookC, bookB), result)
    }

    @Test
    fun `title A-Z sorts by title case-insensitive ascending`() {
        val result = sort(allBooks, BookSortOption.TITLE_AZ)
        assertEquals(listOf(bookA, bookB, bookC), result)
    }

    @Test
    fun `title Z-A sorts by title case-insensitive descending`() {
        val result = sort(allBooks, BookSortOption.TITLE_ZA)
        assertEquals(listOf(bookC, bookB, bookA), result)
    }

    @Test
    fun `recently created sorts by createdAt descending`() {
        val newlyCreated = book("b5", title = "New", updatedAt = 100L, createdAt = 9000L)
        val oldButEdited = book("b6", title = "Old", updatedAt = 9000L, createdAt = 100L)
        val result = sort(listOf(oldButEdited, newlyCreated), BookSortOption.RECENTLY_CREATED)
        assertEquals(listOf(newlyCreated, oldButEdited), result)
    }

    @Test
    fun `oldest created sorts by createdAt ascending`() {
        val newlyCreated = book("b5", title = "New", updatedAt = 100L, createdAt = 9000L)
        val oldButEdited = book("b6", title = "Old", updatedAt = 9000L, createdAt = 100L)
        val result = sort(listOf(newlyCreated, oldButEdited), BookSortOption.OLDEST_CREATED)
        assertEquals(listOf(oldButEdited, newlyCreated), result)
    }
}
