package com.ooustream.iptv.common

/**
 * Single source of truth for user-facing content titles.
 *
 * IPTV providers ship episode titles that already embed the series name and episode token
 * ("The Closer (2005) - S01E02 - About Face"), and the binge-advance path used to prepend the
 * series name again — worst case the previous display title — producing strings like
 * "The Closer (2005) - The Closer (2005) - S01E03 - The Big Picture - The Closer (2005) - S01E02 - About Face"
 * which were then persisted into watch_progress and re-served by Continue Watching forever.
 *
 * Every rule here is deliberately conservative: only exact case-insensitive series-name matches,
 * explicit SxxEyy tokens, and exact duplicate " - " segments are ever removed, so a legitimate
 * title can't be eaten. Season/episode are rendered as separate badges everywhere (controls bar
 * "S1 E2", Continue Watching "S1 E2 · Resume"), so the title text never needs them inline.
 */
object MediaTitleFormatter {

    /** "S01E02", "S1 E2", "S01.E02" — the token the badges already display. */
    private val EPISODE_TOKEN = Regex("(?i)\\bS\\d{1,2}\\s*[. ]?\\s*E\\d{1,4}\\b")

    /** Trailing catalog year: "The Closer (2005)" → "The Closer". */
    private val TRAILING_YEAR = Regex("\\s*\\((19|20)\\d{2}\\)\\s*$")

    /** Provider segment separator: " - " (hyphen, en dash, em dash). */
    private val SEPARATOR = Regex("\\s+[-–—]\\s+")

    private const val JOIN = " – "

    /**
     * Canonical display title for a series episode: "The Closer – About Face".
     * Strips every occurrence of the series name (with or without trailing year) and any SxxEyy
     * token from the provider's episode title; falls back to "Episode N" when nothing is left.
     */
    fun episodeTitle(seriesName: String, rawEpisodeTitle: String?, episodeNum: Int): String {
        val series = TRAILING_YEAR.replace(seriesName.trim(), "").trim().ifBlank { seriesName.trim() }
        var ep = rawEpisodeTitle?.trim().orEmpty()
        if (seriesName.isNotBlank()) {
            ep = Regex("(?i)" + Regex.escape(seriesName.trim())).replace(ep, " ")
        }
        if (series.isNotBlank() && !series.equals(seriesName.trim(), ignoreCase = true)) {
            ep = Regex("(?i)" + Regex.escape(series)).replace(ep, " ")
        }
        ep = EPISODE_TOKEN.replace(ep, " ")
        ep = tidySegments(ep).joinToString(JOIN).ifBlank { "Episode $episodeNum" }
        return if (series.isBlank()) ep else "$series$JOIN$ep"
    }

    /**
     * Display-time cleanup for titles we didn't just build — legacy watch_progress names, provider
     * VOD names. Removes exact duplicate segments (case-insensitive, keeps first occurrence) and,
     * for series, the SxxEyy tokens the badge already shows. Legacy compounded series strings
     * ("Series – StaleEpTitle – CurrentEpTitle") collapse to first + last segment — the current
     * episode's title is always the final appended piece.
     */
    fun cleanDisplayTitle(raw: String, isSeries: Boolean = false): String {
        if (raw.isBlank()) return raw
        val work = if (isSeries) EPISODE_TOKEN.replace(raw, " ") else raw
        var segments = tidySegments(work)
        if (segments.isEmpty()) return raw.trim()
        // Dedupe exact repeats, keeping first occurrence.
        val seen = HashSet<String>()
        segments = segments.filter { seen.add(it.lowercase()) }
        // Compounded legacy series names carry stale middle segments; keep series + current episode.
        if (isSeries && segments.size > 2) {
            segments = listOf(segments.first(), segments.last())
        }
        if (isSeries) {
            segments = segments.mapIndexed { i, s ->
                if (i == 0) TRAILING_YEAR.replace(s, "").trim().ifBlank { s } else s
            }
        }
        return segments.joinToString(JOIN).ifBlank { raw.trim() }
    }

    /** Split on separators, trim residue punctuation left behind by removals, drop empties. */
    private fun tidySegments(s: String): List<String> {
        return s.split(SEPARATOR)
            .map { it.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '·', '.', ',') }
            .filter { it.isNotEmpty() }
    }
}
