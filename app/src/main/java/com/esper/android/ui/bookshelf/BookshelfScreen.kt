package com.esper.android.ui.bookshelf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esper.android.data.local.NotebookWithDetails
import com.esper.android.data.model.NotebookEntity
import com.esper.android.data.model.WorkspaceEntity
import com.esper.android.data.vault.WorkspacePathHelper
import com.esper.android.ui.components.BookCard
import com.esper.android.ui.components.ConfirmDeleteDialog
import com.esper.android.ui.components.CustomBottomNavBar
import com.esper.android.ui.components.LocalReadOnlyMode
import com.esper.android.ui.theme.*
import kotlinx.coroutines.launch

enum class BookSortOption(val label: String) {
    RECENTLY_CREATED("Recently Created"),
    OLDEST_CREATED("Oldest Created"),
    RECENTLY_UPDATED("Recently Updated"),
    OLDEST_UPDATED("Oldest Updated"),
    TITLE_AZ("Title (A-Z)"),
    TITLE_ZA("Title (Z-A)")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    notebooks: List<NotebookEntity>,
    workspaces: List<WorkspaceEntity>,
    activeWorkspace: WorkspaceEntity?,
    allDetails: List<NotebookWithDetails>,
    selectedTagFilter: String,
    bookmarkIds: Set<String>,
    onSelectTagFilter: (String) -> Unit,
    onOpenNotebook: (String) -> Unit,
    onConfigureNotebook: (NotebookEntity) -> Unit = {},
    onDeleteNotebook: (String) -> Unit,
    onToggleBookmark: (String) -> Unit = {},
    onClearAllBookmarks: () -> Unit = {},
    onSelectWorkspace: (String) -> Unit,
    onCloseWorkspace: (String) -> Unit,
    onOpenFolderWorkspace: () -> Unit,
    onOpenAllTagsScreen: () -> Unit,
    onOpenTabsScreen: () -> Unit,
    onOpenSettingsScreen: () -> Unit,
    onOpenCreateNotebookScreen: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val isReadOnly = LocalReadOnlyMode.current

    var searchQuery by remember { mutableStateOf("") }
    var bookToDelete by remember { mutableStateOf<NotebookEntity?>(null) }
    var workspaceToClose by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var showClearBookmarksConfirm by remember { mutableStateOf(false) }
    var currentSort by remember { mutableStateOf(BookSortOption.RECENTLY_CREATED) }
    var sortDropdownExpanded by remember { mutableStateOf(false) }

    // Pagination: 10 notebooks per page (latest created to oldest)
    var currentPage by remember { mutableIntStateOf(1) }
    val pageSize = 10

    val sortedNotebooks = remember(notebooks, currentSort) {
        when (currentSort) {
            BookSortOption.RECENTLY_CREATED -> notebooks.sortedByDescending { it.createdAt }
            BookSortOption.OLDEST_CREATED -> notebooks.sortedBy { it.createdAt }
            BookSortOption.RECENTLY_UPDATED -> notebooks.sortedByDescending {
                if (it.notesUpdatedAt > 0L) it.notesUpdatedAt else it.updatedAt
            }
            BookSortOption.OLDEST_UPDATED -> notebooks.sortedBy {
                if (it.notesUpdatedAt > 0L) it.notesUpdatedAt else it.updatedAt
            }
            BookSortOption.TITLE_AZ -> notebooks.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            BookSortOption.TITLE_ZA -> notebooks.sortedWith(
                compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
        }
    }

    val filtered = remember(
        sortedNotebooks,
        selectedTagFilter,
        searchQuery,
        bookmarkIds
    ) {
        BookshelfFiltering.filter(
            books = sortedNotebooks,
            tagFilter = selectedTagFilter,
            searchQuery = searchQuery,
            bookmarkIds = bookmarkIds
        )
    }

    val totalPages = maxOf(1, (filtered.size + pageSize - 1) / pageSize)
    val paginatedBooks = remember(filtered, currentPage) {
        val safePage = currentPage.coerceIn(1, totalPages)
        val start = (safePage - 1) * pageSize
        filtered.drop(start).take(pageSize)
    }

    LaunchedEffect(selectedTagFilter, searchQuery, filtered.size) {
        currentPage = 1
    }

    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = CosmicSlate,
                drawerContentColor = TextPrimary,
                modifier = Modifier.width(320.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Workspaces",
                            color = IndigoGlow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        IconButton(
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onOpenFolderWorkspace()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.CreateNewFolder,
                                contentDescription = "Open Folder as Workspace",
                                tint = EmeraldSync
                            )
                        }
                    }

