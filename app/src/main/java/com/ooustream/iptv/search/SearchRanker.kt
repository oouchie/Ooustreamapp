package com.ooustream.iptv.search

import java.text.Normalizer

/**
 * Ranks catalogue titles for a search query. Pure Kotlin, no Android imports (JVM unit-tested:
 * `./gradlew :app:testDebugUnitTest --tests '*SearchRankerTest'`).
 *
 * Why (2026-11-07): Search used a raw `contains` and the row showed the first 60 matches in
 * catalogue order, which is `added` ASCENDING — the 60 oldest matches. "Scream" had 147 matches
 * and "Scream (1996)" was #122, so it never reached the screen (customer Cyrus Snell asked for it
 * through the portal's title requests). A query carrying the year — "Scream 1996" — matched nothing,
 * because the catalogue writes "Scream (1996)". And "Jay Z" missed "JAŸ-Z in 8".
 *
 * Rules:
 *  - Match on NORMALISED text (lower-case, diacritics folded, punctuation → space), so
 *    "jay z" ⊇ "JAŸ-Z", "spider man" ⊇ "Spider-Man", "doctor who 2005" ⊇ "Doctor Who (2005)".
 *  - A trailing year in the query is parsed out and used to PREFER a title with that year.
 *    Titles with a different year still match, ranked lower. A query that is only a year
 *    ("2012") is treated as text.
 *  - Score tiers: exact title > title starts with the query > query at a word boundary >
 *    plain substring. Then: year match, the plain copy ahead of its "4K:" / "4KDV:" twins,
 *    newer release year, newer `added`.
 *  - Callers keep their own cap (60 cards); only the ORDER changes, so the heap cost is unchanged.
 *
 * Cost: one pass over the list per query. ASCII titles (≈98% of the catalogue) take a hand-rolled
 * path with no regex and no Normalizer; a cheap `contains` on the first query token rejects most
 * of them before any allocation. 135k synthetic titles: ~10–15 ms on an M-series Mac.
 */
object SearchRanker {

    class Query(val raw: String, val text: String, val year: Int?, firstToken: String) {
        /** Cheap pre-filter token: a normalised match implies the raw name contains it (ASCII titles). */
        val probe: String = firstToken
        val isEmpty: Boolean get() = text.isEmpty()
    }

    /** One parsed catalogue name. */
    class Title(val base: String, val year: Int?, val qualityPrefixed: Boolean)

    private class Hit<T>(val item: T, val score: Int, val titleYear: Int?, val added: Long)

    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val COMBINING = Regex("""\p{M}+""")
    private val QUERY_YEAR = Regex("""\s*\(?\b((?:19|20)\d{2})\)?\s*$""")

    /** Lower-case, fold diacritics, punctuation → single spaces, trimmed. */
    fun normalize(s: String): String {
        if (isAscii(s)) return normalizeAscii(s, 0, s.length)
        val folded = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(COMBINING, "")
        return folded.lowercase().replace(NON_ALNUM, " ").trim()
    }

    fun parseQuery(raw: String): Query {
        val trimmed = raw.trim()
        val m = QUERY_YEAR.find(trimmed)
        var year: Int? = null
        var textPart = trimmed
        if (m != null && m.range.first > 0) {
            // "scream 1996" / "scream (1996)" — only when there is text before the year.
            year = m.groupValues[1].toInt()
            textPart = trimmed.substring(0, m.range.first)
        }
        var text = normalize(textPart)
        if (text.isEmpty()) {
            // Nothing but a year (or punctuation): search it as plain text.
            text = normalize(trimmed)
            year = null
        }
        return Query(trimmed, text, year, text.substringBefore(' '))
    }

