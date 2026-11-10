package com.esper.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import com.esper.android.data.export.ZipImporter
import com.esper.android.data.local.EsperDatabase
import com.esper.android.data.model.*
import com.esper.android.data.vault.VaultManager
import com.esper.android.ui.bookshelf.AllTagsScreen
import com.esper.android.ui.bookshelf.BookshelfFiltering
import com.esper.android.ui.bookshelf.BookshelfScreen
import com.esper.android.ui.bookshelf.ConfigureNotebookScreen
import com.esper.android.ui.bookshelf.CreateNotebookScreen
import com.esper.android.ui.bookshelf.SettingsScreen
import com.esper.android.ui.journal.JournalScreen
import com.esper.android.ui.theme.EsperTheme
import com.esper.android.ui.workspace.QuestionEditorScreen
import com.esper.android.ui.workspace.RecentPageItem
import com.esper.android.ui.workspace.TabItemData
import com.esper.android.ui.workspace.TabsBottomSheet
import com.esper.android.ui.workspace.WorkspaceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class ActiveScreen {
    BOOKSHELF,
    ALL_TAGS,
    SETTINGS,
    CREATE_NOTEBOOK,
    CONFIGURE_NOTEBOOK,
    WORKSPACE,
    JOURNAL
}

class MainActivity : ComponentActivity() {

