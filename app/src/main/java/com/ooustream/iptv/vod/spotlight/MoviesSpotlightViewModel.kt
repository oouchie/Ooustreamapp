package com.ooustream.iptv.vod.spotlight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.catalog.CatalogBrowse
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.model.VodStream
import com.ooustream.iptv.data.repository.CatalogCache
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.WatchProgressRepository
import com.ooustream.iptv.parental.AdultCategoryDetector
import com.ooustream.iptv.parental.AdultContentGuard
import com.ooustream.iptv.parental.ContentFilterManager
import com.ooustream.iptv.vod.VodViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** One poster on the Spotlight screen. Only fields the bulk movie list really carries. */
data class SpotlightMovie(
    val id: Int,
    val name: String,
    val poster: String?,
    val year: Int?,
    val rating: Double?,
    val ext: String?
)

/** The same film is often listed twice ("4K Sisu" and "Sisu"); collapse those in a row. */
internal val SpotlightMovie.dedupeKey: String
    get() = CatalogBrowse.sameTitleKey(name)

/** [browseId] is the category the full Movies grid opens on for this row's "See all". */
data class SpotlightRow(
    val key: String,
    val title: String,
    val items: List<SpotlightMovie>,
    val browseId: String
)

/** Hero text for the focused movie. The extra fields come from get_vod_info and may be null. */
data class SpotlightHero(
    val movie: SpotlightMovie,
    val backdrop: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val duration: String? = null
)

/**
 * Movies "Spotlight + Rows" (design B). Trial screen on the Kotlin 2 branch.
 *
 * The provider's bulk movie list has no genre, runtime or plot, so the rows are built only from
 * what it does carry: the `added` timestamp, the title year, the rating and the service category.
 * The hero fills in plot/genre/backdrop lazily from get_vod_info for whichever card has focus.
 */
