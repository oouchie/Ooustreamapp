package com.ooustream.iptv.player

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.ooustream.iptv.R
import com.ooustream.iptv.common.AdaptiveImageLoader
import com.ooustream.iptv.common.NetworkMonitor
import com.ooustream.iptv.common.QualityPolicy
import com.ooustream.iptv.common.RemoteHintOverlay
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.epg.SmartEpgFiller
import com.ooustream.iptv.recommendation.WatchSessionLogger
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OoustreamPlaybackFragment : VideoSupportFragment() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var adaptiveImageLoader: AdaptiveImageLoader
    @Inject lateinit var qualityPolicy: QualityPolicy
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var epgCacheRepository: com.ooustream.iptv.data.repository.EpgCacheRepository
    @Inject lateinit var smartEpgFiller: SmartEpgFiller
    @Inject lateinit var watchSessionLogger: WatchSessionLogger

    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var glue: OoustreamPlaybackGlue? = null
    private var zapOverlay: ChannelZapOverlay? = null
    private var bingeOverlay: BingeCountdownOverlay? = null
    private var sleepTimerManager: SleepTimerManager? = null
    private var statsOverlay: StreamStatsOverlay? = null
    private var hintsOverlay: RemoteHintOverlay? = null
    private var audioOnlyOverlay: AudioOnlyOverlay? = null
    private var watchNextOverlay: WatchNextOverlay? = null
    private var channelBanner: ChannelBannerOverlay? = null
    private var seriesCompleteOverlay: SeriesCompleteOverlay? = null
    private var seekFeedback: SeekFeedbackOverlay? = null
    private var trackPickerOverlay: TrackPickerOverlay? = null
    private var isAudioOnly = false
    private var bingeShown = false

    // Custom controls bar (replaces Leanback default controls)
    private var controlsBar: PlayerControlsBar? = null
    private var controlsManager: PlayerControlsManager? = null
    private var currentEpg: List<com.ooustream.iptv.data.model.EpgProgram> = emptyList()

    // Playback hardening state
    private var mediaSession: MediaSession? = null
    private var bufferingOverlay: View? = null
    private var retryCount = 0
    private var retryJob: Job? = null
    private var channelSwitchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep screen on during playback (dynamically toggled by player listener)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val loadControl = BufferConfigs.forContentTypeAndQuality(
            viewModel.contentType, qualityPolicy.tier.value
        )
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        // Prefer English audio tracks by default
        player!!.trackSelectionParameters = player!!.trackSelectionParameters
            .buildUpon()
            .setPreferredAudioLanguage("en")
            .build()

        // [Fix 2.2] Audio focus: ExoPlayer handles pause/duck/resume automatically
        player!!.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )

        // [Fix 2.1] MediaSession: tells system media is active (screensaver defense + Now Playing)
        mediaSession = MediaSession.Builder(requireContext(), player!!)
            .setId("ooustream_playback")
            .build()

        // Warn on low bandwidth before VOD/Series playback
        if (qualityPolicy.shouldWarnBeforeVod && viewModel.contentType != ContentType.LIVE) {
            Toast.makeText(requireContext(), "Low bandwidth detected. Playback may buffer.", Toast.LENGTH_LONG).show()
        }

        val playerAdapter = LeanbackPlayerAdapter(requireContext(), player!!, 1000)
        glue = OoustreamPlaybackGlue(requireContext(), playerAdapter).apply {
            host = VideoSupportFragmentGlueHost(this@OoustreamPlaybackFragment)
            isControlsOverlayAutoHideEnabled = false
            contentType = viewModel.contentType
            title = viewModel.streamName

            onChannelSwitch = { direction ->
                // [Fix 2.4] Debounced channel switch — zap overlay updates instantly, stream loads after 300ms
                val newChannel = viewModel.switchChannel(direction)
                if (newChannel != null) {
                    zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                    debouncedTune(newChannel)
                }
            }

            onZapConfirm = {
                zapOverlay?.dismiss()
            }

            isZapOverlayShowing = {
                zapOverlay?.isShowing == true
            }

            onAudioTrackClicked = { showTrackPicker() }
            onSubtitleTrackClicked = { showTrackPicker() }

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
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .build()
                    audioOnlyOverlay?.show(viewModel.streamName)
                } else {
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                        .build()
                    audioOnlyOverlay?.dismiss()
                }
            }

            // Seek/navigation callbacks — glue handles all key events in onKey()
            onSeekForward = { deltaMs ->
                seekFeedback?.showSeek(deltaMs)
                player?.let { p -> controlsBar?.updatePosition(p.currentPosition, p.duration) }
            }
            onSeekBackward = { deltaMs ->
                seekFeedback?.showSeek(-deltaMs)
                player?.let { p -> controlsBar?.updatePosition(p.currentPosition, p.duration) }
            }
            onNextEpisode = { skipToNextEpisode() }
            isTrackPickerShowing = { trackPickerOverlay?.isShowing == true }
            onDismissTrackPicker = { trackPickerOverlay?.dismiss() }
            // Back handling moved to OnBackPressedCallback below (glue's onKey
            // only intercepts when focus is inside Leanback's BrowseFrameLayout,
            // which is bypassed when our custom controls bar has focus)
        }

        // Trim image cache to free memory for video playback
        adaptiveImageLoader.trimForPlayback()

        // Record play event for analytics
        viewModel.recordPlayStart()

        // Start session tracking for live TV recommendations
        if (viewModel.contentType == ContentType.LIVE) {
            val channels = viewModel.channels.value
            val idx = viewModel.currentChannelIndex.value
            channels.getOrNull(idx)?.let { channel ->
                watchSessionLogger.onChannelStarted(channel, null)
            }
        }

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

        // Channel banner overlay for live TV (shows on channel switch)
        if (viewModel.contentType == ContentType.LIVE) {
            val banner = ChannelBannerOverlay(requireContext())
            (view as? ViewGroup)?.addView(
                banner,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            channelBanner = banner

            // Show banner for the initial channel after a brief delay (wait for stream to start)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(500)
                showChannelBanner()
            }
        }

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
                    viewModel.markCompleted()
                    val next = viewModel.resolveNextEpisode()
                    if (next != null) {
                        viewModel.streamUrl = next.url
                        viewModel.streamId = next.episodeId
                        viewModel.streamName = next.name
                        viewModel.seasonNum = next.season
                        viewModel.episodeNum = next.episodeNum
                        bingeShown = false

                        player?.setMediaItem(MediaItem.fromUri(next.url))
                        player?.prepare()
                        player?.play()
                        glue?.title = next.name

                        Toast.makeText(requireContext(), next.name, Toast.LENGTH_SHORT).show()
                    } else {
                        seriesCompleteOverlay?.show(viewModel.streamName)
                    }
                }
            }
            binge.onCancel = { /* Stay on current episode */ }
            bingeOverlay = binge

            // Series Complete overlay
            val complete = SeriesCompleteOverlay(requireContext())
            (view as? ViewGroup)?.addView(
                complete,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            complete.onReplay = {
                player?.seekTo(0)
                player?.play()
            }
            complete.onExit = {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
            seriesCompleteOverlay = complete
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

        // Seek feedback overlay (+10s / -10s)
        val seekOv = SeekFeedbackOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            seekOv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        seekFeedback = seekOv

        // Track picker overlay (audio + subtitle switching)
        val trackPicker = TrackPickerOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            trackPicker,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        trackPicker.onDismissed = {
            controlsManager?.resumeAutoHide()
        }
        trackPickerOverlay = trackPicker

        // Watch Next overlay for VOD end-of-movie suggestions
        if (viewModel.contentType == ContentType.VOD) {
            val watchNext = WatchNextOverlay(requireContext())
            (view as? ViewGroup)?.addView(
                watchNext,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            watchNext.onMovieSelected = { item ->
                val ext = item.containerExtension ?: "mp4"
                val url = viewModel.buildVodStreamUrl(item.streamId, ext)
                val fragment = newInstance(
                    streamUrl = url,
                    contentType = ContentType.VOD,
                    streamId = item.streamId.toString(),
                    streamName = item.name,
                    streamIcon = item.icon ?: ""
                )
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
            watchNextOverlay = watchNext
        }

        // Remote control hints overlay (auto-dismiss)
        val hints = RemoteHintOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            hints,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val hintText = when (viewModel.contentType) {
            ContentType.LIVE -> getString(R.string.hint_player_live)
            ContentType.SERIES -> getString(R.string.hint_player_series)
            ContentType.VOD -> getString(R.string.hint_player_vod)
        }
        hints.showHints(hintText, 5000)
        hintsOverlay = hints

        // Replace default flat scrim with cinematic gradient
        view.findViewById<View>(androidx.leanback.R.id.playback_fragment_background)
            ?.setBackgroundResource(R.drawable.bg_playback_scrim)

        // Hide Leanback default controls permanently — we use custom PlayerControlsBar
        hideControlsOverlay(false)

        // ─── Custom Controls Bar ────────────────────────────────────────────
        controlsBar = PlayerControlsBar(requireContext()).also { bar ->
            (view as? ViewGroup)?.addView(
                bar,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            controlsManager = PlayerControlsManager(bar, viewModel.contentType)
            controlsManager?.onVisibilityChanged = { visible ->
                if (visible) bar.requestFocusOnPlayPause()
            }

            // Wire glue to our controls manager
            glue?.customControlsManager = controlsManager

            // Wire action button callbacks
            bar.onPlayPause = {
                player?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                    bar.updatePlayPauseIcon(p.isPlaying)
                }
            }
            bar.onSeekBack = {
                player?.let { p ->
                    val newPos = (p.currentPosition - 10_000).coerceAtLeast(0)
                    p.seekTo(newPos)
                    seekFeedback?.showSeek(-10_000)
                    bar.updatePosition(newPos, p.duration)
                }
            }
            bar.onSeekForward = {
                player?.let { p ->
                    val newPos = p.currentPosition + 10_000
                    p.seekTo(newPos)
                    seekFeedback?.showSeek(10_000)
                    bar.updatePosition(newPos, p.duration)
                }
            }
            bar.onAspectRatio = { cycleAspectRatio() }
            bar.onTracksClicked = { showTrackPicker() }
            bar.onExternalPlayer = { showExternalPlayerDialog() }
            bar.onDpadSeek = { deltaMs ->
                player?.let { p ->
                    val newPos = (p.currentPosition + deltaMs).coerceAtLeast(0)
                    p.seekTo(newPos)
                    seekFeedback?.showSeek(deltaMs)
                    bar.updatePosition(newPos, p.duration)
                    controlsManager?.resetAutoHideTimer()
                }
            }

            // Live TV specific callbacks
            bar.onPrevChannel = {
                val newChannel = viewModel.switchChannel(-1)
                if (newChannel != null) {
                    zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                    debouncedTune(newChannel)
                }
            }
            bar.onNextChannel = {
                val newChannel = viewModel.switchChannel(+1)
                if (newChannel != null) {
                    zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                    debouncedTune(newChannel)
                }
            }
            bar.onChannelList = {
                zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
            }

            // Initial content binding
            viewLifecycleOwner.lifecycleScope.launch {
                when (viewModel.contentType) {
                    ContentType.LIVE -> {
                        val channels = viewModel.channels.value
                        val idx = viewModel.currentChannelIndex.value
                        val channel = channels.getOrNull(idx)
                        if (channel != null) {
                            val epg = try { epgCacheRepository.getEpg(channel.streamId) } catch (_: Exception) { emptyList() }
                            currentEpg = epg
                            val now = System.currentTimeMillis() / 1000
                            val currentProg = epg.find { p ->
                                val start = p.startTimestamp?.toLongOrNull() ?: return@find false
                                val end = p.stopTimestamp?.toLongOrNull() ?: return@find false
                                now in start..end
                            }
                            val inferred = if (currentProg?.title == null) {
                                smartEpgFiller.getSmartEpg(null, channel.streamId, channel.name, null)
                            } else {
                                smartEpgFiller.learnPattern(channel.streamId, channel.name, currentProg.title!!)
                                null
                            }
                            bar.bindLive(channel, epg, idx, inferred)
                        }
                    }
                    ContentType.VOD -> bar.bindVod(viewModel.streamName, viewModel.streamIcon)
                    ContentType.SERIES -> bar.bindSeries(
                        viewModel.streamName, viewModel.streamIcon,
                        viewModel.seasonNum, viewModel.episodeNum
                    )
                }
            }
        }

        // ─── Back Button Handling ────────────────────────────────────────────
        // Leanback's key interceptor lives on BrowseFrameLayout (playback_controls_dock),
        // NOT on the fragment root. When our custom PlayerControlsBar has focus, Back
        // bypasses the interceptor entirely and hits Activity.onBackPressed() directly.
        // This callback catches Back at the Activity level regardless of focus location.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (trackPickerOverlay?.isShowing == true) {
                        trackPickerOverlay?.dismiss()
                        return
                    }
                    if (controlsManager?.isVisible == true) {
                        controlsManager?.hide()
                        return
                    }
                    if (watchNextOverlay?.isShowing == true) {
                        watchNextOverlay?.dismiss()
                    }
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        )

        // Position update coroutine (1s interval) for controls bar
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                val p = player ?: continue
                val bar = controlsBar ?: continue
                when (viewModel.contentType) {
                    ContentType.LIVE -> {
                        val needsReload = bar.updateLiveProgress(currentEpg)
                        if (needsReload) {
                            // EPG program ended — reload
                            val channels = viewModel.channels.value
                            val idx = viewModel.currentChannelIndex.value
                            val channel = channels.getOrNull(idx)
                            if (channel != null) {
                                val epg = try { epgCacheRepository.getEpg(channel.streamId) } catch (_: Exception) { emptyList() }
                                currentEpg = epg
                                bar.bindLive(channel, epg, idx)
                            }
                        }
                    }
                    else -> bar.updatePosition(p.currentPosition, p.duration)
                }
                bar.updatePlayPauseIcon(p.isPlaying)
                bar.setQualityBadge(p.videoFormat?.height)
            }
        }

        // Resume position for VOD/Series
        if (viewModel.contentType != ContentType.LIVE && !viewModel.hasResumed) {
            viewModel.getResumePosition { position ->
                if (position > 0) {
                    player?.seekTo(position)
                    viewModel.hasResumed = true
                }
            }
        }

        // Auto-save progress every 5s (no upper bound — completed flag handles removal)
        if (viewModel.contentType != ContentType.LIVE) {
            viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    delay(5_000)
                    val p = player ?: continue
                    val pos = p.currentPosition
                    val dur = p.duration
                    if (dur > 0) {
                        val pct = pos.toFloat() / dur.toFloat()
                        if (pct > 0.05f) {
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
                        val nextInfo = viewModel.resolveNextEpisode()
                        if (nextInfo != null) {
                            bingeOverlay?.show(nextInfo.name, 10)
                        } else {
                            seriesCompleteOverlay?.show(viewModel.streamName)
                        }
                    }
                }
            }
        }

        // [Fix 1.2 + 3.2] Comprehensive player listener: error retry, buffering, dynamic keepScreenOn
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (retryCount < MAX_AUTO_RETRIES) {
                    val delayMs = RETRY_DELAYS_MS.getOrElse(retryCount) { 5_000L }
                    retryCount++
                    retryJob?.cancel()
                    retryJob = viewLifecycleOwner.lifecycleScope.launch {
                        showBufferingOverlay(true)
                        delay(delayMs)
                        val p = player ?: return@launch
                        p.prepare()
                        p.play()
                    }
                } else {
                    showBufferingOverlay(false)
                    showErrorDialog(error)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> showBufferingOverlay(true)
                    Player.STATE_READY -> {
                        showBufferingOverlay(false)
                        retryCount = 0
                    }
                    Player.STATE_ENDED -> {
                        showBufferingOverlay(false)
                        if (viewModel.contentType != ContentType.LIVE) {
                            val p = player
                            val dur = p?.duration ?: 0
                            if (dur > 0) {
                                viewModel.saveProgress(dur, dur, 1.0f)
                            }
                            viewModel.markCompleted()
                            // Series: insert "Up Next" entry for next episode
                            if (viewModel.contentType == ContentType.SERIES) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    viewModel.advanceSeriesOnCompletion()
                                }
                            }
                        }
                        if (viewModel.contentType == ContentType.VOD) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val suggestions = viewModel.getWatchNextSuggestions()
                                if (suggestions.isNotEmpty()) {
                                    watchNextOverlay?.show(suggestions)
                                } else {
                                    activity?.onBackPressedDispatcher?.onBackPressed()
                                }
                            }
                        }
                    }
                    Player.STATE_IDLE -> { /* no-op */ }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Auto-select audio track if none is selected (fixes no-sound on some streams)
                // Prefer English, fall back to first available
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val hasSelectedAudio = audioGroups.any { group ->
                    (0 until group.length).any { group.isTrackSelected(it) }
                }
                if (!hasSelectedAudio && audioGroups.isNotEmpty()) {
                    // Try to find an English track first
                    val englishGroup = audioGroups.firstOrNull { group ->
                        (0 until group.length).any { i ->
                            val lang = group.getTrackFormat(i).language?.lowercase()
                            lang == "en" || lang == "eng" || lang?.startsWith("en") == true
                        }
                    }
                    val targetGroup = englishGroup ?: audioGroups[0]
                    val override = TrackSelectionOverride(targetGroup.mediaTrackGroup, 0)
                    player?.trackSelectionParameters = player?.trackSelectionParameters
                        ?.buildUpon()
                        ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        ?.setOverrideForType(override)
                        ?.build() ?: return
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // [Fix 3.2] Dynamic keepScreenOn: allow screensaver when paused
                if (isPlaying) {
                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        })

        // [Fix 1.3] Network-aware playback recovery
        viewLifecycleOwner.lifecycleScope.launch {
            var previouslyConnected = true
            networkMonitor.state.collect { netState ->
                if (!netState.isConnected && previouslyConnected) {
                    previouslyConnected = false
                    showBufferingOverlay(true)
                } else if (netState.isConnected && !previouslyConnected) {
                    previouslyConnected = true
                    val p = player ?: return@collect
                    if (p.playbackState == Player.STATE_IDLE || p.playerError != null) {
                        retryCount = 0
                        p.prepare()
                        p.play()
                    }
                }
            }
        }
    }

    // --- Playback Hardening Helpers ---

    private fun showBufferingOverlay(show: Boolean) {
        if (show) {
            if (bufferingOverlay == null) {
                val spinner = ProgressBar(requireContext()).apply {
                    isIndeterminate = true
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                }
                (view as? ViewGroup)?.addView(spinner)
                bufferingOverlay = spinner
            }
            bufferingOverlay?.visibility = View.VISIBLE
        } else {
            bufferingOverlay?.visibility = View.GONE
        }
    }

    private fun showErrorDialog(error: PlaybackException) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.error_stream)
            .setMessage(error.message ?: getString(R.string.error_general))
            .setPositiveButton(R.string.retry) { _, _ ->
                retryCount = 0
                player?.prepare()
                player?.play()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
            .setCancelable(false)
            .show()
    }

    /** Skip to the next episode in a series. */
    private fun skipToNextEpisode() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.markCompleted()
            val next = viewModel.resolveNextEpisode()
            if (next != null) {
                viewModel.streamUrl = next.url
                viewModel.streamId = next.episodeId
                viewModel.streamName = next.name
                viewModel.seasonNum = next.season
                viewModel.episodeNum = next.episodeNum
                bingeShown = false

                player?.setMediaItem(MediaItem.fromUri(next.url))
                player?.prepare()
                player?.play()
                glue?.title = next.name

                Toast.makeText(requireContext(), next.name, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.no_more_episodes, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** [Fix 2.4] Debounced channel tune — cancels previous load, waits 300ms before starting stream. */
    private fun debouncedTune(channel: com.ooustream.iptv.data.model.LiveStream) {
        channelSwitchJob?.cancel()
        channelSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            tuneToChannel(channel)
        }
    }

    /** Switch playback to [channel] and update viewModel + glue state. */
    private fun tuneToChannel(channel: com.ooustream.iptv.data.model.LiveStream) {
        // Auto-close track picker on channel switch
        if (trackPickerOverlay?.isShowing == true) trackPickerOverlay?.dismiss()

        // Log session for previous channel before switching
        watchSessionLogger.endCurrentSession()
        watchSessionLogger.onChannelStarted(channel, null)

        val url = viewModel.buildLiveUrl(channel)
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.play()
        glue?.title = channel.name
        viewModel.streamName = channel.name
        viewModel.streamId = channel.streamId.toString()

        // Sync the viewModel index to the channel we just tuned to
        val channels = viewModel.channels.value
        val newIdx = channels.indexOf(channel)
        if (newIdx >= 0) {
            viewModel.setChannels(channels, newIdx)
        }

        // Show channel banner after brief delay (let stream start)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            showChannelBanner()
        }

        // Update custom controls bar with new channel info
        viewLifecycleOwner.lifecycleScope.launch {
            val epg = try { epgCacheRepository.getEpg(channel.streamId) } catch (_: Exception) { emptyList() }
            currentEpg = epg

            // Check for current program to decide if we need inferred EPG
            val now = System.currentTimeMillis() / 1000
            val currentProg = epg.find { p ->
                val start = p.startTimestamp?.toLongOrNull() ?: return@find false
                val end = p.stopTimestamp?.toLongOrNull() ?: return@find false
                now in start..end
            }
            val inferred = if (currentProg?.title == null) {
                smartEpgFiller.getSmartEpg(null, channel.streamId, channel.name, null)
            } else {
                smartEpgFiller.learnPattern(channel.streamId, channel.name, currentProg.title!!)
                null
            }

            controlsBar?.bindLive(channel, epg, viewModel.currentChannelIndex.value, inferred)
        }
    }

    /** Fetch EPG and show the pre-roll channel banner for the current channel. */
    private fun showChannelBanner() {
        val channels = viewModel.channels.value
        val idx = viewModel.currentChannelIndex.value
        val channel = channels.getOrNull(idx) ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val epg = try {
                epgCacheRepository.getEpg(channel.streamId)
            } catch (_: Exception) {
                emptyList()
            }

            // Check if real EPG has a current program
            val now = System.currentTimeMillis() / 1000
            val currentProgram = epg.find { p ->
                val start = p.startTimestamp?.toLongOrNull() ?: return@find false
                val end = p.stopTimestamp?.toLongOrNull() ?: return@find false
                now in start..end
            }

            val inferredEpg = if (currentProgram?.title == null) {
                // No real EPG — use SmartEpgFiller
                smartEpgFiller.getSmartEpg(null, channel.streamId, channel.name, null)
            } else {
                // Good EPG — learn pattern for future inference
                smartEpgFiller.learnPattern(channel.streamId, channel.name, currentProgram.title!!)
                null
            }

            channelBanner?.show(channel, idx, epg, inferredEpg)
        }
    }

    /** Opens the track picker overlay (audio + subtitle tracks). */
    private fun showTrackPicker() {
        val p = player ?: return
        controlsManager?.pauseAutoHide()
        trackPickerOverlay?.show(p)
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

    // [Fix 2.3] Restart live TV after returning from background
    override fun onResume() {
        super.onResume()
        if (viewModel.contentType == ContentType.LIVE) {
            player?.let { p ->
                if (!p.isPlaying) {
                    p.seekToDefaultPosition()
                    p.play()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // End live TV session for recommendation tracking
        if (viewModel.contentType == ContentType.LIVE) {
            watchSessionLogger.onPlayerExit()
        }
        player?.let { p ->
            // Explicit progress save on exit for VOD/Series (no upper bound)
            if (viewModel.contentType != ContentType.LIVE) {
                val pos = p.currentPosition
                val dur = p.duration
                if (dur > 0) {
                    val pct = pos.toFloat() / dur.toFloat()
                    if (pct > 0.05f) {
                        viewModel.saveProgress(pos, dur, pct)
                    }
                }
            }
        }
        player?.pause()
    }

    // ─── Suppress Leanback default controls ────────────────────────────
    override fun showControlsOverlay(runAnimation: Boolean) {
        // No-op: suppress Leanback default controls — using custom PlayerControlsBar
    }

    // ─── Aspect Ratio Cycling ────────────────────────────────────────────
    private var currentScalingMode = 0
    private val scalingModes = intArrayOf(
        C.VIDEO_SCALING_MODE_SCALE_TO_FIT,
        C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    )
    private val scalingLabels = arrayOf("Fit", "Crop/Fill")

    private fun cycleAspectRatio() {
        currentScalingMode = (currentScalingMode + 1) % scalingModes.size
        player?.videoScalingMode = scalingModes[currentScalingMode]
        Toast.makeText(requireContext(), "Aspect: ${scalingLabels[currentScalingMode]}", Toast.LENGTH_SHORT).show()
    }

    // [Fix 1.1] Correct lifecycle order: clean up everything BEFORE super tears down view hierarchy
    override fun onDestroyView() {
        // Write back final channel index for LiveTvFragment to pick up on resume
        if (viewModel.contentType == ContentType.LIVE) {
            val channels = viewModel.channels.value
            val idx = viewModel.currentChannelIndex.value
            ChannelListHolder.lastPlayedIndex = idx
            ChannelListHolder.lastPlayedChannel = channels.getOrNull(idx)
        }
        // Safety net: ensure screen can sleep after player exits
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Cancel async jobs
        retryJob?.cancel()
        channelSwitchJob?.cancel()
        // Clean up custom controls bar
        controlsManager?.destroy()
        controlsManager = null
        controlsBar?.cleanup()
        controlsBar = null
        // Dismiss all overlays while view hierarchy is still alive
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
        watchNextOverlay?.dismiss()
        watchNextOverlay = null
        channelBanner?.dismiss()
        channelBanner = null
        seriesCompleteOverlay?.dismiss()
        seriesCompleteOverlay = null
        seekFeedback?.dismiss()
        seekFeedback = null
        trackPickerOverlay?.dismiss()
        trackPickerOverlay = null
        bufferingOverlay = null
        // Release MediaSession before player
        mediaSession?.release()
        mediaSession = null
        // Release player last
        player?.release()
        player = null
        glue = null
        super.onDestroyView()
    }

    companion object {
        private const val MAX_AUTO_RETRIES = 3
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 5_000)

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
