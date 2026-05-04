package com.ooustream.iptv.player

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * v3.7.11 — phone-native track picker. Replaces the right-edge slide-in
 * [TrackPickerOverlay] when running on phone. Same audio + subtitle selection
 * logic (delegated to [TrackPickerOverlay.Companion.applyTrack]), rendered as
 * a Material bottom sheet so the user gets:
 *
 *   - Drag-to-dismiss handle
 *   - Tap-outside-to-dismiss scrim (free from BottomSheetDialog)
 *   - 70% screen height — doesn't cover the video
 *   - Standard mobile affordance — what users expect from Netflix / YouTube
 *
 * On TV the existing slide-in panel is still used. Branching happens in
 * [OoustreamPlaybackFragment.showTrackPicker].
 *
 * Caller passes a [Player] reference via [setPlayer] before show. We register
 * a Player.Listener for onTracksChanged so late-arriving subtitle metadata
 * causes the sheet to refresh in place.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PhoneTrackPickerSheet : BottomSheetDialogFragment() {

    private var player: Player? = null
    private var onDismissed: (() -> Unit)? = null
    private var audioContainer: LinearLayout? = null
    private var subtitleContainer: LinearLayout? = null

    private val trackChangeListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val p = player ?: return
            if (tracks.groups.isNotEmpty()) refresh(p)
        }
    }

    fun setPlayer(p: Player): PhoneTrackPickerSheet {
        this.player = p
        return this
    }

    fun setOnDismissed(cb: () -> Unit): PhoneTrackPickerSheet {
        this.onDismissed = cb
        return this
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { d ->
            val sheet = (d as BottomSheetDialog).findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
                // Dark sheet background so it reads on top of video.
                it.background = GradientDrawable().apply {
                    cornerRadii = floatArrayOf(
                        16f * resources.displayMetrics.density, 16f * resources.displayMetrics.density,
                        16f * resources.displayMetrics.density, 16f * resources.displayMetrics.density,
                        0f, 0f, 0f, 0f
                    )
                    setColor(0xFF111316.toInt())
                }
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        val root = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (24 * density).toInt()
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // Drag handle pill at top — Material bottom sheet idiom.
        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 4f * density
                setColor(0x55FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                (40 * density).toInt(), (4 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * density).toInt()
            }
        }
        column.addView(handle)

        val audioHeader = sectionHeader(ctx, "Audio", density)
        column.addView(audioHeader)
        val audioBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * density).toInt() }
        }
        column.addView(audioBox)
        audioContainer = audioBox

        val subtitleHeader = sectionHeader(ctx, "Subtitles", density)
        column.addView(subtitleHeader)
        val subtitleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        column.addView(subtitleBox)
        subtitleContainer = subtitleBox

        root.addView(column)
        return root
    }

    override fun onStart() {
        super.onStart()
        val p = player
        if (p == null) {
            dismiss()
            return
        }
        p.addListener(trackChangeListener)
        refresh(p)
    }

    override fun onStop() {
        super.onStop()
        player?.removeListener(trackChangeListener)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissed?.invoke()
    }

    private fun refresh(p: Player) {
        val (audioTracks, subtitleTracks) = TrackPickerOverlay.buildTrackList(p)
        audioContainer?.removeAllViews()
        subtitleContainer?.removeAllViews()
        audioTracks.forEach { audioContainer?.addView(buildRow(it)) }
        subtitleTracks.forEach { subtitleContainer?.addView(buildRow(it)) }
    }

    private fun sectionHeader(ctx: android.content.Context, label: String, density: Float): TextView {
        return TextView(ctx).apply {
            text = label.uppercase()
            setTextColor(0xCCFFC107.toInt()) // brand gold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
                topMargin = (8 * density).toInt()
            }
        }
    }

    private fun buildRow(track: TrackPickerOverlay.TrackInfo): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = ContextCompatRipple(ctx, 0xFF111316.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { minimumHeight = (52 * density).toInt() }
            setPadding(
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt()
            )
            setOnClickListener {
                player?.let { p ->
                    TrackPickerOverlay.applyTrack(p, track)
                    // Refresh selection state so user sees the new check
                    refresh(p)
                }
            }
        }
        // Selection indicator (filled gold dot on selected, outline on others).
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (track.isSelected) {
                    setColor(0xFFFFC107.toInt())
                } else {
                    setStroke((1.5f * density).toInt(), 0x66FFFFFF)
                    setColor(Color.TRANSPARENT)
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                (16 * density).toInt(), (16 * density).toInt()
            ).apply { rightMargin = (12 * density).toInt() }
        }
        val label = TextView(ctx).apply {
            text = track.name
            setTextColor(if (track.isSelected) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            if (track.isSelected) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(dot)
        row.addView(label)
        return row
    }

    /** Plain ripple on dark — kept inline so we don't add yet another resource file. */
    private fun ContextCompatRipple(ctx: android.content.Context, normalColor: Int): android.graphics.drawable.Drawable {
        val rippleColor = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
        return android.graphics.drawable.RippleDrawable(
            rippleColor,
            android.graphics.drawable.ColorDrawable(normalColor),
            null
        )
    }
}
