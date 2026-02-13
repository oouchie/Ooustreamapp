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

        replayBtn.setOnClickListener {
            visibility = GONE
            onReplay?.invoke()
        }
        exitBtn.setOnClickListener {
            visibility = GONE
            onExit?.invoke()
        }
    }

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

    val isShowing: Boolean get() = visibility == VISIBLE
}
