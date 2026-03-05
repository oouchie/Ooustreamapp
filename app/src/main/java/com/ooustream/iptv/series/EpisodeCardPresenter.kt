package com.ooustream.iptv.series

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import coil.request.CachePolicy
import com.ooustream.iptv.R
import com.ooustream.iptv.common.PosterUrlRewriter
import com.ooustream.iptv.data.model.Episode

class EpisodeCardPresenter(
    private val onEpisodeClicked: (Episode) -> Unit
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_card, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(vh: ViewHolder, item: Any) {
        val episode = item as Episode
        val view = vh.view

        val thumbnail = view.findViewById<ImageView>(R.id.episode_thumbnail)
        val number = view.findViewById<TextView>(R.id.episode_number)
        val title = view.findViewById<TextView>(R.id.episode_title)
        val duration = view.findViewById<TextView>(R.id.episode_duration)
        val plot = view.findViewById<TextView>(R.id.episode_plot)

        number.text = "E${episode.episodeNum}"
        title.text = episode.title ?: "Episode ${episode.episodeNum}"

        // Episode thumbnail
        val imageUrl = episode.info?.movieImage
        if (!imageUrl.isNullOrBlank()) {
            thumbnail.visibility = View.VISIBLE
            thumbnail.load(PosterUrlRewriter.rewrite(imageUrl)) {
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

        view.setOnClickListener { onEpisodeClicked(episode) }

        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(150)
                .start()
            v.setBackgroundColor(if (hasFocus) 0xFF263244.toInt() else 0xFF1A2332.toInt())
        }
    }

    override fun onUnbindViewHolder(vh: ViewHolder) {}
}
