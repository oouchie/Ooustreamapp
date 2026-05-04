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
    // TV path: full-screen overlay with D-pad focus trap.
    private var overlay: ContentInfoOverlay? = null
    // Phone path: BottomSheetDialogFragment instance shown per long-press (created on demand).
    private var activePhoneSheet: PhoneContentInfoSheet? = null
    private var currentItem: PosterItem? = null
    private var favoriteCallback: ((PosterItem) -> Unit)? = null

    private val isPhone: Boolean
        get() = !DeviceUtils.isTV(fragment.requireContext())

    /** Add the overlay to the fragment's root view. Call in onViewCreated. */
    fun attach(root: ViewGroup) {
        // On phone, we don't pre-attach an overlay — the BottomSheetDialogFragment
        // is created per long-press and adds itself to the FragmentManager. The TV
        // overlay path is unchanged.
        if (isPhone) return

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
            if (isPhone) {
                showPhoneSheet(data, item)
            } else {
                overlay?.show(data)
            }
        }
    }

    private fun showPhoneSheet(data: ContentInfoOverlay.ContentData, item: PosterItem) {
        val sheet = PhoneContentInfoSheet()
            .setData(data)
            .setOnPlay { onPlay(item) }
            .setOnFavorite {
                favoriteCallback?.invoke(item)
            }
        activePhoneSheet = sheet
        sheet.show(fragment.parentFragmentManager, "content_info_sheet")
    }

    /** Dismiss the overlay/sheet. Returns true if it was showing. */
    fun dismiss(): Boolean {
        return if (isPhone) {
            val sheet = activePhoneSheet
            if (sheet?.isVisible == true) {
                sheet.dismissAllowingStateLoss()
                activePhoneSheet = null
                true
            } else false
        } else {
            if (overlay?.isShowing == true) {
                overlay?.dismiss()
                true
            } else false
        }
    }

    val isShowing: Boolean
        get() = if (isPhone) activePhoneSheet?.isVisible == true
                else overlay?.isShowing == true

    fun cleanup() {
        if (isPhone) {
            activePhoneSheet?.dismissAllowingStateLoss()
            activePhoneSheet = null
        } else {
            overlay?.dismiss()
            overlay = null
        }
    }

    /** Set the favorite button callback. */
    fun setOnFavorite(callback: (PosterItem) -> Unit) {
        favoriteCallback = callback
        // TV overlay path needs the callback wired through the existing field.
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
