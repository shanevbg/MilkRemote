package com.sheinsez.mdropdx12.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.sheinsez.mdropdx12.remote.network.ConnectionState

@Composable
fun ConnectionHeader(
    connectionState: ConnectionState,
    presetName: String,
    serverName: String,
    isReconnecting: Boolean,
    modifier: Modifier = Modifier,
) {
    val dotColor = when (connectionState) {
        ConnectionState.Connected    -> Color(0xFF4CAF50)
        ConnectionState.AuthPending  -> Color(0xFFFFC107)
        ConnectionState.Connecting   -> Color(0xFFFFC107)
        ConnectionState.Disconnected -> Color(0xFFF44336)
    }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = serverName.ifBlank { "Not connected" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        when {
            connectionState == ConnectionState.AuthPending -> {
                Text(
                    text = "Waiting for authorization on PC...",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFFFFC107),
                )
            }
            connectionState == ConnectionState.Connecting -> {
                Text(
                    text = "Connecting...",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            isReconnecting -> {
                Text(
                    text = "Reconnecting...",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            presetName.isNotBlank() -> {
                Text(
                    text = presetName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
