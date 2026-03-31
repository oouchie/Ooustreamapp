package com.ooustream.iptv.common

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import coil.request.CachePolicy
import coil.size.Scale
import com.ooustream.iptv.R
import com.ooustream.iptv.epg.ChannelNameParser
import com.ooustream.iptv.epg.DisplayChannelName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Helper for channel display in the Live TV channel list.
 * Handles name parsing, quality badges, initials fallback, and EPG formatting.
 */
object ChannelDisplayHelper {

    /**
     * Get 2-3 character initials from a channel name for logo fallback.
     * "USA | ESPN HD" → "ESP", "BBC One" → "BBC", "CNN International" → "CNN"
     */
    fun getInitials(name: String): String {
        val cleaned = name
            .replace(Regex("^[A-Z]{2}\\s*[|:\\-]\\s*"), "") // strip country prefix
            .replace(Regex("\\s*(HD|FHD|4K|SD|UHD|H265|HEVC)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        return cleaned.take(3).uppercase()
    }

    /**
     * Load channel logo with smart fallback to initials.
     */
    fun loadLogo(
        logoImageView: ImageView,
        initialsTextView: TextView,
        logoUrl: String?,
        channelName: String
    ) {
        if (logoUrl.isNullOrBlank()) {
            showInitials(logoImageView, initialsTextView, channelName)
            return
        }

        initialsTextView.visibility = View.GONE
        logoImageView.visibility = View.VISIBLE

        logoImageView.load(logoUrl) {
            crossfade(150)
            scale(Scale.FIT)
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.ENABLED)
            placeholder(ColorDrawable(0xFF1A2332.toInt()))
            error(ColorDrawable(0xFF1A2332.toInt()))
            allowHardware(false) // Need software bitmap for size check
            listener(
                onSuccess = { _, result ->
                    val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null && bitmap.width < 24 && bitmap.height < 24) {
                        // Only replace truly broken/tiny logos (< 24px)
                        showInitials(logoImageView, initialsTextView, channelName)
                    }
                },
                onError = { _, _ ->
                    showInitials(logoImageView, initialsTextView, channelName)
                }
            )
        }
    }

    private fun showInitials(logoImageView: ImageView, initialsTextView: TextView, channelName: String) {
        logoImageView.visibility = View.GONE
        initialsTextView.visibility = View.VISIBLE
        initialsTextView.text = getInitials(channelName)
    }

    /**
     * Bind quality badge with correct style.
     */
    fun bindQualityBadge(badge: TextView, quality: String?) {
        if (quality == null) {
            badge.visibility = View.GONE
            return
        }

        when (quality.uppercase()) {
            "4K", "UHD", "2160P" -> {
                badge.text = "4K"
                badge.setTextColor(0xFF22C55E.toInt())
                badge.setBackgroundResource(R.drawable.bg_quality_badge_4k)
                badge.visibility = View.VISIBLE
            }
            "FHD", "1080P" -> {
                badge.text = "FHD"
                badge.setTextColor(0xFFFFD700.toInt())
                badge.setBackgroundResource(R.drawable.bg_quality_badge_fhd)
                badge.visibility = View.VISIBLE
            }
            "HD", "720P" -> {
                badge.text = "HD"
                badge.setTextColor(0x99FFD700.toInt())
                badge.setBackgroundResource(R.drawable.bg_quality_badge_hd)
                badge.visibility = View.VISIBLE
            }
            else -> {
                // SD or unknown — no badge
                badge.visibility = View.GONE
            }
        }
    }

    /**
     * Bind country badge.
     */
    fun bindCountryBadge(badge: TextView, country: String?) {
        if (country.isNullOrBlank()) {
            badge.visibility = View.GONE
            return
        }
        badge.text = country
        badge.visibility = View.VISIBLE
    }

    /**
     * Format EPG time for compact display: "9:00p" or "21:00"
     */
    fun formatEpgTimeCompact(startTime: String?, startTimestamp: String? = null): String {
        // Prefer Unix timestamp — always timezone-correct
        val epochSec = startTimestamp?.toLongOrNull()
        if (epochSec != null) {
            val outputFormat = SimpleDateFormat("h:mma", Locale.US)
            return outputFormat.format(java.util.Date(epochSec * 1000))
                .lowercase().replace("am", "a").replace("pm", "p")
        }
        // Fallback: parse start string as local time (Xtream servers provide local times)
        if (startTime.isNullOrBlank()) return ""
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val date = inputFormat.parse(startTime) ?: return ""
            val outputFormat = SimpleDateFormat("h:mma", Locale.US)
            outputFormat.format(date).lowercase().replace("am", "a").replace("pm", "p")
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Calculate EPG progress as fraction 0.0-1.0.
     */
    fun calculateEpgProgress(startTimestamp: Long?, stopTimestamp: Long?): Float {
        if (startTimestamp == null || stopTimestamp == null) return 0f
        val now = System.currentTimeMillis() / 1000
        if (now < startTimestamp || now > stopTimestamp) return 0f
        val duration = stopTimestamp - startTimestamp
        if (duration <= 0) return 0f
        return ((now - startTimestamp).toFloat() / duration).coerceIn(0f, 1f)
    }

    /**
     * Set progress bar width as a fraction of parent width.
     */
    fun setProgressBar(progressFill: View, progressContainer: View, progress: Float) {
        if (progress <= 0f) {
            progressContainer.visibility = View.GONE
            return
        }
        progressContainer.visibility = View.VISIBLE
        progressContainer.post {
            val parentWidth = progressContainer.width
            val lp = progressFill.layoutParams
            lp.width = (parentWidth * progress).toInt()
            progressFill.layoutParams = lp
        }
    }
}
