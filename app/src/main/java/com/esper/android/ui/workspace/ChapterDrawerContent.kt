package com.esper.android.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.esper.android.data.model.CanvasEntity
import com.esper.android.data.model.PageEntity
import com.esper.android.ui.components.ConfirmDeleteDialog
import com.esper.android.ui.components.LocalReadOnlyMode
import com.esper.android.ui.theme.*

@Composable
fun ChapterDrawerContent(
    pages: List<PageEntity>,
    canvases: List<CanvasEntity>,
    activePageIndex: Int,
    onSelectPage: (Int) -> Unit,
    onCreatePage: (title: String, parentId: String?) -> Unit,
    onDeletePage: (String) -> Unit,
    onReorderPages: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> }
) {
    val isReadOnly = LocalReadOnlyMode.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPageTitle by remember { mutableStateOf("") }
    var selectedParentId by remember { mutableStateOf<String?>(null) }
    var pageToDelete by remember { mutableStateOf<PageEntity?>(null) }

    // Reorder drag state
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    ModalDrawerSheet(
        drawerContainerColor = CosmicSlate,
        drawerContentColor = TextPrimary,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = IndigoGlow, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Chapters (${pages.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = TextPrimary
                    )
                }
                if (!isReadOnly) {
                    IconButton(onClick = {
                        selectedParentId = null
                        showCreateDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Chapter", tint = IndigoAccent)
                    }
                }
            }

            Text(
                text = "Hold & drag the handle to reorder chapters",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            HorizontalDivider(color = BorderSlate, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                    val isSelected = index == activePageIndex
                    val isSubpage = page.parentId != null
                    val hasAttachment = canvases.any { it.assignedChapter == index + 1 }
                    val isBeingDragged = draggedIndex == index

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) Color(0xFF312E81) else if (isBeingDragged) SurfaceSlate else Color.Transparent,
                        border = if (isBeingDragged) BorderStroke(1.dp, IndigoGlow) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isBeingDragged) 10f else 1f)
                            .graphicsLayer {
                                if (isBeingDragged) {
                                    translationY = dragOffsetY
                                    scaleX = 1.03f
                                    scaleY = 1.03f
                                }
                            }
                            .padding(start = if (isSubpage) 18.dp else 0.dp)
                            .clickable { onSelectPage(index) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (!isReadOnly && !isSubpage) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        tint = TextMuted,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .pointerInput(pages.size) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        draggedIndex = index
                                                        dragOffsetY = 0f
                                                    },
                                                    onDragEnd = {
                                                        val currentIndex = draggedIndex
                                                        if (currentIndex != null) {
                                                            // Each row is ~48dp tall plus 4dp spacing.
                                                            val itemHeightPx = 52.dp.toPx()
                                                            val targetShift = (dragOffsetY / itemHeightPx).toInt()
                                                            val targetIndex = (currentIndex + targetShift)
                                                                .coerceIn(0, pages.size - 1)
                                                            if (targetIndex != currentIndex) {
                                                                onReorderPages(currentIndex, targetIndex)
                                                            }
                                                        }
                                                        draggedIndex = null
                                                        dragOffsetY = 0f
                                                    },
                                                    onDragCancel = {
                                                        draggedIndex = null
                                                        dragOffsetY = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragOffsetY += dragAmount.y
                                                    }
                                                )
                                            }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }

                                Text(
                                    text = "${index + 1}.",
                                    color = if (isSelected) IndigoGlow else TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(22.dp)
                                )
                                Text(
                                    text = page.title,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                if (hasAttachment) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Attachment,
                                        contentDescription = "Attachment",
                                        tint = EmeraldSync,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }

                            if (!isReadOnly) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            selectedParentId = page.id
                                            showCreateDialog = true
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Subchapter", tint = TextMuted, modifier = Modifier.size(14.dp))
                                    }
                                    // Always available: deleting the last
                                    // chapter is allowed (the notebook keeps
                                    // its empty state until one is added).
                                    IconButton(
                                        onClick = { pageToDelete = page },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete chapter",
                                            tint = RoseRuby.copy(alpha = 0.85f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = {
                Text(
                    text = if (selectedParentId != null) "New Subchapter" else "New Chapter",
                    color = TextPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = newPageTitle,
                    onValueChange = { newPageTitle = it },
                    label = { Text("Chapter Title") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPageTitle.isNotBlank()) {
                            onCreatePage(newPageTitle.trim(), selectedParentId)
                            newPageTitle = ""
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent)
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CosmicSlate
        )
    }

    pageToDelete?.let { page ->
        ConfirmDeleteDialog(
            title = "Delete Chapter",
            message = "Are you sure you want to delete \u201C${page.title}\u201D and any subchapters?",
            onConfirm = {
                onDeletePage(page.id)
                pageToDelete = null
            },
            onDismiss = { pageToDelete = null }
        )
    }
}
