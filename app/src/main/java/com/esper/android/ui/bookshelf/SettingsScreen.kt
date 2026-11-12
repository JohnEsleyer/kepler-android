package com.esper.android.ui.bookshelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esper.android.data.vault.WorkspacePathHelper
import com.esper.android.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    workspaceName: String,
    workspaceLocation: String,
    notebookCount: Int,
    onRescanWorkspace: () -> Unit,
    onSelectWorkspaceFolder: (Uri) -> Unit,
    onExportZip: () -> Unit,
    onImportPackage: (Uri) -> Unit,
    onScanOrphans: (suspend () -> List<com.esper.android.data.vault.VaultManager.OrphanScanResult>)? = null,
    onDeleteOrphans: ((bookId: String, orphanIds: List<String>) -> Unit)? = null,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onImportPackage(uri)
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) onSelectWorkspaceFolder(uri)
    }

    val displayPath = remember(workspaceLocation) {
        WorkspacePathHelper.getDisplayPath(workspaceLocation)
    }
    val coroutineScope = rememberCoroutineScope()
    var orphanScanResults by remember { mutableStateOf<List<com.esper.android.data.vault.VaultManager.OrphanScanResult>?>(null) }
    var isScanningOrphans by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspace & Settings", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
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
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CosmicSlate,
                border = BorderStroke(1.dp, BorderSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(IndigoAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = workspaceName,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = if (displayPath.isNotEmpty()) "Kepler Folder Workspace" else "No Workspace Open",
                                color = if (displayPath.isNotEmpty()) EmeraldSync else TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    HorizontalDivider(color = BorderSlate.copy(alpha = 0.5f), thickness = 1.dp)

                    Text("• Stored books: $notebookCount", color = TextSecondary, fontSize = 12.sp)

                    if (displayPath.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Folder Path on Device:",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SurfaceSlate,
                                border = BorderStroke(1.dp, BorderSlate),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = displayPath,
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 15.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            WorkspacePathHelper.copyPathToClipboard(context, displayPath)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy Path",
                                            tint = IndigoGlow,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            WorkspacePathHelper.openFolderInFileManager(context, workspaceLocation)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Launch,
                                            contentDescription = "Open in File Manager",
                                            tint = EmeraldSync,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No workspace folder selected yet. Tap below to choose a folder.",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    Text(
                        text = "• Books are local directories containing Markdown chapters (*.md), book.json metadata, and visuals/ companions.",
                        color = IndigoGlow,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CosmicSlate,
                border = BorderStroke(1.dp, BorderSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Workspace Storage", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    if (displayPath.isNotEmpty()) {
                        Button(
                            onClick = onRescanWorkspace,
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Re-scan workspace & index")
                        }
                    }

                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = IndigoGlow),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (displayPath.isNotEmpty()) "Change workspace folder" else "Open workspace folder")
                    }
                }
            }

            if (onScanOrphans != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = CosmicSlate,
                    border = BorderStroke(1.dp, BorderSlate)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Universal Orphan Subpages Scanner", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = "Scans all books for subpages (<id>.md) whose links do not exist in any chapter or sibling subpage.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isScanningOrphans = true
                                    try {
                                        orphanScanResults = onScanOrphans()
                                    } finally {
                                        isScanningOrphans = false
                                    }
                                }
                            },
                            enabled = !isScanningOrphans && displayPath.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isScanningOrphans) "Scanning..." else "Scan for Orphan Subpages")
                        }

                        orphanScanResults?.let { results ->
                            val total = results.sumOf { it.orphanPageIds.size }
                            if (total == 0) {
                                Text("No orphaned subpages found! All child pages are referenced.", color = EmeraldSync, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            } else {
                                Text("Found $total unreferenced subpages across ${results.size} notebooks:", color = AmberGlow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                results.forEach { res ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${res.bookTitle}: ${res.orphanPageIds.size} orphans", color = TextPrimary, fontSize = 11.sp)
                                        TextButton(
                                            onClick = {
                                                onDeleteOrphans?.invoke(res.bookId, res.orphanPageIds)
                                                orphanScanResults = orphanScanResults?.filterNot { it.bookId == res.bookId }
                                            }
                                        ) {
                                            Text("Clean Up", color = RoseRuby, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CosmicSlate,
                border = BorderStroke(1.dp, BorderSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Archive & Packages", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Button(
                        onClick = onExportZip,
                        enabled = displayPath.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export workspace as ZIP")
                    }

                    OutlinedButton(
                        onClick = { importPicker.launch("*/*") },
                        enabled = displayPath.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldSync),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import book package (.zip / .json)")
                    }
                }
            }
        }
    }
}
