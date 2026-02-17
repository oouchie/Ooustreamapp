package com.ooustream.iptv.player

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.ooustream.iptv.R
import com.ooustream.iptv.common.NetworkMonitor
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.epg.SmartEpgFiller
import com.ooustream.iptv.recommendation.WatchSessionLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import javax.inject.Inject

/**
 * VLC-based playback fragment — auto-fallback when ExoPlayer can't decode a codec (e.g. DTS).
 * Same overlay UI and controls as OoustreamPlaybackFragment, but uses libVLC + FFmpeg decoders.
 */
@AndroidEntryPoint
class VlcPlaybackFragment : Fragment() {

    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var epgCacheRepository: com.ooustream.iptv.data.repository.EpgCacheRepository
    @Inject lateinit var smartEpgFiller: SmartEpgFiller
    @Inject lateinit var watchSessionLogger: WatchSessionLogger

    private val viewModel: PlayerViewModel by viewModels()

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vlcSurface: SurfaceView? = null

    // Overlays (same as ExoPlayer fragment)
    private var zapOverlay: ChannelZapOverlay? = null
    private var bingeOverlay: BingeCountdownOverlay? = null
    private var channelBanner: ChannelBannerOverlay? = null
    private var seriesCompleteOverlay: SeriesCompleteOverlay? = null
    private var seekFeedback: SeekFeedbackOverlay? = null
    private var vlcTrackPicker: VlcTrackPickerOverlay? = null
    private var audioOnlyOverlay: AudioOnlyOverlay? = null
    private var watchNextOverlay: WatchNextOverlay? = null
    private var audioStatusOverlay: AudioStatusOverlay? = null

    // Custom controls bar
    private var controlsBar: PlayerControlsBar? = null
    private var controlsManager: PlayerControlsManager? = null
    private var currentEpg: List<com.ooustream.iptv.data.model.EpgProgram> = emptyList()

