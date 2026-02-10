package com.ooustream.iptv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.ooustream.iptv.data.model.ContentType
import okhttp3.OkHttpClient

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LivePreviewManager(private val context: Context, private val okHttpClient: OkHttpClient) {

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var lowBitrateEnabled = false

    /**
     * Limits preview video resolution to 480p (854x480) to reduce bandwidth
     * and CPU usage during channel browsing. Call before [startPreview].
     */
    fun setLowBitrateMode() {
        lowBitrateEnabled = true
    }

    fun startPreview(playerView: PlayerView, streamUrl: String) {
        release()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        val builder = ExoPlayer.Builder(context)
            .setLoadControl(BufferConfigs.forContentType(ContentType.LIVE))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))

        if (lowBitrateEnabled) {
            trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setMaxVideoSize(854, 480)
                )
            }
            builder.setTrackSelector(trackSelector!!)
        }

        player = builder.build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            volume = 0.15f
            prepare()
            play()
        }
        playerView.player = player
    }

    /**
     * Adjusts preview audio volume.
     * @param volume value between 0f (muted) and 1f (full volume)
     */
    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun release() {
        player?.release()
        player = null
        trackSelector = null
    }

    fun getPlayer(): ExoPlayer? = player
}
