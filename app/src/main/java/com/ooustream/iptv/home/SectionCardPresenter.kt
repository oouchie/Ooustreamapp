package com.ooustream.iptv.home

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.facebook.shimmer.ShimmerFrameLayout
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FocusBracketDrawable
import com.ooustream.iptv.common.GoldGlowFocusDrawable
import com.ooustream.iptv.common.dp

data class SectionItem(
    val id: String,
    val title: String,
    val iconRes: Int,
    val accentColor: Int,
    val count: String = "",
    val cta: String = "",
    val gradientStart: Int = 0,
    val gradientEnd: Int = 0
)

class SectionCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_section_card, parent, false)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val section = item as SectionItem
        val root = viewHolder.view as FrameLayout
        val icon = root.findViewById<ImageView>(R.id.section_icon)
        val title = root.findViewById<TextView>(R.id.section_title)
        val count = root.findViewById<TextView>(R.id.section_count)
        val accentBar = root.findViewById<View>(R.id.section_accent_bar)
        val gradientBg = root.findViewById<View>(R.id.section_gradient_bg)
        val cta = root.findViewById<TextView>(R.id.section_cta)
        val shimmerLine = root.findViewById<ShimmerFrameLayout>(R.id.section_shimmer_line)

        icon.setImageResource(section.iconRes)
        icon.setColorFilter(section.accentColor)
        title.text = section.title
        count.text = section.count
        accentBar.setBackgroundColor(section.accentColor)

        // Apply gradient background
        if (section.gradientStart != 0 && section.gradientEnd != 0) {
            val gradientDrawable = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(section.gradientStart, section.gradientEnd)
            ).apply {
                cornerRadius = 16f.dp
            }
            gradientBg.background = gradientDrawable
        }

        // Set CTA text
        cta.text = section.cta

        // Start sweeping gold shimmer on the accent line
        shimmerLine?.startShimmer()

        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
                if (DeviceUtils.isTV(v.context)) {
                    v.overlay.add(GoldGlowFocusDrawable())
                    v.overlay.add(FocusBracketDrawable())
                }
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
                cta.setTextColor(0xFFFFC107.toInt())   // gold
                count.setTextColor(0xCCFFFFFF.toInt()) // brighter white
            } else {
                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                cta.setTextColor(0x99FFFFFF.toInt())   // default muted white
                count.setTextColor(0xB3FFFFFF.toInt()) // default secondary white
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as FrameLayout
        root.setOnFocusChangeListener(null)
        root.findViewById<ShimmerFrameLayout>(R.id.section_shimmer_line)?.stopShimmer()
    }
}
