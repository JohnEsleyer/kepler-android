package com.esper.android.data.export

import android.content.Context
import android.net.Uri
import com.esper.android.data.local.EsperDatabase
import com.esper.android.data.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class ImportedBook(
    val id: String,
    val title: String,
    val description: String,
    val visibility: String,
    val tagsCsv: String,
    val coverPreset: String,
    val coverImage: String? = null,
    val pages: List<PageEntity>,
    val canvases: List<CanvasEntity>,
    val questions: List<QuestionEntity>
)

object ZipImporter {

    suspend fun importPackage(
        context: Context,
        uri: Uri,
        db: EsperDatabase,
        workspaceId: String = "default"
    ): ImportedBook = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot open selected file")
        if (raw.size >= 2 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte()) {
            importZip(raw, db, workspaceId)
        } else {
            importSingleJson(String(raw, Charsets.UTF_8), db, workspaceId)
        }
    }

    private suspend fun importZip(raw: ByteArray, db: EsperDatabase, workspaceId: String): ImportedBook {
        val files = mutableMapOf<String, String>()
        ZipInputStream(raw.inputStream()).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name ?: ""
                if (!entry.isDirectory && !name.endsWith("/") &&
                    !name.startsWith("__MACOSX/") && !name.endsWith(".DS_Store")
                ) {
                    if (++count > 2000) throw IllegalArgumentException("Archive has too many entries")
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var n = zip.read(buf)
                    while (n != -1) {
                        out.write(buf, 0, n)
                        if (out.size() > 8 * 1024 * 1024) throw IllegalArgumentException("Entry too large: $name")
                        n = zip.read(buf)
                    }
                    files[name] = out.toString(Charsets.UTF_8.name())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (files.isEmpty()) throw IllegalArgumentException("Archive is empty")

        val metaEntry = files.entries.find { it.key.lowercase().endsWith("book.json") }?.value
        val meta = if (!metaEntry.isNullOrBlank()) {
            try { Gson().fromJson(metaEntry, JsonObject::class.java) } catch (_: Exception) { JsonObject() }
        } else JsonObject()

        val mdFiles = files
            .filter { it.key.lowercase().endsWith(".md") && !it.key.contains("readme", ignoreCase = true) }
            .toList()
            .sortedWith { a, b -> naturalCompare(a.first, b.first) }
        if (mdFiles.isEmpty()) throw IllegalArgumentException("Archive contains no chapter markdown files (.md)")

        val newBookId = "book-${UUID.randomUUID().toString().take(8)}"
        val title = meta.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: "Imported Book"
        val desc = meta.get("description")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val visibility = meta.get("visibility")?.takeIf { it.isJsonPrimitive }?.asString ?: "public"
        val preset = meta.get("coverPreset")?.takeIf { it.isJsonPrimitive }?.asString ?: "celestial"
        val coverImage = meta.get("coverImage")?.takeIf { it.isJsonPrimitive }?.asString
        val tagsCsv = meta.get("tags")?.takeIf { it.isJsonArray }
            ?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.filter { it.isNotBlank() }?.joinToString(",")?.ifEmpty { null } ?: "Imported"

        val pageEntities = mdFiles.mapIndexed { idx, (path, content) ->
            val baseName = path.substringAfterLast("/").removeSuffix(".md").removeSuffix(".MD")
            val cleanTitle = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?.replace(Regex("^[#\\s*_\\->`]+"), "")?.replace(Regex("[#\\s*_\\->`]+$"), "")?.trim()?.take(100)
                ?.ifEmpty { null }
                ?: stripNumericPrefix(baseName).ifEmpty { "Chapter ${idx + 1}" }
            PageEntity(
                id = "p-$newBookId-${path.substringAfterLast('/')}",
                notebookId = newBookId,
                parentId = null,
                title = cleanTitle,
                notes = content,
                position = idx
            )
        }

        val htmlFiles = files.filter { it.key.lowercase().endsWith(".html") && !it.key.lowercase().endsWith("index.html") }
        val canvasEntities = htmlFiles.entries.mapIndexed { idx, (path, code) ->
            val baseName = path.substringAfterLast("/").removeSuffix(".html").removeSuffix(".HTML")
            val regex = Regex("^0*(\\d+)")
            val assignedCh = regex.find(baseName)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            CanvasEntity(
                id = "c-$newBookId-${path.substringAfterLast('/')}",
                notebookId = newBookId,
                kind = "html",
                title = stripNumericPrefix(baseName).ifEmpty { "Simulation" },
                payloadJson = code,
                assignedChapter = assignedCh
            )
        }

        val questionsMeta = meta.get("questions")?.takeIf { it.isJsonArray }?.asJsonArray
        val questionEntities = (questionsMeta?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: emptyList())
            .mapIndexed { idx, q ->
                QuestionEntity(
                    id = "q-${System.currentTimeMillis() + idx}",
                    notebookId = newBookId,
                    title = q.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.ifEmpty { null }
                        ?: "Review Question ${idx + 1}",
                    answer = q.get("answer")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    position = idx
                )
            }

        return ImportedBook(
            id = newBookId,
            title = title,
            description = desc,
            visibility = visibility,
            tagsCsv = tagsCsv,
            coverPreset = preset,
            coverImage = coverImage,
            pages = pageEntities,
            canvases = canvasEntities,
            questions = questionEntities
        )
    }

    private fun importSingleJson(text: String, db: EsperDatabase, workspaceId: String): ImportedBook {
        val root = try {
            Gson().fromJson(text, JsonObject::class.java)
        } catch (_: Exception) {
            throw IllegalArgumentException("File is not valid JSON")
        } ?: throw IllegalArgumentException("File is not valid JSON")
        val pagesArr = root.get("pages")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalArgumentException("Book file contains no pages")

        val newBookId = "book-${UUID.randomUUID().toString().take(8)}"
        val title = root.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: "Imported Book"
        val desc = root.get("description")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val visibility = root.get("visibility")?.takeIf { it.isJsonPrimitive }?.asString ?: "public"
        val preset = root.get("coverPreset")?.takeIf { it.isJsonPrimitive }?.asString ?: "celestial"
        val coverImage = root.get("coverImage")?.takeIf { it.isJsonPrimitive }?.asString
        val tagsCsv = root.get("tags")?.takeIf { it.isJsonArray }
            ?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.filter { it.isNotBlank() }?.joinToString(",")?.ifEmpty { null } ?: "Imported"

        val pageEntities = pagesArr.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .mapIndexed { idx, p ->
                PageEntity(
                    id = p.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.ifEmpty { null }
                        ?: "p-$newBookId-$idx",
                    notebookId = newBookId,
                    parentId = null,
                    title = p.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.ifEmpty { null }
                        ?: "Chapter ${idx + 1}",
                    notes = p.get("notes")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    position = idx
                )
            }

        val canvasEntities = (root.get("visuals")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: root.get("canvases")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: emptyList())
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .mapIndexed { idx, c ->
                CanvasEntity(
                    id = "c-$newBookId-$idx",
                    notebookId = newBookId,
                    kind = "html",
                    title = c.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: "Simulation",
                    payloadJson = c.get("content")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    assignedChapter = c.get("assignedChapter")?.takeIf { it.isJsonPrimitive }?.asInt ?: (idx + 1)
                )
            }

        val questionEntities = (root.get("questions")?.takeIf { it.isJsonArray }?.asJsonArray ?: emptyList())
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .mapIndexed { idx, q ->
                QuestionEntity(
                    id = "q-${System.currentTimeMillis() + idx}",
                    notebookId = newBookId,
                    title = q.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: "Question ${idx + 1}",
                    answer = q.get("answer")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    position = idx
                )
            }

        return ImportedBook(
            id = newBookId,
            title = title,
            description = desc,
            visibility = visibility,
            tagsCsv = tagsCsv,
            coverPreset = preset,
            coverImage = coverImage,
            pages = pageEntities,
            canvases = canvasEntities,
            questions = questionEntities
        )
    }

    private fun stripNumericPrefix(name: String): String =
        name.replace(Regex("^[0-9._\\-\\s]+"), "").replace(Regex("[_\\-]+"), " ").trim()

    private fun naturalCompare(a: String, b: String): Int {
        val regex = Regex("\\d+|\\D+")
        val aParts = regex.findAll(a).map { it.value }.toList()
        val bParts = regex.findAll(b).map { it.value }.toList()
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val ap = aParts.getOrNull(i) ?: return -1
            val bp = bParts.getOrNull(i) ?: return 1
            val cmp = if (ap[0].isDigit() && bp[0].isDigit()) {
                ap.toLongOrNull()?.compareTo(bp.toLongOrNull() ?: 0) ?: ap.compareTo(bp)
            } else ap.compareTo(bp, ignoreCase = true)
            if (cmp != 0) return cmp
        }
        return 0
    }
}
