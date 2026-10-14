package com.sheinsez.mdropdx12.remote.network.vban

import com.sheinsez.mdropdx12.remote.data.model.displayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mixer state, as the rows the Mixer screen already draws.
 *
 * The keys are the point. MDropDX12 turns the same records into
 * `<channel>|monitoring`, `<channel>|streaming` and `endpoint:<id>|main`, and
 * this phone has stored volume-key links under those keys -- so reading from
 * the mixer has to arrive at exactly them, or every link is silently orphaned.
 */
class MdxmMixerRowsTest {

    private val buds = "{0.0.0.00000000}.{11111111-2222-3333-4444-555555555555}"
    private val speakers = "{0.0.0.00000000}.{66666666-7777-8888-9999-000000000000}"

    private fun chan(id: String, name: String, pvol: String = "0.5", pmute: Int = 0,
                     svol: String = "1", smute: Int = 0, health: String = "ok") =
        "MDXM_CHAN|id=$id|name=$name|health=$health|pvol=$pvol|pmute=$pmute" +
            "|svol=$svol|smute=$smute|eq=0|peak=0|idle=0"

    private fun dev(id: String, name: String, alias: String = name, flow: String = "render",
                    vol: String = "0.42", mute: Int = 0, active: Int = 1, battery: Int = -1,
                    hidden: Int = 0) =
        "MDXM_DEVLVL|id=$id|name=$name|alias=$alias|flow=$flow|default=0|vol=$vol|mute=$mute" +
            "|active=$active|battery=$battery|seen=2026-10-11 16:07|handsfree=0|hidden=$hidden" +
            "|pinned=0|container=|bt=|peak=-1"

    // ── channels ─────────────────────────────────────────────────────────

    @Test
    fun `a channel is two faders under the keys the visualizer uses`() {
        val c = MdxmCommands.parseChannel(chan("sonar:aux", "Aux", pvol = "0.13", svol = "1"))!!
        val (personal, streaming) = MdxmMixerRows.channelFaders(c)
        assertEquals("sonar:aux|monitoring", personal.key)
        assertEquals("sonar:aux|streaming", streaming.key)
        assertEquals("Personal", personal.label)
        assertEquals("Streaming", streaming.label)
        assertEquals("Aux", personal.channelName)
        assertEquals(13, personal.volumePercent)
        assertEquals(100, streaming.volumePercent)
    }

    /**
     * 0.29f * 100 is 28.999998. Truncating draws a fader the PC shows at 29
     * as 28, and the next +1 then sends 29 -- a press that visibly does
     * nothing.
     */
    @Test
    fun `a level is rounded, not truncated`() {
        val c = MdxmCommands.parseChannel(chan("sonar:game", "Game", pvol = "0.29"))!!
        assertEquals(29, MdxmMixerRows.channelFaders(c)[0].volumePercent)
    }

    @Test
    fun `each mix keeps its own mute`() {
        val c = MdxmCommands.parseChannel(chan("sonar:chat", "Chat", pmute = 1, smute = 0))!!
        val (personal, streaming) = MdxmMixerRows.channelFaders(c)
        assertTrue(personal.muted)
        assertFalse(streaming.muted)
    }

    /**
     * The mixer REFUSES this one: Sonar's master mute rewrites every channel's
     * mute and restores a stale copy on the way back. Said on the row so the
     * control is absent rather than offered and failing.
     */
    @Test
    fun `the Sonar master cannot be muted`() {
        val master = MdxmCommands.parseChannel(chan("sonar:masters", "Master"))!!
        assertTrue(MdxmMixerRows.channelFaders(master).none { it.canMute })
        val aux = MdxmCommands.parseChannel(chan("sonar:aux", "Aux"))!!
        assertTrue(MdxmMixerRows.channelFaders(aux).all { it.canMute })
    }

    // ── devices ──────────────────────────────────────────────────────────

