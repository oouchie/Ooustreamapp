package com.ooustream.iptv.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
import com.ooustream.iptv.common.PosterUrlRewriter
import com.ooustream.iptv.data.local.entity.SeriesTrackingEntity

class NewEpisodesPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_new_episode, parent, false)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true

        // Apply 4dp corner radius to both badges once at view creation
        val density = parent.context.resources.displayMetrics.density
        val cornerRadius = 4 * density

        val badgeNew = view.findViewById<TextView>(R.id.ne_badge_new)
        val badgeCount = view.findViewById<TextView>(R.id.ne_badge_count)

        badgeNew.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#E622C55E"))  // green, 90% opacity
            this.cornerRadius = cornerRadius
        }

        badgeCount.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#CCFFC107"))  // gold, 80% opacity
            this.cornerRadius = cornerRadius
        }

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val series = item as SeriesTrackingEntity
        val root = viewHolder.view as FrameLayout
        val image = root.findViewById<ImageView>(R.id.ne_image)
        val badgeNew = root.findViewById<TextView>(R.id.ne_badge_new)
        val badgeCount = root.findViewById<TextView>(R.id.ne_badge_count)
        val title = root.findViewById<TextView>(R.id.ne_title)

        title.text = series.seriesTitle

        // "NEW" badge is always visible (row only shows series with new episodes)
        badgeNew.visibility = View.VISIBLE

        // Count badge: show episode count
        if (series.newEpisodeCount > 0) {
            badgeCount.text = "${series.newEpisodeCount} NEW"
            badgeCount.visibility = View.VISIBLE
        } else {
            badgeCount.visibility = View.GONE
        }

        // Load poster image
        val url = series.posterUrl
        if (!url.isNullOrBlank()) {
            image.load(PosterUrlRewriter.rewrite(url)) {
                crossfade(200)
            }
        } else {
            image.setImageDrawable(null)
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
        root.overlay.clear()
        root.animate().scaleX(1f).scaleY(1f).setDuration(0).start()
    }
}
