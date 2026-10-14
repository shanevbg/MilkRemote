package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.DisplayProfile
import com.sheinsez.mdropdx12.remote.data.model.DisplayProfileStartup

/**
 * The saved display arrangements, gathered one record at a time.
 *
 * `DISPLAY_PROFILE_LIST` answers with a `DISPLAY_PROFILE|…` row per
 * arrangement and no terminator. mdx12 writes them all into one pipe message,
 * and the Displays screen used to lean on that: it gathered the rows of one
 * message and published at the end of it. Neither wire delivers a message any
 * more -- the link splits a TCP batch into records, and a mixer that takes a
 * message apart relays a row per record -- so that rule published after every
 * row and the list ended up holding the last one.
 *
 * What closes a listing is the `DISPLAY_PROFILE_STARTUP|…` reply, which the
 * screen asks for in the same breath and mdx12 answers after the rows. That
 * record does two jobs, and both are done here so that neither can shadow the
 * other: it ends the listing, and it says which arrangement loads at startup.
 *
 * Pure. The caller says when a listing was [asked] for and when it could not
 * be, which are the two things a record cannot say about itself.
 */
class DisplayProfileListing {

    sealed interface Update {
        /** A whole listing, and the startup choice that closed it. */
        data class Listed(
            val profiles: List<DisplayProfile>,
            val startup: DisplayProfileStartup,
        ) : Update

        /** The startup choice alone. The list is whatever it already was. */
        data class Startup(val startup: DisplayProfileStartup) : Update
    }

    // Keyed by file, in arrival order. mdx12 sends every reply to every
    // client, so two phones listing at once each receive both listings
    // interleaved; the key is what keeps that from doubling the list, and the
    // order is the PC's, which its own next/previous stepping walks.
    private val rows = LinkedHashMap<String, DisplayProfile>()
    private var awaited = false

    /** A listing has been asked for. Anything gathered so far was not it. */
    fun asked() {
        rows.clear()
        awaited = true
    }

    /**
     * The listing could not be asked for after all.
     *
     * Without this the startup reply -- which travels where the listing does
     * not -- closes a listing nobody sent, and the screen reports an empty
     * folder it never read.
     */
    fun abandoned() {
        rows.clear()
        awaited = false
    }

    /** What this record settles, or null when it settles nothing yet. */
    fun accept(record: String): Update? {
        MessageParser.parseDisplayProfile(record)?.let {
            if (it.file !in rows) rows[it.file] = it
            return null
        }
        val startup = MessageParser.parseDisplayProfileStartup(record) ?: return null

        // Rows in hand are a listing whether or not one is still awaited:
        // another client's closing reply can arrive ahead of this phone's
        // rows and close the listing early, and the rows that follow must
        // still be published by the next closing reply rather than stranded.
        if (rows.isEmpty() && !awaited) return Update.Startup(startup)

        val listed = Update.Listed(rows.values.toList(), startup)
        rows.clear()
        awaited = false
        return listed
    }
}