    /**
     * Split a catalogue name into its normalised base, trailing "(YYYY)" year and whether it
     * carries a quality prefix ("4K: ", "4KDV: ", "HD: " …).
     */
    fun parseTitle(name: String): Title {
        var end = name.length
        while (end > 0 && name[end - 1] == ' ') end--
        // Trailing "(YYYY)"
        var year: Int? = null
        if (end >= 6 && name[end - 1] == ')' && name[end - 6] == '(' &&
            name[end - 5].isAsciiDigit() && name[end - 4].isAsciiDigit() && name[end - 3].isAsciiDigit() && name[end - 2].isAsciiDigit()
        ) {
            val y = (name[end - 5] - '0') * 1000 + (name[end - 4] - '0') * 100 + (name[end - 3] - '0') * 10 + (name[end - 2] - '0')
            if (y in 1900..2099) {
                year = y
                end -= 6
                while (end > 0 && name[end - 1] == ' ') end--
            }
        }
        // Leading quality prefix up to the first ':' (only the known tags)
        var start = 0
        val colon = name.indexOf(':')
        if (colon in 2..5) {
            val tag = name.substring(0, colon).trim().lowercase()
            if (tag == "4k" || tag == "4kdv" || tag == "uhd" || tag == "fhd" || tag == "hd" || tag == "sd") {
                start = colon + 1
                while (start < end && name[start] == ' ') start++
            }
        }
        val qualityPrefixed = start > 0
        val base = if (isAscii(name)) normalizeAscii(name, start, end) else normalize(name.substring(start, end))
        return Title(base, year, qualityPrefixed)
    }

    /** Does [name] match the query at all (title text only, no cast)? */
    fun titleMatches(name: String, q: Query): Boolean = titleScore(name, q) > 0

    /** 0 = no match. Otherwise the tier score for the TITLE text (before year/added tie-breaks). */
    fun titleScore(name: String, q: Query): Int = tierScore(parseTitle(name).base, q)

    private fun tierScore(base: String, q: Query): Int {
        if (q.isEmpty || base.isEmpty()) return 0
        val idx = base.indexOf(q.text)
        if (idx < 0) return 0
        val endIdx = idx + q.text.length
        return when {
            base.length == q.text.length -> 400                                            // exact
            idx == 0 && base[endIdx] == ' ' -> 300                                         // starts with, whole words
            idx > 0 && base[idx - 1] == ' ' && (endIdx == base.length || base[endIdx] == ' ') -> 200 // word boundary
            else -> 100                                                                    // substring ("screamers")
        }
    }

    /**
     * Filter + rank. [extraMatch] admits items the title test misses (cast / director); they score
     * below every title match so they sit at the end of the row, as before.
     */
    fun <T> rank(
        items: List<T>,
        q: Query,
        name: (T) -> String,
        added: (T) -> Long,
        extraMatch: (T) -> Boolean = { false }
    ): List<T> {
        if (q.isEmpty) return emptyList()
        val hits = ArrayList<Hit<T>>()
        for (item in items) {
            val n = name(item)
            // Fast reject for ASCII titles: the first query token must appear somewhere in the raw name.
            val prefiltered = q.probe.isNotEmpty() && isAscii(n) && !n.contains(q.probe, ignoreCase = true)
            var title: Title? = null
            var score = 0
            if (!prefiltered) {
                title = parseTitle(n)
                score = tierScore(title.base, q)
            }
            if (score == 0) {
                if (!extraMatch(item)) continue
                score = 50
                if (title == null) title = parseTitle(n)
            }
            val t = title!!
            if (q.year != null && t.year != null) score += if (t.year == q.year) 60 else -60
            if (!t.qualityPrefixed) score += 5 // plain copy ahead of its 4K twins
            hits.add(Hit(item, score, t.year, added(item)))
        }
        hits.sortWith(
            compareByDescending<Hit<T>> { it.score }
                .thenByDescending { it.titleYear ?: -1 }
                .thenByDescending { it.added }
        )
        return hits.map { it.item }
    }

    /** ASCII-only normalisation of name[start, end): lower-case, runs of non-alphanumerics → one space. */
    private fun normalizeAscii(s: String, start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        var pendingSpace = false
        for (i in start until end) {
            val c = s[i]
            val alnum = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')
            if (alnum) {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(if (c in 'A'..'Z') (c + 32) else c)
            } else {
                pendingSpace = true
            }
        }
        return sb.toString()
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun isAscii(s: String): Boolean {
        for (ch in s) if (ch.code > 127) return false
        return true
    }
}
