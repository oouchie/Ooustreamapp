package com.ooustream.iptv.common

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.palette.graphics.Palette
import coil.load
import coil.request.CachePolicy
import coil.size.Size
import coil.transform.RoundedCornersTransformation

/**
 * 3-tier progressive image loading:
 *   1. Instant color placeholder (from DominantColorCache or default dark)
 *   2. 200px thumbnail on bind (unfocused items)
 *   3. Full-resolution on sustained focus (300ms+)
 */
object ProgressiveImageLoader {

    private const val DEFAULT_PLACEHOLDER = 0xFF1A2332.toInt()

    private var qualityPolicy: QualityPolicy? = null

    fun setQualityPolicy(policy: QualityPolicy) {
        qualityPolicy = policy
    }

    /**
     * Load a thumbnail-quality image (200px wide) with an instant color placeholder.
     * Call this for unfocused items during onBindViewHolder.
     */
    fun loadThumbnail(
        imageView: ImageView,
        url: String?,
        cacheKey: String,
        cornerRadius: Float = 0f
    ) {
        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(ColorDrawable(DEFAULT_PLACEHOLDER))
            return
        }

        val cachedColor = DominantColorCache.get(cacheKey)
        val thumbnailSize = qualityPolicy?.imageSize?.takeIf { it > 0 } ?: 200
        imageView.load(url) {
            size(thumbnailSize, (thumbnailSize * 1.5).toInt())
            if (cachedColor != null) {
                placeholder(ColorDrawable(cachedColor))
            } else {
                placeholder(ColorDrawable(DEFAULT_PLACEHOLDER))
            }
            crossfade(150)
            memoryCachePolicy(CachePolicy.ENABLED)
            if (cornerRadius > 0f) {
                transformations(RoundedCornersTransformation(cornerRadius))
            }
            allowHardware(false) // Need software bitmap for Palette extraction
            listener(
                onSuccess = { _, result ->
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null && DominantColorCache.get(cacheKey) == null) {
                        Palette.from(bitmap).generate { palette ->
                            palette?.getDominantColor(DEFAULT_PLACEHOLDER)?.let { color ->
                                DominantColorCache.put(cacheKey, color)
                            }
                        }
                    }
                }
            )
        }
    }

    /**
     * Load full-resolution image. Call this when an item has sustained focus (300ms+).
     */
    fun loadFullRes(
        imageView: ImageView,
        url: String?,
        cornerRadius: Float = 0f
    ) {
        if (url.isNullOrBlank()) return
        imageView.load(url) {
            size(Size.ORIGINAL)
            crossfade(200)
            memoryCachePolicy(CachePolicy.ENABLED)
            if (cornerRadius > 0f) {
                transformations(RoundedCornersTransformation(cornerRadius))
            }
        }
    }
}
