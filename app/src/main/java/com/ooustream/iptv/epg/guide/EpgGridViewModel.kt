package com.ooustream.iptv.epg.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.EpgCacheRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import com.ooustream.iptv.epg.SmartEpgFiller
import com.ooustream.iptv.parental.ContentFilterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Guide data: category-scoped channel list + viewport-driven per-row EPG. EPG is NEVER
 * fetched for the whole list — only rows reported visible by the fragment (±buffer), at
 * most [MAX_CONCURRENT_EPG_FETCHES] in flight, with off-screen fetches cancelled.
 */
@HiltViewModel
class EpgGridViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val favoriteRepository: FavoriteRepository,
    private val epgCacheRepository: EpgCacheRepository,
    private val contentFilterManager: ContentFilterManager,
    val smartEpgFiller: SmartEpgFiller
) : ViewModel() {

    private val _channels = MutableStateFlow<List<LiveStream>>(emptyList())
    val channels: StateFlow<List<LiveStream>> = _channels.asStateFlow()

    /** Live categories (Favorites first) for the in-guide category picker. */
    private val _categories = MutableStateFlow<List<com.ooustream.iptv.data.model.Category>>(emptyList())
    val categories: StateFlow<List<com.ooustream.iptv.data.model.Category>> = _categories.asStateFlow()

    /** Normalized lanes keyed by streamId. Rows without an entry render synthetic blocks. */
    private val _rowEpg = MutableStateFlow<Map<Int, List<GuideProgram>>>(emptyMap())
    val rowEpg: StateFlow<Map<Int, List<GuideProgram>>> = _rowEpg.asStateFlow()

    var categoryName: String? = null
        private set

    var horizonStartMs = 0L
        private set
    var horizonEndMs = 0L
        private set

    /**
     * Render-ready rows: real EPG where fetched, memoized synthetic blocks elsewhere —
     * the grid is never empty. Built off-main; synthetic lanes are cached per channel so
     * each per-row EPG arrival doesn't re-run rule inference for every unfetched channel.
     */
    val rows: StateFlow<List<GuideRowData>> = combine(_channels, _rowEpg) { chans, epg ->
        chans.map { ch -> GuideRowData(ch, epg[ch.streamId] ?: syntheticLane(ch), accentFor(ch)) }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Live favorite ids ("live_<streamId>") — drives the inline hearts + header indicator. */
    val favoriteIds: StateFlow<Set<String>> = favoriteRepository.getFavoritesByType("live")
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private val syntheticCache = HashMap<Int, List<GuideProgram>>()
    private val accentCache = HashMap<Int, Int>()

    @Synchronized
    private fun syntheticLane(ch: LiveStream): List<GuideProgram> =
        syntheticCache.getOrPut(ch.streamId) {
            GuideEpgNormalizer.syntheticBlocks(
                horizonStartMs, horizonEndMs, ch.name, categoryName, smartEpgFiller
            )
        }

    /** Genre accent per channel — classification is regex-heavy, so memoize per streamId. */
    @Synchronized
    private fun accentFor(ch: LiveStream): Int =
        accentCache.getOrPut(ch.streamId) {
            GuideGenrePalette.colorForChannel(ch.name, categoryName)
        }

    /** Toggle a live channel's favorite state (drives [favoriteIds] reactively). */
    fun toggleFavorite(channel: LiveStream) {
        viewModelScope.launch {
            val id = "live_${channel.streamId}"
            if (favoriteRepository.isFavorite(id)) {
                favoriteRepository.removeFavorite(id)
            } else {
                favoriteRepository.addFavorite(
                    FavoriteEntity(
                        id = id,
                        streamId = channel.streamId,
                        type = "live",
                        name = channel.name,
                        icon = channel.streamIcon,
                        categoryId = channel.categoryId,
                        extra = null
                    )
                )
            }
        }
    }

    private val fetchJobs = mutableMapOf<Int, Job>()
    private val fetchedRows = mutableSetOf<Int>()
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_EPG_FETCHES)

    fun setHorizon(startMs: Long, endMs: Long) {
        horizonStartMs = startMs
        horizonEndMs = endMs
    }

    fun loadChannels(categoryId: String?, categoryName: String?) {
        this.categoryName = categoryName
        viewModelScope.launch {
            try {
                val list = when {
                    categoryId == FAVORITES_ID || categoryId == null -> {
                        val favs = favoriteRepository.getFavoritesListByType("live")
                        if (favs.isNotEmpty()) {
                            this@EpgGridViewModel.categoryName = "Favorites"
                            favs.map { fav ->
                                LiveStream(
                                    num = null, name = fav.name, streamType = "live",
                                    streamId = fav.streamId, streamIcon = fav.icon,
                                    epgChannelId = null, added = null,
                                    categoryId = fav.categoryId, customSid = null,
                                    tvArchive = 0, directSource = null, tvArchiveDuration = null
                                )
                            }
                        } else {
                            // No favorites — fall back to the first real category
                            val cats = contentFilterManager.filterCategories(
                                "live", contentRepository.getLiveCategories()
                            )
                            val first = cats.firstOrNull()
                            this@EpgGridViewModel.categoryName = first?.categoryName
                            first?.let {
                                contentFilterManager.filterContent(
                                    "live", contentRepository.getLiveStreams(it.categoryId)
                                ) { s -> s.categoryId }
                            } ?: emptyList()
                        }
                    }
                    else -> contentFilterManager.filterContent(
                        "live", contentRepository.getLiveStreams(categoryId)
                    ) { it.categoryId }
                }
                // Favorites-first ordering (stable): favorited channels float to the top of the
                // current scope. Snapshotted at load — toggling a heart updates the icon but does
                // NOT reorder live (avoids a jarring jump + lost focus position mid-browse).
                val favStreamIds = try {
                    favoriteRepository.getFavoritesListByType("live").map { it.streamId }.toSet()
                } catch (_: Exception) {
                    emptySet()
                }
                _channels.value = list.sortedByDescending { favStreamIds.contains(it.streamId) }
            } catch (_: Exception) {
                _channels.value = emptyList()
            }
        }
    }

    /**
     * Called by the fragment (already 400ms-debounced) with the visible row range.
     * Cancels in-flight fetches that scrolled away; starts fetches for new rows.
     */
    fun onVisibleRangeChanged(first: Int, last: Int) {
        val list = _channels.value
        if (list.isEmpty()) return
        val lo = (first - VISIBLE_BUFFER_ROWS).coerceAtLeast(0)
        val hi = (last + VISIBLE_BUFFER_ROWS).coerceAtMost(list.size - 1)
        val wanted = (lo..hi).map { list[it].streamId }.toSet()

        // Cancel fetches for rows that left the window
        fetchJobs.entries.removeAll { (streamId, job) ->
            if (streamId !in wanted) { job.cancel(); true } else false
        }

        for (idx in lo..hi) {
            val channel = list[idx]
            val id = channel.streamId
            if (id in fetchedRows || fetchJobs.containsKey(id)) continue
            fetchJobs[id] = viewModelScope.launch {
                val programs = try {
                    // minPrograms=20 → Xtream `limit` param: ~8-12h of listings per lane
                    fetchSemaphore.withPermit { epgCacheRepository.getEpg(id, minPrograms = 20) }
                } catch (_: Exception) {
                    emptyList()
                }
                val normalized = withContext(Dispatchers.Default) {
                    GuideEpgNormalizer.normalize(
                        programs, horizonStartMs, horizonEndMs,
                        channel.name, categoryName, smartEpgFiller
                    )
                }
                fetchedRows += id
                fetchJobs.remove(id)
                _rowEpg.value = _rowEpg.value + (id to normalized)
            }
        }
    }

    /** Populate the category picker (one fetch per session — categories rarely change). */
    fun loadCategoryOptions() {
        if (_categories.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val cats = contentFilterManager.filterCategories(
                    "live", contentRepository.getLiveCategories()
                )
                _categories.value =
                    listOf(com.ooustream.iptv.data.model.Category(FAVORITES_ID, "Favorites", null)) + cats
            } catch (_: Exception) {
                // picker simply stays unavailable on a fetch error
            }
        }
    }

    /** Re-scope the guide to another category: clears all per-row EPG state and reloads. */
    fun switchCategory(categoryId: String?, categoryName: String?) {
        fetchJobs.values.forEach { it.cancel() }
        fetchJobs.clear()
        fetchedRows.clear()
        synchronized(this) { syntheticCache.clear(); accentCache.clear() }   // categoryName affects inference
        _rowEpg.value = emptyMap()
        _channels.value = emptyList()
        loadChannels(categoryId, categoryName)
    }

    fun buildStreamUrl(streamId: Int): String = contentRepository.buildLiveStreamUrl(streamId)

    companion object {
        const val FAVORITES_ID = "__favorites__"
        private const val MAX_CONCURRENT_EPG_FETCHES = 3
        private const val VISIBLE_BUFFER_ROWS = 3
    }
}
