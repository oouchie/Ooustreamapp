package com.ooustream.iptv.epg.guide

import com.ooustream.iptv.data.model.EpgProgram
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.epg.EpgSource
import com.ooustream.iptv.epg.SmartEpgFiller

/**
 * One cell in the guide grid. [source] drives styling per the SmartEpgFiller convention:
 * REAL = normal white, PATTERN_CACHE = italic light blue, RULE_INFERRED = italic dim.
 */
data class GuideProgram(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val source: EpgSource
) {
    fun contains(timeMs: Long): Boolean = timeMs in startMs until endMs
}

/** One row of the guide: a channel plus its (normalized, gap-filled) program lane. */
data class GuideRowData(
    val channel: LiveStream,
    val programs: List<GuideProgram>
)

/**
 * Pure normalization for guide lanes: epoch-second timestamps → ms, invalid rows dropped,
 * clipped to the horizon, sorted, and any hole >5 min filled with hour-aligned synthetic
 * blocks from [SmartEpgFiller.inferRuleBased] so the grid never shows an empty cell.
 */
object GuideEpgNormalizer {

    private const val MIN_GAP_MS = 5 * 60_000L
    private const val SYNTHETIC_BLOCK_MS = 60 * 60_000L

    fun normalize(
        raw: List<EpgProgram>,
        horizonStartMs: Long,
        horizonEndMs: Long,
        channelName: String,
        categoryName: String?,
        filler: SmartEpgFiller
    ): List<GuideProgram> {
        val real = raw.mapNotNull { p ->
            val start = p.startTimestamp?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            val end = p.stopTimestamp?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            if (end <= horizonStartMs || start >= horizonEndMs) return@mapNotNull null
            val title = p.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GuideProgram(
                title = title,
                description = p.description,
                startMs = start.coerceAtLeast(horizonStartMs),
                endMs = end.coerceAtMost(horizonEndMs),
                source = EpgSource.REAL
            )
        }.sortedBy { it.startMs }

        if (real.isEmpty()) {
            return syntheticBlocks(horizonStartMs, horizonEndMs, channelName, categoryName, filler)
        }

        // Fill holes between/around real programs with synthetic blocks
        val result = mutableListOf<GuideProgram>()
        var cursor = horizonStartMs
        for (p in real) {
            if (p.startMs - cursor > MIN_GAP_MS) {
                result += syntheticBlocks(cursor, p.startMs, channelName, categoryName, filler)
            }
            result += p
            cursor = maxOf(cursor, p.endMs)
        }
        if (horizonEndMs - cursor > MIN_GAP_MS) {
            result += syntheticBlocks(cursor, horizonEndMs, channelName, categoryName, filler)
        }
        return result
    }

    /** Hour-aligned inferred blocks covering [fromMs, toMs) — the "never blank" fallback. */
    fun syntheticBlocks(
        fromMs: Long,
        toMs: Long,
        channelName: String,
        categoryName: String?,
        filler: SmartEpgFiller
    ): List<GuideProgram> {
        if (toMs <= fromMs) return emptyList()
        val inferred = filler.inferRuleBased(channelName, categoryName)
        val blocks = mutableListOf<GuideProgram>()
        var cursor = fromMs
        while (cursor < toMs) {
            // Align block ends to the next hour boundary so synthetic cells read like a schedule
            val nextHour = ((cursor / SYNTHETIC_BLOCK_MS) + 1) * SYNTHETIC_BLOCK_MS
            val end = minOf(maxOf(nextHour, cursor + MIN_GAP_MS), toMs)
            blocks += GuideProgram(
                title = inferred.title,
                description = inferred.description,
                startMs = cursor,
                endMs = end,
                source = inferred.source
            )
            cursor = end
        }
        return blocks
    }
}
