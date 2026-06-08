package com.ooustream.iptv.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.Episode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EpisodeRecyclerAdapter(
    private val onEpisodeClicked: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeRecyclerAdapter.EpisodeViewHolder>(EpisodeDiffCallback) {

    /** Map of episode streamId -> watch progress. Set externally, triggers rebind. */
    var watchProgressMap: Map<String, WatchProgressEntity> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            // Rebind visible rows IN PLACE so the watched-check / progress-bar refresh.
            // Do NOT submitList(null)+submitList(current): the null submit empties the
            // adapter for a frame, collapsing the RecyclerView to 0 height and jolting the
            // parent NestedScrollView on every onResume (return from playback). The list
            // identity/count is unchanged here — only per-row progress visuals change.
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_card, parent, false)
        view.isFocusable = true
        // TV only: a focusableInTouchMode row inside the detail NestedScrollView makes the
        // first tap a focus event that auto-scrolls the page to the row. On phone, let the
        // click fire without grabbing focus.
        view.isFocusableInTouchMode = DeviceUtils.isTV(parent.context)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.episode_thumbnail)
        private val watchedCheck: ImageView = itemView.findViewById(R.id.episode_watched_check)
        private val number: TextView = itemView.findViewById(R.id.episode_number)
        private val title: TextView = itemView.findViewById(R.id.episode_title)
        private val date: TextView = itemView.findViewById(R.id.episode_date)
        private val duration: TextView = itemView.findViewById(R.id.episode_duration)
        private val plot: TextView = itemView.findViewById(R.id.episode_plot)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.episode_progress_bar)

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
                v.setBackgroundColor(if (hasFocus) 0xFF263244.toInt() else 0x00000000.toInt())
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

            // Date — prefer releaseDate from EpisodeInfo, fallback to added (Unix timestamp)
            val dateText = formatEpisodeDate(episode)
            if (dateText != null) {
                date.text = dateText
                date.visibility = View.VISIBLE
            } else {
                date.visibility = View.GONE
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

            // Watch progress indicators
            val progress = watchProgressMap[episode.id]
            if (progress != null && progress.completed) {
                // Fully watched — show checkmark, dim the row
                watchedCheck.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                title.alpha = 0.6f
                number.alpha = 0.6f
            } else if (progress != null && progress.progressPercent > 0.05f) {
                // Partially watched — show progress bar
                watchedCheck.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                progressBar.progress = (progress.progressPercent * 1000).toInt()
                title.alpha = 1f
                number.alpha = 1f
            } else {
                // Not watched
                watchedCheck.visibility = View.GONE
                progressBar.visibility = View.GONE
                title.alpha = 1f
                number.alpha = 1f
            }
        }
    }

    companion object {
        private val displayFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

        fun formatEpisodeDate(episode: Episode): String? {
            // 1. Try EpisodeInfo.releaseDate (human-readable, e.g. "2024-03-15")
            episode.info?.releaseDate?.takeIf { it.isNotBlank() }?.let { raw ->
                return try {
                    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)
                    if (parsed != null) displayFormat.format(parsed) else raw
                } catch (_: Exception) { raw }
            }
            // 2. Fallback to Episode.added (Unix timestamp string)
            episode.added?.takeIf { it.isNotBlank() }?.let { raw ->
                return try {
                    val epoch = raw.toLong()
                    displayFormat.format(Date(epoch * 1000))
                } catch (_: Exception) { null }
            }
            return null
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
