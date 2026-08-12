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
 * title can't be eaten. Season/episode ARE rendered inline in the title ("The Closer – S1 E2 –
 * About Face") — user decision 2026-08-10: most displays (player title, Watch It Again) have no
 * badge, so the numbers must live in the title text itself. Provider tokens are normalized to
 * "S1 E2" form; caller-supplied season/episode numbers always win over tokens parsed from strings.
 */
object MediaTitleFormatter {

    /** "S01E02", "S1 E2", "S01.E02" — provider token, normalized then re-rendered inline. */
    private val EPISODE_TOKEN = Regex("(?i)\\bS(\\d{1,2})\\s*[. ]?\\s*E(\\d{1,4})\\b")

    /** Trailing catalog year: "The Closer (2005)" → "The Closer". */
    private val TRAILING_YEAR = Regex("\\s*\\((19|20)\\d{2}\\)\\s*$")

    /** Provider segment separator: " - " (hyphen, en dash, em dash). */
    private val SEPARATOR = Regex("\\s+[-–—]\\s+")

    private const val JOIN = " – "

    /**
     * Canonical display title for a series episode: "The Closer – S1 E3 – About Face".
     * Strips every occurrence of the series name (with or without trailing year) from the
     * provider's episode title, normalizes the SxxEyy token into its own "S1 E3" segment
     * (caller-supplied season/episode win over tokens embedded in the provider string), and
     * falls back to "Episode N" when nothing at all is left.
     */
    fun episodeTitle(
        seriesName: String,
        rawEpisodeTitle: String?,
        episodeNum: Int,
        seasonNum: Int = 0
    ): String {
        val series = TRAILING_YEAR.replace(seriesName.trim(), "").trim().ifBlank { seriesName.trim() }
        var ep = rawEpisodeTitle?.trim().orEmpty()
        if (seriesName.isNotBlank()) {
            ep = Regex("(?i)" + Regex.escape(seriesName.trim())).replace(ep, " ")
        }
        if (series.isNotBlank() && !series.equals(seriesName.trim(), ignoreCase = true)) {
            ep = Regex("(?i)" + Regex.escape(series)).replace(ep, " ")
        }
        val token = resolveToken(seasonNum, episodeNum, lastTokenIn(ep))
        ep = EPISODE_TOKEN.replace(ep, " ")
        val epText = tidySegments(ep).joinToString(JOIN)
        val parts = buildList {
            if (series.isNotBlank()) add(series)
            if (token.isNotBlank()) add(token)
            if (epText.isNotBlank()) add(epText)
        }
        return parts.joinToString(JOIN).ifBlank { "Episode $episodeNum" }
    }

    /**
     * Display-time cleanup for titles we didn't just build — legacy watch_progress names, provider
     * VOD names. Removes exact duplicate segments (case-insensitive, keeps first occurrence).
     * Legacy compounded series strings ("Series – StaleEpTitle – CurrentEpTitle") collapse to
     * first + last segment — the current episode's title is always the final appended piece.
     * For series the SxxEyy token is normalized and re-rendered as its own segment right after
     * the series name ("The Closer – S1 E2 – About Face"): caller-supplied season/episode win;
     * otherwise the LAST token in the raw string is used (the current episode is the final
     * appended piece in legacy compounds). Idempotent — cleaning an already-clean title is a no-op.
     */
    fun cleanDisplayTitle(
        raw: String,
        isSeries: Boolean = false,
        seasonNum: Int = 0,
        episodeNum: Int = 0
    ): String {
        if (raw.isBlank()) return raw
        val token = if (isSeries) resolveToken(seasonNum, episodeNum, lastTokenIn(raw)) else ""
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
        if (token.isNotBlank()) {
            segments = if (segments.size <= 1) segments + token
            else listOf(segments.first(), token) + segments.drop(1)
        }
        return segments.joinToString(JOIN).ifBlank { raw.trim() }
    }

    /** "S1 E3" when both known, "E3" when only the episode is, "" otherwise. */
    private fun seToken(season: Int, episode: Int): String = when {
        season > 0 && episode > 0 -> "S$season E$episode"
        episode > 0 -> "E$episode"
        else -> ""
    }

    /**
     * Full caller info wins; a raw-string token (which always carries a season) beats a
     * season-less caller token ("E5"); partial caller info is the last resort.
     */
    private fun resolveToken(seasonNum: Int, episodeNum: Int, rawToken: String): String = when {
        seasonNum > 0 && episodeNum > 0 -> seToken(seasonNum, episodeNum)
        rawToken.isNotBlank() -> rawToken
        else -> seToken(seasonNum, episodeNum)
    }

    /** Normalized form of the LAST SxxEyy token in [s], or "" when none present. */
    private fun lastTokenIn(s: String): String {
        val m = EPISODE_TOKEN.findAll(s).lastOrNull() ?: return ""
        return seToken(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /** Split on separators, trim residue punctuation left behind by removals, drop empties. */
    private fun tidySegments(s: String): List<String> {
        return s.split(SEPARATOR)
            .map { it.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '·', '.', ',') }
            .filter { it.isNotEmpty() }
    }
}
