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

        title.text = progress.name
        progressBar.progress = (progress.progressPercent * 100).toInt()

        // Series subtitle: "S1 E2 · Up Next" or "S1 E2 · Resume"
        val isSeries = progress.type == "series" && progress.seasonNum != null
        val isUpNext = isSeries && progress.position == 0L
        if (isSeries) {
            resumeText.text = if (isUpNext) {
                "S${progress.seasonNum} E${progress.episodeNum} \u00B7 Up Next"
            } else {
                "S${progress.seasonNum} E${progress.episodeNum} \u00B7 Resume"
            }
            resumeText.visibility = View.VISIBLE
        } else {
            resumeText.text = "Resume"
            resumeText.visibility = View.GONE
        }

        progress.icon?.let { url ->
            if (url.isNotBlank()) {
                image.load(PosterUrlRewriter.rewrite(url)) {
                    crossfade(200)
                }
            }
        }

        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
                if (DeviceUtils.isTV(v.context)) {
                    v.overlay.add(GoldGlowFocusDrawable())
                    v.overlay.add(FocusBracketDrawable())
                }
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(200).start()
                resumeText.visibility = View.VISIBLE
            } else {
                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                if (!isSeries) resumeText.visibility = View.GONE
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as FrameLayout
        root.setOnFocusChangeListener(null)
    }
}
