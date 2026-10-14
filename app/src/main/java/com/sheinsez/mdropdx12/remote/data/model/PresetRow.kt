package com.sheinsez.mdropdx12.remote.data.model

/**
 * One row of the annotation database, from BROWSE_PRESETS.
 *
 * This is presets.json, not the preset folder: on a real install that is a few
 * hundred curated entries against tens of thousands of files on disk, and it is
 * the only place ratings, flags, tags and notes live.
 */
data class PresetRow(
    /** Content hash — the database's primary key, and how annotations address a
     *  preset that is not playing. Empty for entries that never got one. */
    val hash: String,
    val name: String,
    val rating: Int,
    val flags: Set<String>,
    val tags: Set<String>,
    /** Folder the reported path sits in, which is how the browser groups. */
    val folder: String,
    val useCount: Int,
    /** Total seconds this preset has been on screen, across every play. */
    val secondsShown: Int,
    val lastUsed: String,
    /**
     * Nothing on disk could be found for this entry, so it cannot be loaded.
     * The PC resolves and records a path when it can, so this means genuinely
     * gone rather than merely unrecorded.
     */
    val missing: Boolean,
    val path: String,
) {
    val isFavorite get() = "favorite" in flags
    val hasError get() = "error" in flags
    /** Annotations are addressed by hash, so a row without one is read-only. */
    val canAnnotate get() = hash.isNotBlank()
    val canLoad get() = !missing && path.isNotBlank()
}

/** A browse group: a folder or a tag, with how many presets carry it. */
data class PresetGroup(val name: String, val count: Int)

/**
 * How the list is being narrowed. Held as one object so a change to any part
 * restarts paging from zero — mixing pages from different filters is how a list
 * ends up showing rows that do not match what the user asked for.
 */
data class PresetFilter(
    val query: String = "",
    val folder: String? = null,
    val tag: String? = null,
    val flag: String? = null,
    val minRating: Int = 0,
    val sort: PresetSort = PresetSort.Name,
)

enum class PresetSort(val wire: String, val label: String) {
    Name("name", "Name"),
    Rating("rating", "Rating"),
    Used("used", "Most played"),
    Time("time", "Most watched"),
    LastUsed("lastused", "Last played"),
}
