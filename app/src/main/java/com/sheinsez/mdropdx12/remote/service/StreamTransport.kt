package com.sheinsez.mdropdx12.remote.service

import android.media.session.PlaybackState
import com.sheinsez.mdropdx12.remote.network.MessageParser

/**
 * What the stream is doing, in the three states a media controller can draw.
 *
 * PAUSED IS MUTED. A live monitor feed has no place to hold -- there is no
 * position to resume from, only now -- so a pause that stopped the stream
 * would be a slower Stop that also had to re-buffer. Muting keeps the audio
 * flowing and in sync, and Play brings it back at once.
 */
enum class StreamPlayback { Stopped, Playing, Paused }

/** A media controller's request: a watch, the lock screen, a headset button. */
enum class TransportCommand { Play, Pause, Stop }

/** What the stream has to do to honour a [TransportCommand]. */
enum class TransportAction { Start, Mute, Unmute, StopAudio, Nothing }

/**
 * Which media sessions the background service holds. Both, at times.
 *
 * TWO, BECAUSE ONE SESSION CANNOT TELL BOTH TRUTHS. Android sends the volume
 * keys only to a session that says it is PLAYING, which is why the
 * media-device option has always claimed to play while nothing did. A
 * stopped stream's player has to say STOPPED and offer Play, or a watch
 * draws a Pause button over silence. With one session the stopped stream
 * lost its player to the PC-volume claim, so Play could never start it
 * again -- exactly the press a watch exists for.
 */
data class SessionPlan(
    /** The stream's player: transport, what is playing, and its volume. */
    val player: Boolean,
    /**
     * The media-device option's session, with nothing playing here: it
     * claims to play so the keys reach the linked PC faders, and offers no
     * transport. Carries no notification, so no watch draws it.
     */
    val pcVolume: Boolean,
)

/** The visualizer's `TRACK|` record, as it arrived. Any field may be blank. */
data class PcTrack(val artist: String, val title: String, val album: String)

/** Title, artist and album as a media controller shows them. */
data class NowPlaying(val title: String, val artist: String, val album: String)

/**
 * Every decision the media session makes, with nothing Android in it but
 * constants -- `android.media` throws `Stub!` in the JVM suite, so whatever
 * is decided inside the service itself is decided untested.
 */
object StreamTransport {

    /** Shown when the PC has said nothing about what is playing. */
    const val NO_TRACK_TITLE = "PC audio"

    /**
     * Stop, as a custom action.
     *
     * Advertising ACTION_STOP is not enough to get a Stop button anywhere a
     * person can press it: Android 13's media player -- the shade and the
     * lock screen -- draws play/pause, previous, next and CUSTOM actions,
     * and nothing else. Measured on the API 36 emulator: a session offering
     * ACTION_STOP showed Pause and only Pause.
     */
    const val CUSTOM_STOP = "com.sheinsez.mdropdx12.remote.STOP"

    /** Whether Stop is on offer at all. Same rule as [actionsFor]. */
    fun offersStop(p: StreamPlayback): Boolean = p != StreamPlayback.Stopped

    fun playback(audioOn: Boolean, muted: Boolean): StreamPlayback = when {
        !audioOn -> StreamPlayback.Stopped
        muted -> StreamPlayback.Paused
        else -> StreamPlayback.Playing
    }

    fun actionFor(command: TransportCommand, now: StreamPlayback): TransportAction = when (command) {
        TransportCommand.Play -> when (now) {
            StreamPlayback.Stopped -> TransportAction.Start
            StreamPlayback.Paused -> TransportAction.Unmute
            StreamPlayback.Playing -> TransportAction.Nothing
        }
        // Only a playing stream can be paused. Pausing a stopped one must
        // not start it muted -- that would put the stream on the network for
        // a press that asked for less, not more.
        TransportCommand.Pause -> when (now) {
            StreamPlayback.Playing -> TransportAction.Mute
            else -> TransportAction.Nothing
        }
        TransportCommand.Stop -> when (now) {
            StreamPlayback.Stopped -> TransportAction.Nothing
            else -> TransportAction.StopAudio
        }
    }

