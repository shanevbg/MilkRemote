package com.sheinsez.mdropdx12.remote.service

import com.sheinsez.mdropdx12.remote.data.model.HeldPress
import com.sheinsez.mdropdx12.remote.data.model.MixerFader
import com.sheinsez.mdropdx12.remote.data.model.MixerQueryThrottle
import com.sheinsez.mdropdx12.remote.data.model.nudgeLinked
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.MessageParser
import com.sheinsez.mdropdx12.remote.network.link.MdxLink
import com.sheinsez.mdropdx12.remote.network.vban.MdxmMixer
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
    private val link: MdxLink,
    /**
     * Where the levels come from whenever there is a mixer session.
     *
     * They used to come from MIXER_STATE alone, which cannot cross the relay:
     * with the visualizer's own socket off the question was refused, the press
     * was held for an answer that was never going to arrive, and the keys did
     * nothing. The mixer answers for its own faders directly.
     */
    private val mixer: MdxmMixer,
    private val scope: CoroutineScope,
    linkedFaders: Flow<Set<String>>,
    stepPercent: Flow<Int>,
) {
    private companion object {
        const val TAG = "MdrLinkedFaders"

        /**
         * How long to let a fader batch settle before measuring a held press
         * against it.
         *
         * Records arrive one per emission over the relay rather than packed
         * into one chunk, so "the group is non-empty" is true after the FIRST
         * fader rather than after most of them. Acting then clamps the group
         * against one level and moves the rest wrongly.
         */
        const val SETTLE_MS = 250L

        /**
         * How old a reading from the mixer may be and still be stepped from.
         *
         * Nothing keeps the state fresh on this path -- that is the Mixer
         * screen's doing, and it is not in front. "Up one step" from a level
         * that was changed at the PC since is not a step.
         */
        const val FRESH_MS = 2_000L

        /** One more ask, in case the answer to the first was lost on the way. */
        const val REASK_MS = 400L
    }

    /** A press waiting for a reading from the mixer fresh enough to step from. */
    private val heldForMixer = HeldPress()

    private fun nowMs() = System.nanoTime() / 1_000_000L

    private val faders = mutableMapOf<String, MixerFader>()
    private var linked: Set<String> = emptySet()
    private var step: Int = 5

    private val throttle = MixerQueryThrottle()
    private var queryJob: Job? = null
    private var settleJob: Job? = null

    /** A press made before any levels were known, waiting for the first batch. */
    private var pendingUp: Boolean? = null

    init {
        scope.launch {
            // One RECORD per emission now, where this used to get a whole
            // batch with embedded newlines and iterate it. That matters to the
            // held press below: it fires as soon as `group()` is non-empty,
            // which over TCP meant after a chunk carrying most of the faders,
            // and here would mean after the FIRST one -- clamping the group
            // against a single fader's level and moving the others by the
            // wrong amount. So the press now waits for a settled batch rather
            // than for the first record that makes the group non-empty.
            link.messages.collect { record ->
                val msg = record.text
                if (!msg.startsWith("MIXER_FADER|")) return@collect
                MessageParser.parseMixerFader(msg)?.let { faders[it.key] = it }
                // Debounced, so a batch arriving one record at a time is
                // allowed to finish before a held press is measured against it.
                pendingUp?.let { up ->
                    if (group().isNotEmpty()) {
                        settleJob?.cancel()
                        settleJob = scope.launch {
                            delay(SETTLE_MS)
                            pendingUp?.let { held -> pendingUp = null; apply(held) }
                        }
                    }
                }
            }
        }
        scope.launch {
            // The mixer hands over a whole state at once, so a held press is
            // answered on the first one. No settling, unlike the batch above
            // that arrives a record at a time.
            //
            // On `reads`, not `state`: a reading that changed nothing is not
            // an event on a StateFlow, and "the mixer answered and nothing has
            // moved" is the usual answer a held press is waiting for.
            mixer.reads.collect {
                heldForMixer.take(nowMs())?.let { up ->
                    if (mixerGroup().isNotEmpty()) applyThroughMixer(up)
                }
            }
        }
        scope.launch { linkedFaders.collect { linked = it } }
        scope.launch { stepPercent.collect { step = it } }
    }

    private fun group() = linked.mapNotNull { faders[it] }

    private fun mixerGroup() =
        mixer.state.value?.faders.orEmpty().filter { it.key in linked }

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
            "link=${link.state.value}")
        if (linked.isEmpty()) return false
        // A mixer session means the mixer owns these faders, and it is asked
        // for them directly -- whether or not the visualizer is even running.
        if (mixer.present.value) return nudgeThroughMixer(up)
        // Asks the LINK, not the TCP socket. The socket reads Disconnected for
        // ever on a PC whose own server is off, which would have made the
        // volume keys dead on exactly the setup this feature is for.
        if (!link.canSend) return false

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
                link.send(CommandBuilder.mixerSet(f.channelId, f.faderId, pct))
                // Optimistic, as the whole mixer is: the PC's reply carries the
                // value asked for, not one read back, so holding it here is what
                // makes a run of presses accumulate instead of fighting itself.
                faders[f.key] = f.copy(volumePercent = pct)
            }
        }
    }

    private fun nudgeThroughMixer(up: Boolean): Boolean {
        // Still authenticating: nothing can be asked or told yet, so the key
        // goes to whatever would normally have had it.
        if (!mixer.askable) return false
        // No screen is keeping the state fresh on this path, so each press
        // asks for one. During a run of presses that keeps it fresh; before
        // the first, it is what the press below waits for.
        mixer.refresh()
        if (mixer.readWithin(FRESH_MS)) {
            // A current state with none of the linked faders in it has nothing
            // here to move -- a linked headset that is switched off, say.
            if (mixerGroup().isEmpty()) return false
            applyThroughMixer(up)
            return true
        }
        Log.d(TAG, "no recent levels from the mixer; asked, and holding the press")
        heldForMixer.hold(up, nowMs())
        scope.launch {
            delay(REASK_MS)
            if (!mixer.readWithin(FRESH_MS)) mixer.refresh()
        }
        return true
    }

    private fun applyThroughMixer(up: Boolean) {
        val group = mixerGroup()
        Log.d(TAG, "applying to ${group.size} fader(s) through the mixer")
        val next = nudgeLinked(group, step, up)
        group.forEach { f -> next[f.key]?.let { pct -> mixer.setVolume(f, pct) } }
    }

    /** Levels only, at most once a second. */
    private fun requestLevels() {
        if (queryJob?.isActive == true) return
        val wait = throttle.waitFor(System.currentTimeMillis())
        queryJob = scope.launch {
            if (wait > 0) delay(wait)
            throttle.record(System.currentTimeMillis())
            link.send(CommandBuilder.mixerState())
        }
    }
}
