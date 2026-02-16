package com.ooustream.iptv.common

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.ooustream.iptv.R

/**
 * Bottom-sheet style overlay that shows content details (synopsis, rating, genre, etc.)
 * when the user long-presses a poster card. Slides up from bottom with a dimmed backdrop.
 *
 * Usage: add to fragment's root ViewGroup, then call [show] with content data.
 * Back key or [dismiss] hides it.
 */
class ContentInfoOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val panel: LinearLayout
    private val poster: ImageView
    private val titleText: TextView
    private val metaText: TextView
    private val technicalText: TextView
    private val plotText: TextView
    private val playBtn: TextView
    private val favoriteBtn: TextView
    private val trailerBtn: TextView
    private val backdropDim: View

    var onPlay: (() -> Unit)? = null
    var onFavorite: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_content_info, this, true)
        panel = findViewById(R.id.info_panel)
        poster = findViewById(R.id.info_poster)
        titleText = findViewById(R.id.info_title)
        metaText = findViewById(R.id.info_meta)
        technicalText = findViewById(R.id.info_technical)
        plotText = findViewById(R.id.info_plot)
        playBtn = findViewById(R.id.info_play_btn)
        favoriteBtn = findViewById(R.id.info_favorite_btn)
        trailerBtn = findViewById(R.id.info_trailer_btn)
        backdropDim = findViewById(R.id.info_backdrop_dim)
        visibility = GONE

        playBtn.setOnClickListener {
            dismiss()
            onPlay?.invoke()
        }
        favoriteBtn.setOnClickListener {
            onFavorite?.invoke()
        }
        backdropDim.setOnClickListener { dismiss() }

        // Focus highlighting — gold border + scale on focused button
        val focusListener = OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * resources.displayMetrics.density
                    setStroke((2f * resources.displayMetrics.density).toInt(), 0xFFFFC107.toInt())
                    setColor(0x33FFC107)
                }
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
            } else {
                v.background = if (v == playBtn) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6f * resources.displayMetrics.density
                        orientation = GradientDrawable.Orientation.LEFT_RIGHT
                        colors = intArrayOf(0xFF00B4D8.toInt(), 0xFF0077B6.toInt())
                    }
                } else {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8f * resources.displayMetrics.density
                        setColor(0xFF1A2332.toInt())
                    }
                }
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }
        playBtn.onFocusChangeListener = focusListener
        favoriteBtn.onFocusChangeListener = focusListener
        trailerBtn.onFocusChangeListener = focusListener
    }

    data class ContentData(
        val title: String,
        val imageUrl: String?,
        val rating: String? = null,
        val year: String? = null,
        val genre: String? = null,
        val duration: String? = null,
        val quality: String? = null,
        val plot: String? = null,
        val youtubeTrailer: String? = null
    )

    fun show(data: ContentData) {
        titleText.text = data.title

        // Poster
        poster.load(data.imageUrl) {
            crossfade(200)
            placeholder(R.drawable.bg_card_rounded)
            error(R.drawable.bg_card_rounded)
            transformations(RoundedCornersTransformation(12f))
        }

        // Metadata line: rating · year · genre
        val metaParts = mutableListOf<String>()
        if (!data.rating.isNullOrBlank() && data.rating != "0") {
            metaParts.add("\u2605 ${data.rating}")
        }
        if (!data.year.isNullOrBlank()) metaParts.add(data.year)
        if (!data.genre.isNullOrBlank()) metaParts.add(data.genre)
        if (metaParts.isNotEmpty()) {
            metaText.text = metaParts.joinToString("  \u00B7  ")
            metaText.visibility = VISIBLE
        } else {
            metaText.visibility = GONE
        }

        // Technical line: quality · duration
        val techParts = mutableListOf<String>()
        if (!data.quality.isNullOrBlank()) techParts.add(data.quality)
        if (!data.duration.isNullOrBlank()) techParts.add(data.duration)
        if (techParts.isNotEmpty()) {
            technicalText.text = techParts.joinToString("  \u00B7  ")
            technicalText.visibility = VISIBLE
        } else {
            technicalText.visibility = GONE
        }

        // Plot
        if (!data.plot.isNullOrBlank()) {
            plotText.text = data.plot
            plotText.visibility = VISIBLE
        } else {
            plotText.visibility = GONE
        }

        // Trailer button — only show when YouTube trailer ID/URL is available
        val trailerId = data.youtubeTrailer?.trim()
        if (!trailerId.isNullOrBlank()) {
            trailerBtn.visibility = VISIBLE
            trailerBtn.setOnClickListener {
                val url = if (trailerId.startsWith("http")) trailerId
                    else "https://www.youtube.com/watch?v=$trailerId"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        } else {
            trailerBtn.visibility = GONE
        }

        // Animate in
        visibility = VISIBLE
        backdropDim.alpha = 0f
        backdropDim.animate().alpha(1f).setDuration(200).start()
        panel.translationY = panel.height.toFloat().coerceAtLeast(400f)
        panel.animate()
            .translationY(0f)
            .setDuration(300)
            .start()

        playBtn.requestFocus()
    }

    fun dismiss() {
        panel.animate()
            .translationY(panel.height.toFloat())
            .setDuration(250)
            .start()
        backdropDim.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction { visibility = GONE }
            .start()
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    // ─── D-pad Focus Trapping ─────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isShowing) {
            // Back closes the overlay
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) dismiss()
                return true
            }
            // Block vertical D-pad to prevent focus escaping to grid behind
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return true
            }
            // Manual left/right focus movement between visible buttons
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.action == KeyEvent.ACTION_DOWN) moveFocusPrev()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) moveFocusNext()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun getVisibleButtons(): List<View> = buildList {
        if (playBtn.visibility == VISIBLE) add(playBtn)
        if (favoriteBtn.visibility == VISIBLE) add(favoriteBtn)
        if (trailerBtn.visibility == VISIBLE) add(trailerBtn)
    }

    private fun moveFocusPrev() {
        val buttons = getVisibleButtons()
        val idx = buttons.indexOfFirst { it.isFocused }
        if (idx > 0) buttons[idx - 1].requestFocus()
    }

    private fun moveFocusNext() {
        val buttons = getVisibleButtons()
        val idx = buttons.indexOfFirst { it.isFocused }
        if (idx >= 0 && idx < buttons.size - 1) buttons[idx + 1].requestFocus()
    }
}
