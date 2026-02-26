package com.ooustream.iptv.favorites

/**
 * Extracts live score information from EPG program titles.
 * Common patterns in sports EPG:
 * - "Lakers vs Warriors 110 - 105"
 * - "MIA 3 - NYK 2 (Q4 3:12)"
 * - "Arsenal 2-1 Chelsea (67')"
 * - "NYY 5 - BOS 3 (R7)"
 */
object ScoreParser {

    data class ScoreResult(
        val score: String,       // "110 - 105", "3 - 2"
        val detail: String?      // "Q4 3:12", "R7", "67'", "2nd Half"
    )

    // Score patterns: "TEAM nn - TEAM nn" or "nn-nn" or "nn - nn"
    private val SCORE_REGEX = Regex(
        """(\d{1,3})\s*[-–]\s*(\d{1,3})"""
    )

    // Detail patterns in parentheses or after score
    private val DETAIL_REGEX = Regex(
        """\(([^)]+)\)"""
    )

    // Quarter/period patterns: Q1-Q4, OT, P1-P3
    private val QUARTER_REGEX = Regex(
        """(Q[1-4]|OT|P[1-3]|[1-4](?:st|nd|rd|th)\s*(?:Qtr|Quarter|Half|Period|Set))\s*(\d{1,2}:\d{2})?""",
        RegexOption.IGNORE_CASE
    )

    // Soccer minute: 45', 90+3'
    private val MINUTE_REGEX = Regex(
        """(\d{1,3}(?:\+\d{1,2})?)'"""
    )

    // Baseball inning: R1-R9, Top/Bot
    private val INNING_REGEX = Regex(
        """(R\d|Top|Bot(?:tom)?)\s*(\d{1,2})?""",
        RegexOption.IGNORE_CASE
    )

    // Half patterns: 1st Half, 2nd Half, HT, FT
    private val HALF_REGEX = Regex(
        """(1st\s*Half|2nd\s*Half|HT|FT|Half(?:\s*Time)?|Full(?:\s*Time)?)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Attempt to extract a score from an EPG title.
     * @return ScoreResult if a score was found, null otherwise.
     */
    fun parse(epgTitle: String?): ScoreResult? {
        if (epgTitle.isNullOrBlank()) return null

        val scoreMatch = SCORE_REGEX.find(epgTitle) ?: return null
        val score = "${scoreMatch.groupValues[1]} - ${scoreMatch.groupValues[2]}"

        // Try to extract detail from parentheses first
        val detailMatch = DETAIL_REGEX.find(epgTitle)
        if (detailMatch != null) {
            return ScoreResult(score, detailMatch.groupValues[1].trim())
        }

        // Try specific detail patterns
        val detail = extractDetail(epgTitle)
        return ScoreResult(score, detail)
    }

    private fun extractDetail(text: String): String? {
        QUARTER_REGEX.find(text)?.let { match ->
            val period = match.groupValues[1]
            val time = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            return if (time != null) "$period $time" else period
        }

        MINUTE_REGEX.find(text)?.let { match ->
            return "${match.groupValues[1]}'"
        }

        INNING_REGEX.find(text)?.let { match ->
            val part = match.groupValues[1]
            val num = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            return if (num != null) "$part $num" else part
        }

        HALF_REGEX.find(text)?.let { match ->
            return match.groupValues[1]
        }

        return null
    }
}
