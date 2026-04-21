package com.ooustream.iptv.player

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
import androidx.media3.common.text.CueGroup
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaSession
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import androidx.media3.ui.SubtitleView
import com.ooustream.iptv.BuildConfig
import com.ooustream.iptv.R
import com.ooustream.iptv.common.AdaptiveImageLoader
import com.ooustream.iptv.common.DeviceTier
import com.ooustream.iptv.common.DeviceTierDetector
import com.ooustream.iptv.common.AudioLogger
import com.ooustream.iptv.common.AudioPipelineFactory
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.NetworkMonitor
import com.ooustream.iptv.common.QualityPolicy
import com.ooustream.iptv.common.RemoteHintOverlay
import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.epg.SmartEpgFiller
import com.ooustream.iptv.recommendation.WatchSessionLogger
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class OoustreamPlaybackFragment : VideoSupportFragment(), com.ooustream.iptv.KeyEventHandler {

    // Lock Leanback to BG_NONE — prevent green brand color overlay
    private var bgLocked = false
    override fun setBackgroundType(type: Int) {
        super.setBackgroundType(BG_NONE)
    }

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var adaptiveImageLoader: AdaptiveImageLoader
    @Inject lateinit var qualityPolicy: QualityPolicy
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var epgCacheRepository: com.ooustream.iptv.data.repository.EpgCacheRepository
    @Inject lateinit var smartEpgFiller: SmartEpgFiller
    @Inject lateinit var watchSessionLogger: WatchSessionLogger
    @Inject lateinit var subtitlePreferences: SubtitlePreferences
    @Inject lateinit var streamDiagnosticLogger: StreamDiagnosticLogger

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
    private val chapterManager = ChapterManager()
    private var trackPickerOverlay: TrackPickerOverlay? = null
    private var subtitleView: SubtitleView? = null
    private var audioStatusOverlay: AudioStatusOverlay? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var isAudioOnly = false
    private var bingeShown = false

    // Custom controls bar (replaces Leanback default controls)
    private var controlsBar: PlayerControlsBar? = null
    private var controlsManager: PlayerControlsManager? = null
    private var currentEpg: List<com.ooustream.iptv.data.model.EpgProgram> = emptyList()
    private var lastEpgRefreshMs = 0L

    // Playback hardening state
    private var mediaSession: MediaSession? = null
    private var bufferingOverlay: View? = null
    private var retryCount = 0
    private var retryJob: Job? = null
    private var stallDetectorJob: Job? = null
    private var frameWatchdogJob: Job? = null
    private var lastRenderedFrameCount: Int = -1
    private var audioFallbackAttempted = false
    private var audioDisabledByFallback = false // Stage 2 disabled audio — don't re-enable in onTracksChanged
    // Media3 1.9.0 MatroskaExtractor is stricter about EBML varints than 1.2.1.
    // Some IPTV-transcoded MKVs break mid-stream. On first hit, retry from position 0.
    private var mkvVarintRecoveryAttempted = false
    private var userTrackOverrideActive = false
    private var subtitlesTemporarilyEnabled = false
    // Cue listener stored as field so we can re-attach it after SW/FFmpeg player rebuilds.
    // Anonymous listeners created inside configureSubtitleView were orphaned on the old
    // (released) player instance when the player was rebuilt — killing CC silently.
    private var cueListener: Player.Listener? = null
    private var channelSwitchJob: Job? = null
    private var diagnosticListener: ExoPlayerDiagnosticListener? = null
    private var healthMonitor: PlaybackHealthMonitor? = null
    private var usingSoftwareVideoDecoder = false
    // Cache video codec string from first TRACKS_CHANGED so we can identify the format
    // (e.g. HEVC Main 10) even after a SW rebuild where videoFormat becomes null
    private var cachedVideoCodecs: String = ""
    private var cachedVideoMime: String = ""
    // Early libVLC swap tracking — prevents infinite swap loops if VLC also fails
    private var earlyVlcSwapAttempted = false
    // Track recent MediaCodecVideoRenderer errors for fast-fail on broken streams
    private val recentVideoRendererErrors = ArrayDeque<Long>()
    // libVLC secondary backend for codecs ExoPlayer can't decode (HEVC Main 10, etc.)
    // When active, all controlsBar callbacks route to libVLC instead of ExoPlayer.
    private var libVlc: org.videolan.libvlc.LibVLC? = null
    private var vlcMediaPlayer: org.videolan.libvlc.MediaPlayer? = null
    private var vlcVideoLayout: org.videolan.libvlc.util.VLCVideoLayout? = null
    private var useVlcBackend = false
    private var vlcPositionJob: Job? = null
    private var vlcHasRenderedFirstFrame = false
    private var vlcTransitionOverlay: View? = null
    private var corePlayerListener: Player.Listener? = null
    // Buffer storm detection: rapid BUFFERING→READY cycling on amlogic HEVC+EAC3
    private var bufferStormCount = 0
    private var bufferStormWindowStart = 0L
    private var ffmpegRebuildAttemptedForBufferStorm = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Kill Leanback green: set BG_NONE immediately
        backgroundType = BG_NONE
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Set again after glue host initializes (it resets backgroundType to BG_DARK)
        view.post {
            backgroundType = BG_NONE
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            killAllGreen()
        }
        // Belt-and-suspenders: kill green at multiple delays (Leanback re-applies at various points)
        view.postDelayed({ backgroundType = BG_NONE; killAllGreen() }, 200)
        view.postDelayed({ backgroundType = BG_NONE; killAllGreen() }, 500)
        view.postDelayed({ backgroundType = BG_NONE; killAllGreen() }, 1000)

        // Keep screen on during playback (dynamically toggled by player listener)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Coil's LRU memory cache evicts under pressure on its own; nuking the whole
        // cache here made Home re-shimmer every return from playback. Let LRU do its job.
        // trimForPlayback() below applies tier-aware trimming only if needed.

        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // AFTMM (mt8695, 1285MB RAM) has memoryClass ~160 but only 164-184MB actual heap.
        // 60s buffer at HIGH quality fills the heap and causes OOM in PlayerControlsBar.formatDuration().
        // Threshold raised from 128 to 192 to match watchdog's low-memory classification.
        val loadControl = if (am.memoryClass <= 192) {
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
                    .setPreferredTextLanguage(subtitlePreferences.preferredLanguage)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlePreferences.subtitlesEnabled)
                    .setTunnelingEnabled(false) // Tunneled playback bypasses audio processor chain
            )
        }

        // v3.5.9: Unified device tier detection (replaces inline memoryClass checks)
        val deviceTier = DeviceTierDetector.tier(requireContext())
        streamDiagnosticLogger.logAppEvent("DEVICE_TIER", DeviceTierDetector.describe(requireContext()))
        AudioLogger.log("Device tier: ${DeviceTierDetector.describe(requireContext())}")

        // Apply tier-appropriate video resolution cap. ULTRA_LOW/LOW: cap at 1080p
        // to block 4K variants (rare on IPTV but a safety net). 1080p AVC hardware
        // decode usually works fine on these devices — HEVC Main 10 is filtered
        // separately in onTracksChanged via canDecodeHevcMain10().
        val maxSize = DeviceTierDetector.maxVideoSize(requireContext())
        if (maxSize != null) {
            trackSelector!!.setParameters(
                trackSelector!!.buildUponParameters()
                    .setMaxVideoSize(maxSize.first, maxSize.second)
            )
            AudioLogger.log("$deviceTier tier: capped video to ${maxSize.first}x${maxSize.second}")
            streamDiagnosticLogger.logAppEvent("RESOLUTION_CAP",
                "maxRes=${maxSize.first}x${maxSize.second}, ${DeviceTierDetector.describe(requireContext())}")
        }

        // HIGH/MID tier devices (Shield, AFTKRT, good Android TVs) get the highest
        // HLS variant the server publishes. Without this, DefaultTrackSelector picks
        // the first qualifying track, so 1080p-capable devices may stall on 720p.
        // LOW/ULTRA_LOW stay on default adaptive behaviour — forcing top bitrate would
        // stutter on mt8695-class chips.
        if (deviceTier == DeviceTier.HIGH || deviceTier == DeviceTier.MID) {
            trackSelector!!.setParameters(
                trackSelector!!.buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
            )
            AudioLogger.log("$deviceTier tier: forceHighestSupportedBitrate=true")
        }

        // Shared audio pipeline: stereo downmix (1-8ch), FFmpeg fallback, decoder fallback
        // MTK devices: deprioritize OMX.MTK video decoders + async mode (black screen bug)
        val renderersFactory = AudioPipelineFactory.createMtkAwareRenderersFactory(requireContext())
        if (AudioPipelineFactory.isMtkDevice()) {
            AudioLogger.log("MTK device detected (${android.os.Build.HARDWARE}): using MTK-aware codec selector + async mode")
            streamDiagnosticLogger.logAppEvent("MTK_DEVICE", "hw=${android.os.Build.HARDWARE}")
        }

        // Bandwidth meter — shared with health monitor for real network throughput
        val bandwidthMeter = DefaultBandwidthMeter.Builder(requireContext()).build()

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(renderersFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory()))
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

        // Stream diagnostic listener — logs all ExoPlayer events to rolling file
        // Resolve content name: live TV uses channel list, VOD/Series uses streamName
        val initialContentName = if (viewModel.contentType == ContentType.LIVE) {
            viewModel.channels.value.getOrNull(viewModel.currentChannelIndex.value)?.name
                ?: viewModel.streamName.ifBlank { "unknown" }
        } else {
            viewModel.streamName.ifBlank { "unknown" }
        }
        diagnosticListener = ExoPlayerDiagnosticListener(streamDiagnosticLogger, initialContentName)
        player!!.addListener(diagnosticListener!!)
        player!!.addAnalyticsListener(diagnosticListener!!)

        // Log stream start for all content types
        streamDiagnosticLogger.logStreamStart(
            channelName = initialContentName,
            url = viewModel.streamUrl,
            codec = null, // filled by ExoPlayerDiagnosticListener on decoder init
            resolution = null,
            protocol = when {
                viewModel.streamUrl.contains(".m3u8", ignoreCase = true) -> "HLS"
                viewModel.streamUrl.contains(".mpd", ignoreCase = true) -> "DASH"
                viewModel.streamUrl.contains(".ts", ignoreCase = true) -> "MPEG-TS"
                else -> "HTTP"
            }
        )
        streamDiagnosticLogger.logAppEvent("CONTENT_TYPE", "type=${viewModel.contentType.name}, id=${viewModel.streamId}")

        // Playback health monitor — periodic buffer/memory/black screen checks
        healthMonitor = PlaybackHealthMonitor(streamDiagnosticLogger, lifecycleScope).apply {
            channelName = initialContentName
            this.bandwidthMeter = bandwidthMeter
            start(player!!)
        }

        // [Fix 2.1] MediaSession: tells system media is active (screensaver defense + Now Playing)
        // Release any lingering session from a previous fragment instance
        mediaSession?.release()
        mediaSession = null
        mediaSession = MediaSession.Builder(requireContext(), player!!)
            .setId("ooustream_playback_${System.nanoTime()}")
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
            onCcToggle = { toggleClosedCaptions() }
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

        // Proactive libVLC for .mkv VOD/Series: Media3 1.9.0 MatroskaExtractor has a regression
        // that causes silent freezes (no error, just stops feeding frames) on some IPTV-transcoded
        // MKVs. libVLC's demuxer handles these cleanly. Routing upfront avoids a ~17s watchdog wait.
        val forceBeginningArg = arguments?.getBoolean("force_start_from_beginning", false) == true
        val needsResume = viewModel.contentType != ContentType.LIVE && !viewModel.hasResumed && !forceBeginningArg

        if (shouldStartWithVlc(viewModel.streamUrl, viewModel.contentType) &&
            swapToVlcInPlace(silentTransition = true)) {
            streamDiagnosticLogger.logAppEvent("VLC_START_UPFRONT",
                "reason=mkv_vod_series, url=${viewModel.streamUrl.take(50)}")
        } else if (needsResume) {
            // Resolve resume position before prepare and pass it to setMediaItem so the
            // player buffers from the resume offset directly — avoids the seek-after-prepare
            // pattern that forced users to watch 2-3s of opening logo before jumping.
            viewLifecycleOwner.lifecycleScope.launch {
                val resumePos = viewModel.getResumePositionSync().coerceAtLeast(0L)
                val p = player ?: return@launch
                p.setMediaItem(MediaItem.fromUri(viewModel.streamUrl), resumePos)
                p.prepare()
                p.play()
                if (resumePos > 0) viewModel.hasResumed = true
            }
        } else {
            player?.setMediaItem(MediaItem.fromUri(viewModel.streamUrl))
            player?.prepare()
            player?.play()
            if (forceBeginningArg) viewModel.hasResumed = true
        }

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

        // Kill green again after all overlays are added to the view hierarchy
        view.post { killAllGreen() }

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
            // Re-disable subtitles if picker was dismissed without selecting one
            if (subtitlesTemporarilyEnabled) {
                subtitlesTemporarilyEnabled = false
                if (!subtitlePreferences.subtitlesEnabled) {
                    player?.let { p ->
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    }
                }
            }
        }
        trackPicker.onTrackSelected = { trackType ->
            if (trackType == C.TRACK_TYPE_AUDIO) {
                userTrackOverrideActive = true
            }
            if (trackType == C.TRACK_TYPE_TEXT) {
                // User explicitly chose a subtitle (or "Off") — don't re-disable on dismiss
                subtitlesTemporarilyEnabled = false
                // Check if subtitles are now disabled (user chose "Off") or enabled
                val textDisabled = player?.trackSelectionParameters
                    ?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
                subtitlePreferences.subtitlesEnabled = !textDisabled
                controlsBar?.updateCcState(!textDisabled)
                // Save selected language for future sessions
                if (!textDisabled) {
                    saveSelectedSubtitleLanguage()
                }
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

        // Configure SubtitleView with TV-optimized defaults
        configureSubtitleView(view)

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

            // Mobile: tapping the scrim area of controls bar hides it
            bar.onScrimTap = { controlsManager?.hide() }

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
            bar.onCcToggle = { toggleClosedCaptions() }
            bar.updateCcState(subtitlePreferences.subtitlesEnabled)
            bar.onExternalPlayer = { showExternalPlayerDialog() }
            bar.onStatsToggle = { statsOverlay?.toggle() }
            bar.onPrevChapter = {
                player?.let { p ->
                    val pos = chapterManager.prevChapterMs(p.currentPosition)
                    p.seekTo(pos)
                    seekFeedback?.dismiss()
                    bar.updatePosition(pos, p.duration)
                    bar.updateChapterIndicator(chapterManager.formatChapter(pos))
                    controlsManager?.resetAutoHideTimer()
                }
            }
            bar.onNextChapter = {
                player?.let { p ->
                    val pos = chapterManager.nextChapterMs(p.currentPosition)
                    if (pos != null) {
                        p.seekTo(pos)
                        seekFeedback?.dismiss()
                        bar.updatePosition(pos, p.duration)
                        bar.updateChapterIndicator(chapterManager.formatChapter(pos))
                        controlsManager?.resetAutoHideTimer()
                    }
                }
            }
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
                            lastEpgRefreshMs = System.currentTimeMillis()
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

        // ─── Mobile Touch Gestures ──────────────────────────────────────────
        // On non-TV devices: single tap toggles controls, double tap play/pause,
        // horizontal fling seeks (VOD/Series) or zaps channels (Live)
        if (!DeviceUtils.isTV(requireContext())) {
            setupTouchGestures(view)
        }

        // Position update coroutine (1s interval) for controls bar
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                val p = player ?: continue
                val bar = controlsBar ?: continue
                when (viewModel.contentType) {
                    ContentType.LIVE -> {
                        val needsReload = bar.updateLiveProgress(currentEpg)
                        val now = System.currentTimeMillis()
                        // Refresh EPG when: program ended, or every 5 minutes
                        val periodicRefreshDue = now - lastEpgRefreshMs > 5 * 60 * 1000L
                        if (needsReload || periodicRefreshDue) {
                            val channels = viewModel.channels.value
                            val idx = viewModel.currentChannelIndex.value
                            val channel = channels.getOrNull(idx)
                            if (channel != null) {
                                // First try cache; if no current program found, force-refresh from server
                                var epg = try { epgCacheRepository.getEpg(channel.streamId) } catch (_: Exception) { emptyList() }
                                val nowSec = now / 1000
                                val hasCurrent = epg.any { prog ->
                                    val s = prog.startTimestamp?.toLongOrNull() ?: return@any false
                                    val e = prog.stopTimestamp?.toLongOrNull() ?: return@any false
                                    nowSec in s..e
                                }
                                if (!hasCurrent) {
                                    epg = try { epgCacheRepository.forceRefresh(channel.streamId) } catch (_: Exception) { emptyList() }
                                }
                                currentEpg = epg
                                lastEpgRefreshMs = now
                                bar.bindLive(channel, epg, idx)
                            }
                        }
                    }
                    else -> {
                        bar.updatePosition(p.currentPosition, p.duration)
                        // Generate chapters once duration is known, update indicator
                        if (p.duration > 0) {
                            chapterManager.generate(p.duration)
                            bar.updateChapterIndicator(chapterManager.formatChapter(p.currentPosition))
                        }
                    }
                }
                bar.updatePlayPauseIcon(p.isPlaying)
                bar.setQualityBadge(p.videoFormat?.height)
            }
        }

        // Resume is handled in the initial setMediaItem(item, positionMs) path above —
        // no post-prepare seek needed. This block intentionally removed in v3.6.3.

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
        corePlayerListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AudioLogger.logAudioError(error)
                // Fast-fail for persistent video renderer errors on the same stream.
                // Some H.264 encodings (e.g. some Chicago series files) trip both HW and SW
                // decoders with repeated MediaCodecVideoRenderer errors. Without fast-fail,
                // the retry ladder cycles ~15 times (>2 minutes) before giving up.
                // Threshold: 3 video renderer errors within 30 seconds → swap to libVLC.
                val errMsg = error.message?.lowercase() ?: ""
                if (errMsg.contains("mediacodecvideorenderer")) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    recentVideoRendererErrors.addLast(now)
                    // Drop entries older than 30s
                    while (recentVideoRendererErrors.isNotEmpty()
                        && now - recentVideoRendererErrors.first() > 30_000L) {
                        recentVideoRendererErrors.removeFirst()
                    }
                    if (recentVideoRendererErrors.size >= 3 && !earlyVlcSwapAttempted) {
                        earlyVlcSwapAttempted = true
                        AudioLogger.log("3+ MediaCodecVideoRenderer errors in 30s — swapping to libVLC")
                        streamDiagnosticLogger.logAppEvent("EARLY_VLC_SWAP",
                            "reason=video_renderer_error_repeat, count=${recentVideoRendererErrors.size}, codecs=$cachedVideoCodecs, channel=${healthMonitor?.channelName ?: "unknown"}")
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            val swapped = swapToVlcInPlace()
                            if (!swapped) {
                                // VLC guard blocked for this URL — player has already errored
                                // and we're out of in-player recovery paths for this content.
                                showFriendlyError("This content uses a video format not supported on this device.")
                            }
                        }
                        return
                    }
                }
                // Audio decoder error: three-stage fallback
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
                        // Stage 1.5: force FFmpeg for AC3/EAC3 by rebuilding player with EXTENSION_RENDERER_MODE_PREFER
                        // Some devices falsely claim hardware AC3/EAC3 support but crash at runtime.
                        // FFmpeg handles these codecs correctly via software decode + stereo downmix.
                        if (AudioLogger.isFfmpegAvailable) {
                            AudioLogger.log("Audio fallback: hardware codec failed, rebuilding player with FFmpeg-preferred mode")
                            streamDiagnosticLogger.logAppEvent("AUDIO_FALLBACK",
                                "stage=1.5_ffmpeg_prefer, channel=${healthMonitor?.channelName ?: "unknown"}")
                            rebuildPlayerWithFfmpegPreferred()
                            return
                        }
                        // Stage 2: no FFmpeg available — disable audio entirely, keep video playing
                        AudioLogger.log("Audio fallback: no alternate track, no FFmpeg, disabling audio")
                        audioDisabledByFallback = true
                        player?.trackSelectionParameters = player?.trackSelectionParameters
                            ?.buildUpon()
                            ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            ?.build() ?: return
                        audioStatusOverlay?.showCodecUnsupported()
                        player?.prepare()
                        player?.play()
                        return
                    }
                    // audioFallbackAttempted is true — Stage 1.5 FFmpeg also failed
                    // Stage 2: disable audio entirely, keep video playing
                    if (!audioDisabledByFallback) {
                        audioDisabledByFallback = true
                        AudioLogger.log("Audio fallback: FFmpeg also failed, disabling audio entirely")
                        streamDiagnosticLogger.logAppEvent("AUDIO_FALLBACK",
                            "stage=2_disable_audio, channel=${healthMonitor?.channelName ?: "unknown"}")
                        player?.trackSelectionParameters = player?.trackSelectionParameters
                            ?.buildUpon()
                            ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            ?.build() ?: return
                        audioStatusOverlay?.showCodecUnsupported()
                        player?.prepare()
                        player?.play()
                        return
                    }
                    // Both FFmpeg and audio-disable already attempted — fall through to generic retry
                }
                // Audio-specific errors: show indicator (video may still play)
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                        audioStatusOverlay?.showCodecUnsupported()
                }
                // Media3 1.9.0 MatroskaExtractor varint regression: the extractor throws
                // IllegalStateException("No valid varint length mask found") on some IPTV MKVs
                // when resuming mid-stream. Retrying the same resume position loops forever.
                // Recovery: play from position 0 (user loses the resume point on this stream only).
                if (isMkvVarintError(error) && !mkvVarintRecoveryAttempted) {
                    mkvVarintRecoveryAttempted = true
                    AudioLogger.log("MKV varint error — restarting from position 0 (losing resume)")
                    streamDiagnosticLogger.logAppEvent("MKV_VARINT_RECOVERY",
                        "lost_resume_ms=${player?.currentPosition ?: 0}, channel=${healthMonitor?.channelName ?: "unknown"}")
                    retryJob?.cancel()
                    retryJob = viewLifecycleOwner.lifecycleScope.launch {
                        showBufferingOverlay(true)
                        val p = player ?: return@launch
                        p.seekTo(0)
                        p.prepare()
                        p.play()
                    }
                    return
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
                        // Buffer storm detection: rapid BUFFERING→READY cycling
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (bufferStormWindowStart == 0L || now - bufferStormWindowStart > BUFFER_STORM_WINDOW_MS) {
                            bufferStormWindowStart = now
                            bufferStormCount = 0
                        }
                        bufferStormCount++
                        if (bufferStormCount >= BUFFER_STORM_THRESHOLD && !ffmpegRebuildAttemptedForBufferStorm) {
                            val isHevc = player?.videoFormat?.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_H265
                            if (isHevc && AudioLogger.isFfmpegAvailable) {
                                ffmpegRebuildAttemptedForBufferStorm = true
                                bufferStormCount = 0
                                val channelName = healthMonitor?.channelName ?: "unknown"
                                AudioLogger.log("Buffer storm detected on HEVC — rebuilding with FFmpeg-preferred audio")
                                streamDiagnosticLogger.logAppEvent("BUFFER_STORM_FFMPEG_REBUILD",
                                    "storms=$BUFFER_STORM_THRESHOLD, isAmlogic=${AudioPipelineFactory.isAmlogicDevice()}, channel=$channelName")
                                rebuildPlayerWithFfmpegPreferred()
                            }
                        }
                    }
                    Player.STATE_READY -> {
                        showBufferingOverlay(false)
                        stallDetectorJob?.cancel()
                        retryCount = 0
                        // Only start watchdog if not already running — restarting it
                        // resets watchdogResetCount to 0, preventing escalation to SW decoder.
                        // Seek flushes cause BUFFERING→READY which was restarting the watchdog
                        // every ~8s, trapping the recovery ladder at step 1 forever.
                        if (frameWatchdogJob?.isActive != true) {
                            startFrameWatchdog()
                        }
                    }
                    Player.STATE_ENDED -> {
                        showBufferingOverlay(false)
                        stallDetectorJob?.cancel()
                        frameWatchdogJob?.cancel()
                        // Live streams should never end — server dropped connection, auto-retry
                        if (viewModel.contentType == ContentType.LIVE) {
                            streamDiagnosticLogger.logAppEvent("LIVE_STREAM_ENDED", "channel=${glue?.title}, auto-retrying")
                            viewLifecycleOwner.lifecycleScope.launch {
                                delay(1000)
                                val p = player ?: return@launch
                                p.seekToDefaultPosition()
                                p.prepare()
                                p.play()
                            }
                            return
                        }
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
                // Cache video codec/mime before any player rebuild loses this info —
                // used by watchdog give-up path to identify HEVC Main 10 content
                tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { group ->
                    if (group.length > 0) {
                        val videoFormat = group.getTrackFormat(0)
                        cachedVideoMime = videoFormat.sampleMimeType ?: ""
                        cachedVideoCodecs = videoFormat.codecs ?: ""
                    }
                }
                // v3.5.9: Device-aware HEVC Main 10 routing.
                //
                // On ULTRA_LOW/LOW tier (mt8695-class), HEVC Main 10 is not playable
                // by any path — hardware doesn't support profile 2, software HEVC is
                // ~8fps on Cortex-A53, and libVLC's HEVC decoder native-crashes on
                // mt8695 (the v3.5.7 regression). Show a graceful error immediately
                // instead of attempting an unwatchable swap.
                //
                // On MID/HIGH tier, the existing early VLC swap continues to work: the
                // libVLC backend can handle HEVC Main 10 on capable hardware.
                if (cachedVideoMime == androidx.media3.common.MimeTypes.VIDEO_H265
                    && (cachedVideoCodecs.startsWith("hvc1.2") || cachedVideoCodecs.startsWith("hev1.2"))) {

                    if (!DeviceTierDetector.canDecodeHevcMain10(requireContext())) {
                        // ULTRA_LOW / LOW tier — refuse to attempt, show friendly error.
                        earlyVlcSwapAttempted = true // prevent watchdog from retrying
                        AudioLogger.log("HEVC Main 10 on ${DeviceTierDetector.tier(requireContext())} tier — hardware can't decode, refusing playback")
                        streamDiagnosticLogger.logAppEvent("STREAM_CAPABILITY_CHECK",
                            "result=unsupported, reason=hevc_main10_low_tier, codecs=$cachedVideoCodecs, " +
                            "${DeviceTierDetector.describe(requireContext())}, channel=${healthMonitor?.channelName ?: "unknown"}")
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            showFriendlyError(
                                "This channel is streaming in HDR (HEVC 10-bit) which your device can't play. " +
                                "Try a different channel or contact support."
                            )
                        }
                        return
                    }

                    // MID/HIGH tier — early VLC swap (existing v3.5.6 behavior)
                    if (!earlyVlcSwapAttempted
                        && !usingSoftwareVideoDecoder
                        && AudioPipelineFactory.isMtkDevice()) {
                        earlyVlcSwapAttempted = true
                        AudioLogger.log("HEVC Main 10 on MTK device (MID+ tier) — swapping to libVLC preemptively")
                        streamDiagnosticLogger.logAppEvent("EARLY_VLC_SWAP",
                            "reason=hevc_main10_mtk, codecs=$cachedVideoCodecs, channel=${healthMonitor?.channelName ?: "unknown"}")
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            swapToVlcInPlace()
                        }
                        return
                    }
                }
                // Don't override user's manual track selection from TrackPickerOverlay
                if (userTrackOverrideActive) return
                // Don't re-enable audio if Stage 2 fallback intentionally disabled it
                if (audioDisabledByFallback) return
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
        }
        attachPlayerListener()

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

    /** Attach the core Player.Listener (error handling, track changes, state). Idempotent. */
    private fun attachPlayerListener() {
        val listener = corePlayerListener ?: return
        val p = player ?: return
        p.removeListener(listener) // prevent double-registration
        p.addListener(listener)
    }

    /** Safely releases a player instance, catching exceptions from stuck decoders (e.g. MTK). */
    private fun safeReleasePlayer(p: ExoPlayer) {
        try { p.stop() } catch (_: Exception) { }
        try { p.clearVideoSurface() } catch (_: Exception) { }
        try { p.release() } catch (e: Exception) {
            AudioLogger.log("Player release error (caught): ${e.message}")
        }
    }

    /** Detect audio decoder errors (e.g. AC3/EAC3 unsupported) vs video decoder errors. */
    private fun isAudioDecoderError(error: PlaybackException): Boolean {
        val code = error.errorCode
        if (code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
        // Check error message directly — ExoPlayer wraps renderer errors with class name
        // e.g. "MediaCodecAudioRenderer error" from mt8695 devices with false AC3/EAC3 support
        val errorMsg = error.message?.lowercase() ?: ""
        if (errorMsg.contains("audiorenderer") || errorMsg.contains("audio_renderer") ||
            errorMsg.contains("mediacodecaudiorenderer")) return true
        if (code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FAILED) {
            // Check cause chain for audio renderer references
            var cause: Throwable? = error.cause
            while (cause != null) {
                val msg = cause.toString().lowercase()
                if (msg.contains("audio") || msg.contains("ac3") || msg.contains("eac3") ||
                    msg.contains("mediacodecaudiorenderer")) return true
                cause = cause.cause
            }
        }
        return false
    }

    /**
     * Returns true if this URL should bypass ExoPlayer entirely and start on libVLC.
     * Media3 1.9.0's MatroskaExtractor has a regression that causes silent freezes on
     * IPTV-transcoded MKVs — libVLC's demuxer handles them cleanly. Routing upfront
     * avoids the ~17s frame-watchdog wait users would otherwise experience.
     *
     * Only applies to VOD/SERIES. Live streams are skipped because they're rarely MKV
     * and the crash guard + watchdog recovery are already good enough there.
     */
    private fun shouldStartWithVlc(url: String, contentType: ContentType): Boolean {
        if (contentType != ContentType.VOD && contentType != ContentType.SERIES) return false
        // Strip query string before extension check (some IPTV backends append ?token=...)
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mkv")
    }

    /**
     * Detects the Media3 1.9.0 MatroskaExtractor varint regression.
     * Symptom: IllegalStateException("No valid varint length mask found") in the cause chain,
     * thrown from VarintReader.readUnsignedVarint during MKV playback.
     */
    private fun isMkvVarintError(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            val msg = cause.message?.lowercase() ?: ""
            if (msg.contains("no valid varint length mask")) return true
            cause = cause.cause
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
     *
     * Escalating recovery ladder — each step tries something DIFFERENT:
     *   1. Seek flush (50ms, no rebuild)
     *   2. SW decoder at 720p on low-mem devices, or current res on others
     *   3. 720p + SW decoder
     *   4. 480p + SW decoder
     *   5. Give up with user-facing error
     *
     * KEY FIX: The watchdog counter only resets after SUSTAINED playback
     * (3 consecutive polls with new frames = 6 seconds). A single rendered
     * frame no longer resets the ladder — MTK decoders render 1 frame
     * then die, which was causing infinite step 1→2 loops.
     */
    private fun startFrameWatchdog() {
        frameWatchdogJob?.cancel()
        lastRenderedFrameCount = -1
        frameWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            var noNewFramesSinceMs = 0L
            var watchdogResetCount = 0
            var consecutiveGoodPolls = 0  // Must reach 3 (6s) before resetting ladder
            // Track whether HW decoder has ever sustained 24fps — if yes, black screens
            // are rebuffer recovery issues, not decoder incompatibility. Don't escalate to SW.
            var hwDecoderProvenGood = false
            var watchdogOverlayShown = false
            // HEVC slideshow detection: SW HEVC decoder renders frames but too slowly (8fps)
            // because OMX.google.hevc.decoder can't keep up with 1080p on mt8695.
            // Track consecutive polls where fps is catastrophically low.
            var consecutiveSlideshowPolls = 0
            val isLowMemory = run {
                val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.memoryClass <= 192
            }

            while (isActive) {
                delay(FRAME_WATCHDOG_INTERVAL_MS)
                val p = player ?: continue
                if (p.playbackState != Player.STATE_READY || !p.playWhenReady) {
                    noNewFramesSinceMs = 0L
                    continue
                }
                // Counters can be null if the video decoder failed to initialize.
                // This happens with HEVC Main 10 on mt8696 where the rebuilt SW player
                // never creates a video decoder. Treat null as 0 frames so the frozen
                // timer escalates instead of silently skipping forever.
                val currentFrames = p.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                if (lastRenderedFrameCount < 0) {
                    lastRenderedFrameCount = currentFrames
                    continue
                }
                if (currentFrames == lastRenderedFrameCount) {
                    consecutiveGoodPolls = 0  // Reset sustained-playback counter
                    if (noNewFramesSinceMs == 0L) {
                        noNewFramesSinceMs = android.os.SystemClock.elapsedRealtime()
                    }
                    val frozenMs = android.os.SystemClock.elapsedRealtime() - noNewFramesSinceMs
                    // Show buffering overlay so user sees spinner instead of black screen
                    if (frozenMs >= FRAME_WATCHDOG_INTERVAL_MS && !watchdogOverlayShown) {
                        watchdogOverlayShown = true
                        withContext(Dispatchers.Main) {
                            showBufferingOverlay(true)
                            android.widget.Toast.makeText(context,
                                "Optimizing video playback…", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (frozenMs >= FRAME_WATCHDOG_FROZEN_MS) {
                        watchdogResetCount++
                        noNewFramesSinceMs = 0L
                        lastRenderedFrameCount = -1
                        val channelName = healthMonitor?.channelName ?: "unknown"

                        // Fast-fail: if we're already on the SW decoder and its counters are still
                        // null, the decoder never initialized. No point running steps 3/4 (they just
                        // restart the same broken SW player). Give up immediately.
                        val swDecoderFailedToInit = usingSoftwareVideoDecoder && p.videoDecoderCounters == null

                        if (watchdogResetCount > MAX_WATCHDOG_RESETS || swDecoderFailedToInit) {
                            // ExoPlayer gave up — either HW decoder silently died (HEVC Main 10
                            // on mt8696 MTK) or SW fallback couldn't init. Either way, this codec
                            // can't be decoded natively by ExoPlayer on this device.
                            val codecs = cachedVideoCodecs.ifEmpty { p.videoFormat?.codecs ?: "" }
                            val mime = cachedVideoMime.ifEmpty { p.videoFormat?.sampleMimeType ?: "" }
                            val decoderNull = p.videoDecoderCounters == null
                            val reason = if (swDecoderFailedToInit) "sw_decoder_init_failed" else "max_resets"
                            AudioLogger.log("Frame watchdog: giving up ($reason) — swapping to libVLC fallback")
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_GIVE_UP",
                                "reason=$reason, resets=$watchdogResetCount, sw=$usingSoftwareVideoDecoder, decoderNull=$decoderNull, codecs=$codecs, mime=$mime, channel=$channelName")
                            // Swap to libVLC fallback fragment — keeps user in the app
                            withContext(Dispatchers.Main) {
                                val swapped = swapToVlcInPlace()
                                if (!swapped) {
                                    // Already walked the whole SW decoder ladder and libVLC
                                    // is guarded for this URL. No more recovery paths.
                                    showFriendlyError("This content uses a video format not supported on this device.")
                                }
                            }
                            return@launch
                        }

                        // If HW decoder has proven it works (sustained 24fps), black screens
                        // are rebuffer recovery issues. Use hard reset (stop/prepare/play)
                        // instead of escalating to SW decoder which would be worse.
                        if (hwDecoderProvenGood && !usingSoftwareVideoDecoder) {
                            // On amlogic, HEVC hard resets lead to buffer storms.
                            // After 2 hard resets, try FFmpeg audio (EAC3 interaction fix)
                            if (AudioPipelineFactory.isAmlogicDevice() && watchdogResetCount >= 2
                                && !ffmpegRebuildAttemptedForBufferStorm) {
                                val isHevc = p.videoFormat?.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_H265
                                if (isHevc && AudioLogger.isFfmpegAvailable) {
                                    ffmpegRebuildAttemptedForBufferStorm = true
                                    AudioLogger.log("Frame watchdog: amlogic HEVC — FFmpeg audio rebuild after $watchdogResetCount hard resets")
                                    streamDiagnosticLogger.logAppEvent("WATCHDOG_AMLOGIC_FFMPEG",
                                        "resets=$watchdogResetCount, channel=$channelName")
                                    rebuildPlayerWithFfmpegPreferred()
                                    continue
                                }
                            }
                            AudioLogger.log("Frame watchdog: HW decoder proven good — hard reset (step $watchdogResetCount)")
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_HARD_RESET",
                                "reset=$watchdogResetCount, hwProven=true, channel=$channelName")
                            val pos = p.currentPosition
                            p.stop()
                            p.seekTo(pos)
                            p.prepare()
                            p.play()
                            continue
                        }

                        // Escalating recovery: each step tries something DIFFERENT
                        // On MTK devices, if the HW decoder has NEVER proven itself (no sustained
                        // playback), the problem stream is fundamentally incompatible with ExoPlayer
                        // on this chipset. SW fallback almost always fails too (c2.android decoder
                        // doesn't handle these edge-case streams). Skip straight to libVLC on step 2
                        // instead of wasting 60-90 seconds on SW rebuild → 720p cap → 480p cap.
                        if (watchdogResetCount == 2
                            && AudioPipelineFactory.isMtkDevice()
                            && !hwDecoderProvenGood
                            && !usingSoftwareVideoDecoder) {
                            AudioLogger.log("Frame watchdog: step 2 MTK shortcut — swapping straight to libVLC")
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_MTK_VLC_SHORTCUT",
                                "reset=2, hwProven=false, channel=$channelName")
                            val swapped = withContext(Dispatchers.Main) {
                                swapToVlcInPlace()
                            }
                            if (swapped) {
                                return@launch
                            }
                            // v3.5.8: libVLC crash guard blocked the swap because this
                            // specific URL previously crashed libVLC (or a previous swap
                            // was interrupted by process death). Fall through to the
                            // software decoder + 720p cap path instead of giving up —
                            // plain AVC usually decodes fine in c2.android at 720p on
                            // mt8695, which is much better than a hard error dialog.
                            AudioLogger.log("Frame watchdog: VLC guard blocked — falling through to software decoder at 720p")
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_MTK_SW_FALLBACK",
                                "reset=2, reason=vlc_guard_blocked, channel=$channelName")
                            withContext(Dispatchers.Main) {
                                trackSelector?.setParameters(
                                    trackSelector!!.buildUponParameters().setMaxVideoSize(1280, 720)
                                )
                                rebuildPlayerWithSoftwareDecoder()
                            }
                            return@launch
                        }

                        when (watchdogResetCount) {
                            1 -> {
                                // Step 1: Seek flush — forces decoder to reset output pipeline
                                AudioLogger.log("Frame watchdog: step 1 — seek flush")
                                streamDiagnosticLogger.logAppEvent("WATCHDOG_SEEK_FLUSH", "reset=1, channel=$channelName")
                                p.seekTo(p.currentPosition)
                            }
                            2 -> {
                                // Step 2: Software decoder (non-MTK devices, or MTK with proven HW)
                                // On low-memory devices: cap to 720p IMMEDIATELY (1080p SW = instant OOM)
                                if (isLowMemory) {
                                    AudioLogger.log("Frame watchdog: step 2 — 720p + software decoder (low-memory device)")
                                    streamDiagnosticLogger.logAppEvent("WATCHDOG_SW_720P", "reset=2, lowMem=true, channel=$channelName")
                                    trackSelector?.setParameters(
                                        trackSelector!!.buildUponParameters().setMaxVideoSize(1280, 720)
                                    )
                                } else {
                                    AudioLogger.log("Frame watchdog: step 2 — software decoder")
                                    streamDiagnosticLogger.logAppEvent("WATCHDOG_SW_FALLBACK", "reset=2, channel=$channelName")
                                }
                                rebuildPlayerWithSoftwareDecoder()
                            }
                            3 -> {
                                // Step 3: Cap to 720p + SW decoder (reduces memory + CPU by ~50%)
                                AudioLogger.log("Frame watchdog: step 3 — 720p cap + software decoder")
                                streamDiagnosticLogger.logAppEvent("WATCHDOG_720P_CAP", "reset=3, channel=$channelName")
                                trackSelector?.setParameters(
                                    trackSelector!!.buildUponParameters().setMaxVideoSize(1280, 720)
                                )
                                if (!usingSoftwareVideoDecoder) rebuildPlayerWithSoftwareDecoder()
                                else { player?.stop(); player?.prepare(); player?.play() }
                            }
                            4 -> {
                                // Step 4: Cap to 480p + SW decoder (last resort — playable on any device)
                                AudioLogger.log("Frame watchdog: step 4 — 480p cap + software decoder")
                                streamDiagnosticLogger.logAppEvent("WATCHDOG_480P_CAP", "reset=4, channel=$channelName")
                                trackSelector?.setParameters(
                                    trackSelector!!.buildUponParameters().setMaxVideoSize(854, 480)
                                )
                                if (!usingSoftwareVideoDecoder) rebuildPlayerWithSoftwareDecoder()
                                else { player?.stop(); player?.prepare(); player?.play() }
                            }
                        }
                    }
                } else {
                    noNewFramesSinceMs = 0L
                    watchdogOverlayShown = false
                    val framesDelta = currentFrames - lastRenderedFrameCount
                    // Check if HW decoder is rendering at full framerate (20+ fps = proven good)
                    // This means black screens are rebuffer recovery, not decoder incompatibility
                    if (!hwDecoderProvenGood && !usingSoftwareVideoDecoder) {
                        // FRAME_WATCHDOG_INTERVAL_MS = 2s, so 20fps = 40+ frames per poll
                        // Use a lower threshold (30 frames) to account for timing jitter
                        if (framesDelta >= 30) {
                            hwDecoderProvenGood = true
                            AudioLogger.log("Frame watchdog: HW decoder proven good ($framesDelta frames in ${FRAME_WATCHDOG_INTERVAL_MS}ms)")
                        }
                    }
                    // HEVC slideshow detection: OMX.google.hevc.decoder on MTK renders
                    // frames but at ~8fps (1080p HEVC is too heavy for software decode).
                    // The existing watchdog never triggers because frames ARE rendering.
                    // Detect sustained catastrophically low fps and swap to libVLC.
                    if (!earlyVlcSwapAttempted && !useVlcBackend
                        && cachedVideoMime == androidx.media3.common.MimeTypes.VIDEO_H265
                        && AudioPipelineFactory.isMtkDevice()
                        && framesDelta in 1 until HEVC_SLIDESHOW_MIN_FRAMES) {
                        consecutiveSlideshowPolls++
                        if (consecutiveSlideshowPolls >= HEVC_SLIDESHOW_POLLS) {
                            val actualFps = framesDelta.toFloat() / (FRAME_WATCHDOG_INTERVAL_MS / 1000f)
                            AudioLogger.log("Frame watchdog: HEVC slideshow detected — ${actualFps.toInt()}fps sustained for ${consecutiveSlideshowPolls * FRAME_WATCHDOG_INTERVAL_MS / 1000}s, swapping to libVLC")
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_HEVC_SLIDESHOW",
                                "fps=${actualFps.toInt()}, polls=$consecutiveSlideshowPolls, framesDelta=$framesDelta, channel=${healthMonitor?.channelName ?: "unknown"}")
                            earlyVlcSwapAttempted = true
                            val swapped = withContext(Dispatchers.Main) {
                                swapToVlcInPlace()
                            }
                            if (!swapped) {
                                // HEVC at ~8fps with SW decoder is exactly the slideshow we're
                                // trying to escape. If VLC is blocked, SW fallback won't save
                                // us — the hardware physically can't decode this content.
                                withContext(Dispatchers.Main) {
                                    showFriendlyError("This content uses a video format not supported on this device.")
                                }
                            }
                            return@launch
                        }
                    } else {
                        consecutiveSlideshowPolls = 0
                    }
                    lastRenderedFrameCount = currentFrames
                    // Only reset recovery ladder after SUSTAINED playback (3 polls = 6s of frames)
                    // Prevents single-frame renders from resetting the ladder (MTK bug: 1 frame then black)
                    if (watchdogResetCount > 0) {
                        consecutiveGoodPolls++
                        if (consecutiveGoodPolls >= SUSTAINED_PLAYBACK_POLLS) {
                            AudioLogger.log("Frame watchdog: sustained playback confirmed after step $watchdogResetCount — resetting ladder")
                            watchdogResetCount = 0
                            consecutiveGoodPolls = 0
                        }
                    } else {
                        consecutiveGoodPolls = 0
                    }
                }
            }
        }
    }

    /**
     * Rebuilds the entire playback stack with a software-only video decoder.
     * Navigates to a new instance of OoustreamPlaybackFragment with a flag to use SW decoding.
     * Used when the hardware decoder inits but fails to render frames.
     */
    private fun rebuildPlayerWithSoftwareDecoder() {
        val p = player ?: return
        val currentPosition = p.currentPosition
        usingSoftwareVideoDecoder = true

        // Stop current player to free decoder resources
        p.stop()

        // Rebuild in-place: release old player, create new one with SW decoders
        diagnosticListener?.let { p.removeListener(it); p.removeAnalyticsListener(it) }
        healthMonitor?.stop()
        mediaSession?.release()
        mediaSession = null
        safeReleasePlayer(p)

        val softwareRenderersFactory = AudioPipelineFactory.createSoftwareVideoRenderersFactory(requireContext())
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val bandwidthMeter = DefaultBandwidthMeter.Builder(requireContext()).build()

        // Use low-memory buffers on constrained devices (SW decode needs all available RAM for frames)
        val swLoadControl = run {
            val am2 = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am2.memoryClass <= 192) BufferConfigs.forLowMemory(viewModel.contentType)
            else BufferConfigs.forContentType(viewModel.contentType)
        }

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(softwareRenderersFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(swLoadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory()))
            .build()

        player!!.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )

        // Re-attach diagnostic listeners
        diagnosticListener?.let { listener ->
            player!!.addListener(listener)
            player!!.addAnalyticsListener(listener)
        }
        healthMonitor?.apply {
            this.bandwidthMeter = bandwidthMeter
            start(player!!)
        }

        // New MediaSession for the rebuilt player
        mediaSession = MediaSession.Builder(requireContext(), player!!)
            .setId("ooustream_playback_sw_${System.nanoTime()}")
            .build()

        // Re-attach core player listener (onPlayerError, onTracksChanged, onPlaybackStateChanged)
        attachPlayerListener()
        // Re-attach subtitle cue listener so SubtitleView keeps receiving cues on the new player
        attachCueListener()

        // Reconnect to Leanback via new adapter + glue
        val newAdapter = LeanbackPlayerAdapter(requireContext(), player!!, 1000)
        glue = OoustreamPlaybackGlue(requireContext(), newAdapter).apply {
            host = VideoSupportFragmentGlueHost(this@OoustreamPlaybackFragment)
            isControlsOverlayAutoHideEnabled = false
            contentType = viewModel.contentType
            title = viewModel.streamName
        }

        // Restore playback from saved position
        player!!.setMediaItem(MediaItem.fromUri(viewModel.streamUrl))
        player!!.prepare()
        if (currentPosition > 0) player!!.seekTo(currentPosition)
        player!!.play()

        Toast.makeText(requireContext(), "Optimizing video decoder...", Toast.LENGTH_SHORT).show()
        streamDiagnosticLogger.logAppEvent("PLAYER_REBUILD", "decoder=software, position=${currentPosition}ms")
    }

    /**
     * Rebuild the ExoPlayer with EXTENSION_RENDERER_MODE_PREFER so FFmpeg handles audio decoding
     * instead of the hardware MediaCodec. Used when hardware falsely claims AC3/EAC3 support
     * but crashes at runtime (e.g. mt8695 Fire TV Sticks).
     */
    private fun rebuildPlayerWithFfmpegPreferred() {
        val p = player ?: return
        val currentPosition = p.currentPosition
        val currentUrl = viewModel.streamUrl

        // Stop and release current player
        p.stop()
        diagnosticListener?.let { p.removeListener(it); p.removeAnalyticsListener(it) }
        healthMonitor?.stop()
        mediaSession?.release()
        mediaSession = null
        safeReleasePlayer(p)

        // Rebuild with FFmpeg-preferred audio pipeline
        val ffmpegRenderersFactory = AudioPipelineFactory.createFfmpegPreferredRenderersFactory(requireContext())
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val bandwidthMeter = DefaultBandwidthMeter.Builder(requireContext()).build()

        val ffmpegLoadControl = run {
            val am2 = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am2.memoryClass <= 192) BufferConfigs.forLowMemory(viewModel.contentType)
            else BufferConfigs.forContentType(viewModel.contentType)
        }

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(ffmpegRenderersFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(ffmpegLoadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory()))
            .build()

        player!!.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )

        // Re-attach diagnostic listeners
        diagnosticListener?.let { listener ->
            player!!.addListener(listener)
            player!!.addAnalyticsListener(listener)
        }
        healthMonitor?.apply {
            this.bandwidthMeter = bandwidthMeter
            start(player!!)
        }

        // Re-attach core player listener (onPlayerError, onTracksChanged, onPlaybackStateChanged)
        attachPlayerListener()
        // Re-attach subtitle cue listener so SubtitleView keeps receiving cues on the new player
        attachCueListener()

        // New MediaSession
        mediaSession = MediaSession.Builder(requireContext(), player!!)
            .setId("ooustream_playback_ffmpeg_${System.nanoTime()}")
            .build()

        // Reconnect to Leanback via new adapter + glue
        val newAdapter = LeanbackPlayerAdapter(requireContext(), player!!, 1000)
        glue = OoustreamPlaybackGlue(requireContext(), newAdapter).apply {
            host = VideoSupportFragmentGlueHost(this@OoustreamPlaybackFragment)
            isControlsOverlayAutoHideEnabled = false
            contentType = viewModel.contentType
            title = viewModel.streamName
        }

        // Restore playback from saved position
        player!!.setMediaItem(MediaItem.fromUri(currentUrl))
        player!!.prepare()
        if (currentPosition > 0) player!!.seekTo(currentPosition)
        player!!.play()

        Toast.makeText(requireContext(), "Switching to software audio decoder...", Toast.LENGTH_SHORT).show()
        streamDiagnosticLogger.logAppEvent("PLAYER_REBUILD",
            "decoder=ffmpeg_preferred, position=${currentPosition}ms, channel=${healthMonitor?.channelName ?: "unknown"}")
    }

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
                audioDisabledByFallback = false
                userTrackOverrideActive = false
                bufferStormCount = 0
                bufferStormWindowStart = 0L
                ffmpegRebuildAttemptedForBufferStorm = false
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

    /** Show a user-friendly error dialog with Retry and Exit options.
     *  Posts to main thread via Handler to ensure dialog shows even when called from
     *  watchdog coroutine after Leanback glue has suppressed focus events. */
    private fun showFriendlyError(message: String) {
        val act = activity ?: return
        // Cancel watchdogs first so they don't keep running while dialog is up
        frameWatchdogJob?.cancel()
        stallDetectorJob?.cancel()
        player?.stop()
        try { glue?.host?.hideControlsOverlay(false) } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!isAdded || isDetached) return@post
            try {
                AlertDialog.Builder(act)
                    .setTitle(R.string.error_stream)
                    .setMessage(message)
                    .setPositiveButton(R.string.retry) { _, _ ->
                        retryCount = 0
                        audioFallbackAttempted = false
                        audioDisabledByFallback = false
                        userTrackOverrideActive = false
                        bufferStormCount = 0
                        bufferStormWindowStart = 0L
                        ffmpegRebuildAttemptedForBufferStorm = false
                        trackSelector?.setParameters(
                            trackSelector!!.buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        )
                        player?.prepare()
                        player?.play()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        act.onBackPressedDispatcher.onBackPressed()
                    }
                    .setCancelable(false)
                    .show()
                streamDiagnosticLogger.logAppEvent("ERROR_DIALOG_SHOWN", "msg=$message")
            } catch (e: Exception) {
                streamDiagnosticLogger.logAppEvent("ERROR_DIALOG_FAILED", "err=${e.message}")
                Toast.makeText(act, message, Toast.LENGTH_LONG).show()
                act.onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    // ── libVLC native crash guard (v3.5.7 + v3.5.8) ─────────────────────────
    //
    // v3.5.7 shipped a global "libVLC ever crashed" boolean flag. That was too
    // aggressive: one crash on one URL permanently locked out libVLC for ALL
    // future content on the same device, even after updates, and even for
    // content that had nothing to do with the original crash. See the
    // v3.5.8 changelog for the regression write-up.
    //
    // v3.5.8 scopes the guard to (URL, versionCode):
    //   - Only the exact URL that crashed is blocked, not "any channel ever".
    //   - On app update (versionCode bump), the guard resets — the new version
    //     may well have fixed the crash we were protecting against.
    //   - MTK watchdog shortcut now falls through to software decoder at 720p
    //     when the guard blocks, instead of giving up (see call site).
    //   - Settings → "Reset Playback Recovery" is a manual escape hatch.
    //
    // Storage is project-private SharedPreferences "vlc_crash_guard":
    //   vlc_swap_crashed_url        — the exact URL that crashed (String)
    //   vlc_swap_crash_channel      — human-readable channel label (log only)
    //   vlc_swap_crash_version_code — app versionCode at time of crash (Int)

    private fun vlcCrashGuardPrefs() =
        requireContext().getSharedPreferences("vlc_crash_guard", Context.MODE_PRIVATE)

    private fun isVlcCrashGuardActive(currentUrl: String): Boolean {
        val prefs = vlcCrashGuardPrefs()
        val storedVersion = prefs.getInt("vlc_swap_crash_version_code", -1)
        // Flag from an older APK — the new version may have fixed the crash.
        if (storedVersion != -1 && storedVersion != BuildConfig.VERSION_CODE) {
            clearVlcCrashGuard()
            return false
        }
        val crashedUrl = prefs.getString("vlc_swap_crashed_url", null) ?: return false
        return crashedUrl == currentUrl
    }

    private fun armVlcCrashGuard(url: String, channelLabel: String) {
        vlcCrashGuardPrefs().edit()
            .putString("vlc_swap_crashed_url", url)
            .putString("vlc_swap_crash_channel", channelLabel)
            .putInt("vlc_swap_crash_version_code", BuildConfig.VERSION_CODE)
            .apply()
    }

    private fun clearVlcCrashGuard() {
        vlcCrashGuardPrefs().edit()
            .remove("vlc_swap_crashed_url")
            .remove("vlc_swap_crash_channel")
            .remove("vlc_swap_crash_version_code")
            // Also wipe the legacy v3.5.7 keys so a user upgrading from 3.5.7
            // isn't stuck with stale state the new code doesn't read.
            .remove("vlc_swap_pending")
            .remove("vlc_swap_channel")
            .apply()
    }

    /** Swap from ExoPlayer to libVLC IN-PLACE inside this same fragment.
     *  User keeps exact same UI (controls bar, poster, scrim, focus behavior) —
     *  only the video decode pipeline changes. Used when ExoPlayer can't decode
     *  the current content (HEVC Main 10, weird H.264, etc.).
     *
     *  Preserves URL, title, stream type, and position. If ExoPlayer hasn't had
     *  time to seek to the saved position yet (early swap during initial buffer),
     *  falls back to reading the resume position directly from the DB.
     *
     *  Returns true if the swap was attempted, false if the native crash guard
     *  blocked it (see isVlcCrashGuardActive). Callers should handle the false
     *  return by falling through to an alternate recovery path instead of
     *  hitting a dead end. */
    private fun swapToVlcInPlace(silentTransition: Boolean = false): Boolean {
        if (!isAdded || isDetached) return false
        if (useVlcBackend) return false // already on VLC

        val url = viewModel.streamUrl

        // v3.5.8 native crash guard: only skip if the EXACT current URL previously
        // crashed libVLC on the CURRENT app version. Unrelated content is never
        // blocked, and version bumps reset the guard automatically.
        if (isVlcCrashGuardActive(url)) {
            val lastChannel = vlcCrashGuardPrefs().getString("vlc_swap_crash_channel", "unknown")
            AudioLogger.log("VLC swap skipped — previous swap on THIS URL crashed (channel=$lastChannel)")
            streamDiagnosticLogger.logAppEvent("VLC_SWAP_CRASH_GUARD",
                "skipped=true, previousCrashChannel=$lastChannel, channel=${healthMonitor?.channelName ?: "unknown"}, urlMatch=true")
            return false
        }

        // Set the pending flag BEFORE starting the swap — cleared after VLC renders
        // first frame. If the app dies in between, the flag persists and blocks the
        // next attempt ON THIS SPECIFIC URL ONLY. Other content is unaffected.
        armVlcCrashGuard(url, healthMonitor?.channelName ?: viewModel.streamName)

        // Resolve the position to resume at:
        //   1. If ExoPlayer is past the saved position (i.e., successfully seeked + played), use it
        //   2. Otherwise fetch the saved position from the DB (Continue Watching)
        val exoPos = player?.currentPosition ?: 0L
        val forceBeginning = arguments?.getBoolean("force_start_from_beginning", false) == true
        streamDiagnosticLogger.logAppEvent("VLC_SWAP_IN_PLACE",
            "url=${url.take(50)}, exoPos=${exoPos}ms, forceBeginning=$forceBeginning")

        if (forceBeginning || viewModel.contentType == ContentType.LIVE) {
            // User chose "Play from Beginning" or live TV — start from 0
            doSwapToVlcInPlace(exoPos.coerceAtLeast(0L), silentTransition)
        } else if (exoPos > 5_000L) {
            // ExoPlayer made it past 5s — it successfully seeked + played, use its position
            doSwapToVlcInPlace(exoPos, silentTransition)
        } else {
            // ExoPlayer didn't get far enough to seek — fetch saved position from DB
            viewLifecycleOwner.lifecycleScope.launch {
                val savedPos = viewModel.getResumePositionSync()
                val resolvedPos = if (savedPos > 0) savedPos else exoPos.coerceAtLeast(0L)
                streamDiagnosticLogger.logAppEvent("VLC_SWAP_RESUME_FROM_DB",
                    "exoPos=${exoPos}ms, savedPos=${savedPos}ms, resolvedPos=${resolvedPos}ms")
                doSwapToVlcInPlace(resolvedPos, silentTransition)
            }
        }
        return true
    }

    private fun doSwapToVlcInPlace(positionMs: Long, silentTransition: Boolean = false) {
        if (!isAdded || isDetached) return
        if (useVlcBackend) return
        val url = viewModel.streamUrl

        // Mid-movie swaps get an explanatory "Optimizing" overlay because the user already
        // had playback. Upfront swaps are just startup — normal buffering UX is enough.
        if (!silentTransition) showVlcTransitionOverlay()

        // 1. Stop all ExoPlayer monitoring/recovery jobs
        frameWatchdogJob?.cancel()
        stallDetectorJob?.cancel()
        retryJob?.cancel()
        healthMonitor?.stop()

        // 2. Release ExoPlayer cleanly
        try {
            val p = player
            if (p != null) {
                diagnosticListener?.let { p.removeListener(it); p.removeAnalyticsListener(it) }
                corePlayerListener?.let { p.removeListener(it) }
                cueListener?.let { p.removeListener(it) }
                mediaSession?.release()
                mediaSession = null
                p.stop()
                p.clearMediaItems()
                safeReleasePlayer(p)
            }
        } catch (e: Exception) {
            streamDiagnosticLogger.logAppEvent("VLC_SWAP_EXOPLAYER_RELEASE_ERROR", "err=${e.message}")
        }
        player = null
        useVlcBackend = true
        vlcHasRenderedFirstFrame = false

        // 3. Initialize libVLC in the SAME fragment
        try {
            initializeVlcBackend(url, positionMs)
        } catch (e: Exception) {
            streamDiagnosticLogger.logAppEvent("VLC_SWAP_FAILED", "err=${e.message}")
            hideVlcTransitionOverlay()
            showFriendlyError("Unable to play this video on this device.")
            return
        }

        // 4. Rebind controls bar callbacks to libVLC methods
        rewireControlsBarForVlc()

        // 5. Start periodic position update loop for libVLC
        startVlcPositionUpdates()
    }

    /** Initialize libVLC, attach its video layout, load media, start playback. */
    private fun initializeVlcBackend(url: String, startPositionMs: Long) {
        // Add VLCVideoLayout as a child of the fragment's root view.
        // Later bringToFront() calls on controls/overlays ensure correct z-order:
        //   [Leanback SurfaceView (dead)] < [VLCVideoLayout] < [controlsBar] < [overlays]
        val root = view as? ViewGroup ?: return
        vlcVideoLayout = org.videolan.libvlc.util.VLCVideoLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            keepScreenOn = true
        }
        root.addView(vlcVideoLayout)
        // Bring controls bar and transition overlay to front so VLC video stays behind them
        controlsBar?.bringToFront()
        vlcTransitionOverlay?.bringToFront()
        seekFeedback?.bringToFront()
        // Hide the Leanback SurfaceView (it would show black since ExoPlayer is dead).
        // Walk the view tree and hide any SurfaceView that isn't our VLC layout's child.
        try {
            fun hideSurfaceViews(v: View) {
                if (v is android.view.SurfaceView
                    && v.parent !== vlcVideoLayout
                    && !isDescendantOf(v, vlcVideoLayout)) {
                    v.visibility = View.INVISIBLE
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) hideSurfaceViews(v.getChildAt(i))
                }
            }
            hideSurfaceViews(root)
        } catch (_: Exception) {}

        val options = arrayListOf(
            // --no-skip-frames removed in v3.6.3: forcing libVLC to never drop frames
            // made decoder fall behind audio on slower devices, causing lipsync drift.
            // Default behavior (skip late frames) is correct for streaming playback.
            "--rtsp-tcp",
            "--http-reconnect",
            "--network-caching=1500"
        )
        libVlc = org.videolan.libvlc.LibVLC(requireContext(), options)
        vlcMediaPlayer = org.videolan.libvlc.MediaPlayer(libVlc).apply {
            attachViews(vlcVideoLayout!!, null, false, false)
            setEventListener { event -> handleVlcEvent(event, startPositionMs) }
        }

        val media = org.videolan.libvlc.Media(libVlc, android.net.Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            // Force FFmpeg (libavformat) demuxer for MKV — libVLC's native Matroska demuxer
            // stalls on malformed EBML blocks that FFmpeg's demuxer handles cleanly. This is
            // the same demuxer IPTV Smarters uses (via ijkplayer). Applies to all content;
            // libVLC falls back to its native demuxer if avformat can't open the stream.
            addOption(":demux=avformat")
            // Set start time as a media option so libVLC seeks to the resume position
            // as part of opening the stream — much more reliable than seeking after Playing event.
            // Uses seconds (libVLC --start-time convention).
            if (startPositionMs > 0) {
                val startSec = startPositionMs / 1000
                addOption(":start-time=$startSec")
            }
        }
        vlcMediaPlayer?.media = media
        media.release()
        vlcMediaPlayer?.play()

        streamDiagnosticLogger.logAppEvent("VLC_BACKEND_START",
            "url=${url.take(50)}, position=${startPositionMs}ms, startTimeOpt=${startPositionMs / 1000}s")
    }

    private fun handleVlcEvent(
        event: org.videolan.libvlc.MediaPlayer.Event,
        startPositionMs: Long
    ) {
        when (event.type) {
            org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                streamDiagnosticLogger.logAppEvent("VLC_PLAYING",
                    "time=${vlcMediaPlayer?.time}ms, length=${vlcMediaPlayer?.length}ms")
                // Apply subtitle preference (enable English SPU, or disable) now that
                // libVLC has parsed the media and spuTracks is populated.
                applyVlcSubtitlePreferences()
                activity?.runOnUiThread {
                    controlsBar?.updateCcState(subtitlePreferences.subtitlesEnabled)
                    controlsBar?.updatePlayPauseIcon(true)
                }
            }
            org.videolan.libvlc.MediaPlayer.Event.Paused -> {
                activity?.runOnUiThread { controlsBar?.updatePlayPauseIcon(false) }
            }
            org.videolan.libvlc.MediaPlayer.Event.Vout -> {
                val wasFirstFrame = !vlcHasRenderedFirstFrame
                vlcHasRenderedFirstFrame = true
                // Clear the native crash guard — VLC survived and rendered a frame
                if (wasFirstFrame) {
                    try { clearVlcCrashGuard() } catch (_: Exception) {}
                }
                val currentTime = vlcMediaPlayer?.time ?: 0L
                streamDiagnosticLogger.logAppEvent("VLC_VOUT",
                    "time=${currentTime}ms, wantedStart=${startPositionMs}ms")
                // Safety-net seek: if the :start-time media option didn't land the playback
                // at the desired resume position (tolerance 3s), seek to it now.
                if (wasFirstFrame && startPositionMs > 3_000L
                    && Math.abs(currentTime - startPositionMs) > 3_000L) {
                    vlcMediaPlayer?.time = startPositionMs
                    streamDiagnosticLogger.logAppEvent("VLC_VOUT_SAFETY_SEEK",
                        "from=${currentTime}ms, to=${startPositionMs}ms")
                }
                activity?.runOnUiThread {
                    hideVlcTransitionOverlay()
                    // Briefly show controls so user sees the familiar UI confirming playback
                    controlsManager?.show()
                }
            }
            org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                streamDiagnosticLogger.logAppEvent("VLC_END_REACHED", "")
                activity?.runOnUiThread {
                    val dur = vlcMediaPlayer?.length ?: 0L
                    if (dur > 0 && viewModel.contentType != ContentType.LIVE) {
                        viewModel.saveProgress(dur, dur, 1.0f)
                        viewModel.markCompleted()
                    }
                    activity?.onBackPressedDispatcher?.onBackPressed()
                }
            }
            org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                streamDiagnosticLogger.logAppEvent("VLC_ERROR", "libVLC reported error")
                activity?.runOnUiThread {
                    hideVlcTransitionOverlay()
                    showFriendlyError("Playback failed. This video format isn't supported.")
                }
            }
        }
    }

    private fun isDescendantOf(v: View, ancestor: View?): Boolean {
        if (ancestor == null) return false
        var parent: android.view.ViewParent? = v.parent
        while (parent != null) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    /** KeyEventHandler implementation — intercepts keys when on libVLC backend since
     *  Leanback's glue is stale after ExoPlayer release and won't trigger controls. */
    override fun onKeyEvent(keyCode: Int): Boolean {
        // Only intercept when we're on the libVLC backend — let Leanback handle ExoPlayer mode
        if (!useVlcBackend) return false

        val mgr = controlsManager
        val bar = controlsBar
        val mp = vlcMediaPlayer
        if (mgr == null || bar == null || mp == null) return false

        return when (keyCode) {
            // Hardware media key: always toggle play/pause regardless of focus.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (mp.isPlaying) mp.pause() else mp.play()
                bar.updatePlayPauseIcon(mp.isPlaying)
                if (mgr.isVisible) mgr.resetAutoHideTimer()
                true
            }
            // DPAD OK: if controls aren't showing, intercept to show them. If they are
            // showing, return false so the focused button (Play, CC, Tracks, Aspect…)
            // gets its click dispatched — otherwise every button would play/pause.
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (!mgr.isVisible) {
                    mgr.show()
                    true
                } else {
                    mgr.resetAutoHideTimer()
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!mgr.isVisible) {
                    mgr.show()
                    true
                } else {
                    // Let controls bar handle directional navigation within its own buttons
                    mgr.resetAutoHideTimer()
                    false
                }
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val newPos = (mp.time - 10_000L).coerceAtLeast(0L)
                mp.time = newPos
                bar.updatePosition(newPos, mp.length.coerceAtLeast(0L))
                if (!mgr.isVisible) mgr.show() else mgr.resetAutoHideTimer()
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val dur = mp.length.coerceAtLeast(0L)
                val newPos = (mp.time + 10_000L).coerceAtMost(dur)
                mp.time = newPos
                bar.updatePosition(newPos, dur)
                if (!mgr.isVisible) mgr.show() else mgr.resetAutoHideTimer()
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                // If controls are visible, hide them first instead of exiting player
                if (mgr.isVisible) {
                    mgr.hide()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    /** Rewire PlayerControlsBar button callbacks to drive libVLC instead of ExoPlayer. */
    private fun rewireControlsBarForVlc() {
        val bar = controlsBar ?: return
        bar.onPlayPause = {
            vlcMediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.pause() else mp.play()
                bar.updatePlayPauseIcon(mp.isPlaying)
            }
        }
        bar.onSeekBack = {
            vlcMediaPlayer?.let { mp ->
                val newPos = (mp.time - 10_000L).coerceAtLeast(0L)
                mp.time = newPos
                seekFeedback?.showSeek(-10_000)
                bar.updatePosition(newPos, mp.length.coerceAtLeast(0L))
            }
        }
        bar.onSeekForward = {
            vlcMediaPlayer?.let { mp ->
                val dur = mp.length.coerceAtLeast(0L)
                val newPos = (mp.time + 10_000L).coerceAtMost(dur)
                mp.time = newPos
                seekFeedback?.showSeek(10_000)
                bar.updatePosition(newPos, dur)
            }
        }
        bar.onDpadSeek = { deltaMs ->
            vlcMediaPlayer?.let { mp ->
                val dur = mp.length.coerceAtLeast(0L)
                val newPos = (mp.time + deltaMs).coerceIn(0L, dur)
                mp.time = newPos
                bar.updatePosition(newPos, dur)
            }
        }
        // libVLC track picker: enumerate audio + SPU tracks from the active MediaPlayer
        // and let user switch. libVLC renders SPU natively — we just call setAudioTrack /
        // setSpuTrack with the picked id. Uses plain AlertDialog (Leanback theme isn't
        // AppCompat — see v2.3.1 hotfix notes for the reasoning).
        bar.onTracksClicked = {
            showVlcTrackPicker()
        }
        bar.onStatsToggle = {
            Toast.makeText(requireContext(),
                "Stats overlay unavailable in compatible mode",
                Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * libVLC track picker using the shared TrackPickerOverlay — same slide-in panel,
     * D-pad focus, gold highlights, radio buttons as the ExoPlayer path. We build
     * TrackInfo lists from MediaPlayer.audioTracks / spuTracks and route selections
     * to setAudioTrack / setSpuTrack via the overlay's custom callbacks.
     */
    private fun showVlcTrackPicker() {
        val mp = vlcMediaPlayer ?: return
        val overlay = trackPickerOverlay ?: return

        val vlcAudio = try { mp.audioTracks?.toList() } catch (_: Exception) { null }.orEmpty()
        val vlcSpu = try { mp.spuTracks?.toList() } catch (_: Exception) { null }.orEmpty()
        val currentAudioId = try { mp.audioTrack } catch (_: Exception) { -1 }
        val currentSpuId = try { mp.spuTrack } catch (_: Exception) { -1 }

        val audioItems = vlcAudio.map { td ->
            TrackPickerOverlay.TrackInfo(
                name = td.name ?: "Track ${td.id}",
                groupIndex = -1,
                trackIndex = -1,
                isSelected = td.id == currentAudioId,
                type = C.TRACK_TYPE_AUDIO,
                customId = td.id
            )
        }

        // libVLC's spuTracks sometimes includes a "Disable" row at id=-1; strip it and
        // always prepend our own "Off" so the selection-state rendering is consistent.
        val spuRealTracks = vlcSpu.filter { it.id != -1 }
        val offSelected = currentSpuId == -1
        val subtitleItems = buildList {
            add(TrackPickerOverlay.TrackInfo(
                name = "Off",
                groupIndex = -1,
                trackIndex = -1,
                isSelected = offSelected,
                type = C.TRACK_TYPE_TEXT,
                customId = -1
            ))
            spuRealTracks.forEach { td ->
                add(TrackPickerOverlay.TrackInfo(
                    name = td.name ?: "Track ${td.id}",
                    groupIndex = -1,
                    trackIndex = -1,
                    isSelected = td.id == currentSpuId,
                    type = C.TRACK_TYPE_TEXT,
                    customId = td.id
                ))
            }
        }

        if (audioItems.isEmpty() && spuRealTracks.isEmpty()) {
            Toast.makeText(requireContext(),
                "No selectable tracks in this stream", Toast.LENGTH_SHORT).show()
            return
        }

        overlay.showCustom(
            audioTracks = audioItems,
            subtitleTracks = subtitleItems,
            onAudioSelect = { info ->
                val p = vlcMediaPlayer ?: return@showCustom
                try { p.setAudioTrack(info.customId) } catch (_: Exception) {}
            },
            onSubtitleSelect = { info ->
                val p = vlcMediaPlayer ?: return@showCustom
                try { p.setSpuTrack(info.customId) } catch (_: Exception) {}
                subtitlePreferences.subtitlesEnabled = info.customId != -1
                controlsBar?.updateCcState(info.customId != -1)
            }
        )
    }

    /** Periodic position update loop for libVLC — drives controlsBar.updatePosition()
     *  AND persists watch progress so Continue Watching / Watch It Again rows stay in sync.
     *  Mirrors the ExoPlayer loop (5s save cadence, 5% threshold). */
    private fun startVlcPositionUpdates() {
        vlcPositionJob?.cancel()
        vlcPositionJob = viewLifecycleOwner.lifecycleScope.launch {
            var tickCount = 0
            while (isActive && useVlcBackend) {
                val mp = vlcMediaPlayer ?: break
                val position = mp.time.coerceAtLeast(0L)
                val duration = mp.length.coerceAtLeast(0L)
                if (duration > 0) {
                    // Display update every tick (1s) for smooth seek bar
                    controlsBar?.updatePosition(position, duration)
                    // Persist progress every 5 ticks (5s) — matches ExoPlayer cadence.
                    // 5% threshold prevents Continue Watching row from flooding with brief previews.
                    if (tickCount % 5 == 0
                        && viewModel.contentType != ContentType.LIVE
                        && position > 0) {
                        val pct = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        if (pct > 0.05f) {
                            viewModel.saveProgress(position, duration, pct)
                        }
                    }
                }
                tickCount++
                delay(1000)
            }
        }
    }

    /** Show the "Optimizing playback for this content" transition overlay. */
    private fun showVlcTransitionOverlay() {
        if (vlcTransitionOverlay != null) return
        val root = view as? ViewGroup ?: return
        val ctx = requireContext()

        val overlay = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xE6000000.toInt()) // 90% opaque black
            isClickable = true
            isFocusable = true
        }

        val contentColumn = LinearLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val spinner = ProgressBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                (72 * resources.displayMetrics.density).toInt(),
                (72 * resources.displayMetrics.density).toInt()
            )
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFFC107.toInt())
        }

        val title = TextView(ctx).apply {
            text = "Optimizing playback for this content"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (24 * resources.displayMetrics.density).toInt() }
        }

        val subtitle = TextView(ctx).apply {
            text = "Using compatible decoder. This may take a few seconds."
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
        }

        contentColumn.addView(spinner)
        contentColumn.addView(title)
        contentColumn.addView(subtitle)
        overlay.addView(contentColumn)
        root.addView(overlay)
        vlcTransitionOverlay = overlay
    }

    private fun hideVlcTransitionOverlay() {
        val overlay = vlcTransitionOverlay ?: return
        overlay.animate().alpha(0f).setDuration(300).withEndAction {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            vlcTransitionOverlay = null
        }.start()
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

    // ─── Mobile Touch Gesture Setup ────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures(rootView: View) {
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                controlsManager?.toggle()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return false
                if (p.isPlaying) p.pause() else p.play()
                controlsManager?.show()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val startX = e1?.x ?: return false
                val deltaX = e2.x - startX
                if (kotlin.math.abs(deltaX) < 100 || kotlin.math.abs(velocityX) < 300) return false

                if (viewModel.contentType == ContentType.LIVE) {
                    // Horizontal fling → channel zap
                    val direction = if (deltaX > 0) -1 else 1
                    val newChannel = viewModel.switchChannel(direction)
                    if (newChannel != null) {
                        zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                        debouncedTune(newChannel)
                    }
                } else {
                    // Horizontal fling → seek ±10s
                    val p = player ?: return false
                    if (deltaX > 0) {
                        p.seekTo((p.currentPosition + 10_000).coerceAtMost(p.duration))
                        seekFeedback?.showSeek(10_000)
                    } else {
                        p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                        seekFeedback?.showSeek(-10_000)
                    }
                    controlsBar?.updatePosition(p.currentPosition, p.duration)
                    controlsManager?.show()
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        rootView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

        // Walk the view tree to find any SurfaceView/TextureView and attach gesture detector
        // Leanback's root may intercept touches before they reach the root listener on phones
        fun attachToSurfaces(v: View) {
            if (v is android.view.SurfaceView || v is android.view.TextureView) {
                v.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) attachToSurfaces(v.getChildAt(i))
            }
        }
        attachToSurfaces(rootView)
    }

    /** Switch playback to [channel] and update viewModel + glue state. */
    private fun tuneToChannel(channel: com.ooustream.iptv.data.model.LiveStream) {
        // Auto-close track picker on channel switch
        if (trackPickerOverlay?.isShowing == true) trackPickerOverlay?.dismiss()

        // Reset audio + video recovery state for new channel
        retryCount = 0
        audioFallbackAttempted = false
        audioDisabledByFallback = false
        mkvVarintRecoveryAttempted = false
        userTrackOverrideActive = false
        bufferStormCount = 0
        bufferStormWindowStart = 0L
        ffmpegRebuildAttemptedForBufferStorm = false
        // Force-restart watchdog so the new channel gets a fresh escalation ladder
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null

        // Re-enable audio in case it was disabled by Stage 2 fallback
        // Re-apply subtitle preference (enabled/disabled + preferred language)
        player?.trackSelectionParameters = player?.trackSelectionParameters
            ?.buildUpon()
            ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlePreferences.subtitlesEnabled)
            ?.setPreferredTextLanguage(subtitlePreferences.preferredLanguage)
            ?.build() ?: return

        // Update diagnostic context for new channel
        diagnosticListener?.channelName = channel.name
        healthMonitor?.channelName = channel.name
        streamDiagnosticLogger.logAppEvent("CHANNEL_SWITCH", "to=${channel.name}")

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
            lastEpgRefreshMs = System.currentTimeMillis()

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
            // Kill green after banner shows (Leanback may re-apply brandColor on view changes)
            view?.postDelayed({ killAllGreen() }, 100)
        }
    }

    /** Opens the track picker overlay (audio + subtitle tracks). */
    private fun showTrackPicker() {
        val p = player ?: return
        controlsManager?.pauseAutoHide()
        // Temporarily enable TEXT track type so ExoPlayer exposes subtitle tracks
        // for enumeration. If user picks "Off", TrackPickerOverlay re-disables it.
        val wasTextDisabled = p.trackSelectionParameters
            .disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        if (wasTextDisabled) {
            subtitlesTemporarilyEnabled = true
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            // Wait for tracks to refresh before showing picker, with safety timeout
            var pickerShown = false
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (pickerShown) return
                    pickerShown = true
                    p.removeListener(this)
                    trackPickerOverlay?.show(p)
                }
            }
            p.addListener(listener)
            // Fallback: show picker after 500ms if onTracksChanged doesn't fire
            view?.postDelayed({
                if (!pickerShown) {
                    pickerShown = true
                    p.removeListener(listener)
                    trackPickerOverlay?.show(p)
                }
            }, 500)
        } else {
            trackPickerOverlay?.show(p)
        }
    }

    /** Create SubtitleView, add to view hierarchy, wire cues from player, apply TV styling. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun configureSubtitleView(root: View) {
        val sv = SubtitleView(requireContext())
        (root as? ViewGroup)?.addView(
            sv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        subtitleView = sv

        // Netflix-style subtitle rendering
        sv.setStyle(subtitlePreferences.buildCaptionStyle())
        sv.setFractionalTextSize(subtitlePreferences.textSizeFraction)
        sv.setApplyEmbeddedStyles(false)
        sv.setApplyEmbeddedFontSizes(false)
        // Bottom padding — Netflix positions subs ~8% from bottom edge for comfortable viewing
        val screenHeight = resources.displayMetrics.heightPixels
        sv.setPadding(0, 0, 0, (screenHeight * 0.08f).toInt())

        // Forward subtitle cues from player to our SubtitleView.
        // Stored as a field so we can re-attach after any player rebuild
        // (SW decoder, FFmpeg-preferred audio). Otherwise captions silently die.
        cueListener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                sv.setCues(cueGroup.cues)
            }
        }
        attachCueListener()
    }

    /** Re-attach the subtitle cue listener to the current player. Idempotent. */
    private fun attachCueListener() {
        val listener = cueListener ?: return
        val p = player ?: return
        p.removeListener(listener)
        p.addListener(listener)
    }

    /** Toggle closed captions on/off. CC button + KEYCODE_CAPTIONS remote key. */
    private fun toggleClosedCaptions() {
        if (useVlcBackend) {
            toggleVlcClosedCaptions()
            return
        }
        val p = player ?: return
        val isCurrentlyDisabled = p.trackSelectionParameters
            .disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val newEnabled = isCurrentlyDisabled // toggling: was disabled → now enable

        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !newEnabled)
            .setPreferredTextLanguage(subtitlePreferences.preferredLanguage)
            .build()

        subtitlePreferences.subtitlesEnabled = newEnabled
        controlsBar?.updateCcState(newEnabled)

        val msg = if (newEnabled) "Subtitles On" else "Subtitles Off"
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

        // Show controls briefly so user sees CC button state change
        if (controlsManager?.isVisible != true) {
            controlsManager?.show()
        }
    }

    /**
     * Apply the user's subtitle preference to libVLC's SPU (subpicture) track.
     * Called after VLC_PLAYING when spuTracks is populated. libVLC renders
     * subtitles natively into its own video surface — we just pick the track.
     *
     * Track name matching: libVLC TrackDescription exposes only (id, name).
     * IPTV sidecar SPU tracks are typically named like "English", "English [en]",
     * "Track 1 - English", "[Eng] English SDH", so we match case-insensitively.
     */
    private fun applyVlcSubtitlePreferences() {
        val mp = vlcMediaPlayer ?: return
        if (!subtitlePreferences.subtitlesEnabled) {
            try { mp.setSpuTrack(-1) } catch (_: Exception) {}
            return
        }
        val tracks = try { mp.spuTracks } catch (_: Exception) { null } ?: return
        if (tracks.isEmpty()) return

        val preferredLang = subtitlePreferences.preferredLanguage.ifBlank { "en" }
        // Match order: exact language code → "english" label → first non-disabled track.
        val chosen = tracks.firstOrNull { td ->
            val n = td.name?.lowercase().orEmpty()
            n.contains("[$preferredLang]") || n.contains(" $preferredLang ") || n.endsWith(" $preferredLang")
        } ?: tracks.firstOrNull { td ->
            val n = td.name?.lowercase().orEmpty()
            n.contains("english") || n.contains("eng")
        } ?: tracks.firstOrNull { it.id != -1 }

        chosen?.let {
            try { mp.setSpuTrack(it.id) } catch (_: Exception) {}
        }
    }

    /** Toggle SPU track on libVLC backend. Mirrors toggleClosedCaptions for Media3. */
    private fun toggleVlcClosedCaptions() {
        val mp = vlcMediaPlayer ?: return
        val currentId = try { mp.spuTrack } catch (_: Exception) { -1 }
        val newEnabled = currentId == -1

        subtitlePreferences.subtitlesEnabled = newEnabled
        if (newEnabled) {
            applyVlcSubtitlePreferences()
        } else {
            try { mp.setSpuTrack(-1) } catch (_: Exception) {}
        }
        controlsBar?.updateCcState(newEnabled)

        val msg = if (newEnabled) "Subtitles On" else "Subtitles Off"
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        if (controlsManager?.isVisible != true) {
            controlsManager?.show()
        }
    }

    /** Save the language of the currently selected subtitle track to preferences. */
    private fun saveSelectedSubtitleLanguage() {
        val p = player ?: return
        val textGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        for (group in textGroups) {
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val lang = group.getTrackFormat(i).language
                    if (!lang.isNullOrBlank()) {
                        subtitlePreferences.preferredLanguage = lang
                    }
                    return
                }
            }
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
        // ExoPlayer progress save on exit
        player?.let { p ->
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
        // libVLC progress save on exit — keeps Continue Watching row in sync
        vlcMediaPlayer?.let { mp ->
            if (viewModel.contentType != ContentType.LIVE) {
                val pos = mp.time.coerceAtLeast(0L)
                val dur = mp.length.coerceAtLeast(0L)
                if (dur > 0) {
                    val pct = pos.toFloat() / dur.toFloat()
                    if (pct > 0.05f) {
                        viewModel.saveProgress(pos, dur, pct)
                    }
                }
            }
            try { mp.pause() } catch (_: Exception) {}
        }
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
        // Release libVLC backend if active
        vlcPositionJob?.cancel()
        vlcPositionJob = null
        try {
            vlcMediaPlayer?.stop()
            vlcMediaPlayer?.detachViews()
            vlcMediaPlayer?.release()
        } catch (_: Exception) {}
        try { libVlc?.release() } catch (_: Exception) {}
        vlcMediaPlayer = null
        libVlc = null
        vlcVideoLayout = null
        useVlcBackend = false
        // Safety net: ensure screen can sleep after player exits
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Cancel async jobs
        retryJob?.cancel()
        channelSwitchJob?.cancel()
        // Stop diagnostic health monitor
        healthMonitor?.stop()
        healthMonitor = null
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
        // Remove listeners before release to prevent queued callback races (code 1003 crash)
        player?.let { p ->
            corePlayerListener?.let { p.removeListener(it) }
            diagnosticListener?.let { l -> p.removeListener(l); p.removeAnalyticsListener(l) }
            try { p.stop(); p.clearVideoSurface() } catch (_: Exception) { }
            try { p.release() } catch (_: Exception) { }
        }
        corePlayerListener = null
        diagnosticListener = null
        player = null
        trackSelector = null
        glue = null
        super.onDestroyView()
    }

    /** DEBUG: Dump all views to a StringBuilder (file output for Fire TV) */
    private fun dumpBackgroundsToString(view: View, depth: Int, sb: StringBuilder) {
        val indent = "  ".repeat(depth)
        val bg = view.background
        val bgDesc = when {
            bg is android.graphics.drawable.ColorDrawable -> {
                val c = bg.color
                "#${Integer.toHexString(c)} (r=${android.graphics.Color.red(c)} g=${android.graphics.Color.green(c)} b=${android.graphics.Color.blue(c)})"
            }
            bg != null -> bg.javaClass.simpleName
            else -> "null"
        }
        val idName = try {
            if (view.id != View.NO_ID) resources.getResourceEntryName(view.id) else "no-id"
        } catch (_: Exception) { "id:${view.id}" }
        sb.appendLine("$indent${view.javaClass.simpleName} [$idName] ${view.width}x${view.height} bg=$bgDesc vis=${view.visibility}")
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dumpBackgroundsToString(view.getChildAt(i), depth + 1, sb)
            }
        }
    }

    /** DEBUG: Dump all views with non-transparent backgrounds to logcat */
    private fun dumpBackgrounds(view: View, depth: Int) {
        val indent = "  ".repeat(depth)
        val bg = view.background
        val bgDesc = when {
            bg is android.graphics.drawable.ColorDrawable -> {
                val c = bg.color
                if (c != android.graphics.Color.TRANSPARENT && c != 0) {
                    "#${Integer.toHexString(c)} (r=${android.graphics.Color.red(c)} g=${android.graphics.Color.green(c)} b=${android.graphics.Color.blue(c)})"
                } else null
            }
            bg != null -> bg.javaClass.simpleName
            else -> null
        }
        if (bgDesc != null) {
            val idName = try {
                if (view.id != View.NO_ID) resources.getResourceEntryName(view.id) else "no-id"
            } catch (_: Exception) { "id:${view.id}" }
            android.util.Log.e("VIEW_DUMP", "$indent${view.javaClass.simpleName} [$idName] ${view.width}x${view.height} bg=$bgDesc")
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dumpBackgrounds(view.getChildAt(i), depth + 1)
            }
        }
    }

    /** Check if a color value is green-ish (green channel dominant). */
    private fun isGreenish(color: Int): Boolean {
        val g = android.graphics.Color.green(color)
        val r = android.graphics.Color.red(color)
        val b = android.graphics.Color.blue(color)
        val a = android.graphics.Color.alpha(color)
        return g > 80 && g > r + 30 && g > b + 30 && a > 50
    }

    /** Our custom overlay classes — never kill their backgrounds. */
    private val SAFE_VIEW_CLASSES = setOf(
        "ChannelBannerOverlay", "ChannelZapOverlay", "ChannelListHolder",
        "StreamStatsOverlay", "AudioOnlyOverlay", "AudioStatusOverlay",
        "WatchNextOverlay", "SeriesCompleteOverlay", "SeekFeedbackOverlay",
        "TrackPickerOverlay", "BingeCountdownOverlay", "PlayerControlsBar",
        "ContentInfoOverlay", "SleepTimerManager"
    )

    /** Check if a view is inside one of our custom overlays. */
    private fun isInsideCustomOverlay(view: View): Boolean {
        var current: android.view.ViewParent? = view.parent
        while (current is View) {
            if (SAFE_VIEW_CLASSES.contains((current as View).javaClass.simpleName)) return true
            current = (current as View).parent
        }
        return SAFE_VIEW_CLASSES.contains(view.javaClass.simpleName)
    }

    /**
     * Walk the view tree and kill green backgrounds on LEANBACK INTERNAL views only.
     * Skips our custom overlay views to avoid removing dark backgrounds we set intentionally.
     */
    private fun killGreenBackgrounds(root: View) {
        // Skip our custom overlay views entirely
        if (isInsideCustomOverlay(root)) return

        val bg = root.background
        if (bg != null) {
            if (bg is android.graphics.drawable.ColorDrawable) {
                if (isGreenish(bg.color)) {
                    android.util.Log.w("GREEN_HUNT", "KILLED ColorDrawable #${Integer.toHexString(bg.color)} on ${root.javaClass.simpleName} ${root.width}x${root.height}")
                    root.background = null
                    root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }
            // For non-ColorDrawable on Leanback views: force transparent on known Leanback containers
            val className = root.javaClass.simpleName
            if (className.contains("NonOverlapping") || className.contains("PlaybackTransport") ||
                className.contains("RowContainer")) {
                root.background = null
                root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
        root.backgroundTintList?.let { tint ->
            if (isGreenish(tint.defaultColor)) {
                root.backgroundTintList = null
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                killGreenBackgrounds(root.getChildAt(i))
            }
        }
    }

    /**
     * Sample any drawable by rendering it to a 1x1 bitmap.
     * Works for ALL drawable types — ColorDrawable, GradientDrawable, LayerDrawable, etc.
     * No reflection needed (Android 9 blocks hidden field access).
     */
    private fun sampleDrawableColor(drawable: android.graphics.drawable.Drawable): Int {
        // ColorDrawable is trivial
        if (drawable is android.graphics.drawable.ColorDrawable) return drawable.color
        return try {
            val size = 4 // sample 4x4 for better accuracy
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val oldBounds = drawable.bounds
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            drawable.bounds = oldBounds
            val pixel = bitmap.getPixel(size / 2, size / 2) // center pixel
            bitmap.recycle()
            pixel
        } catch (_: Exception) { 0 }
    }

    /** Returns true if the drawable contains green and should be killed. */
    private fun killGreenDrawable(view: View, drawable: android.graphics.drawable.Drawable): Boolean {
        // Handle composite drawables by checking inner layers
        when (drawable) {
            is android.graphics.drawable.LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    if (killGreenDrawable(view, drawable.getDrawable(i))) return true
                }
                return false
            }
            is android.graphics.drawable.StateListDrawable -> {
                drawable.current?.let { if (killGreenDrawable(view, it)) return true }
                return false
            }
            is android.graphics.drawable.InsetDrawable -> {
                drawable.drawable?.let { if (killGreenDrawable(view, it)) return true }
                return false
            }
        }
        // For all other types: sample the actual rendered color
        val color = sampleDrawableColor(drawable)
        if (isGreenish(color)) {
            val idName = try {
                if (view.id != View.NO_ID) resources.getResourceEntryName(view.id) else "no-id"
            } catch (_: Exception) { "no-id" }
            android.util.Log.w("GREEN_HUNT",
                "KILLED ${drawable.javaClass.simpleName} #${Integer.toHexString(color)} " +
                "on ${view.javaClass.simpleName} id=$idName ${view.width}x${view.height} " +
                "vis=${view.visibility}")
            return true
        }
        return false
    }

    /** Log ALL backgrounds in the view tree for debugging (call once). */
    private fun logAllBackgrounds(root: View, depth: Int = 0) {
        val indent = "  ".repeat(depth)
        val bg = root.background
        if (bg != null) {
            val color = sampleDrawableColor(bg)
            val idName = try {
                if (root.id != View.NO_ID) resources.getResourceEntryName(root.id) else "no-id"
            } catch (_: Exception) { "no-id" }
            android.util.Log.w("GREEN_HUNT",
                "${indent}BG: ${root.javaClass.simpleName} id=$idName " +
                "${root.width}x${root.height} " +
                "type=${bg.javaClass.simpleName} " +
                "color=#${Integer.toHexString(color)} " +
                "ARGB(${android.graphics.Color.alpha(color)},${android.graphics.Color.red(color)},${android.graphics.Color.green(color)},${android.graphics.Color.blue(color)}) " +
                "vis=${root.visibility}")
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                logAllBackgrounds(root.getChildAt(i), depth + 1)
            }
        }
    }

    /** Run green killer on the entire window decor view to catch all Leanback internals. */
    private fun killAllGreen() {
        val root = activity?.window?.decorView ?: view ?: return
        killGreenBackgrounds(root)
    }

    companion object {
        private const val MAX_RETRIES_LIVE = 3
        private const val MAX_RETRIES_SERIES = 5
        private const val MAX_RETRIES_VOD = 6
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 5_000, 8_000, 12_000, 15_000)
        private const val STALL_TIMEOUT_LIVE_MS = 15_000L
        private const val STALL_TIMEOUT_VOD_MS = 30_000L
        private const val FRAME_WATCHDOG_INTERVAL_MS = 2_000L
        private const val FRAME_WATCHDOG_FROZEN_MS = 3_000L
        private const val MAX_WATCHDOG_RESETS = 4
        private const val SOFTWARE_FALLBACK_THRESHOLD = 1
        // Require 3 consecutive polls with new frames (6s) before resetting recovery ladder
        // Prevents MTK single-frame-then-black from resetting watchdogResetCount
        private const val SUSTAINED_PLAYBACK_POLLS = 3
        // HEVC slideshow detection: consecutive low-fps polls before VLC swap
        // 3 polls × 2s = 6 seconds of <15fps before triggering (avoids false positives on channel switch)
        private const val HEVC_SLIDESHOW_POLLS = 3
        // Min rendered frames per 2s poll to NOT be a slideshow (15fps × 2s = 30 frames)
        private const val HEVC_SLIDESHOW_MIN_FRAMES = 30
        // Buffer storm detection: rapid BUFFERING→READY cycling (amlogic HEVC+EAC3)
        private const val BUFFER_STORM_THRESHOLD = 5
        private const val BUFFER_STORM_WINDOW_MS = 30_000L

        fun newInstance(
            streamUrl: String,
            contentType: ContentType,
            streamId: String,
            streamName: String,
            streamIcon: String = "",
            seriesId: Int = 0,
            seasonNum: Int = 0,
            episodeNum: Int = 0,
            forceStartFromBeginning: Boolean = false
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
                    putBoolean("force_start_from_beginning", forceStartFromBeginning)
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
