package com.ooustream.iptv.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.GoldGlowFocusDrawable

data class ChannelStripItem(
    val channelId: Int,
    val channelName: String,
    val channelIcon: String?,
    val streamUrl: String
)

class ChannelStripPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_strip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val channel = item as? ChannelStripItem ?: return
        val view = viewHolder.view
        val logo = view.findViewById<ImageView>(R.id.channel_logo)
        val initials = view.findViewById<TextView>(R.id.channel_initials)
        val name = view.findViewById<TextView>(R.id.channel_name)
        val logoFrame = view.findViewById<FrameLayout>(R.id.channel_logo_frame)

        name.text = channel.channelName

        if (!channel.channelIcon.isNullOrBlank()) {
            logo.visibility = View.VISIBLE
            initials.visibility = View.GONE
            logo.load(channel.channelIcon) {
                crossfade(true)
                error(android.R.color.transparent)
                listener(onError = { _, _ ->
                    logo.visibility = View.GONE
                    initials.visibility = View.VISIBLE
                    initials.text = getInitials(channel.channelName)
                })
            }
        } else {
            logo.visibility = View.GONE
            initials.visibility = View.VISIBLE
            initials.text = getInitials(channel.channelName)
        }

        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                logoFrame.setBackgroundResource(R.drawable.bg_channel_strip_circle_focused)
                if (DeviceUtils.isTV(v.context)) {
                    v.overlay.add(GoldGlowFocusDrawable())
                }
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                logoFrame.setBackgroundResource(R.drawable.bg_channel_strip_circle)
                v.overlay.clear()
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}

    private fun getInitials(name: String): String {
        val cleaned = name.substringAfter("|").trim().ifEmpty { name }
        return cleaned.take(3).uppercase()
    }
}
