package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The forwarding dialect, tested on the cases that actually break it.
 *
 * mdxmixer's grammar has no escaping and treats every `|` as a field
 * boundary, so a record carrying a FOREIGN record can only be sliced, never
 * parsed. Every test here that looks paranoid is guarding that one rule.
 */
class MdxmForwardTest {

    // ── building ─────────────────────────────────────────────────────────

    @Test
    fun `a command is wrapped raw, with nothing appended after it`() {
        val f = MdxmCommands.forward("SET_DISPLAY_MODE=2,preset")
        assertEquals(
            Forward.Record("MDXM_FWD|cmd=SET_DISPLAY_MODE=2,preset"),
            f,
        )
    }

    @Test
    fun `a command full of pipes and equals signs goes through untouched`() {
        // The receiver slices after the literal `|cmd=`, so this is legal and
        // must not be escaped, rewritten or reordered on the way out.
        val raw = "DISPLAY|2|MESSAGE=now playing: a|b=c"
        val f = MdxmCommands.forward(raw) as Forward.Record
        assertEquals("MDXM_FWD|cmd=$raw", f.text)
        assertTrue(f.text.endsWith(raw))
    }

    @Test
    fun `an MDXM command is refused here rather than looped back at the mixer`() {
        val f = MdxmCommands.forward("MDXM_VBAN|audio=1")
        assertTrue(f is Forward.Refused)
        assertTrue((f as Forward.Refused).why.contains("mixer"))
    }

    /**
     * The PC's own guard is case-sensitive -- `raw.rfind(L"MDXM_", 0)` -- so
     * a lowercase one passes it, reaches mdx12, and gets DRAWN ON THE
     * VISUALIZER as an unrecognised script line. The raw command box can
     * carry one.
     */
    @Test
    fun `a lowercase MDXM command is refused too`() {
        for (c in listOf("mdxm_ping", "Mdxm_Vban|audio=1", "mDxM_fwd|cmd=NEXT")) {
            assertTrue(c, MdxmCommands.forward(c) is Forward.Refused)
        }
    }

    @Test
    fun `an empty or blank command is refused`() {
        assertTrue(MdxmCommands.forward("") is Forward.Refused)
        assertTrue(MdxmCommands.forward("   ") is Forward.Refused)
    }

    /**
     * The one builder here that must NOT truncate.
     *
     * Cutting the tail off `SET_DISPLAY_MODE=2,preset` leaves
     * `SET_DISPLAY_MODE=2,pre` -- a verb mdx12 acts on differently rather than
     * one it rejects. Free text can be trimmed; a command cannot.
     */
    @Test
    fun `a command too long for one packet is refused, not trimmed`() {
        val huge = "MESSAGE=" + "x".repeat(VbanCodec.MAX_DATA)
        val f = MdxmCommands.forward(huge)
        assertTrue(f is Forward.Refused)
        assertTrue((f as Forward.Refused).why.contains("too long"))
    }

    @Test
    fun `a command that exactly fills the packet is still sent`() {
        val prefixLen = "MDXM_FWD|cmd=".length
        val cmd = "M" + "x".repeat(VbanCodec.MAX_DATA - prefixLen - 1)
        val f = MdxmCommands.forward(cmd)
        assertTrue(f is Forward.Record)
        assertEquals(
            VbanCodec.MAX_DATA,
            (f as Forward.Record).text.toByteArray(Charsets.UTF_8).size,
        )
    }

    @Test
    fun `the subscription verb is positional, not keyed`() {
        assertEquals("MDXM_FWDSUB|1", MdxmCommands.fwdSub(true))
        assertEquals("MDXM_FWDSUB|0", MdxmCommands.fwdSub(false))
    }

    // ── the relay slice ──────────────────────────────────────────────────

    @Test
    fun `a relayed record keeps every pipe and equals sign`() {
        val payload = "PRESET_ROW|i=0|name=Geiss - Cruise|path=C:\\p\\a=b.milk|rating=0"
        assertEquals(payload, MdxmCommands.parseRelay("MDXM_FWDR|msg=$payload"))
    }

