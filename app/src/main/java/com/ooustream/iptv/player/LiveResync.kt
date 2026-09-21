package com.ooustream.iptv.player

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * The ONE correct way to re-sync a live Xtream stream.
 *
 * Why this exists (measured on AFTKRT / mt8696, 2026-09-17): `seekToDefaultPosition()` on an
 * Xtream progressive MPEG-TS body is NOT a jump to the live edge. Verified against the Media3
 * 1.10.0 sources:
 *
 *  - The body has no Content-Length and TsExtractor reports an unseekable map with an unknown
 *    duration, so ProgressiveMediaPeriod marks the source live progressive.
 *  - Its in-buffer seek path is explicitly excluded for that source type, and an unseekable map
 *    forces the seek position to 0 — so ANY seek cancels the load and re-opens the HTTP request
 *    from the start. There is no such thing as a cheap seek here.
 *  - TsExtractor then declines to reset its TimestampAdjuster, so the re-opened stream keeps the
 *    timestamp baseline of the connection that was opened before the seek, while the renderer
 *    position is reset to 0. Every incoming frame looks ~60s early, the video release control
 *    holds anything more than 50ms early, and the slot freezes with ZERO dropped frames until
 *    something rebuilds the media item.
 *
 * Re-creating the MediaItem is the only operation that re-seeds the extractor and its
 * TimestampAdjuster, which is exactly why "hard reset" was the only rung that ever recovered.
 *
 * One helper, one copy of the sequence — the hand-copied-playback-path family is this project's
 * most persistent bug source.
 */
@UnstableApi
fun ExoPlayer.resyncProgressiveLive(item: MediaItem? = null) {
    val target = item ?: currentMediaItem ?: return
    stop()
    clearMediaItems()
    setMediaItem(target)
    prepare()
    play()
}