@HiltViewModel
class MoviesSpotlightViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val contentFilterManager: ContentFilterManager,
    private val catalogCache: CatalogCache,
    private val adultContentGuard: AdultContentGuard,
    private val watchProgressRepository: WatchProgressRepository
) : ViewModel() {

    private val _rows = MutableStateFlow<List<SpotlightRow>>(emptyList())
    val rows: StateFlow<List<SpotlightRow>> = _rows.asStateFlow()

    private val _hero = MutableStateFlow<SpotlightHero?>(null)
    val hero: StateFlow<SpotlightHero?> = _hero.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val infoCache = ConcurrentHashMap<Int, SpotlightHero>()
    private var heroJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val categories = contentFilterManager.filterCategories(
                    "vod", contentRepository.getVodCategories()
                )
                val built = withContext(Dispatchers.Default) {
                    buildRows(categories, visibleCatalog(categories))
                }
                _rows.value = built
                built.firstOrNull()?.items?.firstOrNull()?.let { focus(it, immediate = true) }
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't load movies"
            } finally {
                _loading.value = false
            }
        }
    }

    /** Same exclusions as VodViewModel.visibleCatalog(): parental blocks, then adult by name + guard. */
    private suspend fun visibleCatalog(categories: List<Category>): List<VodStream> {
        val all = catalogCache.vod()
        val adultByName = categories
            .filter { AdultCategoryDetector.isAdultCategory(it.categoryName) }
            .map { it.categoryId }
            .toSet()
        val guard = adultContentGuard.ids.value
        val allowedCategories = categories.map { it.categoryId }.toSet()
        return contentFilterManager.filterContent("vod", all) { it.categoryId }
            .filter { it.categoryId in allowedCategories }
            .filterNot { movie ->
                movie.categoryId in adultByName ||
                    (guard != null && (guard.isAdultCategory("vod", movie.categoryId) ||
                        guard.isAdultItem("vod", movie.streamId)))
            }
    }

    private fun buildRows(categories: List<Category>, catalog: List<VodStream>): List<SpotlightRow> {
        // Decorate once: parsing the year/rating inside a comparator would parse O(n log n) times.
        val movies = catalog.map { it to it.toSpotlight() }
        val rows = mutableListOf<SpotlightRow>()

        val recent = movies
            .filter { CatalogBrowse.withinRecentWindow(it.first.added) }
            .sortedByDescending { it.first.added?.toLongOrNull() ?: 0L }
            .map { it.second }
            .distinctBy { it.dedupeKey }
            .take(ROW_SIZE)
        if (recent.isNotEmpty()) rows += SpotlightRow("recent", "Recently Added", recent, VodViewModel.RECENTLY_ADDED_ID)

        rows += topRated("top2020s", "Top Rated · 2020s", "${CatalogBrowse.DECADE_PREFIX}2020", movies) { (it.year ?: 0) in 2020..2029 }

        val byCategory = movies.groupBy { it.first.categoryId }
        val services = categories
            .filterNot { CatalogBrowse.isUnlabeled(it.categoryName) }
            .map { it to (byCategory[it.categoryId]?.size ?: 0) }
            .filter { it.second >= MIN_SERVICE_SIZE }
            .sortedByDescending { it.second }
            .take(SERVICE_ROWS)
        for ((category, _) in services) {
            val items = byCategory[category.categoryId].orEmpty()
                .map { it.second }
                .sortedWith(compareByDescending<SpotlightMovie> { it.year ?: 0 }.thenByDescending { it.rating ?: 0.0 })
                .distinctBy { it.dedupeKey }
                .take(ROW_SIZE)
            rows += SpotlightRow(
                "cat:${category.categoryId}",
                "New on ${CatalogBrowse.displayName(category.categoryName, "vod")}",
                items,
                category.categoryId
            )
        }

        for (decade in listOf(2010, 2000, 1990)) {
            rows += topRated("decade$decade", "Best of the ${decade}s", "${CatalogBrowse.DECADE_PREFIX}$decade", movies) {
                (it.year ?: 0) in decade..decade + 9
            }
        }
        return rows.filter { it.items.isNotEmpty() }
    }

    private fun topRated(
        key: String,
        title: String,
        browseId: String,
        movies: List<Pair<VodStream, SpotlightMovie>>,
        where: (SpotlightMovie) -> Boolean
    ): SpotlightRow = SpotlightRow(
        key, title,
        movies.asSequence().map { it.second }
            // A flat 10.0 looks like the panel's default for unrated titles, not a real score.
            .filter { where(it) && (it.rating ?: 0.0) in 0.1..9.9 }
            .sortedByDescending { it.rating }
            .distinctBy { it.dedupeKey }
            .take(ROW_SIZE)
            .toList(),
        browseId
    )

    private fun VodStream.toSpotlight() = SpotlightMovie(
        id = streamId,
        name = name,
        poster = streamIcon?.takeIf { it.isNotBlank() },
        year = CatalogBrowse.titleYear(name),
        rating = rating?.toDoubleOrNull()?.takeIf { it > 0.0 },
        ext = containerExtension
    )

    /**
     * Point the hero at [movie]. The title/year/rating show at once; plot/backdrop arrive after a
     * short dwell so scrolling quickly along a row doesn't fire a get_vod_info per card.
     */
    fun focus(movie: SpotlightMovie, immediate: Boolean = false) {
        if (_hero.value?.movie?.id == movie.id) return
        heroJob?.cancel()
        _hero.value = infoCache[movie.id] ?: SpotlightHero(movie)
        if (infoCache.containsKey(movie.id)) return
        heroJob = viewModelScope.launch {
            if (!immediate) delay(HERO_DWELL_MS)
            val full = try {
                val info = withContext(Dispatchers.IO) { contentRepository.getVodInfo(movie.id) }.info
                SpotlightHero(
                    movie = movie,
                    backdrop = info?.backdropPath?.firstOrNull { it.isNotBlank() },
                    plot = info?.plot?.trim()?.takeIf { it.isNotEmpty() },
                    genre = info?.genre?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
                    duration = formatRuntime(info?.durationSecs, info?.duration)
                )
            } catch (_: Exception) {
                return@launch
            }
            infoCache[movie.id] = full
            if (_hero.value?.movie?.id == movie.id) _hero.value = full
        }
    }

    fun buildStreamUrl(movie: SpotlightMovie): String =
        contentRepository.buildVodStreamUrl(movie.id, movie.ext ?: "mp4")

    suspend fun progressFor(movie: SpotlightMovie): WatchProgressEntity? =
        watchProgressRepository.getProgress(movie.id.toString())

    /** "01:52:00" / 6720s → "1h 52m". */
    private fun formatRuntime(secs: Int?, raw: String?): String? {
        val total = secs?.takeIf { it > 0 } ?: raw?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 3 }?.let { (h, m, s) -> h * 3600 + m * 60 + s }
            ?: return raw?.takeIf { it.isNotBlank() }
        val h = total / 3600
        val m = (total % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private companion object {
        const val ROW_SIZE = 20
        const val SERVICE_ROWS = 4
        const val MIN_SERVICE_SIZE = 50
        const val HERO_DWELL_MS = 450L
    }
}