    /**
     * The state a controller is TOLD, which for a stopped stream is a lie.
     *
     * STOPPED makes a Pixel Watch take its media controls away. Measured: a
     * Stop pressed on the watch reached the phone, and the watch's player
     * vanished with it -- "now it's all gone" -- so the Play that was meant to
     * start the stream again was never on screen to be pressed. Wear OS reads
     * STOPPED as "nothing playing here"; PAUSED with Play is what every
     * controller keeps.
     *
     * So a stopped stream is SHOWN as paused, and stays a real stop inside:
     * Play is decided from [StreamPlayback], not from this, and starts the
     * stream rather than unmuting one that is not there. What still tells
     * the two apart is the actions -- a stopped stream offers no Stop.
     */
    fun stateFor(p: StreamPlayback): Int = when (p) {
        StreamPlayback.Playing -> PlaybackState.STATE_PLAYING
        StreamPlayback.Paused, StreamPlayback.Stopped -> PlaybackState.STATE_PAUSED
    }

    /**
     * What a controller may offer, which is also what it draws.
     *
     * PLAY_PAUSE in every state, because a headset's one button and a watch's
     * one button both arrive as it, and the framework turns it into onPlay or
     * onPause only when it is advertised. No Stop on a stopped stream: a
     * button that does nothing is worse than no button.
     */
    fun actionsFor(p: StreamPlayback): Long {
        val base = when (p) {
            StreamPlayback.Playing -> PlaybackState.ACTION_PAUSE
            StreamPlayback.Paused, StreamPlayback.Stopped -> PlaybackState.ACTION_PLAY
        } or PlaybackState.ACTION_PLAY_PAUSE
        return if (offersStop(p)) base or PlaybackState.ACTION_STOP else base
    }

    /**
     * Which sessions to hold.
     *
     * The player, while the stream plays or is paused, and after it has
     * played at all -- stopped, it stays offered so the watch that stopped
     * it can start it again. The PC-volume session only while nothing plays
     * here and the link is up, exactly as before the player existed; while
     * the stream plays, the player carries the keys itself.
     */
    fun plan(
        audioOn: Boolean,
        playedBefore: Boolean,
        pcVolumeWanted: Boolean,
        connected: Boolean,
    ) = SessionPlan(
        player = audioOn || playedBefore,
        pcVolume = !audioOn && pcVolumeWanted && connected,
    )

    /**
     * The least time between two `STATE` asks made only to learn the track.
     *
     * The ask is made when the relay comes up, and on a link that flaps that
     * is every few seconds -- measured against the fake mixer, a re-auth
     * every six. Each ask makes mdx12 broadcast its whole state to every
     * client it has, and each answer also put the track back to what it was
     * at the ask, over any newer push that had arrived in between.
     */
    const val STATE_REASK_MS = 60_000L

    /**
     * Ask `STATE` now that the relay is up?
     *
     * Always when nothing is known, since a title is the whole point.
     * Otherwise only if the last ask is old enough that the song has likely
     * changed while the link was down; between asks, the pushes keep it
     * current.
     */
    fun shouldAskState(trackKnown: Boolean, msSinceLastAsk: Long?): Boolean =
        !trackKnown || msSinceLastAsk == null || msSinceLastAsk >= STATE_REASK_MS

    /** The visualizer's `TRACK|artist=..|title=..|album=..`, or null for any other record. */
    fun parseTrack(record: String): PcTrack? =
        MessageParser.parseTrack(record)?.let { (artist, title, album) ->
            PcTrack(artist.trim(), title.trim(), album.trim())
        }

    /**
     * What the controller shows.
     *
     * The PC's own name stands in for an artist whenever the PC has not said
     * who is playing, so the watch always says WHERE the sound comes from
     * even when it cannot say what it is: MDropDX12 closed, or nothing
     * playing on the PC, leaves a track with every field blank.
     */
    fun nowPlaying(track: PcTrack?, pcName: String): NowPlaying {
        val source = pcName.trim().ifBlank { "MilkRemote" }
        val known = track?.takeIf { it.title.isNotBlank() }
            ?: return NowPlaying(NO_TRACK_TITLE, source, "")
        return NowPlaying(known.title, known.artist.ifBlank { source }, known.album)
    }
}
