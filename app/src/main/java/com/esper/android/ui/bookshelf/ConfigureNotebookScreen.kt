package com.esper.android.ui.bookshelf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.esper.android.data.model.NotebookEntity
import com.esper.android.data.model.ShelfEntity
import com.esper.android.ui.theme.*

const val LLM_COVER_PROMPT_TEMPLATE = """You are an award-winning book cover designer and master SVG vector artist.
Design a high-contrast book cover as a standalone valid SVG file for the reading workspace "Esper".

### BOOK DETAILS:
- Title: "[REPLACE: Book Title]"
- Subtitle: "[REPLACE: Subtitle]"
- Category: "[REPLACE: Category]"
- Volume: "VOL. 01"
- Author: "[REPLACE: Author Name]"

### TECHNICAL SPECS:
1. Dimensions: viewBox="0 0 1000 1440" (1:1.44 ratio, standard modern paperback).
2. Palette: Deep dark background (#020617 or #090d16) with luminous vector accents (#818cf8, #34d399, #fbbf24, #60a5fa, #fb7185).
3. Layers: Background gradient, subtle coordinate/grid texture, central topic-specific abstract vector artwork, 3D left-spine shadow (x=0 to 36), and clear typography.
4. Output: ONLY pure valid <svg>...</svg> code without markdown backticks.

Begin."""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureNotebookScreen(
    notebook: NotebookEntity,
    shelves: List<ShelfEntity> = emptyList(),
    onToggleBookShelf: (bookId: String, shelfId: String) -> Unit = { _, _ -> },
    onCreateShelf: (title: String, description: String, color: String, initialBookId: String?) -> Unit = { _, _, _, _ -> },
    onSaveConfig: (
        title: String,
        description: String,
        tags: String,
        coverPreset: String,
        coverImage: String?,
        visibility: String,
        password: String?,
        isPinned: Boolean
    ) -> Unit,
    onNavigateBack: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current

    var title by remember { mutableStateOf(notebook.title) }
    var description by remember { mutableStateOf(notebook.description) }
    var tags by remember { mutableStateOf(notebook.tagsCsv) }
    var coverPreset by remember { mutableStateOf(notebook.coverPreset) }
    var coverImageInput by remember { mutableStateOf(notebook.coverImage ?: "") }
    var isBookmarked by remember { mutableStateOf(notebook.isPinned) }
    var isCopiedPrompt by remember { mutableStateOf(false) }

    var showSetShelfDialog by remember { mutableStateOf(false) }
    var showCreateShelfDialog by remember { mutableStateOf(false) }

    fun sanitizeCover(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("<svg", ignoreCase = true) && trimmed.endsWith("</svg>", ignoreCase = true)) {
            return "data:image/svg+xml;utf8," + Uri.encode(trimmed)
        }
        return trimmed
    }

    val sanitizedCover = remember(coverImageInput) { sanitizeCover(coverImageInput) }

    val coverTheme = CoverThemeEngine.getTheme(coverPreset, sanitizedCover)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Configure Book", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                        Text(notebook.title, color = IndigoGlow, fontSize = 11.sp, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (title.isNotBlank()) {
                                onSaveConfig(
                                    title.trim(),
                                    description.trim(),
                                    tags.trim(),
                                    coverPreset,
                                    sanitizedCover,
                                    "public",
                                    null,
                                    isBookmarked
                                )
                                onNavigateBack()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save Changes", tint = EmeraldSync)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CosmicSlate)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(SpaceBlack)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live 3D Cover Preview
            val previewModel = remember(sanitizedCover) {
                CoverImageHelper.resolveCoverModel(sanitizedCover)
            }
            var isPreviewLoaded by remember(previewModel) { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, coverTheme.accentColor.copy(alpha = 0.5f)),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .width(160.dp)
                        .aspectRatio(0.69f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(coverTheme.gradientColors))
                    ) {
                        if (previewModel != null) {
                            AsyncImage(
                                model = previewModel,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                onSuccess = { isPreviewLoaded = true },
                                onError = { isPreviewLoaded = false },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (isPreviewLoaded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.22f))
                                )
                            }
                        }

                        if (previewModel == null || !isPreviewLoaded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.Black.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        text = tags.split(",").firstOrNull()?.trim()?.ifBlank { "GENERAL" } ?: "GENERAL",
                                        color = coverTheme.accentColor,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                Text(
                                    text = title.ifBlank { "Book Title" },
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                Text(
                                    text = "ESPER NOTEBOOK",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Spine matching the cover (subtle translucent)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(6.dp)
                                .background(coverTheme.spineColor.copy(alpha = 0.55f))
                                .align(Alignment.CenterStart)
                        )
                    }
                }
            }

            // Title & Description
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Book Title *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("Categories / Tags (comma-separated)") },
                placeholder = { Text("Physics, Astrodynamics, Drafts") },
                modifier = Modifier.fillMaxWidth()
            )

            // Bookshelf assignment section
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CosmicSlate,
                border = BorderStroke(1.dp, BorderSlate)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = IndigoGlow, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Bookshelves", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        TextButton(
                            onClick = { showSetShelfDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Set Bookshelf", color = IndigoGlow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    val assignedShelves = remember(shelves, notebook.id) {
                        shelves.filter { BookshelfFiltering.shelfBookIds(it.notebookIdsCsv).contains(notebook.id) }
                    }

                    if (assignedShelves.isEmpty()) {
                        Text("Not assigned to any bookshelf yet", color = TextMuted, fontSize = 12.sp)
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            assignedShelves.forEach { s ->
                                val shelfColor = try {
                                    Color(android.graphics.Color.parseColor(s.color))
                                } catch (_: Exception) { IndigoAccent }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SurfaceSlate
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(shelfColor))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(s.title, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Local vault options
            Text("Bookmark", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isBookmarked,
                    onClick = { isBookmarked = !isBookmarked },
                    label = { Text(if (isBookmarked) "Bookmarked" else "Bookmark") },
                    leadingIcon = {
                        Icon(
                            if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            tint = if (isBookmarked) AmberGlow else TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                )
            }

            // Cover Presets Carousel (all 24 built-in themes)
            PresetCoverCarousel(
                selectedPresetId = coverPreset,
                onPresetSelected = { presetId ->
                    coverPreset = presetId
                    coverImageInput = ""
                }
            )

            // Custom Cover URL or Raw SVG with AI Prompt Copy button
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CosmicSlate,
                border = BorderStroke(1.dp, BorderSlate)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Custom Image or SVG Code", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Esper Cover Prompt", LLM_COVER_PROMPT_TEMPLATE)
                                clipboard.setPrimaryClip(clip)
                                isCopiedPrompt = true
                                Toast.makeText(context, "AI Cover Prompt copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                if (isCopiedPrompt) Icons.Default.Check else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (isCopiedPrompt) EmeraldSync else IndigoGlow,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isCopiedPrompt) "Copied!" else "AI Prompt",
                                color = if (isCopiedPrompt) EmeraldSync else IndigoGlow,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedTextField(
                        value = coverImageInput,
                        onValueChange = { coverImageInput = it },
                        label = { Text("Image URL or raw <svg>...</svg>") },
                        placeholder = { Text("https://... or <svg viewBox=\"0 0 1000 1440\"...") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (coverImageInput.isNotBlank()) {
                        TextButton(
                            onClick = { coverImageInput = "" },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Clear Custom Cover", color = RoseRuby, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Save button
             Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onSaveConfig(
                            title.trim(),
                            description.trim(),
                            tags.trim(),
                            coverPreset,
                            sanitizedCover,
                            "public",
                            null,
                            isBookmarked
                        )
                        onNavigateBack()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Save Configuration", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }

    if (showSetShelfDialog) {
        SetBookToShelfDialog(
            book = notebook,
            shelves = shelves,
            onToggleShelf = { shelfId -> onToggleBookShelf(notebook.id, shelfId) },
            onCreateShelfClick = { showCreateShelfDialog = true },
            onDismiss = { showSetShelfDialog = false }
        )
    }

    if (showCreateShelfDialog) {
        CreateShelfDialog(
            onConfirm = { shelfTitle, desc, color ->
                onCreateShelf(shelfTitle, desc, color, notebook.id)
                showCreateShelfDialog = false
            },
            onDismiss = { showCreateShelfDialog = false }
        )
    }
}
