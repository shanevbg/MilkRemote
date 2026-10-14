package com.sheinsez.mdropdx12.remote.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Use external state if provided, otherwise local state
    var localExpanded by remember { mutableStateOf(initiallyExpanded) }
    val isExpanded = expanded ?: localExpanded
    val toggleExpanded = {
        val newValue = !isExpanded
        if (onExpandedChange != null) {
            onExpandedChange(newValue)
        } else {
            localExpanded = newValue
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { toggleExpanded() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                content()
            }
        }
    }
}
