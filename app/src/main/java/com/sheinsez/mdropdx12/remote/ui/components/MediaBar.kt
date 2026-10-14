package com.sheinsez.mdropdx12.remote.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun MediaBar(
    title: String,
    artist: String,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "No track" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.isNotBlank()) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row {
            IconButton(onClick = onPrev) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous track",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next track",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
