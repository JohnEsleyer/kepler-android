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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esper.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vaultName: String,
    vaultLocation: String,
    notebookCount: Int,
    shelfCount: Int,
    onRescanVault: () -> Unit,
    onSelectVaultFolder: (Uri) -> Unit,
    onResetToDefaultVault: () -> Unit,
    onExportZip: () -> Unit,
    onImportPackage: (Uri) -> Unit,
    onNavigateBack: () -> Unit
) {
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onImportPackage(uri)
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) onSelectVaultFolder(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault & Settings", fontWeight = FontWeight.Bold, color = TextPrimary) },
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
                                text = vaultName,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Pure offline Markdown vault",
                                color = EmeraldSync,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    HorizontalDivider(color = BorderSlate.copy(alpha = 0.5f), thickness = 1.dp)

                    Text("• Stored books: $notebookCount", color = TextSecondary, fontSize = 12.sp)
                    Text("• Custom shelves: $shelfCount", color = TextSecondary, fontSize = 12.sp)
                    Text(
                        vaultLocation,
                        color = TextMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Text(
                        "• Books are ordinary folders: Markdown chapters, JSON metadata, and HTML visuals. " +
                            "Open the same folder in Obsidian, VS Code, or Git.",
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
                    Text("Vault storage", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Button(
                        onClick = onRescanVault,
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Re-scan folder & rebuild index")
                    }

                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = IndigoGlow),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open external folder as vault")
                    }

                    if (vaultName != "Default Vault") {
                        TextButton(
                            onClick = onResetToDefaultVault,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Return to internal default vault", color = AmberGlow, fontSize = 12.sp)
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
                    Text("Archive & sharing", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Button(
                        onClick = onExportZip,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export vault folder as ZIP")
                    }

                    OutlinedButton(
                        onClick = { importPicker.launch("*/*") },
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

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CosmicSlate,
                border = BorderStroke(1.dp, BorderSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("File format", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "Vault/\n" +
                            "├── .esper/            (optional UI config)\n" +
                            "├── Book Name/\n" +
                            "│   ├── book.json\n" +
                            "│   ├── 01_Chapter.md\n" +
                            "│   ├── questions.json\n" +
                            "│   └── visuals/\n" +
                            "│       └── 01_sim.html",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                    Text(
                        "Full specification: docs/vault-spec.md",
                        color = IndigoGlow,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
