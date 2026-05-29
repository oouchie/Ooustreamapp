package com.ooustream.iptv.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.ooustream.iptv.common.AudioPipelineFactory
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
        val dataSourceFactory = StreamingDataFactories.buildDataSourceFactory(okHttpClient)

        trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)     // No subtitles in preview
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)    // Muted preview: no audio decode at all
                    .apply {
                        if (lowBitrateEnabled) {
                            setMaxVideoSize(854, 480)
                        }
                    }
            )
        }

        // Use shared audio pipeline for codec support (AC3/DTS/EAC3 via FFmpeg + stereo downmix)
        val renderersFactory = AudioPipelineFactory.createRenderersFactory(context)

        val builder = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(BufferConfigs.forContentType(ContentType.LIVE))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector!!)

        player = builder.build().apply {
            // Muted browse preview: never take audio focus (don't duck/interrupt anything),
            // and keep volume at 0. Audio track is also disabled above so no AC3/EAC3/FFmpeg
            // decode is spun up while the user is merely scrolling channels.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ false
            )
            volume = 0f
            setMediaItem(MediaItem.fromUri(streamUrl))
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
        try {
            player?.stop()
            player?.clearVideoSurface()
            player?.release()
        } catch (_: Exception) {}
        player = null
        trackSelector = null
    }

    fun getPlayer(): ExoPlayer? = player
}
