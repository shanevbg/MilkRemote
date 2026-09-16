package com.sheinsez.mdropdx12.remote.service

import com.sheinsez.mdropdx12.remote.data.model.MixerFader
import com.sheinsez.mdropdx12.remote.data.model.MixerQueryThrottle
import com.sheinsez.mdropdx12.remote.data.model.nudgeLinked
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.MessageParser
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Moves the linked faders from the volume keys, with no screen in front.
 *
 * The Mixer screen does this through its ViewModel, which exists only while an
 * Activity does. When the keys arrive through a media session the app may have
 * no Activity at all, so the same job has to be doable from application scope.
 *
 * **It never subscribes.** The PC polls its providers only while a client says
 * it is looking, and that count is process-wide, so a background subscription
 * would have it reading SteelSeries Sonar over HTTP forever for a screen nobody
 * has open. Levels are asked for on demand instead, at most once a second, and
 * a press made before any have arrived is held until they do.
 */
class LinkedFaderController(
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
    linkedFaders: Flow<Set<String>>,
    stepPercent: Flow<Int>,
) {
    private companion object { const val TAG = "MdrLinkedFaders" }

    private val faders = mutableMapOf<String, MixerFader>()
    private var linked: Set<String> = emptySet()
    private var step: Int = 5

    private val throttle = MixerQueryThrottle()
    private var queryJob: Job? = null

    /** A press made before any levels were known, waiting for the first batch. */
    private var pendingUp: Boolean? = null

    init {
        scope.launch {
            connectionManager.messages.collect { raw ->
                for (line in raw.lineSequence()) {
                    val msg = line.trim()
                    if (!msg.startsWith("MIXER_FADER|")) continue
                    MessageParser.parseMixerFader(msg)?.let { faders[it.key] = it }
                }
                // A press that arrived before any levels did, now answerable.
                pendingUp?.let { up ->
                    if (group().isNotEmpty()) { pendingUp = null; apply(up) }
                }
            }
        }
        scope.launch { linkedFaders.collect { linked = it } }
        scope.launch { stepPercent.collect { step = it } }
    }

    private fun group() = linked.mapNotNull { faders[it] }

    /**
     * One rocker press.
     *
     * @return false when there is nothing this could move — nothing linked, or
     * no connection — so the caller can leave the key to whatever would
     * normally have had it.
     */
    fun nudge(up: Boolean): Boolean {
        // This path runs with nothing on screen, so without a line here there
        // is no way to tell "the key never arrived" from "it arrived and there
        // was nothing to move".
        Log.d(TAG, "nudge up=$up linked=${linked.size} known=${faders.size} " +
            "state=${connectionManager.connectionState.value}")
        if (linked.isEmpty()) return false
        if (connectionManager.connectionState.value != ConnectionState.Connected) return false

        val group = group()
        if (group.isEmpty()) {
            // Nothing known yet: ask, and answer the press when the reply lands
            // rather than dropping it.
            Log.d(TAG, "no levels yet; asking and holding the press")
            pendingUp = up
            requestLevels()
            return true
        }
        apply(up)
        return true
    }

    private fun apply(up: Boolean) {
        val group = group()
        Log.d(TAG, "applying to ${group.size} fader(s)")
        val next = nudgeLinked(group, step, up)
        group.forEach { f ->
            next[f.key]?.let { pct ->
                connectionManager.send(CommandBuilder.mixerSet(f.channelId, f.faderId, pct))
                // Optimistic, as the whole mixer is: the PC's reply carries the
                // value asked for, not one read back, so holding it here is what
                // makes a run of presses accumulate instead of fighting itself.
                faders[f.key] = f.copy(volumePercent = pct)
            }
        }
    }

    /** Levels only, at most once a second. */
    private fun requestLevels() {
        if (queryJob?.isActive == true) return
        val wait = throttle.waitFor(System.currentTimeMillis())
        queryJob = scope.launch {
            if (wait > 0) delay(wait)
            throttle.record(System.currentTimeMillis())
            connectionManager.send(CommandBuilder.mixerState())
        }
    }
}
