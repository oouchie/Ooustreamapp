package com.ooustream.iptv.catalog

import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.model.VodStream

/**
 * Virtual "Browse by" categories for the Movies and Series screens, built from the bulk lists.
 *
 * The provider's categories are streaming services (Netflix, Prime, …) with no genre or year
 * grouping, and `get_vod_streams` carries no genre at all — only the title's trailing `(YYYY)`
 * (97.6% of movies) and, for series, the `genre` / `releaseDate` fields in the bulk list. So:
 *  - Movies: decades from the title year.
 *  - Series: genres from the `genre` tokens, decades from `releaseDate`.
 * Everything here is pure filtering of a list that is already in memory ([CatalogCache]); there
 * are no extra requests.
 *
 * Ids are stable strings (`__genre__:Drama`, `__decade__:2010`, `__decade__:classics`) so focus
 * restore and the selected-row highlight work exactly like the existing `__favorites__` rows.
 */
object CatalogBrowse {

    const val GENRE_PREFIX = "__genre__:"
    const val DECADE_PREFIX = "__decade__:"
    const val CLASSICS = "classics"

    const val GROUP_GENRE = "__group_genre__"
    const val GROUP_DECADE = "__group_decade__"
    const val GROUP_MORE = "__group_more__"

    /** A virtual category needs at least this many titles to be offered. */
    const val MIN_ITEMS = 5

    /** A real (service) category with fewer titles than this folds under "More services". */
    const val MAJOR_SERVICE_MIN = 50

    /** "Recently Added" window. Drops are lumpy (a thousand titles one day, nothing for a week). */
    const val RECENT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    /** Movies older than this year fold into one "Classics" entry. */
    const val VOD_CLASSICS_BEFORE = 1970
    const val SERIES_CLASSICS_BEFORE = 1990

    data class Entry(val id: String, val label: String, val count: Int)

    data class Index(
        val genres: List<Entry>,
        val decades: List<Entry>,
        /** Title count per real category id — drives the ≥50 fold and the count pills. */
        val perCategory: Map<String, Int>,
        /** Titles inside [RECENT_WINDOW_MS]. */
        val recentCount: Int
    ) {
        val hasBrowse: Boolean get() = genres.isNotEmpty() || decades.isNotEmpty()
    }

    fun isVirtual(id: String?): Boolean =
        id != null && (id.startsWith(GENRE_PREFIX) || id.startsWith(DECADE_PREFIX))

    fun isGroup(id: String?): Boolean =
        id == GROUP_GENRE || id == GROUP_DECADE || id == GROUP_MORE

    /** The group a virtual id lives under, so the sidebar can auto-expand it when selected. */
    fun groupOf(id: String?): String? = when {
        id == null -> null
        id.startsWith(GENRE_PREFIX) -> GROUP_GENRE
        id.startsWith(DECADE_PREFIX) -> GROUP_DECADE
        else -> null
    }

    // ── Title / date parsing ────────────────────────────────────────────────

    private val TRAILING_YEAR = Regex("""\((19\d{2}|20\d{2})\)\s*$""")
    private val ANY_YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")

    /**
     * Year of a movie from its title. Prefers the provider's trailing `(YYYY)`; otherwise the
     * last plausible 4-digit year anywhere in the name.
     */
    fun titleYear(name: String): Int? {
        TRAILING_YEAR.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return ANY_YEAR.findAll(name).mapNotNull { it.groupValues[1].toIntOrNull() }.lastOrNull()
    }

    fun seriesYear(series: Series): Int? = series.releaseDate?.take(4)?.toIntOrNull()

    private fun decadeOf(year: Int) = year / 10 * 10

    fun decadeLabel(decade: Int) = "${decade}s"

    private fun decadeId(decade: Int) = "$DECADE_PREFIX$decade"
    private val classicsId = "$DECADE_PREFIX$CLASSICS"

