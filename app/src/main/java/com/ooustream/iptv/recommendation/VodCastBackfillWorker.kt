package com.ooustream.iptv.recommendation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ooustream.iptv.data.local.dao.VodCastDao
import com.ooustream.iptv.data.repository.ContentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * Backfills the `vod_cast` cache so movies become searchable by actor.
 *
 * The bulk VOD list (`get_vod_streams`) carries no cast, so each un-cached movie's cast is pulled
 * from `get_vod_info` (which caches it as a side effect — see [ContentRepository.getVodInfo]). The
 * job is deliberately GENTLE and RESUMABLE: it processes a small batch per run with a delay between
 * calls and skips movies already fetched, so a large library fills over several scheduled runs
 * without hammering the IPTV server. Movies the user opens get cached instantly (opportunistic),
 * so popular titles are searchable long before the sweep finishes.
 */
@HiltWorker
class VodCastBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val contentRepository: ContentRepository,
    private val vodCastDao: VodCastDao
) : CoroutineWorker(context, params) {

    private val prefs by lazy {
        applicationContext.getSharedPreferences("vod_cast_backfill", Context.MODE_PRIVATE)
    }

    override suspend fun doWork(): Result {
        return try {
            // Once fully indexed, don't re-fetch the whole VOD list every 15 min forever — only
            // re-scan ~daily to pick up newly-added movies.
            val now = System.currentTimeMillis()
            val doneAt = prefs.getLong(KEY_DONE_AT, 0L)
            if (doneAt > 0L && now - doneAt < RESCAN_INTERVAL_MS) return Result.success()

            val allVod = contentRepository.getVodStreams()
            val fetched = vodCastDao.getFetchedIds().toSet()
            val pending = allVod.asSequence()
                .map { it.streamId }
                .filter { it !in fetched }
                .distinct()
                .take(BATCH_SIZE)
                .toList()

            if (pending.isEmpty()) {
                prefs.edit().putLong(KEY_DONE_AT, now).apply() // library fully indexed
                return Result.success()
            }
            prefs.edit().remove(KEY_DONE_AT).apply() // still work to do — keep scanning each run

            for (id in pending) {
                try {
                    // Side effect: getVodInfo upserts this movie's cast into vod_cast.
                    contentRepository.getVodInfo(id)
                } catch (_: Exception) {
                    // Transient per-movie failure — leave it un-fetched; a later run retries it.
                }
                delay(DELAY_MS)
            }
            Result.success()
        } catch (_: Exception) {
            // Not logged in / no network — let WorkManager retry with backoff.
            Result.retry()
        }
    }

    companion object {
        // ~150 movies/run × every 15 min ≈ 600/hour: a 5k-movie library fills in ~8h, gently.
        private const val BATCH_SIZE = 150
        private const val DELAY_MS = 200L
        private const val KEY_DONE_AT = "done_at"
        private const val RESCAN_INTERVAL_MS = 24 * 60 * 60 * 1000L // re-scan once a day when done
    }
}
