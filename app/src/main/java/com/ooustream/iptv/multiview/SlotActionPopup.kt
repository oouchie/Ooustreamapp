package com.ooustream.iptv.multiview

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

enum class SlotAction { CHANGE, SWAP, REMOVE }

/**
 * Long-press action menu for occupied MultiView slots.
 * D-pad navigable: Up/Down between items, OK selects, Back dismisses.
 */
class SlotActionPopup(
    private val context: Context,
    private val onAction: (SlotAction) -> Unit
) {
    private val popupWindow: PopupWindow
    private val items: List<Pair<TextView, SlotAction>>

    init {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F712121C"))
                cornerRadius = 12f * context.resources.displayMetrics.density
            }
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * context.resources.displayMetrics.density).toInt(),
                pad, (8 * context.resources.displayMetrics.density).toInt())
        }

        val actions = listOf(
            "Change Channel" to SlotAction.CHANGE,
            "Swap Position" to SlotAction.SWAP,
            "Remove Channel" to SlotAction.REMOVE
        )

        val density = context.resources.displayMetrics.density
        items = actions.map { (label, action) ->
            val tv = TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor("#9CA3AF"))
                val itemPad = (12 * density).toInt()
                setPadding(itemPad, itemPad, itemPad, itemPad)
                isFocusable = true
                isFocusableInTouchMode = true
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            tv.setOnClickListener {
                onAction(action)
                dismiss()
            }
            tv.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    (v as TextView).setTextColor(Color.parseColor("#FFC107"))
                    v.setBackgroundColor(Color.parseColor("#14FFC107"))
                } else {
                    (v as TextView).setTextColor(Color.parseColor("#9CA3AF"))
                    v.setBackgroundColor(Color.TRANSPARENT)
                }
            }
            container.addView(tv)
            tv to action
        }

        val width = (240 * density).toInt()
        popupWindow = PopupWindow(container, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
            elevation = 24f
            animationStyle = android.R.style.Animation_Dialog
        }

        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss()
                true
            } else false
        }
    }

    fun show(anchor: View) {
        popupWindow.showAtLocation(anchor, Gravity.CENTER, 0, 0)
        items.firstOrNull()?.first?.requestFocus()
    }

    fun dismiss() {
        if (popupWindow.isShowing) popupWindow.dismiss()
    }
}
