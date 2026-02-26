package com.ooustream.iptv.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FocusBracketDrawable
import com.ooustream.iptv.common.GoldGlowFocusDrawable
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterPresenter
import com.ooustream.iptv.common.ProgressiveImageLoader

/**
 * Wraps a PosterItem with its trending rank number for display.
 */
data class TrendingRankItem(
    val rank: Int,
    val poster: PosterItem
)

/**
 * Leanback Presenter for trending ranked poster cards.
 * Shows a large rank number behind an offset poster card.
 */
class TrendingRankPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trending_rank, parent, false)
        // Cache focus drawables per view
        if (DeviceUtils.isTV(parent.context)) {
            view.setTag(R.id.focus_glow_drawable, GoldGlowFocusDrawable())
            view.setTag(R.id.focus_bracket_drawable, FocusBracketDrawable())
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val trendingItem = item as TrendingRankItem
        val root = viewHolder.view as FrameLayout
        val rankNumber = root.findViewById<TextView>(R.id.rank_number)
        val image = root.findViewById<ImageView>(R.id.trending_image)
        val title = root.findViewById<TextView>(R.id.trending_title)
        val rating = root.findViewById<TextView>(R.id.trending_rating)
        val qualityBadge = root.findViewById<TextView>(R.id.trending_quality_badge)

        rankNumber.text = trendingItem.rank.toString()
        title.text = trendingItem.poster.title

        // Rating
        if (!trendingItem.poster.rating.isNullOrBlank() && trendingItem.poster.rating != "0") {
            rating.text = "\u2605 ${trendingItem.poster.rating}"
            rating.visibility = View.VISIBLE
        } else {
            rating.visibility = View.GONE
        }

        // Quality badge (reuses PosterPresenter logic)
        PosterPresenter.bindQualityBadge(qualityBadge, trendingItem.poster.title, trendingItem.poster.extension)

        // Image
        val imageUrl = trendingItem.poster.imageUrl
        val cacheKey = "trending_${trendingItem.poster.id}"
        ProgressiveImageLoader.loadThumbnail(image, imageUrl, cacheKey)

        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
                val glow = v.getTag(R.id.focus_glow_drawable) as? GoldGlowFocusDrawable
                val brackets = v.getTag(R.id.focus_bracket_drawable) as? FocusBracketDrawable
                v.overlay.clear()
                glow?.let { v.overlay.add(it) }
                brackets?.let { v.overlay.add(it) }
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(250).start()
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
