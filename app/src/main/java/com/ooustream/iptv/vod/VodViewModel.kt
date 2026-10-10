package com.ooustream.iptv.vod

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.catalog.CatalogBrowse
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.model.VodStream
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.repository.CatalogCache
import com.ooustream.iptv.data.repository.ContentCacheRepository
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import com.ooustream.iptv.data.repository.WatchProgressRepository
import com.ooustream.iptv.parental.AdultCategoryDetector
import com.ooustream.iptv.parental.AdultContentGuard
import com.ooustream.iptv.parental.ContentFilterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class VodViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val favoriteRepository: FavoriteRepository,
    private val contentCacheRepository: ContentCacheRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val contentFilterManager: ContentFilterManager,
    private val catalogCache: CatalogCache,
    private val adultContentGuard: AdultContentGuard
) : BaseViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _movies = MutableStateFlow<List<VodStream>>(emptyList())
    val movies: StateFlow<List<VodStream>> = _movies.asStateFlow()

    private val _selectedCategoryId = MutableStateFlow<String?>(FAVORITES_ID)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    val favorites = favoriteRepository.getFavoritesByType("vod")

    private val _watchProgressMap = MutableStateFlow<Map<String, WatchProgressEntity>>(emptyMap())
    val watchProgressMap: StateFlow<Map<String, WatchProgressEntity>> = _watchProgressMap.asStateFlow()

    /** Decade entries + per-service counts for the sidebar; null until the bulk list has loaded. */
    private val _browseIndex = MutableStateFlow<CatalogBrowse.Index?>(null)
    val browseIndex: StateFlow<CatalogBrowse.Index?> = _browseIndex.asStateFlow()

    /** Sidebar groups the user has opened. Lives here so back-navigation restores them. */
    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    private var indexJob: Job? = null

    /** Saved positions for focus restoration on back navigation */
    var savedGridPosition: Int = -1
    var savedCategoryPosition: Int = -1

    init {
        selectCategory(FAVORITES_ID)
    }

    fun toggleGroup(groupId: String) {
        val current = _expandedGroups.value
        _expandedGroups.value = if (groupId in current) current - groupId else current + groupId
    }

    fun loadCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                contentCacheRepository.getCategories("vod").collect { categories ->
                    _categories.value = contentFilterManager.filterCategories("vod", categories)
                    buildBrowseIndex()
                    if (_selectedCategoryId.value == FAVORITES_ID) {
                        val favCount = favoriteRepository.getFavoritesListByType("vod").size
                        if (favCount == 0) {
                            categories.firstOrNull()?.let { selectCategory(it.categoryId) }
                        }
                    } else if (_selectedCategoryId.value == null) {
                        categories.firstOrNull()?.let { selectCategory(it.categoryId) }
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load categories")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun buildBrowseIndex() {
        if (indexJob?.isActive == true || _browseIndex.value != null) return
        indexJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                _browseIndex.value = CatalogBrowse.buildVod(visibleCatalog())
            } catch (_: Exception) {
                // Sidebar stays a plain list; nothing else depends on the index.
            }
        }
    }

    /**
     * The whole movie list as this screen may show it: parental blocks applied, and the adult
     * category removed. Adult titles are only ever reachable inside their own category, never
     * through a virtual row that merges categories.
     *
     * Adult detection is by category NAME from the list already on screen (always available, no
     * network wait), plus [AdultContentGuard]'s ids when it has them.
     */
    private suspend fun visibleCatalog(): List<VodStream> = withContext(Dispatchers.Default) {
        val all = catalogCache.vod()
        val adultByName = _categories.value
            .filter { AdultCategoryDetector.isAdultCategory(it.categoryName) }
            .map { it.categoryId }
            .toSet()
        val guard = adultContentGuard.ids.value
        contentFilterManager.filterContent("vod", all) { it.categoryId }
            .filterNot { movie ->
                movie.categoryId in adultByName ||
                    (guard != null && (guard.isAdultCategory("vod", movie.categoryId) ||
                        guard.isAdultItem("vod", movie.streamId)))
            }
    }

    fun selectCategory(categoryId: String) {
        _selectedCategoryId.value = categoryId
        viewModelScope.launch {
            try {
                when {
                    categoryId == FAVORITES_ID -> {
                        val favs = favoriteRepository.getFavoritesListByType("vod")
                        _movies.value = favs.map { fav ->
                            VodStream(
                                num = null,
                                name = fav.name,
                                streamType = "movie",
                                streamId = fav.streamId,
                                streamIcon = fav.icon,
                                rating = null,
                                rating5based = null,
                                added = null,
                                categoryId = fav.categoryId,
                                containerExtension = fav.extra,
                                customSid = null,
                                directSource = null
                            )
                        }
                    }
                    categoryId == RECENTLY_ADDED_ID -> {
                        // A real window, not "newest first": after the 2026-09-21 migration 95% of
                        // the catalog shares one `added` day, so sorting alone showed arbitrary titles.
                        val now = System.currentTimeMillis()
                        _movies.value = withContext(Dispatchers.Default) {
                            visibleCatalog()
                                .filter { CatalogBrowse.withinRecentWindow(it.added, now) }
                                .sortedByDescending { it.added?.toLongOrNull() ?: 0L }
                        }
                    }
                    categoryId == NEW_RELEASES_ID -> {
                        val cutoffYear = Calendar.getInstance().get(Calendar.YEAR) - 1 // last year + this year
                        _movies.value = withContext(Dispatchers.Default) {
                            visibleCatalog()
                                .filter { (CatalogBrowse.titleYear(it.name) ?: 0) >= cutoffYear }
                                .sortedWith(CatalogBrowse.vodDecadeOrder)
                        }
                    }
                    CatalogBrowse.isVirtual(categoryId) -> {
                        _movies.value = withContext(Dispatchers.Default) {
                            visibleCatalog()
                                .filter { CatalogBrowse.matchesVod(categoryId, it) }
                                .sortedWith(CatalogBrowse.vodDecadeOrder)
                        }
                    }
                    else -> {
                        val streams = contentRepository.getVodStreams(categoryId)
                        _movies.value = contentFilterManager.filterContent("vod", streams) { it.categoryId }
                    }
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load movies")
            }
        }
    }

    fun toggleFavorite(movie: VodStream) {
        viewModelScope.launch {
            val id = "vod_${movie.streamId}"
            if (favoriteRepository.isFavorite(id)) {
                favoriteRepository.removeFavorite(id)
                _toastEvent.emit("Removed from Favorites")
            } else {
                favoriteRepository.addFavorite(
                    FavoriteEntity(
                        id = id,
                        streamId = movie.streamId,
                        type = "vod",
                        name = movie.name,
                        icon = movie.streamIcon,
                        categoryId = movie.categoryId,
                        extra = movie.containerExtension
                    )
                )
                _toastEvent.emit("Added to Favorites")
            }
        }
    }

    companion object {
        const val FAVORITES_ID = "__favorites__"
        const val RECENTLY_ADDED_ID = "__recently_added__"
        const val NEW_RELEASES_ID = "__new_releases__"
    }

    fun buildStreamUrl(movie: VodStream): String {
        return contentRepository.buildVodStreamUrl(movie.streamId, movie.containerExtension ?: "mp4")
    }

    /** Load watch progress for the currently displayed movie list */
    fun loadWatchProgress(movieIds: List<Int>) {
        if (movieIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val streamIds = movieIds.map { it.toString() }
                val progressList = watchProgressRepository.getProgressForIds(streamIds)
                _watchProgressMap.value = progressList.associateBy { it.streamId }
            } catch (_: Exception) {
                // Non-critical — silently fail
            }
        }
    }
}
