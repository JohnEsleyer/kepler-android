package com.esper.android.data.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.esper.android.data.export.ImportedBook
import com.esper.android.data.local.EsperDatabase
import com.esper.android.data.model.CanvasEntity
import com.esper.android.data.model.NotebookEntity
import com.esper.android.data.model.PageEntity
import com.esper.android.data.model.QuestionEntity
import com.esper.android.data.model.WorkspaceEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Helpers for resolving human-readable filesystem paths and opening folders in file explorers.
 */
object WorkspacePathHelper {

    fun getDisplayPath(treeUriString: String?): String {
        if (treeUriString.isNullOrBlank()) return ""
        val uri = try { Uri.parse(treeUriString) } catch (_: Exception) { return treeUriString }
        return getDisplayPath(uri)
    }

    fun getDisplayPath(uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.path ?: uri.toString()
        }
        val docId = try {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        } catch (_: Exception) {
            uri.lastPathSegment ?: uri.toString()
        }

        return when {
            docId.startsWith("primary:", ignoreCase = true) -> {
                val sub = docId.substringAfter("primary:").trimStart('/')
                "/storage/emulated/0" + (if (sub.isNotEmpty()) "/$sub" else "")
            }
            docId.startsWith("raw:", ignoreCase = true) -> {
                docId.substringAfter("raw:")
            }
            docId.contains(":") -> {
                val parts = docId.split(":", limit = 2)
                val volume = parts[0]
                val sub = parts.getOrNull(1)?.trimStart('/') ?: ""
                "/storage/$volume" + (if (sub.isNotEmpty()) "/$sub" else "")
            }
            else -> docId.ifEmpty { uri.path ?: uri.toString() }
        }
    }

    fun openFolderInFileManager(context: Context, treeUriString: String?) {
        if (treeUriString.isNullOrBlank()) {
            Toast.makeText(context, "No folder selected", Toast.LENGTH_SHORT).show()
            return
        }
        val treeUri = try { Uri.parse(treeUriString) } catch (_: Exception) {
            Toast.makeText(context, "Invalid folder URI", Toast.LENGTH_SHORT).show()
            return
        }

        var launched = false
        // 1. Try DocumentsContract folder URI with Document.MIME_TYPE_DIR
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            launched = true
        } catch (_: Exception) {}

        if (!launched) {
            // 2. Try generic folder view intent
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(treeUri, "resource/folder")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                launched = true
            } catch (_: Exception) {}
        }

        if (!launched) {
            // 3. Fallback: ACTION_VIEW on treeUri
            try {
                val intent = Intent(Intent.ACTION_VIEW, treeUri).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                launched = true
            } catch (_: Exception) {}
        }

        if (!launched) {
            Toast.makeText(context, "No file manager app found to open this folder directly", Toast.LENGTH_LONG).show()
        }
    }

    fun copyPathToClipboard(context: Context, path: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Workspace Path", path)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

/**
 * VaultManager owns Kepler workspace filesystem operations conforming to Kepler Data & Storage Spec.
 * Pure folder-backed with NO default workspace created automatically.
 */
class VaultManager(
    private val context: Context,
    private val db: EsperDatabase,
    private val gson: Gson = Gson()
) {
    private val prefs = context.getSharedPreferences("kepler_workspace_prefs", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    companion object {
        const val KEY_ACTIVE_WORKSPACE_ID = "active_workspace_id"
        const val BOOK_META_FILE = "book.json"
        const val QUESTIONS_FILE = "questions.json"
        const val VISUALS_DIR = "visuals"
        const val CHAPTERS_DIR = "chapters"
        const val SUBPAGES_DIR = "subpages"
    }

    data class OrphanScanResult(
        val bookId: String,
        val bookTitle: String,
        val orphanPageIds: List<String>
    )

    private data class Node(
        val name: String,
        val file: File? = null,
        val doc: DocumentFile? = null,
        val isDirectory: Boolean = false
    )

    /**
     * Normalizes a workspace folder URI into a stable identity key so that the
     * same folder picked twice (even via a slightly different SAF URI) maps to
     * a single workspace. Falls back to the resolved display path.
     */
    private fun treeKey(treeUriString: String?): String? {
        if (treeUriString.isNullOrBlank()) return null
        val uri = try { Uri.parse(treeUriString) } catch (_: Exception) { return null }
        val key = try {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        return (key ?: WorkspacePathHelper.getDisplayPath(uri)).lowercase(Locale.ROOT)
    }

    /**
     * Merges duplicate workspaces that point to the same folder, keeping the
     * most recently accessed entry and deleting the rest.
     */
    private suspend fun dedupeWorkspaces() {
        val all = db.dao().getAllWorkspaces().filter { !it.treeUriString.isNullOrBlank() }
        val keptByKey = mutableMapOf<String, String>()
        val keptIdForDeleted = mutableMapOf<String, String>()
        val deleteIds = mutableListOf<String>()

        for (ws in all.sortedByDescending { it.lastAccessedAt }) {
            val key = treeKey(ws.treeUriString) ?: continue
            val kept = keptByKey[key]
            if (kept == null) {
                keptByKey[key] = ws.id
            } else {
                deleteIds.add(ws.id)
                keptIdForDeleted[ws.id] = kept
            }
        }

        if (deleteIds.isNotEmpty()) {
            deleteIds.forEach { db.dao().deleteWorkspace(it) }
            val activeId = prefs.getString(KEY_ACTIVE_WORKSPACE_ID, null)
            if (activeId != null && activeId in deleteIds) {
                prefs.edit().putString(KEY_ACTIVE_WORKSPACE_ID, keptIdForDeleted[activeId]).apply()
            }
        }
    }

    suspend fun getActiveWorkspace(): WorkspaceEntity? = withContext(Dispatchers.IO) {
        // Clean up legacy placeholder workspace without a folder uri
        db.dao().getWorkspaceById("default_local_workspace")?.let { legacy ->
            if (legacy.treeUriString == null) {
                db.dao().deleteWorkspace(legacy.id)
            }
        }

        // Merge any workspaces that reference the same folder
        dedupeWorkspaces()

        val id = prefs.getString(KEY_ACTIVE_WORKSPACE_ID, null)
        var ws = if (!id.isNullOrBlank()) db.dao().getWorkspaceById(id) else null
        if (ws != null && ws.treeUriString == null) {
            ws = null
        }
        if (ws == null) {
            val all = db.dao().getAllWorkspaces().filter { !it.treeUriString.isNullOrBlank() }
            ws = all.firstOrNull()
            if (ws != null) {
                prefs.edit().putString(KEY_ACTIVE_WORKSPACE_ID, ws.id).apply()
            } else {
                prefs.edit().remove(KEY_ACTIVE_WORKSPACE_ID).apply()
            }
        }
        ws
    }

    suspend fun setActiveWorkspace(workspaceId: String?) = withContext(Dispatchers.IO) {
        if (workspaceId == null) {
            prefs.edit().remove(KEY_ACTIVE_WORKSPACE_ID).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE_WORKSPACE_ID, workspaceId).apply()
            db.dao().getWorkspaceById(workspaceId)?.let {
                db.dao().upsertWorkspace(it.copy(lastAccessedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun addWorkspace(name: String, treeUri: Uri): WorkspaceEntity = withContext(Dispatchers.IO) {
        val newKey = treeKey(treeUri.toString())

        // Reuse an existing workspace if this folder was already opened before
        if (newKey != null) {
            val existing = db.dao().getAllWorkspaces().firstOrNull { treeKey(it.treeUriString) == newKey }
            if (existing != null) {
                val refreshed = existing.copy(
                    name = name.ifBlank { existing.name },
                    treeUriString = treeUri.toString(),
                    lastAccessedAt = System.currentTimeMillis()
                )
                db.dao().upsertWorkspace(refreshed)
                setActiveWorkspace(refreshed.id)
                return@withContext refreshed
            }
        }

        val newId = "ws-" + UUID.randomUUID().toString().take(8)
        val entity = WorkspaceEntity(
            id = newId,
            name = name.ifBlank { "Workspace" },
            treeUriString = treeUri.toString(),
            isDefault = false,
            lastAccessedAt = System.currentTimeMillis()
        )
        db.dao().upsertWorkspace(entity)
        setActiveWorkspace(newId)
        entity
    }

    suspend fun removeWorkspace(workspaceId: String) = withContext(Dispatchers.IO) {
        db.dao().deleteWorkspace(workspaceId)
        val active = prefs.getString(KEY_ACTIVE_WORKSPACE_ID, null)
        if (active == workspaceId) {
            val remaining = db.dao().getAllWorkspaces().filter { !it.treeUriString.isNullOrBlank() }
            setActiveWorkspace(remaining.firstOrNull()?.id)
        }
    }

    private suspend fun activeRoot(): Node? = withContext(Dispatchers.IO) {
        val ws = getActiveWorkspace() ?: return@withContext null
        val uriStr = ws.treeUriString ?: return@withContext null

        // 1. Direct filesystem access (Obsidian mode): allows direct I/O to /storage/emulated/0/...
        val displayPath = WorkspacePathHelper.getDisplayPath(uriStr)
        if (displayPath.isNotBlank() && (displayPath.startsWith("/") || displayPath.startsWith("file:"))) {
            try {
                val cleanPath = displayPath.removePrefix("file://")
                val f = File(cleanPath)
                if (!f.exists()) {
                    f.mkdirs()
                }
                if (f.exists() && f.isDirectory) {
                    return@withContext Node(f.name.ifBlank { ws.name }, file = f, isDirectory = true)
                }
            } catch (_: Exception) {}
        }

        // 2. Storage Access Framework (SAF) DocumentFile fallback
        try {
            val uri = Uri.parse(uriStr)
            val doc = if (DocumentsContract.isTreeUri(uri)) {
                DocumentFile.fromTreeUri(context, uri)
            } else {
                DocumentFile.fromSingleUri(context, uri)
            }
            if (doc != null && doc.exists() && doc.isDirectory) {
                return@withContext Node(doc.name ?: ws.name, doc = doc, isDirectory = true)
            }
        } catch (_: Exception) {}

        // 3. Last fallback: check if resolved display path on device is directly accessible
        if (displayPath.isNotBlank() && displayPath.startsWith("/storage/")) {
            try {
                val f = File(displayPath)
                if (f.exists() && f.isDirectory) {
                    return@withContext Node(f.name.ifBlank { ws.name }, file = f, isDirectory = true)
                }
            } catch (_: Exception) {}
        }

        null
    }

    // ---------------------------------------------------------- node helpers

    private fun listChildren(parent: Node): List<Node> {
        return try {
            if (parent.file != null) {
                parent.file.listFiles()?.map {
                    Node(it.name, file = it, isDirectory = it.isDirectory)
                } ?: emptyList()
            } else {
                parent.doc?.listFiles()?.map {
                    Node(it.name ?: "untitled", doc = it, isDirectory = it.isDirectory)
                } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findChild(parent: Node, name: String): Node? =
        listChildren(parent).firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun createDirectory(parent: Node, name: String): Node? {
        return try {
            if (parent.file != null) {
                val dir = File(parent.file, name)
                if (!dir.exists() && !dir.mkdirs()) return null
                Node(name, file = dir, isDirectory = true)
            } else {
                val existing = parent.doc?.findFile(name)
                if (existing != null && existing.isDirectory) {
                    return Node(existing.name ?: name, doc = existing, isDirectory = true)
                }
                val doc = parent.doc?.createDirectory(name) ?: return null
                Node(doc.name ?: name, doc = doc, isDirectory = true)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readText(node: Node): String? {
        return try {
            if (node.file != null) {
                node.file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
            } else {
                node.doc?.let { doc ->
                    context.contentResolver.openInputStream(doc.uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeText(parent: Node, name: String, content: String, mimeType: String = "text/plain"): Boolean {
        return try {
            if (parent.file != null) {
                val f = File(parent.file, name)
                f.parentFile?.mkdirs()
                f.writeText(content, Charsets.UTF_8)
                true
            } else {
                val dir = parent.doc ?: return false
                val existing = dir.findFile(name)
                val file = existing ?: dir.createFile(mimeType, name) ?: return false
                context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: return false
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteNode(node: Node): Boolean = try {
        if (node.file != null) {
            if (node.file.isDirectory) node.file.deleteRecursively() else node.file.delete()
        } else {
            node.doc?.delete() ?: false
        }
    } catch (_: Exception) {
        false
    }

    // --------------------------------------------------------------- scanning

    suspend fun syncWorkspaceToLocalDatabase() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ws = getActiveWorkspace() ?: return@withLock
            val root = activeRoot() ?: return@withLock
            val books = listChildren(root)
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            val foundBookIds = mutableSetOf<String>()

            for (bookNode in books) {
                val metaFile = findChild(bookNode, BOOK_META_FILE)
                val metaMap: Map<String, Any?> = metaFile?.let { node ->
                    readText(node)?.let { raw ->
                        try {
                            @Suppress("UNCHECKED_CAST")
                            gson.fromJson(raw, Map::class.java) as Map<String, Any?>
                        } catch (_: Exception) {
                            emptyMap()
                        }
                    }
                } ?: emptyMap()

                val bookId = (metaMap["id"] as? String)?.takeIf { it.isNotBlank() }
                    ?: derivedBookId(bookNode.name)
                foundBookIds.add(bookId)

                val title = (metaMap["title"] as? String)?.takeIf { it.isNotBlank() } ?: bookNode.name
                val desc = (metaMap["description"] as? String) ?: ""
                val preset = (metaMap["coverPreset"] as? String) ?: "celestial"
                val coverImage = metaMap["coverImage"] as? String
                val showTitle = (metaMap["showTitleOnCover"] as? Boolean) ?: true
                val tags = when (val t = metaMap["tags"]) {
                    is List<*> -> t.joinToString(",") { it.toString() }
                    is String -> t
                    else -> "General"
                }
                val folderModified = bookNode.file?.lastModified()
                    ?: bookNode.doc?.lastModified()
                    ?: 0L
                val createdAt = (metaMap["createdAt"] as? Number)?.toLong() ?: folderModified
                val updatedAt = (metaMap["updatedAt"] as? Number)?.toLong() ?: folderModified

                if (metaFile == null) {
                    val generated = linkedMapOf<String, Any?>(
                        "id" to bookId,
                        "title" to title,
                        "description" to desc,
                        "coverPreset" to preset,
                        "coverImage" to coverImage,
                        "showTitleOnCover" to showTitle,
                        "tags" to tags.split(",").map { it.trim() },
                        "isPinned" to false,
                        "createdAt" to createdAt,
                        "updatedAt" to updatedAt
                    )
                    writeText(bookNode, BOOK_META_FILE, gson.toJson(generated), "application/json")
                }

                val notebook = NotebookEntity(
                    id = bookId,
                    workspaceId = ws.id,
                    title = title,
                    description = desc,
                    ownerSub = "local_vault",
                    visibility = "public",
                    coverPreset = preset,
                    coverImage = coverImage,
                    showTitleOnCover = showTitle,
                    tagsCsv = tags,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    notesUpdatedAt = updatedAt
                )
                db.dao().upsertNotebook(notebook)

                // Load chapters from chapters/ folder, falling back to legacy root markdown files
                val chaptersDir = findChild(bookNode, CHAPTERS_DIR)
                val chapterParent = chaptersDir ?: bookNode
                val chapterFiles = listChildren(chapterParent)
                    .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) && !it.name.equals(BOOK_META_FILE, ignoreCase = true) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }

                val chapterRegex = Regex("""^(\d+)_([a-zA-Z0-9\-]+)_(.+)\.md$""")

                val chapters = chapterFiles.mapIndexed { idx, node ->
                    val content = readText(node) ?: ""
                    val chMatch = chapterRegex.matchEntire(node.name)
                    val chId = chMatch?.groupValues?.get(2) ?: pageId(bookId, node.name)
                    PageEntity(
                        id = chId,
                        notebookId = bookId,
                        parentId = null,
                        title = extractChapterTitle(content, node.name, idx),
                        notes = content,
                        position = idx
                    )
                }

                // Load subpages from subpages/ folder (<id>.md)
                val subpagesDir = findChild(bookNode, SUBPAGES_DIR)
                val subpageFiles = subpagesDir?.let { dir ->
                    listChildren(dir).filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                } ?: emptyList()

                val subpages = subpageFiles.map { node ->
                    val content = readText(node) ?: ""
                    val pageId = node.name.removeSuffix(".md").removeSuffix(".MD")
                    PageEntity(
                        id = pageId,
                        notebookId = bookId,
                        parentId = "subpage",
                        title = extractSubpageTitle(content, pageId),
                        notes = content,
                        position = 9999
                    )
                }

                val allPages = chapters + subpages
                db.dao().deletePagesForNotebook(bookId)
                if (allPages.isNotEmpty()) db.dao().upsertPages(allPages)

                val questionsFile = findChild(bookNode, QUESTIONS_FILE)
                val questions = questionsFile?.let { node ->
                    readText(node)?.let { parseQuestions(it, bookId) } ?: emptyList()
                } ?: emptyList()
                db.dao().deleteQuestionsForNotebook(bookId)
                if (questions.isNotEmpty()) db.dao().upsertQuestions(questions)

                val visualsDir = findChild(bookNode, VISUALS_DIR)
                val visuals = visualsDir?.let { dir ->
                    listChildren(dir).filter { !it.isDirectory }
                } ?: emptyList()

                val canvases = visuals.mapIndexedNotNull { _, node ->
                    val content = readText(node) ?: return@mapIndexedNotNull null
                    val kind = if (node.name.endsWith(".json", true)) "json" else "html"
                    val assignedCh = chapterIndexFromName(node.name) ?: 1
                    CanvasEntity(
                        id = canvasId(bookId, node.name),
                        notebookId = bookId,
                        kind = kind,
                        title = visualTitle(node.name),
                        payloadJson = content,
                        assignedChapter = assignedCh
                    )
                }
                db.dao().deleteCanvasesForNotebook(bookId)
                if (canvases.isNotEmpty()) db.dao().upsertCanvases(canvases)
            }

            db.dao().getNotebooksByWorkspace(ws.id).forEach { local ->
                if (local.id !in foundBookIds) {
                    db.dao().deleteNotebookCascade(local.id)
                }
            }
        }
    }

    // ---------------------------------------------------------------- books

    private suspend fun findBook(notebookId: String): Node? = withContext(Dispatchers.IO) {
        val root = activeRoot() ?: return@withContext null
        listChildren(root)
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .firstOrNull { bookIdOf(it) == notebookId }
    }

    private fun bookIdOf(book: Node): String {
        val meta = findChild(book, BOOK_META_FILE)?.let { readText(it) }
        return try {
            val map = meta?.let {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(it, Map::class.java) as Map<String, Any?>
            }
            (map?.get("id") as? String)?.takeIf { it.isNotBlank() } ?: derivedBookId(book.name)
        } catch (_: Exception) {
            derivedBookId(book.name)
        }
    }

    suspend fun saveChapterNotes(notebookId: String, pageId: String, newNotes: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val book = findBook(notebookId) ?: return@withLock false

                // 1. Check if target is a subpage in subpages/
                val subpagesDir = findChild(book, SUBPAGES_DIR)
                val subpageTarget = subpagesDir?.let { findChild(it, "$pageId.md") }
                if (subpageTarget != null) {
                    val written = writeText(subpagesDir, "$pageId.md", newNotes, "text/markdown")
                    if (written) touchBookMeta(book)
                    return@withLock written
                }

                // 2. Check chapters/ directory or root directory
                val chaptersDir = findChild(book, CHAPTERS_DIR)
                val searchDir = chaptersDir ?: book
                val target = listChildren(searchDir)
                    .firstOrNull { !it.isDirectory && (it.name.contains("_$pageId") || it.name.contains(pageId) || pageId(notebookId, it.name) == pageId) }
                    ?: listChildren(book).firstOrNull { !it.isDirectory && pageId(notebookId, it.name) == pageId }
                    ?: return@withLock false

                val writeParent = if (chaptersDir != null && listChildren(chaptersDir).any { it.name == target.name }) chaptersDir else book
                val written = writeText(writeParent, target.name, newNotes, "text/markdown")
                if (written) touchBookMeta(book)
                written
            }
        }

    suspend fun createChapter(notebookId: String, title: String): PageEntity? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = findBook(notebookId) ?: return@withLock null
            val chaptersDir = findChild(book, CHAPTERS_DIR) ?: createDirectory(book, CHAPTERS_DIR) ?: book
            val existing = listChildren(chaptersDir).filter {
                !it.isDirectory && it.name.endsWith(".md", ignoreCase = true)
            }
            val nextIndex = (existing.mapNotNull { chapterIndexFromName(it.name) }.maxOrNull() ?: 0) + 1
            val chId = "ch-" + UUID.randomUUID().toString().take(4)
            val fileName = chapterFileName(nextIndex, chId, title)
            val content = "# $title\n\n"
            if (!writeText(chaptersDir, fileName, content, "text/markdown")) return@withLock null
            touchBookMeta(book)
            PageEntity(
                id = chId,
                notebookId = notebookId,
                parentId = null,
                title = title,
                notes = content,
                position = existing.size
            )
        }
    }

    suspend fun createSubpageWithId(notebookId: String, subpageId: String, title: String): PageEntity? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = findBook(notebookId) ?: return@withLock null
            val subpagesDir = findChild(book, SUBPAGES_DIR) ?: createDirectory(book, SUBPAGES_DIR) ?: return@withLock null
            val fileName = "$subpageId.md"
            val content = "# $title\n\n"
            if (!writeText(subpagesDir, fileName, content, "text/markdown")) return@withLock null
            touchBookMeta(book)
            val entity = PageEntity(
                id = subpageId,
                notebookId = notebookId,
                parentId = "subpage",
                title = title,
                notes = content,
                position = 9999
            )
            db.dao().upsertPage(entity)
            entity
        }
    }

    suspend fun deleteChapter(notebookId: String, pageId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = findBook(notebookId) ?: return@withLock
            val subpagesDir = findChild(book, SUBPAGES_DIR)
            val subTarget = subpagesDir?.let { findChild(it, "$pageId.md") }
            if (subTarget != null) {
                deleteNode(subTarget)
                touchBookMeta(book)
                return@withLock
            }

            val chaptersDir = findChild(book, CHAPTERS_DIR)
            val searchDir = chaptersDir ?: book
            val target = listChildren(searchDir)
                .firstOrNull { !it.isDirectory && (it.name.contains("_$pageId") || it.name.contains(pageId) || pageId(notebookId, it.name) == pageId) }
                ?: listChildren(book).firstOrNull { !it.isDirectory && pageId(notebookId, it.name) == pageId }

            if (target != null) {
                deleteNode(target)
                touchBookMeta(book)
            }
        }
    }

    suspend fun scanOrphanSubpages(): List<OrphanScanResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = activeRoot() ?: return@withLock emptyList()
            val books = listChildren(root).filter { it.isDirectory && !it.name.startsWith(".") }
            val results = mutableListOf<OrphanScanResult>()

            for (bookNode in books) {
                val bookId = bookIdOf(bookNode)
                val subpagesDir = findChild(bookNode, SUBPAGES_DIR) ?: continue
                val subpageFiles = listChildren(subpagesDir).filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                if (subpageFiles.isEmpty()) continue

                val chaptersDir = findChild(bookNode, CHAPTERS_DIR) ?: bookNode
                val chapterFiles = listChildren(chaptersDir).filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                val allChapterTexts = chapterFiles.mapNotNull { readText(it) }
                val allSubpageTexts = subpageFiles.associate { it.name.removeSuffix(".md") to (readText(it) ?: "") }

                val orphans = mutableListOf<String>()
                for (subpage in subpageFiles) {
                    val pageId = subpage.name.removeSuffix(".md")
                    val linkTarget = "kepler-page://$pageId"
                    val foundInChapters = allChapterTexts.any { it.contains(linkTarget) }
                    val foundInSiblings = allSubpageTexts.entries.any { it.key != pageId && it.value.contains(linkTarget) }

                    if (!foundInChapters && !foundInSiblings) {
                        orphans.add(pageId)
                    }
                }

                if (orphans.isNotEmpty()) {
                    val metaTitle = findChild(bookNode, BOOK_META_FILE)?.let { readText(it) }?.let {
                        try { (gson.fromJson(it, Map::class.java)["title"] as? String) } catch (_: Exception) { null }
                    } ?: bookNode.name
                    results.add(OrphanScanResult(bookId, metaTitle, orphans))
                }
            }
            results
        }
    }

    suspend fun deleteOrphanSubpages(bookId: String, orphanIds: List<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = findBook(bookId) ?: return@withLock
            val subpagesDir = findChild(book, SUBPAGES_DIR) ?: return@withLock
            for (id in orphanIds) {
                findChild(subpagesDir, "$id.md")?.let { deleteNode(it) }
                db.dao().deletePageAndChildren(bookId, id)
            }
            touchBookMeta(book)
        }
    }

    suspend fun createBook(
        title: String,
        description: String,
        tags: String,
        preset: String,
        coverImage: String?
    ): NotebookEntity? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ws = getActiveWorkspace() ?: return@withLock null
            val root = activeRoot() ?: return@withLock null
            val folderName = uniqueBookFolderName(root, title)
            val book = createDirectory(root, folderName) ?: return@withLock null
            val chaptersDir = createDirectory(book, CHAPTERS_DIR) ?: book
            createDirectory(book, SUBPAGES_DIR)
            createDirectory(book, VISUALS_DIR)

            val bookId = "book-" + UUID.randomUUID().toString().take(8)
            val now = System.currentTimeMillis()
            val meta = linkedMapOf<String, Any?>(
                "id" to bookId,
                "title" to title,
                "description" to description,
                "coverPreset" to preset,
                "coverImage" to coverImage,
                "showTitleOnCover" to true,
                "tags" to tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                "isPinned" to false,
                "createdAt" to now,
                "updatedAt" to now
            )
            writeText(book, BOOK_META_FILE, gson.toJson(meta), "application/json")
            val chId = "ch-" + UUID.randomUUID().toString().take(4)
            val chapterName = chapterFileName(1, chId, "Introduction")
            val chapterContent = "# $title\n\nWelcome to your notebook.\n"
            writeText(chaptersDir, chapterName, chapterContent, "text/markdown")

            val notebook = NotebookEntity(
                id = bookId,
                workspaceId = ws.id,
                title = title,
                description = description,
                ownerSub = "local_vault",
                visibility = "public",
                coverPreset = preset,
                coverImage = coverImage,
                showTitleOnCover = true,
                tagsCsv = tags,
                createdAt = now,
                updatedAt = now,
                notesUpdatedAt = now
            )
            db.dao().upsertNotebook(notebook)
            db.dao().upsertPage(
                PageEntity(
                    id = chId,
                    notebookId = bookId,
                    title = "Introduction",
                    notes = chapterContent,
                    position = 0
                )
            )
            notebook
        }
    }

    suspend fun deleteBook(notebookId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            findBook(notebookId)?.let { deleteNode(it) }
            db.dao().deleteNotebookCascade(notebookId)
        }
    }

    suspend fun updateBookConfig(
        notebook: NotebookEntity,
        title: String,
        description: String,
        tags: String,
        preset: String,
        coverImage: String?,
        showTitleOnCover: Boolean
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = findBook(notebook.id) ?: return@withLock
            val now = System.currentTimeMillis()
            val meta = findChild(book, BOOK_META_FILE)
                ?.let { readText(it) }
                ?.let { readMetaMap(it) }
                ?: linkedMapOf()
            meta["id"] = notebook.id
            meta["title"] = title
            meta["description"] = description
            meta["coverPreset"] = preset
            meta["coverImage"] = coverImage
            meta["showTitleOnCover"] = showTitleOnCover
            meta["tags"] = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            meta["updatedAt"] = now
            writeText(book, BOOK_META_FILE, gson.toJson(meta), "application/json")
            val updated = notebook.copy(
                title = title,
                description = description,
                tagsCsv = tags,
                coverPreset = preset,
                coverImage = coverImage,
                showTitleOnCover = showTitleOnCover,
                updatedAt = now
            )
            // Use updateNotebook to prevent SQLite OnConflictStrategy.REPLACE from
            // deleting the notebook row and triggering ON DELETE CASCADE on child pages.
            db.dao().updateNotebook(updated)
            if (db.dao().getNotebookById(notebook.id) == null) {
                db.dao().upsertNotebook(updated)
            }
        }
    }

    private fun touchBookMeta(book: Node) {
        val metaNode = findChild(book, BOOK_META_FILE) ?: return
        val raw = readText(metaNode) ?: return
        try {
            val type = object : TypeToken<MutableMap<String, Any?>>() {}.type
            val map: MutableMap<String, Any?> = gson.fromJson(raw, type)
            map["updatedAt"] = System.currentTimeMillis()
            writeText(book, BOOK_META_FILE, gson.toJson(map), "application/json")
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ questions

    suspend fun saveQuestions(notebookId: String, questions: List<QuestionEntity>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val book = findBook(notebookId) ?: return@withLock
                val payload = questions.sortedBy { it.position }.map {
                    mapOf(
                        "id" to it.id,
                        "title" to it.title,
                        "answer" to it.answer,
                        "position" to it.position
                    )
                }
                writeText(book, QUESTIONS_FILE, gson.toJson(payload), "application/json")
                touchBookMeta(book)
            }
        }

    // -------------------------------------------------------------- visuals

    suspend fun saveChapterVisual(
        notebookId: String,
        chapterIndex1Based: Int,
        visualTitle: String,
        htmlContent: String
    ): CanvasEntity? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = findBook(notebookId) ?: return@withLock null
            val visuals = findChild(book, VISUALS_DIR) ?: createDirectory(book, VISUALS_DIR)
                ?: return@withLock null

            val safeTitle = visualTitle.lowercase().replace(Regex("[^a-z0-9]+"), "_")
            val fileName = String.format("%02d_%s.html", chapterIndex1Based, safeTitle)

            if (!writeText(visuals, fileName, htmlContent, "text/html")) return@withLock null
            touchBookMeta(book)

            CanvasEntity(
                id = canvasId(notebookId, fileName),
                notebookId = notebookId,
                kind = "html",
                title = visualTitle.ifBlank { "Companion Visual" },
                payloadJson = htmlContent,
                assignedChapter = chapterIndex1Based
            )
        }
    }

    suspend fun deleteCanvas(notebookId: String, canvasId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = findBook(notebookId) ?: return@withLock
            val visuals = findChild(book, VISUALS_DIR) ?: return@withLock
            listChildren(visuals)
                .firstOrNull { !it.isDirectory && canvasId(notebookId, it.name) == canvasId }
                ?.let {
                    deleteNode(it)
                    touchBookMeta(book)
                }
        }
    }

    // ---------------------------------------------------------- import/export

    suspend fun importBook(imported: ImportedBook) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ws = getActiveWorkspace() ?: return@withLock
            val root = activeRoot() ?: return@withLock
            val folderName = uniqueBookFolderName(root, imported.title)
            val book = createDirectory(root, folderName) ?: return@withLock
            val now = System.currentTimeMillis()
            val meta = linkedMapOf<String, Any?>(
                "id" to imported.id,
                "title" to imported.title,
                "description" to imported.description,
                "coverPreset" to imported.coverPreset,
                "coverImage" to imported.coverImage,
                "tags" to imported.tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                "isPinned" to false,
                "showTitleOnCover" to true,
                "createdAt" to now,
                "updatedAt" to now
            )
            writeText(book, BOOK_META_FILE, gson.toJson(meta), "application/json")

            imported.pages.sortedBy { it.position }.forEachIndexed { idx, page ->
                writeText(
                    book,
                    chapterFileName(idx + 1, page.title),
                    page.notes,
                    "text/markdown"
                )
            }

            if (imported.questions.isNotEmpty()) {
                val payload = imported.questions.sortedBy { it.position }.map {
                    mapOf(
                        "id" to it.id,
                        "title" to it.title,
                        "answer" to it.answer,
                        "position" to it.position
                    )
                }
                writeText(book, QUESTIONS_FILE, gson.toJson(payload), "application/json")
            }

            if (imported.canvases.isNotEmpty()) {
                val visuals = createDirectory(book, VISUALS_DIR)
                if (visuals != null) {
                    imported.canvases.forEachIndexed { idx, canvas ->
                        val ext = if (canvas.kind == "html") "html" else "json"
                        val name = String.format("%02d_%s.%s", canvas.assignedChapter, safeFileName(canvas.title), ext)
                        writeText(visuals, name, canvas.payloadJson, if (ext == "html") "text/html" else "application/json")
                    }
                }
            }
        }
        syncWorkspaceToLocalDatabase()
    }

    fun exportToDownloads(): String {
        val root = kotlinx.coroutines.runBlocking { activeRoot() }
            ?: throw IllegalStateException("No active workspace folder selected")

        val fileName = "kepler-workspace-${System.currentTimeMillis() / 1000}.zip"
        val outputStream: OutputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create Downloads entry")
            resolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot open output stream")
        } else {
            val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            if (!downloads.exists()) downloads.mkdirs()
            File(downloads, fileName).outputStream()
        }

        ZipOutputStream(outputStream).use { zip ->
            writeZipNode(root, zip, "")
        }
        return fileName
    }

    private fun writeZipNode(parent: Node, zip: ZipOutputStream, prefix: String) {
        listChildren(parent).forEach { child ->
            if (child.isDirectory) {
                writeZipNode(child, zip, "$prefix${child.name}/")
            } else {
                val entryName = "$prefix${child.name}"
                zip.putNextEntry(ZipEntry(entryName))
                openNodeStream(child)?.use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun openNodeStream(node: Node): InputStream? = try {
        if (node.file != null) node.file.inputStream()
        else node.doc?.let { context.contentResolver.openInputStream(it.uri) }
    } catch (_: Exception) {
        null
    }

    // --------------------------------------------------------------- parsing

    private fun readMetaMap(raw: String): MutableMap<String, Any?>? = try {
        val type = object : TypeToken<MutableMap<String, Any?>>() {}.type
        gson.fromJson<MutableMap<String, Any?>>(raw, type)
    } catch (_: Exception) {
        null
    }

    private fun parseQuestions(raw: String, bookId: String): List<QuestionEntity> {
        return try {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val list: List<Map<String, Any?>> = gson.fromJson(raw, type) ?: emptyList()
            list.mapIndexed { idx, q ->
                QuestionEntity(
                    id = (q["id"] as? String)?.takeIf { it.isNotBlank() } ?: "q-$bookId-$idx",
                    notebookId = bookId,
                    title = (q["title"] as? String) ?: "Question ${idx + 1}",
                    answer = (q["answer"] as? String) ?: "",
                    position = (q["position"] as? Number)?.toInt() ?: idx
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractChapterTitle(content: String, fileName: String, idx: Int): String {
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("---")) continue
            if (trimmed.startsWith("#")) {
                return trimmed.trimStart('#', ' ', '\t').take(100)
            }
            return trimmed.take(100)
        }
        val clean = fileName.removeSuffix(".md").replace(Regex("^\\d+_[a-zA-Z0-9\\-]+_"), "").replace(Regex("^[0-9._\\-\\s]+"), "")
        return clean.ifEmpty { "Chapter ${idx + 1}" }.replace("_", " ")
    }

    private fun extractSubpageTitle(content: String, fallbackId: String): String {
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("---")) continue
            if (trimmed.startsWith("#")) {
                return trimmed.trimStart('#', ' ', '\t').take(100)
            }
            return trimmed.take(100)
        }
        return fallbackId
    }

    private fun visualTitle(fileName: String): String =
        stripNumericPrefix(fileName.substringBeforeLast('.'))
            .replace('_', ' ')
            .ifEmpty { "Companion Visual" }

    private fun stripNumericPrefix(name: String): String =
        name.replace(Regex("^[0-9._\\-\\s]+"), "").replace(Regex("[_\\-]+"), " ").trim()

    private fun chapterIndexFromName(name: String): Int? =
        Regex("^0*(\\d{1,3})(?=[_.\\s\\-]|$)").find(name)?.groupValues?.get(1)?.toIntOrNull()

    private fun derivedBookId(folderName: String): String =
        "book-" + UUID.nameUUIDFromBytes(folderName.toByteArray(Charsets.UTF_8)).toString().take(8)

    private fun pageId(bookId: String, fileName: String): String = "p-$bookId-$fileName"

    private fun canvasId(bookId: String, fileName: String): String = "c-$bookId-$fileName"

    private fun safeFileName(input: String): String =
        input.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
            .ifEmpty { "untitled" }

    private fun chapterFileName(index: Int, chId: String, title: String): String =
        String.format("%02d_%s_%s.md", index, chId, safeFileName(title).replace(" ", "_"))

    private fun chapterFileName(index: Int, title: String): String =
        chapterFileName(index, "ch-" + UUID.randomUUID().toString().take(4), title)

    private fun uniqueBookFolderName(root: Node, title: String): String {
        val base = safeFileName(title).ifEmpty { "New Book" }
        val existing = listChildren(root)
            .filter { it.isDirectory }
            .map { it.name.lowercase(Locale.ROOT) }
            .toHashSet()
        var candidate = base
        var counter = 2
        while (candidate.lowercase(Locale.ROOT) in existing) {
            candidate = "$base ($counter)"
            counter++
        }
        return candidate
    }

    private fun naturalCompare(a: String, b: String): Int {
        val regex = Regex("\\d+|\\D+")
        val aParts = regex.findAll(a).map { it.value }.toList()
        val bParts = regex.findAll(b).map { it.value }.toList()
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val ap = aParts.getOrNull(i) ?: return -1
            val bp = bParts.getOrNull(i) ?: return 1
            val cmp = if (ap[0].isDigit() && bp[0].isDigit()) {
                ap.toLongOrNull()?.compareTo(bp.toLongOrNull() ?: 0) ?: ap.compareTo(bp)
            } else {
                ap.compareTo(bp, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
