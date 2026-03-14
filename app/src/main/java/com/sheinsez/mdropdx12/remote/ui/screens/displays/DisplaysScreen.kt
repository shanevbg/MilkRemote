package com.sheinsez.mdropdx12.remote.ui.screens.displays

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheinsez.mdropdx12.remote.ui.components.CollapsibleSection
import com.sheinsez.mdropdx12.remote.ui.components.SliderControl
import com.sheinsez.mdropdx12.remote.viewmodel.DisplaysViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaysScreen(
    vm: DisplaysViewModel = viewModel(),
) {
    val mirrorState by vm.mirrorState.collectAsState()

    val monitorCount = mirrorState?.monitors?.size ?: 4
    val currentOpacity = mirrorState?.renderOpacity?.times(100)?.toInt() ?: 100
    val currentClickThrough = mirrorState?.renderClickThrough ?: false

    var localOpacity by remember(currentOpacity) { mutableIntStateOf(currentOpacity) }
    var localClickThrough by remember(currentClickThrough) { mutableStateOf(currentClickThrough) }
    var selectedDisplay by remember { mutableIntStateOf(1) }
    var displayDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // Global opacity
        SliderControl(
            label = "Global Opacity",
            value = localOpacity.toFloat(),
            range = 0f..100f,
            onValueChange = {
                localOpacity = it.toInt()
                vm.setGlobalOpacity(localOpacity)
            },
            step = 1f,
            valueFormat = { "${it.toInt()}%" },
        )

        Spacer(Modifier.height(8.dp))

        // Click-through toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Click-through",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = localClickThrough,
                onCheckedChange = {
                    localClickThrough = it
                    vm.setClickThrough(it)
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        // Move to display
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Move to Display",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box {
                ExposedDropdownMenuBox(
                    expanded = displayDropdownExpanded,
                    onExpandedChange = { displayDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = "Display $selectedDisplay",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(displayDropdownExpanded) },
                        modifier = Modifier
                            .width(160.dp)
                            .menuAnchor(MenuAnchorType.PrimaryEditable),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = displayDropdownExpanded,
                        onDismissRequest = { displayDropdownExpanded = false },
                    ) {
                        (1..monitorCount).forEach { n ->
                            DropdownMenuItem(
                                text = { Text("Display $n") },
                                onClick = {
                                    selectedDisplay = n
                                    displayDropdownExpanded = false
                                    vm.moveToDisplay(n)
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Refresh button
        OutlinedButton(
            onClick = { vm.refresh() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text("Refresh Display Info")
        }

        // Advanced section
        CollapsibleSection(title = "Advanced") {
            Text(
                text = "Monitor map coming soon",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
