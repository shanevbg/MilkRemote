package com.sheinsez.mdropdx12.remote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A level as -10 / -1 / value / +1 / +10 and a mute toggle.
 *
 * Buttons rather than a slider: a slider on a phone cannot place a value
 * precisely and cannot be used without looking at it. The coarse step is what
 * makes a long move quick; the fine one is what makes it exact.
 *
 * The step buttons are shrunk below Material's 58dp minimum on purpose: four of
 * them at that width, plus the value and the mute icon, is already wider than a
 * 360dp phone before the label gets a single pixel.
 */
@Composable
fun VolumeSpinner(
    label: String,
    percent: Int,
    muted: Boolean,
    onPercent: (Int) -> Unit,
    onMute: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False draws no mute control at all. The Sonar master reports canMute=0
     * because muting it writes every render channel's mute and loses their
     * states coming back -- so the button is removed rather than disabled: a
     * greyed control still says "this exists and you may earn it", and this one
     * must never be pressed.
     */
    canMute: Boolean = true,
    /**
     * Draws the label on its own line above the controls rather than beside
     * them.
     *
     * Four step buttons, the value, a mute and a link leave a label column
     * about six characters wide on a 1080px phone. That was survivable while a
     * channel header carried the name and the row only had to say
     * "Monitoring"; with the list flat the row carries both, and
     * "Aux — Monitoring" and "Aux — Streaming" both render as "Aux …".
     */
    labelAbove: Boolean = false,
    /**
     * Emphasises the name. Used for the two faders the PC's Personal and
     * Streaming hotkeys point at, which its own Mixer tab bolds and tags for
     * the same reason: on this machine both are called "Volume" and sit on
     * channels whose names differ only past where the column ends.
     */
    labelBold: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val labelColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                     else MaterialTheme.colorScheme.onSurface

    val name = @Composable { mod: Modifier ->
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (labelBold) FontWeight.Bold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = labelColor,
            modifier = mod,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (labelAbove) name(Modifier.fillMaxWidth().padding(bottom = 2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Either way the controls start at the same x, so the columns line
            // up down the list whichever form a row is in.
            if (labelAbove) Spacer(modifier = Modifier.weight(1f))
            else name(Modifier.weight(1f))

            OutlinedButton(
                onClick = { onPercent((percent - 10).coerceAtLeast(0)) },
                enabled = percent > 0,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 34.dp),
            ) { Text("−10") }

            OutlinedButton(
                onClick = { onPercent((percent - 1).coerceAtLeast(0)) },
                enabled = percent > 0,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 34.dp),
            ) { Text("−1") }

            Text(
                text = "$percent",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(44.dp),
            )

            OutlinedButton(
                onClick = { onPercent((percent + 1).coerceAtMost(100)) },
                enabled = percent < 100,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 34.dp),
            ) { Text("+1") }

            OutlinedButton(
                onClick = { onPercent((percent + 10).coerceAtMost(100)) },
                enabled = percent < 100,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 34.dp),
            ) { Text("+10") }

            if (canMute) {
                IconButton(onClick = { onMute(!muted) }) {
                    Icon(
                        imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff
                                      else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (muted) "Unmute $label" else "Mute $label",
                        tint = if (muted) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                // Hold the column so the values and the link buttons stay aligned
                // with the rows that do have a mute.
                Spacer(modifier = Modifier.width(48.dp))
            }

            trailing?.invoke()
        }
    }
}
