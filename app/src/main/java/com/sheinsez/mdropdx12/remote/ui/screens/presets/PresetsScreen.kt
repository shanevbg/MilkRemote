package com.sheinsez.mdropdx12.remote.ui.screens.presets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheinsez.mdropdx12.remote.data.model.PresetRow
import com.sheinsez.mdropdx12.remote.data.model.PresetSort
import com.sheinsez.mdropdx12.remote.viewmodel.DisplaysViewModel
import com.sheinsez.mdropdx12.remote.viewmodel.PresetsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    vm: PresetsViewModel = viewModel(),
    displaysVm: DisplaysViewModel = viewModel(),
) {
    val rows by vm.rows.collectAsState()
    val total by vm.total.collectAsState()
    val loading by vm.loading.collectAsState()
    val folders by vm.folders.collectAsState()
    val tags by vm.tags.collectAsState()
    val filter by vm.filter.collectAsState()
    val target by vm.targetDisplay.collectAsState()
    val mirrorState by displaysVm.mirrorState.collectAsState()

    var editing by remember { mutableStateOf<PresetRow?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.refreshGroups(); vm.reload() }

    // Fetch the next page while there is still a screenful to scroll through,
    // rather than at the very end where the user would see the list stall.
    LaunchedEffect(listState, rows.size, total) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= rows.size - 8) vm.loadMore() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = vm::setQuery,
            label = { Text("Search presets") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        TargetRow(
            target = target,
            displays = mirrorState?.monitors?.map { it.deviceNumber }.orEmpty(),
            onPick = vm::setTargetDisplay,
        )

        GroupChips(
            filter = filter,
            folders = folders,
            tags = tags,
            onFolder = vm::setFolder,
            onTag = vm::setTag,
            onMinRating = vm::setMinRating,
            onSort = vm::setSort,
            onClear = vm::clearFilters,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (total == 0 && !loading) "No presets match"
                       else "${rows.size} of $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        HorizontalDivider()

        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(rows, key = { it.hash.ifBlank { it.name } + it.folder }) { row ->
                PresetRowItem(
                    row = row,
                    onPlay = { vm.play(row) },
                    onEdit = { editing = row },
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { row ->
        AnnotateSheet(
            row = row,
            knownTags = tags.map { it.name },
            onRate = { vm.rate(row, it) },
            onFlag = { f, on -> vm.setFlag(row, f, on) },
            onTag = { t, on -> vm.setTagOn(row, t, on) },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun TargetRow(target: Int?, displays: List<Int>, onPick: (Int?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Send to",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterChip(
            selected = target == null,
            onClick = { onPick(null) },
            label = { Text("Main") },
        )
        displays.forEach { n ->
            FilterChip(
                selected = target == n,
                onClick = { onPick(n) },
                label = { Text("Display $n") },
            )
        }
    }
}

@Composable
private fun GroupChips(
    filter: com.sheinsez.mdropdx12.remote.data.model.PresetFilter,
    folders: List<com.sheinsez.mdropdx12.remote.data.model.PresetGroup>,
    tags: List<com.sheinsez.mdropdx12.remote.data.model.PresetGroup>,
    onFolder: (String?) -> Unit,
    onTag: (String?) -> Unit,
    onMinRating: (Int) -> Unit,
    onSort: (PresetSort) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = filter.folder != null || filter.tag != null ||
        filter.minRating > 0 || filter.query.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter.minRating > 0,
            onClick = { onMinRating(if (filter.minRating > 0) 0 else 4) },
            label = { Text("4★+") },
        )
        FilterChip(
            selected = expanded,
            onClick = { expanded = !expanded },
            label = {
                Text(filter.folder ?: filter.tag ?: "Groups")
            },
        )
        Spacer(Modifier.weight(1f))
        if (active) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }

    if (expanded) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "Sort",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PresetSort.entries.forEach { s ->
                    FilterChip(
                        selected = filter.sort == s,
                        onClick = { onSort(s) },
                        label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "Folders",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GroupWrap(folders.map { it.name to it.count }, filter.folder) { onFolder(it) }

            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tags",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GroupWrap(tags.map { it.name to it.count }, filter.tag) { onTag(it) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupWrap(
    entries: List<Pair<String, Int>>,
    selected: String?,
    onPick: (String?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (name, count) ->
            FilterChip(
                selected = selected == name,
                onClick = { onPick(if (selected == name) null else name) },
                label = {
                    Text("$name  $count", style = MaterialTheme.typography.labelSmall)
                },
            )
        }
    }
}

/** Cumulative screen time, as a person would say it rather than in seconds. */
private fun formatWatched(seconds: Int): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m watched"
    seconds >= 60 -> "${seconds / 60}m watched"
    else -> "${seconds}s watched"
}

@Composable
private fun PresetRowItem(row: PresetRow, onPlay: () -> Unit, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.canLoad) { onPlay() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name.removeSuffix(".milk"),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (row.canLoad) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = buildString {
                    append(row.folder)
                    if (row.useCount > 0) append("  •  played ${row.useCount}×")
                    if (row.secondsShown > 0) append("  •  ${formatWatched(row.secondsShown)}")
                    // The PC resolves and records a path when it can, so this
                    // means genuinely gone rather than merely unrecorded.
                    if (row.missing) append("  •  file not found")
                    if (row.hasError) append("  •  error")
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (row.rating > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${row.rating}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        TextButton(onClick = onEdit, enabled = row.canAnnotate) { Text("Edit") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotateSheet(
    row: PresetRow,
    knownTags: List<String>,
    onRate: (Int) -> Unit,
    onFlag: (String, Boolean) -> Unit,
    onTag: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var newTag by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(row.name.removeSuffix(".milk"), style = MaterialTheme.typography.titleMedium)
            Text(
                text = row.path.ifBlank { "no file found" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(12.dp))
            Text("Rating", style = MaterialTheme.typography.labelMedium)
            Row {
                (1..5).forEach { star ->
                    IconButton(onClick = { onRate(if (row.rating == star) 0 else star) }) {
                        Icon(
                            if (star <= row.rating) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = "$star stars",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Flags", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("favorite", "skip", "broken", "error").forEach { f ->
                    FilterChip(
                        selected = f in row.flags,
                        onClick = { onFlag(f, f !in row.flags) },
                        label = { Text(f, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Tags", style = MaterialTheme.typography.labelMedium)
            TagEditor(
                current = row.tags,
                known = knownTags,
                newTag = newTag,
                onNewTagChange = { newTag = it },
                onToggle = onTag,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(
    current: Set<String>,
    known: List<String>,
    newTag: String,
    onNewTagChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (known + current).distinct().forEach { t ->
            FilterChip(
                selected = t in current,
                onClick = { onToggle(t, t !in current) },
                label = { Text(t, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newTag,
            onValueChange = onNewTagChange,
            label = { Text("New tag") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onToggle(newTag.trim(), true); onNewTagChange("") },
            enabled = newTag.isNotBlank(),
        ) { Text("Add") }
    }
}
