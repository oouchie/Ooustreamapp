package com.ooustream.iptv.search

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.GoldGlowFocusDrawable

/**
 * Leanback Presenter for recent search chips — gold-glow pill with query text.
 */
class SearchChipPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_chip, parent, false)
        // Cache focus drawable per view
        view.setTag(R.id.focus_glow_drawable, GoldGlowFocusDrawable())
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val query = item as String
        val chip = viewHolder.view as TextView
        chip.text = query

        chip.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
                val glow = v.getTag(R.id.focus_glow_drawable) as? GoldGlowFocusDrawable
                v.overlay.clear()
                glow?.let { v.overlay.add(it) }
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(200).start()
                (v as TextView).setTextColor(0xFFFFFFFF.toInt())
            } else {
                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                (v as TextView).setTextColor(0xFF9CA3AF.toInt())
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.setOnFocusChangeListener(null)
    }
}
