package com.ooustream.iptv.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import com.ooustream.iptv.R
import com.ooustream.iptv.common.PosterUrlRewriter
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FocusBracketDrawable
import com.ooustream.iptv.common.GoldGlowFocusDrawable
import com.ooustream.iptv.data.local.entity.WatchProgressEntity

class ContinueWatchingPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching, parent, false)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val progress = item as WatchProgressEntity
        val root = viewHolder.view as FrameLayout
        val image = root.findViewById<ImageView>(R.id.cw_image)
        val title = root.findViewById<TextView>(R.id.cw_title)
        val progressBar = root.findViewById<ProgressBar>(R.id.cw_progress)
        val resumeText = root.findViewById<TextView>(R.id.cw_resume)

        val isSeries = progress.type == "series" && progress.seasonNum != null
        // Up Next placeholder rows (position 0) are queued, not watched — their stored 6% only
        // clears the Continue Watching filter, so no progress bar and no "time left".
        val isUpNext = isSeries && progress.position == 0L

        // One title line (the show, for series) + one detail line. Before 5.0 the episode showed up
        // three times: an "S3 E3" badge, inside the title, and again in the resume line — and a
        // separate "time left" badge sat on top of the text panel.
        val clean = com.ooustream.iptv.common.MediaTitleFormatter.cleanDisplayTitle(
            progress.name, isSeries = isSeries,
            seasonNum = progress.seasonNum ?: 0, episodeNum = progress.episodeNum ?: 0
        )
        title.text = if (isSeries) clean.split(" – ").first() else clean

        progressBar.visibility = if (isUpNext) View.GONE else View.VISIBLE
        if (!isUpNext) progressBar.progress = (progress.progressPercent * 100).toInt()

        val left = if (!isUpNext && progress.duration > 1L && progress.progressPercent < 0.95f)
            formatLeft(progress.duration - progress.position) else null
        val se = if (isSeries) "S${progress.seasonNum} E${progress.episodeNum}" else null
        resumeText.text = listOfNotNull(se, if (isUpNext) "Up next" else left).joinToString(" \u00B7 ")
        resumeText.visibility = if (resumeText.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val iconUrl = progress.icon?.takeIf { it.isNotBlank() }
        if (iconUrl != null) {
            image.load(PosterUrlRewriter.rewrite(iconUrl)) { crossfade(200) }
        } else {
            // Recycled card: without this it keeps the previous title's poster.
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

    /** "26m left" / "1h 29m left" (compact — the card is ~180dp wide); null when nothing to show. */
    private fun formatLeft(ms: Long): String? {
        val mins = (ms / 60_000L).toInt()
        if (mins <= 0) return null
        return if (mins >= 60) "${mins / 60}h ${mins % 60}m left" else "${mins}m left"
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as FrameLayout
        root.setOnFocusChangeListener(null)
    }
}
