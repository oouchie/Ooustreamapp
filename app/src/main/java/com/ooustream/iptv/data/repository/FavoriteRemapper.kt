package com.ooustream.iptv.data.repository

import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.ooustream.iptv.data.local.dao.FavoriteDao
import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.model.VodStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves movie and series favorites onto the provider's new ids after a re-key.
 *
 * The provider renumbered its series on 2026-10-29. A favorite stores the id it was saved under, so
 * every favorited show became a card that opens nothing. This re-matches each favorite whose id is
 * no longer in the catalog by its saved name ([FavoriteNameMatch]) against the catalog the Home
 * screen already loads, so it costs no extra network call.
 *
 * Rules, all deliberate:
 *  - Only an exact name match to exactly one catalog title moves a favorite; anything else stays
 *    as it is. Nothing unmatched is ever deleted.
 *  - The new row is written before the old one is deleted. If the app dies in between, the next run
 *    finds the new id already saved and just drops the old row.
 *  - Live channels are left out: the re-key seen was series, and movies are covered the same way
 *    in case they move next.
 */
@Singleton
class FavoriteRemapper @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val diagnosticLogger: StreamDiagnosticLogger
) {
    private val vodDone = AtomicBoolean(false)
    private val seriesDone = AtomicBoolean(false)

    /**
     * @param catalog the RAW movie list, unfiltered (see [WatchHistoryPruner.pruneVod]).
     * @param alsoLive sorted ids that exist but are not in [catalog] — the adult titles
     *   [CatalogCache] leaves out. Without them every adult favorite would read as deleted.
     */
    suspend fun remapVod(catalog: List<VodStream>, alsoLive: IntArray = IntArray(0)) =
        remap("vod", vodDone, catalog, { it.streamId }, alsoLive) {
            FavoriteNameMatch.Title(it.streamId, it.name, it.streamIcon, it.categoryId, it.containerExtension)
        }

    /** @param catalog the RAW series list, unfiltered. */
    suspend fun remapSeries(catalog: List<Series>) =
        remap("series", seriesDone, catalog, { it.seriesId }) {
            FavoriteNameMatch.Title(it.seriesId, it.name, it.cover, it.categoryId, null)
        }

    private suspend fun <T> remap(
        type: String,
        done: AtomicBoolean,
        catalog: List<T>,
        idOf: (T) -> Int,
        alsoLive: IntArray = IntArray(0),
        toTitle: (T) -> FavoriteNameMatch.Title
    ) = withContext(Dispatchers.Default) {
        // An empty catalog is a failed fetch, not "everything is gone".
        if (catalog.isEmpty()) return@withContext
        if (!done.compareAndSet(false, true)) return@withContext

        try {
            val favorites = favoriteDao.getFavoritesListByType(type)
            if (favorites.isEmpty()) return@withContext
            val live = catalog.mapTo(HashSet(catalog.size * 2)) { idOf(it) }
            val dead = favorites.filter { it.streamId !in live && alsoLive.binarySearch(it.streamId) < 0 }
            if (dead.isEmpty()) return@withContext

            val steps = FavoriteNameMatch.plan(
                type,
                dead.map { FavoriteNameMatch.Dead(it.id, it.name) },
                favorites.mapTo(HashSet()) { it.id },
                catalog.asSequence().map(toTitle)
            )
            val byId = dead.associateBy { it.id }
            for (step in steps) {
                if (step is FavoriteNameMatch.Move) {
                    val fav = byId.getValue(step.oldId)
                    val title = step.title
                    favoriteDao.insert(
                        fav.copy(
                            id = step.newId,
                            streamId = title.id,
                            name = title.name ?: fav.name,
                            icon = title.icon ?: fav.icon,
                            categoryId = title.categoryId,
                            extra = if (type == "vod") title.extension ?: fav.extra else fav.extra
                        )
                    )
                }
                favoriteDao.delete(step.oldId)
            }
            val moved = steps.count { it is FavoriteNameMatch.Move }
            val deduped = steps.size - moved
            diagnosticLogger.logAppEvent(
                "FAVORITES_REMAP",
                "type=$type dead=${dead.size} matched=$moved deduped=$deduped " +
                    "unmatched=${dead.size - steps.size} catalog=${catalog.size}"
            )
        } catch (c: CancellationException) {
            // Home went away mid-run: re-arm so the next Home load tries again.
            done.set(false)
            throw c
        } catch (e: Exception) {
            done.set(false)
            diagnosticLogger.logAppEvent("FAVORITES_REMAP_FAILED", "type=$type error=${e.message}")
        }
    }
}
