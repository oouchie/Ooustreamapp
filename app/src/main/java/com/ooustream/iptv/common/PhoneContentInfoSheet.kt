package com.ooustream.iptv.common

import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ooustream.iptv.R

/**
 * v3.7.11 — phone-native long-press content-info card.
 *
 * Mirrors [ContentInfoOverlay] but rendered as a Material [BottomSheetDialogFragment]
 * for the standard phone idiom: drag-to-dismiss handle, swipe down to close, scrim
 * provided by the dialog (no manual backdrop_dim management).
 *
 * On TV the existing overlay is still used — that path uses D-pad focus trapping
 * which doesn't translate to a sheet. Branching happens in [ContentInfoHelper].
 *
 * Inflates the same [R.layout.overlay_content_info] for visual parity, but hides
 * the layout's own backdrop_dim view since the BottomSheetDialog already supplies
 * a system-managed scrim.
 */
class PhoneContentInfoSheet : BottomSheetDialogFragment() {

    private var data: ContentInfoOverlay.ContentData? = null
    private var onPlayClick: (() -> Unit)? = null
    private var onFavoriteClick: (() -> Unit)? = null

    fun setData(d: ContentInfoOverlay.ContentData): PhoneContentInfoSheet {
        this.data = d
        return this
    }

    fun setOnPlay(cb: () -> Unit): PhoneContentInfoSheet {
        this.onPlayClick = cb
        return this
    }

    fun setOnFavorite(cb: () -> Unit): PhoneContentInfoSheet {
        this.onFavoriteClick = cb
        return this
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { d ->
            val sheet = (d as BottomSheetDialog).findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                // Top-rounded corners + dark surface so the sheet reads on top of
                // the home/grid behind it.
                val density = resources.displayMetrics.density
                it.background = GradientDrawable().apply {
                    cornerRadii = floatArrayOf(
                        16f * density, 16f * density,
                        16f * density, 16f * density,
                        0f, 0f, 0f, 0f
                    )
                    setColor(0xFF111316.toInt())
                }
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Reuse the existing overlay layout for visual parity. We won't use its
        // backdrop_dim child (BottomSheetDialog supplies its own scrim) — hide it.
        val rootContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val view = inflater.inflate(R.layout.overlay_content_info, rootContainer, false)
        view.findViewById<View>(R.id.info_backdrop_dim)?.visibility = View.GONE

        val d = data
        if (d != null) bind(view, d)

        // Wire button callbacks
        view.findViewById<TextView>(R.id.info_play_btn)?.setOnClickListener {
            dismiss()
            onPlayClick?.invoke()
        }
        view.findViewById<TextView>(R.id.info_favorite_btn)?.setOnClickListener {
            onFavoriteClick?.invoke()
        }

        rootContainer.addView(view)
        return rootContainer
    }

    private fun bind(root: View, data: ContentInfoOverlay.ContentData) {
        root.findViewById<TextView>(R.id.info_title)?.text = data.title

        root.findViewById<ImageView>(R.id.info_poster)?.load(data.imageUrl) {
            crossfade(200)
            placeholder(R.drawable.bg_card_rounded)
            error(R.drawable.bg_card_rounded)
            transformations(RoundedCornersTransformation(12f))
        }

        val metaText = root.findViewById<TextView>(R.id.info_meta)
        val metaParts = mutableListOf<String>()
        if (!data.rating.isNullOrBlank() && data.rating != "0") {
            metaParts.add("★ ${data.rating}")
        }
        if (!data.year.isNullOrBlank()) metaParts.add(data.year)
        if (!data.genre.isNullOrBlank()) metaParts.add(data.genre)
        if (metaParts.isNotEmpty()) {
            metaText?.text = metaParts.joinToString("  ·  ")
            metaText?.visibility = View.VISIBLE
        } else {
            metaText?.visibility = View.GONE
        }

        val techText = root.findViewById<TextView>(R.id.info_technical)
        val techParts = mutableListOf<String>()
        if (!data.quality.isNullOrBlank()) techParts.add(data.quality)
        if (!data.duration.isNullOrBlank()) techParts.add(data.duration)
        if (techParts.isNotEmpty()) {
            techText?.text = techParts.joinToString("  ·  ")
            techText?.visibility = View.VISIBLE
        } else {
            techText?.visibility = View.GONE
        }

        val plotText = root.findViewById<TextView>(R.id.info_plot)
        if (!data.plot.isNullOrBlank()) {
            plotText?.text = data.plot
            plotText?.visibility = View.VISIBLE
        } else {
            plotText?.visibility = View.GONE
        }

        val trailerBtn = root.findViewById<TextView>(R.id.info_trailer_btn)
        val trailerId = data.youtubeTrailer?.trim()
        if (!trailerId.isNullOrBlank()) {
            trailerBtn?.visibility = View.VISIBLE
            trailerBtn?.setOnClickListener {
                val url = if (trailerId.startsWith("http")) trailerId
                else "https://www.youtube.com/watch?v=$trailerId"
                runCatching {
                    requireContext().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        } else {
            trailerBtn?.visibility = View.GONE
        }
    }
}
