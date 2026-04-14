package com.ooustream.iptv.multiview

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.ooustream.iptv.common.AudioLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Per-slot chop detector, auto-recovery engine, and watchdog for MultiView.
 *
 * Detects playback degradation via 3 signals:
 *   1. Dropped frame rate (via AnalyticsListener.onDroppedVideoFrames)
 *   2. Rendered frame stall (via DecoderCounters.renderedOutputBufferCount)
 *   3. Buffer health (bufferedPosition - currentPosition)
 *
 * Drives a 3-level recovery ladder:
 *   Soft reset → Hard reset → Nuclear reset → Signal lost
 *
 * Includes a brute-force watchdog timer as safety net.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MultiViewStallDetector(private val scope: CoroutineScope) {

    companion object {
        // Detection thresholds — background (non-audio) slots
        private const val DROPS_PER_SEC_STUTTER = 15
        private const val DROPS_PER_SEC_CHOPPING = 25
        private const val BUFFER_EMPTY_MS = 500L
        private const val BUFFER_CRITICAL_MS = 1_000L
        // Non-audio slots on mt8696 with 4 simultaneous AVC decoders routinely stall for 1-2s
        // under GPU contention. Raising from 1s → 4s eliminates false-positive HARD_RESETs
        // that flashed the recovery fade mask over otherwise-healthy channel slots.
        private const val FROZEN_THRESHOLD_MS = 4_000L

        // Detection thresholds — active (audio) slot: much more lenient
        // Recovery kills audio + shows fade mask — far worse UX than riding out a stutter.
        // Active slot ONLY recovers from true freezes (no frames rendered at all).
        private const val ACTIVE_FROZEN_THRESHOLD_MS = 8_000L

        // Recovery limits
        private const val MAX_SOFT_RESETS = 2
        private const val MAX_HARD_RESETS = 3
        private const val RECOVERY_COOLDOWN_MS = 10_000L
        private const val ACTIVE_RECOVERY_COOLDOWN_MS = 30_000L

        // Polling
        private const val POLL_INTERVAL_MS = 500L
        private const val DROP_WINDOW_MS = 1_000L

        // Watchdog (brute-force safety net)
        private const val WATCHDOG_INTERVAL_MS = 3_000L
        private const val WATCHDOG_FROZEN_THRESHOLD_MS = 5_000L
        private const val ACTIVE_WATCHDOG_FROZEN_THRESHOLD_MS = 15_000L

        // Signal lost auto-retry
        private const val AUTO_RETRY_INTERVAL_MS = 30_000L
        private const val KEEP_ALIVE_INTERVAL_MS = 60_000L

        // Stagger delay between multi-slot recoveries
        private const val MULTI_SLOT_STAGGER_MS = 500L
    }

    // Per-slot monitoring state
    private class SlotState {
        var player: ExoPlayer? = null
        var analyticsListener: AnalyticsListener? = null
        var playerListener: Player.Listener? = null
        var monitorJob: Job? = null
        var keepAliveJob: Job? = null
        var autoRetryJob: Job? = null

        // Signal 1: Dropped frames
        var dropsInWindow: Int = 0
        var windowStartMs: Long = 0L

        // Signal 2: Rendered frame stall
        var lastRenderedFrameCount: Int = -1
        var noNewFramesSinceMs: Long = 0L

        // Signal 3: Buffer health (tracked in poll loop)

        // Recovery state
        var currentHealth: PlaybackHealth = PlaybackHealth.SMOOTH
        var softResetCount: Int = 0
        var hardResetCount: Int = 0
        var nuclearResetCount: Int = 0
        var lastRecoveryMs: Long = 0L

        fun reset() {
            dropsInWindow = 0
            windowStartMs = SystemClock.elapsedRealtime()
            lastRenderedFrameCount = -1
            noNewFramesSinceMs = 0L
            currentHealth = PlaybackHealth.SMOOTH
            softResetCount = 0
            hardResetCount = 0
            nuclearResetCount = 0
            lastRecoveryMs = 0L
        }
    }

    private val slotStates = Array(4) { SlotState() }
    private var watchdogJob: Job? = null

    /** Which slot currently has audio — uses relaxed thresholds */
    var audioSlot: Int = 0

    private val _healthStates = MutableStateFlow(Array(4) { PlaybackHealth.SMOOTH })
    val healthStates: StateFlow<Array<PlaybackHealth>> = _healthStates

    /** Triggered when a recovery action should be executed by the Fragment */
    var onRecoveryAction: ((slotIndex: Int, action: RecoveryAction) -> Unit)? = null

    /** Triggered on any health state change (for quality tier escalation) */
    var onHealthChanged: ((slotIndex: Int, health: PlaybackHealth) -> Unit)? = null

    /** Triggered when first frame renders after recovery (for fade mask dismiss) */
    var onFirstFrameAfterRecovery: ((slotIndex: Int) -> Unit)? = null

    /**
     * Start monitoring a slot's player for chop/freeze.
     * Attaches AnalyticsListener for dropped frames and starts the polling coroutine.
     */
    fun startMonitoring(slotIndex: Int, player: ExoPlayer) {
        stopMonitoring(slotIndex)

        val state = slotStates[slotIndex]
        state.player = player
        state.reset()
        updateHealthFlow(slotIndex, PlaybackHealth.SMOOTH)

        // Signal 1: Dropped frame tracking via AnalyticsListener
        val analyticsListener = object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                state.dropsInWindow += droppedFrames
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                // Signal recovery complete — dismiss fade mask
                onFirstFrameAfterRecovery?.invoke(slotIndex)
            }
        }
        state.analyticsListener = analyticsListener
        player.addAnalyticsListener(analyticsListener)

        // Player state listener for buffering/error detection
        val playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        // If we were in FROZEN/CHOPPING and recovered naturally, reset counters
                        if (state.currentHealth == PlaybackHealth.FROZEN ||
                            state.currentHealth == PlaybackHealth.CHOPPING) {
                            state.softResetCount = 0
                            state.hardResetCount = 0
                            state.nuclearResetCount = 0
                            cancelAutoRetry(slotIndex)
                        }
                        // Start keep-alive for live streams
                        startKeepAlive(slotIndex, player)
                    }
                    Player.STATE_BUFFERING -> {
                        // Don't count initial buffering as frozen
                        state.noNewFramesSinceMs = 0L
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AudioLogger.log("MultiView slot $slotIndex: player error: ${error.errorCodeName}")
                setHealth(slotIndex, PlaybackHealth.FROZEN)
            }
        }
        state.playerListener = playerListener
        player.addListener(playerListener)

        // Start the per-slot health monitor coroutine
        state.monitorJob = scope.launch {
            state.windowStartMs = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                evaluateHealth(slotIndex)
            }
        }

        // Start watchdog if not already running
        startWatchdog()

        AudioLogger.log("MultiView slot $slotIndex: chop detector started")
    }

    /**
     * Stop monitoring a slot.
     */
    fun stopMonitoring(slotIndex: Int) {
        val state = slotStates[slotIndex]
        state.monitorJob?.cancel()
        state.monitorJob = null
        state.keepAliveJob?.cancel()
        state.keepAliveJob = null
        state.autoRetryJob?.cancel()
        state.autoRetryJob = null

        // Remove listeners from player
        state.player?.let { player ->
            state.analyticsListener?.let { player.removeAnalyticsListener(it) }
            state.playerListener?.let { player.removeListener(it) }
        }
        state.analyticsListener = null
        state.playerListener = null
        state.player = null
    }

    fun stopAll() {
        for (i in 0 until 4) stopMonitoring(i)
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // ── Health Evaluation ──────────────────────────────────────────────

    private fun evaluateHealth(slotIndex: Int) {
        val state = slotStates[slotIndex]
        val player = state.player ?: return

        // Skip if player isn't in a playable state
        if (player.playbackState != Player.STATE_READY || !player.playWhenReady) return

        val now = SystemClock.elapsedRealtime()

        // Signal 1: Dropped frame rate
        val windowDuration = (now - state.windowStartMs).coerceAtLeast(1)
        val dropsPerSec = if (windowDuration >= DROP_WINDOW_MS) {
            val rate = (state.dropsInWindow * 1000.0 / windowDuration).toInt()
            // Reset window
            state.dropsInWindow = 0
            state.windowStartMs = now
            rate
        } else {
            0 // Window too short, skip this check
        }

        // Signal 2: Rendered frame stall
        val counters = player.videoDecoderCounters
        val currentFrameCount = counters?.renderedOutputBufferCount ?: 0

        if (state.lastRenderedFrameCount < 0) {
            // First check — initialize baseline
            state.lastRenderedFrameCount = currentFrameCount
            state.noNewFramesSinceMs = 0L
        } else if (currentFrameCount == state.lastRenderedFrameCount) {
            // No new frames since last check
            if (state.noNewFramesSinceMs == 0L) {
                state.noNewFramesSinceMs = now
            }
        } else {
            // Frames are progressing — clear frozen state
            state.noNewFramesSinceMs = 0L
            state.lastRenderedFrameCount = currentFrameCount
        }

        val frozenDuration = if (state.noNewFramesSinceMs > 0L) {
            now - state.noNewFramesSinceMs
        } else {
            0L
        }

        // Signal 3: Buffer health
        val bufferMs = player.bufferedPosition - player.currentPosition

        // Active (audio) slot: only detect true freezes — recovery is far more disruptive
        // than riding out stutters/chop. Background slots keep aggressive detection.
        val isActive = slotIndex == audioSlot

        val health = if (isActive) {
            // Active slot: ONLY frozen detection (no frames for 8s+)
            // Stutter and chop are tolerable — recovery is not
            when {
                frozenDuration >= ACTIVE_FROZEN_THRESHOLD_MS -> PlaybackHealth.FROZEN
                else -> PlaybackHealth.SMOOTH
            }
        } else {
            // Background slots: full 3-signal evaluation
            when {
                frozenDuration >= FROZEN_THRESHOLD_MS -> PlaybackHealth.FROZEN
                dropsPerSec > DROPS_PER_SEC_CHOPPING -> PlaybackHealth.CHOPPING
                bufferMs in 0 until BUFFER_EMPTY_MS -> PlaybackHealth.CHOPPING
                dropsPerSec > DROPS_PER_SEC_STUTTER -> PlaybackHealth.SLIGHT_STUTTER
                bufferMs in 0 until BUFFER_CRITICAL_MS -> PlaybackHealth.SLIGHT_STUTTER
                else -> PlaybackHealth.SMOOTH
            }
        }

        if (health != state.currentHealth) {
            AudioLogger.log(
                "MultiView slot $slotIndex: health ${state.currentHealth} → $health " +
                    "(drops/s=$dropsPerSec, buf=${bufferMs}ms, frozen=${frozenDuration}ms)"
            )
        }

        setHealth(slotIndex, health)
    }

    private fun setHealth(slotIndex: Int, health: PlaybackHealth) {
        val state = slotStates[slotIndex]
        val previousHealth = state.currentHealth
        state.currentHealth = health
        updateHealthFlow(slotIndex, health)

        when {
            health == PlaybackHealth.SMOOTH && previousHealth != PlaybackHealth.SMOOTH -> {
                // Recovered — reset counters
                state.softResetCount = 0
                state.hardResetCount = 0
                state.nuclearResetCount = 0
                cancelAutoRetry(slotIndex)
            }
            health == PlaybackHealth.SLIGHT_STUTTER -> {
                triggerRecovery(slotIndex, health)
            }
            health == PlaybackHealth.CHOPPING || health == PlaybackHealth.FROZEN -> {
                triggerRecovery(slotIndex, health)
            }
        }
    }

    // ── Recovery Engine ──────────────────────────────────────────────

    private fun triggerRecovery(slotIndex: Int, health: PlaybackHealth) {
        val state = slotStates[slotIndex]
        val now = SystemClock.elapsedRealtime()
        val isActive = slotIndex == audioSlot

        // Active slot gets longer cooldown — recovery kills audio
        val cooldown = if (isActive) ACTIVE_RECOVERY_COOLDOWN_MS else RECOVERY_COOLDOWN_MS
        if (now - state.lastRecoveryMs < cooldown) return

        // Active slot: ONLY recover from FROZEN — anything less is tolerable
        if (isActive && health != PlaybackHealth.FROZEN) return

        val action = if (isActive) {
            // Active slot recovery ladder: soft → hard → nuclear → signal lost
            // Always start gentle to minimize audio disruption
            when {
                state.softResetCount < MAX_SOFT_RESETS -> {
                    state.softResetCount++
                    RecoveryAction.SOFT_RESET
                }
                state.hardResetCount < MAX_HARD_RESETS -> {
                    state.hardResetCount++
                    RecoveryAction.HARD_RESET
                }
                state.nuclearResetCount < 2 -> {
                    state.nuclearResetCount++
                    RecoveryAction.NUCLEAR_RESET
                }
                else -> {
                    setHealth(slotIndex, PlaybackHealth.DEAD)
                    startAutoRetry(slotIndex)
                    RecoveryAction.MARK_SIGNAL_LOST
                }
            }
        } else {
            // Background slot recovery ladder (unchanged — aggressive is fine)
            when {
                health == PlaybackHealth.SLIGHT_STUTTER && state.softResetCount < MAX_SOFT_RESETS -> {
                    state.softResetCount++
                    RecoveryAction.SOFT_RESET
                }
                health == PlaybackHealth.SLIGHT_STUTTER && state.hardResetCount < MAX_HARD_RESETS -> {
                    state.hardResetCount++
                    RecoveryAction.HARD_RESET
                }
                (health == PlaybackHealth.CHOPPING || health == PlaybackHealth.FROZEN)
                    && state.hardResetCount < MAX_HARD_RESETS -> {
                    state.hardResetCount++
                    RecoveryAction.HARD_RESET
                }
                state.nuclearResetCount < 2 -> {
                    state.nuclearResetCount++
                    RecoveryAction.NUCLEAR_RESET
                }
                else -> {
                    setHealth(slotIndex, PlaybackHealth.DEAD)
                    startAutoRetry(slotIndex)
                    RecoveryAction.MARK_SIGNAL_LOST
                }
            }
        }

        state.lastRecoveryMs = now

        AudioLogger.log(
            "MultiView slot $slotIndex: recovery $action " +
                "(soft=${state.softResetCount}/$MAX_SOFT_RESETS, " +
                "hard=${state.hardResetCount}/$MAX_HARD_RESETS, " +
                "nuclear=${state.nuclearResetCount}/2)"
        )

        // Stagger multi-slot recoveries: don't reset all 4 at once
        scope.launch {
            val pendingRecoveries = slotStates.count {
                it.currentHealth != PlaybackHealth.SMOOTH && it.currentHealth != PlaybackHealth.DEAD
            }
            if (pendingRecoveries > 1) {
                delay(slotIndex * MULTI_SLOT_STAGGER_MS)
            }
            onRecoveryAction?.invoke(slotIndex, action)
        }
    }

    // ── Watchdog Timer ──────────────────────────────────────────────

    /**
     * Brute-force safety net: checks every 3s if any PLAYING slot
     * has stopped rendering frames for 5+ seconds.
     * Independent from the per-slot evaluator — catches edge cases.
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return

        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()

                for (i in 0 until 4) {
                    val state = slotStates[i]
                    val player = state.player ?: continue

                    // Only check slots that think they're playing
                    if (player.playbackState != Player.STATE_READY || !player.playWhenReady) continue

                    // Skip slots already in DEAD state
                    if (state.currentHealth == PlaybackHealth.DEAD) continue

                    val counters = player.videoDecoderCounters ?: continue
                    val currentFrames = counters.renderedOutputBufferCount

                    if (state.noNewFramesSinceMs > 0L &&
                        currentFrames == state.lastRenderedFrameCount
                    ) {
                        val frozenDuration = now - state.noNewFramesSinceMs
                        val threshold = if (i == audioSlot) ACTIVE_WATCHDOG_FROZEN_THRESHOLD_MS
                            else WATCHDOG_FROZEN_THRESHOLD_MS
                        if (frozenDuration >= threshold) {
                            AudioLogger.log(
                                "MultiView WATCHDOG slot $i: frozen ${frozenDuration}ms, " +
                                    "forcing recovery"
                            )
                            // Force FROZEN state — this triggers the recovery engine
                            setHealth(i, PlaybackHealth.FROZEN)
                        }
                    }
                }
            }
        }
    }

    // ── Keep-Alive & Auto-Retry ──────────────────────────────────────

    /**
     * Keep-alive: periodically seek to live edge to prevent drift.
     */
    private fun startKeepAlive(slotIndex: Int, player: ExoPlayer) {
        slotStates[slotIndex].keepAliveJob?.cancel()
        slotStates[slotIndex].keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (player.playbackState == Player.STATE_READY && player.isCurrentMediaItemLive) {
                    player.seekToDefaultPosition()
                    AudioLogger.log("MultiView slot $slotIndex: keep-alive seek-to-live")
                }
            }
        }
    }

    /**
     * Auto-retry from signal lost: every 30s, reset counters and try hard reset.
     */
    private fun startAutoRetry(slotIndex: Int) {
        slotStates[slotIndex].autoRetryJob?.cancel()
        slotStates[slotIndex].autoRetryJob = scope.launch {
            while (isActive) {
                delay(AUTO_RETRY_INTERVAL_MS)
                AudioLogger.log("MultiView slot $slotIndex: auto-retry from signal lost")
                val state = slotStates[slotIndex]
                state.softResetCount = 0
                state.hardResetCount = 0
                state.nuclearResetCount = 0
                state.lastRecoveryMs = 0L
                updateHealthFlow(slotIndex, PlaybackHealth.CHOPPING) // Show recovering state
                onRecoveryAction?.invoke(slotIndex, RecoveryAction.HARD_RESET)
            }
        }
    }

    private fun cancelAutoRetry(slotIndex: Int) {
        slotStates[slotIndex].autoRetryJob?.cancel()
        slotStates[slotIndex].autoRetryJob = null
    }

    // ── Health State Flow ──────────────────────────────────────────────

    private fun updateHealthFlow(slotIndex: Int, health: PlaybackHealth) {
        val current = _healthStates.value.copyOf()
        current[slotIndex] = health
        _healthStates.value = current
        onHealthChanged?.invoke(slotIndex, health)
    }
}
