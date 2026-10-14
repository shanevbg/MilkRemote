package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.DisplayProfile
import com.sheinsez.mdropdx12.remote.data.model.DisplayProfileStartup
import com.sheinsez.mdropdx12.remote.network.DisplayProfileListing.Update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The saved arrangements, gathered ONE RECORD AT A TIME.
 *
 * mdx12 writes the whole listing into a single pipe message, and the screen
 * used to receive it that way and publish at the end of the message. Both
 * wires now deliver a record at a time -- the link splits a TCP batch, and a
 * mixer that takes a message apart relays a row per record -- so "the end of
 * what just arrived" is the end of every row.
 *
 * The listing has no terminator of its own. The startup reply, asked for in
 * the same breath and answered after it, is what closes it.
 */
class DisplayProfileListingTest {

    private fun row(name: String) = "DISPLAY_PROFILE|file=$name.json|name=$name"
    private fun startup(file: String = "", enabled: Boolean = false) =
        "DISPLAY_PROFILE_STARTUP|file=$file|enabled=${if (enabled) 1 else 0}"

    private fun profile(name: String) = DisplayProfile("$name.json", name)

    /**
     * Published after each row, the list held one arrangement by the end --
     * the last -- against a folder of eighty-two.
     */
    @Test
    fun `rows that arrive one at a time are published together`() {
        val listing = DisplayProfileListing()
        listing.asked()
        assertNull(listing.accept(row("2026-09-14_12-13-38")))
        assertNull(listing.accept(row("2026-09-14_12-48-26")))
        assertNull(listing.accept(row("2026-10-06_12-26-38")))
        assertEquals(
            Update.Listed(
                // The PC's order, which is by name because the name is a
                // timestamp. Its own next/previous stepping walks the same.
                listOf(
                    profile("2026-09-14_12-13-38"),
                    profile("2026-09-14_12-48-26"),
                    profile("2026-10-06_12-26-38"),
                ),
                DisplayProfileStartup(),
            ),
            listing.accept(startup()),
        )
    }

    /**
     * The record that closes the listing is also the one that says which
     * arrangement loads at startup, and it was read for the first and never
     * for the second: one branch matched it and a later one, holding the only
     * write to the startup state, could not run.
     */
    @Test
    fun `the reply that closes a listing also says which one loads at startup`() {
        val listing = DisplayProfileListing()
        listing.asked()
        listing.accept(row("desk"))
        val update = listing.accept(startup("desk.json", enabled = true)) as Update.Listed
        assertEquals(DisplayProfileStartup("desk.json", enabled = true), update.startup)
    }

    @Test
    fun `an empty folder is a listing with nothing in it`() {
        val listing = DisplayProfileListing()
        listing.asked()
        assertEquals(
            Update.Listed(emptyList(), DisplayProfileStartup()),
            listing.accept(startup()),
        )
    }

    /**
     * Choosing a startup arrangement asks for the startup reply alone. That
     * must move the marker and leave the list as it is -- not replace it with
     * the nothing that was gathered since.
     */
    @Test
    fun `a startup reply with no listing asked for is only that`() {
        val listing = DisplayProfileListing()
        assertEquals(
            Update.Startup(DisplayProfileStartup("desk.json", enabled = true)),
            listing.accept(startup("desk.json", enabled = true)),
        )
    }

    /**
     * Through a mixer that cannot carry the listing, the list query is refused
     * and the startup query still travels. Its reply then closed a listing
     * that had never been asked for, and the screen said "0 saved" about a
     * folder it had not read.
     */
    @Test
    fun `a listing that could not be asked for is not closed by the startup reply`() {
        val listing = DisplayProfileListing()
        listing.asked()
        listing.abandoned()
        assertEquals(
            Update.Startup(DisplayProfileStartup("desk.json", enabled = true)),
            listing.accept(startup("desk.json", enabled = true)),
        )
    }

    @Test
    fun `a second listing replaces the first rather than adding to it`() {
        val listing = DisplayProfileListing()
        listing.asked()
        listing.accept(row("a"))
        listing.accept(row("b"))
        listing.accept(startup())

        listing.asked()
        listing.accept(row("b"))
        listing.accept(row("c"))
        assertEquals(
            listOf(profile("b"), profile("c")),
            (listing.accept(startup()) as Update.Listed).profiles,
        )
    }

    /**
     * mdx12 sends every reply to every client, so two phones listing at once
     * each receive both listings, interleaved.
     */
    @Test
    fun `a listing answered twice over is listed once`() {
        val listing = DisplayProfileListing()
        listing.asked()
        for (r in listOf("a", "b", "a", "c", "b", "c")) listing.accept(row(r))
        assertEquals(
            listOf(profile("a"), profile("b"), profile("c")),
            (listing.accept(startup()) as Update.Listed).profiles,
        )
    }

    /** Rows somebody else asked for, before this phone asked for anything. */
    @Test
    fun `rows left over from before the question are not part of the answer`() {
        val listing = DisplayProfileListing()
        listing.accept(row("stale"))
        listing.asked()
        listing.accept(row("a"))
        assertEquals(
            listOf(profile("a")),
            (listing.accept(startup()) as Update.Listed).profiles,
        )
    }

    /**
     * Another client's startup reply can land before this phone's rows do. It
     * closes the listing early and empty -- and the rows that follow must
     * still be published when this phone's own closing reply arrives, not left
     * waiting for a listing that is no longer open.
     */
    @Test
    fun `a closing reply that came early costs a moment, not the list`() {
        val listing = DisplayProfileListing()
        listing.asked()
        assertEquals(Update.Listed(emptyList(), DisplayProfileStartup()), listing.accept(startup()))
        listing.accept(row("a"))
        listing.accept(row("b"))
        assertEquals(
            listOf(profile("a"), profile("b")),
            (listing.accept(startup()) as Update.Listed).profiles,
        )
    }

    @Test
    fun `anything else is not this listing's business`() {
        val listing = DisplayProfileListing()
        listing.asked()
        assertNull(listing.accept("DISPLAY_PROFILE_SAVED|file=a.json"))
        assertNull(listing.accept("PRESET=Geiss - Cruise"))
        assertNull(listing.accept("MIRRORS|active=0"))
    }
}
