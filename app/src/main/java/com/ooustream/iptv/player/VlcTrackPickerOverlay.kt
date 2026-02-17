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
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ooustream.iptv.R
import org.videolan.libvlc.MediaPlayer

/**
 * VLC-specific track picker overlay. Same visual design as TrackPickerOverlay
 * but reads track info from libVLC's MediaPlayer API instead of ExoPlayer.
 */
class VlcTrackPickerOverlay(context: Context) : FrameLayout(context) {

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

    fun show(mp: MediaPlayer) {
        audioContainer.removeAllViews()
        subtitleContainer.removeAllViews()

        // Audio tracks
        val audioTracks = mp.audioTracks ?: emptyArray()
        val currentAudioId = mp.audioTrack
        audioHeader.visibility = VISIBLE
        audioContainer.visibility = VISIBLE
        if (audioTracks.isEmpty()) {
            addItem(audioContainer, "Default", -1, true, mp, isAudio = true)
        } else {
            for ((i, track) in audioTracks.withIndex()) {
                val name = track.name?.takeIf { it.isNotBlank() } ?: "Audio Track ${i + 1}"
                addItem(audioContainer, name, track.id, track.id == currentAudioId, mp, isAudio = true)
            }
        }

        // Subtitle tracks
        val spuTracks = mp.spuTracks ?: emptyArray()
        val currentSpuId = mp.spuTrack
        subtitleHeader.visibility = VISIBLE
        subtitleContainer.visibility = VISIBLE
        divider.visibility = VISIBLE
        val offSelected = currentSpuId == -1 || spuTracks.isEmpty()
        addItem(subtitleContainer, "Off", -1, offSelected, mp, isAudio = false)
        for ((i, track) in spuTracks.withIndex()) {
            val name = track.name?.takeIf { it.isNotBlank() } ?: "Subtitle Track ${i + 1}"
            addItem(subtitleContainer, name, track.id, track.id == currentSpuId, mp, isAudio = false)
        }

        // Slide in
        bringToFront()
        visibility = VISIBLE
        panel.translationX = dp(320).toFloat()
        panel.animate().translationX(0f).setDuration(250).setListener(null).start()
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(250).start()

        post {
            val first = if (audioContainer.childCount > 0) audioContainer.getChildAt(0)
            else if (subtitleContainer.childCount > 0) subtitleContainer.getChildAt(0)
            else null
            first?.requestFocus()
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
            }).start()
        scrim.animate().alpha(0f).setDuration(200).start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isShowing) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) dismiss()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun addItem(
        container: LinearLayout,
        name: String,
        trackId: Int,
        selected: Boolean,
        mp: MediaPlayer,
        isAudio: Boolean
    ) {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
            setPadding(dp(24), dp(4), dp(24), dp(4))
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(context, R.drawable.bg_track_item_states)

            val radio = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(if (selected) R.drawable.ic_radio_selected else R.drawable.ic_radio_unselected)
            }
            addView(radio)

            val nameText = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = name
                textSize = 15f
                setTextColor(if (selected) 0xFFFFC107.toInt() else Color.WHITE)
                if (selected) setTypeface(null, Typeface.BOLD)
                maxLines = 1
            }
            addView(nameText)

            setOnClickListener {
                try {
                    if (isAudio) mp.audioTrack = trackId
                    else mp.spuTrack = trackId
                } catch (e: Exception) {
                    Toast.makeText(context, "Unable to switch track", Toast.LENGTH_SHORT).show()
                }
                dismiss()
            }

            setOnFocusChangeListener { _, hasFocus ->
                nameText.setTextColor(
                    if (hasFocus || selected) 0xFFFFC107.toInt() else Color.WHITE
                )
            }
        }
        container.addView(item)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
