package com.ooustream.iptv.vod

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import coil.request.CachePolicy
import com.ooustream.iptv.R
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VodDetailFragment : Fragment() {

    private val viewModel: VodDetailViewModel by viewModels()

    private var vodId: Int = 0
    private var vodName: String = ""
    private var coverUrl: String? = null
    private var containerExtension: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            vodId = it.getInt(ARG_VOD_ID, 0)
            vodName = it.getString(ARG_VOD_NAME, "") ?: ""
            coverUrl = it.getString(ARG_COVER_URL)
            containerExtension = it.getString(ARG_CONTAINER_EXT)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vod_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val backdrop = view.findViewById<ImageView>(R.id.vod_backdrop)
        val cover = view.findViewById<ImageView>(R.id.vod_cover)
        val title = view.findViewById<TextView>(R.id.vod_title)
        val rating = view.findViewById<TextView>(R.id.vod_rating)
        val genre = view.findViewById<TextView>(R.id.vod_genre)
        val year = view.findViewById<TextView>(R.id.vod_year)
        val duration = view.findViewById<TextView>(R.id.vod_duration)
        val director = view.findViewById<TextView>(R.id.vod_director)
        val cast = view.findViewById<TextView>(R.id.vod_cast)
        val plot = view.findViewById<TextView>(R.id.vod_plot)
        val playButton = view.findViewById<Button>(R.id.vod_play_button)
        val loadingIndicator = view.findViewById<ProgressBar>(R.id.loading_indicator)

        // Set title immediately from arguments
        title.text = vodName

        // Load cover poster immediately as placeholder
        if (!coverUrl.isNullOrBlank()) {
            cover.load(coverUrl) {
                crossfade(true)
                memoryCachePolicy(CachePolicy.ENABLED)
                placeholder(R.color.card_bg)
            }
            // Also use cover as initial backdrop until detail loads
            backdrop.load(coverUrl) {
                crossfade(true)
                memoryCachePolicy(CachePolicy.ENABLED)
                placeholder(R.color.card_bg)
            }
        }

        // Play button navigates to playback
        playButton.setOnClickListener {
            val ext = containerExtension ?: "mp4"
            val streamUrl = viewModel.buildStreamUrl(vodId, ext)
            val fragment = OoustreamPlaybackFragment.newInstance(
                streamUrl = streamUrl,
                contentType = ContentType.VOD,
                streamId = vodId.toString(),
                streamName = vodName,
                streamIcon = coverUrl ?: ""
            )
            requireActivity().supportFragmentManager.beginTransaction()
                .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.PLAYER) }
                .replace(R.id.main_container, fragment)
                .addToBackStack(null)
                .commit()
        }

        // Gold highlight on focus for the play button
        playButton.setOnFocusChangeListener { v, hasFocus ->
            val btn = v as Button
            if (hasFocus) {
                btn.setBackgroundColor(0xFFFFC107.toInt())
                btn.setTextColor(0xFF0A0A0A.toInt())
            } else {
                btn.setBackgroundColor(0xFF8B5CF6.toInt())
                btn.setTextColor(0xFFFFFFFF.toInt())
            }
        }

        // Observe loading state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { loading ->
                    loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
                }
            }
        }

        // Observe VOD info and populate UI
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.vodInfo.collect { info ->
                    if (info == null) return@collect
                    val detail = info.info

                    // Backdrop: prefer backdrop_path, then movieImage, then fall back to cover
                    val backdropUrl = detail?.backdropPath?.firstOrNull()
                        ?: detail?.movieImage
                        ?: coverUrl
                    if (!backdropUrl.isNullOrBlank()) {
                        backdrop.load(backdropUrl) {
                            crossfade(true)
                            memoryCachePolicy(CachePolicy.ENABLED)
                            placeholder(R.color.card_bg)
                        }
                    }

                    // Cover: prefer movieImage, then fall back to existing cover
                    val detailCover = detail?.movieImage ?: coverUrl
                    if (!detailCover.isNullOrBlank()) {
                        cover.load(detailCover) {
                            crossfade(true)
                            memoryCachePolicy(CachePolicy.ENABLED)
                            placeholder(R.color.card_bg)
                        }
                    }

                    // Update container extension from movie data if available
                    info.movieData?.containerExtension?.let { ext ->
                        containerExtension = ext
                    }

                    // Rating
                    val ratingValue = detail?.rating
                    if (!ratingValue.isNullOrBlank()) {
                        val ratingNum = ratingValue.toDoubleOrNull()
                        if (ratingNum != null && ratingNum > 0) {
                            val stars = ratingToStars(ratingNum)
                            rating.text = "$stars $ratingValue"
                        } else {
                            rating.text = ratingValue
                        }
                        rating.visibility = View.VISIBLE
                    }

                    // Genre
                    if (!detail?.genre.isNullOrBlank()) {
                        genre.text = detail?.genre
                        genre.visibility = View.VISIBLE
                    }

                    // Year from releaseDate
                    if (!detail?.releaseDate.isNullOrBlank()) {
                        // Extract year: try first 4 chars or full value
                        val yearStr = detail?.releaseDate?.take(4)
                        year.text = yearStr
                        year.visibility = View.VISIBLE
                    }

                    // Duration
                    if (!detail?.duration.isNullOrBlank()) {
                        duration.text = detail?.duration
                        duration.visibility = View.VISIBLE
                    } else if (detail?.durationSecs != null && detail.durationSecs > 0) {
                        val hours = detail.durationSecs / 3600
                        val mins = (detail.durationSecs % 3600) / 60
                        duration.text = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                        duration.visibility = View.VISIBLE
                    }

                    // Director
                    if (!detail?.director.isNullOrBlank()) {
                        director.text = "Director: ${detail?.director}"
                        director.visibility = View.VISIBLE
                    }

                    // Cast
                    if (!detail?.cast.isNullOrBlank()) {
                        cast.text = "Cast: ${detail?.cast}"
                        cast.visibility = View.VISIBLE
                    }

                    // Plot
                    if (!detail?.plot.isNullOrBlank()) {
                        plot.text = detail?.plot
                        plot.visibility = View.VISIBLE
                    }

                    // Request focus on play button once info is loaded
                    playButton.requestFocus()
                }
            }
        }

        // Observe watch progress for play button state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.watchProgress.collect { progress ->
                    if (progress == null) {
                        playButton.text = "Play"
                    } else if (progress.completed) {
                        playButton.text = "Watch Again"
                    } else if (progress.progressPercent > 0.05f) {
                        val totalSecs = (progress.position / 1000).toInt()
                        val mins = totalSecs / 60
                        val secs = totalSecs % 60
                        playButton.text = "Resume from ${mins}:${"%02d".format(secs)}"
                    } else {
                        playButton.text = "Play"
                    }
                }
            }
        }

        // Load the movie info from API
        viewModel.loadVodInfo(vodId)

        // Request focus on play button immediately (even before API returns)
        playButton.post { playButton.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshWatchProgress()
    }

    /**
     * Convert a numeric rating (0-10 scale) to star characters.
     * Returns 1-5 filled stars based on the rating.
     */
    private fun ratingToStars(rating: Double): String {
        val normalized = (rating / 2.0).coerceIn(0.0, 5.0)
        val fullStars = normalized.toInt()
        val halfStar = if (normalized - fullStars >= 0.5) 1 else 0
        val emptyStars = 5 - fullStars - halfStar
        return buildString {
            repeat(fullStars) { append("\u2605") }   // filled star
            repeat(halfStar) { append("\u2606") }     // half star (outline)
            repeat(emptyStars) { append("\u2606") }   // empty star
        }
    }

    companion object {
        private const val ARG_VOD_ID = "vod_id"
        private const val ARG_VOD_NAME = "vod_name"
        private const val ARG_COVER_URL = "cover_url"
        private const val ARG_CONTAINER_EXT = "container_extension"

        fun newInstance(
            vodId: Int,
            vodName: String,
            coverUrl: String?,
            containerExtension: String?
        ): VodDetailFragment {
            return VodDetailFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_VOD_ID, vodId)
                    putString(ARG_VOD_NAME, vodName)
                    putString(ARG_COVER_URL, coverUrl)
                    putString(ARG_CONTAINER_EXT, containerExtension)
                }
            }
        }
    }
}
