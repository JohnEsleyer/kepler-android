package com.esper.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.esper.android.data.model.NotebookEntity
import com.esper.android.ui.theme.*

@Composable
fun BookCard(
    notebook: NotebookEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onToggleBookmark: () -> Unit = {},
    isBookmarked: Boolean = false
) {
    val isReadOnly = LocalReadOnlyMode.current
    val theme = remember(notebook.coverPreset, notebook.coverImage) {
        CoverThemeEngine.getTheme(notebook.coverPreset, notebook.coverImage)
    }
    val gradient = theme.gradientColors

    val coverModel = remember(notebook.coverImage) {
        CoverImageHelper.resolveCoverModel(notebook.coverImage)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CosmicSlate),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column {
            // Book cover container with standard paperback book ratio (1:1.45)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.69f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(Brush.linearGradient(gradient))
            ) {
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = notebook.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Ambient contrast vignette (matches web `bg-black/25`)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.22f))
                    )
                }

                // App-rendered book spine (subtle translucent, artwork stays full-bleed)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(6.dp)
                        .background(theme.spineColor.copy(alpha = 0.55f))
                        .align(Alignment.CenterStart)
                )

                // Top action icons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .align(Alignment.TopEnd),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick bookmark toggle button
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isBookmarked) Color.Black.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.45f)
                    ) {
                        IconButton(
                            onClick = onToggleBookmark,
                            enabled = !isReadOnly,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (isBookmarked) "Remove Bookmark" else "Bookmark",
                                tint = if (isBookmarked) AmberGlow else Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (!isReadOnly) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Black.copy(alpha = 0.5f)
                            ) {
                                IconButton(
                                    onClick = onEdit,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = "Configure Book",
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Black.copy(alpha = 0.5f)
                            ) {
                                IconButton(
                                    onClick = onDelete,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (coverModel == null) {
                    Text(
                        text = notebook.title,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 4,
                        lineHeight = 18.sp,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp)
                    )
                }

                // Category pill at the bottom
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = notebook.tagsCsv.split(",").firstOrNull()?.trim()?.ifBlank { "General" } ?: "General",
                        color = IndigoGlow,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            // Bottom title metadata: max 2 lines with ellipsis
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Text(
                    text = notebook.title,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}