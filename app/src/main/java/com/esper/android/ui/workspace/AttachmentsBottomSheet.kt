package com.esper.android.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esper.android.data.model.CanvasEntity
import com.esper.android.ui.components.ConfirmDeleteDialog
import com.esper.android.ui.components.LocalReadOnlyMode
import com.esper.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsBottomSheet(
    canvases: List<CanvasEntity>,
    selectedIndex: Int,
    onSelectCanvas: (Int) -> Unit,
    onAddAttachment: (title: String, kind: String, payload: String) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val isReadOnly = LocalReadOnlyMode.current
    var showDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("html") }
    var payload by remember { mutableStateOf("") }
    var canvasToDelete by remember { mutableStateOf<CanvasEntity?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CosmicSlate,
        contentColor = TextPrimary
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Companion Visuals & Attachments (${canvases.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (!isReadOnly) {
                    IconButton(onClick = { showDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = EmeraldSync)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (canvases.isEmpty()) {
                Text("No companion visuals attached yet.", color = TextMuted, fontSize = 13.sp)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    itemsIndexed(canvases) { idx, canvas ->
                        val isSelected = idx == selectedIndex
                        Surface(
                            onClick = {
                                onSelectCanvas(idx)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) SurfaceSlate else SpaceBlack,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = canvas.title,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Type: ${canvas.kind.uppercase()}",
                                        color = IndigoGlow,
                                        fontSize = 10.sp
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = EmeraldSync,
                                            modifier = Modifier.size(16.dp).padding(end = 8.dp)
                                        )
                                    }
                                    if (!isReadOnly) {
                                        IconButton(
                                            onClick = { canvasToDelete = canvas },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = RoseRuby.copy(alpha = 0.8f),
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
            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Attach Companion Visual", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = kind == "html",
                            onClick = { kind = "html" },
                            label = { Text("HTML/JS Sim") }
                        )
                        FilterChip(
                            selected = kind == "excalidraw",
                            onClick = {
                                kind = "excalidraw"
                                if (payload.isBlank()) payload = "{\"elements\":[]}"
                            },
                            label = { Text("Drawing") }
                        )
                    }

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Visual Title") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it },
                        label = { Text(if (kind == "excalidraw") "Drawing JSON (elements)" else "HTML / JS Simulation Code") },
                        placeholder = {
                            Text("<!DOCTYPE html>...")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalTitle = when (kind) {
                            "excalidraw" -> title.trim().ifBlank { "Whiteboard Canvas" }
                            else -> title.trim()
                        }
                        val finalPayload = if (kind == "excalidraw" && payload.isBlank()) "{\"elements\":[]}" else payload.trim()
                        if (finalPayload.isNotBlank() && (kind == "excalidraw" || finalTitle.isNotBlank())) {
                            onAddAttachment(finalTitle, kind, finalPayload)
                            title = ""
                            payload = ""
                            showDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync)
                ) {
                    Text("Attach")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = CosmicSlate
        )
    }

    canvasToDelete?.let { canvas ->
        ConfirmDeleteDialog(
            title = "Delete Companion Visual",
            message = "Are you sure you want to delete \u201C${canvas.title}\u201D?",
            onConfirm = {
                onDeleteAttachment(canvas.id)
                canvasToDelete = null
            },
            onDismiss = { canvasToDelete = null }
        )
    }
}
