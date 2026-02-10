package com.ooustream.iptv.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R

class PosterSkeletonPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poster_skeleton, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {}
    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}

class ChannelSkeletonPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_skeleton, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {}
    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
