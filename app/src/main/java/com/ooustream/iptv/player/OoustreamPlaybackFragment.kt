package com.ooustream.iptv.player

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.os.Bundle
import android.media.AudioManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.media3.exoplayer.SeekParameters
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
import com.ooustream.iptv.common.VideoDecoderCapability
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import coil.load
import com.ooustream.iptv.common.PosterUrlRewriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
// v3.7.0: no longer implements KeyEventHandler — libVLC is gone, so all key
// handling is done through OoustreamPlaybackGlue (Leanback's native pattern).
class OoustreamPlaybackFragment : VideoSupportFragment() {

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
    @Inject lateinit var userPlanManager: com.ooustream.iptv.data.UserPlanManager

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
    // v3.7.11 phone HUD overlays — Netflix-style touch feedback
    private var doubleTapRipple: DoubleTapRippleOverlay? = null
    private var volumeBrightnessHud: VolumeBrightnessOverlay? = null
    private var aspectHud: AspectRatioOverlay? = null
    private var speedBadge: SpeedBadgeOverlay? = null
    // Long-press 2x speed state — set true on onLongPress, cleared on ACTION_UP/CANCEL
    private var speedHoldActive = false
    // Original brightness saved at first brightness drag so we can restore on screen-off.
    // Float.NaN = "no override active". A real saved value can be -1f (system default).
    private var savedScreenBrightness: Float = Float.NaN
    private val chapterManager = ChapterManager()
    private var trackPickerOverlay: TrackPickerOverlay? = null
    /**
     * View that had focus right before the track picker opened. Stashed so we can
     * restore focus to the same bar button (Audio/Tracks/CC) when the picker dismisses.
     * Without this, focus is left on the (now-hidden) picker view and the cursor
     * disappears until the user blindly hits a DPAD direction.
     */
    private var focusedBeforeTrackPicker: View? = null
    private var subtitleView: SubtitleView? = null
    private var audioStatusOverlay: AudioStatusOverlay? = null
    /**
     * Pending show of the "No Audio" overlay, debounced 1500ms. onTracksChanged
     * fires with empty audio groups during every channel-switch transition for
     * a few hundred ms before the new stream's tracks arrive — without this debounce
     * the overlay flashes on every channel change.
     */
    private var noAudioOverlayJob: Job? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var isAudioOnly = false
    private var bingeShown = false
    // Gapless binge (v4.0.0): next episode resolved at the 15s mark and queued on the live player as
    // playlist item index 1 so ExoPlayer pre-buffers it; the boundary advances via playlist transition
    // instead of setMediaItem→prepare→play (no black/frozen gap).
    // INVARIANT: non-null ⇔ player.mediaItemCount == 2 (current item is index 0).
    // Cleared by: onMediaItemTransition (consumed), binge Cancel (removed), any player rebuild
    // (dropped — rebuilds setMediaItem a single item, boundary falls back to legacy advance),
    // and onDestroyView.
    private var pendingNextEpisode: NextEpisodeResult? = null
    // Duration of the CURRENT episode, refreshed by the 1s binge poll. By the time
    // onMediaItemTransition fires the player already reports the NEW item's duration, so the
    // previous episode's 100% progress save needs this remembered value.
    private var lastKnownDurationMs = 0L
    // Playlist pre-buffer is gated to HIGH/MID tiers — LOW/ULTRA_LOW (mt8695-class) keep the legacy
    // single-item advance; a second open stream is least safe exactly where rebuilds are most likely.
    private var preBufferEnabled = false
    // Coalesced D-pad seeking (watch-audit): rapid LEFT/RIGHT taps accumulate into ONE committed
    // seek ~300ms after the last tap, instead of a network seek per tap. The seekbar and the
    // seek-feedback overlay update optimistically per tap, so the UI still feels instant.
    private var pendingSeekTargetMs = -1L
    private var pendingSeekJob: Job? = null

    // Custom controls bar (replaces Leanback default controls)
    private var controlsBar: PlayerControlsBar? = null
    private var controlsManager: PlayerControlsManager? = null
    private var currentEpg: List<com.ooustream.iptv.data.model.EpgProgram> = emptyList()
    private var lastEpgRefreshMs = 0L

    // Playback hardening state
    private var mediaSession: MediaSession? = null
    private var bufferingOverlay: View? = null
    private var bufferingArt: ImageView? = null      // poster/channel art shown behind the spinner
    private var bufferingLabel: TextView? = null     // optional status copy ("Reconnecting…") under the spinner
    private var bufferingShowJob: Job? = null        // debounce: delays the spinner so sub-second dips don't flash
    private val BUFFER_SPINNER_DEBOUNCE_MS = 600L    // mid-playback rebuffers under this never flash a spinner
    private var hasRenderedFirstFrame = false        // once true, the loading backdrop can hold the last frame
    private var retryCount = 0
    private var retryJob: Job? = null
    private var stallDetectorJob: Job? = null
    private var frameWatchdogJob: Job? = null
    private var lastRenderedFrameCount: Int = -1
    private var audioFallbackAttempted = false
    private var audioDisabledByFallback = false // Stage 2 disabled audio — don't re-enable in onTracksChanged
    // Mid-stream audio dropout recovery (watch-audit): 0 = untried, 1 = renderer re-init tried,
    // 2 = FFmpeg rebuild tried. Reset per content. AUDIO_STALL / AUDIO_SINK_ERROR used to be
    // log-only — a silent dropout was never recovered until the user backed out.
    private var audioStallRecoveryStage = 0
    // One-shot alternate-container retry for VOD/Series whose server bytes no extractor
    // recognizes (provider listed the wrong containerExtension, or serves an HTML error body
    // with HTTP 200). Reset per content. Customer report: Kung Fu Panda — 6 blind retries of
    // the same dead source (~50s of spinner) before the error dialog.
    private var containerExtRetryAttempted = false
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
    // v3.7.10: tracks whether the active player is rebuilt around our FFmpeg software
    // video decoder (ExperimentalFfmpegVideoRenderer from PR #1591). Stays orthogonal to
    // usingSoftwareVideoDecoder — the latter is true for both OMX.google.h264.decoder
    // AND FFmpeg, but only this flag tells the watchdog "we already escalated to FFmpeg,
    // don't loop on the give-up path".
    private var usingFfmpegVideoDecoder = false
    // Resume save-gate (watch-audit P0): true while a decoder rebuild is mid-flight (player stopped,
    // not yet seeked back to position). Progress saves MUST skip this window or a ~0 currentPosition
    // read clobbers a deep bookmark via the REPLACE upsert.
    private var rebuildInProgress = false
    // Last position we persisted this session — used to refuse a glitchy collapse of a deep bookmark.
    private var lastSavedPositionMs = 0L
    // Bidirectional quality: clear a watchdog resolution cap ONCE after sustained-good playback so a
    // transient dip doesn't leave the rest of the title soft. (One-shot per content to avoid oscillation.)
    private var upwardReprobeAttempted = false
    // Cache video codec string from first TRACKS_CHANGED so we can identify the format
    // (e.g. HEVC Main 10) even after a SW rebuild where videoFormat becomes null
    private var cachedVideoCodecs: String = ""
    private var cachedVideoMime: String = ""
    // Cache video resolution from TRACKS_CHANGED so the watchdog can recognise 4K content
    // even after a rebuild where videoFormat momentarily reads null.
    private var cachedVideoWidth: Int = 0
    private var cachedVideoHeight: Int = 0
    // Set once the upfront "no decoder handles this resolution" refusal has fired for the current
    // content, so a later onTracksChanged can't stack a second error dialog on top of it.
    //
    // Deliberately NOT cleared by the error dialog's Retry button. Retry is the escape hatch if the
    // capability probe was wrong about this device: leaving the flag set lets a second attempt run
    // the normal decoder path (and, if it really is undecodable, the v4.2.3 watchdog give-up still
    // ends it). Clearing it here would instead re-show the same modal the instant Retry is pressed.
    private var oversizedVideoRefused: Boolean = false
    // Name of the video decoder that ExoPlayer most recently initialised (e.g.
    // "ffmpegLavc60.3.100-hevc", "OMX.allwinner.video.decoder.hevc", "c2.android.hevc.decoder").
    // Captured from onVideoDecoderInitialized so the watchdog knows whether it's running on a
    // SOFTWARE decoder — needed because the FFmpeg video renderer can be auto-selected by Media3's
    // default factory (for HEVC Main 10 the HW decoder won't advertise) WITHOUT setting
    // usingSoftwareVideoDecoder/usingFfmpegVideoDecoder, which only track explicit rebuilds.
    private var activeVideoDecoderName: String = ""
    // Subtitle pipeline self-test — flipped true once per play session so we don't spam logs
    private var subtitleSelfTestRan = false
    private var corePlayerListener: Player.Listener? = null
    // Buffer storm detection: rapid BUFFERING→READY cycling on amlogic HEVC+EAC3
    private var bufferStormCount = 0
    // v3.7.3: MTK (mt8695/mt8696/mt8167) can hardware-decode AC3/EAC3/DTS fine, but the
    // 6→2 channel downmix through ChannelMixingAudioProcessor is too heavy — audio
    // buffer underruns every 10-15s and the stall-recovery loop kicks in (15s IDLE →
    // full restart). FFmpeg-preferred factory lets FFmpeg handle both decode AND downmix
    // in one pass, which keeps up. Applied once per channel on the first onTracksChanged.
    private var mtkMultichannelFfmpegApplied = false
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
        //
        // BUT a device can report memoryClass<=192 (heapgrowthlimit) yet have plenty of total RAM —
        // the Fire TV Stick 4K Max (AFTKRT) is memoryClass 192 with 1.6GB RAM and largeHeap 384MB.
        // Capping it to the low-memory 30s buffer starves high-bitrate 4K (esp. Dolby Vision remuxes
        // now that the base layer decodes in hardware, off the Java heap): the buffer can't build a
        // cushion, so it dips into brief re-buffers. Devices with >=1.4GB total get the normal
        // tier buffer (45-60s) so they ride out throughput wobble; 1GB/1.28GB sticks (Ooustick,
        // AFTMM) stay capped. Mirrors UserPlanManager.isDeviceCapable()'s 1.4GB gate.
        val totalMemGb = run {
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            mi.totalMem / (1024f * 1024f * 1024f)
        }
        val loadControl = if (am.memoryClass <= 192 && totalMemGb < 1.4f) {
            BufferConfigs.forLowMemory(viewModel.contentType)
        } else {
            BufferConfigs.forContentTypeAndQuality(viewModel.contentType, qualityPolicy.tier.value)
        }
        streamDiagnosticLogger.logAppEvent(
            "BUFFER_CONFIG",
            "memoryClass=${am.memoryClass}, totalMem=${"%.2f".format(totalMemGb)}GB, " +
                "lowMem=${am.memoryClass <= 192 && totalMemGb < 1.4f}, type=${viewModel.contentType}"
        )
        val dataSourceFactory = StreamingDataFactories.buildDataSourceFactory(okHttpClient)

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
        // Gapless binge pre-buffer needs (a) a tier with headroom for a second open stream AND
        // (b) an account that allows >1 simultaneous connection. The pre-buffer addMediaItem()s the
        // next episode while the current one is still playing — a 2nd concurrent stream — which on a
        // 1-connection account returns HTTP 551 (max connections) and breaks the playing episode.
        // maxConnections is refreshed at launch/login (MainActivity.refreshPlan / AuthViewModel); it
        // defaults to 1, so an unknown plan safely falls back to the legacy (non-prebuffered) advance.
        val maxConnections = userPlanManager.maxConnections.value
        preBufferEnabled = (deviceTier == DeviceTier.HIGH || deviceTier == DeviceTier.MID) &&
            maxConnections > 1
        streamDiagnosticLogger.logAppEvent("PREBUFFER_GATE",
            "enabled=$preBufferEnabled, tier=$deviceTier, maxConnections=$maxConnections")

