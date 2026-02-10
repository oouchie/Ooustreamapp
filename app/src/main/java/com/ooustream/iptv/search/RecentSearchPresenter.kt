package com.ooustream.iptv.search

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R

/**
 * Simple Leanback Presenter that renders a recent search query string
 * as a focusable pill/chip in the search results area.
 */
class RecentSearchPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val textView = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 16, 32, 16)
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_card_rounded)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
        }
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val query = item as String
        val textView = viewHolder.view as TextView
        textView.text = query
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        // No cleanup needed
    }
}
