package com.ooustream.iptv.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.epg.ChannelNameParser
import com.ooustream.iptv.epg.InferredEpg
import com.ooustream.iptv.epg.bindEpgText
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
    var favoriteIds: Set<String> = emptySet(),
    // Cheap, synchronous "on now" resolver (rule-based inference, no DB). When set,
    // every visible row shows programming context instead of only the focused one.
    var epgResolver: ((LiveStream) -> InferredEpg?)? = null
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
        val root = viewHolder.view as FrameLayout
        val logo = root.findViewById<ImageView>(R.id.channel_logo)
        val initials = root.findViewById<TextView>(R.id.channel_initials)
        val name = root.findViewById<TextView>(R.id.channel_name)
        val epg = root.findViewById<TextView>(R.id.channel_epg)
        val epgTime = root.findViewById<TextView>(R.id.epg_time)
        val epgContainer = root.findViewById<View>(R.id.epg_container)
        val epgProgressContainer = root.findViewById<View>(R.id.epg_progress_container)
        val qualityBadge = root.findViewById<TextView>(R.id.quality_badge)
        val countryBadge = root.findViewById<TextView>(R.id.country_badge)
        val accentBar = root.findViewById<View>(R.id.channel_accent_bar)

        // Parse channel name for display
        val parsed = ChannelNameParser.parseForDisplay(channel.name)
        name.text = parsed.name

        // "On now" line for every row: cheap rule-based inference so the whole list
        // shows programming context, not just the focused row. The fragment upgrades
        // the focused card to real EPG + a live progress bar when it loads.
        epgTime.text = ""
        epgProgressContainer.visibility = View.GONE
        val inferred = epgResolver?.invoke(channel)
        if (inferred != null && inferred.title.isNotBlank()) {
            bindEpgText(epg, inferred)
            epgContainer.visibility = View.VISIBLE
        } else {
            epg.text = ""
            epgContainer.visibility = View.GONE
        }

        // Quality badge
        ChannelDisplayHelper.bindQualityBadge(qualityBadge, parsed.quality)

        // Country badge
        ChannelDisplayHelper.bindCountryBadge(countryBadge, parsed.country)

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

        // Load logo with initials fallback
        ChannelDisplayHelper.loadLogo(logo, initials, channel.streamIcon, channel.name)

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
                    v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).start()
                    accentBar.setBackgroundColor(ContextCompat.getColor(v.context, R.color.focus_gold))
                    // Gold tint on name when focused
                    name.setTextColor(0xFFFFD700.toInt())

                    // Full-res image after 300ms total
                    delay(240)
                    if (logo.visibility == View.VISIBLE) {
                        ProgressiveImageLoader.loadFullRes(logo, channel.streamIcon)
                    }
                }
                v.setTag(R.id.focus_effect_job, job)
            } else {
                // DEBOUNCED: defer clearing focus effects by 100ms. If focus comes back
                // within that window (e.g., briefly after an OK tap triggers sibling
                // focusability changes and causes a focus flicker), the next hasFocus=true
                // cancels this job and the gold cursor stays visible. Prevents the
                // "cursor disappears on first tap" bug.
                val clearJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(100)
                    v.overlay.clear()
                    v.animate().cancel()
                    v.scaleX = 1f
                    v.scaleY = 1f
                    accentBar.setBackgroundColor(ContextCompat.getColor(v.context, R.color.brand_cyan))
                    name.setTextColor(0xFFFFFFFF.toInt())
                }
                v.setTag(R.id.focus_effect_job, clearJob)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as FrameLayout
        (root.getTag(R.id.focus_effect_job) as? Job)?.cancel()
        root.setOnFocusChangeListener(null)
    }
}
