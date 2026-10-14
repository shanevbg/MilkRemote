package com.sheinsez.mdropdx12.remote.network.vban

import com.sheinsez.mdropdx12.remote.data.model.MixerFader
import com.sheinsez.mdropdx12.remote.data.model.PendingWrites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The mixer's faders, read from the mixer.
 *
 * The Mixer screen and the volume keys used to ask MDropDX12 for these, and
 * mdx12 does not own them: it subscribes to mdxmixer and republishes what it
 * hears. Asking it meant asking the mixer to forward a question to the
 * visualizer about the mixer's own data -- and the answer could not come back,
 * because mdx12 packs it into pipe messages several times the size of a
 * datagram. So the screen was empty on exactly the setup the mixer session
 * exists for, and stayed empty with the visualizer closed for a second reason.
 *
 * This asks the mixer directly. `MDXM_STATE` is its own verb, formatted one
 * record per line for this transport, and needs no visualizer at all.
 *
 * Application-scoped, beside the session and the link, because the volume keys
 * need it with no screen in front.
 *
 * POLLED, because the mixer refuses a subscription over the network. Only
 * while something says it is watching -- see [watch].
 */
class MdxmMixer(
    private val session: VbanSession,
    private val scope: CoroutineScope,
    /** Monotonic, so a clock change cannot stretch a hold or stall a poll. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    companion object {
        /**
         * How often to ask while a screen is watching.
         *
         * A state is about a hundred records in sixteen datagrams, on the
         * link that is also carrying the audio. Once a second keeps a change
         * made at the PC from sitting unseen for long, and a write made here
         * does not wait for it -- it is drawn at once.
         */
        const val POLL_MS = 1_000L

        /** Two questions closer together than this would be answered identically. */
        private const val MIN_ASK_GAP_MS = 300L

        /** The mixer will answer a question now: this phone is authorized. */
        fun askable(s: VbanSessionState): Boolean = s == VbanSessionState.Streaming

        /**
         * The mixer is where faders are read from.
         *
         * Wider than [askable] on purpose. A session re-authenticates for a
         * second or two after a roam or a socket rebuild, and a source that
         * went away for that long would empty the screen and send it to ask
         * the visualizer instead -- over rows that were right a moment ago.
         */
        fun isSource(s: VbanSessionState): Boolean =
            s == VbanSessionState.Streaming ||
                s == VbanSessionState.Pinging ||
                s == VbanSessionState.AuthPending
    }

    // Read now rather than left false until the collector below first runs on
    // another thread: a screen built in that gap would start out asking the
    // visualizer, and draw that path's refusal for a moment.
    private val _present = MutableStateFlow(isSource(session.state.value))

    /** There is a mixer session, so this -- not the visualizer -- is the source. */
    val present: StateFlow<Boolean> = _present

    private val _state = MutableStateFlow<MdxmMixerState?>(null)

    /**
     * The last state the mixer sent, with anything just written laid over it.
     *
     * Null until it has answered once. Kept while the session re-authenticates
     * and dropped when the session ends: a reading belongs to the session that
     * took it.
     */
    val state: StateFlow<MdxmMixerState?> = _state

    /**
     * One tick per reading that arrives, whether or not it changed anything.
     *
     * [state] cannot say this: a reading identical to the last one is, to a
     * StateFlow, no event at all -- and "the mixer just answered, and nothing
     * has moved" is precisely what a held key press is waiting to hear.
     */
    private val _reads = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val reads: SharedFlow<Unit> = _reads

    val askable: Boolean get() = askable(session.state.value)

    // Written from the session's collector and from whichever thread a tap or
    // a key press arrives on, so everything below is behind one lock.
    private val lock = Any()
    private val pending = PendingWrites()
    private var read: MdxmMixerState? = null
    private var readAt: Long? = null
    private var lastAskAt: Long? = null
    private var pollJob: Job? = null

    /**
     * Has the mixer been heard from within [maxAgeMs]?
     *
     * For a caller about to STEP a level rather than set one. With no screen
     * watching, the last reading can be from an hour ago, and "up one step"
     * from a level that has since been changed at the PC is a jump.
     */
    fun readWithin(maxAgeMs: Long): Boolean = synchronized(lock) {
        readAt?.let { now() - it <= maxAgeMs } ?: false
    }

    init {
        scope.launch {
            var wasAskable = false
            session.state.collect { s ->
                val source = isSource(s)
                if (!source) forget()
                _present.value = source
                // Asked the moment it can be, rather than up to a second
                // later: a screen opened while the link was still coming up
                // has been showing a spinner since.
                val can = askable(s)
                if (can && !wasAskable && watching()) ask()
                wasAskable = can
            }
        }
        scope.launch {
            session.mixerState.collect { batch ->
                synchronized(lock) {
                    read = MdxmMixerRows.fromState(batch)
                    readAt = now()
                    publishLocked()
                }
                _reads.tryEmit(Unit)
            }
        }
    }

    /**
     * Keep the state fresh while a screen is showing it, and stop when it is
     * not. One watcher: the Mixer screen.
     */
    fun watch(on: Boolean) {
        synchronized(lock) {
            pollJob?.cancel()
            pollJob = if (!on) null else scope.launch {
                while (isActive) {
                    ask()
                    delay(POLL_MS)
                }
            }
        }
    }

    private fun watching() = synchronized(lock) { pollJob?.isActive == true }

    /** One question, now. For a caller that is not watching -- the volume keys. */
    fun refresh() = ask()

    private fun ask() {
        if (!askable) return
        synchronized(lock) {
            val t = now()
            val last = lastAskAt
            if (last != null && t - last < MIN_ASK_GAP_MS) return
            lastAskAt = t
        }
        session.queryMixerState()
    }

    // Writes are drawn at once and held briefly over the next reading. See
    // PendingWrites for what a poll answered a moment too late does otherwise.

    fun setVolume(f: MixerFader, percent: Int) {
        val p = percent.coerceIn(0, 100)
        synchronized(lock) {
            pending.volume(f.key, p, now())
            publishLocked()
        }
        session.sendMixer(MdxmMixerRows.volumeCommand(f, p))
    }

    fun setMute(f: MixerFader, muted: Boolean) {
        synchronized(lock) {
            pending.mute(f.key, muted, now())
            publishLocked()
        }
        session.sendMixer(MdxmMixerRows.muteCommand(f, muted))
    }

    private fun publishLocked() {
        val r = read ?: return
        _state.value = r.copy(faders = pending.over(r.faders, now()))
    }

    private fun forget() = synchronized(lock) {
        read = null
        readAt = null
        lastAskAt = null
        pending.clear()
        _state.value = null
    }
}
