package com.ooustream.iptv.multiview

import android.view.View
import android.widget.TextView
import com.ooustream.iptv.R

/**
 * Controller for MultiView top bar UI.
 * Wires layout button clicks and updates visual state based on ViewModel.
 */
class MultiViewTopBarController(private val rootView: View) {

    // Layout selector buttons
    private val btnLayoutQuad: TextView = rootView.findViewById(R.id.btn_layout_quad)
    private val btnLayoutMain3: TextView = rootView.findViewById(R.id.btn_layout_main3)
    private val btnLayoutDual: TextView = rootView.findViewById(R.id.btn_layout_dual)
    private val btnLayoutTriple: TextView = rootView.findViewById(R.id.btn_layout_triple)

    // Status indicators
    private val streamCountText: TextView = rootView.findViewById(R.id.stream_count)

    // All layout buttons in order
    private val layoutButtons = arrayOf(btnLayoutQuad, btnLayoutMain3, btnLayoutDual, btnLayoutTriple)

    // Active/inactive colors
    private val goldColor = 0xFFFFD700.toInt()
    private val dimWhiteColor = 0x66FFFFFF

    /**
     * Update layout mode button styles.
     * Highlights the active button with gold background and text.
     */
    fun updateLayoutMode(mode: LayoutMode) {
        val modes = arrayOf(LayoutMode.QUAD, LayoutMode.MAIN_3, LayoutMode.DUAL, LayoutMode.TRIPLE)

        layoutButtons.forEachIndexed { index, btn ->
            if (modes[index] == mode) {
                // Active state: gold background + gold text
                btn.setBackgroundResource(R.drawable.bg_layout_btn_active)
                btn.setTextColor(goldColor)
            } else {
                // Inactive state: no background + dim white text
                btn.background = null
                btn.setTextColor(dimWhiteColor)
            }
        }
    }

    /**
     * Update stream count display.
     * Shows "N Stream(s) Active" text.
     */
    fun updateStreamCount(count: Int) {
        val plural = if (count != 1) "s" else ""
        streamCountText.text = "$count Stream$plural Active"
    }

    /**
     * Wire layout button clicks to a listener.
     * Listener receives the selected LayoutMode.
     * Also adds focus visual feedback (gold border glow on focus).
     */
    fun setLayoutClickListener(listener: (LayoutMode) -> Unit) {
        val modes = arrayOf(LayoutMode.QUAD, LayoutMode.MAIN_3, LayoutMode.DUAL, LayoutMode.TRIPLE)

        layoutButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                listener(modes[index])
            }
            // Focus visual feedback — scale + gold text highlight
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.15f else 1.0f)
                    .scaleY(if (hasFocus) 1.15f else 1.0f)
                    .setDuration(150)
                    .start()
                if (hasFocus) {
                    (v as TextView).setTextColor(goldColor)
                }
                // Don't reset text color on unfocus — updateLayoutMode handles active/inactive styling
            }
        }
    }
}
