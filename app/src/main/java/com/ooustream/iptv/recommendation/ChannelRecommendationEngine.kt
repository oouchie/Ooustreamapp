package com.ooustream.iptv.recommendation

import com.ooustream.iptv.data.local.dao.ChannelScoreDao
import com.ooustream.iptv.data.local.dao.ChannelWatchLogDao
import com.ooustream.iptv.data.local.entity.ChannelScoreEntity
import com.ooustream.iptv.data.local.entity.ChannelWatchLogEntity
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

/**
 * On-device recommendation engine for "For You — Live Now".
 * Computes scores from watch session logs using:
 *   score = frequency × recency_weight × duration_weight
 *
 * Recency: exponential decay with 7-day half-life
 * Duration: log-scale (watching 30min is better than 1min, but not 30x better)
 */
@Singleton
class ChannelRecommendationEngine @Inject constructor(
    private val watchLogDao: ChannelWatchLogDao,
    private val channelScoreDao: ChannelScoreDao
) {

    /**
     * Data class for recommended channel with context.
     */
    data class RecommendedChannel(
        val channelId: Int,
        val channelName: String,
        val categoryName: String?,
        val channelIcon: String?,
        val score: Float,
        val contextHint: String?
    )

    /**
     * Recompute all channel scores from raw watch logs.
     * Called periodically by ScoreRefreshWorker (every 6 hours).
     */
    suspend fun recomputeScores() {
        // Prune old logs (>90 days)
        watchLogDao.pruneOldLogs()

        val logs = watchLogDao.getRecentLogs()
        if (logs.isEmpty()) {
            channelScoreDao.clearAll()
            return
        }

        val now = System.currentTimeMillis()
        val scores = mutableListOf<ChannelScoreEntity>()

        // Group by (channelId, dayOfWeek, hourBucket)
        val grouped = logs.groupBy { Triple(it.channelId, it.dayOfWeek, toHourBucket(it.hourOfDay)) }

        for ((key, sessionLogs) in grouped) {
            val (channelId, dayOfWeek, hourBucket) = key
            val first = sessionLogs.first()

            // Frequency: number of sessions
            val frequency = sessionLogs.size.toFloat()

            // Recency weight: exponential decay, 7-day half-life
            val mostRecent = sessionLogs.maxOf { it.timestamp }
            val daysSince = (now - mostRecent).toFloat() / (24 * 60 * 60 * 1000)
            val recencyWeight = exp(-0.693 * daysSince / 7.0).toFloat() // ln(2)/7 ≈ 0.099

            // Duration weight: log-scale of average duration
            val avgDuration = sessionLogs.map { it.durationSeconds }.average().toLong()
            val durationWeight = ln(avgDuration.toFloat().coerceAtLeast(1f) + 1f)

            val score = frequency * recencyWeight * durationWeight
            val contextHint = generateContextHint(dayOfWeek, hourBucket, sessionLogs.size)

            scores.add(
                ChannelScoreEntity(
                    channelId = channelId,
                    channelName = first.channelName,
                    categoryName = first.categoryName,
                    channelIcon = first.channelIcon,
                    dayOfWeek = dayOfWeek,
                    hourBucket = hourBucket,
                    score = score,
                    watchCount = sessionLogs.size,
                    avgDurationSec = avgDuration,
                    lastWatched = mostRecent,
                    contextHint = contextHint,
                    updatedAt = now
                )
            )
        }

        channelScoreDao.clearAll()
        channelScoreDao.upsertAll(scores)
    }

    /**
     * Get recommended channels for the current time slot.
     * Falls back to overall top channels if current slot is sparse.
     */
    suspend fun getRecommendations(limit: Int = 10): List<RecommendedChannel> {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val hourBucket = toHourBucket(calendar.get(Calendar.HOUR_OF_DAY))

        // Try time-slot-specific recommendations first
        var scores = channelScoreDao.getTopChannelsForSlot(dayOfWeek, hourBucket, limit)

        // Fallback to overall if too few results
        if (scores.size < 3) {
            scores = channelScoreDao.getTopChannelsOverall(limit)
        }

        return scores.map { entity ->
            RecommendedChannel(
                channelId = entity.channelId,
                channelName = entity.channelName,
                categoryName = entity.categoryName,
                channelIcon = entity.channelIcon,
                score = entity.score,
                contextHint = entity.contextHint
            )
        }
    }

    /**
     * Check if we have enough data to show recommendations.
     * Need at least 3 unique channels watched.
     */
    suspend fun hasEnoughData(): Boolean {
        return watchLogDao.getUniqueChannelCount() >= 3
    }

    /**
     * Map hour (0-23) to bucket for broader pattern matching.
     * 0=night(0-5), 1=morning(6-11), 2=afternoon(12-17), 3=evening(18-23)
     */
    fun toHourBucket(hour: Int): Int = when (hour) {
        in 0..5 -> 0
        in 6..11 -> 1
        in 12..17 -> 2
        else -> 3
    }

    private fun generateContextHint(dayOfWeek: Int, hourBucket: Int, watchCount: Int): String {
        val dayName = when (dayOfWeek) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            else -> ""
        }

        val timeName = when (hourBucket) {
            0 -> "late night"
            1 -> "morning"
            2 -> "afternoon"
            3 -> "evening"
            else -> ""
        }

        return when {
            watchCount >= 5 -> "Your go-to $dayName $timeName channel"
            watchCount >= 3 -> "You watch this $timeName"
            else -> "Recently watched"
        }
    }
}