                    Text(
                        text = "Workspaces are ordinary folders containing Markdown chapters.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    HorizontalDivider(color = BorderSlate, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (workspaces.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FolderOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No workspaces yet", color = TextSecondary, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Open a folder to start", color = TextMuted, fontSize = 11.sp)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(workspaces) { ws ->
                                val isSelected = ws.id == activeWorkspace?.id
                                val displayPath = remember(ws.treeUriString) {
                                    WorkspacePathHelper.getDisplayPath(ws.treeUriString)
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) SurfaceSlate else Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelectWorkspace(ws.id)
                                            coroutineScope.launch { drawerState.close() }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            tint = if (isSelected) EmeraldSync else IndigoGlow,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                ws.name,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            if (displayPath.isNotEmpty()) {
                                                Text(
                                                    displayPath,
                                                    color = TextMuted,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { workspaceToClose = ws },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Close Workspace",
                                                tint = TextMuted,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = "Active", tint = EmeraldSync, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = BorderSlate, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = EmeraldSync) },
                        label = { Text("Open Folder as Workspace", color = TextPrimary) },
                        selected = false,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onOpenFolderWorkspace()
                        },
                        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = TextSecondary) },
                        label = { Text("Settings & Archive", color = TextSecondary) },
                        selected = false,
                        onClick = {
                            onOpenSettingsScreen()
                            coroutineScope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = SpaceBlack,
                                border = BorderStroke(1.dp, BorderSlate),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = TextMuted,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = "Search books, tags...",
                                                color = TextMuted,
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp
                                            )
                                        }
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp
                                            ),
                                            cursorBrush = SolidColor(IndigoAccent),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TextPrimary)
                            }
                        },
                        actions = {
                            IconButton(onClick = onOpenSettingsScreen) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = CosmicSlate)
                    )
                }
            },
            bottomBar = {
                CustomBottomNavBar(
                    onOpenLibrary = {
                        onSelectTagFilter("All")
                        searchQuery = ""
                    },
                    onOpenTabs = onOpenTabsScreen,
                    onOpenCreateBook = onOpenCreateNotebookScreen,
                    onOpenSettings = onOpenSettingsScreen
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(SpaceBlack)
            ) {
                // Check if a workspace is selected
                if (activeWorkspace == null) {
                    // Empty state: No workspace selected
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = CosmicSlate),
                            border = BorderStroke(1.dp, BorderSlate),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(IndigoAccent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        tint = IndigoAccent,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Text(
                                    text = "No Workspace Selected",
                                    color = TextPrimary,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = "Kepler is an offline, folder-backed notebook workspace. Open any folder on your device to read, write, and manage your books.",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Button(
                                    onClick = onOpenFolderWorkspace,
                                    colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open Workspace Folder", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                } else {
                    // Quick tags row with overflow "..." button
                    val extractedTags = remember(notebooks) {
                        notebooks.flatMap { it.tagsCsv.split(",") }
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.equals("Bookmarks", ignoreCase = true) }
                            .distinct()
                    }

                    // First 5 tags, plus "..." button if there are more
                    val maxVisibleQuickTags = 5
                    val visibleTags = remember(extractedTags) {
                        listOf("All", "Bookmarks") + extractedTags.take(maxVisibleQuickTags)
                    }
                    val hasMoreTags = extractedTags.size > maxVisibleQuickTags

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(visibleTags) { tag ->
                            val isSelected = tag == selectedTagFilter
                            val isBookmarkTag = tag.equals("Bookmarks", ignoreCase = true)
                            Surface(
                                onClick = { onSelectTagFilter(tag) },
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) (if (isBookmarkTag) AmberGlow else IndigoAccent) else CosmicSlate
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isBookmarkTag) {
                                        Icon(
                                            Icons.Default.Bookmark,
                                            contentDescription = null,
                                            tint = if (isSelected) Color.Black else AmberGlow,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = tag,
                                        color = if (isSelected) (if (isBookmarkTag) Color.Black else TextPrimary) else (if (isBookmarkTag) AmberGlow else TextSecondary),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected || isBookmarkTag) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        if (hasMoreTags) {
                            item {
                                Surface(
                                    onClick = onOpenAllTagsScreen,
                                    shape = RoundedCornerShape(20.dp),
                                    color = SurfaceSlate,
                                    border = BorderStroke(1.dp, IndigoAccent.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "...",
                                        color = IndigoGlow,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Workspace Header, Path Chip & Sort
                    val headerTitle = when {
                        selectedTagFilter.equals("Bookmarks", ignoreCase = true) -> "Bookmarks"
                        selectedTagFilter != "All" -> selectedTagFilter
                        else -> activeWorkspace.name
                    }

                    val folderPath = remember(activeWorkspace.treeUriString) {
                        WorkspacePathHelper.getDisplayPath(activeWorkspace.treeUriString)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(IndigoAccent)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = headerTitle,
                                        color = TextPrimary,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = "${filtered.size} books · Page $currentPage of $totalPages",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 18.dp)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectedTagFilter.equals("Bookmarks", ignoreCase = true) && bookmarkIds.isNotEmpty()) {
                                    TextButton(
                                        onClick = { showClearBookmarksConfirm = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Clear All Bookmarks", color = RoseRuby, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }

                                if (selectedTagFilter != "All" || searchQuery.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            onSelectTagFilter("All")
                                            searchQuery = ""
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Clear filter", color = IndigoGlow, fontSize = 11.sp)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                }

                                Box {
                                    Surface(
                                        onClick = { sortDropdownExpanded = true },
                                        shape = RoundedCornerShape(10.dp),
                                        color = SurfaceSlate,
                                        border = BorderStroke(1.dp, BorderSlate)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", tint = IndigoGlow, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(currentSort.label, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = sortDropdownExpanded,
                                        onDismissRequest = { sortDropdownExpanded = false },
                                        modifier = Modifier.background(CosmicSlate)
                                    ) {
                                        BookSortOption.values().forEach { option ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        if (currentSort == option) {
                                                            Icon(Icons.Default.Check, contentDescription = null, tint = IndigoGlow, modifier = Modifier.size(14.dp))
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                        } else {
                                                            Spacer(modifier = Modifier.width(22.dp))
                                                        }
                                                        Text(
                                                            text = option.label,
                                                            color = if (currentSort == option) IndigoGlow else TextPrimary,
                                                            fontWeight = if (currentSort == option) FontWeight.Bold else FontWeight.Normal,
                                                            fontSize = 13.sp
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    currentSort = option
                                                    sortDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Folder path bar with Copy & Open in File Explorer buttons
                        if (folderPath.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = CosmicSlate,
                                border = BorderStroke(1.dp, BorderSlate),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = IndigoGlow,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = folderPath,
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            WorkspacePathHelper.copyPathToClipboard(context, folderPath)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy Path",
                                            tint = TextMuted,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            WorkspacePathHelper.openFolderInFileManager(context, activeWorkspace.treeUriString)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Launch,
                                            contentDescription = "Open in File Manager",
                                            tint = EmeraldSync,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (paginatedBooks.isEmpty()) {
                        // Empty state: No notebooks in this workspace
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(SurfaceSlate),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = if (selectedTagFilter != "All" || searchQuery.isNotEmpty()) "No matching notebooks" else "No notebooks in this workspace",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (selectedTagFilter != "All" || searchQuery.isNotEmpty()) {
                                        "Try clearing your tag filter or search query."
                                    } else {
                                        "Tap + New below to create a notebook, or add Markdown folders directly to this directory."
                                    },
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    textAlign = TextAlign.Center
                                )
                                if (selectedTagFilter == "All" && searchQuery.isEmpty() && !isReadOnly) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = onOpenCreateNotebookScreen,
                                        colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Create Notebook", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(paginatedBooks, key = { it.id }) { book ->
                                BookCard(
                                    notebook = book,
                                    onClick = { onOpenNotebook(book.id) },
                                    onEdit = { onConfigureNotebook(book) },
                                    onDelete = { bookToDelete = book },
                                    onToggleBookmark = { onToggleBookmark(book.id) },
                                    isBookmarked = bookmarkIds.contains(book.id)
                                )
                            }
                        }

                        // Pagination footer bar
                        if (totalPages > 1) {
                            Surface(
                                color = CosmicSlate,
                                border = BorderStroke(1.dp, BorderSlate),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { if (currentPage > 1) currentPage-- },
                                        enabled = currentPage > 1
                                    ) {
                                        Text("← Prev", color = if (currentPage > 1) IndigoGlow else TextMuted, fontSize = 12.sp)
                                    }

                                    Text(
                                        text = "Page $currentPage / $totalPages",
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    TextButton(
                                        onClick = { if (currentPage < totalPages) currentPage++ },
                                        enabled = currentPage < totalPages
                                    ) {
                                        Text("Next →", color = if (currentPage < totalPages) IndigoGlow else TextMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        bookToDelete?.let { book ->
            ConfirmDeleteDialog(
                title = "Delete Notebook",
                message = "Are you sure you want to delete \u201C${book.title}\u201D? All chapters, visuals, and review questions will be permanently removed.",
                onConfirm = {
                    onDeleteNotebook(book.id)
                    bookToDelete = null
                },
                onDismiss = { bookToDelete = null }
            )
        }

        if (showClearBookmarksConfirm) {
            ConfirmDeleteDialog(
                title = "Clear All Bookmarks",
                message = "Remove all local bookmarks? The books will remain in the workspace.",
                confirmText = "Clear All",
                onConfirm = {
                    onClearAllBookmarks()
                    showClearBookmarksConfirm = false
                },
                onDismiss = { showClearBookmarksConfirm = false }
            )
        }

        workspaceToClose?.let { ws ->
            ConfirmDeleteDialog(
                title = "Close Workspace",
                message = "Remove \u201C${ws.name}\u201D from your workspaces list? The folder and its books will remain on disk.",
                confirmText = "Remove",
                onConfirm = {
                    onCloseWorkspace(ws.id)
                    workspaceToClose = null
                },
                onDismiss = { workspaceToClose = null }
            )
        }
    }
}
