package com.sheinsez.mdropdx12.remote.data.model

/**
 * The faders as one flat list, in the PC's stored MIXER_ORDER sequence.
 *
 * The PC owns fader positioning; the phone reads that order and writes back to
 * it with MIXER_ORDER_MOVE. So the order is rendered LITERALLY — no grouping,
 * no secondary sort. An earlier build grouped the rows by channel and gave each
 * group the position of its earliest fader, which reads well until two channels
 * interleave in the stored order: the phone then showed a different sequence
 * from the window on the monitor, and Move Up / Move Down operated on positions
 * that were not on screen.
 */
fun orderFadersForDisplay(
    faders: List<MixerFader>,
    order: List<String>,
    showVirtual: Boolean,
): List<MixerFader> {
    val position = order.withIndex().associate { (i, key) -> key to i }
    // A fader the order has never seen -- a device that has just joined -- sorts
    // after every one it has, rather than at position 0 where a -1 would put it.
    // They all share that rank, and sortedBy is stable, so they hold the order
    // they arrived in until the refetched MIXER_ORDER places them.
    fun posOf(f: MixerFader) = position[f.key] ?: order.size

    return faders
        .filter { showVirtual || !it.virtual }
        .sortedBy { posOf(it) }
}

/**
 * The faders as the PC's Mixer tab DRAWS them.
 *
 * MIXER_ORDER is the order the PC stores; MIXER_VIEW is the one it renders,
 * after unmuted-first, failover pinning and hiding have been applied to it.
 * Only the second is the list a person is looking at, so only the second is
 * worth showing on a phone that claims to mirror it.
 *
 * @param view null, or empty, when the PC is too old to answer MIXER_VIEW --
 * the stored order is then all there is, and it is used.
 * @param showVirtual reveals the rows the PC is HIDING. When it hides nothing,
 * the switch has nothing to do: the drawn list is already everything.
 */
fun viewFadersForDisplay(
    faders: List<MixerFader>,
    view: MixerView?,
    order: List<String>,
    showVirtual: Boolean,
): List<MixerFader> {
    if (view == null || view.rows.isEmpty()) {
        return orderFadersForDisplay(faders, order, showVirtual)
    }

    val drawn = mutableListOf<Pair<Int, MixerFader>>()
    val hidden = mutableListOf<Pair<Int, MixerFader>>()
    val unplaced = mutableListOf<MixerFader>()

    for (f in faders) {
        val row = view.byKey[f.key]
        when {
            // A fader the view has not placed yet -- it arrived after the list
            // was fetched. The PC's push will correct this shortly; until then
            // it belongs at the end rather than nowhere.
            row == null -> unplaced += f
            row.pos >= 0 -> drawn += row.pos to f
            // Hidden rows keep their place among each other by stored order,
            // which is the only sequence the PC gives them: it draws them
            // nowhere, so there is no drawn position to honour.
            else -> hidden += row.order to f
        }
    }

    return drawn.sortedBy { it.first }.map { it.second } +
        (if (showVirtual) hidden.sortedBy { it.first }.map { it.second } else emptyList()) +
        unplaced
}

/**
 * The MIXER_ORDER-space delta that swaps a row with its neighbour ON SCREEN,
 * or null when there is no neighbour that way.
 *
 * Render the view, move in the order. Sending -1 for "up one row" assumes the
 * two coordinate systems agree, and they do not: the stored order keeps a place
 * for every device that is merely switched off, so a single step usually landed
 * on one of those and moved nothing the user could see.
 *
 * @param direction -1 for up the screen, +1 for down.
 */
fun orderDeltaForVisibleMove(view: MixerView, key: String, direction: Int): Int? {
    val drawn = view.rows.filter { it.pos >= 0 }.sortedBy { it.pos }
    val at = drawn.indexOfFirst { it.key == key }
    if (at < 0) return null
    val neighbour = drawn.getOrNull(at + direction) ?: return null
    return neighbour.order - drawn[at].order
}

