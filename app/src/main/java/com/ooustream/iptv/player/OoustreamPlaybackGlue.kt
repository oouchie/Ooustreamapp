package com.ooustream.iptv.player

import android.content.Context
import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.media.PlaybackGlueHost
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
    var customControlsManager: PlayerControlsManager? = null

    /**
     * v3.7.6 (round 2): the customer's report on v3.7.5 confirmed the duplicate Leanback
     * overlay only appears AFTER an automatic decoder switch (rebuildPlayerWithFfmpegPreferred
     * for MTK 5.1 audio). The first round of fix tried to detach the old glue and post a
     * hideControlsOverlay AFTER the new host attached — but the host's "auto-show on
     * attach" runs internally during attach, and our posted hide was either too early
     * or competing with another show.
     *
     * Override onAttachedToHost so EVERY attach (initial + rebuilds) immediately hides
     * the default Leanback overlay before it gets a chance to render. Combined with the
     * fragment's showControlsOverlay() no-op override, this keeps the default UI invisible
     * for the lifetime of the glue.
     */
    override fun onAttachedToHost(host: PlaybackGlueHost) {
        super.onAttachedToHost(host)
        host.hideControlsOverlay(false)
    }
    var onChannelSwitch: ((Int) -> Unit)? = null
    var onZapConfirm: (() -> Unit)? = null
    var isZapOverlayShowing: (() -> Boolean)? = null
    var onAudioTrackClicked: (() -> Unit)? = null
    var onSubtitleTrackClicked: (() -> Unit)? = null
    var onExternalPlayerClicked: (() -> Unit)? = null
    var onSleepTimerClicked: (() -> Unit)? = null
    /**
     * Diagnostic hook for the fragment to wire StreamDiagnosticLogger into the glue.
     * Used to emit KEY_NO_HANDLER events when a key fires but its callback is null —
     * the fingerprint of "rebuild path forgot to re-attach a callback" bugs.
     */
    var onDiagnosticEvent: ((event: String, details: String) -> Unit)? = null

    private fun emitNoHandler(callback: String, key: String) {
        onDiagnosticEvent?.invoke(
            "KEY_NO_HANDLER",
            "key=$key, callback=$callback, glue=${System.identityHashCode(this)}"
        )
    }
    var onStatsToggle: (() -> Unit)? = null
    private var backConsumedOnDown = false
    var onAudioOnlyToggled: (() -> Unit)? = null
    // v3.7.0: renamed from isTrackPickerShowing. Returns true when ANY modal
    // overlay (track picker, binge countdown, watch-next, series-complete) is
    // showing. Glue passes every non-BACK key through when true, so overlay
    // buttons can receive focus and clicks instead of the glue stealing them
    // to show the controls bar. Without this, e.g. pressing OK on "Watch Next"
    // in the binge overlay just showed controls and never advanced episodes.
    var isModalOverlayShowing: (() -> Boolean)? = null
    var onDismissTrackPicker: (() -> Unit)? = null
    var onCcToggle: (() -> Unit)? = null

    // Seek/navigation callbacks (wired by fragment). v4.0.0: the glue no longer calls
    // playerAdapter.seekTo() itself — the fragment owns seeking via a coalescer that
    // accumulates rapid taps into ONE committed network seek (~300ms after the last tap).
    var onSeekForward: ((Long) -> Unit)? = null
    var onSeekBackward: ((Long) -> Unit)? = null
    var onNextEpisode: (() -> Unit)? = null

    private val ffAction = PlaybackControlsRow.FastForwardAction(context)
    private val rwAction = PlaybackControlsRow.RewindAction(context)

    private val iconTint = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(0xFFFFC107.toInt(), 0xFFFFFFFF.toInt())
    )

    val audioTrackAction = Action(ACTION_AUDIO_TRACK, "Audio").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_audio_track)?.mutate()?.also { it.setTintList(iconTint) }
    }

    val subtitleTrackAction = Action(ACTION_SUBTITLE_TRACK, "Subtitles").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_subtitles)?.mutate()?.also { it.setTintList(iconTint) }
    }

    val externalPlayerAction = Action(ACTION_EXTERNAL_PLAYER, "External Player").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_external_player)?.mutate()?.also { it.setTintList(iconTint) }
    }

    val sleepTimerAction = Action(ACTION_SLEEP_TIMER, "Sleep Timer").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_sleep_timer)?.mutate()?.also { it.setTintList(iconTint) }
    }

    val audioOnlyAction = Action(ACTION_AUDIO_ONLY, "Audio Only").apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_audio_only)?.mutate()?.also { it.setTintList(iconTint) }
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
                    onSeekForward?.invoke(10_000)
                        ?: emitNoHandler("onSeekForward", "ff_action")
                }
            }
            rwAction -> {
                if (contentType != ContentType.LIVE) {
                    onSeekBackward?.invoke(10_000)
                        ?: emitNoHandler("onSeekBackward", "rw_action")
                }
            }
            audioTrackAction -> onAudioTrackClicked?.invoke()
                ?: emitNoHandler("onAudioTrackClicked", "audio_action")
            subtitleTrackAction -> onSubtitleTrackClicked?.invoke()
                ?: emitNoHandler("onSubtitleTrackClicked", "subtitle_action")
            externalPlayerAction -> onExternalPlayerClicked?.invoke()
                ?: emitNoHandler("onExternalPlayerClicked", "external_action")
            sleepTimerAction -> onSleepTimerClicked?.invoke()
                ?: emitNoHandler("onSleepTimerClicked", "sleep_action")
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
        val controlsHidden = customControlsManager?.isVisible != true

        // When the track picker is showing, let ALL keys flow through to it —
        // otherwise the glue swallows OK/D-pad thinking "controls are hidden, I
        // should show them" and the focused subtitle/audio row never receives
        // its click. BACK is handled below (dismisses picker via callback).
        // This was the root cause of the "subtitles never displaying on ExoPlayer"
        // bug — OK presses on picker items were being consumed by the glue.
        if (isModalOverlayShowing?.invoke() == true && keyCode != KeyEvent.KEYCODE_BACK) {
            return false
        }

        // BACK key: dismiss controls if visible (edge case: focus still in BrowseFrameLayout).
        // Primary Back handling is via OnBackPressedCallback in the fragment, which works
        // regardless of focus location. This is defense-in-depth for the rare case where
        // focus remains inside Leanback's BrowseFrameLayout when controls are showing.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event?.action == KeyEvent.ACTION_DOWN) {
                if (isModalOverlayShowing?.invoke() == true) {
                    onDismissTrackPicker?.invoke()
                    backConsumedOnDown = true
                    return true
                }
                if (!controlsHidden) {
                    customControlsManager?.hide()
                    backConsumedOnDown = true
                    return true
                }
                backConsumedOnDown = false
            }
            if (event?.action == KeyEvent.ACTION_UP && backConsumedOnDown) {
                backConsumedOnDown = false
                return true
            }
            return false
        }

        // ACTION_UP handling
        if (event?.action != KeyEvent.ACTION_DOWN) {
            // Always consume media buttons, MENU, and CAPTIONS
            if (keyCode in intArrayOf(
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_CAPTIONS
                )) return true

            // D-pad Left/Right for VOD/Series: always consume (seek handled on DOWN)
            if (contentType != ContentType.LIVE && keyCode in intArrayOf(
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
                )) return true

            // Controls visible → pass remaining D-pad through for focus navigation
            if (!controlsHidden) {
                val isDpad = keyCode in intArrayOf(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
                )
                if (isDpad) return false
            }

            // Controls hidden → consume D-pad keys we handled on DOWN
            return true
        }

        // ACTION_DOWN handling
        when (keyCode) {
            // Media play/pause: toggle playback + show controls
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isPlaying) pause() else play()
                customControlsManager?.show()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (!isPlaying) play()
                customControlsManager?.show()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isPlaying) pause()
                customControlsManager?.show()
                return true
            }

            // FF/RW media buttons: seek + show controls
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (contentType != ContentType.LIVE) {
                    onSeekForward?.invoke(10_000)
                        ?: emitNoHandler("onSeekForward", "media_ff")
                }
                customControlsManager?.show()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (contentType != ContentType.LIVE) {
                    onSeekBackward?.invoke(10_000)
                        ?: emitNoHandler("onSeekBackward", "media_rw")
                }
                customControlsManager?.show()
                return true
            }

            // DPAD LEFT/RIGHT — always seek for VOD/Series regardless of controls state
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (contentType != ContentType.LIVE) {
                    val delta = seekDeltaForRepeat(event?.repeatCount ?: 0)
                    onSeekBackward?.invoke(delta)
                        ?: emitNoHandler("onSeekBackward", "dpad_left")
                    if (controlsHidden) customControlsManager?.show()
                    else customControlsManager?.resetAutoHideTimer()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (contentType != ContentType.LIVE) {
                    val delta = seekDeltaForRepeat(event?.repeatCount ?: 0)
                    onSeekForward?.invoke(delta)
                        ?: emitNoHandler("onSeekForward", "dpad_right")
                    if (controlsHidden) customControlsManager?.show()
                    else customControlsManager?.resetAutoHideTimer()
                    return true
                }
            }

            // DPAD UP: channel switch (LIVE when hidden) or next episode (SERIES when hidden)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (contentType == ContentType.LIVE) {
                    if (controlsHidden) {
                        if (onChannelSwitch != null) {
                            onChannelSwitch?.invoke(-1)
                        } else {
                            emitNoHandler("onChannelSwitch", "channel_up")
                        }
                        return true
                    }
                    customControlsManager?.resetAutoHideTimer()
                    return false
                }
                if (contentType == ContentType.SERIES && controlsHidden) {
                    android.util.Log.w("OOUSTREAM_NAV",
                        "DPAD_UP SERIES controlsHidden=true → onNextEpisode " +
                        "(wired=${onNextEpisode != null})")
                    onNextEpisode?.invoke()
                    return true
                }
                android.util.Log.w("OOUSTREAM_NAV",
                    "DPAD_UP content=$contentType controlsHidden=$controlsHidden — no action")
                if (!controlsHidden) {
                    customControlsManager?.resetAutoHideTimer()
                    return false
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (contentType == ContentType.LIVE) {
                    if (controlsHidden) {
                        if (onChannelSwitch != null) {
                            onChannelSwitch?.invoke(+1)
                        } else {
                            emitNoHandler("onChannelSwitch", "channel_down")
                        }
                        return true
                    }
                    customControlsManager?.resetAutoHideTimer()
                    return false
                }
                if (!controlsHidden) {
                    customControlsManager?.resetAutoHideTimer()
                    return false
                }
            }

            // DPAD CENTER/ENTER: show controls or confirm zap
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (controlsHidden) {
                    customControlsManager?.show()
                    return true
                }
                if (contentType == ContentType.LIVE && isZapOverlayShowing?.invoke() == true) {
                    onZapConfirm?.invoke()
                    return true
                }
                // Controls visible → let focus system handle OK on buttons
                customControlsManager?.resetAutoHideTimer()
                return false
            }

            KeyEvent.KEYCODE_MENU -> {
                onStatsToggle?.invoke()
                return true
            }
            KeyEvent.KEYCODE_CAPTIONS -> {
                onCcToggle?.invoke()
                return true
            }
        }
        return true // Consume unhandled keys to prevent Leanback from showing its controls
    }

    private fun seekDeltaForRepeat(repeatCount: Int): Long = when {
        repeatCount > 10 -> 60_000L
        repeatCount > 3 -> 30_000L
        else -> 10_000L
    }
}
