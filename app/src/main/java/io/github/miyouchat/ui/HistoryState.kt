package io.github.miyouchat.ui

import io.github.miyouchat.data.*

data class HistoryState(val visible: Boolean = false, val filter: HistoryFilter = HistoryFilter(),
    val hits: List<SearchHit> = emptyList(), val next: SearchCursor? = null, val loading: Boolean = false,
    val coverage: List<HistoryCoverage> = emptyList(), val syncing: Boolean = false, val progress: String = "")
data class ArchiveState(val visible: Boolean = false, val running: Boolean = false, val progress: String = "", val result: String = "")
