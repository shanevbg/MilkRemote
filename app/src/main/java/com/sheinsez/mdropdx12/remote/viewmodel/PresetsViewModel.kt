package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.data.model.PresetFilter
import com.sheinsez.mdropdx12.remote.data.model.PresetGroup
import com.sheinsez.mdropdx12.remote.data.model.PresetRow
import com.sheinsez.mdropdx12.remote.data.model.PresetSort
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.MessageParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PresetsViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionManager = (application as MdrApp).connectionManager

    private val _rows = MutableStateFlow<List<PresetRow>>(emptyList())
    val rows: StateFlow<List<PresetRow>> = _rows

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _folders = MutableStateFlow<List<PresetGroup>>(emptyList())
    val folders: StateFlow<List<PresetGroup>> = _folders

    private val _tags = MutableStateFlow<List<PresetGroup>>(emptyList())
    val tags: StateFlow<List<PresetGroup>> = _tags

    private val _filter = MutableStateFlow(PresetFilter())
    val filter: StateFlow<PresetFilter> = _filter

    /** null = the main window; otherwise the DISPLAYn number a pick is sent to. */
    private val _targetDisplay = MutableStateFlow<Int?>(null)
    val targetDisplay: StateFlow<Int?> = _targetDisplay

    /**
     * Rows for the page being fetched. Held separately and published only at
     * PRESET_ROWS_END, so the list never renders half a page — and so a reply
     * that never terminates cannot leave rows from two different filters mixed
     * together in the visible list.
     */
    private val pending = mutableListOf<PresetRow>()
    private var pendingOffset = 0

    init {
        viewModelScope.launch {
            connectionManager.messages.collect { msg ->
                when {
                    msg.startsWith("PRESET_ROW|") ->
                        MessageParser.parsePresetRow(msg)?.let { pending += it }

                    msg.startsWith("PRESET_ROWS_END") -> {
                        val end = MessageParser.parsePresetRowsEnd(msg)
                        _total.value = end?.first ?: 0
                        // offset 0 is a new query; anything else extends the list.
                        _rows.value =
                            if (pendingOffset == 0) pending.toList()
                            else _rows.value + pending
                        pending.clear()
                        _loading.value = false
                    }

                    msg.startsWith("FOLDERS") ->
                        MessageParser.parseGroups(msg, "FOLDERS")
                            ?.let { g -> _folders.value = g.sortedByDescending { it.count } }

                    msg.startsWith("TAGS") ->
                        MessageParser.parseGroups(msg, "TAGS")
                            ?.let { g -> _tags.value = g.sortedByDescending { it.count } }

                    // An annotation write is acknowledged rather than echoed
                    // back as a row, so re-read the page to show what changed.
                    msg.startsWith("PRESET_ANNOT=OK") -> reload()
                }
            }
        }

        viewModelScope.launch {
            connectionManager.connectionState
                .filter { it == ConnectionState.Connected }
                .collect { refreshGroups(); reload() }
        }
    }

    fun refreshGroups() {
        connectionManager.send(CommandBuilder.getPresetFolders())
        connectionManager.send(CommandBuilder.getPresetTags())
    }

    /** Restart paging from the top under the current filter. */
    fun reload() {
        pending.clear()
        pendingOffset = 0
        _loading.value = true
        connectionManager.send(CommandBuilder.browsePresets(_filter.value, 0, PAGE))
    }

    fun loadMore() {
        if (_loading.value) return
        val have = _rows.value.size
        if (have >= _total.value) return
        pending.clear()
        pendingOffset = have
        _loading.value = true
        connectionManager.send(CommandBuilder.browsePresets(_filter.value, have, PAGE))
    }

    // Every filter change restarts paging: pages fetched under different
    // filters describe different sets, and appending one to the other shows
    // rows that do not match what was asked for.
    private var searchJob: Job? = null

    fun setQuery(q: String) {
        _filter.value = _filter.value.copy(query = q)
        // Debounced: each keystroke is a round trip that re-filters and re-sorts
        // the whole database on the PC.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            reload()
        }
    }

    fun setFolder(name: String?) {
        _filter.value = _filter.value.copy(folder = name, tag = null)
        reload()
    }

    fun setTag(name: String?) {
        _filter.value = _filter.value.copy(tag = name, folder = null)
        reload()
    }

    fun setMinRating(stars: Int) {
        _filter.value = _filter.value.copy(minRating = stars)
        reload()
    }

    fun setSort(sort: PresetSort) {
        _filter.value = _filter.value.copy(sort = sort)
        reload()
    }

    fun clearFilters() {
        _filter.value = PresetFilter()
        reload()
    }

    fun setTargetDisplay(display: Int?) {
        _targetDisplay.value = display
    }

    /** Load on the main window, or on the chosen display's own instance. */
    fun play(row: PresetRow) {
        if (!row.canLoad) return
        val target = _targetDisplay.value
        connectionManager.send(
            if (target == null) CommandBuilder.loadPreset(row.path)
            else CommandBuilder.setDisplayPreset(target, row.path),
        )
    }

    fun rate(row: PresetRow, stars: Int) {
        if (!row.canAnnotate) return
        connectionManager.send(CommandBuilder.setPresetRatingFor(row.hash, stars))
    }

    fun setFlag(row: PresetRow, flag: String, on: Boolean) {
        if (!row.canAnnotate) return
        connectionManager.send(CommandBuilder.setPresetFlagFor(row.hash, flag, on))
    }

    fun setTagOn(row: PresetRow, tag: String, on: Boolean) {
        if (!row.canAnnotate || tag.isBlank()) return
        connectionManager.send(CommandBuilder.setPresetTagFor(row.hash, tag, on))
        refreshGroups()   // a new tag becomes a group
    }

    private companion object {
        // Big enough that scrolling rarely waits, small enough that a filter
        // change does not drag a large reply through the pipe and the TCP
        // broadcast for results about to be discarded.
        const val PAGE = 60
    }
}
