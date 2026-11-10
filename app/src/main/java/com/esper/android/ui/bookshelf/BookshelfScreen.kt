
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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esper.android.data.local.NotebookWithDetails
import com.esper.android.data.model.NotebookEntity
import com.esper.android.data.model.ShelfEntity
import com.esper.android.ui.components.BookCard
import com.esper.android.ui.components.ConfirmDeleteDialog
import com.esper.android.ui.components.CustomBottomNavBar
import com.esper.android.ui.components.LocalReadOnlyMode
import com.esper.android.ui.components.OfflineNoticeBanner
import com.esper.android.ui.components.PullToRefreshLayout
import com.esper.android.ui.theme.*
import kotlinx.coroutines.delay
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
    shelves: List<ShelfEntity>,
    allDetails: List<NotebookWithDetails>,
    selectedTagFilter: String,
    onSelectTagFilter: (String) -> Unit,
    onOpenNotebook: (String) -> Unit,
    onConfigureNotebook: (NotebookEntity) -> Unit = {},
    onDeleteNotebook: (String) -> Unit,
    onToggleBookmark: (String) -> Unit = {},
    onOpenAllTagsScreen: () -> Unit,
    onOpenTabsScreen: () -> Unit,
    onOpenSettingsScreen: () -> Unit,
    onOpenCreateNotebookScreen: () -> Unit,
    onOpenJournalScreen: () -> Unit,
    onDeleteShelf: (String) -> Unit,
    onCreateShelf: (title: String, description: String, color: String, initialBookId: String?) -> Unit = { _, _, _, _ -> },
    onToggleBookShelf: (bookId: String, shelfId: String) -> Unit = { _, _ -> },
    onSyncWithRemote: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val isReadOnly = LocalReadOnlyMode.current

    var searchQuery by remember { mutableStateOf("") }
    var selectedShelfId by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var bookToDelete by remember { mutableStateOf<NotebookEntity?>(null) }
    var shelfToDelete by remember { mutableStateOf<ShelfEntity?>(null) }
    var bookToSetShelf by remember { mutableStateOf<NotebookEntity?>(null) }
    var showCreateShelfDialog by remember { mutableStateOf(false) }
    var createShelfInitialBookId by remember { mutableStateOf<String?>(null) }
    var currentSort by remember { mutableStateOf(BookSortOption.RECENTLY_CREATED) }
    var sortDropdownExpanded by remember { mutableStateOf(false) }

    val sortedNotebooks = remember(notebooks, currentSort) {
        val nonJournal = notebooks.filter {
            !it.tagsCsv.split(",").map { tag -> tag.trim() }.contains("Journal") &&
                !it.title.contains("Journal", ignoreCase = true)
        }
        when (currentSort) {
            BookSortOption.RECENTLY_CREATED -> nonJournal.sortedByDescending { it.createdAt }
            BookSortOption.OLDEST_CREATED -> nonJournal.sortedBy { it.createdAt }
            BookSortOption.RECENTLY_UPDATED -> nonJournal.sortedByDescending {
                if (it.notesUpdatedAt > 0L) it.notesUpdatedAt else it.updatedAt
            }
            BookSortOption.OLDEST_UPDATED -> nonJournal.sortedBy {
                if (it.notesUpdatedAt > 0L) it.notesUpdatedAt else it.updatedAt
            }
            BookSortOption.TITLE_AZ -> nonJournal.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            BookSortOption.TITLE_ZA -> nonJournal.sortedWith(
                compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
        }
    }

    val currentShelf = remember(shelves, selectedShelfId) {
        shelves.find { it.id == selectedShelfId }
    }

    val shelfBookIds = remember(currentShelf) {
        BookshelfFiltering.shelfBookIds(currentShelf)
    }

    val shelfFilterActive = selectedShelfId != null && currentShelf != null

    val filtered = remember(
        sortedNotebooks,
        selectedTagFilter,
        searchQuery,
        shelfFilterActive,
        shelfBookIds
    ) {
        BookshelfFiltering.filter(
            books = sortedNotebooks,
            tagFilter = selectedTagFilter,
            searchQuery = searchQuery,
            shelfBookIds = shelfBookIds,
            shelfFilterActive = shelfFilterActive
        )
    }

    fun triggerRefresh() {
        if (!isRefreshing) {
            isRefreshing = true
            onSyncWithRemote()
            coroutineScope.launch {
                delay(900)
                isRefreshing = false
            }
        }
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
                modifier = Modifier.width(310.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .clickable {
                                onOpenSettingsScreen()
                                coroutineScope.launch { drawerState.close() }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(IndigoAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "V",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local Vault",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Settings & Workspace",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    HorizontalDivider(color = BorderSlate, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SurfaceSlate.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, BorderSlate),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = null,
                                tint = EmeraldSync,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Pure Offline Vault",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Folder-backed · zero cloud",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Shelves & Collections (${shelves.size})",
                            color = IndigoGlow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        if (!isReadOnly) {
                            IconButton(
                                onClick = {
                                    createShelfInitialBookId = null
                                    showCreateShelfDialog = true
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Create Bookshelf",
                                    tint = IndigoAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selectedShelfId == null) SurfaceSlate else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedShelfId = null
                                        onSelectTagFilter("All")
                                        coroutineScope.launch { drawerState.close() }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = IndigoGlow, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("All Notebooks", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        if (shelves.isEmpty()) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.Transparent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("No bookshelves yet", color = TextMuted, fontSize = 12.sp)
                                        if (!isReadOnly) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "+ Create a bookshelf",
                                                color = IndigoGlow,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.clickable {
                                                    createShelfInitialBookId = null
                                                    showCreateShelfDialog = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        items(shelves) { s ->
                            val isSelected = s.id == selectedShelfId
                            val shelfColor = try {
                                Color(android.graphics.Color.parseColor(s.color))
                            } catch (_: Exception) {
                                IndigoAccent
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) SurfaceSlate else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedShelfId = s.id
                                        onSelectTagFilter("All")
                                        coroutineScope.launch { drawerState.close() }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(shelfColor)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(s.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            if (s.description.isNotBlank()) {
                                                Text(s.description, color = TextMuted, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                    if (!isReadOnly) {
                                        IconButton(
                                            onClick = { shelfToDelete = s },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = RoseRuby, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = BorderSlate, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = TextSecondary) },
                        label = { Text("Vault Settings", color = TextSecondary) },
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
                                                text = "Search notebooks, tags...",
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
                        selectedShelfId = null
                        onSelectTagFilter("All")
                        searchQuery = ""
                    },
                    onOpenJournal = onOpenJournalScreen,
                    onOpenTabs = onOpenTabsScreen,
                    onOpenCreateBook = onOpenCreateNotebookScreen,
                    onOpenSettings = onOpenSettingsScreen
                )
            }
        ) { innerPadding ->
            PullToRefreshLayout(
                isRefreshing = isRefreshing,
                onRefresh = { triggerRefresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(SpaceBlack)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val quickTags = remember(notebooks) {
                        val extracted = notebooks.filter { !it.id.startsWith("esper-journal") }
                            .flatMap { it.tagsCsv.split(",") }
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.equals("Bookmarks", ignoreCase = true) }
                            .distinct()
                            .take(6)
                        listOf("All", "Bookmarks") + extracted
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            Surface(
                                onClick = onOpenAllTagsScreen,
                                shape = RoundedCornerShape(20.dp),
                                color = SurfaceSlate,
                                border = BorderStroke(1.dp, IndigoAccent.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Tag, contentDescription = null, tint = IndigoGlow, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("All Tags", color = IndigoGlow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        items(quickTags) { tag ->
                            val isSelected = tag == selectedTagFilter && selectedShelfId == null
                            val isBookmarkTag = tag.equals("Bookmarks", ignoreCase = true)
                            Surface(
                                onClick = {
                                    selectedShelfId = null
                                    onSelectTagFilter(tag)
                                },
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
                    }

                    val shelfTitle = when {
                        currentShelf != null -> currentShelf.title
                        selectedTagFilter.equals("Bookmarks", ignoreCase = true) -> "Bookmarks"
                        else -> "All Notebooks"
                    }
                    val subtitleText = when {
                        selectedTagFilter.equals("Bookmarks", ignoreCase = true) -> "${filtered.size} bookmarked"
                        else -> "${filtered.size} books available"
                    }
                    val shelfColor = remember(currentShelf) {
                        try {
                            currentShelf?.let { Color(android.graphics.Color.parseColor(it.color)) } ?: IndigoAccent
                        } catch (_: Exception) { IndigoAccent }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
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
                                            .background(shelfColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = shelfTitle,
                                        color = TextPrimary,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = subtitleText,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 18.dp)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (currentShelf != null && !isReadOnly) {
                                    IconButton(
                                        onClick = { shelfToDelete = currentShelf },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete Bookshelf",
                                            tint = RoseRuby,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }

                                if (selectedShelfId != null || selectedTagFilter != "All" || searchQuery.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            selectedShelfId = null
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
                    }

                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("No notebooks found", color = TextSecondary, fontSize = 14.sp)
                                Text("Pull down to refresh or tap + to create one", color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filtered, key = { it.id }) { book ->
                                BookCard(
                                    notebook = book,
                                    onClick = { onOpenNotebook(book.id) },
                                    onEdit = { onConfigureNotebook(book) },
                                    onDelete = { bookToDelete = book },
                                    onSetShelf = { bookToSetShelf = book },
                                    onToggleBookmark = { onToggleBookmark(book.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        bookToDelete?.let { book ->
            ConfirmDeleteDialog(
                title = "Delete Notebook",
                message = "Are you sure you want to delete \u201C${book.title}\u201D? All pages, companion visuals, and review questions will be permanently deleted.",
                onConfirm = {
                    onDeleteNotebook(book.id)
                    bookToDelete = null
                },
                onDismiss = { bookToDelete = null }
            )
        }

        shelfToDelete?.let { shelf ->
            ConfirmDeleteDialog(
                title = "Delete Bookshelf",
                message = "Are you sure you want to delete bookshelf \u201C${shelf.title}\u201D? Books on this shelf will not be deleted.",
                onConfirm = {
                    if (selectedShelfId == shelf.id) {
                        selectedShelfId = null
                    }
                    onDeleteShelf(shelf.id)
                    shelfToDelete = null
                },
                onDismiss = { shelfToDelete = null }
            )
        }

        bookToSetShelf?.let { book ->
            SetBookToShelfDialog(
                book = book,
                shelves = shelves,
                onToggleShelf = { shelfId -> onToggleBookShelf(book.id, shelfId) },
                onCreateShelfClick = {
                    createShelfInitialBookId = book.id
                    showCreateShelfDialog = true
                },
                onDismiss = { bookToSetShelf = null }
            )
        }

        if (showCreateShelfDialog) {
            CreateShelfDialog(
                onConfirm = { title, desc, color ->
                    onCreateShelf(title, desc, color, createShelfInitialBookId)
                    showCreateShelfDialog = false
                    createShelfInitialBookId = null
                },
                onDismiss = {
                    showCreateShelfDialog = false
                    createShelfInitialBookId = null
                }
            )
        }
    }
}
