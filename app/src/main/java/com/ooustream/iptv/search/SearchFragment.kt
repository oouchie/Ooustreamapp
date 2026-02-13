package com.ooustream.iptv.search

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.R
import com.ooustream.iptv.common.ChannelPresenter
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterPresenter
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import com.ooustream.iptv.series.SeriesDetailFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val viewModel: SearchViewModel by viewModels()
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is LiveStream -> {
                    val url = viewModel.buildLiveStreamUrl(item.streamId)
                    val fragment = OoustreamPlaybackFragment.newInstance(
                        streamUrl = url,
                        contentType = ContentType.LIVE,
                        streamId = item.streamId.toString(),
                        streamName = item.name
                    )
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(R.id.main_container, fragment)
                        .addToBackStack(null)
                        .commit()
                }
                is PosterItem -> {
                    when (item.type) {
                        "vod" -> {
                            val ext = item.extension ?: "mp4"
                            val url = viewModel.buildVodStreamUrl(item.id, ext)
                            val fragment = OoustreamPlaybackFragment.newInstance(
                                streamUrl = url,
                                contentType = ContentType.VOD,
                                streamId = item.id.toString(),
                                streamName = item.title
                            )
                            requireActivity().supportFragmentManager.beginTransaction()
                                .replace(R.id.main_container, fragment)
                                .addToBackStack(null)
                                .commit()
                        }
                        "series" -> {
                            val fragment = SeriesDetailFragment.newInstance(
                                seriesId = item.id,
                                seriesName = item.title
                            )
                            requireActivity().supportFragmentManager.beginTransaction()
                                .replace(R.id.main_container, fragment)
                                .addToBackStack(null)
                                .commit()
                        }
                    }
                }
                // Handle recent search string clicks
                is String -> {
                    setSearchQuery(item, true)
                }
            }
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.searchResults.collect { results ->
                        if (results != null) {
                            displaySearchResults(results)
                        } else {
                            // No active search -- show recent searches
                            displayRecentSearches()
                        }
                    }
                }

                launch {
                    viewModel.recentSearches.collect {
                        // If there are no active search results, refresh the recent list
                        if (viewModel.searchResults.value == null) {
                            displayRecentSearches()
                        }
                    }
                }
            }
        }
    }

    private fun displaySearchResults(results: com.ooustream.iptv.data.repository.SearchResults) {
        rowsAdapter.clear()

        // Empty state
        if (results.live.isEmpty() && results.vod.isEmpty() && results.series.isEmpty()) {
            val emptyAdapter = ArrayObjectAdapter(EmptyResultPresenter())
            emptyAdapter.add(getString(R.string.no_results))
            rowsAdapter.add(ListRow(HeaderItem("Search Results"), emptyAdapter))
            return
        }

        // Live TV row
        if (results.live.isNotEmpty()) {
            val liveAdapter = ArrayObjectAdapter(ChannelPresenter())
            results.live.forEach { liveAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem("Live TV"), liveAdapter))
        }

        // Movies row
        if (results.vod.isNotEmpty()) {
            val vodAdapter = ArrayObjectAdapter(PosterPresenter())
            results.vod.forEach { vod ->
                vodAdapter.add(
                    PosterItem(
                        id = vod.streamId,
                        title = vod.name,
                        imageUrl = vod.streamIcon,
                        rating = vod.rating,
                        extension = vod.containerExtension,
                        type = "vod"
                    )
                )
            }
            rowsAdapter.add(ListRow(HeaderItem("Movies"), vodAdapter))
        }

        // Series row
        if (results.series.isNotEmpty()) {
            val seriesAdapter = ArrayObjectAdapter(PosterPresenter())
            results.series.forEach { series ->
                seriesAdapter.add(
                    PosterItem(
                        id = series.seriesId,
                        title = series.name,
                        imageUrl = series.cover,
                        rating = series.rating,
                        extension = null,
                        type = "series"
                    )
                )
            }
            rowsAdapter.add(ListRow(HeaderItem("Series"), seriesAdapter))
        }
    }

    private fun displayRecentSearches() {
        rowsAdapter.clear()
        val recent = viewModel.recentSearches.value
        if (recent.isNotEmpty()) {
            val recentAdapter = ArrayObjectAdapter(RecentSearchPresenter())
            recent.forEach { entry -> recentAdapter.add(entry.query) }
            rowsAdapter.add(ListRow(HeaderItem("Recent Searches"), recentAdapter))
        }
    }

    override fun getResultsAdapter(): ObjectAdapter {
        return rowsAdapter
    }

    override fun onQueryTextChange(newQuery: String): Boolean {
        viewModel.search(newQuery)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        viewModel.search(query)
        return true
    }

    /** Simple presenter for "No results found" empty state. */
    private class EmptyResultPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val tv = TextView(parent.context).apply {
                setPadding(48, 32, 48, 32)
                textSize = 18f
                setTextColor(0xFFAAAAAA.toInt())
                isFocusable = false
            }
            return ViewHolder(tv)
        }
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            (viewHolder.view as TextView).text = item as String
        }
        override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
    }
}
