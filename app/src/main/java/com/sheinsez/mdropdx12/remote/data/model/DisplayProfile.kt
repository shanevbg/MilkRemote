package com.sheinsez.mdropdx12.remote.data.model

/**
 * A saved display arrangement, addressed by NAME.
 *
 * The PC also takes a full path, and that form stays for scripts, but a path is
 * not something a phone can build or a person can type: it cannot learn the
 * profiles folder, and a tile cannot carry one.
 */
data class DisplayProfile(
    /** The file in the profiles folder, which is what the verbs address. */
    val file: String,
    /** What to show. Usually a timestamp, because that is how they sort. */
    val name: String,
)

/**
 * Which profile loads at startup, and whether one does at all.
 *
 * Two values, reported and set together, because either alone is a trap: a
 * profile chosen while loading is disabled looks set and never happens, with
 * nothing on screen to say why.
 */
data class DisplayProfileStartup(
    val file: String = "",
    val enabled: Boolean = false,
) {
    /** Nothing will load: either none is chosen, or loading is switched off. */
    val inactive get() = !enabled || file.isEmpty()
}
