package com.ooustream.iptv.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import com.ooustream.iptv.R
import android.widget.Toast
import java.util.Locale

/**
 * Right-side slide-in panel showing available audio and subtitle tracks.
 * D-pad navigable: Up/Down moves between items, Enter selects, Back closes.
 * Video keeps playing behind the semi-transparent scrim.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackPickerOverlay(context: Context) : FrameLayout(context) {

    private val panel: ScrollView
    private val audioContainer: LinearLayout
    private val subtitleContainer: LinearLayout
    private val audioHeader: TextView
    private val subtitleHeader: TextView
    private val divider: View
    private val scrim: View

    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = visibility == VISIBLE

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_track_picker, this, true)

        scrim = findViewById(R.id.track_picker_scrim)
        panel = findViewById(R.id.track_picker_panel)
        audioContainer = findViewById(R.id.audio_tracks_container)
        subtitleContainer = findViewById(R.id.subtitle_tracks_container)
        audioHeader = findViewById(R.id.audio_section_header)
        subtitleHeader = findViewById(R.id.subtitle_section_header)
        divider = findViewById(R.id.track_picker_divider)

        visibility = GONE
    }

    fun show(player: Player) {
        audioContainer.removeAllViews()
        subtitleContainer.removeAllViews()

        val audioTracks = getTracksOfType(player, C.TRACK_TYPE_AUDIO)
        val subtitleTracks = getTracksOfType(player, C.TRACK_TYPE_TEXT)
        val subtitlesDisabled = player.trackSelectionParameters
            .disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        // Audio section — always show; fallback to "Default" if player reports no tracks
        audioHeader.visibility = VISIBLE
        audioContainer.visibility = VISIBLE
        if (audioTracks.isEmpty()) {
            addTrackItem(
                audioContainer,
                TrackInfo("Default", -1, -1, true, C.TRACK_TYPE_AUDIO),
                player
            )
        } else {
            audioTracks.forEach { track ->
                addTrackItem(audioContainer, track, player)
            }
        }

        // Subtitle section — always show with "Off" option
        subtitleHeader.visibility = VISIBLE
        subtitleContainer.visibility = VISIBLE
        divider.visibility = VISIBLE

        val offSelected = subtitlesDisabled || subtitleTracks.none { it.isSelected }
        addTrackItem(
            subtitleContainer,
            TrackInfo("Off", -1, -1, offSelected, C.TRACK_TYPE_TEXT),
            player
        )
        subtitleTracks.forEach { track ->
            addTrackItem(subtitleContainer, track, player)
        }

        // Show with slide animation — bring to front so we draw above controls bar
        bringToFront()
        visibility = VISIBLE
        panel.translationX = dp(320).toFloat()
        panel.animate()
            .translationX(0f)
            .setDuration(250)
            .setListener(null)
            .start()

        scrim.alpha = 0f
        scrim.animate()
            .alpha(1f)
            .setDuration(250)
            .start()

        // Focus on first track item
        post {
            val firstItem = if (audioContainer.childCount > 0) {
                audioContainer.getChildAt(0)
            } else if (subtitleContainer.childCount > 0) {
                subtitleContainer.getChildAt(0)
            } else null
            firstItem?.requestFocus()
        }
    }

    fun dismiss() {
        if (!isShowing) return

        panel.animate()
            .translationX(dp(320).toFloat())
            .setDuration(200)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                    panel.animate().setListener(null)
                    onDismissed?.invoke()
                }
            })
            .start()

        scrim.animate()
            .alpha(0f)
            .setDuration(200)
            .start()
    }

    /** Returns true if the player has multiple audio tracks or any subtitle tracks. */
    fun hasMultipleTracks(player: Player): Boolean {
        val audioTracks = getTracksOfType(player, C.TRACK_TYPE_AUDIO)
        val subtitleTracks = getTracksOfType(player, C.TRACK_TYPE_TEXT)
        return audioTracks.size > 1 || subtitleTracks.isNotEmpty()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isShowing) {
            // Back closes the picker
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    dismiss()
                }
                return true
            }
            // Prevent D-pad left from escaping the panel
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Track Parsing ────────────────────────────────────────────────

    private data class TrackInfo(
        val name: String,
        val groupIndex: Int,
        val trackIndex: Int,
        val isSelected: Boolean,
        val type: Int
    )

    private fun getTracksOfType(player: Player, trackType: Int): List<TrackInfo> {
        val result = mutableListOf<TrackInfo>()
        val currentTracks = player.currentTracks

        for ((groupIndex, group) in currentTracks.groups.withIndex()) {
            if (group.type != trackType) continue
            for (trackIndex in 0 until group.length) {
                // Hide audio tracks the device truly cannot play (no decoder, no passthrough)
                if (trackType == C.TRACK_TYPE_AUDIO && !group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val name = buildTrackName(format, trackType, result.size + 1)
                val isSelected = group.isTrackSelected(trackIndex)
                result.add(TrackInfo(name, groupIndex, trackIndex, isSelected, trackType))
            }
        }
        return result
    }

    private fun buildTrackName(format: Format, trackType: Int, index: Int): String {
        val baseName = when {
            !format.label.isNullOrBlank() -> format.label!!
            !format.language.isNullOrBlank() -> Locale(format.language!!).displayLanguage
            else -> {
                val typeLabel = if (trackType == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitle"
                "$typeLabel Track $index"
            }
        }
        if (trackType == C.TRACK_TYPE_AUDIO) {
            val parts = listOfNotNull(
                formatCodecLabel(format.sampleMimeType),
                formatChannelLabel(format.channelCount)
            )
            if (parts.isNotEmpty()) return "$baseName (${parts.joinToString(" ")})"
        }
        return baseName
    }

    private fun formatCodecLabel(mime: String?): String? = when (mime) {
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "E-AC3"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/opus" -> "Opus"
        else -> null
    }

    private fun formatChannelLabel(channelCount: Int): String? = when {
        channelCount <= 0 -> null
        channelCount == 1 -> "Mono"
        channelCount == 2 -> "Stereo"
        channelCount == 6 -> "5.1"
        channelCount == 8 -> "7.1"
        else -> "${channelCount}ch"
    }

    // ─── Track Selection ──────────────────────────────────────────────

    private fun applyTrackSelection(player: Player, track: TrackInfo) {
        if (track.groupIndex == -1) {
            // "Off" — disable subtitle track type
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(track.type, true)
                .build()
        } else {
            // Guard against stale group indices (track groups can shift during rebuffer)
            val groups = player.currentTracks.groups
            if (track.groupIndex >= groups.size) return
            val group = groups[track.groupIndex]
            if (track.trackIndex >= group.length) return

            val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(track.type, false)
                .setOverrideForType(override)
                .build()
        }
    }

    // ─── UI Building ──────────────────────────────────────────────────

    private fun addTrackItem(container: LinearLayout, track: TrackInfo, player: Player) {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setPadding(dp(24), dp(4), dp(24), dp(4))
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(context, R.drawable.bg_track_item_states)

            // Radio indicator
            val radio = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginEnd = dp(12)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(
                    if (track.isSelected) R.drawable.ic_radio_selected
                    else R.drawable.ic_radio_unselected
                )
            }
            addView(radio)

            // Track name
            val nameText = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                text = track.name
                textSize = 15f
                setTextColor(if (track.isSelected) 0xFFFFC107.toInt() else Color.WHITE)
                if (track.isSelected) setTypeface(null, Typeface.BOLD)
                maxLines = 1
            }
            addView(nameText)

            setOnClickListener {
                try {
                    applyTrackSelection(player, track)
                } catch (e: Exception) {
                    Toast.makeText(context, "Unable to switch track", Toast.LENGTH_SHORT).show()
                }
                dismiss()
            }

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    nameText.setTextColor(0xFFFFC107.toInt())
                } else {
                    nameText.setTextColor(
                        if (track.isSelected) 0xFFFFC107.toInt() else Color.WHITE
                    )
                }
            }
        }
        container.addView(item)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
