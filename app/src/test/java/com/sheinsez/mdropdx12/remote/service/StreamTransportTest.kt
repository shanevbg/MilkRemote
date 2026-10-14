package com.sheinsez.mdropdx12.remote.service

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a watch, the lock screen or a headset button does to the stream.
 *
 * Stop stops it, Play starts it, Pause mutes it -- and nothing a controller
 * sends may make the stream louder or busier than the press asked for.
 */
class StreamTransportTest {

    private fun has(actions: Long, a: Long) = actions and a != 0L

    // ── state ───────────────────────────────────────────────────────────

    @Test
    fun `no audio is stopped, whatever the mute says`() {
        assertEquals(StreamPlayback.Stopped, StreamTransport.playback(audioOn = false, muted = true))
        assertEquals(StreamPlayback.Stopped, StreamTransport.playback(audioOn = false, muted = false))
    }

    @Test
    fun `audio muted is paused, audio not muted is playing`() {
        assertEquals(StreamPlayback.Paused, StreamTransport.playback(audioOn = true, muted = true))
        assertEquals(StreamPlayback.Playing, StreamTransport.playback(audioOn = true, muted = false))
    }

    // ── commands ────────────────────────────────────────────────────────

    @Test
    fun `play starts a stopped stream and unmutes a paused one`() {
        assertEquals(TransportAction.Start, StreamTransport.actionFor(TransportCommand.Play, StreamPlayback.Stopped))
        assertEquals(TransportAction.Unmute, StreamTransport.actionFor(TransportCommand.Play, StreamPlayback.Paused))
        assertEquals(TransportAction.Nothing, StreamTransport.actionFor(TransportCommand.Play, StreamPlayback.Playing))
    }

    @Test
    fun `pause mutes a playing stream`() {
        assertEquals(TransportAction.Mute, StreamTransport.actionFor(TransportCommand.Pause, StreamPlayback.Playing))
    }

    @Test
    fun `pause never starts a stopped stream, muted or otherwise`() {
        assertEquals(TransportAction.Nothing, StreamTransport.actionFor(TransportCommand.Pause, StreamPlayback.Stopped))
        assertEquals(TransportAction.Nothing, StreamTransport.actionFor(TransportCommand.Pause, StreamPlayback.Paused))
    }

    @Test
    fun `stop stops a playing or paused stream and leaves a stopped one alone`() {
        assertEquals(TransportAction.StopAudio, StreamTransport.actionFor(TransportCommand.Stop, StreamPlayback.Playing))
        assertEquals(TransportAction.StopAudio, StreamTransport.actionFor(TransportCommand.Stop, StreamPlayback.Paused))
        assertEquals(TransportAction.Nothing, StreamTransport.actionFor(TransportCommand.Stop, StreamPlayback.Stopped))
    }

    // ── what a controller is told ───────────────────────────────────────

    @Test
    fun `playing and paused are told as themselves`() {
        assertEquals(PlaybackState.STATE_PLAYING, StreamTransport.stateFor(StreamPlayback.Playing))
        assertEquals(PlaybackState.STATE_PAUSED, StreamTransport.stateFor(StreamPlayback.Paused))
    }

    /**
     * Measured on a Pixel Watch: a session that says STOPPED loses its
     * controls on the watch, so the Play that should restart the stream is
     * never on screen. A stopped stream is shown as paused instead.
     */
    @Test
    fun `a stopped stream is never told as STOPPED, which a watch hides`() {
        assertEquals(PlaybackState.STATE_PAUSED, StreamTransport.stateFor(StreamPlayback.Stopped))
    }

    /** And Play on it still STARTS the stream: the decision reads the real state. */
    @Test
    fun `Play on a stream shown as paused but really stopped starts it`() {
        assertEquals(TransportAction.Start, StreamTransport.actionFor(TransportCommand.Play, StreamPlayback.Stopped))
    }

    /**
     * The single button on a headset or a watch arrives as PLAY_PAUSE, and
     * the framework only turns it into onPlay/onPause when it is advertised.
     */
    @Test
    fun `the one-button toggle is offered in every state`() {
        for (p in StreamPlayback.values()) {
            assertTrue("$p", has(StreamTransport.actionsFor(p), PlaybackState.ACTION_PLAY_PAUSE))
        }
    }

    @Test
    fun `a playing stream offers pause and stop, not play`() {
        val a = StreamTransport.actionsFor(StreamPlayback.Playing)
        assertTrue(has(a, PlaybackState.ACTION_PAUSE))
        assertTrue(has(a, PlaybackState.ACTION_STOP))
        assertTrue(!has(a, PlaybackState.ACTION_PLAY))
    }

    @Test
    fun `a paused stream offers play and stop`() {
        val a = StreamTransport.actionsFor(StreamPlayback.Paused)
        assertTrue(has(a, PlaybackState.ACTION_PLAY))
        assertTrue(has(a, PlaybackState.ACTION_STOP))
        assertTrue(!has(a, PlaybackState.ACTION_PAUSE))
    }

    @Test
    fun `a stopped stream offers play and nothing that would do nothing`() {
        val a = StreamTransport.actionsFor(StreamPlayback.Stopped)
        assertTrue(has(a, PlaybackState.ACTION_PLAY))
        assertTrue(!has(a, PlaybackState.ACTION_STOP))
        assertTrue(!has(a, PlaybackState.ACTION_PAUSE))
    }

