package com.ooustream.iptv.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R
import com.ooustream.iptv.data.model.LiveStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Presenter for channel list items in VerticalGridView.
 *
 * Background state is handled by StateListDrawable (bg_channel_states.xml) —
 * the framework manages focus/unfocus transitions with cached drawables,
 * so there are ZERO setBackgroundResource() calls during scrolling.
 *
 * Expensive visual effects (overlay drawables, scale animation, sound, accent bar)
 * are debounced behind 60ms so rapid D-pad scrolling stays smooth.
 */
open class ChannelPresenter(
    var favoriteIds: Set<String> = emptySet()
) : Presenter() {

    /** Throttle sound during fast scrolling — minimum 80ms between sounds */
    private var lastSoundTime = 0L

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        // Pre-create focus overlay drawables once per view to avoid GC pressure
        if (DeviceUtils.isTV(parent.context)) {
            view.setTag(R.id.focus_glow_drawable, GoldGlowFocusDrawable())
            view.setTag(R.id.focus_bracket_drawable, FocusBracketDrawable())
        }
        // Explicit focus target — bypasses geometric search which fails when scrolled
        view.nextFocusLeftId = R.id.categories_list
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val channel = item as LiveStream
        val root = viewHolder.view as LinearLayout
        val logo = root.findViewById<ImageView>(R.id.channel_logo)
        val name = root.findViewById<TextView>(R.id.channel_name)
        val epg = root.findViewById<TextView>(R.id.channel_epg)
        val accentBar = root.findViewById<View>(R.id.channel_accent_bar)

        name.text = channel.name
        epg.text = "" // EPG set externally

        // Show favorites star
        val star = root.findViewById<ImageView>(R.id.channel_favorite_star)
        val isFavorite = favoriteIds.contains("live_${channel.streamId}")
        if (isFavorite) {
            star.visibility = View.VISIBLE
            star.setImageResource(R.drawable.ic_favorites)
            star.setColorFilter(ContextCompat.getColor(root.context, R.color.focus_gold))
        } else {
            star.visibility = View.GONE
        }

        val iconUrl = channel.streamIcon
        val cacheKey = "channel_${channel.streamId}"
        ProgressiveImageLoader.loadThumbnail(logo, iconUrl, cacheKey)

        root.setOnFocusChangeListener { v, hasFocus ->
            // Cancel any pending focus effect from prior focus/unfocus
            (v.getTag(R.id.focus_effect_job) as? Job)?.cancel()

            if (hasFocus) {
                // Background handled by StateListDrawable — instant, no inflation needed

                // DEFERRED: all expensive effects after 60ms of sustained focus
                val job = CoroutineScope(Dispatchers.Main).launch {
                    delay(60)
                    // Sound (throttled)
                    val now = System.currentTimeMillis()
                    if (now - lastSoundTime > 80) {
                        DpadSoundManager.getInstance()?.playMove()
                        lastSoundTime = now
                    }
                    // Overlay drawables (reused from tags)
                    val glow = v.getTag(R.id.focus_glow_drawable) as? GoldGlowFocusDrawable
                    val brackets = v.getTag(R.id.focus_bracket_drawable) as? FocusBracketDrawable
                    v.overlay.clear()
                    glow?.let { v.overlay.add(it) }
                    brackets?.let { v.overlay.add(it) }
                    // Scale + accent
                    v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(150).start()
                    accentBar.setBackgroundColor(ContextCompat.getColor(v.context, R.color.focus_gold))

                    // Full-res image after 300ms total
                    delay(240)
                    ProgressiveImageLoader.loadFullRes(logo, iconUrl)
                }
                v.setTag(R.id.focus_effect_job, job)
            } else {
                // Background handled by StateListDrawable — instant, no inflation needed
                v.overlay.clear()
                v.animate().cancel()
                v.scaleX = 1f
                v.scaleY = 1f
                accentBar.setBackgroundColor(ContextCompat.getColor(v.context, R.color.brand_cyan))
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as LinearLayout
        (root.getTag(R.id.focus_effect_job) as? Job)?.cancel()
        root.setOnFocusChangeListener(null)
    }
}
