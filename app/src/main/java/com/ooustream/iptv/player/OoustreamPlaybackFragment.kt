package com.ooustream.iptv.player

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import android.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.ooustream.iptv.R
import com.ooustream.iptv.common.AdaptiveImageLoader
import com.ooustream.iptv.common.AudioLogger
import com.ooustream.iptv.common.AudioPipelineFactory
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
    private var audioStatusOverlay: AudioStatusOverlay? = null
    private var trackSelector: DefaultTrackSelector? = null
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
    private var stallDetectorJob: Job? = null
    private var frameWatchdogJob: Job? = null
    private var lastRenderedFrameCount: Int = -1
    private var audioFallbackAttempted = false
    private var userTrackOverrideActive = false
    private var channelSwitchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep screen on during playback (dynamically toggled by player listener)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val loadControl = if (am.memoryClass <= 128) {
            BufferConfigs.forLowMemory(viewModel.contentType)
        } else {
            BufferConfigs.forContentTypeAndQuality(viewModel.contentType, qualityPolicy.tier.value)
        }
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        // Verify FFmpeg extension loaded (native .so files from Jellyfin AAR)
        val ffmpegAvailable = AudioLogger.isFfmpegAvailable
        AudioLogger.log("FFmpeg available: $ffmpegAvailable")
        if (ffmpegAvailable) {
            AudioLogger.logFfmpegCodecs()
        } else {
            AudioLogger.log("WARNING: FFmpeg not loaded — AC3/DTS will use hardware decoder only")
        }

        // DefaultTrackSelector: AAC first (cheapest, hardware-decoded), FFmpeg handles surround fallback
        trackSelector = DefaultTrackSelector(requireContext()).apply {
            setParameters(
                buildUponParameters()
                    .setExceedRendererCapabilitiesIfNecessary(false) // Don't select codecs device can't decode
                    .setPreferredAudioMimeTypes(
                        MimeTypes.AUDIO_AAC,     // Hardware-decoded, lowest CPU
                        MimeTypes.AUDIO_E_AC3,   // FFmpeg fallback
                        MimeTypes.AUDIO_AC3,
                        MimeTypes.AUDIO_DTS,
                        MimeTypes.AUDIO_DTS_HD,
                    )
                    .setPreferredAudioLanguage("en")
                    .setPreferredTextLanguage("en")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setTunnelingEnabled(false) // Tunneled playback bypasses audio processor chain
            )
        }

        // Shared audio pipeline: stereo downmix (1-8ch), FFmpeg fallback, decoder fallback
        val renderersFactory = AudioPipelineFactory.createRenderersFactory(requireContext())

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        // Audio focus: ExoPlayer handles pause/duck/resume automatically
        player!!.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        AudioLogger.logPlayerCreated(hasTrackSelector = true, hasAudioAttributes = true)

        // Log which decoder handles each audio stream (verify FFmpeg extension is working)
        // "libffmpeg" = FFmpeg software decode, "OMX."/"c2." = hardware MediaCodec
        player!!.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                AudioLogger.logDecoderInitialized(decoderName, initializationDurationMs)
            }
        })

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
                        controlsBar?.bindSeries(
                            viewModel.streamName, viewModel.streamIcon,
                            viewModel.seasonNum, viewModel.episodeNum
                        )

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
        trackPicker.onTrackSelected = { trackType ->
            if (trackType == C.TRACK_TYPE_AUDIO) {
                userTrackOverrideActive = true
            }
        }
        trackPickerOverlay = trackPicker

        // Audio status indicator (no audio track, unsupported codec)
        val audioStatus = AudioStatusOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            audioStatus,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        audioStatusOverlay = audioStatus

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
                AudioLogger.logAudioError(error)
                // Audio decoder error: two-stage fallback
                if (isAudioDecoderError(error)) {
                    if (!audioFallbackAttempted) {
                        audioFallbackAttempted = true
                        // Stage 1: try alternate audio track (different codec, prefer English)
                        val altTrack = findAlternateAudioTrack()
                        if (altTrack != null) {
                            AudioLogger.log("Audio fallback: switching to alternate track")
                            player?.trackSelectionParameters = player?.trackSelectionParameters
                                ?.buildUpon()
                                ?.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                ?.setOverrideForType(altTrack)
                                ?.build() ?: return
                            player?.prepare()
                            player?.play()
                            return
                        }
                        // Stage 2: no alternate track — disable audio entirely, keep video playing
                        AudioLogger.log("Audio fallback: no alternate track, disabling audio")
                        player?.trackSelectionParameters = player?.trackSelectionParameters
                            ?.buildUpon()
                            ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            ?.build() ?: return
                        audioStatusOverlay?.showCodecUnsupported()
                        player?.prepare()
                        player?.play()
                        return
                    }
                }
                // Audio-specific errors: show indicator (video may still play)
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                        audioStatusOverlay?.showCodecUnsupported()
                }
                val maxRetries = maxRetriesForContent(viewModel.contentType)
                if (retryCount < maxRetries) {
                    val delayMs = RETRY_DELAYS_MS.getOrElse(retryCount) { 15_000L }
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
                    Player.STATE_BUFFERING -> {
                        showBufferingOverlay(true)
                        startStallDetector()
                    }
                    Player.STATE_READY -> {
                        showBufferingOverlay(false)
                        stallDetectorJob?.cancel()
                        retryCount = 0
                        startFrameWatchdog()
                    }
                    Player.STATE_ENDED -> {
                        showBufferingOverlay(false)
                        stallDetectorJob?.cancel()
                        frameWatchdogJob?.cancel()
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
                AudioLogger.logTrackSelection(tracks)
                // Don't override user's manual track selection from TrackPickerOverlay
                if (userTrackOverrideActive) return
                try {
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

                    // Show status overlay if stream has no audio tracks
                    if (audioGroups.isEmpty()) {
                        AudioLogger.logNoAudioTracks()
                        audioStatusOverlay?.showNoAudio()
                        return
                    }
                    audioStatusOverlay?.dismiss()

                    val hasSelectedAudio = audioGroups.any { group ->
                        (0 until group.length).any { group.isTrackSelected(it) }
                    }

                    // If audio is already selected AND it's English, nothing to do
                    if (hasSelectedAudio) {
                        val selectedIsEnglish = audioGroups.any { group ->
                            (0 until group.length).any { i ->
                                group.isTrackSelected(i) && isEnglishTrack(group.getTrackFormat(i))
                            }
                        }
                        if (selectedIsEnglish) {
                            AudioLogger.logLanguageSelected("en", null, fallback = false, "already English")
                            return
                        }
                    }

                    // Find English track by language code OR label
                    val englishGroup = audioGroups.firstOrNull { group ->
                        (0 until group.length).any { i -> isEnglishTrack(group.getTrackFormat(i)) }
                    }

                    // Select English if found, or first track if nothing selected
                    if (!hasSelectedAudio || englishGroup != null) {
                        val targetGroup = englishGroup ?: audioGroups[0]
                        val targetFormat = targetGroup.getTrackFormat(0)
                        val fallback = englishGroup == null
                        AudioLogger.logLanguageSelected(
                            targetFormat.language, targetFormat.label, fallback,
                            if (fallback) "no English, using first" else "English found"
                        )
                        val override = TrackSelectionOverride(targetGroup.mediaTrackGroup, 0)
                        player?.trackSelectionParameters = player?.trackSelectionParameters
                            ?.buildUpon()
                            ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            ?.setOverrideForType(override)
                            ?.build() ?: return
                    }
                } catch (_: Exception) { /* Safe to ignore — player uses default track selection */ }
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

    /** Detect audio decoder errors (e.g. AC3/EAC3 unsupported) vs video decoder errors. */
    private fun isAudioDecoderError(error: PlaybackException): Boolean {
        val code = error.errorCode
        if (code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
        if (code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FAILED) {
            // Check cause chain for audio renderer references
            var cause: Throwable? = error.cause
            while (cause != null) {
                val msg = cause.toString().lowercase()
                if (msg.contains("audio") || msg.contains("ac3") || msg.contains("eac3")) return true
                cause = cause.cause
            }
        }
        return false
    }

    /** Find an alternate audio track (preferring English). Used as fallback when current track fails. */
    private fun findAlternateAudioTrack(): TrackSelectionOverride? {
        val tracks = player?.currentTracks ?: return null
        val selectedMime = tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it).sampleMimeType } }
            .firstOrNull()
        var bestGroup: Tracks.Group? = null
        var bestIndex = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) continue // Skip the currently-failing track
                if (!group.isTrackSupported(i)) continue // Skip unsupported tracks
                val format = group.getTrackFormat(i)
                // Prefer a different codec than the one that failed
                if (format.sampleMimeType == selectedMime && bestGroup != null) continue
                if (bestGroup == null || isEnglishTrack(format)) {
                    bestGroup = group
                    bestIndex = i
                    if (isEnglishTrack(format)) break
                }
            }
            if (bestGroup != null && isEnglishTrack(bestGroup.getTrackFormat(bestIndex))) break
        }
        if (bestGroup == null) return null
        return TrackSelectionOverride(bestGroup.mediaTrackGroup, listOf(bestIndex))
    }

    private fun maxRetriesForContent(contentType: ContentType): Int = when (contentType) {
        ContentType.LIVE -> MAX_RETRIES_LIVE
        ContentType.VOD -> MAX_RETRIES_VOD
        ContentType.SERIES -> MAX_RETRIES_SERIES
    }

    private fun stallTimeoutForContent(contentType: ContentType): Long = when (contentType) {
        ContentType.LIVE -> STALL_TIMEOUT_LIVE_MS
        ContentType.VOD, ContentType.SERIES -> STALL_TIMEOUT_VOD_MS
    }

    /** Watchdog: if player stays in STATE_BUFFERING longer than the timeout, force a retry. */
    private fun startStallDetector() {
        stallDetectorJob?.cancel()
        stallDetectorJob = viewLifecycleOwner.lifecycleScope.launch {
            val timeout = stallTimeoutForContent(viewModel.contentType)
            delay(timeout)
            // Still buffering after timeout — force recovery
            val p = player ?: return@launch
            if (p.playbackState == Player.STATE_BUFFERING) {
                val maxRetries = maxRetriesForContent(viewModel.contentType)
                if (retryCount < maxRetries) {
                    val delayMs = RETRY_DELAYS_MS.getOrElse(retryCount) { 15_000L }
                    retryCount++
                    retryJob?.cancel()
                    p.stop()
                    delay(delayMs)
                    p.prepare()
                    p.play()
                } else {
                    showBufferingOverlay(false)
                    showErrorDialog(PlaybackException(
                        "Stream stalled — no data received",
                        null,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    ))
                }
            }
        }
    }

    /**
     * Frame watchdog: detects silent freezes where ExoPlayer is STATE_READY
     * but no new frames are rendering (decoder buffer debt).
     * Checks every 3s; if no new frames for 5s, forces a hard reset.
     */
    private fun startFrameWatchdog() {
        frameWatchdogJob?.cancel()
        lastRenderedFrameCount = -1
        frameWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            var noNewFramesSinceMs = 0L
            while (isActive) {
                delay(FRAME_WATCHDOG_INTERVAL_MS)
                val p = player ?: continue
                if (p.playbackState != Player.STATE_READY || !p.playWhenReady) {
                    noNewFramesSinceMs = 0L
                    continue
                }
                val counters = p.videoDecoderCounters ?: continue
                val currentFrames = counters.renderedOutputBufferCount
                if (lastRenderedFrameCount < 0) {
                    lastRenderedFrameCount = currentFrames
                    continue
                }
                if (currentFrames == lastRenderedFrameCount) {
                    if (noNewFramesSinceMs == 0L) {
                        noNewFramesSinceMs = android.os.SystemClock.elapsedRealtime()
                    }
                    val frozenMs = android.os.SystemClock.elapsedRealtime() - noNewFramesSinceMs
                    if (frozenMs >= FRAME_WATCHDOG_FROZEN_MS) {
                        AudioLogger.log("Frame watchdog: frozen ${frozenMs}ms, forcing hard reset")
                        noNewFramesSinceMs = 0L
                        lastRenderedFrameCount = -1
                        // Hard reset: same as what stall detector does
                        p.stop()
                        p.prepare()
                        p.play()
                    }
                } else {
                    noNewFramesSinceMs = 0L
                    lastRenderedFrameCount = currentFrames
                }
            }
        }
    }

    /** Matches English audio tracks by language code or label. */
    /** Matches English audio tracks by language code or label. */
    private fun isEnglishTrack(format: Format): Boolean {
        val lang = format.language?.lowercase()
        if (lang == "en" || lang == "eng" || lang == "en-us" || lang == "en-gb") return true
        val label = format.label?.lowercase() ?: return false
        return label == "english" || label.startsWith("english ")
    }

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
        val message = friendlyErrorMessage(error)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.error_stream)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ ->
                retryCount = 0
                audioFallbackAttempted = false
                userTrackOverrideActive = false
                trackSelector?.setParameters(
                    trackSelector!!.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                )
                player?.prepare()
                player?.play()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
            .setCancelable(false)
            .show()
    }

    private fun friendlyErrorMessage(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Unable to connect to the stream. Check your internet connection and try again."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "This stream is currently unavailable. It may be temporarily offline."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Stream not found. The content may have been removed."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            if (isAudioDecoderError(error))
                "Audio format not supported on this device. Try a different stream."
            else
                "Video format not supported on this device."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
            "Audio format not supported on this device."
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
            "Live stream fell behind. Reconnecting..."
        PlaybackException.ERROR_CODE_TIMEOUT ->
            "Stream timed out. The server may be slow or overloaded."
        else ->
            "Playback error. Please try again or choose a different stream."
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
                controlsBar?.bindSeries(
                    viewModel.streamName, viewModel.streamIcon,
                    viewModel.seasonNum, viewModel.episodeNum
                )

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

        // Reset audio state for new channel
        retryCount = 0
        audioFallbackAttempted = false
        userTrackOverrideActive = false

        // Re-enable audio in case it was disabled by Stage 2 fallback
        player?.trackSelectionParameters = player?.trackSelectionParameters
            ?.buildUpon()
            ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            ?.build() ?: return

        // Log session for previous channel before switching
        watchSessionLogger.endCurrentSession()
        watchSessionLogger.onChannelStarted(channel, null)

        // Mute before loading new source to prevent audio pop from previous stream
        player?.volume = 0f
        val url = viewModel.buildLiveUrl(channel)
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.play()
        player?.volume = 1f
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
                    ctx, selectedPlayer, viewModel.streamUrl, viewModel.streamName,
                    positionMs = player?.currentPosition ?: 0L
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
        audioStatusOverlay?.dismiss()
        audioStatusOverlay = null
        bufferingOverlay = null
        stallDetectorJob?.cancel()
        stallDetectorJob = null
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null
        retryJob?.cancel()
        retryJob = null
        // Release MediaSession before player
        mediaSession?.release()
        mediaSession = null
        // Release player last
        player?.release()
        player = null
        trackSelector = null
        glue = null
        super.onDestroyView()
    }

    companion object {
        private const val MAX_RETRIES_LIVE = 3
        private const val MAX_RETRIES_SERIES = 5
        private const val MAX_RETRIES_VOD = 6
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 5_000, 8_000, 12_000, 15_000)
        private const val STALL_TIMEOUT_LIVE_MS = 15_000L
        private const val STALL_TIMEOUT_VOD_MS = 30_000L
        private const val FRAME_WATCHDOG_INTERVAL_MS = 3_000L
        private const val FRAME_WATCHDOG_FROZEN_MS = 5_000L

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
