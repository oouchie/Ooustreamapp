package com.ooustream.iptv.home

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FocusBracketDrawable
import com.ooustream.iptv.common.GoldGlowFocusDrawable
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterUrlRewriter

data class Top10Item(
    val rank: Int,
    val poster: PosterItem
)

class Top10Presenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top10_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val top10 = item as Top10Item
        val poster = top10.poster
        val root = viewHolder.view as FrameLayout
        val number = root.findViewById<TextView>(R.id.top10_number)
        val image = root.findViewById<ImageView>(R.id.top10_poster)
        val title = root.findViewById<TextView>(R.id.top10_title)
        val rating = root.findViewById<TextView>(R.id.top10_rating)

        number.text = top10.rank.toString()
        title.text = poster.title

        if (!poster.rating.isNullOrBlank()) {
            rating.text = "\u2605 ${poster.rating}"
            rating.visibility = android.view.View.VISIBLE
        } else {
            rating.visibility = android.view.View.GONE
        }

        poster.imageUrl?.let { url ->
            if (url.isNotBlank()) {
                image.load(PosterUrlRewriter.rewrite(url)) {
                    crossfade(200)
                }
            }
        }

        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
                if (DeviceUtils.isTV(v.context)) {
                    v.overlay.add(GoldGlowFocusDrawable())
                    v.overlay.add(FocusBracketDrawable())
                }
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(200).start()
            } else {
                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as FrameLayout
        root.setOnFocusChangeListener(null)
    }
}
