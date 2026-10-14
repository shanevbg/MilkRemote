package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command surface answering, as distinct from the PC being there.
 *
 * WHY A SECOND SIGNAL AND NOT A CHANGE TO [Reachability]. Reachability asks
 * "is the mixer there at all", and is credited by ANY packet -- deliberately,
 * because crediting only PING0 replies once demoted a healthy streaming
 * session every six seconds while a couple of hundred audio packets a second
 * were arriving to say the link was fine. That lesson stands and this must not
 * undo it.
 *
 * But PING0 is answered by mdxmixer STATELESSLY. It replies to a device it has
 * never heard of, and to one it has explicitly denied. So when the PC restarts
 * and loses its in-memory approval, the pings keep being answered, reachability
 * stays true, and the session sits in Streaming receiving nothing -- for ever.
 * Observed as `peers=1 emitting=0` on the mixer, eight times in forty minutes.
 *
 * A TXT reply cannot arrive that way: an unauthorized peer's records are
 * dropped in silence. So the absence of one, while we are asking every tick,
 * is the signal reachability cannot give.
 */
class ControlLivenessTest {

    @Test
    fun `it starts answering and stays so while replies arrive`() {
        val c = ControlLiveness()
        assertTrue(c.answering)
        repeat(10) {
            assertFalse(c.onQuerySent())
            c.onReplyHeard()
        }
        assertTrue(c.answering)
    }

    /**
     * Three unanswered queries at a 2 s tick is about six seconds. One is
     * ordinary on UDP; two happens. Three in a row, while the far end is
     * supposed to answer every one, is a dead control surface -- the same
     * tolerance and the same reasoning as Reachability's.
     */
    @Test
    fun `three unanswered queries asks for a fresh AUTH`() {
        val c = ControlLiveness()
        assertFalse(c.onQuerySent())
        assertFalse(c.onQuerySent())
        assertTrue(c.onQuerySent())
        assertFalse(c.answering)
    }

    /** A single late reply clears the whole debt, as it does for reachability. */
    @Test
    fun `one reply forgives the misses before it`() {
        val c = ControlLiveness()
        c.onQuerySent()
        c.onQuerySent()
        c.onReplyHeard()
        assertTrue(c.answering)
        assertFalse(c.onQuerySent())   // counting starts again from zero
        assertFalse(c.onQuerySent())
    }

    /**
     * IT MUST NOT ASK EVERY TICK ONCE IT HAS GIVEN UP. Re-sending AUTH twice a
     * second at a mixer that is not answering is a retry storm, and the server
     * logs every prompt. One attempt per window, then wait the window again.
     */
    @Test
    fun `it re-asks once per window, not once per tick`() {
        val c = ControlLiveness()
        val asks = (1..9).count { c.onQuerySent() }
        assertFalse(c.answering)
        // Nine ticks, three windows of three: three attempts, not nine.
        assertTrue("expected 3 attempts over 9 silent ticks, got $asks", asks == 3)
    }

    /** Recovery puts it straight back, so the next outage gets a full window. */
    @Test
    fun `a reply after giving up restores it fully`() {
        val c = ControlLiveness()
        repeat(3) { c.onQuerySent() }
        assertFalse(c.answering)
        c.onReplyHeard()
        assertTrue(c.answering)
        assertFalse(c.onQuerySent())
        assertFalse(c.onQuerySent())
        assertTrue(c.onQuerySent())
    }

    /** Reset is what a new session gets, and it must not inherit a debt. */
    @Test
    fun `reset clears the debt`() {
        val c = ControlLiveness()
        c.onQuerySent()
        c.onQuerySent()
        c.reset()
        assertTrue(c.answering)
        assertFalse(c.onQuerySent())
        assertFalse(c.onQuerySent())
    }
}
