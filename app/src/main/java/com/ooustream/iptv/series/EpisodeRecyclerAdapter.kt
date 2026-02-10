package com.ooustream.iptv.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.ooustream.iptv.R
import com.ooustream.iptv.data.model.Episode

class EpisodeRecyclerAdapter(
    private val onEpisodeClicked: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeRecyclerAdapter.EpisodeViewHolder>(EpisodeDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_card, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.episode_thumbnail)
        private val number: TextView = itemView.findViewById(R.id.episode_number)
        private val title: TextView = itemView.findViewById(R.id.episode_title)
        private val duration: TextView = itemView.findViewById(R.id.episode_duration)
        private val plot: TextView = itemView.findViewById(R.id.episode_plot)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onEpisodeClicked(getItem(pos))
                }
            }
            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.02f else 1f)
                    .scaleY(if (hasFocus) 1.02f else 1f)
                    .setDuration(150)
                    .start()
                v.setBackgroundColor(if (hasFocus) 0xFF263244.toInt() else 0xFF1A2332.toInt())
            }
        }

        fun bind(episode: Episode) {
            number.text = "E${episode.episodeNum}"
            title.text = episode.title ?: "Episode ${episode.episodeNum}"

            // Episode thumbnail
            val imageUrl = episode.info?.movieImage
            if (!imageUrl.isNullOrBlank()) {
                thumbnail.visibility = View.VISIBLE
                thumbnail.load(imageUrl) {
                    crossfade(true)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    placeholder(R.color.card_bg)
                    error(R.color.card_bg)
                }
            } else {
                thumbnail.visibility = View.GONE
            }

            // Duration
            val durationText = episode.info?.duration
            if (!durationText.isNullOrBlank()) {
                duration.text = durationText
                duration.visibility = View.VISIBLE
            } else {
                duration.visibility = View.GONE
            }

            // Plot
            val plotText = episode.info?.plot
            if (!plotText.isNullOrBlank()) {
                plot.text = plotText
                plot.visibility = View.VISIBLE
            } else {
                plot.visibility = View.GONE
            }
        }
    }

    object EpisodeDiffCallback : DiffUtil.ItemCallback<Episode>() {
        override fun areItemsTheSame(oldItem: Episode, newItem: Episode): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Episode, newItem: Episode): Boolean {
            return oldItem == newItem
        }
    }
}
