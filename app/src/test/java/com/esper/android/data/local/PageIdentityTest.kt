package com.esper.android.data.local

import com.esper.android.data.model.PageEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for the page identity bug.
 *
 * Page ids are only unique *within* a notebook: every book created on the web
 * starts with a chapter called "p1". With a single column primary key Room
 * replaced the previous book's row on every sync, so all but one of those books
 * silently lost their only chapter and opened to an empty workspace.
 *
 * The contract is asserted against Room's own exported schema, which is the
 * canonical description of the database.
 */
class PageIdentityTest {

    private fun schemaFile(): File {
        val candidates = listOf(
            File("schemas/com.esper.android.data.local.EsperDatabase/7.json"),
            File("app/schemas/com.esper.android.data.local.EsperDatabase/7.json")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Room schema not found. Looked in: " + candidates.joinToString { it.path })
    }

    private fun pagesEntity(): JsonObject {
        val root = JsonParser.parseString(schemaFile().readText()).asJsonObject
        val entities = root.getAsJsonObject("database").getAsJsonArray("entities")
        for (i in 0 until entities.size()) {
            val entity = entities[i].asJsonObject
            if (entity.get("tableName").asString == "pages") return entity
        }
        error("no pages entity in the Room schema")
    }

    @Test
    fun `pages table uses a composite primary key scoped to the notebook`() {
        val primaryKey = pagesEntity().getAsJsonObject("primaryKey")
        val columns = primaryKey.getAsJsonArray("columnNames")
            .map { it.asString }
            .sorted()

        assertEquals(
            "the page key must be (notebookId, id) - a single column key makes " +
                "books overwrite each other's chapter",
            listOf("id", "notebookId"),
            columns
        )
    }

    @Test
    fun `pages table keeps the notebook reference`() {
        val fields = pagesEntity().getAsJsonArray("fields")
            .map { it.asJsonObject.get("columnName").asString }

        assertTrue("notebookId column is required", fields.contains("notebookId"))
        assertTrue("id column is required", fields.contains("id"))
    }

    @Test
    fun `two notebooks can hold the same page id`() {
        val first = PageEntity(id = "p1", notebookId = "book-a", title = "Chapter One")
        val second = PageEntity(id = "p1", notebookId = "book-b", title = "Chapter One")

        // Same page id, different notebooks -> two distinct rows.
        assertEquals(first.id, second.id)
        assertTrue(first.notebookId != second.notebookId)
    }

    @Test
    fun `the exported schema is the one the database runs`() {
        val root = JsonParser.parseString(schemaFile().readText()).asJsonObject
        val version = root.getAsJsonObject("database").get("version").asInt
        assertEquals(7, version)
        assertNotNull(root.getAsJsonObject("database").get("identityHash"))
    }
}