    @Test
    fun `a payload that itself contains the marker is not cut at the second one`() {
        // Only the FIRST `|msg=` is the frame. mdx12 could legitimately send a
        // record mentioning one, and a lastIndexOf here would behead it.
        val payload = "ERR|BADARG|msg=who knows"
        assertEquals(payload, MdxmCommands.parseRelay("MDXM_FWDR|msg=$payload"))
    }

    @Test
    fun `a relayed record carrying a newline-free batch terminator survives`() {
        assertEquals("PRESET_ROWS_END|total=4",
            MdxmCommands.parseRelay("MDXM_FWDR|msg=PRESET_ROWS_END|total=4"))
    }

    @Test
    fun `anything that is not a relay record is not one`() {
        assertNull(MdxmCommands.parseRelay("MDXM_OK"))
        assertNull(MdxmCommands.parseRelay("MDXM_FWDSTATE|enabled=1"))
        assertNull(MdxmCommands.parseRelay("MDXM_FWDR"))
        assertNull(MdxmCommands.parseRelay("MDXM_FWDR|msg="))      // empty payload
        // Not a prefix match: a verb that merely starts the same way.
        assertNull(MdxmCommands.parseRelay("MDXM_FWDRX|msg=hi"))
    }

    // ── errors ───────────────────────────────────────────────────────────

    @Test
    fun `every refusal the PC can send is readable`() {
        val expected = listOf(
            "empty command",
            "forwarding is off",
            "notarget",
            "nolaunchpath",
            "launchfail",
            "queuefull",
            "refusing to forward an MDXM record",
        )
        for (msg in expected) {
            assertEquals(msg, MdxmCommands.parseErr("MDXM_ERR|msg=$msg"))
        }
    }

    @Test
    fun `an error message containing a pipe is not cut at it`() {
        // Free text, last in its record, so it may contain anything. Reading
        // this out of a field map would return only "could not run ".
        val msg = "could not run C:\\x\\mdropdx12.exe |pid=0"
        assertEquals(msg, MdxmCommands.parseErr("MDXM_ERR|msg=$msg"))
    }

    @Test
    fun `MDXM_OK is recognised and nothing else is`() {
        assertTrue(MdxmCommands.isOk("MDXM_OK"))
        assertTrue(MdxmCommands.isOk("MDXM_OK\r"))
        assertFalse(MdxmCommands.isOk("MDXM_OKAY"))
        assertFalse(MdxmCommands.isOk("MDXM_ERR|msg=notarget"))
    }

    // ── state ────────────────────────────────────────────────────────────

    private val liveState =
        "MDXM_FWDSTATE|enabled=1|targetExe=mdropdx12.exe|pid=4242|connected=1" +
            "|launching=0|autolaunch=0|learned=1|subs=1|queued=0|dropped=0" +
            "|sent=47|received=4|path=C:\\Program Files\\MDropDX12\\mdropdx12.exe" +
            "|lastError="

    @Test
    fun `the state record read off the wire`() {
        val s = MdxmCommands.parseFwdState(liveState)!!
        assertTrue(s.enabled)
        assertEquals("mdropdx12.exe", s.targetExe)
        assertEquals(4242, s.pid)
        assertTrue(s.connected)
        assertFalse(s.launching)
        assertTrue(s.learned)
        assertEquals(1, s.subs)
        assertEquals(0L, s.dropped)
        assertEquals(47L, s.sent)
        assertEquals(4L, s.received)
        assertEquals("C:\\Program Files\\MDropDX12\\mdropdx12.exe", s.path)
        assertEquals("", s.lastError)
    }

