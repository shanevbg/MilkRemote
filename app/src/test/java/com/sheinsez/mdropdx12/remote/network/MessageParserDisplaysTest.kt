package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsed against a DIAG_MIRRORS reply captured from a real MDropDX12 instance
 * rather than one written to match the parser, so the grammar is the PC's and
 * not our idea of it.
 *
 * The machine it came from has three monitors: DISPLAY1 disabled, DISPLAY2 a
 * 2160x3840 portrait panel hosting the render window, DISPLAY3 landscape.
 */
class MessageParserDisplaysTest {

    private val liveDiagMirrors = """
        MIRRORS|active=0|independent=1|aot=0|watermark=0|main=0x0,mainPort=0,needSrv=0,canSample=0,anyOpp=0,anyIndepMilk3=0,shadertoy=0,compPso=0,slots=0,frame=0,skipFrames=0,allocHr=0x00000000,listHr=0x00000000,auxUsed=0,orient=0x0,orientReady=0,orientFrames=0,orientFb=0,orientFps=0.0,mirrorFps=0.0,maxFps=0,aspBad=0,aspGood=0|pace_ms=orientDt(min=0.0,avg=0.0,max=0.0),lock(avg=0.0,max=0.0),rec(avg=0.0,max=0.0),sim(avg=0.0,max=0.0),fenceMiss=0,primDt(min=15.9,avg=16.6,max=17.0)|simFps=0.0|stMouse=0,0,down=0|render_on=\\.\DISPLAY2,renderwin=(-2160,-1165)-(0,2675) 2160x3840,opacity=1.00,fs=1,clickthru=0|mon0=\\.\DISPLAY1,enabled=0,opacity=100,clickthru=0,independent=1,skipped=1,display=(0,-1440)-(2560,0) 2560x1440,portrait=0,state=none|mon1=\\.\DISPLAY2,enabled=1,opacity=100,clickthru=0,independent=1,skipped=1,display=(-2160,-1165)-(0,2675) 2160x3840,portrait=1,state=none|mon2=\\.\DISPLAY3,enabled=1,opacity=100,clickthru=0,independent=1,skipped=1,display=(0,0)-(2560,1440) 2560x1440,portrait=0,state=none
    """.trimIndent()

    @Test
    fun parsesEveryMonitorFromALiveReply() {
        val state = MessageParser.parseMirrors(liveDiagMirrors)!!
        assertEquals(3, state.monitors.size)
    }

    /**
     * The trap this model exists to prevent: mon0 is DISPLAY1, so the
     * enumeration index and the number every command takes differ by one.
     */
    @Test
    fun deviceNumberComesFromTheNameNotTheEnumerationIndex() {
        val monitors = MessageParser.parseMirrors(liveDiagMirrors)!!.monitors

        assertEquals(0, monitors[0].monIndex)
        assertEquals(1, monitors[0].deviceNumber)
        assertNotEquals(monitors[0].monIndex, monitors[0].deviceNumber)

        assertEquals(2, monitors[2].monIndex)
        assertEquals(3, monitors[2].deviceNumber)
    }

    @Test
    fun readsPerMonitorStateThatTheOldParserDiscarded() {
        val portrait = MessageParser.parseMirrors(liveDiagMirrors)!!.monitors[1]

        assertEquals("""\\.\DISPLAY2""", portrait.deviceName)
        assertTrue(portrait.enabled)
        assertTrue(portrait.portrait)
        assertTrue(portrait.independent)
        assertTrue(portrait.skipped)
        assertEquals(100, portrait.opacity)
        assertFalse(portrait.clickThrough)
    }

    @Test
    fun readsMonitorGeometryIncludingNegativeOrigins() {
        val portrait = MessageParser.parseMirrors(liveDiagMirrors)!!.monitors[1]

        assertEquals(-2160, portrait.displayRect.x)
        assertEquals(-1165, portrait.displayRect.y)
        assertEquals(2160, portrait.displayRect.w)
        assertEquals(3840, portrait.displayRect.h)
        assertEquals(0, portrait.displayRect.right)
    }

    @Test
    fun identifiesWhichDisplayHostsTheRenderWindow() {
        val state = MessageParser.parseMirrors(liveDiagMirrors)!!

        assertEquals("""\\.\DISPLAY2""", state.renderDisplay)
        assertEquals(2, state.renderDisplayNumber)
        assertEquals("""\\.\DISPLAY2""", state.primary?.deviceName)
        assertTrue(state.renderFullscreen)
    }

