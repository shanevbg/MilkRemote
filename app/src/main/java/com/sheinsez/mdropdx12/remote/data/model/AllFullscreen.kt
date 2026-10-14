package com.sheinsez.mdropdx12.remote.data.model

/**
 * Whether every screen is showing the visualiser, read from the actual windows.
 *
 * The walk-away state: one command puts the primary and every ready child
 * foreground and fullscreen, and one puts them back exactly as they were. The
 * restore is why this is a PC verb rather than a burst of relays from here --
 * only the PC knows what each window was before it was changed.
 */
data class AllFullscreen(
    /** The answer to "is everything up", which is what a toggle shows. */
    val all: Boolean = false,
    val primary: Boolean = false,
    val mirrorsActive: Boolean = false,
    val childrenUp: Int = 0,
    val childrenTotal: Int = 0,
    /**
     * False until the PC has answered. A default must not read as "everything
     * is down": that is a statement about the PC that nothing has made, and it
     * would show up as a confident explanation in the tile of a machine that
     * has not been asked.
     */
    val known: Boolean = false,
) {
    /**
     * Why it is not all up, when it is not, in the fewest words that are true.
     * Null when everything is up, or when there is nothing to say.
     */
    val shortfall: String?
        get() = when {
            !known -> null
            all -> null
            childrenTotal > 0 && childrenUp < childrenTotal ->
                "$childrenUp of $childrenTotal displays up"
            !primary -> "the main window is not up"
            else -> null
        }
}
