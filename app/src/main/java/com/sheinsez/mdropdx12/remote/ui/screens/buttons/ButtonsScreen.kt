package com.sheinsez.mdropdx12.remote.ui.screens.buttons

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheinsez.mdropdx12.remote.data.model.ButtonActionType
import com.sheinsez.mdropdx12.remote.data.model.ButtonConfig
import com.sheinsez.mdropdx12.remote.data.model.ButtonTemplate
import com.sheinsez.mdropdx12.remote.data.model.ButtonTemplates
import com.sheinsez.mdropdx12.remote.viewmodel.ButtonsViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ButtonsScreen(
    vm: ButtonsViewModel = viewModel(),
) {
    val buttons by vm.buttons.collectAsState()
    var editTarget by remember { mutableStateOf<ButtonConfig?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var templateToEdit by remember { mutableStateOf<ButtonTemplate?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (buttons.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No buttons yet — tap + to add one",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(buttons, key = { it.id }) { button ->
                        Card(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .combinedClickable(
                                    onClick = { vm.executeButton(button) },
                                    onLongClick = { editTarget = button },
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = button.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FloatingActionButton(
                onClick = { showTemplateDialog = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Add from template")
            }
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add custom button")
            }
        }
    }

    if (showTemplateDialog) {
        TemplatePickerDialog(
            onPick = { template ->
                showTemplateDialog = false
                templateToEdit = template
            },
            onDismiss = { showTemplateDialog = false },
        )
    }

    templateToEdit?.let { template ->
        ButtonEditDialog(
            button = ButtonConfig(
                label = template.label,
                actionType = template.actionType,
                payload = template.payload,
            ),
            onSave = { vm.saveButton(it); templateToEdit = null },
            onDismiss = { templateToEdit = null },
        )
    }

    if (showAddDialog) {
        ButtonEditDialog(
            button = null,
            onSave = { vm.saveButton(it); showAddDialog = false },
            onDismiss = { showAddDialog = false },
        )
    }

    editTarget?.let { target ->
        ButtonEditDialog(
            button = target,
            onSave = { vm.saveButton(it); editTarget = null },
            onDelete = { vm.deleteButton(target); editTarget = null },
            onDismiss = { editTarget = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ButtonEditDialog(
    button: ButtonConfig?,
    onSave: (ButtonConfig) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var label by remember { mutableStateOf(button?.label ?: "") }
    var payload by remember { mutableStateOf(button?.payload ?: "") }
    var actionType by remember { mutableStateOf(button?.actionType ?: ButtonActionType.Signal) }
    var actionDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (button == null) "Add Button" else "Edit Button") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                ExposedDropdownMenuBox(
                    expanded = actionDropdownExpanded,
                    onExpandedChange = { actionDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = actionType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Action Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(actionDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryEditable),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = actionDropdownExpanded,
                        onDismissRequest = { actionDropdownExpanded = false },
                    ) {
                        ButtonActionType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    actionType = type
                                    actionDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = { Text("Payload") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val config = ButtonConfig(
                        id = button?.id ?: 0,
                        label = label.trim(),
                        actionType = actionType,
                        payload = payload.trim(),
                        icon = button?.icon ?: "",
                        position = button?.position ?: 0,
                        usageCount = button?.usageCount ?: 0,
                    )
                    onSave(config)
                },
                enabled = label.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatePickerDialog(
    onPick: (ButtonTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add from Template") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val query = searchQuery.trim().lowercase()
                    ButtonTemplates.categories.forEach { category ->
                        val filtered = if (query.isEmpty()) {
                            category.templates
                        } else {
                            category.templates.filter {
                                it.label.lowercase().contains(query) ||
                                    it.payload.lowercase().contains(query)
                            }
                        }
                        if (filtered.isNotEmpty()) {
                            item {
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                            lazyItems(filtered) { template ->
                                Surface(
                                    onClick = { onPick(template) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 10.dp,
                                        ),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = template.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = template.actionType.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
