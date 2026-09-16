package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.PresetFilter
import com.sheinsez.mdropdx12.remote.data.model.PresetSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsed against replies captured from a running MDropDX12 rather than written
 * from the format string, so the grammar under test is the PC's.
 */
class PresetBrowseTest {

    /** A five-star entry that had no recorded path until the PC resolved one. */
    private val resolvedRow =
        "PRESET_ROW|hash=|name=amandio c - fume MilkDrop2077rmx2b.milk|rating=5|flags=|tags=" +
            "|folder=presets1|used=0|lastUsed=|missing=0" +
            "|path=C:\\Code\\Entertainment\\MilkAssets\\MilkDrop 3PRO\\Milkdrop3\\presets1\\" +
            "amandio c - fume MilkDrop2077rmx2b.milk"

    private val playedRow =
        "PRESET_ROW|hash=b98e35110a3a7b27|name=diatribes - MilkDrop2077 - Cloud Look3 [MOUSE].milk" +
            "|rating=0|flags=|tags=|folder=md31_rewritten|used=10|lastUsed=2026-08-29T21:44:06" +
            "|missing=0|path=C:\\Code\\Entertainment\\MilkAssets\\md31_rewritten\\x.milk"

    @Test
    fun parsesARowWhosePathWasResolvedByThePc() {
        val row = MessageParser.parsePresetRow(resolvedRow)!!

        assertEquals("amandio c - fume MilkDrop2077rmx2b.milk", row.name)
        assertEquals(5, row.rating)
        assertEquals("presets1", row.folder)
        assertFalse(row.missing)
        assertTrue(row.canLoad)
        assertTrue(row.path.endsWith("amandio c - fume MilkDrop2077rmx2b.milk"))
    }

    /**
     * These entries came from scanning .milk fRating values, so they carry no
     * hash. They can be loaded but not annotated, and the row has to say so
     * rather than offering an edit that silently does nothing.
     */
    @Test
    fun aRowWithNoHashCanBeLoadedButNotAnnotated() {
        val row = MessageParser.parsePresetRow(resolvedRow)!!

        assertEquals("", row.hash)
        assertFalse(row.canAnnotate)
        assertTrue(row.canLoad)
    }

    @Test
    fun parsesPlayCountsAndHashes() {
        val row = MessageParser.parsePresetRow(playedRow)!!

        assertEquals("b98e35110a3a7b27", row.hash)
        assertTrue(row.canAnnotate)
        assertEquals(10, row.useCount)
        assertEquals("2026-08-29T21:44:06", row.lastUsed)
        assertEquals("md31_rewritten", row.folder)
    }

    /** A path can contain '=' , so fields split on the FIRST separator only. */
    @Test
    fun aPathContainingAnEqualsSignSurvives() {
        val row = MessageParser.parsePresetRow(
            "PRESET_ROW|hash=abc|name=x.milk|rating=0|flags=|tags=|folder=odd" +
                "|used=0|lastUsed=|missing=0|path=C:\\presets\\a=b\\x.milk",
        )!!
        assertEquals("C:\\presets\\a=b\\x.milk", row.path)
    }

    @Test
    fun anUnloadableRowIsMarkedMissing() {
        val row = MessageParser.parsePresetRow(
            "PRESET_ROW|hash=|name=gone.milk|rating=5|flags=|tags=|folder=" +
                "|used=0|lastUsed=|missing=1|path=",
        )!!
        assertTrue(row.missing)
        assertFalse(row.canLoad)
    }

    @Test
    fun readsFlagsAndTagsAsSets() {
        val row = MessageParser.parsePresetRow(
            "PRESET_ROW|hash=h|name=n.milk|rating=3|flags=favorite;error|tags=butterfly1;dark" +
                "|folder=f|used=1|lastUsed=|missing=0|path=p",
        )!!
        assertEquals(setOf("favorite", "error"), row.flags)
        assertEquals(setOf("butterfly1", "dark"), row.tags)
        assertTrue(row.isFavorite)
        assertTrue(row.hasError)
    }