    /**
     * The reason reading stops at the first free-text field instead of
     * trusting that it is last.
     */
    @Test
    fun `an error message quoting a path field cannot overwrite the real path`() {
        val rec = "MDXM_FWDSTATE|enabled=1|targetExe=mdropdx12.exe|pid=0|connected=0" +
            "|launching=0|autolaunch=1|learned=0|subs=1|queued=2|dropped=0" +
            "|sent=0|received=0|path=C:\\real\\mdropdx12.exe" +
            "|lastError=CreateProcess failed for |path=C:\\stale\\gone.exe"
        val s = MdxmCommands.parseFwdState(rec)!!
        assertEquals("C:\\real\\mdropdx12.exe", s.path)
        assertEquals("CreateProcess failed for |path=C:\\stale\\gone.exe", s.lastError)
        assertEquals(0, s.pid)
        assertEquals(2, s.queued)
    }

    @Test
    fun `a state record from an older PC keeps what it does carry`() {
        // Fields are appended and never inserted, so a short record is an old
        // PC rather than a corrupt one. It must not parse as null.
        val s = MdxmCommands.parseFwdState(
            "MDXM_FWDSTATE|enabled=1|targetExe=mdropdx12.exe|pid=4242|connected=1")!!
        assertTrue(s.enabled)
        assertEquals(4242, s.pid)
        assertEquals(0, s.subs)
        assertEquals("", s.path)
    }

    @Test
    fun `a dropped count is the signal that the view is incomplete`() {
        val s = MdxmCommands.parseFwdState(
            liveState.replace("|dropped=0", "|dropped=317"))!!
        assertEquals(317L, s.dropped)
    }

    /**
     * `maxparts` is how a client tells a mixer that cuts a large reply into
     * parts from one that answers `toolong`. It is the newest machine-readable
     * field, so it sits last of them and still ahead of the free text.
     */
    @Test
    fun `a mixer that sends parts says how many`() {
        val s = MdxmCommands.parseFwdState(
            liveState.replace("|path=", "|maxparts=16|path="))!!
        assertEquals(16, s.maxParts)
        // And reading it has not disturbed what follows.
        assertEquals("C:\\Program Files\\MDropDX12\\mdropdx12.exe", s.path)
    }

    @Test
    fun `a mixer that says nothing about parts sends none`() {
        assertEquals(0, MdxmCommands.parseFwdState(liveState)!!.maxParts)
    }

    /** Free text is last so that it cannot be mistaken for a field. */
    @Test
    fun `an error message mentioning maxparts does not grant them`() {
        val s = MdxmCommands.parseFwdState(
            liveState.replace("|lastError=", "|lastError=old build, no |maxparts=16"))!!
        assertEquals(0, s.maxParts)
    }

    @Test
    fun `not a state record`() {
        assertNull(MdxmCommands.parseFwdState("MDXM_VBANSTATE|on=1"))
        assertNull(MdxmCommands.parseFwdState("MDXM_OK"))
    }

    // ── targets ──────────────────────────────────────────────────────────

    @Test
    fun `a target row, path last`() {
        val t = MdxmCommands.parseFwdTarget(
            "MDXM_FWDTARGET|pid=4242|exe=mdropdx12.exe|target=1" +
                "|path=C:\\Program Files\\MDropDX12\\mdropdx12.exe")!!
        assertEquals(4242, t.pid)
        assertEquals("mdropdx12.exe", t.exe)
        assertTrue(t.isTarget)
        assertEquals("C:\\Program Files\\MDropDX12\\mdropdx12.exe", t.path)
    }

    @Test
    fun `a row that is not the chosen target`() {
        val t = MdxmCommands.parseFwdTarget(
            "MDXM_FWDTARGET|pid=9100|exe=mdropdx12.exe|target=0|path=C:\\builds\\dev\\x.exe")!!
        assertFalse(t.isTarget)
        assertEquals(9100, t.pid)
    }

    @Test
    fun `not a target row`() {
        assertNull(MdxmCommands.parseFwdTarget("MDXM_FWDTARGETS"))
        assertNull(MdxmCommands.parseFwdTarget("MDXM_BEGIN"))
    }
}
