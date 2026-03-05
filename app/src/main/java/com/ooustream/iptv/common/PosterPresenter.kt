package com.ooustream.iptv.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils

data class PosterItem(
    val id: Int,
    val title: String,
    val imageUrl: String?,
    val rating: String?,
    val extension: String?,
    val type: String, // "vod" or "series"
    val watchCompleted: Boolean = false,
    val watchProgress: Float = 0f, // 0.0 - 1.0
    val tmdbId: String? = null
)

open class PosterPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster_card, parent, false)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val poster = item as PosterItem
        val root = viewHolder.view as FrameLayout
        val image = root.findViewById<ImageView>(R.id.poster_image)
        val title = root.findViewById<TextView>(R.id.poster_title)
        val rating = root.findViewById<TextView>(R.id.poster_rating)
        val overlay = root.findViewById<LinearLayout>(R.id.poster_info_overlay)
        val qualityBadge = root.findViewById<TextView>(R.id.poster_quality_badge)

        title.text = poster.title

        // Rating in gold
        if (!poster.rating.isNullOrBlank() && poster.rating != "0") {
            rating.text = "\u2605 ${poster.rating}"
            rating.visibility = View.VISIBLE
        } else {
            rating.visibility = View.GONE
        }

        // Quality badge - parse from title + extension
        bindQualityBadge(qualityBadge, poster.title, poster.extension)

        // Watch status indicators
        val watchedBadge = root.findViewById<ImageView>(R.id.poster_watched_badge)
        val progressBar = root.findViewById<ProgressBar>(R.id.poster_progress_bar)
        if (poster.watchCompleted) {
            watchedBadge.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
            image.alpha = 0.7f
        } else if (poster.watchProgress > 0.05f) {
            watchedBadge.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            progressBar.progress = (poster.watchProgress * 1000).toInt()
            image.alpha = 1f
        } else {
            watchedBadge.visibility = View.GONE
            progressBar.visibility = View.GONE
            image.alpha = 1f
        }

        val imageUrl = poster.imageUrl
        val cacheKey = "poster_${poster.id}"
        ProgressiveImageLoader.loadThumbnail(image, imageUrl, cacheKey)

        // Info overlay always visible
        overlay.visibility = View.VISIBLE

        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
                if (DeviceUtils.isTV(v.context)) {
                    v.overlay.add(GoldGlowFocusDrawable())
                    v.overlay.add(FocusBracketDrawable())
                }
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(250).start()
                v.setBackgroundResource(R.drawable.bg_poster_card_aurora_focused)

            } else {
                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                v.setBackgroundResource(R.drawable.bg_poster_card_aurora)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as FrameLayout
        root.setOnFocusChangeListener(null)
    }

    companion object {
        /**
         * Parse quality indicators from title and extension.
         * Priority: 4K > FHD > HD > CAM > SD (default)
         * Every movie gets a badge — SD is the fallback.
         */
        fun bindQualityBadge(badge: TextView, title: String, extension: String? = null) {
            val upper = title.uppercase()
            when {
                upper.contains("4K") || upper.contains("UHD") || upper.contains("2160") -> {
                    badge.text = "4K"
                    badge.setTextColor(0xFF10B981.toInt())
                    badge.setBackgroundResource(R.drawable.bg_quality_badge_4k)
                }
                upper.contains("FHD") || upper.contains("1080") ||
                upper.contains("BLURAY") || upper.contains("BLU-RAY") ||
                upper.contains("BRRIP") || upper.contains("BDRIP") -> {
                    badge.text = "FHD"
                    badge.setTextColor(0xFFFFC107.toInt())
                    badge.setBackgroundResource(R.drawable.bg_quality_badge_hd)
                }
                upper.contains(" HD") || upper.contains("[HD]") || upper.contains("720") ||
                upper.contains("WEB-DL") || upper.contains("WEBDL") ||
                upper.contains("WEBRIP") || upper.contains("WEB-RIP") ||
                upper.contains("HDTV") || upper.contains("HDRIP") ||
                extension?.uppercase() == "MKV" -> {
                    badge.text = "HD"
                    badge.setTextColor(0xFFFFC107.toInt())
                    badge.setBackgroundResource(R.drawable.bg_quality_badge_hd)
                }
                upper.contains("CAM") || upper.contains("HDCAM") ||
                upper.contains("TELESYNC") || upper.contains(" TS ") ||
                upper.contains("TELECINE") -> {
                    badge.text = "CAM"
                    badge.setTextColor(0xFFEF4444.toInt())
                    badge.setBackgroundResource(R.drawable.bg_quality_badge_cam)
                }
                else -> {
                    badge.text = "SD"
                    badge.setTextColor(0xFF6B7280.toInt())
                    badge.setBackgroundResource(R.drawable.bg_quality_badge_sd)
                }
            }
            badge.visibility = View.VISIBLE
        }
    }
}
