package com.sheinsez.mdropdx12.remote.ui.screens.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.sheinsez.mdropdx12.remote.viewmodel.RemoteViewModel

@Composable
fun RemoteScreen(
    vm: RemoteViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // Transport controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = { vm.prevPreset() },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("Prev Preset", style = MaterialTheme.typography.titleSmall)
            }
            Button(
                onClick = { vm.nextPreset() },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("Next Preset", style = MaterialTheme.typography.titleSmall)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Mode buttons grid
        Text(
            text = "Mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val modeButtons = listOf(
            "Fullscreen"   to "SIG_FULLSCREEN",
            "Mirror"       to "SIG_MIRROR",
            "Mirror WM"    to "SIG_MIRROR_WM",
            "Watermark"    to "SIG_WATERMARK",
            "Capture"      to "SIG_CAPTURE",
            "Borderless"   to "SIG_BORDERLESS",
        )
        // Fixed-height grid to avoid nested scroll conflicts
        val modeRows = (modeButtons.size + 2) / 3
        val modeGridHeight = (modeRows * 48 + (modeRows - 1) * 8 + 16).dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(modeGridHeight)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false,
        ) {
            items(modeButtons) { (label, signal) ->
                FilledTonalButton(
                    onClick = { vm.sendSignal(signal) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Quick buttons placeholder
        Text(
            text = "Quick Buttons",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = "Assign buttons in the Buttons tab",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // Wave Controls
        CollapsibleSection(title = "Wave Controls") {
            SliderControl(
                label = "Mode",
                value = state.waveMode.toFloat(),
                range = 0f..7f,
                onValueChange = { vm.updateWaveParam("wave_mode", it.toInt().toString()) },
                step = 1f,
                valueFormat = { it.toInt().toString() },
            )
            SliderControl(
                label = "Alpha",
                value = state.waveAlpha,
                range = 0f..1f,
                onValueChange = { vm.updateWaveParam("wave_a", "%.3f".format(it)) },
            )
            SliderControl(
                label = "Scale",
                value = state.waveScale,
                range = 0f..2f,
                onValueChange = { vm.updateWaveParam("wave_scale", "%.3f".format(it)) },
            )
            SliderControl(
                label = "Zoom",
                value = state.waveZoom,
                range = 0f..2f,
                onValueChange = { vm.updateWaveParam("zoom", "%.3f".format(it)) },
            )
            SliderControl(
                label = "Warp",
                value = state.waveWarp,
                range = 0f..2f,
                onValueChange = { vm.updateWaveParam("warp", "%.3f".format(it)) },
            )
            SliderControl(
                label = "Rotation",
                value = state.waveRotation,
                range = -1f..1f,
                onValueChange = { vm.updateWaveParam("rot", "%.3f".format(it)) },
            )
            SliderControl(
                label = "Decay",
                value = state.waveDecay,
                range = 0f..1f,
                onValueChange = { vm.updateWaveParam("decay", "%.3f".format(it)) },
            )

            // Toggle checkboxes
            val toggles = listOf(
                "Brighten"  to state.waveBrighten  to "wave_brighten",
                "Darken"    to state.waveDarken    to "wave_darken",
                "Solarize"  to state.waveSolarize  to "wave_solarize",
                "Invert"    to state.waveInvert    to "wave_invert",
                "Additive"  to state.waveAdditive  to "additivewave",
                "Thick"     to state.waveThick     to "wave_usedots",
            )
            toggles.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    pair.forEach { (labelAndValue, key) ->
                        val (label, checked) = labelAndValue
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { vm.updateWaveParam(key, if (it) "1" else "0") },
                            )
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Color
        CollapsibleSection(title = "Color") {
            SliderControl(
                label = "Hue",
                value = state.colorHue,
                range = 0f..360f,
                onValueChange = { vm.updateColor(hue = it) },
                valueFormat = { "%.0f°".format(it) },
            )
            SliderControl(
                label = "Saturation",
                value = state.colorSaturation,
                range = 0f..2f,
                onValueChange = { vm.updateColor(saturation = it) },
            )
            SliderControl(
                label = "Brightness",
                value = state.colorBrightness,
                range = 0f..2f,
                onValueChange = { vm.updateColor(brightness = it) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.hueAuto,
                    onCheckedChange = { vm.updateWaveParam("hue_auto", if (it) "1" else "0") },
                )
                Text("Auto Hue", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Variables
        CollapsibleSection(title = "Variables") {
            SliderControl(
                label = "Time",
                value = state.varTime,
                range = 0f..2f,
                onValueChange = { vm.updateVariable(time = it) },
            )
            SliderControl(
                label = "Intensity",
                value = state.varIntensity,
                range = 0f..2f,
                onValueChange = { vm.updateVariable(intensity = it) },
            )
            SliderControl(
                label = "Quality",
                value = state.varQuality,
                range = 0f..1f,
                onValueChange = { vm.updateVariable(quality = it) },
            )
        }

        // Audio
        CollapsibleSection(title = "Audio") {
            SliderControl(
                label = "Amp Left",
                value = state.ampLeft,
                range = 0f..4f,
                onValueChange = { vm.updateAmp(it, state.ampRight) },
            )
            SliderControl(
                label = "Amp Right",
                value = state.ampRight,
                range = 0f..4f,
                onValueChange = { vm.updateAmp(state.ampLeft, it) },
            )
            SliderControl(
                label = "FFT Attack",
                value = state.fftAttack,
                range = 0f..1f,
                onValueChange = { vm.updateFftAttack(it) },
            )
            SliderControl(
                label = "FFT Decay",
                value = state.fftDecay,
                range = 0f..1f,
                onValueChange = { vm.updateFftDecay(it) },
            )
        }

        // Message
        CollapsibleSection(title = "Message") {
            var messageText by remember { mutableStateOf("") }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Message text") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            vm.sendMessage(messageText)
                            messageText = ""
                        }
                    },
                ) {
                    Text("Send")
                }
            }
        }

        // Raw Command
        CollapsibleSection(title = "Raw Command") {
            var rawText by remember { mutableStateOf("") }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("Command") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        if (rawText.isNotBlank()) {
                            vm.sendRaw(rawText)
                            rawText = ""
                        }
                    },
                ) {
                    Text("Send")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