    @Test
    fun readsTheGlobalFlagsBesideTheMonitorList() {
        val state = MessageParser.parseMirrors(liveDiagMirrors)!!

        assertFalse(state.active)
        assertTrue(state.independentDefault)
        assertFalse(state.alwaysOnTop)
    }

    /** An unbuilt mirror reports state=none and carries no ready/path fields. */
    @Test
    fun toleratesAMonitorWithNoRuntimeState() {
        val first = MessageParser.parseMirrors(liveDiagMirrors)!!.monitors[0]

        assertFalse(first.ready)
        assertFalse(first.visible)
        assertEquals("none", first.path)
    }

    /** The exact shape a running instance emits, captured from GET_CHILDREN. */
    @Test
    fun parsesALiveChildLine() {
        val child = MessageParser.parseChild(
            """CHILD|display=\\.\DISPLAY1|pid=4432|preset=C:\Code\MilkAssets\diatribes - Fractal Tunnel Flight3.milk|interval=0.000|interval_raw=-1.000|order=0|dir=|startup=|state=ready""",
        )!!

        assertEquals("""\\.\DISPLAY1""", child.deviceName)
        assertEquals(1, child.deviceNumber)
        assertEquals(4432L, child.pid)
        assertTrue(child.preset.endsWith("Fractal Tunnel Flight3.milk"))
        assertFalse(child.sequentialOrder)
        assertTrue(child.isRunning)
    }

    /**
     * interval is the EFFECTIVE value and interval_raw the configured one, so a
     * child following a parent that never cycles reports 0 and -1. Reading only
     * `interval` cannot tell that apart from a display explicitly set to never.
     */
    @Test
    fun distinguishesAnInheritedIntervalFromAnExplicitOne() {
        val inherited = MessageParser.parseChild(
            """CHILD|display=\\.\DISPLAY1|pid=1|preset=|interval=0.000|interval_raw=-1.000|order=0|state=ready""",
        )!!
        assertEquals(0f, inherited.interval, 0.001f)
        assertTrue(inherited.inheritsInterval)

        val explicit = MessageParser.parseChild(
            """CHILD|display=\\.\DISPLAY1|pid=1|preset=|interval=30.000|interval_raw=30.000|order=1|state=ready""",
        )!!
        assertEquals(30f, explicit.interval, 0.001f)
        assertFalse(explicit.inheritsInterval)
        assertTrue(explicit.sequentialOrder)
    }

    @Test
    fun childStatesOtherThanReadyAreNotRunning() {
        val starting = MessageParser.parseChild(
            """CHILD|display=\\.\DISPLAY3|pid=0|preset=|interval=0.000|order=0|state=starting""",
        )!!

        assertFalse(starting.isRunning)
        assertFalse(starting.sequentialOrder)
        assertEquals(0f, starting.interval, 0.001f)
    }

    @Test
    fun theTerminatorIsNotAChild() {
        assertNull(MessageParser.parseChild("CHILDREN_END"))
    }

    /**
     * MOVE_TO_DISPLAY is addressed by 1-based position in the monitor
     * enumeration, while SET_MIRROR_* and SET_DISPLAY_MODE take the DISPLAYn
     * number. They agree on a contiguous 1..n desktop, so the wrong one passes
     * casual testing — pin the distinction here instead.
     */
    @Test
    fun theTwoDisplayAddressingSchemesAreNotInterchangeable() {
        val monitors = MessageParser.parseMirrors(liveDiagMirrors)!!.monitors
        val third = monitors[2]

        assertEquals(3, third.deviceNumber)
        assertEquals("SET_MIRROR_ENABLED=3,1", CommandBuilder.setMirrorEnabled(third.deviceNumber, true))
        assertEquals("MOVE_TO_DISPLAY=3", CommandBuilder.moveToMonitorPosition(third.monIndex + 1))

        // A gap in the device numbers makes them disagree: the second monitor
        // enumerated would be position 2 but device 3.
        val gapped = MessageParser.parseMirrors(
            """MIRRORS|active=0|mon0=\\.\DISPLAY1,enabled=1,display=(0,0)-(100,100) 100x100|mon1=\\.\DISPLAY3,enabled=1,display=(100,0)-(200,100) 100x100""",
        )!!.monitors[1]

        assertEquals(3, gapped.deviceNumber)
        assertEquals(1, gapped.monIndex)
        assertEquals("MOVE_TO_DISPLAY=2", CommandBuilder.moveToMonitorPosition(gapped.monIndex + 1))
        assertEquals("SET_DISPLAY_MODE=3,child", CommandBuilder.setDisplayMode(gapped.deviceNumber, DisplayMode.Child))
    }
}
