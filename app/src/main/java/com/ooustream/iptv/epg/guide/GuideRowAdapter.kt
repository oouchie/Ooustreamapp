package com.ooustream.iptv.epg.guide

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R
import com.ooustream.iptv.common.ChannelDisplayHelper

/**
 * Vertical guide rows. The row root is the focusable unit (D-pad UP/DOWN moves rows);
 * the lane draws its own virtual horizontal focus. DiffUtil + an EPG payload keep
 * per-row updates cheap (PERFORMANCE_AGENT mandate — no notifyDataSetChanged).
 */
class GuideRowAdapter(
    private val controller: GuideTimelineController,
    private val onRowFocused: (Int) -> Unit,
    private val onProgramClicked: (GuideRowData, GuideProgram) -> Unit,
    private val onLanePan: ((Long) -> Unit)? = null
) : ListAdapter<GuideRowData, GuideRowAdapter.GuideRowViewHolder>(DIFF) {

    /** Set of favorite ids ("live_<streamId>"). Updating repaints visible hearts only. */
    var favoriteIds: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_FAV)
        }

    companion object {
        const val PAYLOAD_EPG = "epg"
        const val PAYLOAD_FAV = "fav"

        private val DIFF = object : DiffUtil.ItemCallback<GuideRowData>() {
            override fun areItemsTheSame(a: GuideRowData, b: GuideRowData) =
                a.channel.streamId == b.channel.streamId

            override fun areContentsTheSame(a: GuideRowData, b: GuideRowData) =
                a.programs == b.programs && a.channel.name == b.channel.name &&
                    a.accentColor == b.accentColor

            override fun getChangePayload(a: GuideRowData, b: GuideRowData): Any? =
                if (a.channel.streamId == b.channel.streamId) PAYLOAD_EPG else null
        }
    }

    inner class GuideRowViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val channelNumber: TextView = root.findViewById(R.id.guide_channel_number)
        val channelName: TextView = root.findViewById(R.id.guide_channel_name)
        val channelLogo: ImageView = root.findViewById(R.id.guide_channel_logo)
        val channelInitials: TextView = root.findViewById(R.id.guide_channel_initials)
        val favoriteIcon: ImageView = root.findViewById(R.id.guide_channel_favorite)
        val laneContainer: FrameLayout = root.findViewById(R.id.guide_lane_container)
        val lane: GuideProgramLaneView = GuideProgramLaneView(root.context).also {
            it.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            laneContainer.addView(it)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuideRowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_guide_row, parent, false)
        val holder = GuideRowViewHolder(view)
        holder.lane.controller = controller

        view.setOnFocusChangeListener { v, hasFocus ->
            holder.lane.rowFocused = hasFocus
            v.setBackgroundColor(if (hasFocus) 0x14FFC107 else 0x00000000)
            if (hasFocus) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRowFocused(pos)
            }
        }
        view.setOnClickListener {
            // OK / tap on the row: act on the virtually-focused cell
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val row = getItem(pos)
            holder.lane.focusedProgram()?.let { onProgramClicked(row, it) }
        }
        // Phone: tap a specific cell, horizontal drag pans the shared timeline
        if (onLanePan != null) {
            var downX = 0f
            var panning = false
            holder.lane.setOnTouchListener { laneView, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = ev.x; panning = false; true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        if (panning || kotlin.math.abs(dx) > 24f) {
                            panning = true
                            val deltaMs = (-dx / laneView.width * controller.windowDurationMs).toLong()
                            onLanePan.invoke(deltaMs)
                            downX = ev.x
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!panning) {
                            val pos = holder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                val row = getItem(pos)
                                (laneView as GuideProgramLaneView).programAtX(ev.x)
                                    ?.let { onProgramClicked(row, it) }
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: GuideRowViewHolder, position: Int) {
        val row = getItem(position)
        holder.channelNumber.text = row.channel.num?.toString() ?: ""
        holder.channelName.text = row.channel.name
        ChannelDisplayHelper.loadLogo(
            holder.channelLogo, holder.channelInitials,
            row.channel.streamIcon, row.channel.name
        )
        holder.lane.accentColor = row.accentColor
        holder.lane.programs = row.programs
        bindFavorite(holder, row.channel.streamId)
    }

    override fun onBindViewHolder(
        holder: GuideRowViewHolder, position: Int, payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val row = getItem(position)
        for (pl in payloads) {
            when (pl) {
                PAYLOAD_EPG -> {
                    holder.lane.accentColor = row.accentColor
                    holder.lane.programs = row.programs
                }
                PAYLOAD_FAV -> bindFavorite(holder, row.channel.streamId)
            }
        }
    }

    private fun bindFavorite(holder: GuideRowViewHolder, streamId: Int) {
        if (favoriteIds.contains("live_$streamId")) {
            holder.favoriteIcon.setImageResource(R.drawable.ic_favorites)
            holder.favoriteIcon.setColorFilter(0xFFFFC107.toInt())
        } else {
            holder.favoriteIcon.setImageResource(R.drawable.ic_heart_outline)
            holder.favoriteIcon.setColorFilter(0x66FFFFFF)
        }
    }

    /** The lane of the currently focused row (for virtual-focus queries). */
    fun laneAt(recyclerView: RecyclerView, position: Int): GuideProgramLaneView? {
        val vh = recyclerView.findViewHolderForAdapterPosition(position) as? GuideRowViewHolder
        return vh?.lane
    }
}
