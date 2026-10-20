package com.ooustream.iptv.series.spotlight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.catalog.CatalogBrowse
import com.ooustream.iptv.data.local.dao.SeriesTrackingDao
import com.ooustream.iptv.data.local.entity.SeriesTrackingEntity
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.repository.CatalogCache
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.WatchProgressRepository
import com.ooustream.iptv.data.repository.resumeStreamUrl
import com.ooustream.iptv.parental.AdultCategoryDetector
import com.ooustream.iptv.parental.AdultContentGuard
import com.ooustream.iptv.parental.ContentFilterManager
import com.ooustream.iptv.series.SeriesViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One show on the Series Spotlight screen, from the bulk series list (which is rich). */
data class SpotlightShow(
    val id: Int,
    val name: String,
    val cover: String?,
    val backdrop: String?,
    val year: Int?,
    val genre: String?,
    val rating: Double?,
    val plot: String?
)

/** Where the viewer is in a show: an episode in progress, or new episodes since the last watched. */
sealed interface ShowState {
    data class InProgress(val item: WatchProgressEntity) : ShowState
    data class NewEpisodes(val tracking: SeriesTrackingEntity) : ShowState
}

/** [browseId] is the category the full Series grid opens on for "See all"; null hides it. */
data class ShowRow(val key: String, val title: String, val items: List<SpotlightShow>, val browseId: String?)

/**
 * Series "Spotlight + Rows" (design B, "series 1"). Trial screen on the Kotlin 2 branch.
 *
 * Unlike movies, the bulk series list carries plot, genre, release date, rating and backdrop, so
 * the hero never needs a per-title request. Keep Watching leads; everything is filtered through
 * parental blocks and adult exclusion before any row is built — history rows included, by only
 * keeping shows that are in the visible catalogue.
 */