    /**
     * The custom Stop button follows ACTION_STOP exactly, so the system
     * player never draws a Stop that the session would refuse to do.
     */
    @Test
    fun `the Stop button is offered exactly when stopping is`() {
        for (p in StreamPlayback.values()) {
            assertEquals(
                "$p",
                has(StreamTransport.actionsFor(p), PlaybackState.ACTION_STOP),
                StreamTransport.offersStop(p),
            )
        }
    }

    // ── which session ───────────────────────────────────────────────────

    @Test
    fun `audio on holds the player alone, even with the media-device option`() {
        assertEquals(
            SessionPlan(player = true, pcVolume = false),
            StreamTransport.plan(audioOn = true, playedBefore = true, pcVolumeWanted = true, connected = true),
        )
    }

    /**
     * The case that broke on the phone: with "Volume keys from any screen"
     * on, a Stop handed the only session to PC volume, which offers no Play,
     * and the watch had nothing to start the stream with.
     */
    @Test
    fun `stopped with the media-device option keeps the player AND gives the keys their session`() {
        assertEquals(
            SessionPlan(player = true, pcVolume = true),
            StreamTransport.plan(audioOn = false, playedBefore = true, pcVolumeWanted = true, connected = true),
        )
    }

    @Test
    fun `stopped after playing stays offered, so the watch can start it again`() {
        assertEquals(
            SessionPlan(player = true, pcVolume = false),
            StreamTransport.plan(audioOn = false, playedBefore = true, pcVolumeWanted = false, connected = true),
        )
        // And with the link down too: Play is what retries it.
        assertEquals(
            SessionPlan(player = true, pcVolume = false),
            StreamTransport.plan(audioOn = false, playedBefore = true, pcVolumeWanted = false, connected = false),
        )
    }

    @Test
    fun `never played, the media-device option holds its session alone, as before`() {
        assertEquals(
            SessionPlan(player = false, pcVolume = true),
            StreamTransport.plan(audioOn = false, playedBefore = false, pcVolumeWanted = true, connected = true),
        )
    }

    @Test
    fun `never played and no media-device option holds nothing, as before`() {
        assertEquals(
            SessionPlan(player = false, pcVolume = false),
            StreamTransport.plan(audioOn = false, playedBefore = false, pcVolumeWanted = false, connected = true),
        )
    }

    @Test
    fun `the PC-volume session still needs the link`() {
        assertEquals(
            SessionPlan(player = false, pcVolume = false),
            StreamTransport.plan(audioOn = false, playedBefore = false, pcVolumeWanted = true, connected = false),
        )
    }

    // ── asking what is playing ──────────────────────────────────────────

    @Test
    fun `the first time the relay comes up, ask`() {
        assertTrue(StreamTransport.shouldAskState(trackKnown = false, msSinceLastAsk = null))
        assertTrue(StreamTransport.shouldAskState(trackKnown = true, msSinceLastAsk = null))
    }

    @Test
    fun `with nothing known, ask however recently it was asked`() {
        assertTrue(StreamTransport.shouldAskState(trackKnown = false, msSinceLastAsk = 1_000L))
    }

    /**
     * Seen against the fake mixer: a re-auth every six seconds, each one
     * asking STATE, and each answer putting back the track from before the
     * newest push. A flapping link must not do that.
     */
    @Test
    fun `a link that flaps does not re-ask while the track is known`() {
        assertTrue(!StreamTransport.shouldAskState(trackKnown = true, msSinceLastAsk = 6_000L))
    }

    @Test
    fun `after a long enough gap, ask again in case the song changed meanwhile`() {
        assertTrue(
            StreamTransport.shouldAskState(trackKnown = true, msSinceLastAsk = StreamTransport.STATE_REASK_MS),
        )
    }

    // ── what is playing ─────────────────────────────────────────────────

    @Test
    fun `a track record is read, trimmed`() {
        assertEquals(
            PcTrack("Daft Punk", "Veridis Quo", "Discovery"),
            StreamTransport.parseTrack("TRACK|artist=Daft Punk|title=Veridis Quo |album=Discovery"),
        )
    }

    @Test
    fun `anything else is not a track`() {
        assertNull(StreamTransport.parseTrack("PRESET=c:\\presets\\a.milk"))
    }

    @Test
    fun `a known track is shown as the PC gave it`() {
        assertEquals(
            NowPlaying("Veridis Quo", "Daft Punk", "Discovery"),
            StreamTransport.nowPlaying(PcTrack("Daft Punk", "Veridis Quo", "Discovery"), "SHANE-PC"),
        )
    }

    @Test
    fun `no track names the PC, so the watch still says where the sound is from`() {
        assertEquals(
            NowPlaying(StreamTransport.NO_TRACK_TITLE, "SHANE-PC", ""),
            StreamTransport.nowPlaying(null, "SHANE-PC"),
        )
    }

    /** MDropDX12 sends every field blank when nothing is playing on the PC. */
    @Test
    fun `a blank track is the same as no track`() {
        assertEquals(
            NowPlaying(StreamTransport.NO_TRACK_TITLE, "SHANE-PC", ""),
            StreamTransport.nowPlaying(PcTrack("", "", ""), "SHANE-PC"),
        )
    }

    @Test
    fun `a title with no artist borrows the PC's name for the second line`() {
        assertEquals(
            NowPlaying("Live set", "SHANE-PC", ""),
            StreamTransport.nowPlaying(PcTrack("", "Live set", ""), "SHANE-PC"),
        )
    }

    @Test
    fun `with no PC name either, the app's name is the source`() {
        assertEquals(
            NowPlaying(StreamTransport.NO_TRACK_TITLE, "MilkRemote", ""),
            StreamTransport.nowPlaying(null, "  "),
        )
    }
}