    @Test
    fun readsTheFilteredTotalFromTheTerminator() {
        val (total, offset, count) =
            MessageParser.parsePresetRowsEnd("PRESET_ROWS_END|total=69|offset=0|count=4|backfilled=4")!!
        assertEquals(69, total)
        assertEquals(0, offset)
        assertEquals(4, count)
    }

    /**
     * Folder counts sum to MORE than the number of presets: a preset in two
     * places is counted in both. Captured live.
     */
    @Test
    fun parsesFolderGroups() {
        val groups = MessageParser.parseGroups(
            "FOLDERS|BeatDrop=5|Incubo_ Picks=7|TEST=34|md31_rewritten=54|presets=70",
            "FOLDERS",
        )!!
        assertEquals(5, groups.size)
        assertEquals("BeatDrop", groups[0].name)
        assertEquals(54, groups.first { it.name == "md31_rewritten" }.count)
        assertEquals(7, groups.first { it.name == "Incubo_ Picks" }.count)
    }

    @Test
    fun anEmptyTagListIsNotAFailure() {
        assertEquals(emptyList<Any>(), MessageParser.parseGroups("TAGS", "TAGS"))
        assertNull(MessageParser.parseGroups("FOLDERS|a=1", "TAGS"))
    }

    @Test
    fun buildsABrowseCommandWithOnlyTheActiveFilters() {
        assertEquals(
            "BROWSE_PRESETS=0,60,sort=name",
            CommandBuilder.browsePresets(PresetFilter(), 0, 60),
        )
        assertEquals(
            "BROWSE_PRESETS=60,60,sort=rating,q=fume,folder=presets1,minrating=4",
            CommandBuilder.browsePresets(
                PresetFilter(
                    query = "fume",
                    folder = "presets1",
                    minRating = 4,
                    sort = PresetSort.Rating,
                ),
                60, 60,
            ),
        )
    }

    /**
     * Captured from the running PC. Positional, not key=value, so field order
     * is the contract: order, lock, interval, randomness.
     */
    @Test
    fun parsesTheLiveMainWindowCycleReply() {
        val cycle = MessageParser.parsePresetCycle("PRESET_CYCLE=0,0,0.000,10.000")!!

        assertFalse(cycle.sequential)      // 0 = random
        assertFalse(cycle.locked)
        assertEquals(0f, cycle.intervalSeconds, 0.001f)
        assertEquals(10f, cycle.randomnessSeconds, 0.001f)
        assertFalse(cycle.cycling)         // interval 0 means nothing advances
    }

    @Test
    fun readsSequentialAndLockedAndAnInterval() {
        val cycle = MessageParser.parsePresetCycle("PRESET_CYCLE=1,1,30.000,5.000")!!

        assertTrue(cycle.sequential)
        assertTrue(cycle.locked)
        assertEquals(30f, cycle.intervalSeconds, 0.001f)
        assertTrue(cycle.cycling)
    }

    @Test
    fun aTruncatedCycleReplyIsRejectedRatherThanHalfRead() {
        assertNull(MessageParser.parsePresetCycle("PRESET_CYCLE=1,1"))
        assertNull(MessageParser.parsePresetCycle("PRESET_STATUS|file=x"))
    }

    @Test
    fun theCycleCommandsAreLocaleSafe() {
        assertEquals("SET_TIME_BETWEEN_PRESETS=30.0", CommandBuilder.setTimeBetweenPresets(30f))
        assertEquals("SET_PRESET_ORDER=1", CommandBuilder.setPresetOrder(true))
        assertEquals("SET_PRESET_ORDER=0", CommandBuilder.setPresetOrder(false))
    }

    @Test
    fun annotationCommandsAddressByHash() {
        assertEquals("SET_PRESET_RATING_FOR=abc123,5", CommandBuilder.setPresetRatingFor("abc123", 5))
        assertEquals("SET_PRESET_FLAG_FOR=abc123,favorite,1", CommandBuilder.setPresetFlagFor("abc123", "favorite", true))
        assertEquals("SET_PRESET_TAG_FOR=abc123,dark,0", CommandBuilder.setPresetTagFor("abc123", "dark", false))
    }
}
