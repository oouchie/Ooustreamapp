package com.ooustream.iptv.favorites

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.R
import com.ooustream.iptv.common.ChannelPresenter
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterPresenter
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import com.ooustream.iptv.series.SeriesDetailFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FavoritesFragment : RowsSupportFragment() {

    private val viewModel: FavoritesViewModel by viewModels()

    private lateinit var rowsAdapter: ArrayObjectAdapter

    private lateinit var allAdapter: ArrayObjectAdapter
    private lateinit var liveAdapter: ArrayObjectAdapter
    private lateinit var vodAdapter: ArrayObjectAdapter
    private lateinit var seriesAdapter: ArrayObjectAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRows()
        setupClickListeners()
        observeFavorites()
    }

    private fun setupRows() {
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter

        // Removable presenters: add long-press → remove confirmation
        val removablePoster = RemovablePosterPresenter { item ->
            confirmRemove(item.title, item.id.toString())
        }
        val removableChannel = RemovableChannelPresenter { channel ->
            confirmRemove(channel.name, channel.streamId.toString())
        }

        // The "All Favorites" row uses a ClassPresenterSelector so it can display
        // both LiveStream items (via ChannelPresenter) and PosterItem items
        // (via PosterPresenter) in the same horizontal list.
        val mixedSelector = ClassPresenterSelector().apply {
            addClassPresenter(LiveStream::class.java, RemovableChannelPresenter { channel ->
                confirmRemove(channel.name, channel.streamId.toString())
            })
            addClassPresenter(PosterItem::class.java, RemovablePosterPresenter { item ->
                confirmRemove(item.title, item.id.toString())
            })
        }
        allAdapter = ArrayObjectAdapter(mixedSelector)
        liveAdapter = ArrayObjectAdapter(removableChannel)
        vodAdapter = ArrayObjectAdapter(removablePoster)
        seriesAdapter = ArrayObjectAdapter(RemovablePosterPresenter { item ->
            confirmRemove(item.title, item.id.toString())
        })

        rowsAdapter.add(ListRow(HeaderItem(0, "All Favorites"), allAdapter))
        rowsAdapter.add(ListRow(HeaderItem(1, "Live TV"), liveAdapter))
        rowsAdapter.add(ListRow(HeaderItem(2, "Movies"), vodAdapter))
        rowsAdapter.add(ListRow(HeaderItem(3, "Series"), seriesAdapter))
    }

    private fun confirmRemove(name: String, streamId: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.remove_favorite_title)
            .setMessage(getString(R.string.remove_favorite_message, name))
            .setPositiveButton(R.string.remove) { _, _ ->
                viewModel.removeFavorite(streamId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupClickListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is LiveStream -> {
                    val url = viewModel.buildLiveUrl(item.streamId)
                    navigateToPlayer(url, ContentType.LIVE, item.streamId.toString(), item.name, item.streamIcon ?: "")
                }
                is PosterItem -> {
                    if (item.type == "series") {
                        navigateToSeriesDetail(item.id, item.title)
                    } else {
                        val ext = item.extension ?: "mp4"
                        val url = viewModel.buildVodUrl(item.id, ext)
                        navigateToPlayer(url, ContentType.VOD, item.id.toString(), item.title, item.imageUrl ?: "")
                    }
                }
            }
        }
    }

    private fun observeFavorites() {
        // Observe all favorites
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allFavorites.collect { favorites ->
                    allAdapter.clear()
                    if (favorites.isEmpty()) {
                        showEmptyState()
                    } else {
                        hideEmptyState()
                        favorites.forEach { entity ->
                            allAdapter.add(entityToPresenterItem(entity))
                        }
                    }
                }
            }
        }

        // Observe live favorites
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveFavorites.collect { favorites ->
                    liveAdapter.clear()
                    favorites.forEach { entity ->
                        liveAdapter.add(entityToLiveStream(entity))
                    }
                }
            }
        }

        // Observe vod favorites
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.vodFavorites.collect { favorites ->
                    vodAdapter.clear()
                    favorites.forEach { entity ->
                        vodAdapter.add(entityToPosterItem(entity))
                    }
                }
            }
        }

        // Observe series favorites
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.seriesFavorites.collect { favorites ->
                    seriesAdapter.clear()
                    favorites.forEach { entity ->
                        seriesAdapter.add(entityToPosterItem(entity))
                    }
                }
            }
        }

        // Observe toast events
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toastEvent.collect { msg ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- Conversion helpers ---

    private fun entityToLiveStream(entity: FavoriteEntity): LiveStream {
        return LiveStream(
            num = null,
            name = entity.name,
            streamType = null,
            streamId = entity.streamId,
            streamIcon = entity.icon,
            epgChannelId = null,
            added = null,
            categoryId = entity.categoryId,
            customSid = null,
            tvArchive = null,
            directSource = null,
            tvArchiveDuration = null
        )
    }

    private fun entityToPosterItem(entity: FavoriteEntity): PosterItem {
        return PosterItem(
            id = entity.streamId,
            title = entity.name,
            imageUrl = entity.icon,
            rating = null,
            extension = entity.extra,
            type = entity.type
        )
    }

    /**
     * Converts a FavoriteEntity to the appropriate presenter item based on its type.
     * LiveStream for "live" type, PosterItem for "vod" and "series".
     * Used by the "All Favorites" row with ClassPresenterSelector.
     */
    private fun entityToPresenterItem(entity: FavoriteEntity): Any {
        return when (entity.type) {
            "live" -> entityToLiveStream(entity)
            else -> entityToPosterItem(entity)
        }
    }

    // --- Navigation ---

    private fun navigateToPlayer(
        streamUrl: String,
        contentType: ContentType,
        streamId: String,
        streamName: String,
        streamIcon: String = ""
    ) {
        val fragment = OoustreamPlaybackFragment.newInstance(
            streamUrl = streamUrl,
            contentType = contentType,
            streamId = streamId,
            streamName = streamName,
            streamIcon = streamIcon
        )
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
        tx.replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToSeriesDetail(seriesId: Int, seriesName: String) {
        val fragment = SeriesDetailFragment.newInstance(seriesId, seriesName)
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
        tx.replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    // --- Empty state ---

    private var emptyRow: ListRow? = null

    private fun showEmptyState() {
        if (emptyRow != null) return
        val emptyAdapter = ArrayObjectAdapter(EmptyPresenter())
        emptyAdapter.add("No favorites yet")
        emptyRow = ListRow(HeaderItem("Favorites"), emptyAdapter)
        rowsAdapter.add(0, emptyRow!!)
    }

    private fun hideEmptyState() {
        emptyRow?.let {
            rowsAdapter.remove(it)
            emptyRow = null
        }
    }

    /**
     * Simple presenter for the empty state placeholder text.
     */
    private class EmptyPresenter : Presenter() {

        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val textView = TextView(parent.context).apply {
                setPadding(48, 32, 48, 32)
                textSize = 18f
                setTextColor(0xFFAAAAAA.toInt())
                isFocusable = false
            }
            return ViewHolder(textView)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            (viewHolder.view as TextView).text = item as String
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
    }

    /** PosterPresenter that adds long-click → remove callback. */
    private class RemovablePosterPresenter(
        private val onLongClick: (PosterItem) -> Unit
    ) : PosterPresenter() {
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            super.onBindViewHolder(viewHolder, item)
            viewHolder.view.setOnLongClickListener {
                onLongClick(item as PosterItem)
                true
            }
        }
    }

    /** ChannelPresenter that adds long-click → remove callback. */
    private class RemovableChannelPresenter(
        private val onLongClick: (LiveStream) -> Unit
    ) : ChannelPresenter() {
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            super.onBindViewHolder(viewHolder, item)
            viewHolder.view.setOnLongClickListener {
                onLongClick(item as LiveStream)
                true
            }
        }
    }
}
