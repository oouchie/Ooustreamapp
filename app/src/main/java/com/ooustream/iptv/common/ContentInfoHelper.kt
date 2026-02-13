package com.ooustream.iptv.common

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ooustream.iptv.data.repository.ContentRepository
import kotlinx.coroutines.launch

/**
 * Helper that manages a [ContentInfoOverlay] for any fragment.
 * Call [attach] in onViewCreated, then pass [onLongPress] as the
 * PosterPresenter callback. Handles API fetching and overlay lifecycle.
 */
class ContentInfoHelper(
    private val fragment: Fragment,
    private val contentRepository: ContentRepository,
    private val onPlay: (PosterItem) -> Unit
) {
    private var overlay: ContentInfoOverlay? = null
    private var currentItem: PosterItem? = null

    /** Add the overlay to the fragment's root view. Call in onViewCreated. */
    fun attach(root: ViewGroup) {
        val infoOverlay = ContentInfoOverlay(fragment.requireContext())
        root.addView(
            infoOverlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        infoOverlay.onPlay = {
            currentItem?.let { onPlay(it) }
        }
        overlay = infoOverlay
    }

    /** Use as PosterPresenter's onLongPress callback. */
    val onLongPress: (PosterItem) -> Unit = { item ->
        currentItem = item
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val data = fetchContentData(item)
            overlay?.show(data)
        }
    }

    /** Dismiss the overlay. Returns true if it was showing. */
    fun dismiss(): Boolean {
        return if (overlay?.isShowing == true) {
            overlay?.dismiss()
            true
        } else false
    }

    val isShowing: Boolean get() = overlay?.isShowing == true

    fun cleanup() {
        overlay?.dismiss()
        overlay = null
    }

    /** Set the favorite button callback. */
    fun setOnFavorite(callback: (PosterItem) -> Unit) {
        overlay?.onFavorite = {
            currentItem?.let { callback(it) }
        }
    }

    private suspend fun fetchContentData(item: PosterItem): ContentInfoOverlay.ContentData {
        return try {
            when (item.type) {
                "vod" -> {
                    val info = contentRepository.getVodInfo(item.id)
                    val detail = info.info
                    ContentInfoOverlay.ContentData(
                        title = item.title,
                        imageUrl = item.imageUrl,
                        rating = detail?.rating ?: item.rating,
                        year = detail?.releaseDate?.take(4),
                        genre = detail?.genre,
                        duration = detail?.duration,
                        quality = parseQuality(item.title, item.extension),
                        plot = detail?.plot,
                        youtubeTrailer = detail?.youtubeTrailer
                    )
                }
                "series" -> {
                    val info = contentRepository.getSeriesInfo(item.id)
                    val detail = info.info
                    ContentInfoOverlay.ContentData(
                        title = item.title,
                        imageUrl = item.imageUrl,
                        rating = detail?.rating ?: item.rating,
                        year = detail?.releaseDate?.take(4),
                        genre = detail?.genre,
                        plot = detail?.plot,
                        youtubeTrailer = detail?.youtubeTrailer
                    )
                }
                else -> basicData(item)
            }
        } catch (_: Exception) {
            basicData(item)
        }
    }

    private fun basicData(item: PosterItem) = ContentInfoOverlay.ContentData(
        title = item.title,
        imageUrl = item.imageUrl,
        rating = item.rating
    )

    private fun parseQuality(title: String, extension: String?): String? {
        val upper = title.uppercase()
        return when {
            upper.contains("4K") || upper.contains("UHD") -> "4K"
            upper.contains("FHD") || upper.contains("1080") -> "FHD"
            upper.contains(" HD") || upper.contains("720") || extension?.uppercase() == "MKV" -> "HD"
            else -> null
        }
    }
}