        // Apply the tier-appropriate video resolution PREFERENCE. ULTRA_LOW/LOW prefer 1080p.
        // This does NOT block 4K: exceedVideoConstraintsIfNecessary defaults to true, so a
        // single-track 2160p IPTV stream is still selected (see DeviceTierDetector.maxVideoSize).
        // Oversized video is actually refused upfront in onTracksChanged, by asking the device's
        // real MediaCodec decoders via VideoDecoderCapability.
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
            // DolbyVisionBaseLayer.wrap: routes DV Profile 7 to the hardware HEVC decoder (see the
            // class doc). Only the MAIN player path is wrapped — the watchdog-rebuild paths below
            // stay unwrapped so, if the HW decoder ever chokes on a specific P7 encode, escalation
            // still falls back to the FFmpeg software path.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    dataSourceFactory,
                    DolbyVisionBaseLayer.wrap(requireContext(), StreamingDataFactories.buildExtractorsFactory())
                )
            )
            // Hold CPU+WiFi awake while playing/buffering — without this the radio can
            // power-save mid-stall and turn a recoverable dip into a long rebuffer.
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
        // Crossfade the art backdrop out the instant real video appears (not on STATE_READY, which
        // fires before the first frame paints on slow decoders). Runs on the player's (main) looper.
        diagnosticListener!!.onFirstFrame = {
            hasRenderedFirstFrame = true
            showBufferingOverlay(false)
        }
        diagnosticListener!!.onAudioSinkFault = { recoverFromAudioStall("sink_error") }
        // Track which video decoder is live so the watchdog can tell HW from SW even when the
        // FFmpeg renderer is auto-selected by the default factory (HEVC Main 10 the HW decoder
        // won't advertise). Survives rebuilds — the same listener object is re-attached.
        diagnosticListener!!.onVideoDecoder = { name -> activeVideoDecoderName = name }
        player!!.addListener(diagnosticListener!!)
        player!!.addAnalyticsListener(diagnosticListener!!)
        // Initial play is a guaranteed black moment — show the title art immediately.
        showBufferingOverlay(true, immediate = true)

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
            onAudioStall = { recoverFromAudioStall("audio_stall") }
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
        }
        wireGlueCallbacks(glue!!)

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

        // v3.7.0: libVLC removed — FFmpeg video extension inside ExoPlayer now
        // handles HEVC Main 10, edge-case H.264, VC-1, MPEG-2 via EXTENSION_RENDERER_MODE_ON.
        // The MKV upfront libVLC swap is no longer needed.
        val forceBeginningArg = arguments?.getBoolean("force_start_from_beginning", false) == true
        val needsResume = viewModel.contentType != ContentType.LIVE && !viewModel.hasResumed && !forceBeginningArg

        if (needsResume) {
            // Resolve resume position before prepare and pass it to setMediaItem so the
            // player buffers from the resume offset directly — avoids the seek-after-prepare
            // pattern that forced users to watch 2-3s of opening logo before jumping.
            viewLifecycleOwner.lifecycleScope.launch {
                val resumePos = viewModel.getResumePositionSync().coerceAtLeast(0L)
                val p = player ?: return@launch
                setPlayerSource(viewModel.streamUrl, resumePos)
                p.prepare()
                p.play()
                if (resumePos > 0) viewModel.hasResumed = true
            }
        } else {
            setPlayerSource(viewModel.streamUrl)
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
            binge.onPlayNext = { advanceToNextEpisode("binge_overlay") }
            binge.onCancel = {
                // Cancel = "no autoplay". A queued playlist item would silently auto-advance at
                // the natural end, violating the user's explicit choice — remove it so the episode
                // ends to STATE_ENDED exactly like the legacy flow.
                val p = player
                if (pendingNextEpisode != null && p != null && p.mediaItemCount > 1) {
                    p.removeMediaItem(p.mediaItemCount - 1)
                    streamDiagnosticLogger.logAppEvent("PREBUFFER_REMOVED", "reason=user_cancel")
                }
                pendingNextEpisode = null
            }
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

        // v3.7.11 phone HUD overlays — Netflix-style touch feedback. Always added
        // to the view tree (cheap), but only triggered from setupTouchGestures
        // which is itself gated on !isTV.
        val rippleOv = DoubleTapRippleOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            rippleOv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        doubleTapRipple = rippleOv

        val volBrightHud = VolumeBrightnessOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            volBrightHud,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        volumeBrightnessHud = volBrightHud

        val aspectOv = AspectRatioOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            aspectOv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        aspectHud = aspectOv

        val spdBadge = SpeedBadgeOverlay(requireContext())
        (view as? ViewGroup)?.addView(
            spdBadge,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        speedBadge = spdBadge

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
            // Restore focus to the bar button (Audio / Tracks / CC) that opened the picker.
            // Without this the cursor is left on the (now-hidden) picker and the user can't
            // see what's focused on the bottom action row. Fall back to play/pause if the
            // saved view is gone (rebuild between open and close).
            val target = focusedBeforeTrackPicker
            focusedBeforeTrackPicker = null
            if (target != null && target.isAttachedToWindow && target.isShown) {
                target.requestFocus()
            } else {
                controlsBar?.requestFocusOnPlayPause()
            }
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
                // Diagnostic: log post-pick state so we can tell whether the override
                // actually landed vs being silently rejected by the track selector.
                val p = player
                if (p != null) {
                    val params = p.trackSelectionParameters
                    val selectedText = p.currentTracks.groups
                        .filter { it.type == C.TRACK_TYPE_TEXT }
                        .flatMap { g ->
                            (0 until g.length).mapNotNull { i ->
                                if (g.isTrackSelected(i)) {
                                    val f = g.getTrackFormat(i)
                                    "${f.language ?: "und"}/${f.sampleMimeType}"
                                } else null
                            }
                        }
                    streamDiagnosticLogger.logAppEvent("SUBTITLE_PICKED",
                        "textDisabled=$textDisabled, " +
                        "prefLang=${params.preferredTextLanguages}, " +
                        "overrides=${params.overrides.size}, " +
                        "selectedTextTracks=$selectedText, " +
                        "svAttached=${subtitleView?.parent != null}, " +
                        "svVisibility=${subtitleView?.visibility}")
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
                // Free THIS stream's server connection slot BEFORE the next fragment opens its own.
                // The fragment .replace().commit() below is async, so without this the new player
                // connects while the current one is still streaming — a momentary 2-connection overlap
                // that 551s (max connections) on a limited account. stop() ends the current MediaSource
                // (closes the OkHttp socket); onDestroyView still does the full release shortly after.
                // Mirrors LiveTvFragment's stopPreview()-before-commit pattern.
                try { player?.stop() } catch (_: Exception) {}
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

        // Hide Leanback default controls permanently — we use custom PlayerControlsBar.
        // hideControlsOverlay() animates alpha but Leanback re-shows the dock on every row
        // update (which fires constantly during playback), so we also nuke the dock view's
        // visibility directly. Without this, after an automatic decoder switch
        // (rebuildPlayerWithFfmpegPreferred), the default Leanback overlay reappears
        // alongside our custom PlayerControlsBar (the duplicate-overlay customer report).
        hideControlsOverlay(false)
        forceHideLeanbackPlaybackDock()

        // ─── Custom Controls Bar ────────────────────────────────────────────
        controlsBar = PlayerControlsBar(requireContext()).also { bar ->
            (view as? ViewGroup)?.addView(
                bar,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // The SubtitleView was added before this bar, so the bar painted over captions
            // (watch-audit P2). Captions belong above the controls scrim.
            subtitleView?.bringToFront()
            controlsManager = PlayerControlsManager(bar, viewModel.contentType)
            controlsManager?.onVisibilityChanged = { visible ->
                if (visible) bar.requestFocusOnPlayPause()
                // Lift captions out of the controls band while the bar is up (8% → 22% of
                // screen height from the bottom), back down when it hides.
                subtitleView?.let { sv ->
                    val screenH = resources.displayMetrics.heightPixels
                    val frac = if (visible) 0.22f else 0.08f
                    sv.setPadding(0, 0, 0, (screenH * frac).toInt())
                }
                // Toggle whether the (invisible alpha=0) Leanback dock children can
                // receive focus. When our bar is showing, block them — otherwise
                // DPAD navigation from a bar button finds the invisible Pause/Audio/etc.
                // buttons inside the dock and the cursor disappears. When our bar is
                // hidden, allow focus back into the dock so the SeekBar can capture
                // OK presses for OoustreamPlaybackGlue.onKey to summon the bar.
                view?.findViewById<android.view.ViewGroup>(
                    androidx.leanback.R.id.playback_controls_dock
                )?.descendantFocusability =
                    if (visible) android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    else android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
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
            bar.onSeekBack = { requestDpadSeek(-10_000) }
            bar.onSeekForward = { requestDpadSeek(10_000) }
            bar.onAspectRatio = { cycleAspectRatio() }
            bar.onRestart = {
                // Play from the beginning — the in-player "start over" (resume is now silent).
                player?.let { p ->
                    p.seekTo(0)
                    p.play()
                    bar.updatePosition(0, p.duration)
                }
            }
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
            bar.onDpadSeek = { deltaMs -> requestDpadSeek(deltaMs) }

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

        // Auto-save progress every 5s (no upper bound — completed flag handles removal). Gated via
        // checkpointProgress() so a save never fires from a non-READY / mid-rebuild state.
        if (viewModel.contentType != ContentType.LIVE) {
            viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    delay(5_000)
                    checkpointProgress()
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
                    if (dur > 0) lastKnownDurationMs = dur
                    if (dur > 0 && pos > 0 && (dur - pos) < 15_000 && !bingeShown) {
                        bingeShown = true
                        val nextInfo = viewModel.resolveNextEpisode()
                        if (nextInfo != null) {
                            // Gapless fast path: queue the next episode on the playlist so it
                            // pre-buffers over the remaining 15s. A blank episodeId can't be
                            // disambiguated in onMediaItemTransition (initial items have the
                            // default "" mediaId) — those stay on the legacy advance.
                            // Don't pre-buffer a second concurrent decoder for QHD/4K content.
                            // The gapless path keeps the current stream playing while it buffers
                            // the next episode — for 4K HDR HEVC that means TWO ~190MB decoders +
                            // two large buffers alive at once, which pushes a 1.5-2GB Fire Stick
                            // past the kernel Low-Memory-Killer threshold (confirmed LMK foreground
                            // kills at ~1.4GB on AFTKRT during 4K series binge). For big video we
                            // fall through to the sequential advance (a brief load between episodes).
                            val hiRes = isHighResVideo(p)
                            if (preBufferEnabled && !hiRes && pendingNextEpisode == null &&
                                !rebuildInProgress && p.mediaItemCount == 1 &&
                                nextInfo.episodeId.isNotBlank()
                            ) {
                                pendingNextEpisode = nextInfo
                                p.addMediaItem(buildNextMediaItem(nextInfo))
                                streamDiagnosticLogger.logAppEvent("PREBUFFER_QUEUED",
                                    "next=${nextInfo.name}, s${nextInfo.season}e${nextInfo.episodeNum}")
                            } else if (preBufferEnabled && hiRes) {
                                streamDiagnosticLogger.logAppEvent("PREBUFFER_SKIPPED_HIRES",
                                    "w=${p.videoFormat?.width}, h=${p.videoFormat?.height} — sequential advance to protect memory")
                            }
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
                // v3.7.0: repeated MediaCodecVideoRenderer errors used to trigger a
                // libVLC swap after 3 failures in 30s. With FFmpeg video extension
                // in EXTENSION_RENDERER_MODE_ON, the player already tries the
                // FFmpeg software fallback as part of its own renderer chain, so
                // this early-escape path isn't needed anymore. rebuildPlayerWithSoftwareDecoder()
                // below still handles the SW-decoder-rebuild case explicitly.
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
                // Deterministic source failure: the server returned bytes no extractor can read
                // (typically an HTML/error body served with HTTP 200, or a file whose real
                // container doesn't match its listed extension). Replaying the same URL can
                // never succeed, so don't burn the 6-retry ladder on it. Try the alternate
                // container extension ONCE (wrong containerExtension in provider listings is
                // common), then fail fast with an honest message.
                if ((isUnrecognizedContainerError(error) || isDeterministicHttpError(error)) &&
                    viewModel.contentType != ContentType.LIVE
                ) {
                    val failFastMessage =
                        if (isDeterministicHttpError(error)) friendlyErrorMessage(error)
                        else "This title appears to be broken or missing on your provider's server. " +
                            "Please try a different title or contact your provider."
                    if (!containerExtRetryAttempted) {
                        containerExtRetryAttempted = true
                        retryJob?.cancel()
                        retryJob = viewLifecycleOwner.lifecycleScope.launch {
                            showBufferingOverlay(true, immediate = true)
                            // Provider "dead listing" check. Some titles are LISTED (get_series_info /
                            // get_vod return them, so they appear in the app) but the provider serves an
                            // empty text/html page instead of redirecting to its video CDN — there is no
                            // media to play, and no ext-swap/retry can fix it. Detect it and give an honest,
                            // specific message. (Proven on flarecoral 2026-06-17: dead titles return HTTP 200
                            // text/html empty; served titles return 302 → CDN with real media.)
                            if (isUnrecognizedContainerError(error)) {
                                val ct = probeStreamContentType(viewModel.streamUrl)
                                if (ct != null && ct.trim().startsWith("text/html", ignoreCase = true)) {
                                    streamDiagnosticLogger.logAppEvent("PROVIDER_DEAD_LISTING",
                                        "contentType=$ct, channel=${healthMonitor?.channelName ?: "unknown"}")
                                    showFriendlyError("This title isn't available from your provider right now. " +
                                        "Try another title, or let your provider know.")
                                    return@launch
                                }
                            }
                            // Prefer the AUTHORITATIVE extension from get_vod_info — panels only
                            // serve a file at its exact extension and the heuristic swap can't
                            // guess exotic ones (Kung Fu Panda = .m2ts). Heuristic is the
                            // offline/API-failure fallback.
                            val currentExt = viewModel.streamUrl.substringAfterLast('.', "").lowercase()
                            val realExt = viewModel.fetchRealVodExtension()
                            val altUrl = when {
                                realExt != null && !realExt.equals(currentExt, ignoreCase = true) ->
                                    viewModel.streamUrl.substringBeforeLast('.') + ".$realExt"
                                // SERIES episodes already carry the AUTHORITATIVE container extension
                                // from get_series_info (e.g. .mkv). fetchRealVodExtension() is VOD-only
                                // (returns null → authoritative=false), so a series would otherwise fall
                                // into the mkv↔mp4 heuristic and request a file that doesn't exist →
                                // HTTP 551. The real failure is a server-side bad/empty body, not a wrong
                                // extension, so don't swap — fail fast with the honest message instead.
                                viewModel.contentType == ContentType.SERIES -> null
                                else -> alternateContainerUrl(viewModel.streamUrl)
                            }
                            if (altUrl == null) {
                                streamDiagnosticLogger.logAppEvent("UNPLAYABLE_SOURCE",
                                    "extRetryTried=false, noAlt=true, channel=${healthMonitor?.channelName ?: "unknown"}")
                                showFriendlyError(failFastMessage)
                                return@launch
                            }
                            streamDiagnosticLogger.logAppEvent("CONTAINER_EXT_RETRY",
                                "from=$currentExt, to=${altUrl.substringAfterLast('.')}, " +
                                "authoritative=${realExt != null}, channel=${healthMonitor?.channelName ?: "unknown"}")
                            viewModel.streamUrl = altUrl
                            val p = player ?: return@launch
                            setPlayerSource(altUrl)   // M2TS-aware (Kung Fu Panda = .m2ts)
                            p.prepare()
                            p.play()
                        }
                        return
                    }
                    streamDiagnosticLogger.logAppEvent("UNPLAYABLE_SOURCE",
                        "extRetryTried=true, channel=${healthMonitor?.channelName ?: "unknown"}")
                    showFriendlyError(failFastMessage)
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
                        rebuildInProgress = false   // safety net: never leave saves blocked once ready
                        stallDetectorJob?.cancel()
                        retryCount = 0
                        // Only start watchdog if not already running — restarting it
                        // resets watchdogResetCount to 0, preventing escalation to SW decoder.
                        // Seek flushes cause BUFFERING→READY which was restarting the watchdog
                        // every ~8s, trapping the recovery ladder at step 1 forever.
                        if (frameWatchdogJob?.isActive != true) {
                            startFrameWatchdog()
                        }
                        runSubtitlePipelineSelfTest()
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val pending = pendingNextEpisode ?: return
                // PLAYLIST_CHANGED fires on every setMediaItem (channel tune, rebuild, legacy
                // advance) and must never run the gapless bookkeeping. AUTO = natural end /
                // seek-past-end clamp; SEEK = seekToNextMediaItem (Watch Next button, skip
                // button, KEYCODE_MEDIA_NEXT via MediaSession) — both need identical handling.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                ) return
                if (mediaItem?.mediaId != pending.episodeId) return
                handleGaplessEpisodeTransition(pending, reason)
            }

            override fun onTracksChanged(tracks: Tracks) {
                AudioLogger.logTrackSelection(tracks)
                // Cache video codec/mime before any player rebuild loses this info —
                // used by watchdog give-up path to identify HEVC Main 10 content
                tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { group ->
                    if (group.length > 0) {
                        val videoFormat = group.getTrackFormat(0)
                        // The selector's actual verdict on the video track. support: 4=HANDLED,
                        // 3=EXCEEDS_CAPABILITIES, 1=UNSUPPORTED_SUBTYPE, 0=UNSUPPORTED_TYPE.
                        // selected=false with support<4 is the audio-plays-over-black-screen bug class
                        // (e.g. over-declared HEVC level + exceedRendererCapabilities=false).
                        streamDiagnosticLogger.logAppEvent(
                            "VIDEO_TRACK_SUPPORT",
                            "support=${group.getTrackSupport(0)}, selected=${group.isTrackSelected(0)}, " +
                                "mime=${videoFormat.sampleMimeType}, codecs=${videoFormat.codecs}, " +
                                "res=${videoFormat.width}x${videoFormat.height}@${videoFormat.frameRate}"
                        )
                        cachedVideoMime = videoFormat.sampleMimeType ?: ""
                        cachedVideoCodecs = videoFormat.codecs ?: ""
                        if (videoFormat.width > 0) cachedVideoWidth = videoFormat.width
                        if (videoFormat.height > 0) cachedVideoHeight = videoFormat.height

                        // Upfront oversized-video refusal (4K on a stick with no 4K decoder).
                        //
                        // Read straight from THIS emission's videoFormat — never the cached fields.
                        // The cache survives across channels and the empty-tracks emission that
                        // `setMediaItem` fires on every zap would otherwise let us refuse the NEXT
                        // channel using the PREVIOUS channel's resolution. Living inside this `let`
                        // guarantees we only judge a track that actually exists in `tracks`.
                        //
                        // Ask the device's real MediaCodec decoders about the exact dimensions rather
                        // than a fixed 2160 threshold — a 3840x1600 ultrawide the hardware genuinely
                        // handles still plays. Nothing else catches this: the tier's setMaxVideoSize
                        // cap is bypassed by Media3's exceedVideoConstraintsIfNecessary default, and
                        // the auto-registered FFmpeg software renderer claims support then decodes at
                        // ~0fps. Only a definitive `false` refuses; unknown (null) always proceeds
                        // (see VideoDecoderCapability). The v4.2.3 watchdog give-up is the backstop —
                        // this just turns a ~2.5-minute thrash into an immediate, honest message.
                        if (!oversizedVideoRefused &&
                            videoFormat.width > 0 && videoFormat.height > 0
                        ) {
                            val decodable = VideoDecoderCapability.canDecode(
                                cachedVideoMime, videoFormat.width, videoFormat.height,
                                videoFormat.frameRate
                            )
                            if (decodable == false) {
                                oversizedVideoRefused = true
                                val isMain10 = cachedVideoCodecs.startsWith("hvc1.2") ||
                                    cachedVideoCodecs.startsWith("hev1.2")
                                val is4k = videoFormat.height >= 1440 || videoFormat.width >= 2560
                                streamDiagnosticLogger.logAppEvent(
                                    "VIDEO_SIZE_UNSUPPORTED",
                                    "res=${videoFormat.width}x${videoFormat.height}, " +
                                        "mime=$cachedVideoMime, codecs=$cachedVideoCodecs, " +
                                        "fps=${videoFormat.frameRate}, " +
                                        "${DeviceTierDetector.describe(requireContext())}, " +
                                        "channel=${healthMonitor?.channelName ?: "unknown"}"
                                )
                                AudioLogger.log(
                                    "No MediaCodec decoder handles " +
                                        "${videoFormat.width}x${videoFormat.height} " +
                                        "$cachedVideoMime — refusing upfront"
                                )
                                showFriendlyError(
                                    when {
                                        is4k && isMain10 -> "This is a 4K HDR (10-bit) title — this device can't decode it. Try a 1080p or HD version."
                                        is4k -> "This is a 4K title — this device can't decode it. Try a 1080p or HD version."
                                        else -> "This video is too high-resolution for this device. Try an HD version."
                                    }
                                )
                                return
                            }
                        }
                    }
                }
                // v3.7.9: removed the upfront HEVC Main 10 refusal that previously
                // blocked playback on mt8695-class devices. The original assumption —
                // "hardware doesn't support profile 2" — is provably wrong: customer
                // 'allinone' debug log shows OMX.MTK.VIDEO.DECODER.HEVC successfully
                // initializing in 804ms and rendering a first frame on mt8695 with
                // hvc1.2.4.L120.90 (Main 10) content, AND the same content plays in
                // IPTV Smarters on the same device. The hardware DOES accept Main 10.
                //
                // We now let ExoPlayer's normal decoder selection run. Hardware MediaCodec
                // gets first crack (per v3.7.4 createFfmpegPreferredRenderersFactory video
                // override + the default createRenderersFactory MODE_ON ordering). If a
                // genuinely-incapable device rejects the format, setEnableDecoderFallback(true)
                // chains through c2.android.hevc.decoder (software). If THAT slideshows
                // we'd want a FPS-based detector — but adding one preemptively isn't
                // worth it until a real customer report shows the failure mode.
                //
                // Diagnostic kept for visibility — emit the capability "would-have-blocked"
                // event so we can correlate logs across customers if the relaxation
                // produces fallout.
                if (cachedVideoMime == androidx.media3.common.MimeTypes.VIDEO_H265
                    && (cachedVideoCodecs.startsWith("hvc1.2") || cachedVideoCodecs.startsWith("hev1.2"))
                    && !DeviceTierDetector.canDecodeHevcMain10(requireContext())) {
                    streamDiagnosticLogger.logAppEvent("HEVC_MAIN10_ATTEMPT",
                        "tier_says_blocked=true, letting_hw_try, codecs=$cachedVideoCodecs, " +
                        "${DeviceTierDetector.describe(requireContext())}, channel=${healthMonitor?.channelName ?: "unknown"}")
                }

                // v3.7.3: MTK 5.1 audio preemptive FFmpeg rebuild.
                // Hardware AC3/EAC3/DTS decode + ChannelMixingAudioProcessor 6→2 downmix
                // on mt8695/mt8696/mt8167 underruns the audio sink every 10-15s, which
                // trips the player's stall recovery (15s IDLE → full restart) in a loop.
                // FFmpeg handles decode + downmix in one pass and keeps up. Catch it on
                // first onTracksChanged so the rebuild happens inside the initial buffer
                // window instead of after users have seen the stall pattern.
                //
                // v4.2.6: on the KNOWN-BAD MTK chipsets (mt8695/mt8167) the gate fires at ANY
                // channel count, not just >=6. Customer kiarawil (AFTSS, mt8695, v4.2.5): series
                // MKVs with EAC3 *2ch* on hardware froze after the first video frame with a full,
                // byte-frozen 30s buffer — on BOTH the HW and FFmpeg video decoders — while every
                // session with FFmpeg audio (AC3 6ch live) or AAC played fine. A silently-stalled
                // hardware EAC3 decoder freezes the playback clock, so video renders one frame and
                // waits forever; mt8695 falsely claiming AC3/EAC3 support is our oldest documented
                // failure (v2.3.5, v3.3.3). mt8696 keeps the >=6 gate — its hardware EAC3 works.
                if (!mtkMultichannelFfmpegApplied && AudioLogger.isFfmpegAvailable) {
                    val hw = android.os.Build.HARDWARE.lowercase()
                    val isMtk = hw.startsWith("mt8")
                    if (isMtk) {
                        val isKnownBadMtkAudio = hw.contains("mt8695") || hw.contains("mt8167")
                        val selectedAudioFormat = tracks.groups
                            .filter { it.type == C.TRACK_TYPE_AUDIO }
                            .flatMap { group ->
                                (0 until group.length).mapNotNull { i ->
                                    if (group.isTrackSelected(i)) group.getTrackFormat(i) else null
                                }
                            }
                            .firstOrNull()
                        val needsFfmpeg = selectedAudioFormat?.let { fmt ->
                            val mime = fmt.sampleMimeType
                            val isDolbyClass =
                                mime == androidx.media3.common.MimeTypes.AUDIO_E_AC3 ||
                                    mime == androidx.media3.common.MimeTypes.AUDIO_AC3 ||
                                    mime == androidx.media3.common.MimeTypes.AUDIO_DTS
                            isDolbyClass && (isKnownBadMtkAudio || fmt.channelCount >= 6)
                        } ?: false
                        if (needsFfmpeg) {
                            mtkMultichannelFfmpegApplied = true
                            val mime = selectedAudioFormat?.sampleMimeType
                            val ch = selectedAudioFormat?.channelCount
                            val channelName = healthMonitor?.channelName ?: "unknown"
                            AudioLogger.log(
                                "MTK $hw + $mime ${ch}ch — preemptive FFmpeg-preferred rebuild"
                            )
                            streamDiagnosticLogger.logAppEvent(
                                "MTK_MULTICHANNEL_FFMPEG_REBUILD",
                                "hw=$hw, mime=$mime, ch=$ch, channel=$channelName"
                            )
                            rebuildPlayerWithFfmpegPreferred()
                            return
                        }
                    }
                }

                // Don't override user's manual track selection from TrackPickerOverlay
                if (userTrackOverrideActive) return
                // Don't re-enable audio if Stage 2 fallback intentionally disabled it
                if (audioDisabledByFallback) return
                try {
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

                    // Show "No Audio" overlay if stream has no audio tracks — but
                    // debounced 1.5s so the empty-tracks emission during channel-switch
                    // transitions doesn't flash it on every change.
                    if (audioGroups.isEmpty()) {
                        AudioLogger.logNoAudioTracks()
                        noAudioOverlayJob?.cancel()
                        noAudioOverlayJob = viewLifecycleOwner.lifecycleScope.launch {
                            delay(1500)
                            // Re-check tracks before showing — if they've populated since,
                            // bail out silently.
                            val current = player?.currentTracks?.groups
                                ?.filter { it.type == C.TRACK_TYPE_AUDIO } ?: emptyList()
                            if (current.isEmpty()) {
                                audioStatusOverlay?.showNoAudio()
                            }
                        }
                        return
                    }
                    // Tracks now have audio — kill any pending show and dismiss any
                    // currently-visible overlay.
                    noAudioOverlayJob?.cancel()
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
                    showBufferingOverlay(true, label = getString(R.string.waiting_for_network))
                } else if (netState.isConnected && !previouslyConnected) {
                    previouslyConnected = true
                    val p = player ?: return@collect
                    // Network is back after a confirmed drop. Re-kick not just IDLE/errored players but
                    // also one stuck in STATE_BUFFERING — otherwise a fast reconnect leaves it spinning
                    // until the 15-30s stall detector / read-timeout fires (watch-audit P1). The
                    // !previouslyConnected guard means we genuinely lost the network, so this won't
                    // interrupt a normal initial/seek buffer.
                    if (p.playbackState == Player.STATE_IDLE ||
                        p.playbackState == Player.STATE_BUFFERING ||
                        p.playerError != null) {
                        retryCount = 0
                        if (viewModel.contentType == ContentType.LIVE) p.seekToDefaultPosition()
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

    /**
     * Two-stage recovery for a mid-stream audio dropout (AUDIO_STALL from the health monitor or
     * AUDIO_SINK_ERROR from the diagnostic listener). Stage 1: disable→re-enable the audio track
     * type, forcing the audio renderer + sink to re-init without touching video. Stage 2 (if a
     * second stall fires on the same content): rebuild with the FFmpeg-preferred audio factory.
     * Capped per content via [audioStallRecoveryStage]; skipped when audio is deliberately off.
     */
    private fun recoverFromAudioStall(trigger: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val p = player ?: return@launch
            if (audioDisabledByFallback || isAudioOnly || rebuildInProgress) return@launch
            when (audioStallRecoveryStage) {
                0 -> {
                    audioStallRecoveryStage = 1
                    streamDiagnosticLogger.logAppEvent("AUDIO_STALL_RECOVERY",
                        "stage=1_renderer_reinit, trigger=$trigger")
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    delay(250)
                    val p2 = player ?: return@launch
                    p2.trackSelectionParameters = p2.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
                }
                1 -> {
                    audioStallRecoveryStage = 2
                    if (AudioLogger.isFfmpegAvailable) {
                        streamDiagnosticLogger.logAppEvent("AUDIO_STALL_RECOVERY",
                            "stage=2_ffmpeg_rebuild, trigger=$trigger")
                        rebuildPlayerWithFfmpegPreferred()
                    } else {
                        streamDiagnosticLogger.logAppEvent("AUDIO_STALL_RECOVERY",
                            "stage=2_skipped_no_ffmpeg, trigger=$trigger")
                    }
                }
                else -> { /* both stages spent for this content — leave it to the error paths */ }
            }
        }
    }

    /** Invalidate a queued gapless next episode (rebuilds replace the playlist with a single item). */
    private fun dropPendingNextEpisode() {
        if (pendingNextEpisode != null) {
            pendingNextEpisode = null
            streamDiagnosticLogger.logAppEvent("PREBUFFER_DROPPED", "reason=player_rebuild")
        }
    }

    /** Safely releases a player instance, catching exceptions from stuck decoders (e.g. MTK). */
    /** Lightweight probe of a stream URL's final Content-Type via the shared OkHttp client (which
     *  follows redirects, so a served title resolves to its CDN's video type). A `text/html` result on
     *  a /movie//series/ stream = a provider "dead listing" (title listed but no media served). Returns
     *  null on failure/timeout. Error-path use only (one brief, self-closing request). */
    private suspend fun probeStreamContentType(url: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okHttpClient.newBuilder()
                    .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Ooustream/1.0 (Mobile)")
                    .header("Range", "bytes=0-1")
                    .build()
                client.newCall(req).execute().use { it.header("Content-Type") }
            } catch (_: Exception) {
                null
            }
        }

    /**
     * True when the currently-decoding video is QHD/4K (>= 2560 wide or >= 1440 tall).
     * Used to suppress the gapless binge pre-buffer for large content: running a second
     * concurrent decoder + buffer for a 4K HDR stream can push a 1.5-2GB Fire Stick past
     * the Low-Memory-Killer threshold. Returns false when no video format is available yet
     * (audio-only / not ready) so ordinary 1080p content keeps its gapless transition.
     */
    private fun isHighResVideo(p: ExoPlayer): Boolean {
        val vf = p.videoFormat ?: return false
        return vf.width >= 2560 || vf.height >= 1440
    }

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
     * Detects "no extractor recognizes these bytes" — UnrecognizedInputFormatException /
     * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED. Deterministic per URL: the server is sending
     * something that isn't the listed media container at all.
     */
    private fun isUnrecognizedContainerError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) return true
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is androidx.media3.exoplayer.source.UnrecognizedInputFormatException) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * Deterministic HTTP failures: 4xx (except 408 timeout / 429 throttle) and 551 (Xtream
     * "stream unavailable / not in package"). Retrying the same URL can't succeed — but a 404
     * CAN be a wrong listed container extension, so these share the alternate-ext retry.
     */
    private fun isDeterministicHttpError(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < 6) {
            if (cause.javaClass.name == "androidx.media3.datasource.HttpDataSource\$InvalidResponseCodeException") {
                val code = runCatching {
                    cause!!.javaClass.getField("responseCode").getInt(cause)
                }.getOrNull() ?: return false
                return (code in 400..499 && code != 408 && code != 429) || code == 551
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    /** Swap a VOD/Series URL's container extension (mp4 ↔ mkv, avi → mkv). Null if no sensible swap. */
    private fun alternateContainerUrl(url: String): String? {
        val ext = url.substringAfterLast('.', "").lowercase()
        val alt = when (ext) {
            "mp4" -> "mkv"
            "mkv" -> "mp4"
            "avi" -> "mkv"
            else -> return null
        }
        return url.substringBeforeLast('.') + ".$alt"
    }

    /**
     * Detects the Media3 MatroskaExtractor varint regression.
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
                    // Past the first silent escalation, tell the user we're actively reconnecting
                    // instead of leaving an unlabeled spinner over the held frame.
                    if (retryCount > 1) {
                        showBufferingOverlay(true, immediate = true, label = getString(R.string.reconnecting))
                    }
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
            var consecutiveSlideshowPolls = 0
            // HEVC slideshow detection: SW HEVC decoder renders frames but too slowly (8fps)
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
                    // Data starvation vs decoder stall. If the buffer is nearly empty, "no new
                    // frames" means the network can't feed the decoder — a hard reset here would
                    // re-buffer from scratch (re-opening the connection) and loop, which is exactly
                    // what happened once 4K Dolby Vision moved onto the HW decoder: brief throughput
                    // dips got misread as decoder freezes and the resets prevented any buffer from
                    // ever building. Let ExoPlayer's own buffering ride it out; only a stall WITH a
                    // healthy buffer (data present, frames not advancing) is a real decoder fault.
                    val bufferedAheadMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
                    if (bufferedAheadMs < WATCHDOG_STARVATION_BUFFER_MS) {
                        noNewFramesSinceMs = 0L
                        continue
                    }
                    if (noNewFramesSinceMs == 0L) {
                        noNewFramesSinceMs = android.os.SystemClock.elapsedRealtime()
                    }
                    val frozenMs = android.os.SystemClock.elapsedRealtime() - noNewFramesSinceMs
                    // Show the loading backdrop so a silent freeze reads as "loading", not "frozen".
                    // immediate=true: the watchdog only fires after the frame is already gone, so don't debounce.
                    if (frozenMs >= FRAME_WATCHDOG_INTERVAL_MS && !watchdogOverlayShown) {
                        watchdogOverlayShown = true
                        withContext(Dispatchers.Main) {
                            showBufferingOverlay(true, immediate = true)
                        }
                    }
                    if (frozenMs >= FRAME_WATCHDOG_FROZEN_MS) {
                        watchdogResetCount++
                        noNewFramesSinceMs = 0L
                        lastRenderedFrameCount = -1
                        val channelName = healthMonitor?.channelName ?: "unknown"

                        // ── 4K HEVC handling (Allwinner / generic Android-box decoder gap) ─────────
                        // No software decoder in this CPU class can sustain realtime 4K HEVC, so the
                        // normal HW→SW escalation ladder below is futile for 4K HEVC — it thrashes for
                        // ~2.5 minutes and ends on "format not supported". Handle 4K HEVC explicitly:
                        //   • already on a SOFTWARE video decoder → unwinnable, fail fast + honest.
                        //     HEVC Main 10 lands here because OMX.allwinner HEVC hides its 10-bit
                        //     profile, so Media3's default factory auto-picks the FFmpeg SW renderer
                        //     (which sets neither usingSoftwareVideoDecoder nor usingFfmpegVideoDecoder —
                        //     that's why we sniff the live decoder name).
                        //   • on the HARDWARE decoder → a stall is a transient network dip or sustained
                        //     starvation on a thin connection. Hard-reset to recover, but NEVER swap to
                        //     software (can't do 4K HEVC + the rebuild re-buffers from scratch, making a
                        //     thin pipe worse). Give up honestly after a few resets unless the HW decoder
                        //     has proven it can sustain frames.
                        val vf4kW = p.videoFormat?.width?.takeIf { it > 0 } ?: cachedVideoWidth
                        val vf4kH = p.videoFormat?.height?.takeIf { it > 0 } ?: cachedVideoHeight
                        val mime4k = cachedVideoMime.ifEmpty { p.videoFormat?.sampleMimeType ?: "" }
                        // Treat 4K Dolby Vision as 4K-HEVC-class. Its base layer IS HEVC (we route it to
                        // the HW HEVC decoder via DolbyVisionBaseLayer), and — critically — the FFmpeg
                        // software video renderer NATIVE-CRASHES (SIGSEGV / malloc integer-underflow on a
                        // 4K frame) trying to decode it. So DV 4K must take this HW-only recovery path
                        // and NEVER fall into the generic ladder below that rebuilds onto FFmpeg video.
                        val is4kHevc = (vf4kH >= 1440 || vf4kW >= 2560) &&
                            (mime4k == androidx.media3.common.MimeTypes.VIDEO_H265 ||
                                mime4k == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION)
                        if (is4kHevc) {
                            val codecs4k = cachedVideoCodecs.ifEmpty { p.videoFormat?.codecs ?: "" }
                            val decoderLc = activeVideoDecoderName.lowercase()
                            val onSoftwareVideo = usingSoftwareVideoDecoder || usingFfmpegVideoDecoder ||
                                decoderLc.contains("ffmpeg") ||
                                decoderLc.startsWith("c2.android") ||
                                decoderLc.startsWith("omx.google")
                            if (onSoftwareVideo) {
                                val isMain10 = codecs4k.startsWith("hvc1.2") || codecs4k.startsWith("hev1.2")
                                AudioLogger.log("Frame watchdog: software 4K HEVC unplayable — fast give-up (decoder=$activeVideoDecoderName)")
                                streamDiagnosticLogger.logAppEvent("WATCHDOG_GIVE_UP",
                                    "reason=sw_4k_hevc_unplayable, decoder=$activeVideoDecoderName, res=${vf4kW}x${vf4kH}, codecs=$codecs4k, channel=$channelName")
                                withContext(Dispatchers.Main) {
                                    showFriendlyError(
                                        if (isMain10) "This is a 4K HDR (10-bit) title — this device can't decode it. Try a 1080p or HD version."
                                        else "This is a 4K title — this device can't decode it. Try a 1080p or HD version."
                                    )
                                }
                                return@launch
                            }
                            // Hardware decoder stalled on 4K HEVC — recover, never swap to software.
                            if (watchdogResetCount <= FOURK_HW_HARD_RESET_LIMIT || hwDecoderProvenGood) {
                                AudioLogger.log("Frame watchdog: HW 4K HEVC stall — hard reset (step $watchdogResetCount, no SW swap)")
                                streamDiagnosticLogger.logAppEvent("WATCHDOG_HW_4K_RESET",
                                    "reset=$watchdogResetCount, hwProven=$hwDecoderProvenGood, res=${vf4kW}x${vf4kH}, channel=$channelName")
                                val pos = p.currentPosition
                                p.stop(); p.seekTo(pos); p.prepare(); p.play()
                                continue
                            }
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_GIVE_UP",
                                "reason=hw_4k_hevc_stalled, resets=$watchdogResetCount, res=${vf4kW}x${vf4kH}, codecs=$codecs4k, channel=$channelName")
                            withContext(Dispatchers.Main) {
                                showFriendlyError("This 4K title won't play smoothly on your current connection. Try a 1080p or HD version.")
                            }
                            return@launch
                        }
                        // ──────────────────────────────────────────────────────────────────────────

                        // Fast-fail: if we're already on the SW decoder and its counters are still
                        // null, the decoder never initialized. No point running steps 3/4 (they just
                        // restart the same broken SW player). Give up immediately.
                        val swDecoderFailedToInit = usingSoftwareVideoDecoder && p.videoDecoderCounters == null

                        if (watchdogResetCount > MAX_WATCHDOG_RESETS || swDecoderFailedToInit) {
                            val codecs = cachedVideoCodecs.ifEmpty { p.videoFormat?.codecs ?: "" }
                            val mime = cachedVideoMime.ifEmpty { p.videoFormat?.sampleMimeType ?: "" }
                            val decoderNull = p.videoDecoderCounters == null

                            // v3.7.10: Final escalation before giving up — if we haven't yet tried
                            // the FFmpeg software video decoder (libavcodec via PR #1591), do that
                            // now. OMX.google.h264.decoder is single-threaded and routinely fails on
                            // 1080p H.264 High Profile + low-RAM devices (mt8695 / Fire TV Stick Lite,
                            // customer andresi's report). FFmpeg's frame-threaded decoder can keep up
                            // where the platform SW decoder can't. supportsFormat guards the rebuild
                            // for codecs the FFmpeg AAR doesn't have enabled.
                            if (!usingFfmpegVideoDecoder
                                && mime.isNotEmpty()
                                && AudioPipelineFactory.isFfmpegVideoAvailable(mime)) {
                                AudioLogger.log("Frame watchdog: pre-give-up FFmpeg video escalation — codec=$codecs mime=$mime")
                                streamDiagnosticLogger.logAppEvent("WATCHDOG_FFMPEG_VIDEO",
                                    "trigger=pre_give_up, resets=$watchdogResetCount, sw=$usingSoftwareVideoDecoder, codecs=$codecs, mime=$mime, channel=$channelName")
                                withContext(Dispatchers.Main) {
                                    rebuildPlayerWithFfmpegVideoDecoder()
                                }
                                return@launch
                            }

                            // ExoPlayer gave up — HW decoder silently died, the platform SW decoder
                            // couldn't keep up, AND (if reachable) FFmpeg software video also failed.
                            // No more recovery paths.
                            val reason = if (swDecoderFailedToInit) "sw_decoder_init_failed" else "max_resets"
                            AudioLogger.log("Frame watchdog: giving up ($reason) — no more recovery paths")
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_GIVE_UP",
                                "reason=$reason, resets=$watchdogResetCount, sw=$usingSoftwareVideoDecoder, ff=$usingFfmpegVideoDecoder, decoderNull=$decoderNull, codecs=$codecs, mime=$mime, channel=$channelName")
                            withContext(Dispatchers.Main) {
                                showFriendlyError("This content uses a video format not supported on this device.")
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

                        // v3.7.10: On known-bad MTK chipsets (mt8695, mt8167), skip the
                        // OMX.google.h264.decoder path entirely. We have customer evidence
                        // (andresi report, May 2026) that this single-threaded Stagefright
                        // decoder routinely stalls at fps=0 on 1080p H.264 High Profile
                        // because the 4× Cortex-A53 cores can't keep up single-threaded.
                        // Go straight to FFmpeg software video (libavcodec, frame-threaded)
                        // which is the same decoder IPTV Smarters uses on this hardware.
                        // mt8696 (AFTKRT) has working HW HEVC and a faster CPU, so it
                        // continues to use the platform SW decoder path.
                        val mtkHardware = Build.HARDWARE.lowercase()
                        val isKnownBadMtk = mtkHardware.contains("mt8695") || mtkHardware.contains("mt8167")
                        if (watchdogResetCount == 2
                            && isKnownBadMtk
                            && !hwDecoderProvenGood
                            && !usingSoftwareVideoDecoder) {
                            val mime = cachedVideoMime.ifEmpty { p.videoFormat?.sampleMimeType ?: "video/avc" }
                            if (AudioPipelineFactory.isFfmpegVideoAvailable(mime)) {
                                AudioLogger.log("Frame watchdog: step 2 known-bad MTK — FFmpeg video decoder")
                                streamDiagnosticLogger.logAppEvent("WATCHDOG_FFMPEG_VIDEO",
                                    "trigger=known_bad_mtk, hw=$mtkHardware, mime=$mime, channel=$channelName")
                                withContext(Dispatchers.Main) {
                                    rebuildPlayerWithFfmpegVideoDecoder()
                                }
                                return@launch
                            }
                            // FFmpeg AAR didn't load or doesn't support this codec — fall through
                            // to the platform SW path so we still attempt SOMETHING.
                        }

                        // Other MTK chipsets without hwDecoderProvenGood: try platform SW
                        // decoder + 720p cap. mt8696 (AFTKRT) lands here.
                        if (watchdogResetCount == 2
                            && AudioPipelineFactory.isMtkDevice()
                            && !hwDecoderProvenGood
                            && !usingSoftwareVideoDecoder) {
                            AudioLogger.log("Frame watchdog: step 2 MTK — software decoder at 720p")
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_MTK_SW_FALLBACK",
                                "reset=2, hwProven=false, hw=$mtkHardware, channel=$channelName")
                            withContext(Dispatchers.Main) {
                                trackSelector?.setParameters(
                                    trackSelector!!.buildUponParameters().setMaxVideoSize(1280, 720)
                                )
                                rebuildPlayerWithSoftwareDecoder()
                            }
                            return@launch
                        }

                        // v3.7.12: When FFmpeg software video is already active and stalled
                        // on a known-bad MTK chipset (mt8695, mt8167), do NOT escalate into
                        // rebuildPlayerWithSoftwareDecoder() — that builds the player around
                        // OMX.google.h264.decoder which throws IllegalArgumentException from
                        // MediaCodec.native_configure on these chipsets. ExoPlayer then
                        // retries 6 times back-to-back and the user sees "Unable to play
                        // content" after a long crash loop. (Customer vanaeym AFTMM mt8695,
                        // May 2026 — "The Calling Witch" 1080p H.264 High Profile + EAC3.)
                        //
                        // Instead try one 720p cap on FFmpeg (fewer pixels per frame may
                        // unblock libavcodec on 4× A53). If that also stalls, give up
                        // cleanly with the friendly error — no more decoder swaps to try.
                        if (isKnownBadMtk && usingFfmpegVideoDecoder) {
                            // v4.2.6: the v3.7.12 "720p cap retry" rung was removed. It was
                            // unreachable — the FFmpeg rebuild restarts the watchdog, so the
                            // post-rebuild counter reaches the give-up below at reset=1 before
                            // `== 2` could ever match (kiarawil report confirms resets=1) — and
                            // it was a no-op lever anyway: setMaxVideoSize is a PREFERENCE
                            // (exceedVideoConstraintsIfNecessary defaults true), so it cannot
                            // deselect a single-bitrate 1080p track or reduce decode work.
                            val codecs = cachedVideoCodecs.ifEmpty { p.videoFormat?.codecs ?: "" }
                            val mime = cachedVideoMime.ifEmpty { p.videoFormat?.sampleMimeType ?: "" }
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_GIVE_UP",
                                "reason=ffmpeg_failed_on_bad_mtk, resets=$watchdogResetCount, hw=$mtkHardware, codecs=$codecs, mime=$mime, channel=$channelName")
                            withContext(Dispatchers.Main) {
                                showFriendlyError("This content uses a video format not supported on this device.")
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
                    // v4.0.0 slideshow guard (watch-audit): a software decoder "playing" at ~3-8fps
                    // advances frames forever without tripping the frozen-frame path — the user is
                    // stranded in an unwatchable slideshow with NO error (mt8695 + HEVC Main 10 via
                    // FFmpeg). Only armed on the software-decoder paths we escalated to; genuine
                    // low-fps content on a healthy HW decoder is left alone.
                    if (usingSoftwareVideoDecoder && framesDelta in 1 until SLIDESHOW_MIN_FRAMES_PER_POLL) {
                        consecutiveSlideshowPolls++
                        if (consecutiveSlideshowPolls >= SLIDESHOW_POLLS_GIVE_UP) {
                            val codecs = cachedVideoCodecs.ifEmpty { p.videoFormat?.codecs ?: "" }
                            val fps = framesDelta * 1000f / FRAME_WATCHDOG_INTERVAL_MS
                            streamDiagnosticLogger.logAppEvent("WATCHDOG_SLIDESHOW_GIVE_UP",
                                "fps=$fps, polls=$consecutiveSlideshowPolls, ff=$usingFfmpegVideoDecoder, " +
                                "codecs=$codecs, mime=$cachedVideoMime, channel=${healthMonitor?.channelName ?: "unknown"}")
                            val isMain10 = cachedVideoMime == androidx.media3.common.MimeTypes.VIDEO_H265 &&
                                (codecs.startsWith("hvc1.2") || codecs.startsWith("hev1.2"))
                            withContext(Dispatchers.Main) {
                                showFriendlyError(
                                    if (isMain10) "This title is 10-bit HDR video, which this device can't play smoothly."
                                    else "This content uses a video format this device can't play smoothly."
                                )
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
                            // Bidirectional quality (watch-audit #4): lift any watchdog resolution cap once
                            // playback has been sustained-good, so a transient dip doesn't keep the rest of
                            // the title soft. One-shot per content (upwardReprobeAttempted) to avoid
                            // oscillation. NOTE: the SW→HW *decoder*-swap re-probe is intentionally NOT done
                            // here — HW-decode failures are usually a permanent codec/chip limit, so a
                            // mid-title HW rebuild would almost always glitch then fall back. Left for a
                            // device-verified follow-up.
                            if (!upwardReprobeAttempted) {
                                upwardReprobeAttempted = true
                                withContext(Dispatchers.Main) {
                                    trackSelector?.let { ts ->
                                        ts.setParameters(ts.buildUponParameters().clearVideoSizeConstraints())
                                    }
                                    streamDiagnosticLogger.logAppEvent("QUALITY_REPROBE", "cleared video cap after sustained playback")
                                }
                            }
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
        dropPendingNextEpisode()   // rebuild setMediaItems a single item — boundary falls back to legacy advance
        val currentPosition = p.currentPosition
        rebuildInProgress = true   // suppress progress saves until position is restored
        showBufferingOverlay(true, immediate = true)   // show art over the rebuild gap (silent — no toast)
        usingSoftwareVideoDecoder = true

        // v3.7.6: detach the OLD glue from its host BEFORE the rebuild. Without this
        // the old transport row stays in the fragment's row adapter and the default
        // Leanback overlay renders behind our PlayerControlsBar (the "old format
        // still stuck" customer report on v3.7.5).
        glue?.host = null

        // Stop current player to free decoder resources
        p.stop()

        // Rebuild in-place: release old player, create new one with SW decoders
        diagnosticListener?.let { p.removeListener(it); p.removeAnalyticsListener(it) }
        healthMonitor?.stop()
        mediaSession?.release()
        mediaSession = null
        safeReleasePlayer(p)

        val softwareRenderersFactory = AudioPipelineFactory.createSoftwareVideoRenderersFactory(requireContext())
        val dataSourceFactory = StreamingDataFactories.buildDataSourceFactory(okHttpClient)
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
            // DolbyVisionBaseLayer.wrap on REBUILD paths too — the rewrites are format-level
            // (DV P7→hevc, over-declared level normalization), orthogonal to which renderer decodes.
            // Leaving rebuilds unwrapped caused a real bug: the MTK 5.1-audio rebuild fired 3ms after
            // the main player selected a normalized 4K HEVC track and its unwrapped extractor brought
            // the bogus H156 level back → video track deselected → audio over black screen.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    dataSourceFactory,
                    DolbyVisionBaseLayer.wrap(requireContext(), StreamingDataFactories.buildExtractorsFactory())
                )
            )
            // Hold CPU+WiFi awake while playing/buffering — without this the radio can
            // power-save mid-stall and turn a recoverable dip into a long rebuffer.
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
            // v3.7.5: re-attach our custom controls. Without these two lines the new glue
            // falls back to default Leanback PlaybackTransportControlGlue UI ("old format
            // bleeds through") and our PlayerControlsBar loses its auto-hide driver
            // ("controls stay on screen").
            customControlsManager = controlsManager
        }
        // v3.7.7: re-attach all the channel-switch / action-button / overlay-passthrough
        // callbacks that the original glue had. Without this, the customer can't change
        // channels, trigger the audio picker, sleep timer, etc. after the rebuild.
        wireGlueCallbacks(glue!!)
        // hideControlsOverlay must run AFTER the host's attach sequence completes,
        // otherwise the host's auto-show on attach overrides our hide and the default
        // Leanback transport overlay stays visible behind our custom PlayerControlsBar.
        view?.post {
            try { glue?.host?.hideControlsOverlay(false) } catch (_: Exception) {}
            forceHideLeanbackPlaybackDock()
        }

        // Restore playback from saved position
        player!!.setMediaItem(MediaItem.fromUri(viewModel.streamUrl))
        player!!.prepare()
        if (currentPosition > 0) player!!.seekTo(currentPosition)
        rebuildInProgress = false   // position restored — checkpoints may resume
        player!!.play()

        streamDiagnosticLogger.logAppEvent("PLAYER_REBUILD", "decoder=software, position=${currentPosition}ms")
    }

    /**
     * v3.7.10: Rebuilds the ExoPlayer around our FFmpeg software video decoder
     * (ExperimentalFfmpegVideoRenderer from PR #1591 in the bundled AAR). This is
     * the final escalation step in the watchdog ladder for content that neither
     * the hardware decoder NOR the platform software decoder (OMX.google.h264.decoder)
     * can handle.
     *
     * Why we need it: customer andresi (mt8695 Fire TV Stick Lite, 128MB heap) hit
     * the WATCHDOG_GIVE_UP path on a 1080p H.264 High Profile series episode. HW
     * decoder OMX.MTK.VIDEO.DECODER.AVC stalled after 1 frame; SW decoder
     * OMX.google.h264.decoder is single-threaded and can't sustain 1080p on 4× A53.
     * libavcodec via FFmpeg has frame-level multithreading and matches what IPTV
     * Smarters / TiviMate do via IJKPlayer on the same hardware.
     *
     * Identical structure to [rebuildPlayerWithSoftwareDecoder] — same listener and
     * glue re-attachment, same low-memory load control, same MediaSession lifecycle.
     */
    private fun rebuildPlayerWithFfmpegVideoDecoder() {
        val p = player ?: return
        // Defense-in-depth: the FFmpeg software video renderer NATIVE-CRASHES (SIGSEGV / a ~4GB
        // malloc integer-underflow) trying to allocate a 4K frame — it cannot decode 4K in this CPU
        // class anyway. Never rebuild onto it for 4K content; fail honestly instead of taking down
        // the whole process. The watchdog's is4kHevc branch already routes 4K HEVC/DV away from here,
        // so this only fires if a future path slips through.
        val rbW = p.videoFormat?.width?.takeIf { it > 0 } ?: cachedVideoWidth
        val rbH = p.videoFormat?.height?.takeIf { it > 0 } ?: cachedVideoHeight
        if (rbH >= 1440 || rbW >= 2560) {
            streamDiagnosticLogger.logAppEvent("FFMPEG_VIDEO_REBUILD_BLOCKED",
                "reason=4k_would_crash, res=${rbW}x${rbH}, channel=${healthMonitor?.channelName ?: "unknown"}")
            showFriendlyError("This 4K title can't be decoded on this device. Try a 1080p or HD version.")
            return
        }
        dropPendingNextEpisode()   // rebuild setMediaItems a single item — boundary falls back to legacy advance
        val currentPosition = p.currentPosition
        rebuildInProgress = true   // suppress progress saves until position is restored
        showBufferingOverlay(true, immediate = true)   // show art over the rebuild gap (silent — no toast)
        val currentUrl = viewModel.streamUrl
        usingSoftwareVideoDecoder = true
        usingFfmpegVideoDecoder = true

        // Detach the OLD glue from its host BEFORE the rebuild — without this the old
        // transport row stays in the fragment's row adapter and the default Leanback
        // overlay renders behind our PlayerControlsBar.
        glue?.host = null

        p.stop()
        diagnosticListener?.let { p.removeListener(it); p.removeAnalyticsListener(it) }
        healthMonitor?.stop()
        mediaSession?.release()
        mediaSession = null
        safeReleasePlayer(p)

        val ffmpegVideoFactory = AudioPipelineFactory.createFfmpegVideoSoftwareRenderersFactory(requireContext())
        val dataSourceFactory = StreamingDataFactories.buildDataSourceFactory(okHttpClient)
        val bandwidthMeter = DefaultBandwidthMeter.Builder(requireContext()).build()

        // Same buffer policy as SW rebuild: low-memory devices need conservative buffers
        // so frame buffers (FFmpeg output is uncompressed YUV — a few MB per frame) and
        // the existing decoded queue don't push the heap past the GC threshold.
        val ffmpegLoadControl = run {
            val am2 = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am2.memoryClass <= 192) BufferConfigs.forLowMemory(viewModel.contentType)
            else BufferConfigs.forContentType(viewModel.contentType)
        }

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(ffmpegVideoFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(ffmpegLoadControl)
            // DolbyVisionBaseLayer.wrap on REBUILD paths too — the rewrites are format-level
            // (DV P7→hevc, over-declared level normalization), orthogonal to which renderer decodes.
            // Leaving rebuilds unwrapped caused a real bug: the MTK 5.1-audio rebuild fired 3ms after
            // the main player selected a normalized 4K HEVC track and its unwrapped extractor brought
            // the bogus H156 level back → video track deselected → audio over black screen.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    dataSourceFactory,
                    DolbyVisionBaseLayer.wrap(requireContext(), StreamingDataFactories.buildExtractorsFactory())
                )
            )
            // Hold CPU+WiFi awake while playing/buffering — without this the radio can
            // power-save mid-stall and turn a recoverable dip into a long rebuffer.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player!!.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )

        diagnosticListener?.let { listener ->
            player!!.addListener(listener)
            player!!.addAnalyticsListener(listener)
        }
        healthMonitor?.apply {
            this.bandwidthMeter = bandwidthMeter
            start(player!!)
        }

        mediaSession = MediaSession.Builder(requireContext(), player!!)
            .setId("ooustream_playback_ffvideo_${System.nanoTime()}")
            .build()

        attachPlayerListener()
        attachCueListener()

        val newAdapter = LeanbackPlayerAdapter(requireContext(), player!!, 1000)
        glue = OoustreamPlaybackGlue(requireContext(), newAdapter).apply {
            host = VideoSupportFragmentGlueHost(this@OoustreamPlaybackFragment)
            isControlsOverlayAutoHideEnabled = false
            contentType = viewModel.contentType
            title = viewModel.streamName
            customControlsManager = controlsManager
        }
        wireGlueCallbacks(glue!!)
        view?.post {
            try { glue?.host?.hideControlsOverlay(false) } catch (_: Exception) {}
            forceHideLeanbackPlaybackDock()
        }

        player!!.setMediaItem(MediaItem.fromUri(currentUrl))
        player!!.prepare()
        if (currentPosition > 0) player!!.seekTo(currentPosition)
        rebuildInProgress = false   // position restored — checkpoints may resume
        player!!.play()

        streamDiagnosticLogger.logAppEvent("PLAYER_REBUILD",
            "decoder=ffmpeg_video, position=${currentPosition}ms, channel=${healthMonitor?.channelName ?: "unknown"}")
    }

    /**
     * Rebuild the ExoPlayer with EXTENSION_RENDERER_MODE_PREFER so FFmpeg handles audio decoding
     * instead of the hardware MediaCodec. Used when hardware falsely claims AC3/EAC3 support
     * but crashes at runtime (e.g. mt8695 Fire TV Sticks).
     */
    private fun rebuildPlayerWithFfmpegPreferred() {
        val p = player ?: return
        dropPendingNextEpisode()   // rebuild setMediaItems a single item — boundary falls back to legacy advance
        val currentPosition = p.currentPosition
        rebuildInProgress = true   // suppress progress saves until position is restored
        showBufferingOverlay(true, immediate = true)   // show art over the rebuild gap (silent — no toast)
        val currentUrl = viewModel.streamUrl

        // v3.7.6: detach old glue from host first — see SW rebuild for full reasoning.
        glue?.host = null

        // Stop and release current player
        p.stop()
        diagnosticListener?.let { p.removeListener(it); p.removeAnalyticsListener(it) }
        healthMonitor?.stop()
        mediaSession?.release()
        mediaSession = null
        safeReleasePlayer(p)

        // Rebuild with FFmpeg-preferred audio pipeline
        val ffmpegRenderersFactory = AudioPipelineFactory.createFfmpegPreferredRenderersFactory(requireContext())
        val dataSourceFactory = StreamingDataFactories.buildDataSourceFactory(okHttpClient)
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
            // DolbyVisionBaseLayer.wrap on REBUILD paths too — the rewrites are format-level
            // (DV P7→hevc, over-declared level normalization), orthogonal to which renderer decodes.
            // Leaving rebuilds unwrapped caused a real bug: the MTK 5.1-audio rebuild fired 3ms after
            // the main player selected a normalized 4K HEVC track and its unwrapped extractor brought
            // the bogus H156 level back → video track deselected → audio over black screen.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    dataSourceFactory,
                    DolbyVisionBaseLayer.wrap(requireContext(), StreamingDataFactories.buildExtractorsFactory())
                )
            )
            // Hold CPU+WiFi awake while playing/buffering — without this the radio can
            // power-save mid-stall and turn a recoverable dip into a long rebuffer.
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
            // v3.7.5: re-attach our custom controls — see SW rebuild for full explanation.
            customControlsManager = controlsManager
        }
        // v3.7.7: re-attach all the channel-switch / action-button / overlay-passthrough
        // callbacks that the original glue had. Without this, the customer can't change
        // channels, trigger the audio picker, sleep timer, etc. after the rebuild.
        wireGlueCallbacks(glue!!)
        // hideControlsOverlay must run AFTER the host's attach sequence completes,
        // otherwise the host's auto-show on attach overrides our hide and the default
        // Leanback transport overlay stays visible behind our custom PlayerControlsBar.
        view?.post {
            try { glue?.host?.hideControlsOverlay(false) } catch (_: Exception) {}
            forceHideLeanbackPlaybackDock()
        }

        // Restore playback from saved position
        player!!.setMediaItem(MediaItem.fromUri(currentUrl))
        player!!.prepare()
        if (currentPosition > 0) player!!.seekTo(currentPosition)
        rebuildInProgress = false   // position restored — checkpoints may resume
        player!!.play()

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

    private fun ensureBufferingOverlay() {
        if (bufferingOverlay != null) return
        val ctx = context ?: return
        val container = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Dark base so the gap between art-load and first-frame is a designed surface, not raw black.
            setBackgroundColor(0xFF0A0A0A.toInt())
        }
        val art = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val scrim = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x99000000.toInt())   // 60% scrim — keeps the spinner legible over art
        }
        val spinner = ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        }
        val label = TextView(ctx).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ).apply { topMargin = (72 * resources.displayMetrics.density).toInt() }
            visibility = View.GONE
        }
        container.addView(art)
        container.addView(scrim)
        container.addView(spinner)
        container.addView(label)
        container.visibility = View.GONE
        (view as? ViewGroup)?.addView(container)
        bufferingOverlay = container
        bufferingArt = art
        bufferingLabel = label
    }

    /** Load the current title/channel art into the buffering backdrop (already-cached from the row). */
    private fun loadBufferingArt() {
        val art = bufferingArt ?: return
        val url = viewModel.streamIcon
        if (url.isNotBlank()) {
            art.load(PosterUrlRewriter.rewriteBackdrop(url)) { crossfade(true) }
            art.visibility = View.VISIBLE
        } else {
            art.visibility = View.GONE   // just dark base + scrim + spinner
        }
    }

    /**
     * Hold the LAST rendered video frame behind the spinner during a stop/rebuild/zap/rebuffer, so
     * recovery looks like a freeze, not a cut to a poster (Netflix/YouTube behaviour). Best-effort:
     * PixelCopy is API 24+ and can fail; on any miss it falls back to the poster backdrop, so this can
     * never be worse than the v3.9.0 art backdrop. Only attempts once a real frame has rendered.
     */
    private fun captureLastFrame() {
        val art = bufferingArt
        if (art == null) return
        if (Build.VERSION.SDK_INT < 24 || !hasRenderedFirstFrame) { loadBufferingArt(); return }
        val sv = findSurfaceView(view as? ViewGroup)
        if (sv == null || sv.width <= 0 || sv.height <= 0 || !sv.holder.surface.isValid) {
            loadBufferingArt(); return
        }
        val bmp = try {
            Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) { loadBufferingArt(); return }
        try {
            PixelCopy.request(sv, bmp, { result ->
                val a = bufferingArt
                if (result == PixelCopy.SUCCESS && a != null && bufferingOverlay?.visibility == View.VISIBLE) {
                    a.setImageBitmap(bmp)
                    a.visibility = View.VISIBLE
                } else {
                    loadBufferingArt()   // capture missed — fall back to poster
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) { loadBufferingArt() }
    }

    private fun findSurfaceView(vg: ViewGroup?): android.view.SurfaceView? {
        vg ?: return null
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i)
            if (c is android.view.SurfaceView) return c
            if (c is ViewGroup) findSurfaceView(c)?.let { return it }
        }
        return null
    }

    /**
     * Buffering/loading affordance. [immediate]=true for the guaranteed-black moments (initial play, zap,
     * decoder rebuild) so art appears at once; default is debounced ~600ms so a sub-second mid-playback
     * rebuffer never flashes a spinner. Dismissed on onRenderedFirstFrame / STATE_READY (fail-safe: a 6s
     * timeout also clears it so it can never permanently cover the video).
     */
    private fun showBufferingOverlay(show: Boolean, immediate: Boolean = false, label: String? = null) {
        if (show) {
            bufferingShowJob?.cancel()
            if (immediate) {
                revealBufferingOverlay(label)
            } else {
                bufferingShowJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(BUFFER_SPINNER_DEBOUNCE_MS)
                    revealBufferingOverlay(label)
                }
            }
        } else {
            bufferingShowJob?.cancel()
            bufferingShowJob = null
            bufferingOverlay?.let { ov ->
                if (ov.visibility == View.VISIBLE) {
                    ov.animate().alpha(0f).setDuration(200).withEndAction {
                        ov.visibility = View.GONE
                        ov.alpha = 1f
                    }.start()
                }
            }
        }
    }

    private fun revealBufferingOverlay(label: String? = null) {
        ensureBufferingOverlay()
        captureLastFrame()   // hold the last frame if we have one, else the poster backdrop
        bufferingLabel?.apply {
            text = label ?: ""
            visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        bufferingOverlay?.let { ov ->
            ov.alpha = 1f
            ov.visibility = View.VISIBLE
        }
        // Keep the controls bar above the full-screen overlay if the user has it up.
        controlsBar?.bringToFront()
        // Fail-safe: never let the backdrop linger if first-frame/READY signals are missed.
        bufferingShowJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(6_000)
            bufferingOverlay?.takeIf { it.visibility == View.VISIBLE }?.let { showBufferingOverlay(false) }
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
                        .clearVideoSizeConstraints()   // restore full resolution on an explicit retry
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

    private fun friendlyErrorMessage(error: PlaybackException): String {
        // First, inspect the cause chain for concrete network/HTTP failures.
        // These are far more actionable than ExoPlayer's generic errorCode buckets.
        causeChainMessage(error)?.let { return it }

        // Fall back to errorCode-based bucketing for cases where the cause chain
        // doesn't clearly tell us what happened (decoder, codec, live window).
        return when (error.errorCode) {
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
    }

    /**
     * Walks error.cause to find concrete IO failures and returns a targeted message.
     * Returns null if nothing specific is detected — caller falls back to errorCode bucketing.
     */
    private fun causeChainMessage(error: Throwable): String? {
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < 6) {
            // HTTP response codes — most actionable signal we have.
            // Use reflection to avoid hard dependency on nested class shape.
            val cls = cause.javaClass.name
            if (cls == "androidx.media3.datasource.HttpDataSource\$InvalidResponseCodeException") {
                val code = runCatching {
                    cause!!.javaClass.getField("responseCode").getInt(cause)
                }.getOrNull()
                when (code) {
                    401, 403 -> return "Access denied. Your subscription may not include this title — contact your provider."
                    404 -> return "This title isn't available on the server. Contact your provider."
                    408 -> return "Request timed out. Check your connection and try again."
                    409, 429 -> return "Too many connections on your account. Close Ooustream on your other devices and try again."
                    // Xtream panels return 551 for "stream unavailable / connection limit reached" —
                    // give actionable guidance instead of the generic 5xx "server is having issues".
                    551 -> return "This title is unavailable right now. Your account may have hit its connection limit — close Ooustream on your other devices, then try again."
                    in 500..599 -> return "The server is having issues. Try again in a minute."
                    else -> if (code != null) return "Server returned error $code. Try again or contact your provider."
                }
            }

            // TLS / certificate problems — usually wrong device clock.
            if (cause is SSLException) {
                return "Secure connection failed. Check your device's date and time, then try again."
            }

            // DNS failure — couldn't resolve the hostname.
            if (cause is UnknownHostException) {
                return "Can't reach the server. Check your WiFi or DNS settings."
            }

            // TCP refused / unreachable.
            if (cause is ConnectException) {
                return "Couldn't connect to the server. It may be offline — try again in a moment."
            }

            // Socket read/connect timeout.
            if (cause is SocketTimeoutException) {
                return "Server is too slow to respond. Check your connection or try a different stream."
            }

            cause = cause.cause
            depth++
        }
        return null
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
                val builder = AlertDialog.Builder(act)
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
                // VOD/Series escape hatch: some providers deliver a title in a container/transport
                // ExoPlayer can't open (e.g. M2TS via a single-connection live-redirect — plays in
                // FFmpeg-based players like VLC/MX). Offer to hand off if such a player is installed.
                val externalPlayers = ExternalPlayerLauncher.getAvailablePlayers(act)
                    .filter { it != ExternalPlayerLauncher.Player.SYSTEM }
                if (viewModel.contentType != ContentType.LIVE && externalPlayers.isNotEmpty()) {
                    builder.setNeutralButton(R.string.open_in_external_player) { _, _ ->
                        val launched = ExternalPlayerLauncher.launch(
                            act, externalPlayers.first(), viewModel.streamUrl, viewModel.streamName
                        )
                        streamDiagnosticLogger.logAppEvent("EXTERNAL_PLAYER_HANDOFF",
                            "player=${externalPlayers.first().displayName}, launched=$launched")
                        if (!launched) Toast.makeText(act, "Couldn't open external player", Toast.LENGTH_SHORT).show()
                    }
                }
                builder.setCancelable(false).show()
                streamDiagnosticLogger.logAppEvent("ERROR_DIALOG_SHOWN", "msg=$message")
            } catch (e: Exception) {
                streamDiagnosticLogger.logAppEvent("ERROR_DIALOG_FAILED", "err=${e.message}")
                Toast.makeText(act, message, Toast.LENGTH_LONG).show()
                act.onBackPressedDispatcher.onBackPressed()
            }
        }
    }


    /** Skip to the next episode in a series. */
    /**
     * Reset per-stream audio/subtitle recovery state when moving to NEW content on the SAME player
     * (next-episode autoplay / binge). The LIVE [tuneToChannel] path already does this; the episode
     * paths used to skip it, leaking a Stage-2 disabled-audio fallback or a manual track override into
     * the next episode (multi-episode silence / wrong-language audio) and freezing the subtitle
     * self-test after episode 1.
     */
    private fun resetTrackStateForNewContent() {
        audioFallbackAttempted = false
        audioDisabledByFallback = false
        audioStallRecoveryStage = 0
        containerExtRetryAttempted = false
        userTrackOverrideActive = false
        mtkMultichannelFfmpegApplied = false
        upwardReprobeAttempted = false
        subtitleSelfTestRan = false
        // Drop the previous title's decoder identity + resolution so the 4K-HEVC watchdog
        // path reads the new content, not a stale value from the prior title.
        activeVideoDecoderName = ""
        cachedVideoWidth = 0
        cachedVideoHeight = 0
        oversizedVideoRefused = false
        player?.trackSelectionParameters = player?.trackSelectionParameters
            ?.buildUpon()
            ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)            // undo Stage-2 audio disable
            ?.clearOverridesOfType(C.TRACK_TYPE_AUDIO)                   // undo a manual episode-N track pick
            ?.clearVideoSizeConstraints()                               // new episode starts at full resolution
            ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlePreferences.subtitlesEnabled)
            ?.setPreferredTextLanguage(subtitlePreferences.preferredLanguage)
            ?.build() ?: return
    }

    /**
     * Accumulate a D-pad / FF-RW / on-screen-button seek. [deltaMs] is signed. The target is
     * clamped to [0, duration]; the actual player.seekTo happens once, ~300ms after the last tap.
     */
    private fun requestDpadSeek(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        val base = if (pendingSeekTargetMs >= 0) pendingSeekTargetMs else p.currentPosition
        var target = base + deltaMs
        if (target < 0) target = 0
        if (dur > 0 && target > dur) target = dur
        pendingSeekTargetMs = target
        // Optimistic UI — feedback overlay (cumulative delta + absolute landing time) and seekbar.
        seekFeedback?.showSeek(deltaMs, target)
        controlsBar?.updatePosition(target, dur)
        controlsManager?.resetAutoHideTimer()
        pendingSeekJob?.cancel()
        pendingSeekJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            commitPendingSeek()
        }
    }

    /** Commit the accumulated seek target (if any). Safe to call from onPause. */
    private fun commitPendingSeek() {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        val target = pendingSeekTargetMs
        pendingSeekTargetMs = -1L
        if (target < 0) return
        val p = player ?: return
        // Coarse scrubs land on the nearest keyframe — much faster than a frame-exact seek when
        // the target is outside the buffer. The three messages are processed in order on the
        // playback thread, so EXACT semantics are restored for later resume/chapter jumps.
        val coarse = kotlin.math.abs(target - p.currentPosition) > 30_000
        if (coarse) p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        p.seekTo(target)
        if (coarse) p.setSeekParameters(SeekParameters.DEFAULT)
    }

    /**
     * Set the player source for [url] at [positionMs], choosing the M2TS-stripping media source
     * for .m2ts/.mts VOD (ExoPlayer can't demux 192-byte Blu-ray packets natively) and the normal
     * MediaItem path for everything else. Centralizes initial-load and retry so both handle M2TS.
     */
    private fun setPlayerSource(url: String, positionMs: Long = 0L) {
        val p = player ?: return
        if (StreamingDataFactories.isM2tsUrl(url)) {
            streamDiagnosticLogger.logAppEvent("M2TS_SOURCE", "ext=${url.substringAfterLast('.')}, pos=${positionMs}ms")
            val dsf = StreamingDataFactories.buildDataSourceFactory(okHttpClient)
            p.setMediaSource(StreamingDataFactories.buildM2tsMediaSource(url, dsf), positionMs)
        } else if (positionMs > 0) {
            p.setMediaItem(MediaItem.fromUri(url), positionMs)
        } else {
            p.setMediaItem(MediaItem.fromUri(url))
        }
    }

    /** MediaItem for a queued next episode. mediaId carries the episodeId so
     *  onMediaItemTransition can unambiguously recognize our queued item (initial
     *  items built via MediaItem.fromUri keep the default "" mediaId). */
    private fun buildNextMediaItem(next: NextEpisodeResult): MediaItem =
        MediaItem.Builder().setUri(next.url).setMediaId(next.episodeId).build()

    /**
     * Single entry point for "go to the next episode" (binge Watch Next button, countdown
     * auto-fire, DPAD_UP skip). If the next episode is pre-buffered on the playlist, advance
     * instantly via seekToNextMediaItem() — all bookkeeping then happens in
     * onMediaItemTransition (reason=SEEK). Otherwise fall back to the legacy
     * setMediaItem→prepare→play flow (identical to pre-v4.0.0 behavior).
     */
    private fun advanceToNextEpisode(source: String) {
        val p = player
        if (pendingNextEpisode != null && p != null && p.mediaItemCount > 1 &&
            p.currentMediaItemIndex < p.mediaItemCount - 1
        ) {
            streamDiagnosticLogger.logAppEvent("TRANSITION_REQUEST", "source=$source, mode=playlist")
            p.seekToNextMediaItem()
        } else {
            streamDiagnosticLogger.logAppEvent("TRANSITION_FALLBACK", "source=$source")
            legacyAdvanceToNextEpisode(source)
        }
    }

    /**
     * Bookkeeping for a gapless playlist advance into [next]. Runs exactly once per queued
     * episode, from onMediaItemTransition. The previous episode is finalized using the
     * pre-swap ViewModel fields; the identity swap is SYNCHRONOUS (main thread) so the 5s
     * checkpoint loop can never attribute the new item's position to the old streamId.
     * Deliberately no buffering overlay / captureLastFrame here — there is no gap to cover;
     * if the new item briefly rebuffers, STATE_BUFFERING already holds the last frame.
     */
    private fun handleGaplessEpisodeTransition(next: NextEpisodeResult, reason: Int) {
        pendingNextEpisode = null
        // 1. Finalize the PREVIOUS episode (ViewModel fields still hold it). player.duration
        //    is already the new item's — use the remembered duration of the old one.
        if (lastKnownDurationMs > 0) {
            viewModel.saveProgress(lastKnownDurationMs, lastKnownDurationMs, 1.0f)
        }
        viewModel.markCompleted()
        // 2. Swap identity — synchronous, before any suspend point.
        viewModel.streamUrl = next.url
        viewModel.streamId = next.episodeId
        viewModel.streamName = next.name
        viewModel.seasonNum = next.season
        viewModel.episodeNum = next.episodeNum
        // 3. Continue Watching row for the now-current episode (parity with the STATE_ENDED path).
        viewModel.queueUpNextRow(next)
        // 4. Per-episode state resets (superset of the legacy advance's resets).
        bingeShown = false
        lastSavedPositionMs = 0L
        lastKnownDurationMs = 0L
        retryCount = 0
        mkvVarintRecoveryAttempted = false
        bufferStormCount = 0
        bufferStormWindowStart = 0L
        ffmpegRebuildAttemptedForBufferStorm = false
        resetTrackStateForNewContent()
        // 5. UI / diagnostics identity swap. dismiss() also cancels a still-running countdown
        //    timer, so a natural-end transition can't be followed by a late onPlayNext.
        bingeOverlay?.dismiss()
        glue?.title = next.name
        controlsBar?.bindSeries(
            viewModel.streamName, viewModel.streamIcon,
            viewModel.seasonNum, viewModel.episodeNum
        )
        diagnosticListener?.channelName = next.name
        healthMonitor?.channelName = next.name
        viewModel.recordPlayStart()
        context?.let { Toast.makeText(it, next.name, Toast.LENGTH_SHORT).show() }
        streamDiagnosticLogger.logAppEvent(
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) "TRANSITION_AUTO" else "TRANSITION_SEEK",
            "to=${next.name}, s${next.season}e${next.episodeNum}"
        )
    }

    /** Legacy single-item advance — the universal fallback when nothing is pre-buffered. */
    private fun legacyAdvanceToNextEpisode(source: String) {
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
                lastSavedPositionMs = 0L
                lastKnownDurationMs = 0L
                retryCount = 0

                resetTrackStateForNewContent()
                // Hold the last frame of this episode over the load gap instead of a black cut.
                showBufferingOverlay(true, immediate = true)
                player?.setMediaItem(MediaItem.fromUri(next.url))
                player?.prepare()
                player?.play()
                glue?.title = next.name
                controlsBar?.bindSeries(
                    viewModel.streamName, viewModel.streamIcon,
                    viewModel.seasonNum, viewModel.episodeNum
                )
                diagnosticListener?.channelName = next.name
                healthMonitor?.channelName = next.name
                viewModel.recordPlayStart()

                Toast.makeText(requireContext(), next.name, Toast.LENGTH_SHORT).show()
            } else if (source == "binge_overlay") {
                seriesCompleteOverlay?.show(viewModel.streamName)
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
    // v3.7.11 — Netflix-style phone gesture suite:
    //   • single tap                    → toggle controls
    //   • double tap LEFT half          → seek -10s + ripple
    //   • double tap RIGHT half         → seek +10s + ripple
    //   • horizontal fling              → channel zap (LIVE) / coarse seek (VOD)
    //   • vertical drag LEFT half       → screen brightness
    //   • vertical drag RIGHT half      → media volume
    //   • long press + hold             → 2× playback speed (VOD/SERIES only)
    //   • pinch zoom                    → cycle aspect ratio (Fit/Fill/Stretch)
    //
    // Gesture order in the touch listener matters:
    //   1. ScaleGestureDetector first — it consumes pointer-down/up
    //      events and reports onScaleEnd. If pinching, swallow other gestures.
    //   2. GestureDetector second — handles tap, double-tap, fling, scroll.
    //   3. Raw ACTION_UP / ACTION_CANCEL — needed because GestureDetector does
    //      not signal long-press release. Without this, 2× speed gets stuck on.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures(rootView: View) {
        val ctx = requireContext()
        val isLive = { viewModel.contentType == ContentType.LIVE }

        // Track vertical-scroll mode so onScroll knows whether to keep adjusting
        // brightness/volume or treat the gesture as something else.
        var verticalScrollMode: VerticalScrollMode = VerticalScrollMode.NONE
        var pinchActive = false

        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                verticalScrollMode = VerticalScrollMode.NONE
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                controlsManager?.toggle()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return false
                // LIVE has no concept of seeking — fall back to old toggle behavior.
                if (isLive()) {
                    if (p.isPlaying) p.pause() else p.play()
                    controlsManager?.show()
                    return true
                }
                val isLeftHalf = e.x < rootView.width / 2f
                val deltaMs = if (isLeftHalf) -10_000L else 10_000L
                val target = (p.currentPosition + deltaMs).coerceIn(0L, p.duration.coerceAtLeast(0L))
                p.seekTo(target)
                doubleTapRipple?.showAt(e.x, e.y, (deltaMs / 1000).toInt())
                controlsBar?.updatePosition(p.currentPosition, p.duration)
                controlsManager?.show()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Long-press = 2× speed while held. Disabled for LIVE (can't scrub).
                if (isLive()) return
                val p = player ?: return
                speedHoldActive = true
                p.setPlaybackParameters(androidx.media3.common.PlaybackParameters(2f))
                speedBadge?.show()
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null || pinchActive || speedHoldActive) return false
                val totalDx = kotlin.math.abs(e2.x - e1.x)
                val totalDy = kotlin.math.abs(e2.y - e1.y)
                // Lock direction the first time we cross the deadband, to avoid jittery flips.
                if (verticalScrollMode == VerticalScrollMode.NONE) {
                    if (totalDy < 24f || totalDy < totalDx) return false
                    verticalScrollMode = if (e1.x < rootView.width / 2f)
                        VerticalScrollMode.BRIGHTNESS else VerticalScrollMode.VOLUME
                    if (verticalScrollMode == VerticalScrollMode.BRIGHTNESS) {
                        ensureSavedBrightness()
                    }
                }
                // Pixels of vertical motion → 0..1 delta. Use rootView.height for consistent feel.
                val verticalSpan = rootView.height.coerceAtLeast(1).toFloat()
                val delta = distanceY / verticalSpan // positive = up = increase
                when (verticalScrollMode) {
                    VerticalScrollMode.VOLUME -> adjustVolumeBy(delta)
                    VerticalScrollMode.BRIGHTNESS -> adjustBrightnessBy(delta)
                    VerticalScrollMode.NONE -> {}
                }
                return verticalScrollMode != VerticalScrollMode.NONE
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (verticalScrollMode != VerticalScrollMode.NONE) return false
                val startX = e1?.x ?: return false
                val deltaX = e2.x - startX
                if (kotlin.math.abs(deltaX) < 100 || kotlin.math.abs(velocityX) < 300) return false

                if (isLive()) {
                    val direction = if (deltaX > 0) -1 else 1
                    val newChannel = viewModel.switchChannel(direction)
                    if (newChannel != null) {
                        zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                        debouncedTune(newChannel)
                    }
                } else {
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
        }
        val gestureDetector = GestureDetector(ctx, gestureListener)
        // Disable long-press when LIVE (we use it for 2× speed which doesn't apply).
        // GestureDetector.setIsLongpressEnabled is dynamic — we re-check at touch time
        // by gating onLongPress on isLive(), which is simpler than toggling state here.
        gestureDetector.setIsLongpressEnabled(true)

        val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var accumulatedScale = 1f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchActive = true
                accumulatedScale = 1f
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                accumulatedScale *= detector.scaleFactor
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (accumulatedScale > 1.15f) cycleAspectRatio()
                else if (accumulatedScale < 0.85f) cycleAspectRatio() // single direction list — same call
                pinchActive = false
            }
        }
        val scaleDetector = ScaleGestureDetector(ctx, scaleListener)

        val touchListener = View.OnTouchListener { v, event ->
            // Pinch detection first — its onTouchEvent updates internal state without
            // consuming events that aren't part of a multi-touch pinch.
            scaleDetector.onTouchEvent(event)
            // Long-press release tracking (GestureDetector doesn't fire this itself).
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (speedHoldActive) {
                    speedHoldActive = false
                    player?.setPlaybackParameters(androidx.media3.common.PlaybackParameters(1f))
                    speedBadge?.dismiss()
                }
                if (verticalScrollMode != VerticalScrollMode.NONE) {
                    verticalScrollMode = VerticalScrollMode.NONE
                }
            }
            // If we're mid-pinch, swallow the rest so single-tap doesn't fire when fingers lift.
            if (pinchActive && event.actionMasked != MotionEvent.ACTION_UP) return@OnTouchListener true
            gestureDetector.onTouchEvent(event)
            true
        }

        rootView.setOnTouchListener(touchListener)

        // Leanback's root sometimes intercepts touches before the listener fires on
        // phones. Walk down and wire any video surface as well so taps on the actual
        // video frame still reach our gesture pipeline.
        fun attachToSurfaces(v: View) {
            if (v is android.view.SurfaceView || v is android.view.TextureView) {
                v.setOnTouchListener(touchListener)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) attachToSurfaces(v.getChildAt(i))
            }
        }
        attachToSurfaces(rootView)

        // The controls bar is a full-screen overlay; when it's visible it sits above the surface
        // and would swallow every gesture. Forward its empty-area touches into the SAME pipeline
        // so double-tap-seek / drag volume-brightness / fling-zap / long-press-speed keep working
        // while controls are shown (single tap still toggles controls via onSingleTapConfirmed).
        controlsBar?.onScrimTouch = { ev -> touchListener.onTouch(controlsBar, ev) }
    }

    private enum class VerticalScrollMode { NONE, VOLUME, BRIGHTNESS }

    private fun adjustVolumeBy(delta: Float) {
        val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
        // delta is fraction of screen height — full screen sweep = ±1.0.
        val newFrac = (current + delta).coerceIn(0f, 1f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (newFrac * maxVol).toInt(), 0)
        volumeBrightnessHud?.show(VolumeBrightnessOverlay.Mode.VOLUME, newFrac)
    }

    private fun ensureSavedBrightness() {
        if (savedScreenBrightness.isNaN()) {
            savedScreenBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        }
    }

    private fun adjustBrightnessBy(delta: Float) {
        val window = activity?.window ?: return
        val lp = window.attributes
        val current = if (lp.screenBrightness >= 0f) lp.screenBrightness else 0.5f
        val newFrac = (current + delta).coerceIn(0.05f, 1f) // floor at 5% so screen never goes black
        lp.screenBrightness = newFrac
        window.attributes = lp
        volumeBrightnessHud?.show(VolumeBrightnessOverlay.Mode.BRIGHTNESS, newFrac)
    }

    /** Switch playback to [channel] and update viewModel + glue state. */
    private fun tuneToChannel(channel: com.ooustream.iptv.data.model.LiveStream) {
        // Auto-close track picker on channel switch
        if (trackPickerOverlay?.isShowing == true) trackPickerOverlay?.dismiss()

        // Reset audio + video recovery state for new channel
        retryCount = 0
        audioFallbackAttempted = false
        audioDisabledByFallback = false
        audioStallRecoveryStage = 0
        mkvVarintRecoveryAttempted = false
        userTrackOverrideActive = false
        subtitleSelfTestRan = false
        bufferStormCount = 0
        bufferStormWindowStart = 0L
        ffmpegRebuildAttemptedForBufferStorm = false
        mtkMultichannelFfmpegApplied = false
        upwardReprobeAttempted = false
        // Re-arm the upfront oversized-video refusal, so zapping from a refused 4K channel to
        // another one still fails fast instead of falling through to the watchdog ladder. Also drop
        // the previous channel's cached resolution/mime — tuneToChannel doesn't call
        // resetTrackStateForNewContent(), and a stale value here would feed both this gate and the
        // watchdog the wrong channel's numbers until the new tracks arrive.
        oversizedVideoRefused = false
        cachedVideoWidth = 0
        cachedVideoHeight = 0
        cachedVideoMime = ""
        cachedVideoCodecs = ""
        // Force-restart watchdog so the new channel gets a fresh escalation ladder
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null

        // Re-enable audio in case it was disabled by Stage 2 fallback
        // Re-apply subtitle preference (enabled/disabled + preferred language)
        player?.trackSelectionParameters = player?.trackSelectionParameters
            ?.buildUpon()
            ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            ?.clearVideoSizeConstraints()   // each channel starts at full res — don't inherit a prior channel's watchdog cap
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

        // Channel change is a guaranteed black moment — show the channel art at once (no spinner flash).
        showBufferingOverlay(true, immediate = true)
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
        // Capture which bar button has focus so we can restore it on dismiss.
        focusedBeforeTrackPicker = view?.findFocus()
        // Temporarily enable TEXT track type so ExoPlayer exposes subtitle tracks
        // for enumeration. If user picks "Off", we re-disable it.
        val wasTextDisabled = p.trackSelectionParameters
            .disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val launch = { showTrackPickerSurface(p) }
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
                    launch()
                }
            }
            p.addListener(listener)
            // Fallback: show picker after 500ms if onTracksChanged doesn't fire
            view?.postDelayed({
                if (!pickerShown) {
                    pickerShown = true
                    p.removeListener(listener)
                    launch()
                }
            }, 500)
        } else {
            launch()
        }
    }

    /**
     * Picks the right track-picker surface for the device:
     *   • TV → existing right-edge slide-in [TrackPickerOverlay] (D-pad navigable)
     *   • Phone → bottom-sheet [PhoneTrackPickerSheet] (drag-to-dismiss, Material)
     */
    private fun showTrackPickerSurface(p: androidx.media3.common.Player) {
        if (DeviceUtils.isTV(requireContext())) {
            trackPickerOverlay?.show(p)
        } else {
            val sheet = PhoneTrackPickerSheet()
                .setPlayer(p)
                .setOnDismissed {
                    controlsManager?.resumeAutoHide()
                    focusedBeforeTrackPicker?.requestFocus()
                }
            sheet.show(parentFragmentManager, "track_picker_sheet")
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
            private var firstCueLogged = false
            override fun onCues(cueGroup: CueGroup) {
                sv.setCues(cueGroup.cues)
                // Log first cue delivery per session so future "subs never displayed"
                // reports can be triaged from the diagnostic log without a debug build.
                if (!firstCueLogged && cueGroup.cues.isNotEmpty()) {
                    firstCueLogged = true
                    streamDiagnosticLogger.logAppEvent("SUBTITLE_FIRST_CUE",
                        "n=${cueGroup.cues.size}, svVisible=${sv.isShown}, " +
                        "svW=${sv.width}, svH=${sv.height}")
                }
            }
        }
        attachCueListener()
    }

    /**
     * Runs once per play session (guarded via [subtitleSelfTestRan]) and logs the
     * structural health of the subtitle pipeline for whichever backend is active.
     * Catches regressions like v3.6.7's "enableSubtitles=false" or this session's
     * "onKey swallows OK before picker click" BEFORE a customer notices.
     *
     * Expected healthy shape:
     *   ExoPlayer: SubtitleView attached, cueListener non-null, TEXT track type not
     *              permanently disabled (subtitlesEnabled pref respected), at least
     *              one TEXT track group present if the content has subs.
     *   libVLC:    VLCVideoLayout attached, MediaPlayer non-null, spuTracks callable
     *              without throwing, and enableSubtitles was true at attachViews time
     *              (we can't re-check attachViews parameters retroactively, but the
     *              absence of "can't get Subtitles Surface" in logcat is the signal).
     *
     * Any failed invariant logs a SUBTITLE_PIPELINE_BROKEN event — customers can
     * Send Debug Log and the next session will surface the break.
     */
    private fun runSubtitlePipelineSelfTest() {
        if (subtitleSelfTestRan) return
        subtitleSelfTestRan = true

        // v3.7.0: single-engine pipeline — ExoPlayer's TextRenderer → SubtitleView.
        // The libVLC branch is gone; the FFmpeg subtitle decoders (PGS, etc.) are
        // wired through the same TextRenderer path when the extension is active.
        val p = player
        val sv = subtitleView
        val listener = cueListener
        val textGroups = p?.currentTracks?.groups?.count { it.type == C.TRACK_TYPE_TEXT } ?: 0
        val textDisabled = p?.trackSelectionParameters?.disabledTrackTypes
            ?.contains(C.TRACK_TYPE_TEXT) == true
        val broken = p == null || sv == null || listener == null ||
            sv.parent == null
        streamDiagnosticLogger.logAppEvent(
            if (broken) "SUBTITLE_PIPELINE_BROKEN" else "SUBTITLE_PIPELINE_OK",
            "backend=ExoPlayer, player=${p != null}, svAttached=${sv?.parent != null}, " +
            "cueListener=${listener != null}, textGroups=$textGroups, " +
            "textDisabled=$textDisabled, prefEnabled=${subtitlePreferences.subtitlesEnabled}"
        )
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
        val p = player ?: return
        val isCurrentlyDisabled = p.trackSelectionParameters
            .disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val newEnabled = isCurrentlyDisabled // toggling: was disabled → now enable

        // Only claim "On" if the stream actually carries a subtitle track to render — otherwise the
        // CC button used to flip to a lying "On" with nothing on screen (watch-audit P1). This is the
        // primary captions entry point (subtitles default off), so it's the first thing a user hits.
        if (newEnabled && p.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) {
            controlsBar?.updateCcState(false)
            Toast.makeText(requireContext(), "No subtitles available for this content", Toast.LENGTH_SHORT).show()
            if (controlsManager?.isVisible != true) controlsManager?.show()
            return
        }

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

    /**
     * Gated progress checkpoint (watch-audit P0 fix). Saves Continue Watching position ONLY from a
     * known-good playing state, so a transient ~0 currentPosition (read during a rebuild/buffer) can
     * never clobber a deep bookmark through the REPLACE upsert. Completion (95%/STATE_ENDED) is saved
     * separately and is intentionally NOT routed through here.
     */
    private fun checkpointProgress() {
        if (viewModel.contentType == ContentType.LIVE) return
        val p = player ?: return
        if (rebuildInProgress) return                       // mid decoder-rebuild → position is unreliable
        if (p.playbackState != Player.STATE_READY) return   // only checkpoint from a settled, ready player
        val pos = p.currentPosition
        val dur = p.duration
        if (dur <= 0 || pos <= 0) return
        // Refuse a glitchy collapse of a deep bookmark to ~0 (the P0 corruption signature). A genuine
        // user rewind lands well above this floor; scrubbing literally to 0:00 then exiting is rare and
        // recoverable, whereas losing an hour-deep position is not.
        if (pos < 3_000 && lastSavedPositionMs > 30_000) return
        val pct = pos.toFloat() / dur.toFloat()
        if (pct <= 0.05f) return
        lastSavedPositionMs = pos
        viewModel.saveProgress(pos, dur, pct)
    }

    override fun onPause() {
        super.onPause()
        // End live TV session for recommendation tracking
        if (viewModel.contentType == ContentType.LIVE) {
            watchSessionLogger.onPlayerExit()
        }
        // Land any accumulated D-pad seek first (seekTo masks position immediately, so the
        // checkpoint below records where the user actually scrubbed to).
        commitPendingSeek()
        // ExoPlayer progress save on exit (gated — see checkpointProgress)
        checkpointProgress()
        player?.pause()
    }

    // ─── Suppress Leanback default controls ────────────────────────────
    override fun showControlsOverlay(runAnimation: Boolean) {
        // No-op: suppress Leanback default controls — using custom PlayerControlsBar
        forceHideLeanbackPlaybackDock()
    }

    /**
     * Wire all glue callbacks (channel switch, audio/subtitle/external/sleep buttons,
     * seek feedback, modal-overlay key passthrough, etc.) onto the given glue instance.
     *
     * This used to live inline in the initial onViewCreated `.apply { }` block, but the
     * v3.7.3 / v3.7.4 player rebuilds construct a new OoustreamPlaybackGlue and were
     * silently dropping every one of these callbacks — most visibly, channel-up/down
     * stopped working after the automatic 5.1 audio FFmpeg rebuild because
     * `onChannelSwitch` was null on the new glue (customer Bigd66 report on v3.7.6).
     *
     * Centralizing here so every future rebuild path picks all callbacks up by simply
     * calling `wireGlueCallbacks(newGlue)`.
     */
    private fun wireGlueCallbacks(g: OoustreamPlaybackGlue) {
        g.onChannelSwitch = { direction ->
            // [Fix 2.4] Debounced channel switch — zap overlay updates instantly,
            // stream loads after 300ms
            val newChannel = viewModel.switchChannel(direction)
            if (newChannel != null) {
                zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                debouncedTune(newChannel)
            }
        }
        g.onZapConfirm = { zapOverlay?.dismiss() }
        g.isZapOverlayShowing = { zapOverlay?.isShowing == true }
        g.onAudioTrackClicked = { showTrackPicker() }
        g.onSubtitleTrackClicked = { showTrackPicker() }
        g.onExternalPlayerClicked = { showExternalPlayerDialog() }
        g.onSleepTimerClicked = { sleepTimerManager?.showTimerDialog() }
        g.onStatsToggle = { statsOverlay?.toggle() }
        g.onAudioOnlyToggled = toggleAudioOnly@{
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
        // v4.0.0: the glue no longer seeks directly — these callbacks own the seek via the
        // coalescer (one committed seekTo per burst of taps, optimistic seekbar/feedback UI).
        g.onSeekForward = { deltaMs -> requestDpadSeek(deltaMs) }
        g.onSeekBackward = { deltaMs -> requestDpadSeek(-deltaMs) }
        g.onNextEpisode = { advanceToNextEpisode("skip_button") }
        // v3.7.0: any modal overlay should make the glue pass keys through (was only
        // track picker before). Otherwise OK on binge "Watch Next" etc. just shows
        // controls instead of clicking the button.
        g.isModalOverlayShowing = {
            trackPickerOverlay?.isShowing == true
                || bingeOverlay?.isShowing == true
                || watchNextOverlay?.isShowing == true
                || seriesCompleteOverlay?.isShowing == true
        }
        g.onDismissTrackPicker = { trackPickerOverlay?.dismiss() }
        g.onCcToggle = { toggleClosedCaptions() }
        // Route the glue's "key fired but callback was null" warnings into the
        // customer-visible diagnostic file. Lets us spot future "channel switch
        // doesn't work after rebuild" -class bugs from a debug log without needing
        // to repro on a debug build.
        g.onDiagnosticEvent = { event, details ->
            streamDiagnosticLogger.logAppEvent(event, details)
        }
        // Positive marker so future debug logs prove the glue was wired after each
        // rebuild. If a customer report shows PLAYER_REBUILD without a matching
        // GLUE_CALLBACKS_WIRED line right after, a rebuild path skipped this helper.
        streamDiagnosticLogger.logAppEvent(
            "GLUE_CALLBACKS_WIRED",
            "glue=${System.identityHashCode(g)}, contentType=${g.contentType}"
        )
    }

    /**
     * Hide the Leanback `playback_controls_dock` view in the fragment hierarchy.
     *
     * The dock hosts the default transport row + secondary actions, and the SeekBar
     * inside it is where Leanback registers the glue's key listener. We can't set
     * visibility=GONE because that pulls the SeekBar out of the focus tree — OK presses
     * then have no focused target, so `OoustreamPlaybackGlue.onKey` never fires and
     * `customControlsManager?.show()` is never called (the "controls don't pop up"
     * customer report immediately after the visibility=GONE attempt).
     *
     * Setting alpha = 0 keeps the SeekBar focusable + receiving key events while making
     * the duplicate UI invisible. Leanback's own `hideControlsOverlay` only animates
     * alpha temporarily and gets re-asserted to 1f on every row update — so we pin
     * alpha to 0 directly here, called after every show attempt.
     */
    private fun forceHideLeanbackPlaybackDock() {
        view?.findViewById<View>(androidx.leanback.R.id.playback_controls_dock)?.alpha = 0f
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
        // v3.7.11: replaced Toast with centered HUD label (works on TV + phone).
        aspectHud?.show(scalingLabels[currentScalingMode])
    }

    // [Fix 1.1] Correct lifecycle order: clean up everything BEFORE super tears down view hierarchy
    override fun onDestroyView() {
        // Write back final channel index for LiveTvFragment to pick up on resume.
        // viewModel.streamId is the authoritative signal — it's whatever the player
        // is actually playing right now, even if PlayerViewModel.channels[idx] resolves
        // to something else after channel switches in fullscreen.
        if (viewModel.contentType == ContentType.LIVE) {
            val channels = viewModel.channels.value
            val idx = viewModel.currentChannelIndex.value
            ChannelListHolder.lastPlayedIndex = idx
            ChannelListHolder.lastPlayedChannel = channels.getOrNull(idx)
            ChannelListHolder.lastPlayedStreamId = viewModel.streamId.toIntOrNull() ?: -1
        }
        // Safety net: ensure screen can sleep after player exits
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Cancel async jobs
        retryJob?.cancel()
        channelSwitchJob?.cancel()
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        pendingSeekTargetMs = -1L
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
        // v3.7.11 phone HUD overlays
        doubleTapRipple = null
        volumeBrightnessHud?.dismiss()
        volumeBrightnessHud = null
        aspectHud?.dismiss()
        aspectHud = null
        speedBadge?.dismiss()
        speedBadge = null
        // Restore brightness override so app exits leave screen at system brightness.
        if (!savedScreenBrightness.isNaN()) {
            val w = activity?.window
            val attrs = w?.attributes
            if (attrs != null) {
                attrs.screenBrightness = savedScreenBrightness
                w.attributes = attrs
            }
            savedScreenBrightness = Float.NaN
        }
        trackPickerOverlay?.dismiss()
        trackPickerOverlay = null
        audioStatusOverlay?.dismiss()
        audioStatusOverlay = null
        bufferingOverlay = null
        bufferingArt = null
        bufferingLabel = null
        bufferingShowJob?.cancel()
        bufferingShowJob = null
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
        pendingNextEpisode = null
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
        // Below this much buffered-ahead media, "no new frames" is data STARVATION (the network can't
        // feed the decoder), NOT a decoder stall — so the watchdog must not hard-reset (a reset just
        // re-buffers from scratch and loops). Only a frame stall WITH a healthy buffer is a real
        // decoder fault. Set above the LIVE bufferForPlayback floor so a normally-refilling stream
        // isn't mistaken for a stall.
        private const val WATCHDOG_STARVATION_BUFFER_MS = 2_500L
        private const val MAX_WATCHDOG_RESETS = 4
        // 4K HEVC on a HW decoder that stalled: number of hard-reset recovery attempts (network-dip
        // recovery) before giving up honestly. We never swap 4K HEVC to a software decoder — no SW
        // decoder in this device class does realtime 4K HEVC, and the swap re-buffers from scratch.
        private const val FOURK_HW_HARD_RESET_LIMIT = 3
        private const val SOFTWARE_FALLBACK_THRESHOLD = 1
        // Require 3 consecutive polls with new frames (6s) before resetting recovery ladder
        // Prevents MTK single-frame-then-black from resetting watchdogResetCount
        private const val SUSTAINED_PLAYBACK_POLLS = 3
        // Slideshow guard: <10fps (20 frames per 2s poll) for 5 consecutive polls (10s) on a
        // software decoder → unwatchable, give up with a friendly error instead of stranding.
        private const val SLIDESHOW_MIN_FRAMES_PER_POLL = 20
        private const val SLIDESHOW_POLLS_GIVE_UP = 5
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
