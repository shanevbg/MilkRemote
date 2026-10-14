package com.sheinsez.mdropdx12.remote.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.sheinsez.mdropdx12.remote.network.vban.JitterPolicy
import com.sheinsez.mdropdx12.remote.network.vban.LatencyProfile
import com.sheinsez.mdropdx12.remote.network.vban.PlayoutAction
import com.sheinsez.mdropdx12.remote.network.vban.VbanCodec
import android.media.AudioFormat as AndroidAudioFormat

/**
 * AudioTrack, and nothing clever.
 *
 * No NDK and no Oboe: the repo's first native code is not justified against a
 * ~200 ms network reality, where tens of microseconds of callback jitter are
 * noise. Every decision that could be gotten wrong lives in [JitterPolicy],
 * which is Android-free and therefore testable; this class only drives it.
 *
 * The one decision that has to live here is [formatChanged], and it is a
 * companion function precisely so it can be tested — `android.media` throws
 * `Stub!` in the JVM suite, so anything reachable only through a real
 * AudioTrack is not covered by anything.
 */
class VbanAudioPlayer(
    private val context: Context,
    private val profile: LatencyProfile,
) {

    private var track: AudioTrack? = null
    private var current: VbanCodec.AudioFormat? = null
    private var focusRequest: AudioFocusRequest? = null
    private val policy = JitterPolicy(profile)

    /**
     * Guards the track's LIFE: building it, releasing it, and every call on
     * it except the blocking write.
     *
     * Three threads reach this player -- the playout loop writing, the ping
     * loop re-priming, and the main thread stopping -- and nothing kept them
     * apart. Stop released the track while the playout loop was between its
     * write and its depth reading, and getPlaybackHeadPosition on a released
     * track does not return an error, it THROWS: "Unable to retrieve
     * AudioTrack pointer for getPosition()", uncaught on a worker, and the
     * app died. Seen on the phone the first time Stop came from a watch; the
     * stream card's Stop had always been exposed to it.
     *
     * The write itself stays OUTSIDE the lock. It blocks on the sink by
     * design, and holding the lock across it would make Stop, on the main
     * thread, wait on the headphones.
     */
    private val lock = Any()

    /**
     * Stopped for good. A player is never restarted -- the session builds a
     * new one -- so a packet already on its way when Stop ran must not find
     * `current` cleared, read that as a format change, and build a fresh
     * track that nothing would ever release.
     */
    private var released = false

    /**
     * The route the user picked, or [PhoneOutput.AUTOMATIC_ID] to follow the
     * system. Applied to the track when it is built and whenever this
     * changes, so switching output mid-stream does not need a reconnect.
     */
    var preferredOutputId: Int = PhoneOutput.AUTOMATIC_ID
        set(value) {
            field = value
            applyPreferredOutput()
        }

    /**
     * Silent, and still playing: what Pause means for a live feed.
     *
     * The track keeps running and the stream keeps arriving, so unmuting is
     * instant and in sync rather than a re-buffer. Kept here rather than
     * applied once, because a format change builds a new track and the new
     * one has to come up as quiet as the old one was.
     */
    var muted: Boolean = false
        set(value) {
            field = value
            applyVolume()
        }

    /**
     * Times the AudioTrack ran dry, as the platform counts it.
     *
     * Read from `getUnderrunCount()` rather than inferred. This used to be
     * incremented when `write` returned a negative value, which is an ERROR
     * CODE -- `ERROR_INVALID_OPERATION`, `ERROR_DEAD_OBJECT` -- and not an
     * underrun at all. So the one number that would have named a crackle as
     * "the buffer is starving" sat at zero through every starving buffer, and
     * a genuine write failure was filed under the wrong cause.
     *
     * It is the figure that separates the three reasons audio breaks up: the
     * buffer starving (here), packets missing on the network
     * ([inserts]/[restarts]), and the PC sending well-formed silence because
     * it has no clock, which shows up on the far end's own starved counter
     * instead.
     */
    val underruns: Int get() = runCatching { track?.underrunCount ?: 0 }.getOrDefault(0)

    /** `write` refused. A dead track or a bad call, never a starving one. */
    var writeErrors = 0; private set

    var bufferedMs = 0; private set
    var outputLatencyMs = 0; private set

    /**
     * What is ACTUALLY queued, as against [bufferedMs], which is the track's
     * allocated size. The two were conflated, so latency sitting at the
     * ceiling was indistinguishable from latency sitting at the floor.
     */
    var queuedMs = 0; private set

    /**
     * Frames handed to the track since it was built. Reset wherever the
     * track's own head position is -- a flush resets that, and a counter that
     * did not follow would make the depth read four billion.
     */
    private var framesWritten = 0L
    val inserts get() = policy.inserts
    val restarts get() = policy.restarts
    val drops get() = policy.drops

    /** Times the track was re-primed after playing with no lead. See [reprime]. */
    var reprimes = 0; private set

    /**
     * Throw away what is queued and start the stream again with a cushion.
     *
     * Exactly what stopping and starting playback by hand does, minus the
     * session teardown -- and that hand-press is the only thing that has ever
     * cleared this fault, which is why the cure is modelled on it rather than
     * invented.
     *
     * The policy is reset with the track: its idea of the last packet belongs
     * to audio that has just been discarded, and leaving it would make the
     * first packet after the flush look like a gap and insert silence into
     * the very cushion this is rebuilding.
     *
     * Safe when nothing is playing: with no track there is nothing to flush
     * and the counter is not moved, so a caller need not check first.
     */
    fun reprime() {
        synchronized(lock) {
            if (released) return
            val t = track ?: return
            runCatching {
                t.pause()
                t.flush()
                t.play()
            }
            framesWritten = 0L
            queuedMs = 0
            policy.reset()
            reprimes++
        }
    }

    companion object {
        /**
         * Everything the track is configured FROM, and nothing that varies
         * packet to packet.
         *
         * `samplesPerPacket` is deliberately excluded: the sender may change
         * packet size without the stream changing at all, and tearing the
         * track down for that would stutter for no reason. A sample-rate
         * change, by contrast, MUST reconfigure — 44.1 kHz played through a
         * 48 kHz track is wrong pitch, which sounds like a bad recording
         * rather than a bug and so never gets reported as one.
         */
        fun formatChanged(
            a: VbanCodec.AudioFormat?,
            b: VbanCodec.AudioFormat,
        ): Boolean = a == null ||
            a.sampleRate != b.sampleRate ||
            a.channels != b.channels ||
            a.dataType != b.dataType

        fun bytesPerSample(dataType: Int): Int = when (dataType) {
            VbanCodec.DATATYPE_FLOAT32 -> 4
            else -> 2
        }
    }

    fun submit(
        format: VbanCodec.AudioFormat,
        pcm: ByteArray,
        offset: Int,
        length: Int,
        nuFrame: Long,
    ) {
        val (t, silenceBytes) = synchronized(lock) {
            if (released) return
            if (formatChanged(current, format)) {
                reconfigure(format)
                policy.reset()
            }
            val t = track ?: return
            val d = policy.onPacket(nuFrame, format.samplesPerPacket, format.sampleRate)
            var silence = 0
            when (d.action) {
                PlayoutAction.Drop -> return
                PlayoutAction.Restart -> {
                    runCatching { t.pause(); t.flush(); t.play() }
                    // flush() zeroes playbackHeadPosition, so the counter it is
                    // subtracted from has to go with it. Without this the next
                    // depth reading is however many frames were written before
                    // the flush -- enormous, and reported as seconds of queued
                    // audio that is not there.
                    framesWritten = 0L
                }
                PlayoutAction.InsertSilence -> {
                    val bytes = d.silenceSamples * format.channels * bytesPerSample(format.dataType)
                    if (bytes in 1..(1 shl 20)) silence = bytes
                }
                PlayoutAction.Play -> Unit
            }
            t to silence
        }
        // Unlocked, because these block on the sink. A Stop landing here
        // releases the track under them, and a write on a released track
        // throws rather than failing -- so both are caught, and what follows
        // checks the track is still this one before touching it again.
        if (silenceBytes > 0) runCatching { t.write(ByteArray(silenceBytes), 0, silenceBytes) }
        val written = runCatching { t.write(pcm, offset, length) }.getOrDefault(0)
        synchronized(lock) {
            if (released || track !== t) return
            if (written < 0) writeErrors++
            if (written > 0) framesWritten += written / frameBytes(format)
            updateTimings(format)
            measureDepth(t, format)
        }
    }

    /** Bytes per frame: all channels, one sample each. */
    private fun frameBytes(format: VbanCodec.AudioFormat): Int =
        (format.channels * bytesPerSample(format.dataType)).coerceAtLeast(1)

    /**
     * How much audio is ACTUALLY queued in the track.
     *
     * NOT THE BUFFER SIZE. Those are different numbers and conflating them is
     * why latency could sit at the ceiling unnoticed: `bufferedMs` reports
     * capacity, so a track configured for 40 ms reads 40 whether it is
     * holding 5 ms or 40. The contract on LagTerms.bufferDepthMs has always
     * said "what the player actually has buffered now"; until this, it did
     * not.
     *
     * playbackHeadPosition is a 32-bit frame counter and WRAPS -- about 25
     * hours at 48 kHz, which a machine left streaming will reach. Masked the
     * same way JitterPolicy masks nuFrame, and for the same reason: a naive
     * subtraction reads the wrap as a jump of four billion.
     *
     * NO VARISPEED TRIM HERE, AND THAT IS SETTLED RATHER THAN MISSING. One
     * was written, tested and tried: it cannot work on this path. Requesting
     * PERFORMANCE_MODE_LOW_LATENCY earns AUDIO_OUTPUT_FLAG_FAST, and a FAST
     * AudioTrack refuses setPlaybackParams -- the fast mixer path and the
     * time-stretcher are mutually exclusive. Since the fast path is worth
     * ~178 ms and the trim was worth nothing here, the trim went.
     *
     * The seconds it was meant to recover are not on this device anyway. The
     * phone holds 40 ms of buffer and 60 ms of output latency; the PC's send
     * ring is what grows to two seconds on a weak link and never comes back
     * down, and that is where the drain belongs (mdxmixer, forgejo#33). It
     * already has the policy -- dsp/ring_speed.h -- applied to its capture
     * rings and not to that one.
     */
    private fun measureDepth(t: AudioTrack, format: VbanCodec.AudioFormat) {
        if (format.sampleRate <= 0) return
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val depth = (framesWritten - head) and 0xFFFFFFFFL
        // Past half the counter means framesWritten is BEHIND the head, which
        // is the disagreement a flush leaves for an instant. Signed, and
        // clamped at zero: a momentary negative is a reporting artefact, not
        // a negative amount of audio.
        val signed = if (depth > 0x7FFFFFFFL) depth - 0x100000000L else depth
        queuedMs = (signed.coerceAtLeast(0L) * 1000 / format.sampleRate).toInt()
    }

    private fun reconfigure(format: VbanCodec.AudioFormat) {
        releaseTrack()
        requestFocus()
        val encoding = when (format.dataType) {
            VbanCodec.DATATYPE_FLOAT32 -> AndroidAudioFormat.ENCODING_PCM_FLOAT
            else -> AndroidAudioFormat.ENCODING_PCM_16BIT
        }
        val channelMask =
            if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
            else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBytes = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        // The profile's depth is a TARGET, not a floor: the device's own
        // minimum wins when it is larger, because a buffer under it will not
        // play at all.
        val targetBytes = format.sampleRate * profile.bufferMs / 1000 *
            format.channels * bytesPerSample(format.dataType)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, targetBytes))
                .setTransferMode(AudioTrack.MODE_STREAM)
                // ASK FOR THE FAST MIXER PATH, which is where the latency
                // actually is. MEASURED on a wired Pixel 10 Pro XL: of 360 ms
                // end to end, the PC contributed ~80, our own buffer 40, the
                // network ~2 -- and ~238 ms was outputLatencyMs, the sink's
                // own, below anything this app buffers. Three times
                // everything else combined.
                //
                // USAGE_MEDIA + CONTENT_TYPE_MUSIC with no performance mode
                // selects the deep-buffer output, which is built for power
                // efficiency and is the right default for playing a song. It
                // is the wrong one for a live monitor feed, where the whole
                // point is that the sound tracks what the PC is doing now.
                //
                // A REQUEST, NOT A GUARANTEE. The platform may decline it,
                // and it will decline it if the buffer is too large -- which
                // is why this is paired with the LowLatency profile and is
                // pointless beside a 150 ms one. Declining costs nothing: the
                // track is built either way and outputLatencyMs reports what
                // was actually granted, so the log says whether it took.
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrNull() ?: return
        runCatching { t.play() }
        track = t
        current = format
        applyPreferredOutput()
        applyVolume()
    }

    /** The track's own gain, not the route's: the system volume is the user's to keep. */
    private fun applyVolume() {
        synchronized(lock) {
            val t = track ?: return
            runCatching { t.setVolume(if (muted) 0f else 1f) }
        }
    }

    /**
     * Point the track at the chosen route. Null means "follow the system",
     * which is also the only setting that survives a headset being unplugged
     * mid-stream -- a pinned device that goes away stops the audio, and the
     * platform will not re-route it for us.
     */
    private fun applyPreferredOutput() {
        synchronized(lock) {
            val t = track ?: return
            runCatching { t.preferredDevice = PhoneAudioOutputs.deviceFor(context, preferredOutputId) }
        }
    }

    /**
     * Take audio focus while streaming, so phone media pauses the way a user
     * expects of something that is playing music, and give it back on stop.
     */
    private fun requestFocus() {
        if (focusRequest != null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .build()
        runCatching { am.requestAudioFocus(req) }
        focusRequest = req
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching { am?.abandonAudioFocusRequest(req) }
        focusRequest = null
    }

    private fun updateTimings(format: VbanCodec.AudioFormat) {
        val t = track ?: return
        bufferedMs = runCatching {
            t.bufferSizeInFrames * 1000 / format.sampleRate
        }.getOrDefault(profile.bufferMs)
        outputLatencyMs = runCatching {
            val ts = AudioTimestamp()
            if (t.getTimestamp(ts)) {
                val played = t.playbackHeadPosition.toLong()
                (((played - ts.framePosition) * 1000) / format.sampleRate).toInt().coerceAtLeast(0)
            } else {
                0
            }
        }.getOrDefault(0)
    }

    private fun releaseTrack() {
        track?.let { t -> runCatching { t.pause(); t.flush(); t.release() } }
        track = null
        current = null
        // A new track starts its head at zero, so the counter and the trim
        // start again with it. Carrying either across would steer the new
        // track from the old one's state.
        framesWritten = 0L
        queuedMs = 0
    }

    fun stop() {
        synchronized(lock) {
            released = true
            releaseTrack()
            abandonFocus()
            policy.reset()
        }
    }
}
