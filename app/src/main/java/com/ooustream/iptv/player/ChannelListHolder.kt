package com.ooustream.iptv.player

import com.ooustream.iptv.data.model.LiveStream

/**
 * Simple in-memory holder for passing the live TV channel list
 * from LiveTvFragment to OoustreamPlaybackFragment.
 *
 * Fragment arguments can't hold complex Parcelable lists efficiently,
 * so we use this static holder which is cleared after consumption.
 */
object ChannelListHolder {
    var channels: List<LiveStream> = emptyList()
    var currentIndex: Int = 0

    /** Written by player on destroy — read by LiveTvFragment on resume */
    var lastPlayedIndex: Int = -1
    var lastPlayedChannel: LiveStream? = null

    /**
     * Authoritative "actually playing" stream ID from PlayerViewModel.streamId, written
     * on player teardown. More reliable than `lastPlayedChannel` because it doesn't
     * depend on PlayerViewModel.channels being intact at teardown time. LiveTvFragment
     * uses this to look up the channel from its own list as a fallback.
     */
    var lastPlayedStreamId: Int = -1

    fun consume(): Pair<List<LiveStream>, Int> {
        val result = channels to currentIndex
        channels = emptyList()
        currentIndex = 0
        return result
    }
}