    @Test
    fun `a device is one fader under the endpoint key`() {
        val d = MdxmCommands.parseDevice(
            dev(buds, "Headphones (Test Buds)", alias = "Buds", vol = "0.42", mute = 1, battery = 80),
        )!!
        val f = MdxmMixerRows.deviceFader(d)!!
        assertEquals("endpoint:$buds|main", f.key)
        assertEquals("Buds", f.channelName)
        assertEquals("Headphones (Test Buds)", f.windowsName)
        assertEquals(42, f.volumePercent)
        assertTrue(f.muted)
        assertEquals(80, f.batteryPercent)
    }

    /** The field's own rule: present only when a name was chosen. */
    @Test
    fun `a device nobody renamed has no second name`() {
        val d = MdxmCommands.parseDevice(dev(speakers, "Speakers (Desk)"))!!
        val f = MdxmMixerRows.deviceFader(d)!!
        assertEquals("Speakers (Desk)", f.channelName)
        assertEquals("", f.windowsName)
        assertNull(f.batteryPercent)
    }

    /** A headset in a drawer is paired, not usable: no level to move. */
    @Test
    fun `a device that is not connected is not a fader`() {
        assertNull(MdxmMixerRows.deviceFader(MdxmCommands.parseDevice(dev(buds, "X", active = 0))!!))
    }

    /** Outputs only, which is what this screen has always listed. */
    @Test
    fun `a capture device is not a fader`() {
        assertNull(MdxmMixerRows.deviceFader(MdxmCommands.parseDevice(dev(buds, "Mic", flow = "capture"))!!))
    }

    @Test
    fun `a device the user filed away arrives marked hidden`() {
        val f = MdxmMixerRows.deviceFader(MdxmCommands.parseDevice(dev(buds, "X", hidden = 1))!!)!!
        assertTrue(f.hidden)
        assertFalse(f.virtual)
    }

    /**
     * mdxmixer's own rule, copied from `IsProviderOwnedEndpoint`: Sonar's
     * endpoints sit at 100 and ignore a write, right beside the Sonar channel
     * that does not. The mixer hides them by this name test and sends no flag
     * for it, so the test is repeated here until it does.
     */
    @Test
    fun `Sonar's own endpoints are marked as another program's`() {
        val f = MdxmMixerRows.deviceFader(
            MdxmCommands.parseDevice(dev(buds, "SteelSeries Sonar - Aux (SteelSeries Sonar Virtual Audio Device)"))!!,
        )!!
        assertTrue(f.virtual)
        val real = MdxmMixerRows.deviceFader(MdxmCommands.parseDevice(dev(speakers, "Speakers (Desk)"))!!)!!
        assertFalse(real.virtual)
    }

    // ── a whole state ────────────────────────────────────────────────────

    private val state = listOf(
        chan("sonar:game", "Game"),
        chan("sonar:aux", "Aux"),
        "MDXM_ROUTE|id=personal|device=$buds",
        "MDXM_DEV|id=$buds|name=Headphones (Test Buds)|flow=render|active=1",
        "MDXM_DEV|id=$speakers|name=Speakers (Desk)|flow=render|active=0",
        dev(buds, "Headphones (Test Buds)", alias = "Buds", battery = 80),
        dev(speakers, "Speakers (Desk)", active = 0),
    )

    @Test
    fun `rows come in the order the mixer sent them, channels then devices`() {
        val s = MdxmMixerRows.fromState(state)
        assertEquals(
            listOf(
                "sonar:game|monitoring", "sonar:game|streaming",
                "sonar:aux|monitoring", "sonar:aux|streaming",
                "endpoint:$buds|main",
            ),
            s.faders.map { it.key },
        )
    }

    /** The device list keeps what the fader list drops, with when it was last here. */
    @Test
    fun `the device list keeps the ones that are not connected`() {
        val s = MdxmMixerRows.fromState(state)
        assertEquals(listOf(buds, speakers), s.devices.map { it.id })
        val desk = s.devices[1]
        assertFalse(desk.active)
        assertEquals("render", desk.flow)
        assertEquals("2026-10-11 16:07", desk.lastSeen)
        assertEquals("Buds", s.devices[0].name)
        assertEquals(80, s.devices[0].batteryPercent)
    }

