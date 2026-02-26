package com.ooustream.iptv.favorites

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.dao.WatchProgressDao
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.model.EpgProgram
import com.ooustream.iptv.data.repository.ContentCacheRepository
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.EpgCacheRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortMode { RECENTLY_ADDED, RECENTLY_WATCHED, NAME_AZ, CATEGORY }
enum class ViewMode { GRID, LIST }

data class UndoEvent(val item: FavoriteEntity, val message: String)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val contentRepository: ContentRepository,
    private val contentCacheRepository: ContentCacheRepository,
    private val epgCacheRepository: EpgCacheRepository,
    private val watchProgressDao: WatchProgressDao
) : BaseViewModel() {

    private val _activeFilter = MutableStateFlow(ChannelCategoryMapper.GROUP_ALL)
    val activeFilter: StateFlow<String> = _activeFilter.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.RECENTLY_ADDED)
    val sortMode: StateFlow<String> get() = MutableStateFlow(_sortMode.value.name).asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.GRID)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    private val _undoEvent = MutableSharedFlow<UndoEvent>()
    val undoEvent: SharedFlow<UndoEvent> = _undoEvent.asSharedFlow()

    /** Trigger for 60-second EPG refresh cycle */
    private val epgRefreshTrigger = MutableStateFlow(System.currentTimeMillis())

    /** Category maps: categoryId → categoryName (loaded once) */
    private var liveCategoryMap: Map<String, String> = emptyMap()
    private var vodCategoryMap: Map<String, String> = emptyMap()
    private var seriesCategoryMap: Map<String, String> = emptyMap()
    private var categoriesLoaded = false

    /** All enriched display items (before filter/sort) */
    private val _allDisplayItems = MutableStateFlow<List<FavoriteDisplayItem>>(emptyList())

    /** Filtered + sorted display items for the UI */
    val displayItems: StateFlow<List<FavoriteDisplayItem>> = combine(
        _allDisplayItems,
        _activeFilter,
        _sortMode
    ) { items, filter, sort ->
        val filtered = if (filter == ChannelCategoryMapper.GROUP_ALL) {
            items
        } else {
            items.filter { it.categoryGroup == filter }
        }
        applySorting(filtered, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Per-group counts for tab badges */
    val filterCounts: StateFlow<Map<String, Int>> = _allDisplayItems
        .combine(MutableStateFlow(Unit)) { items, _ ->
            val counts = mutableMapOf<String, Int>()
            counts[ChannelCategoryMapper.GROUP_ALL] = items.size
            for (group in ChannelCategoryMapper.ALL_GROUPS) {
                if (group != ChannelCategoryMapper.GROUP_ALL) {
                    counts[group] = items.count { it.categoryGroup == group }
                }
            }
            counts
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val totalCount: StateFlow<Int> = favoriteRepository.getCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Saved focus position for restoration after returning from playback */
    var savedFocusPosition: Int = 0

    /** Entity cache for undo support */
    private var lastRemovedEntity: FavoriteEntity? = null

    init {
        // Start the enrichment pipeline
        viewModelScope.launch {
            combine(
                favoriteRepository.getAllFavorites(),
                epgRefreshTrigger
            ) { favorites, _ -> favorites }.collect { favorites ->
                val enriched = enrichFavorites(favorites)
                _allDisplayItems.value = enriched
            }
        }

        // 60-second EPG refresh cycle
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                epgRefreshTrigger.value = System.currentTimeMillis()
            }
        }
    }

    fun setActiveFilter(filter: String) {
        _activeFilter.value = filter
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun toggleViewMode() {
        _viewMode.value = if (_viewMode.value == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
    }

    fun toggleEditMode() {
        _isEditMode.value = !_isEditMode.value
    }

    fun exitEditMode() {
        _isEditMode.value = false
    }

    fun removeFavorite(id: String) {
        viewModelScope.launch {
            try {
                // Find the entity for undo support
                val items = _allDisplayItems.value
                val displayItem = items.find { it.id == id }
                if (displayItem != null) {
                    // Reconstruct entity for undo
                    val entity = FavoriteEntity(
                        id = displayItem.id,
                        streamId = displayItem.streamId,
                        type = displayItem.type,
                        name = displayItem.name,
                        icon = displayItem.icon,
                        categoryId = displayItem.categoryId,
                        extra = displayItem.extra,
                        createdAt = displayItem.createdAt
                    )
                    lastRemovedEntity = entity
                    favoriteRepository.removeFavorite(id)
                    _undoEvent.emit(UndoEvent(entity, "\"${displayItem.name}\" removed"))
                } else {
                    favoriteRepository.removeFavorite(id)
                    _toastEvent.emit("Removed from Favorites")
                }
            } catch (e: Exception) {
                _toastEvent.emit("Failed to remove favorite")
            }
        }
    }

    fun undoRemove() {
        val entity = lastRemovedEntity ?: return
        lastRemovedEntity = null
        viewModelScope.launch {
            try {
                favoriteRepository.addFavorite(entity)
                _toastEvent.emit("Restored \"${entity.name}\"")
            } catch (_: Exception) { }
        }
    }

    fun buildLiveUrl(streamId: Int): String = contentRepository.buildLiveStreamUrl(streamId)

    fun buildVodUrl(streamId: Int, ext: String): String = contentRepository.buildVodStreamUrl(streamId, ext)

    // --- Private enrichment pipeline ---

    private suspend fun loadCategoryMaps() {
        if (categoriesLoaded) return
        try {
            liveCategoryMap = loadCategoryMapForType("live")
            vodCategoryMap = loadCategoryMapForType("vod")
            seriesCategoryMap = loadCategoryMapForType("series")
            categoriesLoaded = true
        } catch (_: Exception) { }
    }

    private suspend fun loadCategoryMapForType(type: String): Map<String, String> {
        return try {
            val categories = contentCacheRepository.getCategories(type).firstOrNull() ?: emptyList()
            categories.associate { it.categoryId to it.categoryName }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun enrichFavorites(favorites: List<FavoriteEntity>): List<FavoriteDisplayItem> {
        loadCategoryMaps()

        return favorites.map { entity ->
            val categoryMap = when (entity.type) {
                "live" -> liveCategoryMap
                "vod" -> vodCategoryMap
                "series" -> seriesCategoryMap
                else -> emptyMap()
            }
            val categoryName = entity.categoryId?.let { categoryMap[it] }
            val categoryGroup = ChannelCategoryMapper.mapToGroup(entity.type, entity.name, categoryName)

            // EPG data for live channels
            var epgTitle: String? = null
            var epgNextTitle: String? = null
            var epgProgress = 0f
            var score: String? = null
            var scoreDetail: String? = null

            if (entity.type == "live") {
                try {
                    val programs = epgCacheRepository.getEpg(entity.streamId)
                    val now = System.currentTimeMillis() / 1000
                    val current = findCurrentProgram(programs, now)
                    val next = findNextProgram(programs, now)

                    if (current != null) {
                        epgTitle = current.title
                        val start = current.startTimestamp?.toLongOrNull() ?: 0
                        val end = current.stopTimestamp?.toLongOrNull() ?: 0
                        if (end > start) {
                            epgProgress = ((now - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
                        }
                        // Parse score for sports channels
                        if (categoryGroup == ChannelCategoryMapper.GROUP_SPORTS) {
                            val scoreResult = ScoreParser.parse(current.title)
                            score = scoreResult?.score
                            scoreDetail = scoreResult?.detail
                        }
                    }
                    epgNextTitle = next?.title?.let { "Next: $it" }
                } catch (_: Exception) { }
            }

            // Watch progress for VOD/series
            var lastWatched: Long? = null
            var watchProgress = 0f

            if (entity.type == "vod" || entity.type == "series") {
                try {
                    val progress = watchProgressDao.getProgress(entity.streamId.toString())
                    if (progress != null) {
                        lastWatched = progress.lastWatched
                        watchProgress = progress.progressPercent
                    }
                } catch (_: Exception) { }
            }

            FavoriteDisplayItem(
                id = entity.id,
                streamId = entity.streamId,
                type = entity.type,
                name = entity.name,
                icon = entity.icon,
                categoryId = entity.categoryId,
                categoryName = categoryName,
                categoryGroup = categoryGroup,
                extra = entity.extra,
                createdAt = entity.createdAt,
                epgTitle = epgTitle,
                epgNextTitle = epgNextTitle,
                epgProgress = epgProgress,
                score = score,
                scoreDetail = scoreDetail,
                lastWatched = lastWatched,
                watchProgress = watchProgress,
                isLive = entity.type == "live"
            )
        }
    }

    private fun findCurrentProgram(programs: List<EpgProgram>, nowSecs: Long): EpgProgram? {
        return programs.find { p ->
            val start = p.startTimestamp?.toLongOrNull() ?: return@find false
            val end = p.stopTimestamp?.toLongOrNull() ?: return@find false
            nowSecs in start..end
        }
    }

    private fun findNextProgram(programs: List<EpgProgram>, nowSecs: Long): EpgProgram? {
        val sorted = programs
            .filter { (it.startTimestamp?.toLongOrNull() ?: 0) > nowSecs }
            .sortedBy { it.startTimestamp?.toLongOrNull() ?: Long.MAX_VALUE }
        return sorted.firstOrNull()
    }

    private fun applySorting(items: List<FavoriteDisplayItem>, sort: SortMode): List<FavoriteDisplayItem> {
        return when (sort) {
            SortMode.RECENTLY_ADDED -> items.sortedByDescending { it.createdAt }
            SortMode.RECENTLY_WATCHED -> items.sortedByDescending { it.lastWatched ?: 0L }
            SortMode.NAME_AZ -> items.sortedBy { it.name.lowercase() }
            SortMode.CATEGORY -> items.sortedWith(compareBy({ it.categoryGroup }, { it.name.lowercase() }))
        }
    }
}
