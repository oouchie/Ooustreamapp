package com.ooustream.iptv.multiview

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R
import com.ooustream.iptv.common.CategoryItem
import com.ooustream.iptv.common.CategoryListAdapter
import com.ooustream.iptv.data.local.dao.ChannelWatchLogDao
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.EpgCacheRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Dual-pane channel picker dialog for MultiView.
 * Left pane: category list (reuses CategoryListAdapter).
 * Right pane: channels for the selected category.
 */
@AndroidEntryPoint
class ChannelPickerDialogFragment : DialogFragment() {

    @Inject
    lateinit var epgCacheRepository: EpgCacheRepository

    @Inject
    lateinit var contentRepository: ContentRepository

    @Inject
    lateinit var favoriteRepository: FavoriteRepository

    @Inject
    lateinit var channelWatchLogDao: ChannelWatchLogDao

    companion object {
        private const val ARG_SLOT_INDEX = "slot_index"
        private const val CATEGORY_FAVORITES = "__favorites__"
        private const val CATEGORY_RECENT = "__recent__"

        fun newInstance(slotIndex: Int): ChannelPickerDialogFragment {
            return ChannelPickerDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SLOT_INDEX, slotIndex)
                }
            }
        }
    }

    private var slotIndex: Int = 0
    private var onChannelSelected: ((LiveStream) -> Unit)? = null

    private lateinit var categoriesRecyclerView: RecyclerView
    private lateinit var channelsRecyclerView: RecyclerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var categoryAdapter: CategoryListAdapter
    private lateinit var channelAdapter: ChannelPickerAdapter

    private var selectedCategoryId: String? = null
    private var categoryItems: List<CategoryItem> = emptyList()
    private var channelLoadJob: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_channel_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Make dialog full-screen with transparent background
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        // Restore slot index from arguments
        slotIndex = arguments?.getInt(ARG_SLOT_INDEX, 0) ?: 0

        // Set header text
        val titleText = view.findViewById<TextView>(R.id.picker_title)
        val subtitleText = view.findViewById<TextView>(R.id.picker_subtitle)
        titleText.text = "Select Channel"
        subtitleText.text = "Slot ${slotIndex + 1}"

        // Setup categories RecyclerView (left pane)
        categoriesRecyclerView = view.findViewById(R.id.picker_categories_list)
        categoriesRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        categoryAdapter = CategoryListAdapter { category ->
            onCategorySelected(category)
        }
        categoriesRecyclerView.adapter = categoryAdapter

        // Setup channels RecyclerView (right pane) with scroll optimizations
        channelsRecyclerView = view.findViewById(R.id.channels_list)
        channelsRecyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            initialPrefetchItemCount = 8
        }
        channelsRecyclerView.setHasFixedSize(true)
        loadingSpinner = view.findViewById(R.id.loading_spinner)

        channelAdapter = ChannelPickerAdapter(
            channels = emptyList(),
            onChannelClick = { channel ->
                onChannelSelected?.invoke(channel)
                dismiss()
            }
        )
        channelsRecyclerView.adapter = channelAdapter

        // Handle Back key to dismiss via dialog key listener
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else {
                false
            }
        }

        // Load categories
        loadCategories()
    }

    private fun onCategorySelected(category: CategoryItem) {
        if (selectedCategoryId == category.id) return

        selectedCategoryId = category.id
        // Update category adapter to show selected state
        categoryAdapter.updateData(categoryItems, selectedCategoryId)

        // Load channels based on category type
        when (category.id) {
            CATEGORY_FAVORITES -> loadFavoriteChannels()
            CATEGORY_RECENT -> loadRecentChannels()
            else -> loadChannelsForCategory(category.id)
        }
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            loadingSpinner.visibility = View.VISIBLE

            try {
                val categories = withContext(Dispatchers.IO) {
                    contentRepository.getLiveCategories()
                }

                // Virtual categories first, then server categories
                val virtualCategories = listOf(
                    CategoryItem(
                        id = CATEGORY_FAVORITES,
                        name = "\u2605 Favorites",
                        accentColor = 0xFFFFD700.toInt(),
                        isSpecial = true
                    ),
                    CategoryItem(
                        id = CATEGORY_RECENT,
                        name = "Recently Watched",
                        accentColor = 0xFF90CAF9.toInt(),
                        isSpecial = true
                    )
                )

                val serverCategories = categories.map { cat ->
                    CategoryItem(
                        id = cat.categoryId,
                        name = cat.categoryName
                    )
                }

                categoryItems = virtualCategories + serverCategories

                categoryAdapter.updateData(
                    categoryItems,
                    categoryItems.firstOrNull()?.id
                )

                // Auto-select Favorites
                val firstCategory = categoryItems.firstOrNull()
                if (firstCategory != null) {
                    selectedCategoryId = firstCategory.id
                    loadFavoriteChannels()
                } else {
                    loadingSpinner.visibility = View.GONE
                }

                // Auto-focus first category item
                categoriesRecyclerView.post {
                    categoriesRecyclerView.getChildAt(0)?.requestFocus()
                }
            } catch (e: Exception) {
                loadingSpinner.visibility = View.GONE
            }
        }
    }

    private fun loadChannelsForCategory(categoryId: String) {
        channelLoadJob?.cancel()
        channelLoadJob = lifecycleScope.launch {
            loadingSpinner.visibility = View.VISIBLE
            channelsRecyclerView.visibility = View.GONE

            try {
                val channels = withContext(Dispatchers.IO) {
                    contentRepository.getLiveStreams(categoryId)
                }

                val sortedChannels = channels.sortedBy { it.name }
                channelAdapter.updateChannels(sortedChannels)

                loadingSpinner.visibility = View.GONE
                channelsRecyclerView.visibility = View.VISIBLE

                // Load EPG data in background for visible channels
                sortedChannels.forEach { channel ->
                    launch {
                        val epg = try {
                            epgCacheRepository.getEpg(channel.streamId)
                                .firstOrNull { it.nowPlaying == 1 }
                        } catch (e: Exception) {
                            null
                        }
                        channelAdapter.updateEpg(channel.streamId, epg?.title)
                    }
                }
            } catch (e: Exception) {
                loadingSpinner.visibility = View.GONE
                channelsRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadFavoriteChannels() {
        channelLoadJob?.cancel()
        channelLoadJob = lifecycleScope.launch {
            loadingSpinner.visibility = View.VISIBLE
            channelsRecyclerView.visibility = View.GONE

            try {
                val favorites = withContext(Dispatchers.IO) {
                    favoriteRepository.getFavoritesListByType("live")
                }

                val channels = favorites.map { fav ->
                    LiveStream(
                        num = null, name = fav.name, streamType = "live",
                        streamId = fav.streamId, streamIcon = fav.icon,
                        epgChannelId = null, added = null, categoryId = fav.categoryId,
                        customSid = null, tvArchive = null, directSource = null,
                        tvArchiveDuration = null
                    )
                }

                if (channels.isEmpty()) {
                    channelAdapter.updateChannels(emptyList())
                    loadingSpinner.visibility = View.GONE
                    channelsRecyclerView.visibility = View.VISIBLE
                    return@launch
                }

                channelAdapter.updateChannels(channels)
                loadingSpinner.visibility = View.GONE
                channelsRecyclerView.visibility = View.VISIBLE

                // Load EPG for favorites
                channels.forEach { channel ->
                    launch {
                        val epg = try {
                            epgCacheRepository.getEpg(channel.streamId)
                                .firstOrNull { it.nowPlaying == 1 }
                        } catch (e: Exception) { null }
                        channelAdapter.updateEpg(channel.streamId, epg?.title)
                    }
                }
            } catch (e: Exception) {
                loadingSpinner.visibility = View.GONE
                channelsRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadRecentChannels() {
        channelLoadJob?.cancel()
        channelLoadJob = lifecycleScope.launch {
            loadingSpinner.visibility = View.VISIBLE
            channelsRecyclerView.visibility = View.GONE

            try {
                val logs = withContext(Dispatchers.IO) {
                    channelWatchLogDao.getRecentLogs()
                }

                // Deduplicate by channelId, keep most recent
                val uniqueChannels = logs
                    .sortedByDescending { it.timestamp }
                    .distinctBy { it.channelId }
                    .take(20)
                    .map { log ->
                        LiveStream(
                            num = null, name = log.channelName, streamType = "live",
                            streamId = log.channelId, streamIcon = log.channelIcon,
                            epgChannelId = null, added = null, categoryId = log.categoryId,
                            customSid = null, tvArchive = null, directSource = null,
                            tvArchiveDuration = null
                        )
                    }

                channelAdapter.updateChannels(uniqueChannels)
                loadingSpinner.visibility = View.GONE
                channelsRecyclerView.visibility = View.VISIBLE

                // Load EPG for recent channels
                uniqueChannels.forEach { channel ->
                    launch {
                        val epg = try {
                            epgCacheRepository.getEpg(channel.streamId)
                                .firstOrNull { it.nowPlaying == 1 }
                        } catch (e: Exception) { null }
                        channelAdapter.updateEpg(channel.streamId, epg?.title)
                    }
                }
            } catch (e: Exception) {
                loadingSpinner.visibility = View.GONE
                channelsRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    fun setOnChannelSelectedListener(listener: (LiveStream) -> Unit) {
        this.onChannelSelected = listener
    }

}

/**
 * RecyclerView adapter for channel picker items.
 * Each item shows LIVE dot, channel name, and EPG program.
 */
private class ChannelPickerAdapter(
    private var channels: List<LiveStream>,
    private val onChannelClick: (LiveStream) -> Unit
) : RecyclerView.Adapter<ChannelPickerAdapter.ViewHolder>() {

    private val epgCache = mutableMapOf<Int, String?>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val channelName: TextView = view.findViewById(R.id.channel_name)
        val epgProgram: TextView = view.findViewById(R.id.epg_program)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_picker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]

        holder.channelName.text = channel.name

        // Show EPG if available
        val epgTitle = epgCache[channel.streamId]
        holder.epgProgram.text = epgTitle ?: ""
        holder.epgProgram.visibility = if (epgTitle.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Click handler
        holder.itemView.setOnClickListener {
            onChannelClick(channel)
        }

        // Focus handling — focusable only (no isFocusableInTouchMode for TV)
        holder.itemView.isFocusable = true
    }

    override fun getItemCount(): Int = channels.size

    fun updateChannels(newChannels: List<LiveStream>) {
        channels = newChannels
        epgCache.clear()
        notifyDataSetChanged()
    }

    fun updateEpg(streamId: Int, epgTitle: String?) {
        epgCache[streamId] = epgTitle
        val index = channels.indexOfFirst { it.streamId == streamId }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }
}
