package com.sheinsez.mdropdx12.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sheinsez.mdropdx12.remote.network.link.LinkState
import com.sheinsez.mdropdx12.remote.network.link.LinkStateMachine

/**
 * Whether the visualizer can be reached, and by what.
 *
 * READS THE LINK, NOT THE TCP SOCKET. It used to render `ConnectionState`,
 * which answers a question that stopped being the interesting one: with
 * MDropDX12's own server switched off that socket is Disconnected for ever
 * and its reconnect loop cycles Connecting -> Disconnected every few seconds,
 * so the header flickered between "Connecting..." and "Not connected" while
 * every button on the screen worked. Accurate about the socket, and useless.
 *
 * `Live(tcp = false)` is the ordinary everyday state once the mixer is
 * carrying commands, so it is drawn in the same plain colour as any other
 * working state. Nothing here says "degraded" about the configuration the rig
 * is deliberately moving to.
 *
 * It is also the way in and out. While anything is connected or trying to be
 * ([engaged]), a small red X offers Disconnect or Exit; once nothing is, the
 * header itself is the Connect button. There was no way to do either before:
 * nothing on any screen called disconnect, and leaving meant swiping the app
 * away while its background service carried on.
 */
@Composable
fun ConnectionHeader(
    link: LinkState,
    presetName: String,
    serverName: String,
    engaged: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val green = Color(0xFF4CAF50)
    val amber = Color(0xFFFFC107)
    val red = Color(0xFFF44336)

    var asking by remember { mutableStateOf(false) }
    if (asking) {
        LeaveDialog(
            red = red,
            onDisconnect = { asking = false; onDisconnect() },
            onExit = { asking = false; onExit() },
            onCancel = { asking = false },
        )
    }

    val dotColor = when (link) {
        is LinkState.Live -> green
        LinkState.Reaching -> amber
        is LinkState.MixerOnly -> amber
        LinkState.Stale -> amber
        is LinkState.Lost -> red
        LinkState.Idle -> red
    }

    // What is actually carrying commands, which is the thing the old header
    // could not say at all.
    val route = when (link) {
        is LinkState.Live -> if (link.tcp) "visualizer direct" else "via the mixer"
        else -> null
    }

    // Nothing up and nothing trying: the whole header is the Connect button.
    // While anything is up or trying, the X is the way out instead.
    val tapToConnect = if (engaged) Modifier else Modifier.clickable(
        onClickLabel = "Connect",
        onClick = onConnect,
    )

    Column(modifier = modifier.then(tapToConnect).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        link is LinkState.Idle -> "Not connected"
                        serverName.isNotBlank() -> serverName
                        link is LinkState.Live -> "Connected"
                        else -> "The PC"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (route != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "· $route",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (engaged) {
                LeaveButton(red = red, onClick = { asking = true })
            }
        }

        // One sentence, and it says what to do rather than naming a mechanism.
        // Null whenever the link is working, which is when the preset name is
        // the more useful thing to show.
        val trouble = LinkStateMachine.explain(link)
        when {
            !engaged -> {
                Text(
                    text = "Tap to connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            trouble != null -> {
                Text(
                    text = trouble,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = if (link is LinkState.Lost || link is LinkState.Stale) red else amber,
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

/**
 * The small red X. A box drawn smaller than the area that takes the tap, so
 * it reads as a small control without being a small target.
 */
@Composable
private fun LeaveButton(red: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Disconnect or exit", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(red),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Disconnect",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Disconnect, Exit or Cancel.
 *
 * Asked rather than done on the tap, because the X sits beside the server name
 * where a thumb reaching for the header lands -- and a dropped stream is not
 * something to cause by accident.
 */
@Composable
private fun LeaveDialog(
    red: Color,
    onDisconnect: () -> Unit,
    onExit: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Disconnect from the PC?") },
        text = {
            Text(
                "Disconnect drops the link to the PC and stops any stream; " +
                    "MilkRemote stays open, and tapping the header connects again. " +
                    "Exit app does the same and closes MilkRemote.",
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = onExit) { Text("Exit app", color = red) }
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
