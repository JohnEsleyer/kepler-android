package com.esper.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esper.android.ui.theme.*

@Composable
fun CustomBottomNavBar(
    onOpenLibrary: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenCreateBook: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isReadOnly = LocalReadOnlyMode.current

    Surface(
        color = CosmicSlate,
        border = BorderStroke(1.dp, BorderSlate),
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            NavigationBar(
                containerColor = CosmicSlate,
                modifier = Modifier.height(58.dp)
            ) {
                NavigationBarItem(
                    selected = true,
                    onClick = onOpenLibrary,
                    icon = { Icon(Icons.Default.Book, contentDescription = "Library", modifier = Modifier.size(19.dp)) },
                    label = { Text("Library", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TextPrimary,
                        indicatorColor = IndigoAccent,
                        unselectedIconColor = TextMuted
                    )
                )

                NavigationBarItem(
                    selected = false,
                    enabled = !isReadOnly,
                    onClick = { if (!isReadOnly) onOpenCreateBook() },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (isReadOnly) TextMuted else IndigoAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    },
                    label = { Text("New", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        unselectedIconColor = IndigoAccent,
                        unselectedTextColor = IndigoGlow,
                        disabledIconColor = TextMuted,
                        disabledTextColor = TextMuted
                    )
                )

                NavigationBarItem(
                    selected = false,
                    onClick = onOpenTabs,
                    icon = { Icon(Icons.Default.Layers, contentDescription = "Tabs", modifier = Modifier.size(19.dp)) },
                    label = { Text("Tabs", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        unselectedIconColor = ElectricViolet,
                        unselectedTextColor = ElectricViolet
                    )
                )

                NavigationBarItem(
                    selected = false,
                    onClick = onOpenSettings,
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(19.dp)) },
                    label = { Text("Settings", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )
            }
        }
    }
}
