package com.sheinsez.mdropdx12.remote.ui.screens.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheinsez.mdropdx12.remote.audio.PhoneOutput
import com.sheinsez.mdropdx12.remote.network.vban.Downlink
import com.sheinsez.mdropdx12.remote.network.vban.LostReason
import com.sheinsez.mdropdx12.remote.network.vban.VbanSessionState
import com.sheinsez.mdropdx12.remote.viewmodel.VbanViewModel
import kotlin.math.roundToInt

/**
 * Play the PC's monitor mix on this phone.
 *
 * Collapsed it is one button. Playing, it becomes the controls that matter
 * while it plays: where the sound goes, how loud it is THERE, and how far
 * behind the picture it is.
 *
 * The level is the system media volume for the selected route, not a gain
 * private to this stream. Android already keeps one per route — a Bluetooth
 * headset remembers its own, independent of the speaker — so a private gain
 * would mean two numbers both claiming to be "how loud".
 */
@Composable
fun PhoneStreamRow(vm: VbanViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val lagMs by vm.lagMs.collectAsState()
    val outputs by vm.outputs.collectAsState()
    val outputId by vm.outputId.collectAsState()
    val localVolume by vm.localVolume.collectAsState()
    val pc by vm.pcState.collectAsState()
    val rockerLocal by vm.rockerLocal.collectAsState()
    val pcSilent by vm.pcSilent.collectAsState()
    val counters by vm.counters.collectAsState()
    val downlink by vm.downlink.collectAsState()

    // AUDIO, not the session. The link now comes up on its own and stays up
    // for commands, so "the session is Streaming" no longer means anything is
    // playing -- keying the Play button on it would hide the button on a
    // phone that is silent.
    val audioOn by vm.audioOn.collectAsState()
    val idle = !audioOn

    // Paused from a watch or the lock screen. Said on the card because a
    // pause is a mute: everything else here keeps reading "playing", and a
    // card that says so over silence sends somebody hunting for a fault.
    val muted by vm.muted.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (idle) {
            OutlinedButton(
                onClick = { vm.refreshOutputs(); vm.start() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Play on phone")
            }
            (state as? VbanSessionState.Lost)?.let { lost ->
                if (lost.reason != LostReason.Stopped) {
                    Text(
                        text = reasonText(lost.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            return@Column
        }

        // ── Playing, or on the way there ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (state) {
                        VbanSessionState.Pinging -> "Connecting to the mixer…"
                        VbanSessionState.AuthPending -> "Waiting for approval on the PC"
                        VbanSessionState.Streaming ->
                            if (muted) "Paused — muted, still streaming" else "Playing on this phone"
                        else -> ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state == VbanSessionState.Streaming) {
                    Text(
                        // The number exists to be typed into a video player's
                        // audio-delay box, so it is shown plainly in ms.
                        text = "about $lagMs ms behind" +
                            (pc?.let { " · ${it.source} · ${it.format}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Hidden while healthy, which is nearly always. It exists
                    // because "some crackling" has three unrelated causes that
                    // sound identical -- this phone's buffer starving, packets
                    // lost on the way, and the PC emitting silence it has no
                    // clock for -- and two of them are not on this device, so
                    // guessing costs a debugging session.
                    // RECENTLY, not ever. The tallies are cumulative, so the
                    // old test -- any of them above zero -- was true within
                    // seconds of every session: a track underruns about nine
                    // times while its cushion fills. "Hidden while healthy"
                    // was the intent and has never been the behaviour.
                    if (counters.recentlyTroubled) {
                        Text(
                            text = buildList {
                                if (counters.underruns > 0) add("buffer dry ${counters.underruns}x")
                                if (counters.inserts > 0) {
                                    // Whose hole it was. Gaps this phone made
                                    // by discarding from a full playout queue
                                    // are a different fault from packets the
                                    // network lost, and they read identically
                                    // until they are counted apart.
                                    val mine = counters.selfInflicted
                                    add(
                                        if (mine > 0) "${counters.inserts} gaps filled ($mine ours)"
                                        else "${counters.inserts} gaps filled",
                                    )
                                }
                                if (counters.restarts > 0) add("${counters.restarts} re-locks")
                                if (counters.reprimes > 0) add("${counters.reprimes} re-primes")
                                if (counters.writeErrors > 0) add("${counters.writeErrors} write errors")
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pcSilent) {
                        // The audio gives nothing away here -- the packets are
                        // well formed and the stream looks perfectly healthy.
                        // Without this line the only symptom is no sound, and
                        // every obvious suspect is on the phone.
                        Text(
                            text = "The PC has no audio device — it is sending silence.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // THE ONE FAULT THIS APP CANNOT FIX, SO IT SAYS SO.
                    //
                    // Android's indicator reads association, signal and a
                    // connectivity check, and all three stay green while the
                    // downlink collapses -- so the stream dies behind a
                    // full-strength icon. Audio travels PC to phone, and a
                    // walk across the house took the downlink to a seventh of
                    // its rate while the uplink never moved, which is why
                    // everything that measures by sending stayed happy.
                    //
                    // Phrased as an instruction rather than a reading. Nobody
                    // holding a phone wants a megabit figure; they want to
                    // know that walking back is the fix and that waiting is
                    // not, because no socket rebuild or buffer depth carries
                    // a stream the radio will not deliver.
                    when (downlink) {
                        Downlink.Collapsed -> Text(
                            text = "The Wi-Fi downlink has collapsed — move closer to the router.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        // Said while the audio is still clean, which is the
                        // point of having a second band: the rate falls before
                        // the buffer drains, so this is the warning rather
                        // than the explanation.
                        Downlink.Weak -> Text(
                            text = "Wi-Fi is getting weak — the stream may break up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Downlink.Fine, Downlink.Unknown -> Unit
                    }
                }
            }
            if (muted) {
                TextButton(onClick = { vm.resume() }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("  Resume")
                }
            }
            TextButton(onClick = { vm.stop() }) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Text("  Stop")
            }
        }

        if (state != VbanSessionState.Streaming) return@Column

        // Where it goes.
        var menuOpen by remember { mutableStateOf(false) }
        val currentLabel = outputs.firstOrNull { it.id == outputId }?.label ?: "Automatic"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { vm.refreshOutputs(); menuOpen = true }) {
                Text(currentLabel)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                outputs.forEach { out ->
                    DropdownMenuItem(
                        text = { Text(out.label) },
                        onClick = { vm.selectOutput(out.id); menuOpen = false },
                    )
                }
            }
            Text(
                text = "${(localVolume * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // How loud it is there. This is the route's own media volume, which
        // is why switching route changes the number.
        Slider(
            value = localVolume,
            onValueChange = { vm.setLocalVolume(it) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (rockerLocal) {
                "The volume keys move this while the stream plays."
            } else {
                "The volume keys are going to the PC's faders, not to this."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun reasonText(reason: LostReason): String = when (reason) {
    LostReason.Denied -> "The PC refused this phone. Approve it there, then try again."
    LostReason.Locked -> "Too many wrong PINs — the PC has locked this phone out briefly."
    LostReason.BadPin -> "Wrong PIN for the mixer. Check it in Settings."
    LostReason.Timeout -> "Could not reach the mixer."
    LostReason.Stopped -> ""
}