    /**
     * Every listing shares MDXM_BEGIN and MDXM_END, so a batch has to be
     * recognised by what is in it: a peer table must never be read as a mixer
     * with no channels.
     */
    @Test
    fun `only a state is a state`() {
        assertTrue(MdxmMixerRows.isState(state))
        assertTrue(MdxmMixerRows.isState(listOf(chan("sonar:aux", "Aux"))))
        assertFalse(MdxmMixerRows.isState(listOf("MDXM_VBANPEER|addr=192.168.0.105|device=Phone")))
        assertFalse(MdxmMixerRows.isState(listOf("MDXM_FWDTARGET|pid=1|exe=x.exe|target=1|path=C:\\x")))
        assertFalse(MdxmMixerRows.isState(emptyList()))
    }

    @Test
    fun `labels follow the rule the screen already has`() {
        val labels = displayLabels(MdxmMixerRows.fromState(state).faders)
        assertEquals("Aux \u2014 Personal", labels["sonar:aux|monitoring"])
        assertEquals("Aux \u2014 Streaming", labels["sonar:aux|streaming"])
        assertEquals("Buds", labels["endpoint:$buds|main"])
    }

    // ── what is drawn ────────────────────────────────────────────────────

    private val mixed = MdxmMixerRows.fromState(
        listOf(
            chan("sonar:aux", "Aux"),
            dev(buds, "Buds", hidden = 1),
            dev(speakers, "SteelSeries Sonar - Aux (SteelSeries Sonar Virtual Audio Device)"),
            dev("{0.0.0.00000000}.{desk}", "Speakers (Desk)"),
        ),
    ).faders

    @Test
    fun `hidden rows and another program's rows are not drawn`() {
        assertEquals(
            listOf("sonar:aux|monitoring", "sonar:aux|streaming", "endpoint:{0.0.0.00000000}.{desk}|main"),
            MdxmMixerRows.forDisplay(mixed, showHidden = false).map { it.key },
        )
    }

    /** At the end, as on the visualizer path: the PC draws them nowhere. */
    @Test
    fun `asked for, they come last`() {
        assertEquals(
            listOf(
                "sonar:aux|monitoring", "sonar:aux|streaming", "endpoint:{0.0.0.00000000}.{desk}|main",
                "endpoint:$buds|main", "endpoint:$speakers|main",
            ),
            MdxmMixerRows.forDisplay(mixed, showHidden = true).map { it.key },
        )
    }

    // ── writes ───────────────────────────────────────────────────────────

    private val faders = MdxmMixerRows.fromState(state).faders
    private fun fader(key: String) = faders.first { it.key == key }

    /** The screen says "monitoring"; the mixer's word for that mix is "personal". */
    @Test
    fun `a channel fader is written as its mix`() {
        assertEquals(
            "MDXM_SET|sonar:aux|personal|0.4000",
            MdxmMixerRows.volumeCommand(fader("sonar:aux|monitoring"), 40),
        )
        assertEquals(
            "MDXM_SET|sonar:aux|streaming|0.4000",
            MdxmMixerRows.volumeCommand(fader("sonar:aux|streaming"), 40),
        )
        assertEquals(
            "MDXM_MUTE|sonar:aux|personal|1",
            MdxmMixerRows.muteCommand(fader("sonar:aux|monitoring"), true),
        )
    }

    /** The prefix is the visualizer's; the mixer knows the endpoint by its bare id. */
    @Test
    fun `a device fader is written by its endpoint id`() {
        assertEquals(
            "MDXM_DEVVOL|$buds|0.4000",
            MdxmMixerRows.volumeCommand(fader("endpoint:$buds|main"), 40),
        )
        assertEquals(
            "MDXM_DEVMUTE|$buds|0",
            MdxmMixerRows.muteCommand(fader("endpoint:$buds|main"), false),
        )
    }

    @Test
    fun `a level outside the range is clamped on the way out`() {
        assertEquals(
            "MDXM_SET|sonar:aux|personal|1.0000",
            MdxmMixerRows.volumeCommand(fader("sonar:aux|monitoring"), 140),
        )
    }
}
