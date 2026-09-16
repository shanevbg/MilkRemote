package com.sheinsez.mdropdx12.remote.ui.screens.mixer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheinsez.mdropdx12.remote.data.model.MixerDevice
import com.sheinsez.mdropdx12.remote.data.model.PcFeature
import com.sheinsez.mdropdx12.remote.data.model.batteryFor
import com.sheinsez.mdropdx12.remote.data.model.displayLabels
import com.sheinsez.mdropdx12.remote.ui.components.CollapsibleSection
import com.sheinsez.mdropdx12.remote.ui.components.VolumeSpinner
import com.sheinsez.mdropdx12.remote.viewmodel.MixerViewModel
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel

@Composable
fun MixerScreen(
    vm: MixerViewModel = viewModel(),
    settingsVm: SettingsViewModel = viewModel(),
) {
    val faders by vm.faders.collectAsState()
    val view by vm.view.collectAsState()
    val identity by vm.identity.collectAsState()
    val devices by vm.devices.collectAsState()
    val loading by vm.loading.collectAsState()
    val showVirtual by vm.showVirtual.collectAsState()
    val unavailable by vm.unavailable.collectAsState()
    val linkedFaders by settingsVm.linkedFaders.collectAsState(initial = emptySet())
    val expanded by settingsVm.expandedSections.collectAsState(initial = emptySet())

    // Subscribe only while this screen is actually in front. The PC polls
    // nothing unless a client says it is looking, and its count is
    // process-wide, so a screen left subscribed in the background makes it
    // poll for nobody.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Arriving on this tab while the Activity is ALREADY started produces
        // no ON_START, and an observer added afterwards is never sent the one
        // that has passed. Arming only from the observer therefore left the
        // screen unsubscribed for the common case -- tapping Mixer on a running
        // app -- so the PC pushed nothing and every row sat frozen at whatever
        // the first query returned.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            vm.subscribe(true)
            vm.refresh()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { vm.subscribe(true); vm.refresh() }
                Lifecycle.Event.ON_STOP -> vm.subscribe(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.subscribe(false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // -- Slots: always visible, never collapsible --
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Volume",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        // The PC distinguishes "switched off" from "nothing to report", so say
        // which it was rather than leaving a blank screen to be read as a
        // machine with no audio.
        if (unavailable) {
            Text(
                text = "The audio mixer is switched off on the PC. Enable it there " +
                    "(MIXER_ENABLE=1) and pull Refresh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else if (faders.isEmpty() && !loading) {
            Text(
                text = "No mixer information yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Under the header rather than buried in Options: a hardware key that
        // silently drives four faders is the kind of thing a user has to be
        // able to see, not discover.
        if (linkedFaders.isNotEmpty()) {
            Text(
                text = "${linkedFaders.size} fader(s) follow the volume keys, together.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // The PC's rule: a channel with one fader is named by the CHANNEL, so
        // an endpoint row is "XM5 Black #1" rather than "XM5 Black #1 - Volume".
        // Where a device has been given a short name on the PC, that name is
        // already what arrives as its channel name.
        val labels = displayLabels(faders)
        faders.forEach { f ->
            val isLinked = f.key in linkedFaders
            // Which of the PC's volume-hotkey groups this fader is in.
            // These are the rows a key press at the desk moves, and without
            // it the user is left guessing why one row jumped.
            val groupTag = if (f.groups.isEmpty()) "" else
                f.groups.sorted().joinToString(",", prefix = "[G", postfix = "] ")
            VolumeSpinner(
                // Flat, so no header above the row says which channel it is:
                // six channels' worth of "Monitoring" would be six identical
                // rows.
                // The charge of the headset this row controls, when it is the
                // kind of device that has one and is actually connected.
                label = groupTag + labels.getValue(f.key) +
                    (batteryFor(f, devices)?.let { "  ·  $it%" } ?: ""),
                percent = f.volumePercent,
                muted = f.muted,
                onPercent = { vm.setFader(f, it) },
                onMute = { vm.muteFader(f, it) },
                canMute = f.canMute,
                labelAbove = true,
            ) {
                IconToggleButton(
                    checked = isLinked,
                    onCheckedChange = { settingsVm.toggleFaderLink(f.key, it) },
                ) {
                    Icon(
                        imageVector = if (isLinked) Icons.Default.Link
                                      else Icons.Default.LinkOff,
                        contentDescription = if (isLinked)
                            "Unlink ${labels.getValue(f.key)} from the volume keys"
                        else "Link ${labels.getValue(f.key)} to the volume keys",
                        tint = if (isLinked) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // -- Reorder: below the volume controls, its own header --
        CollapsibleSection(
            title = "Reorder",
            expanded = "mixer.reorder" in expanded,
            onExpandedChange = { settingsVm.toggleSectionExpanded("mixer.reorder", it) },
        ) {
            Column {
                Text(
                    text = "The order is the PC's — moving a fader here moves it there too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                // Said once, and only when it applies: a pinned row will not
                // move, and a Move Up that does nothing reads as broken unless
                // the reason is on screen.
                if (view?.rows?.any { it.pinned } == true) {
                    Text(
                        text = "Pinned rows are held at the top by the PC while the " +
                            "device is connected, so they will not move.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                // The same rows, in the same sequence as above and on the PC.
                faders.forEach { f ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val pinned = view?.byKey?.get(f.key)?.pinned == true
                        Text(
                            text = labels.getValue(f.key) +
                                if (pinned) "  •  pinned" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pinned) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // By key: a row index would be wrong the moment the
                        // refetched order rebuilds the list, which is the bug
                        // the PC's own list had.
                        //
                        // Dead for a pinned row. The PC holds it at the top
                        // while its device is connected, so a move there
                        // rewrites the stored order and changes nothing on
                        // either screen -- a press that silently rearranges
                        // something invisible is worse than one that is
                        // plainly unavailable.
                        TextButton(
                            onClick = { vm.moveFader(f.key, -1) },
                            enabled = !pinned,
                        ) { Text("Up") }
                        TextButton(
                            onClick = { vm.moveFader(f.key, 1) },
                            enabled = !pinned,
                        ) { Text("Down") }
                        // Hiding sits with the other list-management actions
                        // rather than on the volume row: it is a decision
                        // about the LIST, and that row has no width left.
                        TextButton(
                            onClick = { vm.setFaderHidden(f, true) },
                        ) { Text("Hide") }
                    }
                }
            }
        }

        // -- Devices: read-only --
        CollapsibleSection(
            title = "Devices",
            expanded = "mixer.devices" in expanded,
            onExpandedChange = { settingsVm.toggleSectionExpanded("mixer.devices", it) },
        ) {
            DeviceList(devices)
        }

        // -- Options --
        CollapsibleSection(
            title = "Options",
            expanded = "mixer.options" in expanded,
            onExpandedChange = { settingsVm.toggleSectionExpanded("mixer.options", it) },
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show hidden faders", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "The rows the PC is hiding: endpoints another program " +
                                "owns, named like the faders above, sitting at 100 and " +
                                "ignoring changes. Shown at the end of the list, because " +
                                "the PC draws them nowhere.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = showVirtual, onCheckedChange = { vm.setShowVirtual(it) })
                }
                OutlinedButton(
                    // Explicit press: the one place a full provider re-read
                    // on the PC is worth its cost.
                    onClick = { vm.refresh(force = true) },
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<MixerDevice>) {
    // Monitor and HDMI outputs are dozens of entries nobody opens this screen
    // for. Connected first, then most recently seen: a device not used for a
    // while has been on its charger, which is worth knowing when picking one.
    val shown = devices
        .filterNot { it.displayAudio }
        .sortedWith(compareByDescending<MixerDevice> { it.active }.thenByDescending { it.lastSeen })
        .take(20)

    if (shown.isEmpty()) {
        Text(
            text = "No devices reported.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }

    Column {
        shown.forEach { d ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = d.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Only for a connected device: Windows keeps the last reading
                    // after a disconnect, so a stale number would look current.
                    d.batteryPercent?.takeIf { d.active }?.let {
                        Text(
                            text = "$it%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(if (d.active) "connected" else "last seen ${d.lastSeen}")
                        append("  •  ")
                        append(d.flow)
                        // The hands-free endpoint of a Bluetooth headset: enables
                        // the mic and sounds far worse. It shares a name with the
                        // good one, so it has to be called out.
                        if (d.handsfree) append("  •  hands-free (lower quality)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
