package com.ooustream.iptv.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R
import com.ooustream.iptv.data.model.LiveStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChannelPresenter(
    var favoriteIds: Set<String> = emptySet()
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val channel = item as LiveStream
        val root = viewHolder.view as LinearLayout
        val logo = root.findViewById<ImageView>(R.id.channel_logo)
        val name = root.findViewById<TextView>(R.id.channel_name)
        val epg = root.findViewById<TextView>(R.id.channel_epg)
        val accentBar = root.findViewById<View>(R.id.channel_accent_bar)

        name.text = channel.name
        epg.text = "" // EPG set externally

        // Show favorites star
        val star = root.findViewById<ImageView>(R.id.channel_favorite_star)
        val isFavorite = favoriteIds.contains("live_${channel.streamId}")
        if (isFavorite) {
            star.visibility = View.VISIBLE
            star.setImageResource(R.drawable.ic_favorites)
            star.setColorFilter(ContextCompat.getColor(root.context, R.color.focus_gold))
        } else {
            star.visibility = View.GONE
        }

        val iconUrl = channel.streamIcon
        val cacheKey = "channel_${channel.streamId}"
        ProgressiveImageLoader.loadThumbnail(logo, iconUrl, cacheKey)

        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
                v.overlay.add(GoldGlowFocusDrawable())
                v.overlay.add(FocusBracketDrawable())
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(200).start()
                v.setBackgroundResource(R.drawable.bg_channel_aurora_focused)
                accentBar.setBackgroundColor(ContextCompat.getColor(v.context, R.color.focus_gold))

                // Debounced full-res load after 300ms sustained focus
                val job = CoroutineScope(Dispatchers.Main).launch {
                    delay(300)
                    ProgressiveImageLoader.loadFullRes(logo, iconUrl)
                }
                v.setTag(R.id.focus_load_job, job)
            } else {
                // Cancel pending full-res load
                (v.getTag(R.id.focus_load_job) as? Job)?.cancel()

                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                v.setBackgroundResource(R.drawable.bg_channel_aurora)
                accentBar.setBackgroundColor(ContextCompat.getColor(v.context, R.color.brand_cyan))
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as LinearLayout
        root.setOnFocusChangeListener(null)
    }
}