    private val db by lazy {
        Room.databaseBuilder(applicationContext, EsperDatabase::class.java, "esper_offline.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    private val vaultManager by lazy {
        VaultManager(applicationContext, db)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            EsperTheme {
                val coroutineScope = rememberCoroutineScope()
                var screenStack by remember { mutableStateOf(listOf(ActiveScreen.BOOKSHELF)) }
                val currentScreen = screenStack.lastOrNull() ?: ActiveScreen.BOOKSHELF

                var activeNotebookId by remember { mutableStateOf<String?>(null) }
                var activePageIndex by remember { mutableIntStateOf(0) }
                var activeEditingQuestion by remember { mutableStateOf<QuestionEntity?>(null) }
                var configuringNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
                var selectedTagFilter by remember { mutableStateOf("All") }

                var openTabItems by remember { mutableStateOf(emptyList<TabItemData>()) }
                var recentPageItems by remember { mutableStateOf<List<RecentPageItem>>(emptyList()) }
                var isTabsSheetOpen by remember { mutableStateOf(false) }

                var vaultName by remember { mutableStateOf(vaultManager.getActiveVaultName()) }
                var vaultLocation by remember { mutableStateOf(vaultManager.getActiveVaultLocation()) }

                val notebooks by db.dao().observeAllNotebooks().collectAsState(initial = emptyList())
                val shelves by db.dao().observeAllShelves().collectAsState(initial = emptyList())
                val allDetails by db.dao().observeAllNotebooksWithDetails().collectAsState(initial = emptyList())

                suspend fun rescanVault() {
                    withContext(Dispatchers.IO) {
                        vaultManager.seedDefaultVaultIfEmpty()
                        vaultManager.syncVaultToLocalDatabase()
                    }
                    vaultName = vaultManager.getActiveVaultName()
                    vaultLocation = vaultManager.getActiveVaultLocation()
                }

                // First run: create the starter vault, then index whatever folder is active.
                LaunchedEffect(Unit) {
                    rescanVault()
                }

                fun navigateTo(screen: ActiveScreen) {
                    if (screen == ActiveScreen.BOOKSHELF) {
                        screenStack = listOf(ActiveScreen.BOOKSHELF)
                    } else if (screenStack.lastOrNull() != screen) {
                        screenStack = screenStack + screen
                    }
                }

                fun popScreen() {
                    if (activeEditingQuestion != null) {
                        activeEditingQuestion = null
                    } else if (screenStack.size > 1) {
                        screenStack = screenStack.dropLast(1)
                        if (screenStack.lastOrNull() != ActiveScreen.WORKSPACE) {
                            activeNotebookId = null
                        }
                    } else {
                        screenStack = listOf(ActiveScreen.BOOKSHELF)
                        activeNotebookId = null
                    }
                }

                BackHandler(enabled = screenStack.size > 1 || activeEditingQuestion != null) {
                    popScreen()
                }

                val resolvedTabs = remember(openTabItems, notebooks) {
                    openTabItems.filter { item ->
                        notebooks.isEmpty() || notebooks.any { it.id == item.notebook.id }
                    }.map { item ->
                        val nb = notebooks.find { it.id == item.notebook.id } ?: item.notebook
                        item.copy(notebook = nb)
                    }
                }
                val resolvedRecent = remember(recentPageItems, notebooks) {
                    recentPageItems.filter { item ->
                        notebooks.isEmpty() || notebooks.any { it.id == item.notebook.id }
                    }.map { item ->
                        val nb = notebooks.find { it.id == item.notebook.id } ?: item.notebook
                        item.copy(notebook = nb)
                    }
                }

                fun selectTab(docId: String, pageIndex: Int) {
                    val details = allDetails.find { it.notebook.id == docId }
                    val pages = details?.pages?.sortedWith(compareBy({ it.position }, { it.id })) ?: emptyList()
                    val safeIndex = pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    val page = pages.getOrNull(safeIndex)
                    val now = System.currentTimeMillis()
                    activeNotebookId = docId
                    activePageIndex = safeIndex
                    openTabItems = openTabItems.map { item ->
                        if (item.notebook.id == docId) {
                            item.copy(
                                pageNumber = safeIndex + 1,
                                pageId = page?.id ?: item.pageId,
                                lastOpenedAt = now
                            )
                        } else item
                    }.let {
                        if (it.any { t -> t.notebook.id == docId }) it
                        else {
                            val nb = notebooks.find { n -> n.id == docId } ?: details?.notebook
                            if (nb != null) {
                                it + TabItemData(
                                    notebook = nb,
                                    pageNumber = safeIndex + 1,
                                    pageId = page?.id,
                                    lastOpenedAt = now
                                )
                            } else it
                        }
                    }
                    navigateTo(ActiveScreen.WORKSPACE)
                }

                fun openBookDirect(id: String) {
                    val now = System.currentTimeMillis()
                    val existing = openTabItems.find { it.notebook.id == id }
                    if (existing == null) {
                        notebooks.find { it.id == id }?.let { nb ->
                            openTabItems = openTabItems + TabItemData(
                                notebook = nb,
                                pageNumber = 1,
                                pageId = null,
                                lastOpenedAt = now
                            )
                        }
                        activeNotebookId = id
                        activePageIndex = 0
                    } else {
                        openTabItems = openTabItems.map {
                            if (it.notebook.id == id) it.copy(lastOpenedAt = now) else it
                        }
                        activeNotebookId = id
                        activePageIndex = (existing.pageNumber - 1).coerceAtLeast(0)
                    }
                    navigateTo(ActiveScreen.WORKSPACE)
                }

                suspend fun persistQuestions(bookId: String) {
                    val details = db.dao().getNotebookDetails(bookId) ?: return
                    vaultManager.saveQuestions(bookId, details.questions)
                }

                var customTags by remember {
                    mutableStateOf(listOf("Astrodynamics", "Physics", "Quantum", "Research"))
                }

                val allTags = remember(notebooks, customTags) {
                    val extracted = notebooks.filter {
                        !it.title.contains("Journal", ignoreCase = true) &&
                            !it.tagsCsv.split(",").any { tag -> tag.trim().equals("Journal", true) }
                    }
                        .flatMap { it.tagsCsv.split(",") }
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    listOf("All") + (customTags + extracted).distinct()
                }

                val journalNotebook = remember(notebooks) {
                    notebooks.find {
                        it.title.contains("Journal", ignoreCase = true) ||
                            it.tagsCsv.split(",").any { tag -> tag.trim().equals("Journal", true) }
                    }
                }

                if (isTabsSheetOpen) {
                    TabsBottomSheet(
                        openTabs = resolvedTabs,
                        recentPages = resolvedRecent,
                        activeNotebookId = activeNotebookId ?: "",
                        onSelectTab = { docId, pageIndex ->
                            selectTab(docId, pageIndex)
                            isTabsSheetOpen = false
                        },
                        onCloseTab = { closedId ->
                            val remaining = openTabItems.filter { it.notebook.id != closedId }
                            openTabItems = remaining
                            if (activeNotebookId == closedId) {
                                val next = remaining.lastOrNull()
                                activeNotebookId = next?.notebook?.id
                                activePageIndex = (next?.pageNumber ?: 1) - 1
                                if (activeNotebookId == null) navigateTo(ActiveScreen.BOOKSHELF)
                            }
                        },
                        onCloseAllTabs = {
                            openTabItems = emptyList()
                            activeNotebookId = null
                            isTabsSheetOpen = false
                            navigateTo(ActiveScreen.BOOKSHELF)
                        },
                        onNewTabClick = {
                            isTabsSheetOpen = false
                            navigateTo(ActiveScreen.BOOKSHELF)
                        },
                        onDismiss = { isTabsSheetOpen = false }
                    )
                }

                when (currentScreen) {
                    ActiveScreen.ALL_TAGS -> {
                        AllTagsScreen(
                            allTags = allTags,
                            notebooks = notebooks,
                            selectedTag = selectedTagFilter,
                            onSelectTag = { tag -> selectedTagFilter = tag },
                            onCreateTag = { newTag ->
                                if (!customTags.contains(newTag)) customTags = customTags + newTag
                            },
                            onRenameTag = { oldTag, newTag ->
                                customTags = customTags.map { if (it == oldTag) newTag else it }
                                if (selectedTagFilter == oldTag) selectedTagFilter = newTag
                            },
                            onDeleteTag = { tag ->
                                customTags = customTags.filter { it != tag }
                                if (selectedTagFilter == tag) selectedTagFilter = "All"
                            },
                            onNavigateBack = { popScreen() }
                        )
                    }

                    ActiveScreen.JOURNAL -> {
                        val journalId = journalNotebook?.id ?: "journal-default"
                        val journalDetails by db.dao().observeNotebookDetails(journalId)
                            .collectAsState(initial = null)
                        val journalPages = remember(journalDetails) {
                            (journalDetails?.pages ?: emptyList()).sortedBy { it.position }
                        }

                        JournalScreen(
                            pages = journalPages,
                            onSavePageNotes = { pageId, text ->
                                val onDisk = vaultManager.saveChapterNotes(journalId, pageId, text)
                                val derivedTitle = deriveChapterTitle(text)
                                db.dao().updatePageNotesAndTitle(journalId, pageId, text, derivedTitle)
                                db.dao().updateNotebookNotesUpdatedAt(journalId, System.currentTimeMillis())
                                onDisk
                            },
                            onCreateNewPage = { dateTitle ->
                                coroutineScope.launch {
                                    val created = vaultManager.createChapter(journalId, dateTitle)
                                    if (created != null) {
                                        db.dao().upsertPage(created)
                                    } else {
                                        val newPageId = "jp-${UUID.randomUUID().toString().take(8)}"
                                        db.dao().upsertPage(
                                            PageEntity(
                                                id = newPageId,
                                                notebookId = journalId,
                                                title = dateTitle,
                                                notes = "# $dateTitle\n\n",
                                                position = journalPages.size
                                            )
                                        )
                                    }
                                }
                            },
                            onDeletePage = { pageId ->
                                coroutineScope.launch {
                                    vaultManager.deleteChapter(journalId, pageId)
                                    db.dao().deletePageAndChildren(journalId, pageId)
                                }
                            },
                            onSyncWithRemote = {
                                withContext(Dispatchers.IO) {
                                    vaultManager.syncVaultToLocalDatabase()
                                }
                            },
                            onNavigateBack = { popScreen() }
                        )
                    }

                    ActiveScreen.SETTINGS -> {
                        SettingsScreen(
                            vaultName = vaultName,
                            vaultLocation = vaultLocation,
                            notebookCount = notebooks.size,
                            shelfCount = shelves.size,
                            onRescanVault = {
                                coroutineScope.launch {
                                    rescanVault()
                                    Toast.makeText(applicationContext, "Vault scanned & indexed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSelectVaultFolder = { uri ->
                                try {
                                    contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    )
                                } catch (_: Exception) {
                                    // Some providers grant only temporary access; still try to open it.
                                }
                                val display = DocumentFile.fromTreeUri(applicationContext, uri)?.name
                                    ?: uri.lastPathSegment
                                    ?: "External Vault"
                                vaultManager.setCustomVaultUri(uri, display)
                                coroutineScope.launch {
                                    rescanVault()
                                    Toast.makeText(applicationContext, "Vault folder loaded", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onResetToDefaultVault = {
                                vaultManager.setCustomVaultUri(null)
                                coroutineScope.launch {
                                    rescanVault()
                                    Toast.makeText(applicationContext, "Switched to internal vault", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onExportZip = {
                                coroutineScope.launch {
                                    try {
                                        val fileName = withContext(Dispatchers.IO) {
                                            vaultManager.exportToDownloads()
                                        }
                                        Toast.makeText(applicationContext, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(applicationContext, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onImportPackage = { uri ->
                                coroutineScope.launch {
                                    try {
                                        val imported = withContext(Dispatchers.IO) {
                                            ZipImporter.importPackage(applicationContext, uri, db, "local_vault")
                                        }
                                        vaultManager.importBook(imported)
                                        Toast.makeText(
                                            applicationContext,
                                            "Imported: ${imported.title}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        openBookDirect(imported.id)
                                    } catch (e: Exception) {
                                        Toast.makeText(applicationContext, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onNavigateBack = { popScreen() }
                        )
                    }

                    ActiveScreen.CREATE_NOTEBOOK -> {
                        CreateNotebookScreen(
                            onSaveNotebook = { title, desc, tags, preset, coverImage ->
                                coroutineScope.launch {
                                    val newBook = vaultManager.createBook(title, desc, tags, preset, coverImage)
                                    if (newBook != null) {
                                        openBookDirect(newBook.id)
                                    } else {
                                        Toast.makeText(applicationContext, "Could not create book folder", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onNavigateBack = { popScreen() }
                        )
                    }

                    ActiveScreen.CONFIGURE_NOTEBOOK -> {
                        configuringNotebook?.let { targetBook ->
                            key(targetBook.id) {
                                ConfigureNotebookScreen(
                                    notebook = targetBook,
                                    shelves = shelves,
                                    onToggleBookShelf = { bookId, shelfId ->
                                        coroutineScope.launch {
                                            val targetShelf = shelves.find { it.id == shelfId } ?: return@launch
                                            val updatedCsv = BookshelfFiltering.toggleBookOnShelf(targetShelf.notebookIdsCsv, bookId)
                                            db.dao().upsertShelf(
                                                targetShelf.copy(
                                                    notebookIdsCsv = updatedCsv,
                                                    updatedAt = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    },
                                    onCreateShelf = { title, desc, color, initialBookId ->
                                        coroutineScope.launch {
                                            val newShelfId = "shelf-${UUID.randomUUID().toString().take(8)}"
                                            val initialIds = if (initialBookId != null) listOf(initialBookId) else emptyList()
                                            db.dao().upsertShelf(
                                                ShelfEntity(
                                                    id = newShelfId,
                                                    title = title,
                                                    description = desc,
                                                    color = color,
                                                    coverPreset = "midnight",
                                                    position = shelves.size,
                                                    notebookIdsCsv = initialIds.joinToString(","),
                                                    updatedAt = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    },
                                    onSaveConfig = { title, desc, tags, preset, coverImage, _, _, isBookmarked ->
                                        coroutineScope.launch {
                                            vaultManager.updateBookConfig(
                                                notebook = targetBook,
                                                title = title,
                                                description = desc,
                                                tags = tags,
                                                preset = preset,
                                                coverImage = coverImage,
                                                isPinned = isBookmarked
                                            )
                                            Toast.makeText(applicationContext, "Book settings updated", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onNavigateBack = { popScreen() }
                                )
                            }
                        }
                    }

                    ActiveScreen.WORKSPACE -> {
                        if (activeEditingQuestion != null) {
                            val editing = activeEditingQuestion!!
                            QuestionEditorScreen(
                                question = editing,
                                onNavigateBack = { activeEditingQuestion = null },
                                onSaveAnswer = { qId, ans ->
                                    coroutineScope.launch {
                                        db.dao().updateQuestionAnswer(qId, ans)
                                        persistQuestions(editing.notebookId)
                                        activeEditingQuestion = db.dao().getNotebookDetails(editing.notebookId)
                                            ?.questions?.find { it.id == qId }
                                    }
                                },
                                onUpdateTitle = { qId, newTitle ->
                                    coroutineScope.launch {
                                        db.dao().updateQuestionTitle(qId, newTitle)
                                        persistQuestions(editing.notebookId)
                                    }
                                }
                            )
                        } else if (activeNotebookId != null) {
                            val workspaceBookId = activeNotebookId!!
                            val details by db.dao().observeNotebookDetails(workspaceBookId)
                                .collectAsState(initial = null)
                            key(workspaceBookId) {
                                details?.let { book ->
                                    val sortedPages = book.pages.sortedWith(compareBy({ it.position }, { it.id }))
                                    WorkspaceScreen(
                                        notebookTitle = book.notebook.title,
                                        pages = book.pages,
                                        canvases = book.canvases,
                                        questions = book.questions,
                                        activePageIndex = activePageIndex,
                                        onOpenTabsScreen = { isTabsSheetOpen = true },
                                        onOpenJournal = { navigateTo(ActiveScreen.JOURNAL) },
                                        onOpenConfigureBook = {
                                            configuringNotebook = book.notebook
                                            navigateTo(ActiveScreen.CONFIGURE_NOTEBOOK)
                                        },
                                        onNavigateBack = {
                                            activeNotebookId = null
                                            navigateTo(ActiveScreen.BOOKSHELF)
                                        },
                                        onSelectPage = { idx ->
                                            activePageIndex = idx
                                            val selectedPage = sortedPages.getOrNull(idx)
                                            openTabItems = openTabItems.map { item ->
                                                if (item.notebook.id == book.notebook.id) {
                                                    item.copy(
                                                        pageNumber = idx + 1,
                                                        pageId = selectedPage?.id ?: item.pageId
                                                    )
                                                } else item
                                            }
                                            if (selectedPage != null) {
                                                recentPageItems = (
                                                    listOf(
                                                        RecentPageItem(
                                                            notebook = book.notebook,
                                                            page = selectedPage,
                                                            pageNumber = idx + 1
                                                        )
                                                    ) + recentPageItems.filterNot {
                                                        it.notebook.id == book.notebook.id && it.page.id == selectedPage.id
                                                    }
                                                ).take(8)
                                            }
                                        },
                                        onCreatePage = { title, _ ->
                                            coroutineScope.launch {
                                                val created = vaultManager.createChapter(book.notebook.id, title)
                                                if (created != null) db.dao().upsertPage(created)
                                            }
                                        },
                                        onDeletePage = { pageId ->
                                            coroutineScope.launch {
                                                vaultManager.deleteChapter(book.notebook.id, pageId)
                                                db.dao().deletePageAndChildren(book.notebook.id, pageId)
                                                val remaining = db.dao().getPagesForNotebook(book.notebook.id)
                                                if (activePageIndex >= remaining.size) {
                                                    activePageIndex = (remaining.size - 1).coerceAtLeast(0)
                                                }
                                            }
                                        },
                                        onSaveNotes = { pageId, text ->
                                            val onDisk = vaultManager.saveChapterNotes(book.notebook.id, pageId, text)
                                            val derivedTitle = deriveChapterTitle(text)
                                            db.dao().updatePageNotesAndTitle(book.notebook.id, pageId, text, derivedTitle)
                                            db.dao().updateNotebookNotesUpdatedAt(book.notebook.id, System.currentTimeMillis())
                                            onDisk
                                        },
                                        onAttachCanvas = { title, kind, payload ->
                                            coroutineScope.launch {
                                                val activePageId = sortedPages.getOrNull(activePageIndex)?.id ?: ""
                                                val created = vaultManager.createCanvas(
                                                    notebookId = book.notebook.id,
                                                    title = title,
                                                    kind = kind,
                                                    payload = payload,
                                                    assignedPagesCsv = activePageId
                                                )
                                                if (created != null) db.dao().upsertCanvas(created)
                                            }
                                        },
                                        onDeleteCanvas = { canvasId ->
                                            coroutineScope.launch {
                                                vaultManager.deleteCanvas(book.notebook.id, canvasId)
                                                db.dao().deleteCanvas(canvasId)
                                            }
                                        },
                                        onUpdateCanvasPayload = { canvasId, payload ->
                                            coroutineScope.launch {
                                                vaultManager.updateCanvasPayload(book.notebook.id, canvasId, payload)
                                                val existing = book.canvases.find { it.id == canvasId }
                                                if (existing != null) {
                                                    db.dao().upsertCanvas(existing.copy(payloadJson = payload))
                                                }
                                            }
                                        },
                                        onCreateQuestion = { qTitle ->
                                            coroutineScope.launch {
                                                val qId = "q-${UUID.randomUUID().toString().take(8)}"
                                                val newQ = QuestionEntity(
                                                    id = qId,
                                                    notebookId = book.notebook.id,
                                                    title = qTitle,
                                                    answer = "",
                                                    position = book.questions.size
                                                )
                                                db.dao().upsertQuestion(newQ)
                                                persistQuestions(book.notebook.id)
                                                activeEditingQuestion = newQ
                                            }
                                        },
                                        onDeleteQuestion = { qId ->
                                            coroutineScope.launch {
                                                db.dao().deleteQuestion(qId)
                                                persistQuestions(book.notebook.id)
                                            }
                                        },
                                        onOpenQuestionEditor = { question -> activeEditingQuestion = question },
                                        onSyncWithRemote = {
                                            withContext(Dispatchers.IO) {
                                                vaultManager.syncVaultToLocalDatabase()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    ActiveScreen.BOOKSHELF -> {
                        BookshelfScreen(
                            notebooks = notebooks,
                            shelves = shelves,
                            allDetails = allDetails,
                            selectedTagFilter = selectedTagFilter,
                            onSelectTagFilter = { selectedTagFilter = it },
                            onOpenNotebook = { id -> openBookDirect(id) },
                            onConfigureNotebook = { book ->
                                configuringNotebook = book
                                navigateTo(ActiveScreen.CONFIGURE_NOTEBOOK)
                            },
                            onDeleteNotebook = { id ->
                                coroutineScope.launch {
                                    vaultManager.deleteBook(id)
                                    openTabItems = openTabItems.filter { it.notebook.id != id }
                                }
                            },
                            onToggleBookmark = { bookId ->
                                coroutineScope.launch {
                                    val target = notebooks.find { it.id == bookId } ?: return@launch
                                    vaultManager.updateBookConfig(
                                        notebook = target,
                                        title = target.title,
                                        description = target.description,
                                        tags = target.tagsCsv,
                                        preset = target.coverPreset,
                                        coverImage = target.coverImage,
                                        isPinned = !target.isPinned
                                    )
                                }
                            },
                            onOpenAllTagsScreen = { navigateTo(ActiveScreen.ALL_TAGS) },
                            onOpenTabsScreen = { isTabsSheetOpen = true },
                            onOpenSettingsScreen = { navigateTo(ActiveScreen.SETTINGS) },
                            onOpenCreateNotebookScreen = { navigateTo(ActiveScreen.CREATE_NOTEBOOK) },
                            onOpenJournalScreen = {
                                if (journalNotebook == null) {
                                    coroutineScope.launch {
                                        vaultManager.createBook(
                                            title = "Personal Journal",
                                            description = "Daily journal, quick notes, and ideas.",
                                            tags = "Journal,Personal",
                                            preset = "celestial",
                                            coverImage = null
                                        )
                                    }
                                }
                                navigateTo(ActiveScreen.JOURNAL)
                            },
                            onDeleteShelf = { id ->
                                coroutineScope.launch { db.dao().deleteShelf(id) }
                            },
                            onCreateShelf = { title, desc, color, initialBookId ->
                                coroutineScope.launch {
                                    val newShelfId = "shelf-${UUID.randomUUID().toString().take(8)}"
                                    val initialIds = if (initialBookId != null) listOf(initialBookId) else emptyList()
                                    db.dao().upsertShelf(
                                        ShelfEntity(
                                            id = newShelfId,
                                            title = title,
                                            description = desc,
                                            color = color,
                                            coverPreset = "midnight",
                                            position = shelves.size,
                                            notebookIdsCsv = initialIds.joinToString(","),
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            },
                            onToggleBookShelf = { bookId, shelfId ->
                                coroutineScope.launch {
                                    val targetShelf = shelves.find { it.id == shelfId } ?: return@launch
                                    val updatedCsv = BookshelfFiltering.toggleBookOnShelf(targetShelf.notebookIdsCsv, bookId)
                                    db.dao().upsertShelf(
                                        targetShelf.copy(
                                            notebookIdsCsv = updatedCsv,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            },
                            onSyncWithRemote = {
                                coroutineScope.launch {
                                    rescanVault()
                                    Toast.makeText(applicationContext, "Vault re-indexed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Derives a chapter title from the first non-empty markdown line.
 */
internal fun deriveChapterTitle(markdown: String): String {
    val firstLine = markdown.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return "Untitled Chapter"
    val stripped = firstLine
        .replace(Regex("^[#\\s*_\\->`]+"), "")
        .replace(Regex("[#\\s*_\\->`]+$"), "")
        .trim()
        .take(100)
    return stripped.ifEmpty { "Untitled Chapter" }
}
