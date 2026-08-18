package com.ooustream.iptv.series

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.PosterUrlRewriter
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.common.safeReplaceAll
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.Episode
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SeriesDetailFragment : Fragment() {

    companion object {
        private const val TAG = "SeriesDetail"
        private const val ARG_SERIES_ID = "series_id"
        private const val ARG_SERIES_NAME = "series_name"

        fun newInstance(seriesId: Int, seriesName: String): SeriesDetailFragment {
            return SeriesDetailFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SERIES_ID, seriesId)
                    putString(ARG_SERIES_NAME, seriesName)
                }
            }
        }
    }

    private val viewModel: SeriesDetailViewModel by viewModels()
    private var seriesId: Int = 0
    private var seriesName: String = ""

    // Views
    private lateinit var backdrop: ImageView
    private lateinit var cover: ImageView
    private lateinit var titleView: TextView
    private lateinit var ratingView: TextView
    private lateinit var genreView: TextView
    private lateinit var releaseView: TextView
    private lateinit var castView: TextView
    private lateinit var plotView: TextView
    private lateinit var seasonsLabel: TextView
    private lateinit var seasonsRow: HorizontalGridView
    private lateinit var episodesLabel: TextView
    private lateinit var episodesList: RecyclerView
    private lateinit var loadingIndicator: ProgressBar

    // Adapters
    private val seasonPresenter = SeasonTabPresenter { key ->
        viewModel.selectSeason(key)
    }
    private val seasonsAdapter = ArrayObjectAdapter(seasonPresenter)

    // Standard RecyclerView adapter for episodes
    private val episodesRvAdapter = EpisodeRecyclerAdapter { episode -> playEpisode(episode) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seriesId = arguments?.getInt(ARG_SERIES_ID, 0) ?: 0
        seriesName = arguments?.getString(ARG_SERIES_NAME, "") ?: ""
        Log.i(TAG, "onCreate: seriesId=$seriesId, seriesName=$seriesName")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_series_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // v3.7.11: phone-only back-arrow toolbar overlay.
        com.ooustream.iptv.common.PhoneToolbarHelper.attach(
            this, view as android.view.ViewGroup, seriesName
        )
        bindViews(view)
        setupAdapters()
        observeViewModel()
        viewModel.loadSeriesInfo(seriesId)
    }

    private fun bindViews(view: View) {
        backdrop = view.findViewById(R.id.series_backdrop)
        cover = view.findViewById(R.id.series_cover)
        titleView = view.findViewById(R.id.series_title)
        ratingView = view.findViewById(R.id.series_rating)
        genreView = view.findViewById(R.id.series_genre)
        releaseView = view.findViewById(R.id.series_release)
        castView = view.findViewById(R.id.series_cast)
        plotView = view.findViewById(R.id.series_plot)
        seasonsLabel = view.findViewById(R.id.seasons_label)
        seasonsRow = view.findViewById(R.id.seasons_row)
        episodesLabel = view.findViewById(R.id.episodes_label)
        episodesList = view.findViewById(R.id.episodes_list)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
    }

    private fun setupAdapters() {
        seasonsRow.adapter = ItemBridgeAdapter(seasonsAdapter)
        episodesList.layoutManager = LinearLayoutManager(requireContext())
        episodesList.adapter = episodesRvAdapter
    }

    override fun onResume() {
        super.onResume()
        // Refresh watch progress when returning from player
        viewModel.refreshWatchProgress()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { collectLoading() }
                launch { collectSeriesInfo() }
                launch { collectSeasonTabs() }
                launch { collectSelectedSeason() }
                launch { collectEpisodes() }
                launch { collectWatchProgress() }
                launch { collectErrors() }
            }
        }
    }

    private suspend fun collectLoading() {
        viewModel.isLoading.collect { isLoading ->
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private suspend fun collectSeriesInfo() {
        viewModel.seriesInfo.collect { info ->
            if (info == null) return@collect

            val detail = info.info

            // Backdrop image
            val backdropUrl = detail?.backdropPath?.firstOrNull() ?: detail?.cover
            if (!backdropUrl.isNullOrBlank()) {
                backdrop.load(PosterUrlRewriter.rewriteBackdrop(backdropUrl)) {
                    crossfade(true)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    placeholder(R.color.card_bg)
                    error(R.color.card_bg)
                }
            }

            // Cover poster
            val coverUrl = detail?.cover
            if (!coverUrl.isNullOrBlank()) {
                cover.load(PosterUrlRewriter.rewrite(coverUrl)) {
                    crossfade(true)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    placeholder(R.color.card_bg)
                    error(R.color.card_bg)
                }
            }

            // Title
            titleView.text = detail?.name ?: seriesName

            // Rating
            val rating = detail?.rating5based
            if (rating != null && rating > 0) {
                ratingView.text = getString(R.string.rating_format, rating)
                ratingView.visibility = View.VISIBLE
            } else {
                ratingView.visibility = View.GONE
            }

            // Genre
            if (!detail?.genre.isNullOrBlank()) {
                genreView.text = detail?.genre
                genreView.visibility = View.VISIBLE
            } else {
                genreView.visibility = View.GONE
            }

            // Release date
            if (!detail?.releaseDate.isNullOrBlank()) {
                releaseView.text = detail?.releaseDate
                releaseView.visibility = View.VISIBLE
            } else {
                releaseView.visibility = View.GONE
            }

            // Cast
            if (!detail?.cast.isNullOrBlank()) {
                castView.text = detail?.cast
                castView.visibility = View.VISIBLE
            } else {
                castView.visibility = View.GONE
            }

            // Plot
            if (!detail?.plot.isNullOrBlank()) {
                plotView.text = detail?.plot
                plotView.visibility = View.VISIBLE
            } else {
                plotView.visibility = View.GONE
            }

        }
    }

    private suspend fun collectSeasonTabs() {
        viewModel.seasonTabs.collect { tabs ->
            Log.i(TAG, "collectSeasonTabs: received ${tabs.size} tabs")
            seasonsAdapter.safeReplaceAll(tabs)
            if (tabs.isNotEmpty()) {
                seasonsLabel.visibility = View.VISIBLE
                seasonsRow.visibility = View.VISIBLE
                // TV only: auto-focus the seasons row so the D-pad lands somewhere useful.
                // On a phone this requestFocus makes the parent NestedScrollView auto-scroll
                // to the (below-the-header) seasons row on load, yanking the poster/title
                // off-screen. Touch users don't need auto-focus.
                if (DeviceUtils.isTV(requireContext())) {
                    seasonsRow.post {
                        if (seasonsAdapter.size() > 0) {
                            seasonsRow.requestFocus()
                        }
                    }
                }
            }
        }
    }

    private suspend fun collectSelectedSeason() {
        viewModel.selectedSeasonKey.collect { key ->
            seasonPresenter.selectedKey = key
            if (seasonsAdapter.size() > 0) {
                seasonsAdapter.notifyArrayItemRangeChanged(0, seasonsAdapter.size())
            }
        }
    }

    private suspend fun collectEpisodes() {
        viewModel.episodes.collect { episodes ->
            Log.i(TAG, "collectEpisodes: received ${episodes.size} episodes")
            if (episodes.isNotEmpty()) {
                episodesLabel.visibility = View.VISIBLE
                episodesList.visibility = View.VISIBLE
                episodesRvAdapter.submitList(episodes) {
                    // Callback fires after DiffUtil completes
                    Log.i(TAG, "submitList done: adapter itemCount=${episodesRvAdapter.itemCount}")
                    episodesList.post {
                        Log.i(TAG, "RV layout: w=${episodesList.width} h=${episodesList.height} vis=${episodesList.visibility} children=${episodesList.childCount}")
                        Log.i(TAG, "Episodes label vis=${episodesLabel.visibility} w=${episodesLabel.width} h=${episodesLabel.height}")
                    }
                }
                Log.i(TAG, "collectEpisodes: submitted ${episodes.size} items to RecyclerView adapter")

                // TV only — same reason as the seasons row above: on a phone this auto-scrolls
                // the NestedScrollView down to the episode list on load.
                if (DeviceUtils.isTV(requireContext())) {
                    episodesList.post {
                        if (!seasonsRow.hasFocus() && episodesRvAdapter.itemCount > 0) {
                            episodesList.requestFocus()
                        }
                    }
                }
            } else {
                episodesLabel.visibility = View.GONE
                episodesList.visibility = View.GONE
                episodesRvAdapter.submitList(emptyList())
            }
        }
    }

    private suspend fun collectWatchProgress() {
        viewModel.episodeWatchProgress.collect { progressMap ->
            episodesRvAdapter.watchProgressMap = progressMap
        }
    }

    private suspend fun collectErrors() {
        viewModel.error.collect { errorMsg ->
            Log.e(TAG, "Error from ViewModel: $errorMsg")
            Toast.makeText(requireContext(), "Series error: $errorMsg", Toast.LENGTH_LONG).show()
        }
    }

    private fun playEpisode(episode: Episode) {
        // Null means the provider's listing for this episode has no usable stream id. Say so here
        // rather than launching the player at a bogus URL and letting the panel's reply masquerade
        // as a playback failure.
        val url = viewModel.buildEpisodeUrl(episode) ?: run {
            Toast.makeText(
                requireContext(),
                "This episode isn't available from your provider.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val iconUrl = episode.info?.movieImage
            ?: viewModel.seriesInfo.value?.info?.cover ?: ""
        val episodeId = episode.id ?: ""
        val epName = com.ooustream.iptv.common.MediaTitleFormatter.episodeTitle(
            seriesName, episode.title, episode.episodeNum, seasonNum = episode.season ?: 0
        )
        val progress = viewModel.episodeWatchProgress.value[episodeId]
        com.ooustream.iptv.common.ResumePlaybackHelper.showIfNeeded(
            context = requireContext(),
            progress = progress
        ) { forceBeginning ->
            val fragment = OoustreamPlaybackFragment.newInstance(
                streamUrl = url,
                contentType = ContentType.SERIES,
                streamId = episodeId,
                streamName = epName,
                streamIcon = iconUrl,
                seriesId = seriesId,
                seasonNum = episode.season ?: 0,
                episodeNum = episode.episodeNum,
                forceStartFromBeginning = forceBeginning,
                seriesName = seriesName
            )
            requireActivity().supportFragmentManager.beginTransaction().apply {
                FragmentTransitions.apply(this, TransitionDirection.PLAYER)
                replace(R.id.main_container, fragment)
                addToBackStack(null)
            }.commit()
        }
    }

}
