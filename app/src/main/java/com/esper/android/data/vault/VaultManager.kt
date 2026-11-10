package com.esper.android.data.vault

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
 * VaultManager owns Kepler workspace filesystem operations conforming to Kepler Data & Storage Spec.
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
        const val DEFAULT_WORKSPACE_NAME = "Primary Workspace"
        const val DEFAULT_WORKSPACE_ID = "default_local_workspace"
    }

    private data class Node(
        val name: String,
        val file: File? = null,
        val doc: DocumentFile? = null,
        val isDirectory: Boolean = false
    )

    fun getDefaultWorkspaceDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val workspaceDir = File(base, "KeplerWorkspace")
        if (!workspaceDir.exists()) workspaceDir.mkdirs()
        return workspaceDir
    }

    suspend fun getActiveWorkspace(): WorkspaceEntity = withContext(Dispatchers.IO) {
        val id = prefs.getString(KEY_ACTIVE_WORKSPACE_ID, DEFAULT_WORKSPACE_ID) ?: DEFAULT_WORKSPACE_ID
        var ws = db.dao().getWorkspaceById(id)
        if (ws == null) {
            ws = WorkspaceEntity(
                id = DEFAULT_WORKSPACE_ID,
                name = DEFAULT_WORKSPACE_NAME,
                treeUriString = null,
                isDefault = true,
                lastAccessedAt = System.currentTimeMillis()
            )
            db.dao().upsertWorkspace(ws)
        }
        ws
    }

    suspend fun setActiveWorkspace(workspaceId: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_ACTIVE_WORKSPACE_ID, workspaceId).apply()
        db.dao().getWorkspaceById(workspaceId)?.let {
            db.dao().upsertWorkspace(it.copy(lastAccessedAt = System.currentTimeMillis()))
        }
    }

    suspend fun addWorkspace(name: String, treeUri: Uri?): WorkspaceEntity = withContext(Dispatchers.IO) {
        val newId = "ws-" + UUID.randomUUID().toString().take(8)
        val entity = WorkspaceEntity(
            id = newId,
            name = name.ifBlank { "Workspace" },
            treeUriString = treeUri?.toString(),
            isDefault = false,
            lastAccessedAt = System.currentTimeMillis()
        )
        db.dao().upsertWorkspace(entity)
        setActiveWorkspace(newId)
        entity
    }

    suspend fun removeWorkspace(workspaceId: String) = withContext(Dispatchers.IO) {
        if (workspaceId == DEFAULT_WORKSPACE_ID) return@withContext
        db.dao().deleteWorkspace(workspaceId)
        val active = prefs.getString(KEY_ACTIVE_WORKSPACE_ID, DEFAULT_WORKSPACE_ID)
        if (active == workspaceId) {
            setActiveWorkspace(DEFAULT_WORKSPACE_ID)
        }
    }

    private suspend fun activeRoot(): Node? = withContext(Dispatchers.IO) {
        val ws = getActiveWorkspace()
        if (ws.treeUriString != null) {
            try {
                val doc = DocumentFile.fromTreeUri(context, Uri.parse(ws.treeUriString))
                if (doc != null && doc.exists() && doc.isDirectory) {
                    return@withContext Node(doc.name ?: ws.name, doc = doc, isDirectory = true)
                }
            } catch (_: Exception) {}
        }
        val dir = getDefaultWorkspaceDir()
        Node(dir.name, file = dir, isDirectory = true)
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
                File(parent.file, name).writeText(content, Charsets.UTF_8)
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
        node.file?.delete() ?: node.doc?.delete() ?: false
    } catch (_: Exception) {
        false
    }

    // --------------------------------------------------------------- scanning

    suspend fun seedDefaultWorkspaceIfEmpty() = withContext(Dispatchers.IO) {
        val ws = getActiveWorkspace()
        if (ws.treeUriString != null) return@withContext
        mutex.withLock {
            val root = activeRoot() ?: return@withLock
            val existingBooks = listChildren(root).filter { it.isDirectory && !it.name.startsWith(".") }
            if (existingBooks.isEmpty()) {
                seedBook(
                    root = root,
                    folderName = "Astrodynamics & Keplerian Orbits",
                    title = "Astrodynamics & Keplerian Orbits",
                    description = "Interactive celestial mechanics, vis-viva equations, and orbital transfers.",
                    preset = "solar",
                    tags = "Physics,Astrodynamics,Space",
                    chapters = listOf(
                        "Keplerian Motion" to "# Keplerian Motion\n\nPlanetary motion obeys Kepler's three laws:\n\n1. The orbit of a planet is an ellipse with the Sun at one of the two foci.\n2. A line segment joining a planet and the Sun sweeps out equal areas during equal intervals of time.\n3. The square of the orbital period is proportional to the cube of the semi-major axis.\n\n${'$'}${'$'}\\frac{T^2}{a^3} = \\frac{4\\pi^2}{G(M + m)}${'$'}${'$'}\n\nNotice how the areal velocity remains constant: ${'$'}${'$'}\\frac{dA}{dt} = \\frac{1}{2} r^2 \\dot{\\theta} = \\text{const}${'$'}${'$'}.\n",
                        "Vis-Viva Energy" to "# The Vis-Viva Equation\n\nFor any Keplerian orbit, the velocity at distance ${'$'}r${'$'} from the central body is determined by the Vis-Viva relation:\n\n${'$'}${'$'}v^2 = \\mu \\left( \\frac{2}{r} - \\frac{1}{a} \\right)${'$'}${'$'}\n\nWhere:\n- ${'$'}v${'$'} is orbital speed\n- ${'$'}\\mu = GM${'$'} is gravitational parameter\n- ${'$'}r${'$'} is orbital radius\n- ${'$'}a${'$'} is semi-major axis\n"
                    ),
                    questions = listOf(
                        "What is Kepler's First Law?" to "The orbit of a planet is an ellipse with the Sun at one of the two foci."
                    )
                )

                seedBook(
                    root = root,
                    folderName = "Quantum Information",
                    title = "Quantum Information & Qubits",
                    description = "Quantum computing fundamentals, Bloch spheres, and state evolution.",
                    preset = "quantum",
                    tags = "Quantum,Computing,Physics",
                    chapters = listOf(
                        "Qubit Representation" to "# The Quantum Bit (Qubit)\n\nUnlike a classical bit which is either ${'$'}0${'$'} or ${'$'}1${'$'}, a qubit exists in a linear superposition state:\n\n${'$'}${'$'}|\\psi\\rangle = \\alpha |0\\rangle + \\beta |1\\rangle${'$'}${'$'}\n\nWhere ${'$'}|\\alpha|^2 + |\\beta|^2 = 1${'$'}.\n"
                    ),
                    questions = listOf(
                        "What is the normalization condition?" to "${'$'}${'$'}|\\alpha|^2 + |\\beta|^2 = 1${'$'}${'$'} ensures total probability equals 1."
                    )
                )
            }
        }
        syncWorkspaceToLocalDatabase()
    }

    private fun seedBook(
        root: Node,
        folderName: String,
        title: String,
        description: String,
        preset: String,
        tags: String,
        chapters: List<Pair<String, String>>,
        questions: List<Pair<String, String>>
    ) {
        val dir = createDirectory(root, folderName) ?: return
        val now = System.currentTimeMillis()
        val meta = linkedMapOf<String, Any?>(
            "id" to "book-" + UUID.randomUUID().toString().take(8),
            "title" to title,
            "description" to description,
            "coverPreset" to preset,
            "tags" to tags.split(",").map { it.trim() },
            "isPinned" to false,
            "showTitleOnCover" to true,
            "createdAt" to now,
            "updatedAt" to now
        )
        writeText(dir, BOOK_META_FILE, gson.toJson(meta), "application/json")

        chapters.forEachIndexed { idx, (chTitle, content) ->
            writeText(dir, chapterFileName(idx + 1, chTitle), content, "text/markdown")
        }

        if (questions.isNotEmpty()) {
            val list = questions.mapIndexed { idx, (prompt, answer) ->
                mapOf(
                    "id" to "q-${System.currentTimeMillis() + idx}",
                    "title" to prompt,
                    "answer" to answer,
                    "position" to idx
                )
            }
            writeText(dir, QUESTIONS_FILE, gson.toJson(list), "application/json")
        }

        val visuals = createDirectory(dir, VISUALS_DIR)
        if (visuals != null) {
            writeText(visuals, "01_keplerian_orbit.html", DEFAULT_SIM_HTML, "text/html")
        }
    }

    suspend fun syncWorkspaceToLocalDatabase() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ws = getActiveWorkspace()
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

                val mdFiles = listChildren(bookNode)
                    .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }

                val pages = mdFiles.mapIndexed { idx, node ->
                    val content = readText(node) ?: ""
                    PageEntity(
                        id = pageId(bookId, node.name),
                        notebookId = bookId,
                        parentId = null,
                        title = extractChapterTitle(content, node.name, idx),
                        notes = content,
                        position = idx
                    )
                }

                db.dao().deletePagesForNotebook(bookId)
                if (pages.isNotEmpty()) db.dao().upsertPages(pages)

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
                val target = listChildren(book)
                    .firstOrNull { !it.isDirectory && pageId(notebookId, it.name) == pageId }
                    ?: return@withLock false
                val written = writeText(book, target.name, newNotes, "text/markdown")
                if (written) touchBookMeta(book)
                written
            }
        }

    suspend fun createChapter(notebookId: String, title: String): PageEntity? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = findBook(notebookId) ?: return@withLock null
            val existing = listChildren(book).filter {
                !it.isDirectory && it.name.endsWith(".md", ignoreCase = true)
            }
            val nextIndex = (existing.mapNotNull { chapterIndexFromName(it.name) }.maxOrNull() ?: 0) + 1
            val fileName = chapterFileName(nextIndex, title)
            val content = "# $title\n\n"
            if (!writeText(book, fileName, content, "text/markdown")) return@withLock null
            touchBookMeta(book)
            PageEntity(
                id = pageId(notebookId, fileName),
                notebookId = notebookId,
                title = title,
                notes = content,
                position = existing.size
            )
        }
    }

    suspend fun deleteChapter(notebookId: String, pageId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = findBook(notebookId) ?: return@withLock
            val target = listChildren(book)
                .firstOrNull { !it.isDirectory && pageId(notebookId, it.name) == pageId }
            if (target != null) {
                deleteNode(target)
                touchBookMeta(book)
            }
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
            val ws = getActiveWorkspace()
            val root = activeRoot() ?: return@withLock null
            val folderName = uniqueBookFolderName(root, title)
            val book = createDirectory(root, folderName) ?: return@withLock null
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
            val chapterName = chapterFileName(1, "Introduction")
            val chapterContent = "# $title\n\nWelcome to your notebook.\n"
            writeText(book, chapterName, chapterContent, "text/markdown")

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
                    id = pageId(bookId, chapterName),
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
            db.dao().upsertNotebook(
                notebook.copy(
                    title = title,
                    description = description,
                    tagsCsv = tags,
                    coverPreset = preset,
                    coverImage = coverImage,
                    showTitleOnCover = showTitleOnCover,
                    updatedAt = now
                )
            )
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
            val ws = getActiveWorkspace()
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
            val root = kotlinx.coroutines.runBlocking { activeRoot() } ?: throw IllegalStateException("Workspace not accessible")
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
        val clean = fileName.removeSuffix(".md").replace(Regex("^[0-9._\\-\\s]+"), "")
        return clean.ifEmpty { "Chapter ${idx + 1}" }.replace("_", " ")
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

    private fun chapterFileName(index: Int, title: String): String =
        String.format("%02d_%s.md", index, safeFileName(title).replace(" ", "_"))

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

private const val DEFAULT_SIM_HTML = """<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <style>
    * { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #020617; overflow: hidden; font-family: sans-serif; }
    canvas { display: block; width: 100vw; height: 100vh; }
    .hud { position: fixed; top: 10px; left: 10px; color: #818cf8; font-size: 11px; font-family: monospace; background: rgba(15, 23, 42, 0.85); padding: 5px 10px; border-radius: 8px; border: 1px solid rgba(129, 140, 248, 0.35); }
  </style>
</head>
<body>
  <div class="hud">● Kepler Orbit Simulation</div>
  <canvas id="c"></canvas>
  <script>
    const c = document.getElementById('c');
    const ctx = c.getContext('2d');
    function resize() { c.width = window.innerWidth; c.height = window.innerHeight; }
    window.addEventListener('resize', resize);
    resize();
    let t = 0;
    function frame() {
      ctx.fillStyle = '#020617';
      ctx.fillRect(0, 0, c.width, c.height);
      const cx = c.width / 2, cy = c.height / 2;
      const a = Math.min(cx, cy) * 0.7, b = a * 0.6;
      ctx.strokeStyle = 'rgba(129, 140, 248, 0.35)';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.ellipse(cx, cy, a, b, 0, 0, Math.PI * 2);
      ctx.stroke();
      ctx.fillStyle = '#fbbf24';
      ctx.beginPath();
      ctx.arc(cx - Math.sqrt(Math.max(0, a*a - b*b)), cy, 8, 0, Math.PI * 2);
      ctx.fill();
      t += 0.02;
      ctx.fillStyle = '#818cf8';
      ctx.beginPath();
      ctx.arc(cx + a * Math.cos(t), cy + b * Math.sin(t), 5, 0, Math.PI * 2);
      ctx.fill();
      requestAnimationFrame(frame);
    }
    frame();
  </script>
</body>
</html>"""
