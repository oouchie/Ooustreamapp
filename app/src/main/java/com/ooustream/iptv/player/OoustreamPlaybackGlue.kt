package com.ooustream.iptv.player

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.ooustream.iptv.R
import com.ooustream.iptv.data.model.ContentType

class OoustreamPlaybackGlue(
    context: Context,
    adapter: LeanbackPlayerAdapter
) : PlaybackTransportControlGlue<LeanbackPlayerAdapter>(context, adapter) {

    var contentType: ContentType = ContentType.LIVE
    var onChannelSwitch: ((Int) -> Unit)? = null  // direction: -1 or +1
    var onZapConfirm: (() -> Unit)? = null         // confirm zap overlay selection
    var isZapOverlayShowing: (() -> Boolean)? = null // query overlay visibility
    var onAudioTrackClicked: (() -> Unit)? = null
    var onSubtitleTrackClicked: (() -> Unit)? = null
    var onExternalPlayerClicked: (() -> Unit)? = null
    var onSleepTimerClicked: (() -> Unit)? = null
    var onStatsToggle: (() -> Unit)? = null
    var onAudioOnlyToggled: (() -> Unit)? = null

    private val ffAction = PlaybackControlsRow.FastForwardAction(context)
    private val rwAction = PlaybackControlsRow.RewindAction(context)

    val audioTrackAction = Action(ACTION_AUDIO_TRACK, "Audio").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_audio_track)
    }

    val subtitleTrackAction = Action(ACTION_SUBTITLE_TRACK, "Subtitles").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_subtitles)
    }

    val externalPlayerAction = Action(ACTION_EXTERNAL_PLAYER, "External Player").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_external_player)
    }

    val sleepTimerAction = Action(ACTION_SLEEP_TIMER, "Sleep Timer").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_sleep_timer)
    }

    val audioOnlyAction = Action(ACTION_AUDIO_ONLY, "Audio Only").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_audio_only)
    }

    override fun onCreateSecondaryActions(adapter: ArrayObjectAdapter) {
        super.onCreateSecondaryActions(adapter)
        adapter.add(audioTrackAction)
        adapter.add(subtitleTrackAction)
        adapter.add(externalPlayerAction)
        adapter.add(sleepTimerAction)
        adapter.add(audioOnlyAction)
    }

    override fun onActionClicked(action: Action) {
        when (action) {
            ffAction -> {
                if (contentType != ContentType.LIVE) {
                    playerAdapter.seekTo(playerAdapter.currentPosition + 10_000)
                }
            }
            rwAction -> {
                if (contentType != ContentType.LIVE) {
                    val newPos = (playerAdapter.currentPosition - 10_000).coerceAtLeast(0)
                    playerAdapter.seekTo(newPos)
                }
            }
            audioTrackAction -> onAudioTrackClicked?.invoke()
            subtitleTrackAction -> onSubtitleTrackClicked?.invoke()
            externalPlayerAction -> onExternalPlayerClicked?.invoke()
            sleepTimerAction -> onSleepTimerClicked?.invoke()
            audioOnlyAction -> onAudioOnlyToggled?.invoke()
            else -> super.onActionClicked(action)
        }
    }

    companion object {
        private const val ACTION_AUDIO_TRACK = 100L
        private const val ACTION_SUBTITLE_TRACK = 101L
        private const val ACTION_EXTERNAL_PLAYER = 102L
        private const val ACTION_SLEEP_TIMER = 103L
        private const val ACTION_AUDIO_ONLY = 104L
    }

    override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.action != KeyEvent.ACTION_DOWN) return super.onKey(v, keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (host != null && !host.isControlsOverlayVisible) {
                    host.showControlsOverlay(true)
                    return true
                }
                return super.onKey(v, keyCode, event)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (!isPlaying) play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isPlaying) pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (contentType != ContentType.LIVE) {
                    playerAdapter.seekTo(playerAdapter.currentPosition + 10_000)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (contentType != ContentType.LIVE) {
                    val newPos = (playerAdapter.currentPosition - 10_000).coerceAtLeast(0)
                    playerAdapter.seekTo(newPos)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (contentType == ContentType.LIVE) {
                    onChannelSwitch?.invoke(-1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (contentType == ContentType.LIVE) {
                    onChannelSwitch?.invoke(+1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (contentType == ContentType.LIVE && isZapOverlayShowing?.invoke() == true) {
                    onZapConfirm?.invoke()
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                onStatsToggle?.invoke()
                return true
            }
        }
        return super.onKey(v, keyCode, event)
    }
}
