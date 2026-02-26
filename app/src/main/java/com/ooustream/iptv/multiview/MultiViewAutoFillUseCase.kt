package com.ooustream.iptv.multiview

import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.ContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-fills remaining MultiView slots with channels from the same category
 * as the seed channel. Falls back to other categories if insufficient.
 */
@Singleton
class MultiViewAutoFillUseCase @Inject constructor(
    private val contentRepository: ContentRepository
) {

    /**
     * Returns up to [slotCount - 1] channels to fill remaining slots.
     * Prioritizes channels in the same category as [seedChannel].
     */
    suspend fun autoFill(seedChannel: LiveStream, slotCount: Int): List<LiveStream> =
        withContext(Dispatchers.IO) {
            val needed = slotCount - 1
            if (needed <= 0) return@withContext emptyList()

            val results = mutableListOf<LiveStream>()
            val excludeId = seedChannel.streamId

            // Try same category first
            val categoryId = seedChannel.categoryId
            if (categoryId != null) {
                try {
                    val sameCategory = contentRepository.getLiveStreams(categoryId)
                        .filter { it.streamId != excludeId }
                        .shuffled()
                        .take(needed)
                    results.addAll(sameCategory)
                } catch (_: Exception) {
                    // Category fetch failed, continue
                }
            }

            // If not enough, fetch from all categories
            if (results.size < needed) {
                try {
                    val usedIds = results.map { it.streamId }.toSet() + excludeId
                    val categories = contentRepository.getLiveCategories()
                    for (cat in categories.shuffled()) {
                        if (results.size >= needed) break
                        if (cat.categoryId == categoryId) continue // Already tried
                        try {
                            val streams = contentRepository.getLiveStreams(cat.categoryId)
                                .filter { it.streamId !in usedIds }
                                .shuffled()
                                .take(needed - results.size)
                            results.addAll(streams)
                        } catch (_: Exception) {
                            // Skip failed category
                        }
                    }
                } catch (_: Exception) {
                    // Categories fetch failed
                }
            }

            results.take(needed)
        }
}
