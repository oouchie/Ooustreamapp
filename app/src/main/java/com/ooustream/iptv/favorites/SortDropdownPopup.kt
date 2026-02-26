package com.ooustream.iptv.favorites

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import com.ooustream.iptv.R

/**
 * Custom sort dropdown anchored below the sort button.
 * D-pad navigable: Up/Down between items, OK selects, Back closes.
 */
class SortDropdownPopup(
    context: Context,
    private val onSortSelected: (SortMode) -> Unit
) {
    private val popupWindow: PopupWindow
    private val contentView: View
    private val options: List<Pair<TextView, SortMode>>

    private var currentMode: SortMode = SortMode.RECENTLY_ADDED

    init {
        contentView = LayoutInflater.from(context).inflate(R.layout.popup_sort_dropdown, null)

        val recentlyAdded = contentView.findViewById<TextView>(R.id.sort_recently_added)
        val recentlyWatched = contentView.findViewById<TextView>(R.id.sort_recently_watched)
        val nameAz = contentView.findViewById<TextView>(R.id.sort_name_az)
        val category = contentView.findViewById<TextView>(R.id.sort_category)

        options = listOf(
            recentlyAdded to SortMode.RECENTLY_ADDED,
            recentlyWatched to SortMode.RECENTLY_WATCHED,
            nameAz to SortMode.NAME_AZ,
            category to SortMode.CATEGORY
        )

        // Click handlers
        options.forEach { (tv, mode) ->
            tv.setOnClickListener {
                onSortSelected(mode)
                dismiss()
            }
            // Focus styling
            tv.setOnFocusChangeListener { _, hasFocus ->
                tv.setBackgroundColor(if (hasFocus) Color.parseColor("#14FFC107") else Color.TRANSPARENT)
            }
        }

        popupWindow = PopupWindow(contentView, 550, -2, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
            elevation = 20f
            animationStyle = android.R.style.Animation_Dialog
        }

        // D-pad key handling for the popup
        contentView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss()
                true
            } else false
        }
    }

    fun show(anchor: View, activeMode: SortMode) {
        currentMode = activeMode
        updateActiveHighlight()
        popupWindow.showAsDropDown(anchor, 0, 4)
        // Focus the active option
        val activeOption = options.find { it.second == activeMode }?.first
        activeOption?.requestFocus()
    }

    fun dismiss() {
        if (popupWindow.isShowing) popupWindow.dismiss()
    }

    private fun updateActiveHighlight() {
        val goldColor = Color.parseColor("#FFC107")
        val secondaryColor = Color.parseColor("#9CA3AF")
        options.forEach { (tv, mode) ->
            tv.setTextColor(if (mode == currentMode) goldColor else secondaryColor)
        }
    }
}