    private fun splitGenres(raw: String?): List<String> =
        raw.orEmpty().split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }

    // ── Index building ──────────────────────────────────────────────────────

    fun buildVod(items: List<VodStream>, now: Long = System.currentTimeMillis()): Index {
        val decades = decadeEntries(items.mapNotNull { titleYear(it.name) }, VOD_CLASSICS_BEFORE)
        val perCategory = items.groupingBy { it.categoryId ?: "" }.eachCount()
        val recent = items.count { withinRecentWindow(it.added, now) }
        return Index(genres = emptyList(), decades = decades, perCategory = perCategory, recentCount = recent)
    }

    fun buildSeries(items: List<Series>, now: Long = System.currentTimeMillis()): Index {
        val genreCounts = HashMap<String, Int>()
        for (s in items) for (g in splitGenres(s.genre)) genreCounts[g] = (genreCounts[g] ?: 0) + 1
        val genres = genreCounts.entries
            .filter { it.value >= MIN_ITEMS }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { Entry("$GENRE_PREFIX${it.key}", it.key, it.value) }
        val decades = decadeEntries(items.mapNotNull { seriesYear(it) }, SERIES_CLASSICS_BEFORE)
        val perCategory = items.groupingBy { it.categoryId ?: "" }.eachCount()
        val recent = items.count { withinRecentWindow(it.lastModified, now) }
        return Index(genres = genres, decades = decades, perCategory = perCategory, recentCount = recent)
    }

    private fun decadeEntries(years: List<Int>, classicsBefore: Int): List<Entry> {
        val counts = HashMap<Int, Int>()
        var classics = 0
        for (y in years) {
            if (y < classicsBefore) classics++ else counts[decadeOf(y)] = (counts[decadeOf(y)] ?: 0) + 1
        }
        val entries = counts.entries
            .filter { it.value >= MIN_ITEMS }
            .sortedByDescending { it.key }
            .map { Entry(decadeId(it.key), decadeLabel(it.key), it.value) }
            .toMutableList()
        if (classics >= MIN_ITEMS) entries += Entry(classicsId, "Classics (before $classicsBefore)", classics)
        return entries
    }

    fun withinRecentWindow(epochSeconds: String?, now: Long = System.currentTimeMillis()): Boolean {
        val ts = epochSeconds?.toLongOrNull() ?: return false
        return now - ts * 1000 <= RECENT_WINDOW_MS
    }

    // ── Membership ──────────────────────────────────────────────────────────

    private fun yearMatches(id: String, year: Int?, classicsBefore: Int): Boolean {
        if (year == null) return false
        val tail = id.removePrefix(DECADE_PREFIX)
        return if (tail == CLASSICS) year < classicsBefore
        else year >= classicsBefore && decadeOf(year) == tail.toIntOrNull()
    }

    fun matchesVod(id: String, item: VodStream): Boolean = when {
        id.startsWith(DECADE_PREFIX) -> yearMatches(id, titleYear(item.name), VOD_CLASSICS_BEFORE)
        else -> false
    }

    fun matchesSeries(id: String, item: Series): Boolean = when {
        id.startsWith(GENRE_PREFIX) -> {
            val wanted = id.removePrefix(GENRE_PREFIX)
            splitGenres(item.genre).any { it.equals(wanted, ignoreCase = true) }
        }
        id.startsWith(DECADE_PREFIX) -> yearMatches(id, seriesYear(item), SERIES_CLASSICS_BEFORE)
        else -> false
    }

    /** Sort for a decade grid: newest year first, then best rated. */
    val vodDecadeOrder: Comparator<VodStream> =
        compareByDescending<VodStream> { titleYear(it.name) ?: 0 }
            .thenByDescending { it.rating5based ?: 0.0 }
            .thenBy { it.name }

    val seriesDecadeOrder: Comparator<Series> =
        compareByDescending<Series> { seriesYear(it) ?: 0 }
            .thenByDescending { it.rating5based ?: 0.0 }
            .thenBy { it.name }

    /** Sort for a genre grid: best rated first. */
    val seriesGenreOrder: Comparator<Series> =
        compareByDescending<Series> { it.rating5based ?: 0.0 }.thenBy { it.name }

    // ── Category naming ─────────────────────────────────────────────────────

    private val UNLABELED = Regex("""^\s*unlabel+ed\b""", RegexOption.IGNORE_CASE)

    fun isUnlabeled(categoryName: String) = UNLABELED.containsMatchIn(categoryName)

    /** "Unlabeled Movies" reads as a defect to a customer; it's just titles with no service tag. */
    fun displayName(categoryName: String, section: String): String =
        if (isUnlabeled(categoryName)) {
            if (section == "series") "All Other Series" else "All Other Movies"
        } else categoryName
}
