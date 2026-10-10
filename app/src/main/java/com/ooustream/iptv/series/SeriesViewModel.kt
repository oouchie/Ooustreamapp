package com.ooustream.iptv.series

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.catalog.CatalogBrowse
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.repository.CatalogCache
import com.ooustream.iptv.data.repository.ContentCacheRepository
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
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
import javax.inject.Inject

@HiltViewModel
class SeriesViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val contentCacheRepository: ContentCacheRepository,
    private val favoriteRepository: FavoriteRepository,
    private val contentFilterManager: ContentFilterManager,
    private val catalogCache: CatalogCache,
    private val adultContentGuard: AdultContentGuard
) : BaseViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _seriesList = MutableStateFlow<List<Series>>(emptyList())
    val seriesList: StateFlow<List<Series>> = _seriesList.asStateFlow()

    private val _selectedCategoryId = MutableStateFlow<String?>(FAVORITES_ID)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    val favorites = favoriteRepository.getFavoritesByType("series")

    /** Genre + decade entries and per-service counts for the sidebar; null until the bulk list has loaded. */
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
                contentCacheRepository.getCategories("series").collect { categories ->
                    _categories.value = contentFilterManager.filterCategories("series", categories)
                    buildBrowseIndex()
                    if (_selectedCategoryId.value == FAVORITES_ID) {
                        val favCount = favoriteRepository.getFavoritesListByType("series").size
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
                _browseIndex.value = CatalogBrowse.buildSeries(visibleCatalog())
            } catch (_: Exception) {
                // Sidebar stays a plain list; nothing else depends on the index.
            }
        }
    }

    /**
     * The whole series list as this screen may show it: parental blocks applied, adult
     * categories removed (by category NAME from the loaded list, plus [AdultContentGuard] ids when
     * known). Adult titles never surface through a virtual row that merges categories.
     */
    private suspend fun visibleCatalog(): List<Series> = withContext(Dispatchers.Default) {
        val all = catalogCache.series()
        val adultByName = _categories.value
            .filter { AdultCategoryDetector.isAdultCategory(it.categoryName) }
            .map { it.categoryId }
            .toSet()
        val guard = adultContentGuard.ids.value
        contentFilterManager.filterContent("series", all) { it.categoryId }
            .filterNot { s ->
                s.categoryId in adultByName ||
                    (guard != null && (guard.isAdultCategory("series", s.categoryId) ||
                        guard.isAdultItem("series", s.seriesId)))
            }
    }

    fun selectCategory(categoryId: String) {
        _selectedCategoryId.value = categoryId
        viewModelScope.launch {
            try {
                when {
                    categoryId == FAVORITES_ID -> {
                        val favs = favoriteRepository.getFavoritesListByType("series")
                        _seriesList.value = favs.map { fav ->
                            Series(
                                num = null,
                                name = fav.name,
                                seriesId = fav.streamId,
                                cover = fav.icon,
                                plot = null,
                                cast = null,
                                director = null,
                                genre = null,
                                releaseDate = null,
                                lastModified = null,
                                rating = null,
                                rating5based = null,
                                backdropPath = null,
                                youtubeTrailer = null,
                                episodeRunTime = null,
                                categoryId = fav.categoryId
                            )
                        }
                    }
                    categoryId == RECENTLY_ADDED_ID -> {
                        // A real window, not "newest first" — see VodViewModel for why.
                        val now = System.currentTimeMillis()
                        _seriesList.value = withContext(Dispatchers.Default) {
                            visibleCatalog()
                                .filter { CatalogBrowse.withinRecentWindow(it.lastModified, now) }
                                .sortedByDescending { it.lastModified?.toLongOrNull() ?: 0L }
                        }
                    }
                    CatalogBrowse.isVirtual(categoryId) -> {
                        val order = if (categoryId.startsWith(CatalogBrowse.GENRE_PREFIX)) {
                            CatalogBrowse.seriesGenreOrder
                        } else {
                            CatalogBrowse.seriesDecadeOrder
                        }
                        _seriesList.value = withContext(Dispatchers.Default) {
                            visibleCatalog()
                                .filter { CatalogBrowse.matchesSeries(categoryId, it) }
                                .sortedWith(order)
                        }
                    }
                    else -> {
                        val series = contentRepository.getSeries(categoryId)
                        _seriesList.value = contentFilterManager.filterContent("series", series) { it.categoryId }
                    }
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load series")
            }
        }
    }

    fun toggleFavorite(series: Series) {
        viewModelScope.launch {
            val id = "series_${series.seriesId}"
            if (favoriteRepository.isFavorite(id)) {
                favoriteRepository.removeFavorite(id)
                _toastEvent.emit("Removed from Favorites")
            } else {
                favoriteRepository.addFavorite(
                    FavoriteEntity(
                        id = id,
                        streamId = series.seriesId,
                        type = "series",
                        name = series.name,
                        icon = series.cover,
                        categoryId = series.categoryId,
                        extra = null
                    )
                )
                _toastEvent.emit("Added to Favorites")
            }
        }
    }

    companion object {
        const val FAVORITES_ID = "__favorites__"
        const val RECENTLY_ADDED_ID = "__recently_added__"
    }
}
