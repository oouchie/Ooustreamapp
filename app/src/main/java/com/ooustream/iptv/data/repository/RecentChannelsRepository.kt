package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.local.dao.ChannelWatchLogDao
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.parental.ContentFilterManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE source of "recently watched live channels" for the whole app.
 *
 * Reads `channel_watch_log` (written by WatchSessionLogger on every live session >= 30s) and
 * maps it to playable [LiveStream] rows without touching the network — the log already carries
 * name, icon and category, exactly like the Favorites rail maps FavoriteEntity.
 *
 * Deliberately shared: a second "recent channels" loader already shipped inside MultiView's
 * channel picker and had drifted from the Live TV mappers (tvArchive null vs 0) before this
 * class existed. Rebuild-path clone drift is this project's #1 recurring bug family — keep the
 * implementation count at one.
 */
@Singleton
class RecentChannelsRepository @Inject constructor(
    private val watchLogDao: ChannelWatchLogDao,
    private val contentFilterManager: ContentFilterManager
) {
    companion object {
        /** The table retains 90 days for recommendation scoring; that is not "recent" to a person. */
        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        const val DISPLAY_LIMIT = 25

        /**
         * Over-fetch, because the SQL LIMIT runs BEFORE parental filtering. Without headroom a
         * few blocked channels near the top could silently collapse the rail to a handful of rows.
         */
        private const val FETCH_LIMIT = 60
    }

    fun observeRecentLiveChannels(
        limit: Int = DISPLAY_LIMIT,
        windowMs: Long = WINDOW_MS
    ): Flow<List<LiveStream>> =
        watchLogDao.observeRecentChannels(
            cutoff = System.currentTimeMillis() - windowMs,
            limit = FETCH_LIMIT
        ).map { rows ->
            val mapped = rows.map { row ->
                LiveStream(
                    num = null,
                    name = row.channelName,
                    streamType = "live",
                    streamId = row.channelId,
                    streamIcon = row.channelIcon,
                    epgChannelId = null,
                    added = null,
                    categoryId = row.categoryId,
                    customSid = null,
                    // 0, matching the Live TV and EPG-guide mappers (MultiView's copy used null).
                    tvArchive = 0,
                    directSource = null,
                    tvArchiveDuration = null
                )
            }
            var visible = contentFilterManager.filterContent("live", mapped) { it.categoryId }
            // filterContent lets a NULL categoryId through, which is right for catalog data but
            // wrong here: the log legitimately contains nulls (Home's "For You — Live Now" rail
            // fabricates LiveStreams with categoryId = null and WatchSessionLogger copies that
            // straight in), so a blocked channel first reached from Home would reappear here.
            // Fail CLOSED on an unverifiable category, but only while filtering is actually on.
            // Cost is a false negative (a Home-launched channel missing from the rail while
            // parental controls are active), never a false positive.
            if (contentFilterManager.isFilteringActive) {
                visible = visible.filter { it.categoryId != null }
            }
            visible.take(limit)
        }
}
