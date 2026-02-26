package com.ooustream.iptv.home

import android.view.LayoutInflater
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
import com.ooustream.iptv.recommendation.RecommendedItem

class ForYouPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_for_you, parent, false)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val recommended = item as RecommendedItem
        val root = viewHolder.view as FrameLayout
        val image = root.findViewById<ImageView>(R.id.fy_image)
        val title = root.findViewById<TextView>(R.id.fy_title)
        val reason = root.findViewById<TextView>(R.id.fy_reason)

        title.text = recommended.name
        reason.text = recommended.reason

        recommended.icon?.let { url ->
            if (url.isNotBlank()) {
                image.load(url) {
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
            } else {
                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as FrameLayout
        root.setOnFocusChangeListener(null)
    }
}
