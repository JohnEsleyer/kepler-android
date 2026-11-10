package com.esper.android.ui.bookshelf

import com.esper.android.data.model.NotebookEntity

object BookshelfFiltering {

    fun matchesTag(tagsCsv: String, tagFilter: String): Boolean {
        if (tagFilter.equals("All", ignoreCase = true)) return true
        return tagsCsv.split(",").any {
            it.trim().equals(tagFilter.trim(), ignoreCase = true)
        }
    }

    fun matchesTag(book: NotebookEntity, tagFilter: String, bookmarkIds: Set<String>): Boolean {
        if (tagFilter.equals("All", ignoreCase = true)) return true
        if (tagFilter.equals("Bookmarks", ignoreCase = true)) {
            return bookmarkIds.contains(book.id)
        }
        return matchesTag(book.tagsCsv, tagFilter)
    }

    fun matchesSearch(title: String, description: String, searchQuery: String): Boolean {
        if (searchQuery.isBlank()) return true
        return title.contains(searchQuery, ignoreCase = true) ||
            description.contains(searchQuery, ignoreCase = true)
    }

    fun filter(
        books: List<NotebookEntity>,
        tagFilter: String,
        searchQuery: String,
        bookmarkIds: Set<String>
    ): List<NotebookEntity> = books.filter { book ->
        matchesTag(book, tagFilter, bookmarkIds) &&
            matchesSearch(book.title, book.description, searchQuery)
    }
}
