package com.ooustream.iptv.common

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import androidx.leanback.widget.Presenter
import com.facebook.shimmer.ShimmerFrameLayout
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import java.util.concurrent.ConcurrentHashMap

data class PosterItem(
    val id: Int,
    val title: String,
    val imageUrl: String?,
    val rating: String?,
    val extension: String?,
    val type: String, // "vod" or "series"
    val watchCompleted: Boolean = false,
    val watchProgress: Float = 0f, // 0.0 - 1.0
    val tmdbId: String? = null,
    /** Set on search results that matched on an actor (not the title) → renders "Starring {actor}". */
    val castMatch: String? = null
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
        val metaView = root.findViewById<TextView>(R.id.poster_meta)
        val ctaView = root.findViewById<TextView>(R.id.poster_cta)
        val shimmer = root.findViewById<ShimmerFrameLayout>(R.id.poster_shimmer)

        title.text = poster.title

        // Rating in gold
        if (!poster.rating.isNullOrBlank() && poster.rating != "0") {
            rating.text = "\u2605 ${poster.rating}"
            rating.visibility = View.VISIBLE
        } else {
            rating.visibility = View.GONE
        }

        // "Starring {actor}" — only for search results matched on cast (always visible when set)
        val starringView = root.findViewById<TextView>(R.id.poster_starring)
        if (!poster.castMatch.isNullOrBlank()) {
            starringView.text = "Starring ${poster.castMatch}"
            starringView.visibility = View.VISIBLE
        } else {
            starringView.visibility = View.GONE
        }

        // Quality badge - parse from title + extension
        bindQualityBadge(qualityBadge, poster.title, poster.extension)

        // Build metadata text for focus reveal
        val qualityLabel = resolveQualityLabel(poster.title, poster.extension)
        val ratingVal = poster.rating?.takeIf { it.isNotBlank() && it != "0" }
        val metaText = when (poster.type) {
            "series" -> {
                if (ratingVal != null) "\u2605 $ratingVal \u00B7 Series" else "Series"
            }
            else -> { // vod
                if (ratingVal != null) "\u2605 $ratingVal \u00B7 $qualityLabel" else qualityLabel
            }
        }
        metaView.text = metaText

        // CTA text based on content type
        ctaView.text = if (poster.type == "series") "\u25B6 Details" else "\u25B6 Watch"

        // Both hidden by default — revealed on focus
        metaView.visibility = View.GONE
        ctaView.visibility = View.GONE

        // Watch status indicators. The progress bar lives behind a ViewStub and is
        // only inflated for cards that are actually partially watched — most cards
        // never pay the cost of the themed ProgressBar (its style resolution was the
        // main-thread stall point in the v4.2.6 ANR traces).
        val watchedBadge = root.findViewById<ImageView>(R.id.poster_watched_badge)
        val existingProgress = root.findViewById<ProgressBar>(R.id.poster_progress_bar)
        if (poster.watchCompleted) {
            watchedBadge.visibility = View.VISIBLE
            existingProgress?.visibility = View.GONE
            image.alpha = 0.7f
        } else if (poster.watchProgress > 0.05f) {
            watchedBadge.visibility = View.GONE
            val progressBar = existingProgress
                ?: (root.findViewById<ViewStub>(R.id.poster_progress_stub)?.inflate() as? ProgressBar)
            progressBar?.visibility = View.VISIBLE
            progressBar?.progress = (poster.watchProgress * 1000).toInt()
            image.alpha = 1f
        } else {
            watchedBadge.visibility = View.GONE
            existingProgress?.visibility = View.GONE
            image.alpha = 1f
        }

        // Shimmer — show the static placeholder immediately, but DON'T start the
        // infinite shimmer animator yet. A cached poster is decoded within a frame
        // and never needs it; only start animating if the image is genuinely still
        // loading after a short delay. Avoids spinning up dozens of ValueAnimators
        // during a fast D-pad scroll (the app was pegging CPU at 138-169% at ANR time).
        shimmer.alpha = 1f
        shimmer.visibility = View.VISIBLE

        val imageUrl = poster.imageUrl
        val cacheKey = "poster_${poster.id}"
        ProgressiveImageLoader.loadThumbnail(image, imageUrl, cacheKey)

        // If the image loaded (cache hit) dismiss the placeholder with no animator;
        // otherwise start the shimmer animation for the genuine loading case.
        image.postDelayed({
            if (image.drawable != null) {
                shimmer.stopShimmer()
                shimmer.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction { shimmer.visibility = View.GONE }
                    .start()
            } else {
                shimmer.startShimmer()
            }
        }, 140)

        // Safety fallback: always dismiss shimmer after 1500ms regardless
        image.postDelayed({
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
        }, 1500)

        // Info overlay always visible
        overlay.visibility = View.VISIBLE

        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
                if (DeviceUtils.isTV(v.context)) {
                    v.overlay.add(GoldGlowFocusDrawable())
                    v.overlay.add(FocusBracketDrawable())
                }
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(250).start()
                v.setBackgroundResource(R.drawable.bg_poster_card_aurora_focused)

                // Slide-up reveal for meta line
                metaView.translationY = 8f * v.context.resources.displayMetrics.density
                metaView.alpha = 0f
                metaView.visibility = View.VISIBLE
                metaView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .start()

                // CTA with 50ms stagger
                ctaView.translationY = 8f * v.context.resources.displayMetrics.density
                ctaView.alpha = 0f
                ctaView.visibility = View.VISIBLE
                ctaView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .setStartDelay(50)
                    .start()

            } else {
                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                v.setBackgroundResource(R.drawable.bg_poster_card_aurora)

                // Cancel any running animations and hide immediately
                metaView.animate().cancel()
                ctaView.animate().cancel()
                metaView.visibility = View.GONE
                ctaView.visibility = View.GONE
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as FrameLayout
        root.setOnFocusChangeListener(null)

        // Reset shimmer state
        val shimmer = root.findViewById<ShimmerFrameLayout>(R.id.poster_shimmer)
        shimmer.stopShimmer()
        shimmer.alpha = 1f
        shimmer.visibility = View.GONE

        // Cancel pending postDelayed callbacks by removing all callbacks on image view
        val image = root.findViewById<ImageView>(R.id.poster_image)
        image.removeCallbacks(null)
    }

    companion object {
        /**
         * Parse quality indicators from title and extension.
         * Priority: 4K > FHD > HD > CAM > SD (default)
         * Every movie gets a badge — SD is the fallback.
         */
        fun bindQualityBadge(badge: TextView, title: String, extension: String? = null) {
            val label = resolveQualityLabel(title, extension)
            badge.text = label
            badge.setTextColor(badgeTextColor(label))
            applyBadgeBackground(badge, badgeBackgroundRes(label))
            badge.visibility = View.VISIBLE
        }

        private fun badgeTextColor(label: String): Int = when (label) {
            "4K" -> 0xFF10B981.toInt()
            "FHD", "HD" -> 0xFFFFC107.toInt()
            "CAM" -> 0xFFEF4444.toInt()
            else -> 0xFF6B7280.toInt()
        }

        @DrawableRes
        private fun badgeBackgroundRes(label: String): Int = when (label) {
            "4K" -> R.drawable.bg_quality_badge_4k
            // FHD deliberately shares the gold HD pill (pre-existing behaviour).
            "FHD", "HD" -> R.drawable.bg_quality_badge_hd
            "CAM" -> R.drawable.bg_quality_badge_cam
            else -> R.drawable.bg_quality_badge_sd
        }

        /**
         * Cached [Drawable.ConstantState] per badge background, keyed by resource id.
         *
         * `setBackgroundResource()` re-resolves the drawable on every bind, and on a cold
         * start that means parsing the shape XML out of the APK. The v4.2.9 Home ANR froze
         * exactly there — `AssetManager.nativeOpenXmlAsset` **while holding the AssetManager
         * lock**, mid leanback layout pass, with kswapd thrashing. Parse each badge once,
         * then hand every view its own cheap Drawable built from the shared constant state.
         *
         * A single Drawable instance must NOT be shared across recycled views: bounds and
         * the callback are per-instance, so siblings would fight over them. `newDrawable()`
         * is the cheap part — it allocates a wrapper over already-parsed state.
         *
         * Safe without a theme because all four badges are plain <shape>s with literal
         * colors (no `?attr/` references) — verified.
         */
        private val badgeBackgrounds = ConcurrentHashMap<Int, Drawable.ConstantState>()

        private fun applyBadgeBackground(badge: TextView, @DrawableRes resId: Int) {
            val resources = badge.resources
            badgeBackgrounds[resId]?.let { cached ->
                badge.background = cached.newDrawable(resources)
                return
            }
            // First touch for this badge in the process: resolve once, then cache.
            val drawable = ResourcesCompat.getDrawable(resources, resId, badge.context.theme)
            if (drawable == null) {
                // Fail open — never lose the badge over a caching optimisation.
                badge.setBackgroundResource(resId)
                return
            }
            drawable.constantState?.let { badgeBackgrounds[resId] = it }
            badge.background = drawable
        }

        /**
         * Resolve a short quality label string (e.g. "4K", "FHD", "HD", "CAM", "SD")
         * from title and extension — mirrors bindQualityBadge logic without touching a View.
         */
        fun resolveQualityLabel(title: String, extension: String? = null): String {
            val upper = title.uppercase()
            return when {
                upper.contains("4K") || upper.contains("UHD") || upper.contains("2160") -> "4K"
                upper.contains("FHD") || upper.contains("1080") ||
                upper.contains("BLURAY") || upper.contains("BLU-RAY") ||
                upper.contains("BRRIP") || upper.contains("BDRIP") -> "FHD"
                upper.contains(" HD") || upper.contains("[HD]") || upper.contains("720") ||
                upper.contains("WEB-DL") || upper.contains("WEBDL") ||
                upper.contains("WEBRIP") || upper.contains("WEB-RIP") ||
                upper.contains("HDTV") || upper.contains("HDRIP") ||
                extension?.uppercase() == "MKV" -> "HD"
                upper.contains("CAM") || upper.contains("HDCAM") ||
                upper.contains("TELESYNC") || upper.contains(" TS ") ||
                upper.contains("TELECINE") -> "CAM"
                else -> "SD"
            }
        }
    }
}