@HiltViewModel
class SeriesSpotlightViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val contentFilterManager: ContentFilterManager,
    private val catalogCache: CatalogCache,
    private val adultContentGuard: AdultContentGuard,
    private val watchProgressRepository: WatchProgressRepository,
    private val seriesTrackingDao: SeriesTrackingDao
) : ViewModel() {

    private val _rows = MutableStateFlow<List<ShowRow>>(emptyList())
    val rows: StateFlow<List<ShowRow>> = _rows.asStateFlow()

    private val _hero = MutableStateFlow<SpotlightShow?>(null)
    val hero: StateFlow<SpotlightShow?> = _hero.asStateFlow()

    /** Keep Watching state per series id, for the hero's resume line and the card captions. */
    private val _states = MutableStateFlow<Map<Int, ShowState>>(emptyMap())
    val states: StateFlow<Map<Int, ShowState>> = _states.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Null until the catalogue loads, so history rows never show before they can be filtered. */
    private val visible = MutableStateFlow<Map<Int, SpotlightShow>?>(null)
    private var catalogRows: List<ShowRow> = emptyList()

    init {
        load()
        observeHistory()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val categories = contentFilterManager.filterCategories(
                    "series", contentRepository.getSeriesCategories()
                )
                val (shows, built) = withContext(Dispatchers.Default) {
                    val catalog = visibleCatalog(categories)
                    catalog.associate { it.seriesId to it.toShow() } to buildRows(categories, catalog)
                }
                catalogRows = built
                // observeHistory publishes once Keep Watching is known, so rows never shift under focus.
                visible.value = shows
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't load series"
            } finally {
                _loading.value = false
            }
        }
    }

    /** Keep Watching: shows with an episode in progress, then shows with new episodes. */
    private fun observeHistory() {
        viewModelScope.launch {
            combine(
                watchProgressRepository.getContinueWatching(),
                seriesTrackingDao.getSeriesWithNewEpisodes(),
                visible.filterNotNull()
            ) { progress, fresh, shows -> Triple(progress, fresh, shows) }
                .collect { (progress, fresh, shows) ->
                    val states = LinkedHashMap<Int, ShowState>()
                    progress.filter { it.type == "series" && it.seriesId != null }
                        .sortedByDescending { it.lastWatched }
                        .forEach { if (it.seriesId!! !in states) states[it.seriesId] = ShowState.InProgress(it) }
                    fresh.forEach { if (it.seriesId !in states) states[it.seriesId] = ShowState.NewEpisodes(it) }
                    // Only shows the visible catalogue holds: that is the parental + adult filter.
                    val kept = states.filterKeys { it in shows }
                    _states.value = kept
                    val items = kept.keys.mapNotNull { shows[it] }.take(ROW_SIZE)
                    publish(if (items.isEmpty()) null else ShowRow(KEEP_WATCHING, "Keep Watching", items, null))
                }
        }
    }

    private fun publish(keepWatching: ShowRow?) {
        _rows.value = listOfNotNull(keepWatching) + catalogRows
        if (_hero.value == null) _rows.value.firstOrNull()?.items?.firstOrNull()?.let { _hero.value = it }
    }

    /** Same exclusions as SeriesViewModel.visibleCatalog(): parental blocks, then adult by name + guard. */
    private suspend fun visibleCatalog(categories: List<Category>): List<Series> {
        val all = catalogCache.series()
        val adultByName = categories
            .filter { AdultCategoryDetector.isAdultCategory(it.categoryName) }
            .map { it.categoryId }
            .toSet()
        val guard = adultContentGuard.ids.value
        val allowedCategories = categories.map { it.categoryId }.toSet()
        return contentFilterManager.filterContent("series", all) { it.categoryId }
            .filter { it.categoryId in allowedCategories }
            .filterNot { s ->
                s.categoryId in adultByName ||
                    (guard != null && (guard.isAdultCategory("series", s.categoryId) ||
                        guard.isAdultItem("series", s.seriesId)))
            }
    }

    private fun buildRows(categories: List<Category>, catalog: List<Series>): List<ShowRow> {
        // Decorate once: parsing year/rating inside a comparator would parse O(n log n) times.
        val shows = catalog.map { it to it.toShow() }
        val rows = mutableListOf<ShowRow>()

        val recent = shows
            .filter { CatalogBrowse.withinRecentWindow(it.first.lastModified) }
            .sortedByDescending { it.first.lastModified?.toLongOrNull() ?: 0L }
            .map { it.second }
            .distinctBy { CatalogBrowse.sameTitleKey(it.name) }
            .take(ROW_SIZE)
        if (recent.isNotEmpty()) rows += ShowRow("recent", "Recently Added", recent, SeriesViewModel.RECENTLY_ADDED_ID)

        rows += topRated("top2020s", "Top Rated · 2020s", "${CatalogBrowse.DECADE_PREFIX}2020", shows) { (it.year ?: 0) in 2020..2029 }

        val index = CatalogBrowse.buildSeries(catalog)
        for (genre in index.genres.take(GENRE_ROWS)) {
            val name = genre.label
            rows += topRated("genre:$name", name, genre.id, shows) { s ->
                s.genre?.split(',', '/')?.any { it.trim() == name } == true
            }
        }

        val byCategory = shows.groupBy { it.first.categoryId }
        val services = categories
            .filterNot { CatalogBrowse.isUnlabeled(it.categoryName) }
            .map { it to (byCategory[it.categoryId]?.size ?: 0) }
            .filter { it.second >= MIN_SERVICE_SIZE }
            .sortedByDescending { it.second }
            .take(SERVICE_ROWS)
        for ((category, _) in services) {
            val items = byCategory[category.categoryId].orEmpty()
                .sortedByDescending { it.first.lastModified?.toLongOrNull() ?: 0L }
                .map { it.second }
                .distinctBy { CatalogBrowse.sameTitleKey(it.name) }
                .take(ROW_SIZE)
            rows += ShowRow(
                "cat:${category.categoryId}",
                "New on ${CatalogBrowse.displayName(category.categoryName, "series")}",
                items,
                category.categoryId
            )
        }

        rows += topRated("decade2010", "Best of the 2010s", "${CatalogBrowse.DECADE_PREFIX}2010", shows) { (it.year ?: 0) in 2010..2019 }
        return rows.filter { it.items.isNotEmpty() }
    }

    private fun topRated(
        key: String,
        title: String,
        browseId: String,
        shows: List<Pair<Series, SpotlightShow>>,
        where: (SpotlightShow) -> Boolean
    ): ShowRow = ShowRow(
        key, title,
        shows.asSequence().map { it.second }
            // A flat 10.0 looks like the panel's default for unrated titles, not a real score.
            .filter { where(it) && (it.rating ?: 0.0) in 0.1..9.9 }
            .sortedByDescending { it.rating }
            .distinctBy { CatalogBrowse.sameTitleKey(it.name) }
            .take(ROW_SIZE)
            .toList(),
        browseId
    )

    private fun Series.toShow() = SpotlightShow(
        id = seriesId,
        name = CatalogBrowse.withoutTrailingYear(name),
        cover = cover?.takeIf { it.isNotBlank() },
        backdrop = backdropPath?.firstOrNull { it.isNotBlank() },
        year = CatalogBrowse.seriesYear(this),
        genre = genre?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2)?.joinToString(" · ")
            ?.takeIf { it.isNotEmpty() },
        rating = rating?.toDoubleOrNull()?.takeIf { it > 0.0 },
        plot = plot?.trim()?.takeIf { it.isNotEmpty() }
    )

    fun focus(show: SpotlightShow) {
        if (_hero.value?.id != show.id) _hero.value = show
    }

    fun resumeUrl(item: WatchProgressEntity): String? = contentRepository.resumeStreamUrl(item)

    companion object {
        const val KEEP_WATCHING = "keep"
        private const val ROW_SIZE = 20
        private const val GENRE_ROWS = 4
        private const val SERVICE_ROWS = 3
        private const val MIN_SERVICE_SIZE = 50
    }
}
