package com.ooustream.iptv.favorites

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R

class LiveScoreCardAdapter(
    private val onClick: (FavoriteDisplayItem) -> Unit
) : ListAdapter<FavoriteDisplayItem, LiveScoreCardAdapter.ScoreViewHolder>(ScoreDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScoreViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_score_card, parent, false)
        return ScoreViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScoreViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ScoreViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelName: TextView = itemView.findViewById(R.id.score_channel_name)
        private val quarterBadge: TextView = itemView.findViewById(R.id.score_quarter_badge)
        private val scoreText: TextView = itemView.findViewById(R.id.score_text)

        fun bind(item: FavoriteDisplayItem) {
            channelName.text = item.name
            scoreText.text = item.score ?: ""

            if (!item.scoreDetail.isNullOrBlank()) {
                quarterBadge.text = item.scoreDetail
                quarterBadge.visibility = View.VISIBLE
            } else {
                quarterBadge.visibility = View.GONE
            }

            // Focus: gold border on TV
            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundResource(
                    if (hasFocus) R.drawable.bg_score_card_focused else R.drawable.bg_score_card
                )
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }

    class ScoreDiffCallback : DiffUtil.ItemCallback<FavoriteDisplayItem>() {
        override fun areItemsTheSame(old: FavoriteDisplayItem, new: FavoriteDisplayItem): Boolean {
            return old.id == new.id
        }

        override fun areContentsTheSame(old: FavoriteDisplayItem, new: FavoriteDisplayItem): Boolean {
            return old.score == new.score && old.scoreDetail == new.scoreDetail
        }
    }
}
