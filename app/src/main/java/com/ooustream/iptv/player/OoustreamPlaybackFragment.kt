package com.ooustream.iptv.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.ooustream.iptv.R
import com.ooustream.iptv.common.AdaptiveImageLoader
import com.ooustream.iptv.common.CrashRecoveryManager
import com.ooustream.iptv.common.QualityPolicy
import com.ooustream.iptv.common.RemoteHintOverlay
import com.ooustream.iptv.data.model.ContentType
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OoustreamPlaybackFragment : VideoSupportFragment() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var adaptiveImageLoader: AdaptiveImageLoader
    @Inject lateinit var crashRecoveryManager: CrashRecoveryManager
    @Inject lateinit var qualityPolicy: QualityPolicy

    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var glue: OoustreamPlaybackGlue? = null
    private var zapOverlay: ChannelZapOverlay? = null
    private var bingeOverlay: BingeCountdownOverlay? = null
    private var sleepTimerManager: SleepTimerManager? = null
    private var statsOverlay: StreamStatsOverlay? = null
    private var hintsOverlay: RemoteHintOverlay? = null
    private var audioOnlyOverlay: AudioOnlyOverlay? = null
    private var isAudioOnly = false
    private var bingeShown = false
    private var pipReceiver: BroadcastReceiver? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep screen on during playback to prevent screensaver
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val loadControl = BufferConfigs.forContentTypeAndQuality(
            viewModel.contentType, qualityPolicy.tier.value
        )
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        // Warn on low bandwidth before VOD/Series playback
        if (qualityPolicy.shouldWarnBeforeVod && viewModel.contentType != ContentType.LIVE) {
            Toast.makeText(requireContext(), "Low bandwidth detected. Playback may buffer.", Toast.LENGTH_LONG).show()
        }

        val playerAdapter = LeanbackPlayerAdapter(requireContext(), player!!, 1000)
        glue = OoustreamPlaybackGlue(requireContext(), playerAdapter).apply {
            host = VideoSupportFragmentGlueHost(this@OoustreamPlaybackFragment)
            isControlsOverlayAutoHideEnabled = true
            contentType = viewModel.contentType
            title = viewModel.streamName

            onChannelSwitch = { direction ->
                // Direct channel switch — tune immediately on UP/DOWN
                val newChannel = viewModel.switchChannel(direction)
                if (newChannel != null) {
                    tuneToChannel(newChannel)
                    // Show overlay briefly to indicate current channel
                    zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                }
            }

            onZapConfirm = {
                // Dismiss overlay on OK press when it's showing
                zapOverlay?.dismiss()
            }

            isZapOverlayShowing = {
                zapOverlay?.isShowing == true
            }

            onAudioTrackClicked = {
                player?.let { p ->
                    TrackSelectionHelper.showAudioTrackSelector(requireContext(), p)
                }
            }

            onSubtitleTrackClicked = {
                player?.let { p ->
                    TrackSelectionHelper.showSubtitleTrackSelector(requireContext(), p)
                }
            }

            onExternalPlayerClicked = {
                showExternalPlayerDialog()
            }

            onSleepTimerClicked = {
                sleepTimerManager?.showTimerDialog()
            }

            onStatsToggle = {
                statsOverlay?.toggle()
            }

            onAudioOnlyToggled = toggleAudioOnly@{
                isAudioOnly = !isAudioOnly
                val p = player ?: return@toggleAudioOnly
                if (isAudioOnly) {
                    // Disable video track to save bandwidth, keep audio playing
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        .build()
                    audioOnlyOverlay?.show(viewModel.streamName)
                } else {
                    // Re-enable video track
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                        .build()
                    audioOnlyOverlay?.dismiss()
                }
            }
        }

        // Intercept DPAD_UP/DOWN before Leanback consumes them for controls overlay.
        // Without this, Leanback's PlaybackSupportFragment shows/navigates the controls
        // overlay on DPAD_UP/DOWN, so the glue's onKey never receives these events.
        if (viewModel.contentType == ContentType.LIVE) {
            setOnKeyInterceptListener(View.OnKeyListener { _, keyCode, event ->
                if (event?.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        val newChannel = viewModel.switchChannel(-1)
                        if (newChannel != null) {
                            tuneToChannel(newChannel)
                            zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        val newChannel = viewModel.switchChannel(+1)
                        if (newChannel != null) {
                            tuneToChannel(newChannel)
                            zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (zapOverlay?.isShowing == true) {
                            zapOverlay?.dismiss()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            })
        }

        // Trim image cache to free memory for video playback
        adaptiveImageLoader.trimForPlayback()

        // Record play event for analytics
        viewModel.recordPlayStart()

        // Start playback
        player?.setMediaItem(MediaItem.fromUri(viewModel.streamUrl))
        player?.prepare()
        player?.play()

        // Add channel zap overlay to fragment view hierarchy
        val overlay = ChannelZapOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.onChannelSelected = { channel ->
            tuneToChannel(channel)
        }
        zapOverlay = overlay

        // Add binge countdown overlay for series content
        if (viewModel.contentType == ContentType.SERIES) {
            val binge = BingeCountdownOverlay(requireContext())
            (view as? ViewGroup)?.addView(
                binge,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            binge.onPlayNext = {
                viewLifecycleOwner.lifecycleScope.launch {
                    val next = viewModel.resolveNextEpisode()
                    if (next != null) {
                        // Update state for continued binge
                        viewModel.streamUrl = next.url
                        viewModel.streamId = next.episodeId
                        viewModel.streamName = next.name
                        viewModel.seasonNum = next.season
                        viewModel.episodeNum = next.episodeNum
                        bingeShown = false

                        // Switch playback to next episode
                        player?.setMediaItem(MediaItem.fromUri(next.url))
                        player?.prepare()
                        player?.play()
                        glue?.title = next.name

                        Toast.makeText(requireContext(), next.name, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "No more episodes", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            binge.onCancel = { /* Stay on current episode */ }
            bingeOverlay = binge
        }

        // Initialize sleep timer
        sleepTimerManager = SleepTimerManager(requireActivity() as FragmentActivity).apply {
            setPlayer(player!!)
        }

        // Stream stats overlay (toggled via MENU key)
        val stats = StreamStatsOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            stats,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        stats.attachPlayer(player!!)
        statsOverlay = stats

        // Audio-only overlay
        val audioOverlay = AudioOnlyOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            audioOverlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        audioOnlyOverlay = audioOverlay

        // Remote control hints overlay (auto-dismiss)
        val hints = RemoteHintOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            hints,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val hintText = if (viewModel.contentType == ContentType.LIVE) {
            getString(R.string.hint_player_live)
        } else {
            getString(R.string.hint_player_vod)
        }
        hints.showHints(hintText, 5000)
        hintsOverlay = hints

        // Hide controls initially
        hideControlsOverlay(false)

        // Resume position for VOD/Series
        if (viewModel.contentType != ContentType.LIVE && !viewModel.hasResumed) {
            viewModel.getResumePosition { position ->
                if (position > 0) {
                    player?.seekTo(position)
                    viewModel.hasResumed = true
                }
            }
        }

        // Auto-save progress every 5s
        if (viewModel.contentType != ContentType.LIVE) {
            viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    delay(5_000)
                    val p = player ?: continue
                    val pos = p.currentPosition
                    val dur = p.duration
                    if (dur > 0) {
                        val pct = pos.toFloat() / dur.toFloat()
                        if (pct in 0.05f..0.95f) {
                            viewModel.saveProgress(pos, dur, pct)
                        }
                    }
                }
            }
        }

        // Binge mode: monitor playback position for series content
        if (viewModel.contentType == ContentType.SERIES) {
            viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    delay(1000)
                    val p = player ?: continue
                    val dur = p.duration
                    val pos = p.currentPosition
                    if (dur > 0 && pos > 0 && (dur - pos) < 15_000 && !bingeShown) {
                        bingeShown = true
                        // Pre-fetch next episode title for the overlay
                        val nextInfo = viewModel.resolveNextEpisode()
                        val nextTitle = nextInfo?.name ?: "Next Episode"
                        bingeOverlay?.show(nextTitle, 10)
                    }
                }
            }
        }

        // Error handling
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Show error toast, offer retry
                activity?.runOnUiThread {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Playback error: ${error.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }

    /** Switch playback to [channel] and update viewModel + glue state. */
    private fun tuneToChannel(channel: com.ooustream.iptv.data.model.LiveStream) {
        val url = viewModel.buildLiveUrl(channel)
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.play()
        glue?.title = channel.name
        viewModel.streamName = channel.name

        // Sync the viewModel index to the channel we just tuned to
        val channels = viewModel.channels.value
        val newIdx = channels.indexOf(channel)
        if (newIdx >= 0) {
            viewModel.setChannels(channels, newIdx)
        }
    }

    private fun showExternalPlayerDialog() {
        val ctx = context ?: return
        val players = ExternalPlayerLauncher.getAvailablePlayers(ctx)
        val names = players.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle("Open in External Player")
            .setItems(names) { _, which ->
                val selectedPlayer = players[which]
                player?.pause()
                val launched = ExternalPlayerLauncher.launch(
                    ctx, selectedPlayer, viewModel.streamUrl, viewModel.streamName
                )
                if (!launched) {
                    Toast.makeText(ctx, "Could not launch ${selectedPlayer.displayName}", Toast.LENGTH_SHORT).show()
                    player?.play()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Picture-in-Picture Support ---

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            // Hide all overlays and controls — PiP window is too small for them
            hideControlsOverlay(false)
            zapOverlay?.dismiss()
            statsOverlay?.let { if (it.visibility == View.VISIBLE) it.toggle() }
            hintsOverlay?.dismiss()

            // Register PiP action receiver for remote controls
            registerPipReceiver()
        } else {
            // Leaving PiP — restore normal state
            unregisterPipReceiver()
        }
    }

    private fun updatePipActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val activity = activity ?: return

        val actions = mutableListOf<RemoteAction>()

        // Play/Pause action
        val isPlaying = player?.isPlaying == true
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseIntent = PendingIntent.getBroadcast(
            activity, 0,
            Intent(ACTION_PIP_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        actions.add(RemoteAction(
            Icon.createWithResource(activity, playPauseIcon),
            playPauseTitle, playPauseTitle, playPauseIntent
        ))

        // Channel up/down for live TV
        if (viewModel.contentType == ContentType.LIVE) {
            val chUpIntent = PendingIntent.getBroadcast(
                activity, 1,
                Intent(ACTION_PIP_CHANNEL_UP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            actions.add(RemoteAction(
                Icon.createWithResource(activity, android.R.drawable.ic_media_previous),
                "Prev Channel", "Previous Channel", chUpIntent
            ))

            val chDownIntent = PendingIntent.getBroadcast(
                activity, 2,
                Intent(ACTION_PIP_CHANNEL_DOWN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            actions.add(RemoteAction(
                Icon.createWithResource(activity, android.R.drawable.ic_media_next),
                "Next Channel", "Next Channel", chDownIntent
            ))
        }

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
        activity.setPictureInPictureParams(params)
    }

    private fun registerPipReceiver() {
        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_PIP_PLAY_PAUSE -> {
                        if (player?.isPlaying == true) player?.pause() else player?.play()
                        updatePipActions()
                    }
                    ACTION_PIP_CHANNEL_UP -> {
                        val newChannel = viewModel.switchChannel(-1)
                        if (newChannel != null) tuneToChannel(newChannel)
                    }
                    ACTION_PIP_CHANNEL_DOWN -> {
                        val newChannel = viewModel.switchChannel(1)
                        if (newChannel != null) tuneToChannel(newChannel)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_CHANNEL_UP)
            addAction(ACTION_PIP_CHANNEL_DOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(pipReceiver, filter)
        }
        updatePipActions()
    }

    private fun unregisterPipReceiver() {
        pipReceiver?.let {
            try { requireContext().unregisterReceiver(it) } catch (_: Exception) { }
        }
        pipReceiver = null
    }

    // --- End Picture-in-Picture Support ---

    override fun onPause() {
        super.onPause()
        // In PiP mode, don't pause playback — the video should keep playing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInPictureInPictureMode == true) {
            player?.let { p ->
                viewLifecycleOwner.lifecycleScope.launch {
                    crashRecoveryManager.savePlaybackState(
                        streamUrl = viewModel.streamUrl,
                        contentType = viewModel.contentType.name,
                        streamId = viewModel.streamId,
                        streamName = viewModel.streamName,
                        position = p.currentPosition
                    )
                }
            }
            return
        }
        player?.let { p ->
            viewLifecycleOwner.lifecycleScope.launch {
                crashRecoveryManager.savePlaybackState(
                    streamUrl = viewModel.streamUrl,
                    contentType = viewModel.contentType.name,
                    streamId = viewModel.streamId,
                    streamName = viewModel.streamName,
                    position = p.currentPosition
                )
            }
        }
        player?.pause()
    }

    override fun onDestroyView() {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        unregisterPipReceiver()
        viewLifecycleOwner.lifecycleScope.launch {
            crashRecoveryManager.markCleanExit()
        }
        super.onDestroyView()
        zapOverlay?.dismiss()
        zapOverlay = null
        bingeOverlay?.dismiss()
        bingeOverlay = null
        sleepTimerManager?.cancel()
        sleepTimerManager = null
        statsOverlay?.cleanup()
        statsOverlay = null
        hintsOverlay?.dismiss()
        hintsOverlay = null
        audioOnlyOverlay?.dismiss()
        audioOnlyOverlay = null
        player?.release()
        player = null
        glue = null
    }

    companion object {
        private const val ACTION_PIP_PLAY_PAUSE = "com.ooustream.iptv.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_CHANNEL_UP = "com.ooustream.iptv.PIP_CHANNEL_UP"
        private const val ACTION_PIP_CHANNEL_DOWN = "com.ooustream.iptv.PIP_CHANNEL_DOWN"

        fun newInstance(
            streamUrl: String,
            contentType: ContentType,
            streamId: String,
            streamName: String,
            streamIcon: String = "",
            seriesId: Int = 0,
            seasonNum: Int = 0,
            episodeNum: Int = 0
        ): OoustreamPlaybackFragment {
            return OoustreamPlaybackFragment().apply {
                arguments = Bundle().apply {
                    putString("stream_url", streamUrl)
                    putString("content_type", contentType.name)
                    putString("stream_id", streamId)
                    putString("stream_name", streamName)
                    putString("stream_icon", streamIcon)
                    putInt("series_id", seriesId)
                    putInt("season_num", seasonNum)
                    putInt("episode_num", episodeNum)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            viewModel.streamUrl = it.getString("stream_url", "")
            viewModel.contentType = ContentType.valueOf(it.getString("content_type", "LIVE"))
            viewModel.streamId = it.getString("stream_id", "")
            viewModel.streamName = it.getString("stream_name", "")
            viewModel.streamIcon = it.getString("stream_icon", "")
            viewModel.seriesId = it.getInt("series_id", 0)
            viewModel.seasonNum = it.getInt("season_num", 0)
            viewModel.episodeNum = it.getInt("episode_num", 0)
        }
        // Consume channel list for live TV zapping
        if (viewModel.contentType == ContentType.LIVE) {
            val (channels, idx) = ChannelListHolder.consume()
            if (channels.isNotEmpty()) {
                viewModel.setChannels(channels, idx)
            }
        }
    }
}
