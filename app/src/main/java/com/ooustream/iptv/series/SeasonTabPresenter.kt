package com.ooustream.iptv.series

import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter

class SeasonTabPresenter(
    private val onSeasonSelected: (String) -> Unit  // passes the episode map key
) : Presenter() {

    var selectedKey: String = ""

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val tv = TextView(parent.context).apply {
            setPadding(32, 16, 32, 16)
            textSize = 15f
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFF1F2937.toInt())
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(vh: ViewHolder, item: Any) {
        val tab = item as SeasonTab
        val tv = vh.view as TextView
        val isSelected = selectedKey == tab.key

        tv.text = tab.displayName
        tv.setTextColor(if (isSelected) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt())
        tv.setBackgroundColor(if (isSelected) 0xFF263244.toInt() else 0xFF1F2937.toInt())

        tv.setOnClickListener {
            onSeasonSelected(tab.key)
        }
        tv.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundColor(0xFF263244.toInt())
                (v as TextView).setTextColor(0xFFFFC107.toInt())
            } else if (!isSelected) {
                v.setBackgroundColor(0xFF1F2937.toInt())
                (v as TextView).setTextColor(Color.WHITE)
            }
        }
    }

    override fun onUnbindViewHolder(vh: ViewHolder) {}
}
