package com.ooustream.iptv.player

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import com.ooustream.iptv.R
import com.ooustream.iptv.common.ChannelDisplayHelper
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.EpgProgram
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.epg.ChannelNameParser
import com.ooustream.iptv.epg.EpgSource
import com.ooustream.iptv.epg.InferredEpg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cinematic bottom-anchored controls bar overlay for the player.
 * Adapts to content type: Live TV (logo + EPG), VOD (poster + seek), Series (poster + episode info).
 */
class PlayerControlsBar(context: Context) : FrameLayout(context) {

    // Views
    private val liveBadge: LinearLayout
    private val liveDot: View
    private val qualityBadge: TextView
    private val btnPlayPause: ImageView
    private val channelLogo: ImageView
    private val contentPoster: ImageView
    private val contentInitials: TextView
    private val infoTitle: TextView
    private val infoSubtitle: TextView
    private val seekBar: SeekBar
    private val infoTimeLeft: TextView
    private val infoTimeRight: TextView
    private val actionButtonsRow: LinearLayout

    // State
    private var liveDotAnimator: ObjectAnimator? = null
    private var currentEpg: List<EpgProgram> = emptyList()
    private var currentContentType: ContentType = ContentType.LIVE

    // Callbacks — set by fragment
    var onPlayPause: (() -> Unit)? = null
    var onPrevChannel: (() -> Unit)? = null
    var onNextChannel: (() -> Unit)? = null
    var onChannelList: (() -> Unit)? = null
    var onAspectRatio: (() -> Unit)? = null
    var onSeekBack: (() -> Unit)? = null
    var onSeekForward: (() -> Unit)? = null
    var onScrimTap: (() -> Unit)? = null
    var onExternalPlayer: (() -> Unit)? = null
    var onTracksClicked: (() -> Unit)? = null
    var onCcToggle: (() -> Unit)? = null
    var onDpadSeek: ((Long) -> Unit)? = null
    var onStatsToggle: (() -> Unit)? = null
    private var ccButton: LinearLayout? = null
    private var ccIcon: ImageView? = null
    private var ccLabel: TextView? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_player_controls, this, true)

        liveBadge = findViewById(R.id.live_badge)
        liveDot = findViewById(R.id.live_dot)
        qualityBadge = findViewById(R.id.quality_badge)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        channelLogo = findViewById(R.id.channel_logo)
        contentPoster = findViewById(R.id.content_poster)
        contentInitials = findViewById(R.id.content_initials)
        infoTitle = findViewById(R.id.info_title)
        infoSubtitle = findViewById(R.id.info_subtitle)
        seekBar = findViewById(R.id.controls_seek_bar)
        infoTimeLeft = findViewById(R.id.info_time_left)
        infoTimeRight = findViewById(R.id.info_time_right)
        actionButtonsRow = findViewById(R.id.action_buttons_row)

        btnPlayPause.setOnClickListener { onPlayPause?.invoke() }

        // Mobile: tap on the scrim/background area dismisses controls
        setOnClickListener { onScrimTap?.invoke() }

        // Start hidden — PlayerControlsManager controls visibility
        visibility = GONE
    }

    // ─── Live TV Binding ────────────────────────────────────────────────

    fun bindLive(channel: LiveStream, epg: List<EpgProgram>, channelIndex: Int, inferredEpg: InferredEpg? = null) {
        currentContentType = ContentType.LIVE
        currentEpg = epg

        // Show channel logo, hide poster
        contentPoster.visibility = GONE
        ChannelDisplayHelper.loadLogo(channelLogo, contentInitials, channel.streamIcon, channel.name)

        // Title = parsed channel name (without country/quality clutter)
        val parsed = ChannelNameParser.parseForDisplay(channel.name)
        infoTitle.text = parsed.name

        // EPG: find current and next program
        val now = System.currentTimeMillis() / 1000
        val currentProgram = epg.find { program ->
            val start = program.startTimestamp?.toLongOrNull() ?: return@find false
            val end = program.stopTimestamp?.toLongOrNull() ?: return@find false
            now in start..end
        }
        val nextProgram = if (currentProgram != null) {
            val currentEnd = currentProgram.stopTimestamp?.toLongOrNull() ?: 0
            epg.find { p ->
                val start = p.startTimestamp?.toLongOrNull() ?: return@find false
                start >= currentEnd
            }
        } else null

        // Subtitle: "Now: {program}" or inferred EPG
        if (currentProgram?.title != null) {
            infoSubtitle.text = "Now: ${currentProgram.title}"
            infoSubtitle.typeface = android.graphics.Typeface.DEFAULT
            infoSubtitle.setTextColor(0xCCFFFFFF.toInt())
        } else if (inferredEpg != null) {
            infoSubtitle.text = "Now: ${inferredEpg.title}"
            infoSubtitle.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC)
            infoSubtitle.setTextColor(
                if (inferredEpg.source == EpgSource.PATTERN_CACHE) 0xAA90CAF9.toInt()
                else 0x77FFFFFF
            )
        } else {
            infoSubtitle.text = "No program info"
            infoSubtitle.typeface = android.graphics.Typeface.DEFAULT
            infoSubtitle.setTextColor(0x77FFFFFF)
        }

        // Program progress bar (non-interactive)
        seekBar.isEnabled = false
        seekBar.isFocusable = false
        seekBar.max = 100
        if (currentProgram != null) {
            val start = currentProgram.startTimestamp?.toLongOrNull() ?: 0
            val end = currentProgram.stopTimestamp?.toLongOrNull() ?: 0
            val totalDuration = end - start
            if (totalDuration > 0) {
                val elapsed = now - start
                seekBar.progress = ((elapsed.toFloat() / totalDuration) * 100).toInt().coerceIn(0, 100)
            } else {
                seekBar.progress = 0
            }
        } else {
            seekBar.progress = 0
        }

        // Time info: "Next: {program}" on left, time on right
        if (nextProgram?.title != null) {
            val nextTime = formatEpochTime(nextProgram.startTimestamp?.toLongOrNull())
            infoTimeLeft.text = "Next: ${nextProgram.title}"
            infoTimeRight.text = nextTime ?: ""
        } else {
            infoTimeLeft.text = ""
            infoTimeRight.text = ""
        }

        // LIVE badge
        liveBadge.visibility = VISIBLE
        startLiveDotPulse()

        // Action buttons for Live TV
        setupLiveActionButtons()
        setupFocusNavigation(ContentType.LIVE)
    }

    // ─── VOD Binding ────────────────────────────────────────────────────

    fun bindVod(title: String, posterUrl: String?) {
        currentContentType = ContentType.VOD

        // Show poster, hide logo
        channelLogo.visibility = GONE
        contentInitials.visibility = GONE
        if (!posterUrl.isNullOrBlank()) {
            contentPoster.visibility = VISIBLE
            contentPoster.load(posterUrl) { crossfade(200) }
        } else {
            contentPoster.visibility = GONE
        }

        infoTitle.text = title
        infoSubtitle.text = "" // Could add genre/year if available

        // Seek bar: interactive
        seekBar.isEnabled = true
        seekBar.isFocusable = true
        seekBar.max = 1000
        seekBar.progress = 0

        infoTimeLeft.text = "0:00"
        infoTimeRight.text = ""

        // Hide LIVE badge
        liveBadge.visibility = GONE
        stopLiveDotPulse()

        setupVodActionButtons()
        setupFocusNavigation(ContentType.VOD)
    }

    // ─── Series Binding ─────────────────────────────────────────────────

    fun bindSeries(title: String, posterUrl: String?, seasonNum: Int, episodeNum: Int) {
        currentContentType = ContentType.SERIES

        // Show poster, hide logo
        channelLogo.visibility = GONE
        contentInitials.visibility = GONE
        if (!posterUrl.isNullOrBlank()) {
            contentPoster.visibility = VISIBLE
            contentPoster.load(posterUrl) { crossfade(200) }
        } else {
            contentPoster.visibility = GONE
        }

        infoTitle.text = title
        infoSubtitle.text = "S${seasonNum} E${episodeNum}"

        // Seek bar: interactive
        seekBar.isEnabled = true
        seekBar.isFocusable = true
        seekBar.max = 1000
        seekBar.progress = 0

        infoTimeLeft.text = "0:00"
        infoTimeRight.text = ""

        // Hide LIVE badge
        liveBadge.visibility = GONE
        stopLiveDotPulse()

        setupVodActionButtons()
        setupFocusNavigation(ContentType.SERIES)
    }

    // ─── Position Updates ───────────────────────────────────────────────

    fun updatePosition(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val progress = ((positionMs.toFloat() / durationMs) * 1000).toInt()
        seekBar.progress = progress.coerceIn(0, 1000)

        infoTimeLeft.text = formatDuration(positionMs)

        val remaining = durationMs - positionMs
        infoTimeRight.text = formatRemaining(remaining)
    }

    /** Returns true if EPG needs reload (current program ended). */
    fun updateLiveProgress(epg: List<EpgProgram>): Boolean {
        currentEpg = epg
        val now = System.currentTimeMillis() / 1000
        val currentProgram = epg.find { program ->
            val start = program.startTimestamp?.toLongOrNull() ?: return@find false
            val end = program.stopTimestamp?.toLongOrNull() ?: return@find false
            now in start..end
        }

        if (currentProgram != null) {
            val start = currentProgram.startTimestamp?.toLongOrNull() ?: 0
            val end = currentProgram.stopTimestamp?.toLongOrNull() ?: 0
            val totalDuration = end - start
            if (totalDuration > 0) {
                val elapsed = now - start
                seekBar.progress = ((elapsed.toFloat() / totalDuration) * 100).toInt().coerceIn(0, 100)
                // Check if program ended
                return elapsed > totalDuration
            }
        }
        return false
    }

    fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_arrow_24
        )
    }

    fun setQualityBadge(height: Int?) {
        if (height == null || height <= 0) {
            qualityBadge.visibility = GONE
            return
        }
        val label = when {
            height >= 2160 -> "4K"
            height >= 1080 -> "FHD"
            height >= 720 -> "HD"
            else -> "SD"
        }
        qualityBadge.text = label
        qualityBadge.visibility = VISIBLE
        when {
            height >= 2160 -> {
                qualityBadge.setBackgroundResource(R.drawable.bg_quality_badge_4k)
                qualityBadge.setTextColor(0xFF22C55E.toInt())
            }
            height >= 1080 -> {
                qualityBadge.setBackgroundResource(R.drawable.bg_quality_badge_fhd)
                qualityBadge.setTextColor(0xFFFFD700.toInt())
            }
            height >= 720 -> {
                qualityBadge.setBackgroundResource(R.drawable.bg_quality_badge_hd)
                qualityBadge.setTextColor(0x99FFD700.toInt())
            }
            else -> {
                qualityBadge.setBackgroundResource(R.drawable.bg_quality_badge_sd)
                qualityBadge.setTextColor(0x99FFFFFF.toInt())
            }
        }
    }

    fun requestFocusOnPlayPause() {
        btnPlayPause.requestFocus()
    }

    // ─── LIVE Dot Pulse Animation ───────────────────────────────────────

    private fun startLiveDotPulse() {
        stopLiveDotPulse()
        liveDotAnimator = ObjectAnimator.ofFloat(liveDot, "alpha", 1f, 0.3f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopLiveDotPulse() {
        liveDotAnimator?.cancel()
        liveDotAnimator = null
        liveDot.alpha = 1f
    }

    // ─── Action Buttons ─────────────────────────────────────────────────

    private fun setupLiveActionButtons() {
        actionButtonsRow.removeAllViews()
        addActionButton(R.drawable.ic_skip_previous_24, "Prev Ch") { onPrevChannel?.invoke() }
        addActionButton(R.drawable.ic_live_tv, "Channels") { onChannelList?.invoke() }
        addActionButton(R.drawable.ic_aspect_ratio_24, "Aspect") { onAspectRatio?.invoke() }
        addCcButton()
        addActionButton(R.drawable.ic_audio_track, "Audio") { onTracksClicked?.invoke() }
        addActionButton(R.drawable.ic_skip_next_24, "Next Ch") { onNextChannel?.invoke() }
        // Stats button — phone only (TV has MENU key)
        if (!DeviceUtils.isTV(context)) {
            addActionButton(R.drawable.ic_stream_stats, "Stats") { onStatsToggle?.invoke() }
        }
    }

    private fun setupVodActionButtons() {
        actionButtonsRow.removeAllViews()
        addActionButton(R.drawable.ic_replay_10_24, "-10s") { onSeekBack?.invoke() }
        addActionButton(R.drawable.ic_play_arrow_24, "Play") { onPlayPause?.invoke() }
        addActionButton(R.drawable.ic_forward_10_24, "+10s") { onSeekForward?.invoke() }
        addActionButton(R.drawable.ic_aspect_ratio_24, "Aspect") { onAspectRatio?.invoke() }
        addCcButton()
        addActionButton(R.drawable.ic_audio_track, "Audio") { onTracksClicked?.invoke() }
        addActionButton(R.drawable.ic_external_player, "External") { onExternalPlayer?.invoke() }
        // Stats button — phone only (TV has MENU key)
        if (!DeviceUtils.isTV(context)) {
            addActionButton(R.drawable.ic_stream_stats, "Stats") { onStatsToggle?.invoke() }
        }
    }

    private fun addCcButton() {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isFocusable = true
            isClickable = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = ContextCompat.getDrawable(context, R.drawable.bg_action_button_states)

            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_subtitles)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    gravity = Gravity.CENTER
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isFocusable = false
            }
            addView(icon)
            ccIcon = icon

            val text = TextView(context).apply {
                this.text = "CC"
                textSize = 11f
                setTextColor(Color.parseColor("#CCFFFFFF"))
                gravity = Gravity.CENTER
                maxLines = 1
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                    gravity = Gravity.CENTER
                }
            }
            addView(text)
            ccLabel = text

            setOnClickListener { onCcToggle?.invoke() }

            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    text.setTextColor(Color.WHITE)
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    // Restore color based on CC state
                    text.setTextColor(text.tag as? Int ?: Color.parseColor("#CCFFFFFF"))
                }
            }
        }
        ccButton = btn
        actionButtonsRow.addView(btn)
    }

    fun updateCcState(enabled: Boolean) {
        val goldColor = 0xFFFFC107.toInt()
        val defaultColor = Color.parseColor("#CCFFFFFF")
        if (enabled) {
            ccIcon?.setColorFilter(goldColor)
            ccLabel?.text = "CC On"
            ccLabel?.setTextColor(goldColor)
            ccLabel?.tag = goldColor
        } else {
            ccIcon?.clearColorFilter()
            ccLabel?.text = "CC"
            ccLabel?.setTextColor(defaultColor)
            ccLabel?.tag = defaultColor
        }
    }

    private fun addActionButton(iconRes: Int, label: String, onClick: () -> Unit) {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isFocusable = true
            isClickable = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = ContextCompat.getDrawable(context, R.drawable.bg_action_button_states)

            val icon = ImageView(context).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    gravity = Gravity.CENTER
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isFocusable = false
            }
            addView(icon)

            val text = TextView(context).apply {
                this.text = label
                textSize = 11f
                setTextColor(Color.parseColor("#CCFFFFFF"))
                gravity = Gravity.CENTER
                maxLines = 1
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                    gravity = Gravity.CENTER
                }
            }
            addView(text)

            setOnClickListener { onClick() }

            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    text.setTextColor(Color.WHITE)
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    text.setTextColor(Color.parseColor("#CCFFFFFF"))
                }
            }
        }
        actionButtonsRow.addView(btn)
    }

    // ─── Focus Navigation ───────────────────────────────────────────────

    private fun setupFocusNavigation(contentType: ContentType) {
        when (contentType) {
            ContentType.LIVE -> {
                // Seek bar not interactive — skip in focus chain
                seekBar.isFocusable = false
                btnPlayPause.nextFocusDownId = R.id.action_buttons_row
                btnPlayPause.nextFocusRightId = View.NO_ID
            }
            ContentType.VOD, ContentType.SERIES -> {
                seekBar.isFocusable = true
                btnPlayPause.nextFocusRightId = R.id.controls_seek_bar
                btnPlayPause.nextFocusDownId = R.id.action_buttons_row
                seekBar.nextFocusLeftId = R.id.btn_play_pause
                seekBar.nextFocusDownId = R.id.action_buttons_row
                seekBar.nextFocusUpId = R.id.btn_play_pause
            }
        }

        // Prevent focus from escaping the overlay
        btnPlayPause.nextFocusUpId = R.id.btn_play_pause

        // Action buttons: up goes to seek bar (VOD) or play/pause (Live)
        val upTarget = if (contentType == ContentType.LIVE) R.id.btn_play_pause else R.id.controls_seek_bar
        for (i in 0 until actionButtonsRow.childCount) {
            val child = actionButtonsRow.getChildAt(i)
            child.nextFocusUpId = upTarget
            // Prevent downward escape
            child.nextFocusDownId = child.id
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun getInitials(name: String): String {
        return name.split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
    }

    private fun formatEpochTime(epochSeconds: Long?): String? {
        if (epochSeconds == null || epochSeconds <= 0) return null
        return try {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epochSeconds * 1000))
        } catch (_: Exception) { null }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun formatRemaining(ms: Long): String {
        val totalMin = (ms / 60_000).toInt()
        return if (totalMin >= 60) {
            "${totalMin / 60}h ${totalMin % 60}m left"
        } else {
            "${totalMin}m left"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // ─── D-pad Seek Interception ─────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentContentType != ContentType.LIVE && !actionButtonsRow.hasFocus()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        onDpadSeek?.invoke(-seekDeltaForRepeat(event.repeatCount))
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        onDpadSeek?.invoke(seekDeltaForRepeat(event.repeatCount))
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekDeltaForRepeat(repeatCount: Int): Long = when {
        repeatCount > 10 -> 60_000L
        repeatCount > 3 -> 30_000L
        else -> 10_000L
    }

    fun cleanup() {
        stopLiveDotPulse()
    }
}
