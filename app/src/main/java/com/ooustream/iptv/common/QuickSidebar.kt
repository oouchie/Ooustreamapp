package com.ooustream.iptv.common

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ooustream.iptv.R

data class SidebarItem(
    val id: String,
    val label: String,
    val iconRes: Int
)

class QuickSidebar(context: Context) : FrameLayout(context) {

    private val panel: LinearLayout
    private val scrim: View
    private val shortcutsContainer: LinearLayout
    private val connectionDot: View
    private val connectionText: TextView
    private val density = resources.displayMetrics.density
    private val panelWidthPx = (280 * density).toInt()

    var onItemSelected: ((String) -> Unit)? = null
    var isOpen: Boolean = false
        private set
    private var previousFocus: View? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_quick_sidebar, this, true)
        panel = findViewById(R.id.sidebar_panel)
        scrim = findViewById(R.id.sidebar_scrim)
        shortcutsContainer = findViewById(R.id.sidebar_shortcuts)
        connectionDot = findViewById(R.id.connection_dot)
        connectionText = findViewById(R.id.connection_text)
        visibility = GONE

        // Initialize panel off-screen using dp-to-pixel conversion
        panel.translationX = -panelWidthPx.toFloat()

        scrim.setOnClickListener { dismiss() }

        setupItems()
    }

    private fun setupItems() {
        val items = listOf(
            SidebarItem("home", "Home", R.drawable.ic_home),
            SidebarItem("live", "Live TV", R.drawable.ic_live_tv),
            SidebarItem("vod", "Movies", R.drawable.ic_movies),
            SidebarItem("series", "Series", R.drawable.ic_series),
            SidebarItem("favorites", "Favorites", R.drawable.ic_favorites),
            SidebarItem("search", "Search", R.drawable.ic_search),
            SidebarItem("settings", "Settings", R.drawable.ic_settings)
        )

        shortcutsContainer.removeAllViews()
        items.forEach { item ->
            val itemView = LayoutInflater.from(context)
                .inflate(R.layout.item_sidebar_shortcut, shortcutsContainer, false)

            itemView.findViewById<ImageView>(R.id.shortcut_icon)
                .setImageResource(item.iconRes)
            itemView.findViewById<TextView>(R.id.shortcut_label)
                .text = item.label

            itemView.setOnClickListener {
                onItemSelected?.invoke(item.id)
                dismiss()
            }

            // Handle DPAD_RIGHT to dismiss sidebar (keeping focus management natural)
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK -> {
                            dismiss()
                            true
                        }
                        else -> false
                    }
                } else false
            }

            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.setBackgroundResource(R.drawable.bg_sidebar_item_focused)
                    v.findViewById<ImageView>(R.id.shortcut_icon)
                        ?.setColorFilter(ContextCompat.getColor(context, R.color.brand_cyan))
                    v.findViewById<TextView>(R.id.shortcut_label)
                        ?.setTextColor(ContextCompat.getColor(context, R.color.brand_cyan))
                } else {
                    v.setBackgroundColor(0x00000000)
                    v.findViewById<ImageView>(R.id.shortcut_icon)
                        ?.setColorFilter(0xFF9CA3AF.toInt())
                    v.findViewById<TextView>(R.id.shortcut_label)
                        ?.setTextColor(0xFFFFFFFF.toInt())
                }
            }

            shortcutsContainer.addView(itemView)
        }
    }

    fun show() {
        if (isOpen) return
        isOpen = true
        // Save the currently focused view so we can restore it on dismiss
        previousFocus = (context as? android.app.Activity)?.currentFocus
        visibility = VISIBLE
        scrim.visibility = VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()

        ObjectAnimator.ofFloat(panel, "translationX", -panelWidthPx.toFloat(), 0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            start()
        }

        // Focus first item after layout
        post {
            shortcutsContainer.getChildAt(0)?.requestFocus()
        }
    }

    fun dismiss() {
        if (!isOpen) return
        isOpen = false
        scrim.animate().alpha(0f).setDuration(200).start()

        ObjectAnimator.ofFloat(panel, "translationX", 0f, -panelWidthPx.toFloat()).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    visibility = GONE
                    // Restore focus to whatever was focused before sidebar opened
                    previousFocus?.requestFocus()
                    previousFocus = null
                }
            })
            start()
        }
    }

    fun updateConnectionStatus(isConnected: Boolean, isWifi: Boolean) {
        val dot = connectionDot.background as? GradientDrawable ?: GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            connectionDot.background = it
        }

        when {
            !isConnected -> {
                dot.setColor(0xFFEF4444.toInt())
                connectionText.text = "Disconnected"
            }
            isWifi -> {
                dot.setColor(0xFF22C55E.toInt())
                connectionText.text = "WiFi Connected"
            }
            else -> {
                dot.setColor(0xFFF59E0B.toInt())
                connectionText.text = "Connected"
            }
        }
    }

    /**
     * Handle key events when the sidebar is open.
     * Returns true if the event was consumed.
     */
    fun handleKeyEvent(keyCode: Int): Boolean {
        if (!isOpen) return false
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_BACK) {
            dismiss()
            return true
        }
        return false
    }
}
