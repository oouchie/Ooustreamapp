package com.ooustream.iptv.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R
import com.ooustream.iptv.common.MediaTitleFormatter
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.Episode

/**
 * Episode rows for the TV series screen (item_episode_row_tv). Lives in a Leanback
 * VerticalGridView, so rows recycle — unlike the phone list, which is fully expanded inside a
 * NestedScrollView.
 */
class EpisodeRowTvAdapter(
    private val seriesName: () -> String,
    private val onEpisodeClicked: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeRowTvAdapter.Holder>(EpisodeRecyclerAdapter.EpisodeDiffCallback) {

    /** Episode id → real name from TMDB. Wins over the provider's "Show S01E01" title. */
    var episodeNames: Map<String, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    var watchProgressMap: Map<String, WatchProgressEntity> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            // In-place rebind only — list identity is unchanged (see EpisodeRecyclerAdapter).
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_episode_row_tv, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.er_number)
        private val title: TextView = itemView.findViewById(R.id.er_title)
        private val meta: TextView = itemView.findViewById(R.id.er_meta)
        private val plot: TextView = itemView.findViewById(R.id.er_plot)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.er_progress)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onEpisodeClicked(getItem(pos))
            }
            // The plot opens up to three lines on the focused row only; resting rows stay one line
            // so a season reads as a scannable list.
            itemView.setOnFocusChangeListener { _, hasFocus -> plot.maxLines = if (hasFocus) 3 else 1 }
        }

        fun bind(episode: Episode) {
            val ctx = itemView.context
            number.text = episode.episodeNum.toString()
            title.text = episodeNames[episode.id]
                ?: MediaTitleFormatter.episodeOwnTitle(seriesName(), episode.title)
                    .ifBlank { ctx.getString(R.string.sd_episode_n, episode.episodeNum) }

            val plotText = episode.info?.plot
            plot.visibility = if (plotText.isNullOrBlank()) View.GONE else View.VISIBLE
            plot.text = plotText
            plot.maxLines = if (itemView.hasFocus()) 3 else 1

            val progress = watchProgressMap[episode.id]
            val watched = progress?.completed == true
            // position 0 = the Up Next placeholder row, not real progress (see ContinueWatchingPresenter).
            val partial = !watched && progress != null && progress.position > 0L &&
                progress.progressPercent > 0.05f
            meta.text = when {
                watched -> ctx.getString(R.string.sd_watched)
                partial -> {
                    val leftMs = (progress!!.duration - progress.position).coerceAtLeast(0)
                    ctx.getString(R.string.sd_min_left, ((leftMs + 59_999) / 60_000).toInt().coerceAtLeast(1))
                }
                else -> formatRuntime(episode).orEmpty()
            }
            progressBar.visibility = if (partial) View.VISIBLE else View.GONE
            if (partial) progressBar.progress = (progress!!.progressPercent * 1000).toInt()
            // Watched episodes step back; the unwatched ones are what you're here for.
            val a = if (watched) 0.45f else 1f
            number.alpha = a
            title.alpha = a
        }
    }

    companion object {
        /** "55 min" / "1 h 5 min" from duration_secs, falling back to the provider's "HH:MM:SS". */
        fun formatRuntime(episode: Episode): String? {
            val secs = episode.info?.durationSecs?.takeIf { it > 0 }
                ?: episode.info?.duration?.split(":")?.mapNotNull { it.trim().toIntOrNull() }
                    ?.takeIf { it.size == 3 }?.let { (h, m, s) -> h * 3600 + m * 60 + s }
                    ?.takeIf { it > 0 }
                ?: return null
            val mins = (secs + 30) / 60
            return when {
                mins <= 60 -> "$mins min"
                mins % 60 == 0 -> "${mins / 60} h"
                else -> "${mins / 60} h ${mins % 60} min"
            }
        }
    }
}

/** Season list for the TV series screen. The current season is "activated" (quiet fill). */
class SeasonRowTvAdapter(
    private val onSeasonFocused: (SeasonTab) -> Unit,
    private val onSeasonClicked: (SeasonTab) -> Unit
) : ListAdapter<SeasonTab, SeasonRowTvAdapter.Holder>(Diff) {

    var selectedKey: String = ""
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_season_row_tv, parent, false) as TextView)

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
        init {
            tv.setOnClickListener { current()?.let(onSeasonClicked) }
            tv.setOnFocusChangeListener { _, hasFocus ->
                current()?.let { if (hasFocus) onSeasonFocused(it) }
                style(current())
            }
        }

        private fun current(): SeasonTab? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { getItem(it) }

        fun bind(tab: SeasonTab) {
            tv.text = tab.displayName
            style(tab)
        }

        private fun style(tab: SeasonTab?) {
            val isCurrent = tab?.key == selectedKey
            tv.isActivated = isCurrent
            tv.setTextColor(
                tv.context.getColor(if (isCurrent || tv.hasFocus()) R.color.sd_text else R.color.sd_text_secondary)
            )
            tv.paint.isFakeBoldText = isCurrent
            tv.invalidate()
        }
    }

    private object Diff : DiffUtil.ItemCallback<SeasonTab>() {
        override fun areItemsTheSame(o: SeasonTab, n: SeasonTab) = o.key == n.key
        override fun areContentsTheSame(o: SeasonTab, n: SeasonTab) = o == n
    }
}
