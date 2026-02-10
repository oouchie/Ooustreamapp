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

    fun consume(): Pair<List<LiveStream>, Int> {
        val result = channels to currentIndex
        channels = emptyList()
        currentIndex = 0
        return result
    }
}