    // Playback state
    private var bufferingOverlay: View? = null
    private var retryCount = 0
    private var retryJob: Job? = null
    private var channelSwitchJob: Job? = null
    private var bingeShown = false
    private var resumePositionMs = 0L
    private var isCodecFallback = false
    private var surfaceReady = false

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
            resumePositionMs = it.getLong("resume_position_ms", 0L)
            isCodecFallback = it.getBoolean("is_codec_fallback", false)
        }
        if (viewModel.contentType == ContentType.LIVE) {
            val (channels, idx) = ChannelListHolder.consume()
            if (channels.isNotEmpty()) {
                viewModel.setChannels(channels, idx)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_vlc_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Init LibVLC with memory-conscious options
        val options = arrayListOf(
            "--network-caching=1500",
            "--file-caching=1500",
            "--live-caching=1500",
            "--rtsp-tcp",
            "--no-audio-time-stretch",
        )
        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.memoryClass <= 128) {
            options.add("--avcodec-skiploopfilter=2")
        }
        libVlc = LibVLC(requireContext(), options)
        mediaPlayer = MediaPlayer(libVlc!!)

        // Attach to SurfaceView with proper video scaling
        val surface = view.findViewById<SurfaceView>(R.id.vlc_surface)
        vlcSurface = surface
        val vlcVout = mediaPlayer!!.vlcVout
        vlcVout.setVideoView(surface)

        // Tell VLC the available display size so it can scale internally
        val dm = resources.displayMetrics
        vlcVout.setWindowSize(dm.widthPixels, dm.heightPixels)

        // Attach with video layout listener — VLC calls back with video dimensions
        vlcVout.attachViews(layoutListener)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                startPlayback()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })

        // VLC event listener — events fire on VLC thread, post to main
        mediaPlayer!!.setEventListener { event ->
            view.post { handleVlcEvent(event) }
        }

        setupOverlays(view)
        setupControlsBar(view)
        setupKeyHandling(view)

        // Position update coroutine (1s interval)
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                val mp = mediaPlayer ?: continue
                val bar = controlsBar ?: continue
                when (viewModel.contentType) {
                    ContentType.LIVE -> {
                        val needsReload = bar.updateLiveProgress(currentEpg)
                        if (needsReload) {
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
                    else -> {
                        val dur = mp.length
                        if (dur > 0) bar.updatePosition(mp.time, dur)
                    }
                }
                bar.updatePlayPauseIcon(mp.isPlaying)
            }
        }

        // Auto-save progress every 5s
        if (viewModel.contentType != ContentType.LIVE) {
            viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    delay(5_000)
                    val mp = mediaPlayer ?: continue
                    val pos = mp.time
                    val dur = mp.length
                    if (dur > 0) {
                        val pct = pos.toFloat() / dur.toFloat()
                        if (pct > 0.05f) viewModel.saveProgress(pos, dur, pct)
                    }
                }
            }
        }

        // Binge mode for series
        if (viewModel.contentType == ContentType.SERIES) {
            viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    delay(1_000)
                    val mp = mediaPlayer ?: continue
                    val dur = mp.length
                    val pos = mp.time
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

        // Network-aware recovery
        viewLifecycleOwner.lifecycleScope.launch {
            var previouslyConnected = true
            networkMonitor.state.collect { netState ->
                if (!netState.isConnected && previouslyConnected) {
                    previouslyConnected = false
                    showBufferingOverlay(true)
                } else if (netState.isConnected && !previouslyConnected) {
                    previouslyConnected = true
                    retryCount = 0
                    startPlayback()
                }
            }
        }

        // Resume position for VOD/Series (non-fallback path — fallback already has position)
        if (!isCodecFallback && viewModel.contentType != ContentType.LIVE && !viewModel.hasResumed) {
            viewModel.getResumePosition { position ->
                if (position > 0) {
                    resumePositionMs = position
                    viewModel.hasResumed = true
                }
            }
        }

        viewModel.recordPlayStart()

        if (viewModel.contentType == ContentType.LIVE) {
            val channels = viewModel.channels.value
            val idx = viewModel.currentChannelIndex.value
            channels.getOrNull(idx)?.let { channel ->
                watchSessionLogger.onChannelStarted(channel, null)
            }
        }

        // If surface is already ready (rare), start now
        if (surfaceReady) startPlayback()
    }

    private fun startPlayback() {
        val mp = mediaPlayer ?: return
        if (!surfaceReady) return

        val media = Media(libVlc!!, Uri.parse(viewModel.streamUrl))
        media.setHWDecoderEnabled(true, false) // HW first, SW fallback
        mp.media = media
        media.release()
        mp.scale = 0f // Best fit (auto-scale to surface)
        mp.aspectRatio = null // Use video's native aspect ratio
        mp.play()

        // Seek to resume position after VLC starts
        if (resumePositionMs > 0) {
            viewLifecycleOwner.lifecycleScope.launch {
                delay(500)
                mp.time = resumePositionMs
                resumePositionMs = 0
            }
        }
        showBufferingOverlay(true)
    }

    // ─── VLC Events ──────────────────────────────────────────────────

    private fun handleVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                showBufferingOverlay(false)
                retryCount = 0
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MediaPlayer.Event.Paused -> {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MediaPlayer.Event.Buffering -> {
                showBufferingOverlay(event.buffering < 100f)
            }
            MediaPlayer.Event.EndReached -> {
                showBufferingOverlay(false)
                handlePlaybackEnded()
            }
            MediaPlayer.Event.EncounteredError -> {
                showBufferingOverlay(false)
                if (retryCount < MAX_RETRIES) {
                    val delayMs = RETRY_DELAYS_MS.getOrElse(retryCount) { 15_000L }
                    retryCount++
                    retryJob?.cancel()
                    retryJob = viewLifecycleOwner.lifecycleScope.launch {
                        showBufferingOverlay(true)
                        delay(delayMs)
                        startPlayback()
                    }
                } else {
                    showErrorDialog("Playback error. VLC could not play this stream.")
                }
            }
            MediaPlayer.Event.Stopped -> {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun handlePlaybackEnded() {
        if (viewModel.contentType != ContentType.LIVE) {
            val mp = mediaPlayer ?: return
            val dur = mp.length
            if (dur > 0) viewModel.saveProgress(dur, dur, 1.0f)
            viewModel.markCompleted()

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

    // ─── Overlay Setup ───────────────────────────────────────────────

    private fun setupOverlays(view: View) {
        val root = view as ViewGroup
        val mp = ViewGroup.LayoutParams.MATCH_PARENT

        // Channel zap
        val zap = ChannelZapOverlay(requireContext())
        root.addView(zap, ViewGroup.LayoutParams(mp, mp))
        zap.onChannelSelected = { channel -> tuneToChannel(channel) }
        zapOverlay = zap

        // Channel banner (live only)
        if (viewModel.contentType == ContentType.LIVE) {
            val banner = ChannelBannerOverlay(requireContext())
            root.addView(banner, ViewGroup.LayoutParams(mp, mp))
            channelBanner = banner
            viewLifecycleOwner.lifecycleScope.launch {
                delay(500)
                showChannelBanner()
            }
        }

        // Binge + Series Complete (series only)
        if (viewModel.contentType == ContentType.SERIES) {
            val binge = BingeCountdownOverlay(requireContext())
            root.addView(binge, ViewGroup.LayoutParams(mp, mp))
            binge.onPlayNext = {
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.markCompleted()
                    val next = viewModel.resolveNextEpisode()
                    if (next != null) {
                        navigateToNext(next)
                    } else {
                        seriesCompleteOverlay?.show(viewModel.streamName)
                    }
                }
            }
            binge.onCancel = { /* Stay on current */ }
            bingeOverlay = binge

            val complete = SeriesCompleteOverlay(requireContext())
            root.addView(complete, ViewGroup.LayoutParams(mp, mp))
            complete.onReplay = {
                mediaPlayer?.time = 0
                mediaPlayer?.play()
            }
            complete.onExit = {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
            seriesCompleteOverlay = complete
        }

        // Audio-only overlay
        val audioOverlay = AudioOnlyOverlay(requireContext())
        root.addView(audioOverlay, ViewGroup.LayoutParams(mp, mp))
        audioOnlyOverlay = audioOverlay

        // Seek feedback
        val seekOv = SeekFeedbackOverlay(requireContext())
        root.addView(seekOv, ViewGroup.LayoutParams(mp, mp))
        seekFeedback = seekOv

        // VLC track picker
        val trackPicker = VlcTrackPickerOverlay(requireContext())
        root.addView(trackPicker, ViewGroup.LayoutParams(mp, mp))
        trackPicker.onDismissed = { controlsManager?.resumeAutoHide() }
        vlcTrackPicker = trackPicker

        // Audio status indicator
        val audioStatus = AudioStatusOverlay(requireContext())
        root.addView(audioStatus, ViewGroup.LayoutParams(mp, mp))
        audioStatusOverlay = audioStatus

        // Watch Next (VOD only)
        if (viewModel.contentType == ContentType.VOD) {
            val watchNext = WatchNextOverlay(requireContext())
            root.addView(watchNext, ViewGroup.LayoutParams(mp, mp))
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
    }

    // ─── Controls Bar Setup ──────────────────────────────────────────

    private fun setupControlsBar(view: View) {
        val root = view as ViewGroup
        controlsBar = PlayerControlsBar(requireContext()).also { bar ->
            root.addView(bar, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            controlsManager = PlayerControlsManager(bar, viewModel.contentType)
            controlsManager?.onVisibilityChanged = { visible ->
                if (visible) bar.requestFocusOnPlayPause()
                else view.requestFocus()
            }

            bar.onPlayPause = {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) mp.pause() else mp.play()
                    bar.updatePlayPauseIcon(mp.isPlaying)
                }
            }
            bar.onSeekBack = {
                mediaPlayer?.let { mp ->
                    val newPos = (mp.time - 10_000).coerceAtLeast(0)
                    mp.time = newPos
                    seekFeedback?.showSeek(-10_000)
                    bar.updatePosition(newPos, mp.length)
                }
            }
            bar.onSeekForward = {
                mediaPlayer?.let { mp ->
                    val newPos = mp.time + 10_000
                    mp.time = newPos
                    seekFeedback?.showSeek(10_000)
                    bar.updatePosition(newPos, mp.length)
                }
            }
            bar.onAspectRatio = { cycleAspectRatio() }
            bar.onTracksClicked = { showVlcTrackPicker() }
            bar.onExternalPlayer = { showExternalPlayerDialog() }
            bar.onDpadSeek = { deltaMs ->
                mediaPlayer?.let { mp ->
                    val newPos = (mp.time + deltaMs).coerceAtLeast(0)
                    mp.time = newPos
                    seekFeedback?.showSeek(deltaMs)
                    bar.updatePosition(newPos, mp.length)
                    controlsManager?.resetAutoHideTimer()
                }
            }

            // Live TV callbacks
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
    }

    // ─── Key Handling ────────────────────────────────────────────────

    private fun setupKeyHandling(view: View) {
        // Root view handles keys when no overlay has focus
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()

        view.setOnKeyListener { _, keyCode, event ->
            // Consume ACTION_UP for handled keys to prevent Leanback ghost events
            if (event.action == KeyEvent.ACTION_UP) {
                return@setOnKeyListener isHandledKey(keyCode)
            }
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    controlsManager?.toggle()
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    mediaPlayer?.let { mp ->
                        if (mp.isPlaying) mp.pause() else mp.play()
                        controlsBar?.updatePlayPauseIcon(mp.isPlaying)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (viewModel.contentType != ContentType.LIVE) {
                        mediaPlayer?.let { mp ->
                            val newPos = (mp.time - 10_000).coerceAtLeast(0)
                            mp.time = newPos
                            seekFeedback?.showSeek(-10_000)
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (viewModel.contentType != ContentType.LIVE) {
                        mediaPlayer?.let { mp ->
                            mp.time = mp.time + 10_000
                            seekFeedback?.showSeek(10_000)
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (viewModel.contentType == ContentType.LIVE) {
                        val newChannel = viewModel.switchChannel(-1)
                        if (newChannel != null) {
                            zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                            debouncedTune(newChannel)
                        }
                    } else {
                        controlsManager?.show()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (viewModel.contentType == ContentType.LIVE) {
                        val newChannel = viewModel.switchChannel(+1)
                        if (newChannel != null) {
                            zapOverlay?.show(viewModel.channels.value, viewModel.currentChannelIndex.value)
                            debouncedTune(newChannel)
                        }
                    } else {
                        controlsManager?.show()
                    }
                    true
                }
                KeyEvent.KEYCODE_MENU -> {
                    showVlcTrackPicker()
                    true
                }
                else -> false
            }
        }

        // Back key via Activity dispatcher (reliable regardless of focus)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (vlcTrackPicker?.isShowing == true) {
                        vlcTrackPicker?.dismiss()
                        return
                    }
                    if (controlsManager?.isVisible == true) {
                        controlsManager?.hide()
                        return
                    }
                    if (watchNextOverlay?.isShowing == true) {
                        watchNextOverlay?.dismiss()
                    }
                    // Save progress before exit
                    if (viewModel.contentType != ContentType.LIVE) {
                        mediaPlayer?.let { mp ->
                            val pos = mp.time
                            val dur = mp.length
                            if (dur > 0) {
                                val pct = pos.toFloat() / dur.toFloat()
                                if (pct > 0.05f) viewModel.saveProgress(pos, dur, pct)
                            }
                        }
                    }
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        )
    }

    private fun isHandledKey(keyCode: Int): Boolean = keyCode in intArrayOf(
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MENU
    )

    // ─── Video Surface Scaling ────────────────────────────────────────

    /** VLC calls this when video dimensions are known or change. */
    private val layoutListener = IVLCVout.OnNewVideoLayoutListener { _, width, height,
                                                                      visibleWidth, visibleHeight,
                                                                      sarNum, sarDen ->
        val surface = vlcSurface ?: return@OnNewVideoLayoutListener
        if (width == 0 || height == 0) return@OnNewVideoLayoutListener

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // Account for sample aspect ratio (non-square pixels)
        val videoW: Double = if (sarNum > 0 && sarDen > 0) {
            visibleWidth.toDouble() * sarNum / sarDen
        } else {
            visibleWidth.toDouble()
        }
        val videoH = visibleHeight.toDouble()

        val videoAspect = videoW / videoH
        val screenAspect = screenW.toDouble() / screenH

        val (finalW, finalH) = if (videoAspect > screenAspect) {
            screenW to (screenW / videoAspect).toInt()
        } else {
            (screenH * videoAspect).toInt() to screenH
        }

        surface.post {
            surface.layoutParams = FrameLayout.LayoutParams(finalW, finalH, Gravity.CENTER)
            surface.holder.setFixedSize(finalW, finalH)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private fun showVlcTrackPicker() {
        val mp = mediaPlayer ?: return
        controlsManager?.pauseAutoHide()
        vlcTrackPicker?.show(mp)
    }

    private fun showExternalPlayerDialog() {
        val ctx = context ?: return
        val players = ExternalPlayerLauncher.getAvailablePlayers(ctx)
        val names = players.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Open in External Player")
            .setItems(names) { _, which ->
                val selectedPlayer = players[which]
                mediaPlayer?.pause()
                val launched = ExternalPlayerLauncher.launch(
                    ctx, selectedPlayer, viewModel.streamUrl, viewModel.streamName
                )
                if (!launched) {
                    Toast.makeText(ctx, "Could not launch ${selectedPlayer.displayName}", Toast.LENGTH_SHORT).show()
                    mediaPlayer?.play()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var currentAspectMode = 0
    private val aspectLabels = arrayOf("Fit", "16:9", "4:3")

    private fun cycleAspectRatio() {
        currentAspectMode = (currentAspectMode + 1) % aspectLabels.size
        val mp = mediaPlayer ?: return
        when (currentAspectMode) {
            0 -> { mp.aspectRatio = null; mp.scale = 0f }
            1 -> { mp.aspectRatio = "16:9"; mp.scale = 0f }
            2 -> { mp.aspectRatio = "4:3"; mp.scale = 0f }
        }
        Toast.makeText(requireContext(), "Aspect: ${aspectLabels[currentAspectMode]}", Toast.LENGTH_SHORT).show()
    }

    private fun navigateToNext(next: NextEpisodeResult) {
        viewModel.streamUrl = next.url
        viewModel.streamId = next.episodeId
        viewModel.streamName = next.name
        viewModel.seasonNum = next.season
        viewModel.episodeNum = next.episodeNum
        bingeShown = false

        val media = Media(libVlc!!, Uri.parse(next.url))
        media.setHWDecoderEnabled(true, false)
        mediaPlayer?.media = media
        media.release()
        mediaPlayer?.play()

        controlsBar?.bindSeries(next.name, viewModel.streamIcon, next.season, next.episodeNum)
        Toast.makeText(requireContext(), next.name, Toast.LENGTH_SHORT).show()
    }

    private fun debouncedTune(channel: com.ooustream.iptv.data.model.LiveStream) {
        channelSwitchJob?.cancel()
        channelSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            tuneToChannel(channel)
        }
    }

    private fun tuneToChannel(channel: com.ooustream.iptv.data.model.LiveStream) {
        if (vlcTrackPicker?.isShowing == true) vlcTrackPicker?.dismiss()

        watchSessionLogger.endCurrentSession()
        watchSessionLogger.onChannelStarted(channel, null)

        val url = viewModel.buildLiveUrl(channel)
        viewModel.streamUrl = url
        viewModel.streamName = channel.name
        viewModel.streamId = channel.streamId.toString()

        val channels = viewModel.channels.value
        val newIdx = channels.indexOf(channel)
        if (newIdx >= 0) viewModel.setChannels(channels, newIdx)

        val media = Media(libVlc!!, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        mediaPlayer?.media = media
        media.release()
        mediaPlayer?.play()

        viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            showChannelBanner()
        }

        viewLifecycleOwner.lifecycleScope.launch {
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
            controlsBar?.bindLive(channel, epg, viewModel.currentChannelIndex.value, inferred)
        }
    }

    private fun showChannelBanner() {
        val channels = viewModel.channels.value
        val idx = viewModel.currentChannelIndex.value
        val channel = channels.getOrNull(idx) ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val epg = try { epgCacheRepository.getEpg(channel.streamId) } catch (_: Exception) { emptyList() }
            val now = System.currentTimeMillis() / 1000
            val currentProgram = epg.find { p ->
                val start = p.startTimestamp?.toLongOrNull() ?: return@find false
                val end = p.stopTimestamp?.toLongOrNull() ?: return@find false
                now in start..end
            }
            val inferredEpg = if (currentProgram?.title == null) {
                smartEpgFiller.getSmartEpg(null, channel.streamId, channel.name, null)
            } else {
                smartEpgFiller.learnPattern(channel.streamId, channel.name, currentProgram.title!!)
                null
            }
            channelBanner?.show(channel, idx, epg, inferredEpg)
        }
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

    private fun showErrorDialog(message: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.error_stream)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ ->
                retryCount = 0
                startPlayback()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
            .setCancelable(false)
            .show()
    }

    // ─── Lifecycle ───────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (viewModel.contentType == ContentType.LIVE) {
            mediaPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        if (viewModel.contentType == ContentType.LIVE) {
            watchSessionLogger.onPlayerExit()
        }
        mediaPlayer?.let { mp ->
            if (viewModel.contentType != ContentType.LIVE) {
                val pos = mp.time
                val dur = mp.length
                if (dur > 0) {
                    val pct = pos.toFloat() / dur.toFloat()
                    if (pct > 0.05f) viewModel.saveProgress(pos, dur, pct)
                }
            }
        }
        mediaPlayer?.pause()
    }

    override fun onDestroyView() {
        // Write back channel index for LiveTvFragment
        if (viewModel.contentType == ContentType.LIVE) {
            val channels = viewModel.channels.value
            val idx = viewModel.currentChannelIndex.value
            ChannelListHolder.lastPlayedIndex = idx
            ChannelListHolder.lastPlayedChannel = channels.getOrNull(idx)
        }
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        retryJob?.cancel()
        channelSwitchJob?.cancel()
        controlsManager?.destroy()
        controlsManager = null
        controlsBar?.cleanup()
        controlsBar = null
        zapOverlay?.dismiss()
        zapOverlay = null
        bingeOverlay?.dismiss()
        bingeOverlay = null
        channelBanner?.dismiss()
        channelBanner = null
        seriesCompleteOverlay?.dismiss()
        seriesCompleteOverlay = null
        seekFeedback?.dismiss()
        seekFeedback = null
        vlcTrackPicker?.dismiss()
        vlcTrackPicker = null
        audioOnlyOverlay?.dismiss()
        audioOnlyOverlay = null
        watchNextOverlay?.dismiss()
        watchNextOverlay = null
        audioStatusOverlay?.dismiss()
        audioStatusOverlay = null
        bufferingOverlay = null

        // Release VLC (reverse init order)
        mediaPlayer?.let { mp ->
            mp.setEventListener(null)
            mp.vlcVout.detachViews()
            mp.release()
        }
        mediaPlayer = null
        libVlc?.release()
        libVlc = null

        super.onDestroyView()
    }

    companion object {
        private const val MAX_RETRIES = 5
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 5_000, 8_000, 12_000)

        fun newInstance(
            streamUrl: String,
            contentType: ContentType,
            streamId: String,
            streamName: String,
            streamIcon: String = "",
            seriesId: Int = 0,
            seasonNum: Int = 0,
            episodeNum: Int = 0,
            resumePositionMs: Long = 0L,
            isCodecFallback: Boolean = false
        ): VlcPlaybackFragment {
            return VlcPlaybackFragment().apply {
                arguments = Bundle().apply {
                    putString("stream_url", streamUrl)
                    putString("content_type", contentType.name)
                    putString("stream_id", streamId)
                    putString("stream_name", streamName)
                    putString("stream_icon", streamIcon)
                    putInt("series_id", seriesId)
                    putInt("season_num", seasonNum)
                    putInt("episode_num", episodeNum)
                    putLong("resume_position_ms", resumePositionMs)
                    putBoolean("is_codec_fallback", isCodecFallback)
                }
            }
        }
    }
}
