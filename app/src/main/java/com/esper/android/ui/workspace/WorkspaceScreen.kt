package com.esper.android.ui.workspace

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.esper.android.data.model.CanvasEntity
import com.esper.android.data.model.PageEntity
import com.esper.android.data.model.QuestionEntity
import com.esper.android.ui.canvas.DrawingCanvasView
import com.esper.android.ui.components.ConfirmDeleteDialog
import com.esper.android.ui.components.EditorSkeleton
import com.esper.android.ui.components.LocalReadOnlyMode
import com.esper.android.ui.components.OfflineNoticeBanner
import com.esper.android.ui.components.SimulationSkeleton
import com.esper.android.ui.components.UnsavedChangesDialog
import com.esper.android.ui.components.UnsavedChangesGuard
import com.esper.android.ui.lexical.ComposeLexicalEditor
import com.esper.android.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ViewportMode {
    SPLIT,
    ATTACHMENT_FULL,
    EDITOR_FULL
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(
    notebookTitle: String,
    pages: List<PageEntity>,
    canvases: List<CanvasEntity>,
    questions: List<QuestionEntity>,
    activePageIndex: Int,
    onOpenTabsScreen: () -> Unit,
    onOpenJournal: () -> Unit = {},
    onOpenConfigureBook: () -> Unit = {},
    onNavigateBack: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onCreatePage: (title: String, parentId: String?) -> Unit,
    onDeletePage: (pageId: String) -> Unit,
    onSaveNotes: suspend (pageId: String, content: String) -> Boolean,
    onAttachCanvas: (title: String, kind: String, payload: String) -> Unit,
    onDeleteCanvas: (canvasId: String) -> Unit,
    onUpdateCanvasPayload: (canvasId: String, payload: String) -> Unit,
    onAssignCanvasToPage: (canvasId: String) -> Unit = {},
    onCreateQuestion: (title: String) -> Unit,
    onDeleteQuestion: (questionId: String) -> Unit,
    onOpenQuestionEditor: (QuestionEntity) -> Unit,
    onSyncWithRemote: suspend () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isReadOnly = LocalReadOnlyMode.current
    var splitRatio by remember { mutableFloatStateOf(0.42f) }
    var viewportMode by remember { mutableStateOf(ViewportMode.SPLIT) }
    var isDraggingSplitter by remember { mutableStateOf(false) }

    val leftChaptersDrawerState = rememberDrawerState(DrawerValue.Closed)

    var isCommentsSheetOpen by remember { mutableStateOf(false) }
    var isQuestionsSheetOpen by remember { mutableStateOf(false) }
    var isAttachmentsSheetOpen by remember { mutableStateOf(false) }
    var showHtmlCodeEditor by remember { mutableStateOf(false) }
    var showDeleteCanvasConfirm by remember { mutableStateOf(false) }

    val sortedPages = remember(pages) {
        pages.sortedWith(compareBy({ it.position }, { it.id }))
    }
    val activePage = sortedPages.getOrNull(activePageIndex)

    // Unsaved-draft protection: every exit vector (system back, top bar back,
    // chapter switch, tabs, journal, configure book) goes through this guard.
    val unsavedGuard = remember { UnsavedChangesGuard() }
    var currentNotes by remember(activePage?.id) { mutableStateOf(activePage?.notes ?: "") }

    fun guardNavigation(action: () -> Unit) {
        if (isReadOnly) action() else unsavedGuard.requestNavigation(action)
    }

    val attachedCanvas = remember(canvases, activePageIndex) {
        // Visuals are strictly per chapter matching the 1-based index (e.g. 01_orbit.html)
        val chapterNum1Based = activePageIndex + 1
        canvases.firstOrNull { it.assignedChapter == chapterNum1Based }
    }

    val hasAttachment = attachedCanvas != null

    // The editor renders immediately - the old 300 ms artificial delay made
    // every chapter flash a skeleton before the text appeared.
    var isAttachmentReady by remember { mutableStateOf(false) }

    LaunchedEffect(activePageIndex, hasAttachment) {
        isAttachmentReady = false
        if (hasAttachment) {
            delay(100)
            isAttachmentReady = true
        } else {
            isAttachmentReady = true
        }
    }

    var simWebView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        if (unsavedGuard.isDialogVisible) {
            unsavedGuard.cancel()
        } else if (leftChaptersDrawerState.isOpen) {
            coroutineScope.launch { leftChaptersDrawerState.close() }
        } else {
            guardNavigation { onNavigateBack() }
        }
    }

    // While the keyboard is open the bottom bar would sit right above it,
    // eating editor space (and its nav-bar padding shows as a dead strip).
    // Hide the whole bar so the document gets the room instead.
    val isImeVisible = WindowInsets.isImeVisible

    ModalNavigationDrawer(
        drawerState = leftChaptersDrawerState,
        gesturesEnabled = true,
        drawerContent = {
            ChapterDrawerContent(
                pages = sortedPages,
                canvases = canvases,
                activePageIndex = activePageIndex,
                onSelectPage = { idx ->
                    guardNavigation {
                        coroutineScope.launch { leftChaptersDrawerState.close() }
                        onSelectPage(idx)
                    }
                },
                onCreatePage = onCreatePage,
                onDeletePage = onDeletePage
            )
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    if (isReadOnly) {
                        OfflineNoticeBanner()
                    }
                    TopAppBar(
                    title = {
                        Column {
                            Text(notebookTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, color = TextPrimary)
                            activePage?.let { page ->
                                Text(
                                    text = "${activePageIndex + 1}. ${page.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IndigoGlow,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { guardNavigation { onNavigateBack() } }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                            }
                            IconButton(onClick = { coroutineScope.launch { leftChaptersDrawerState.open() } }) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Chapters Menu", tint = IndigoGlow)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { isAttachmentsSheetOpen = true }) {
                            Icon(Icons.Default.Attachment, contentDescription = "Attachments", tint = EmeraldSync)
                        }
                        IconButton(onClick = { isQuestionsSheetOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Questions", tint = AmberGlow)
                        }
                        IconButton(onClick = { isCommentsSheetOpen = true }) {
                            Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Discussion", tint = IndigoGlow)
                        }
                        if (!isReadOnly) {
                            IconButton(onClick = { guardNavigation { onOpenConfigureBook() } }) {
                                Icon(Icons.Default.Tune, contentDescription = "Configure Book", tint = IndigoGlow)
                            }
                        }
                        if (hasAttachment) {
                            IconButton(onClick = {
                                viewportMode = when (viewportMode) {
                                    ViewportMode.SPLIT -> ViewportMode.ATTACHMENT_FULL
                                    ViewportMode.ATTACHMENT_FULL -> ViewportMode.EDITOR_FULL
                                    ViewportMode.EDITOR_FULL -> ViewportMode.SPLIT
                                }
                            }) {
                                val icon = when (viewportMode) {
                                    ViewportMode.SPLIT -> Icons.Default.ViewAgenda
                                    ViewportMode.ATTACHMENT_FULL -> Icons.Default.Fullscreen
                                    ViewportMode.EDITOR_FULL -> Icons.Default.EditNote
                                }
                                Icon(icon, contentDescription = "Viewport Mode", tint = TextPrimary)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CosmicSlate)
                    )
                }
            },
            bottomBar = {
                if (!isImeVisible) {
                    Surface(
                        color = CosmicSlate,
                        border = BorderStroke(1.dp, BorderSlate),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            NavigationBar(
                                containerColor = CosmicSlate,
                                tonalElevation = 0.dp,
                                modifier = Modifier.height(58.dp)
                            ) {
                                // LEFT — Chapters sidebar
                                NavigationBarItem(
                                    selected = false,
                                    onClick = { coroutineScope.launch { leftChaptersDrawerState.open() } },
                                    icon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.MenuBook,
                                            contentDescription = "Chapters",
                                            tint = IndigoAccent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            "Chapters",
                                            color = IndigoAccent,
                                            fontSize = 10.sp
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = IndigoAccent.copy(alpha = 0.15f)
                                    )
                                )
                                // RIGHT — Tabs
                                NavigationBarItem(
                                    selected = false,
                                    onClick = { guardNavigation { onOpenTabsScreen() } },
                                    icon = {
                                        Icon(
                                            Icons.Default.Layers,
                                            contentDescription = "Tabs",
                                            tint = ElectricViolet,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            "Tabs",
                                            color = ElectricViolet,
                                            fontSize = 10.sp
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = ElectricViolet.copy(alpha = 0.15f)
                                    )
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(SpaceBlack)
            ) {
                val totalHeight = maxHeight
                val density = LocalDensity.current

                if (!hasAttachment) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SpaceBlack)
                    ) {
                        if (activePage == null) {
                            // The notebook has no chapters yet. Show an empty
                            // state instead of a skeleton that would never end.
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        "This notebook has no chapters yet",
                                        color = TextSecondary,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Add one from the chapters menu",
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        } else {
                            activePage?.let { page ->
                                key(page.id) {
                                ComposeLexicalEditor(
                                    initialMarkdown = page.notes,
                                    onContentChanged = { currentNotes = it },
                                    onHasUnsavedChangesChanged = { unsavedGuard.setDirty(it) },
                                    onSave = { updatedNotes ->
                                        onSaveNotes(page.id, updatedNotes)
                                    },
                                    onTimestampClicked = { seconds ->
                                        Toast.makeText(context, "Timestamp: ${seconds}s", Toast.LENGTH_SHORT).show()
                                    },
                                    readOnly = isReadOnly
                                )
                                }
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (viewportMode != ViewportMode.EDITOR_FULL) {
                            val simHeight = if (viewportMode == ViewportMode.ATTACHMENT_FULL) totalHeight else totalHeight * splitRatio
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(simHeight)
                                    .background(Color.Black)
                            ) {
                                val canvas = attachedCanvas
                                if (isDraggingSplitter || !isAttachmentReady || canvas == null) {
                                    SimulationSkeleton(label = "Rendering ${canvas?.title ?: "attachment"}...")
                                } else if (canvas.kind == "youtube") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(SpaceBlack),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Video attachments are disabled in the offline edition.\nUse an HTML simulation or whiteboard instead.",
                                            color = TextMuted,
                                            fontSize = 12.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                } else if (canvas.kind == "draw" || canvas.kind == "excalidraw" || canvas.kind == "canvas" || canvas.kind == "whiteboard") {
                                    DrawingCanvasView(
                                        rawJson = canvas.payloadJson,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    AndroidView(
                                        factory = { ctx ->
                                            WebView(ctx).apply {
                                                layoutParams = ViewGroup.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                @SuppressLint("SetJavaScriptEnabled")
                                                settings.apply {
                                                    javaScriptEnabled = true
                                                    domStorageEnabled = true
                                                    allowFileAccess = true
                                                    allowContentAccess = true
                                                    databaseEnabled = true
                                                    mediaPlaybackRequiresUserGesture = false
                                                    loadWithOverviewMode = true
                                                    useWideViewPort = true
                                                }
                                                webChromeClient = WebChromeClient()
                                                webViewClient = WebViewClient()
                                                loadDataWithBaseURL("https://localhost/", canvas.payloadJson, "text/html", "UTF-8", null)
                                                simWebView = this
                                            }
                                        },
                                        update = { wv ->
                                            wv.loadDataWithBaseURL("https://localhost/", canvas.payloadJson, "text/html", "UTF-8", null)
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (attachedCanvas?.kind == "html" && !isReadOnly) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = 0.75f),
                                            border = BorderStroke(1.dp, BorderSlate),
                                            shadowElevation = 6.dp
                                        ) {
                                            IconButton(
                                                onClick = { showHtmlCodeEditor = true },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "Edit HTML", tint = IndigoGlow, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }

                                    if (!isReadOnly) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = 0.75f),
                                            border = BorderStroke(1.dp, BorderSlate),
                                            shadowElevation = 6.dp
                                        ) {
                                            IconButton(
                                                onClick = { showDeleteCanvasConfirm = true },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete Attachment", tint = RoseRuby, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (viewportMode == ViewportMode.SPLIT) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .background(if (isDraggingSplitter) IndigoAccent.copy(alpha = 0.35f) else CosmicSlate)
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { isDraggingSplitter = true },
                                            onDragEnd = { isDraggingSplitter = false },
                                            onDragCancel = { isDraggingSplitter = false },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val delta = dragAmount.y / with(density) { totalHeight.toPx() }
                                                splitRatio = (splitRatio + delta).coerceIn(0.15f, 0.85f)
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(4.dp)
                                        .background(
                                            if (isDraggingSplitter) IndigoAccent else BorderSlate,
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }

                        if (viewportMode != ViewportMode.ATTACHMENT_FULL) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(SpaceBlack)
                            ) {
                                if (activePage == null) {
                                    // No chapters yet - an empty state, not an
                                    // endless skeleton.
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.MenuBook,
                                                contentDescription = null,
                                                tint = TextMuted,
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text(
                                                "This notebook has no chapters yet",
                                                color = TextSecondary,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                } else {
                                    activePage?.let { page ->
                                        key(page.id) {
                                        ComposeLexicalEditor(
                                            initialMarkdown = page.notes,
                                            onContentChanged = { currentNotes = it },
                                            onHasUnsavedChangesChanged = { unsavedGuard.setDirty(it) },
                                            onSave = { updatedNotes ->
                                                onSaveNotes(page.id, updatedNotes)
                                            },
                                            onTimestampClicked = { seconds ->
                                                Toast.makeText(context, "Seeking to ${seconds}s", Toast.LENGTH_SHORT).show()
                                                simWebView?.evaluateJavascript(
                                                    "if (window.seekTo) { window.seekTo($seconds); }",
                                                    null
                                                )
                                            },
                                            readOnly = isReadOnly
                                        )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isAttachmentsSheetOpen) {
                    val selectedIdx = canvases.indexOf(attachedCanvas).coerceAtLeast(0)
                    AttachmentsBottomSheet(
                        canvases = canvases,
                        selectedIndex = if (canvases.isEmpty()) 0 else selectedIdx,
                        onSelectCanvas = { idx ->
                            val c = canvases.getOrNull(idx)
                            if (c != null) {
                                onAssignCanvasToPage(c.id)
                                isAttachmentsSheetOpen = false
                            }
                        },
                        onAddAttachment = { title, kind, payload ->
                            onAttachCanvas(title, kind, payload)
                        },
                        onDeleteAttachment = onDeleteCanvas,
                        onDismiss = { isAttachmentsSheetOpen = false }
                    )
                }

                if (isQuestionsSheetOpen) {
                    QuestionsBottomSheet(
                        questions = questions,
                        onDismiss = { isQuestionsSheetOpen = false },
                        onCreateQuestion = onCreateQuestion,
                        onDeleteQuestion = onDeleteQuestion,
                        onOpenQuestionEditor = { q ->
                            isQuestionsSheetOpen = false
                            onOpenQuestionEditor(q)
                        }
                    )
                }

                if (isCommentsSheetOpen) {
                    CommentsBottomSheet(
                        chapterTitle = activePage?.title ?: "Chapter",
                        onDismiss = { isCommentsSheetOpen = false }
                    )
                }

                if (showHtmlCodeEditor && attachedCanvas != null) {
                    HtmlCodeEditorScreen(
                        title = attachedCanvas?.title ?: "HTML",
                        initialCode = attachedCanvas?.payloadJson?.ifBlank {
                            "<!DOCTYPE html>\n<html>\n<head>\n  <style>body { background: #020617; color: #818cf8; }</style>\n</head>\n<body>\n  <h1>Simulation</h1>\n</body>\n</html>"
                        } ?: "",
                        onSaveAndRun = { newCode ->
                            attachedCanvas?.let { onUpdateCanvasPayload(it.id, newCode) }
                            simWebView?.loadDataWithBaseURL("https://localhost/", newCode, "text/html", "UTF-8", null)
                            showHtmlCodeEditor = false
                        },
                        onDismiss = { showHtmlCodeEditor = false }
                    )
                }

                if (showDeleteCanvasConfirm && attachedCanvas != null) {
                    ConfirmDeleteDialog(
                        title = "Delete Companion Visual",
                        message = "Are you sure you want to delete \u201C${attachedCanvas.title}\u201D?",
                        onConfirm = {
                            onDeleteCanvas(attachedCanvas.id)
                            showDeleteCanvasConfirm = false
                        },
                        onDismiss = { showDeleteCanvasConfirm = false }
                    )
                }
            }
        }
    }

    if (unsavedGuard.isDialogVisible) {
        UnsavedChangesDialog(
            title = "Unsaved Changes",
            message = "You have unsaved changes in \"${activePage?.title ?: "this chapter"}\". Do you want to save them before leaving?",
            onSaveAndContinue = {
                val pageId = activePage?.id
                if (pageId == null) {
                    unsavedGuard.completeAfterSave()
                } else {
                    coroutineScope.launch {
                        val saved = onSaveNotes(pageId, currentNotes)
                        if (saved) {
                            unsavedGuard.completeAfterSave()
                        } else {
                            Toast.makeText(
                                context,
                                "Save failed, remaining in chapter",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            onDiscardAndContinue = { unsavedGuard.discardAndContinue() },
            onCancel = { unsavedGuard.cancel() }
        )
    }
}
