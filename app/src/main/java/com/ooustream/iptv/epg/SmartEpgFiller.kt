package com.ooustream.iptv.epg

import android.graphics.Typeface
import android.widget.TextView
import com.ooustream.iptv.data.local.dao.EpgPatternDao
import com.ooustream.iptv.data.local.entity.EpgPatternEntity
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source of EPG data
 */
enum class EpgSource {
    REAL,
    PATTERN_CACHE,
    RULE_INFERRED
}

/**
 * Confidence level in EPG inference
 */
enum class EpgConfidence {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Inferred or real EPG program data
 */
data class InferredEpg(
    val title: String,
    val description: String?,
    val confidence: EpgConfidence,
    val source: EpgSource
)

/**
 * Smart EPG filler with 3-tier resolution:
 * 1. Real EPG data (if valid)
 * 2. Pattern cache from historical viewing (learned patterns)
 * 3. Rule-based inference from channel metadata and time-of-day
 */
@Singleton
class SmartEpgFiller @Inject constructor(
    private val epgPatternDao: EpgPatternDao
) {

    private val GARBAGE_EPG_TITLES = setOf(
        "No Program Found",
        "Programming",
        "To Be Announced",
        "TBA",
        "N/A"
    )

    /**
     * Get smart EPG data using 3-tier resolution strategy.
     *
     * @param realEpgTitle Real EPG title from API (may be garbage)
     * @param channelId Channel ID for pattern cache lookup
     * @param channelName Channel name for rule-based inference
     * @param categoryName Optional category for content type inference
     * @return InferredEpg with best available data and confidence level
     */
    suspend fun getSmartEpg(
        realEpgTitle: String?,
        channelId: Int,
        channelName: String,
        categoryName: String?
    ): InferredEpg {
        // Tier 0: Use real EPG if valid
        if (isGoodEpg(realEpgTitle)) {
            return InferredEpg(
                title = realEpgTitle!!,
                description = null,
                confidence = EpgConfidence.HIGH,
                source = EpgSource.REAL
            )
        }

        // Get current time for pattern/rule matching
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        // Tier 1: Check pattern cache
        val pattern = epgPatternDao.getPattern(channelId, dayOfWeek, hour)
        if (pattern != null && pattern.confidence >= 0.5f) {
            return InferredEpg(
                title = "Likely: ${pattern.programTitle}",
                description = "Based on viewing history",
                confidence = EpgConfidence.MEDIUM,
                source = EpgSource.PATTERN_CACHE
            )
        }

        // Tier 2: Rule-based inference
        val parsed = ChannelNameParser.parse(channelName, categoryName)
        val inferredTitle = inferFromRules(parsed, hour, dayOfWeek)

        return InferredEpg(
            title = inferredTitle,
            description = null,
            confidence = EpgConfidence.LOW,
            source = EpgSource.RULE_INFERRED
        )
    }

    /**
     * Check if EPG title is valid and usable.
     */
    private fun isGoodEpg(title: String?): Boolean {
        if (title.isNullOrBlank() || title.length < 3) {
            return false
        }

        return !GARBAGE_EPG_TITLES.any { garbage ->
            title.equals(garbage, ignoreCase = true)
        }
    }

    /**
     * Learn viewing patterns for future inference.
     * Increases confidence for repeated patterns, resets for new patterns.
     *
     * @param channelId Channel ID
     * @param channelName Channel name for metadata extraction
     * @param programTitle Real program title observed
     */
    suspend fun learnPattern(
        channelId: Int,
        channelName: String,
        programTitle: String
    ) {
        if (!isGoodEpg(programTitle)) {
            return // Don't learn from garbage data
        }

        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        val existing = epgPatternDao.getPattern(channelId, dayOfWeek, hour)

        val entity = if (existing != null) {
            if (existing.programTitle.equals(programTitle, ignoreCase = true)) {
                // Same pattern, increase confidence
                val newConfidence = (existing.confidence + 0.1f).coerceAtMost(1.0f)
                existing.copy(
                    confidence = newConfidence,
                    sampleCount = existing.sampleCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                // Different pattern, reset
                existing.copy(
                    programTitle = programTitle,
                    confidence = 0.3f,
                    sampleCount = 1,
                    updatedAt = System.currentTimeMillis()
                )
            }
        } else {
            // New pattern
            EpgPatternEntity(
                channelId = channelId,
                channelName = channelName,
                dayOfWeek = dayOfWeek,
                hourSlot = hour,
                programTitle = programTitle,
                programDescription = null,
                programCategory = null,
                confidence = 0.5f,
                sampleCount = 1,
                updatedAt = System.currentTimeMillis()
            )
        }

        epgPatternDao.upsert(entity)
    }

    /**
     * Infer program title from channel metadata and time-of-day rules.
     */
    private fun inferFromRules(
        parsed: ParsedChannel,
        hour: Int,
        dayOfWeek: Int
    ): String {
        return when (parsed.contentType) {
            ChannelContentType.NEWS -> {
                when (hour) {
                    in 5..9 -> "Morning News"
                    in 10..16 -> "Midday News"
                    in 17..19 -> "Evening News"
                    in 20..23 -> "Prime Time News"
                    else -> "Overnight News"
                }
            }

            ChannelContentType.SPORTS -> {
                val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
                when {
                    hour in 12..23 && isWeekend -> "Live Sports"
                    hour in 18..23 -> "Live Sports"
                    hour in 6..11 -> "Sports News"
                    else -> "Sports Highlights"
                }
            }

            ChannelContentType.ENTERTAINMENT -> {
                when (hour) {
                    in 6..12 -> "Daytime Programming"
                    in 13..17 -> "Afternoon Shows"
                    in 18..23 -> "Prime Time"
                    else -> "Late Night Programming"
                }
            }

            ChannelContentType.KIDS -> {
                when (hour) {
                    in 6..12 -> "Morning Cartoons"
                    in 13..17 -> "After School Shows"
                    in 18..20 -> "Evening Programming"
                    else -> "Kids Programming"
                }
            }

            ChannelContentType.MOVIES -> {
                "Movie"
            }

            ChannelContentType.DOCUMENTARY -> {
                "Documentary"
            }

            ChannelContentType.MUSIC -> {
                when (hour) {
                    in 6..12 -> "Morning Music"
                    in 13..17 -> "Afternoon Music"
                    in 18..23 -> "Evening Music"
                    else -> "Music Programming"
                }
            }

            ChannelContentType.RELIGIOUS -> {
                val isSunday = dayOfWeek == Calendar.SUNDAY
                when {
                    isSunday && hour in 8..12 -> "Sunday Service"
                    hour in 6..12 -> "Morning Devotional"
                    hour in 18..22 -> "Evening Service"
                    else -> "Religious Programming"
                }
            }

            ChannelContentType.UNKNOWN -> {
                if (parsed.isLocal) {
                    when (hour) {
                        in 5..9 -> "Local Morning News"
                        in 12..13 -> "Local Midday News"
                        in 17..19 -> "Local Evening News"
                        in 22..23 -> "Local Late News"
                        else -> "Local Programming"
                    }
                } else {
                    "Live Programming"
                }
            }
        }
    }
}

/**
 * Apply visual styling to TextView based on EPG source and confidence.
 *
 * - REAL: normal typeface, white 80%
 * - PATTERN_CACHE: italic, light blue 67%
 * - RULE_INFERRED: italic, white 47%
 */
fun bindEpgText(textView: TextView, inferred: InferredEpg) {
    textView.text = inferred.title

    when (inferred.source) {
        EpgSource.REAL -> {
            textView.typeface = Typeface.DEFAULT
            textView.setTextColor(0xCCFFFFFF.toInt()) // white 80%
        }

        EpgSource.PATTERN_CACHE -> {
            textView.typeface = Typeface.defaultFromStyle(Typeface.ITALIC)
            textView.setTextColor(0xAA90CAF9.toInt()) // light blue 67%
        }

        EpgSource.RULE_INFERRED -> {
            textView.typeface = Typeface.defaultFromStyle(Typeface.ITALIC)
            textView.setTextColor(0x77FFFFFF) // white 47%
        }
    }
}
