package com.ooustream.iptv.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
    private var audible = false

    /** Called when the current stream fails (dead channel, refused connection). */
    var onError: (() -> Unit)? = null
    /** Called when the current stream draws its first frame. */
    var onFirstFrame: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            onError?.invoke()
        }

        override fun onRenderedFirstFrame() {
            onFirstFrame?.invoke()
        }
    }

    /**
     * Limits preview video resolution to 480p (854x480) to reduce bandwidth
     * and CPU usage during channel browsing. Call before [startPreview].
     */
    fun setLowBitrateMode() {
        lowBitrateEnabled = true
    }

    /**
     * Full-bleed Live TV: the browsing player is the one being watched, so it plays sound and
     * takes audio focus. Dolby goes through FFmpeg (the same decode the full player falls back
     * to on MediaTek) so a background channel can't hit a broken hardware AC3/EAC3 decoder.
     * Call before [startPreview].
     */
    fun setAudible() {
        audible = true
    }

    fun startPreview(playerView: PlayerView, streamUrl: String) {
        // Audible mode keeps one player and swaps the stream: a zap is then a re-prepare, not
        // a decoder teardown and rebuild on every channel the cursor stops on.
        val existing = player
        if (audible && existing != null) {
            existing.stop()
            existing.setMediaItem(MediaItem.fromUri(streamUrl))
            existing.prepare()
            existing.play()
            if (playerView.player !== existing) playerView.player = existing
            return
        }
        release()
        val dataSourceFactory = StreamingDataFactories.buildDataSourceFactory(okHttpClient)

        trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)     // No subtitles in preview
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible) // Muted preview: no audio decode at all
                    .apply {
                        if (lowBitrateEnabled) {
                            setMaxVideoSize(854, 480)
                        }
                    }
            )
        }

        // Use shared audio pipeline for codec support (AC3/DTS/EAC3 via FFmpeg + stereo downmix)
        val renderersFactory = if (audible) {
            AudioPipelineFactory.createFfmpegPreferredRenderersFactory(context)
        } else {
            AudioPipelineFactory.createRenderersFactory(context)
        }

        val builder = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(BufferConfigs.forContentType(ContentType.LIVE))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector!!)
            .withoutBogusLiveDurationStuckDetection()

        player = builder.build().apply {
            // Muted browse preview: never take audio focus (don't duck/interrupt anything),
            // and keep volume at 0. Audio track is also disabled above so no AC3/EAC3/FFmpeg
            // decode is spun up while the user is merely scrolling channels.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ audible
            )
            volume = if (audible) 1f else 0f
            addListener(listener)
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
            player?.removeListener(listener)
            player?.stop()
            player?.clearVideoSurface()
            player?.release()
        } catch (_: Exception) {}
        player = null
        trackSelector = null
    }

    fun getPlayer(): ExoPlayer? = player
}
