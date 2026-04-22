package com.ooustream.iptv.player

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.ooustream.iptv.R

/**
 * Overlay shown when the user finishes the last episode of a series.
 * Offers "Replay Series" and "Exit" options.
 */
class SeriesCompleteOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val seriesName: TextView
    private val replayBtn: TextView
    private val exitBtn: TextView

    var onReplay: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_series_complete, this, true)
        seriesName = findViewById(R.id.series_complete_name)
        replayBtn = findViewById(R.id.series_replay_btn)
        exitBtn = findViewById(R.id.series_exit_btn)
        visibility = GONE

        // Fix Leanback green: replace XML bg_card_rounded with programmatic drawable
        val dp = context.resources.displayMetrics.density
        val cardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xE6101018.toInt())
            cornerRadius = 14f * dp
            setStroke((1f * dp).toInt(), 0x1AFFFFFF)
        }
        // Center card (parent LinearLayout of seriesName)
        (seriesName.parent as? android.view.View)?.background = cardBg
        // Exit button also uses bg_card_rounded
        exitBtn.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xE6101018.toInt())
            cornerRadius = 10f * dp
            setStroke((1f * dp).toInt(), 0x1AFFFFFF)
        }

        replayBtn.setOnClickListener {
            visibility = GONE
            onReplay?.invoke()
        }
        exitBtn.setOnClickListener {
            visibility = GONE
            onExit?.invoke()
        }
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    fun show(name: String) {
        seriesName.text = name
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
        replayBtn.requestFocus()
    }

    fun dismiss() {
        animate().alpha(0f).setDuration(300).withEndAction {
            visibility = GONE
        }.start()
    }
}
