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
import com.ooustream.iptv.data.local.entity.WatchProgressEntity

class WatchItAgainPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_watch_again, parent, false)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true

        // Apply circular shape to the checkmark badge once at view creation
        val checkmark = view.findViewById<TextView>(R.id.wa_checkmark)
        checkmark.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#FFC107"))  // gold
        }

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val progress = item as WatchProgressEntity
        val root = viewHolder.view as FrameLayout
        val image = root.findViewById<ImageView>(R.id.wa_image)
        val checkmark = root.findViewById<TextView>(R.id.wa_checkmark)
        val title = root.findViewById<TextView>(R.id.wa_title)

        title.text = com.ooustream.iptv.common.MediaTitleFormatter.cleanDisplayTitle(
            progress.name, isSeries = progress.type == "series"
        )

        // Checkmark badge is always visible (row only shows completed content)
        checkmark.visibility = View.VISIBLE

        // Load poster image
        val url = progress.icon
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
