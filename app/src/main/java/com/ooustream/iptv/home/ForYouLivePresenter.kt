package com.ooustream.iptv.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import coil.load
import com.ooustream.iptv.R
import com.ooustream.iptv.common.PosterUrlRewriter
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FocusBracketDrawable
import com.ooustream.iptv.common.GoldGlowFocusDrawable
import com.ooustream.iptv.epg.EpgSource
import com.ooustream.iptv.epg.InferredEpg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Data class for a "For You — Live Now" channel card.
 */
data class ForYouChannel(
    val channelId: Int,
    val channelName: String,
    val channelIcon: String?,
    val categoryName: String?,
    val nowPlaying: InferredEpg?,
    val contextHint: String?
)

/**
 * Presenter for "For You — Live Now" horizontal channel cards on Home screen.
 */
class ForYouLivePresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_for_you_live, parent, false)
        if (DeviceUtils.isTV(parent.context)) {
            view.setTag(R.id.focus_glow_drawable, GoldGlowFocusDrawable())
            view.setTag(R.id.focus_bracket_drawable, FocusBracketDrawable())
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val channel = item as ForYouChannel
        val root = viewHolder.view as LinearLayout
        val logo = root.findViewById<ImageView>(R.id.for_you_live_logo)
        val name = root.findViewById<TextView>(R.id.for_you_live_name)
        val nowPlaying = root.findViewById<TextView>(R.id.for_you_live_now_playing)
        val hint = root.findViewById<TextView>(R.id.for_you_live_hint)

        name.text = channel.channelName

        if (!channel.channelIcon.isNullOrBlank()) {
            logo.load(PosterUrlRewriter.rewrite(channel.channelIcon)) { crossfade(200) }
            logo.visibility = View.VISIBLE
        } else {
            logo.visibility = View.GONE
        }

        // Now playing with EPG source styling
        val epg = channel.nowPlaying
        if (epg != null) {
            nowPlaying.text = epg.title
            when (epg.source) {
                EpgSource.REAL -> {
                    nowPlaying.typeface = android.graphics.Typeface.DEFAULT
                    nowPlaying.setTextColor(0xCCFFFFFF.toInt())
                }
                EpgSource.PATTERN_CACHE -> {
                    nowPlaying.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC)
                    nowPlaying.setTextColor(0xAA90CAF9.toInt())
                }
                EpgSource.RULE_INFERRED -> {
                    nowPlaying.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC)
                    nowPlaying.setTextColor(0x77FFFFFF)
                }
            }
            nowPlaying.visibility = View.VISIBLE
        } else {
            nowPlaying.visibility = View.GONE
        }

        // Context hint
        if (!channel.contextHint.isNullOrBlank()) {
            hint.text = channel.contextHint
            hint.visibility = View.VISIBLE
        } else {
            hint.visibility = View.GONE
        }

        root.setOnFocusChangeListener { v, hasFocus ->
            (v.getTag(R.id.focus_effect_job) as? Job)?.cancel()

            if (hasFocus) {
                val job = CoroutineScope(Dispatchers.Main).launch {
                    delay(60)
                    DpadSoundManager.getInstance()?.playMove()
                    val glow = v.getTag(R.id.focus_glow_drawable) as? GoldGlowFocusDrawable
                    val brackets = v.getTag(R.id.focus_bracket_drawable) as? FocusBracketDrawable
                    v.overlay.clear()
                    glow?.let { v.overlay.add(it) }
                    brackets?.let { v.overlay.add(it) }
                    v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(150).start()
                }
                v.setTag(R.id.focus_effect_job, job)
            } else {
                v.overlay.clear()
                v.animate().cancel()
                v.scaleX = 1f
                v.scaleY = 1f
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as LinearLayout
        (root.getTag(R.id.focus_effect_job) as? Job)?.cancel()
        root.setOnFocusChangeListener(null)
    }
}
