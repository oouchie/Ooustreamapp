package com.ooustream.iptv.livetv

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.common.CategoryEmoji
import com.ooustream.iptv.common.CategoryItem
import com.ooustream.iptv.common.ChannelPresenter
import com.ooustream.iptv.common.ChannelSkeletonPresenter
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.EpgProgram
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.player.ChannelListHolder
import com.ooustream.iptv.player.LivePreviewManager
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@AndroidEntryPoint
class LiveTvFragment : Fragment(), KeyEventHandler {

    @Inject lateinit var okHttpClient: OkHttpClient

    private val viewModel: LiveTvViewModel by viewModels()
    private var previewManager: LivePreviewManager? = null
    private var previewingChannel: LiveStream? = null
    private var previewJob: Job? = null
    private var searchFilter = ""
    private var favoriteIds: Set<String> = emptySet()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_live_tv, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewManager = LivePreviewManager(requireContext(), okHttpClient).apply {
            setLowBitrateMode()
        }

        // Set branded header title and color
        view.findViewById<TextView>(R.id.header_title)?.let {
            it.text = getString(R.string.live_tv)
            it.setTextColor(resources.getColor(R.color.live_blue, null))
        }

        val categoriesList = view.findViewById<RecyclerView>(R.id.categories_list)
        val channelsList = view.findViewById<VerticalGridView>(R.id.channels_list)
        val previewPlayerView = view.findViewById<PlayerView>(R.id.preview_player_view)
        val previewPlaceholder = view.findViewById<TextView>(R.id.preview_placeholder)
        val navHints = view.findViewById<TextView>(R.id.nav_hints)

        // Header search
        val headerTitle = view.findViewById<TextView>(R.id.header_title)
        val headerClock = view.findViewById<TextClock>(R.id.header_clock)
        val headerSearchIcon = view.findViewById<ImageView>(R.id.header_search_icon)
        val headerSearchInput = view.findViewById<EditText>(R.id.header_search_input)

        navHints.text = "OK: Watch \u2022 Long-press: Favorite \u2022 Back: Home"

        // Categories RecyclerView
        categoriesList.layoutManager = LinearLayoutManager(requireContext())

        // Channels VerticalGridView (Leanback - handles 5000+ items)
        channelsList.setNumColumns(1)
        channelsList.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_BOTH_EDGE)
        channelsList.setWindowAlignmentOffsetPercent(40f)
        channelsList.setItemAlignmentOffsetPercent(50f)
        val channelPresenter = ChannelPresenter()
        val channelAdapter = ArrayObjectAdapter(channelPresenter)

        // Search icon focus highlight — gold ring + scale so user sees when it's focused
        headerSearchIcon.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(200).start()
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setStroke(2, 0xFFFFC107.toInt())
                    setColor(0x33FFC107)
                }
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                v.background = null
            }
        }

        // Header search icon toggle
        val centerLogo = view.findViewById<ImageView>(R.id.header_center_logo)
        var searchOpen = false
        headerSearchIcon.setOnClickListener {
            searchOpen = !searchOpen
            if (searchOpen) {
                headerTitle?.visibility = View.GONE
                headerClock?.visibility = View.GONE
                centerLogo?.visibility = View.GONE
                headerSearchInput.visibility = View.VISIBLE
                headerSearchInput.requestFocus()
            } else {
                headerSearchInput.setText("")
                headerSearchInput.visibility = View.GONE
                headerTitle?.visibility = View.VISIBLE
                headerClock?.visibility = View.VISIBLE
                centerLogo?.visibility = View.VISIBLE
            }
        }

        // Header search filter
        headerSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchFilter = s?.toString()?.lowercase() ?: ""
                updateCategoryList(categoriesList)
                updateChannelList(channelAdapter)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Show shimmer skeletons while loading
        val skeletonAdapter = ArrayObjectAdapter(ChannelSkeletonPresenter())
        repeat(8) { skeletonAdapter.add(Unit) }
        channelsList.adapter = ItemBridgeAdapter(skeletonAdapter)

        // Build real channel bridge adapter with click/long-press listeners
        val channelBridgeAdapter = ItemBridgeAdapter(channelAdapter)
        channelBridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos < 0 || pos >= filteredChannels.size) return@setOnClickListener
                    val channel = filteredChannels[pos]
                    // Single press goes directly to fullscreen (preview auto-starts on focus)
                    goFullscreen(channel)
                }
                // Long press for favorites
                viewHolder.itemView.setOnLongClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < filteredChannels.size) {
                        val channel = filteredChannels[pos]
                        viewModel.toggleFavorite(channel)
                    }
                    true
                }
            }
        })
        var skeletonSwapped = false

        // Observe categories
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categories.collect { updateCategoryList(categoriesList) }
            }
        }

        // Observe channels
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.channels.collect { channels ->
                    updateChannelList(channelAdapter)
                    // Swap from skeleton to real adapter on first data arrival
                    if (!skeletonSwapped && channels.isNotEmpty()) {
                        channelsList.adapter = channelBridgeAdapter
                        skeletonSwapped = true
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

        // Channel selection listener - load EPG on focus + debounced preview
        channelsList.addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(
                parent: RecyclerView,
                child: RecyclerView.ViewHolder?,
                position: Int,
                subposition: Int
            ) {
                if (position >= 0 && position < filteredChannels.size) {
                    val channel = filteredChannels[position]
                    viewModel.loadEpg(channel.streamId)

                    // Cancel any pending preview from the previous focused channel
                    previewJob?.cancel()

                    // Start preview after 800ms sustained focus
                    previewJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(800)
                        previewingChannel = channel
                        val url = viewModel.buildStreamUrl(channel.streamId)
                        previewPlayerView.visibility = View.VISIBLE
                        previewPlaceholder.visibility = View.GONE
                        previewManager?.startPreview(previewPlayerView, url)
                        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                } else {
                    // Focus moved to an invalid position, cancel pending preview
                    previewJob?.cancel()
                }
            }
        })

        // EPG List
        val epgList = view.findViewById<RecyclerView>(R.id.epg_list)
        epgList.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.epgPrograms.collect { programs ->
                    if (programs.isEmpty()) {
                        epgList.adapter = EpgAdapter(emptyList())
                    } else {
                        epgList.adapter = EpgAdapter(programs)
                    }
                }
            }
        }

        // Observe favorites to show star icons on channels
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.favorites.collect { favs ->
                    favoriteIds = favs.map { it.id }.toSet()
                    channelPresenter.favoriteIds = favoriteIds
                    // Only refresh item visuals (star icon) without clearing/rebuilding the list,
                    // so the VerticalGridView keeps its scroll position and focus.
                    if (channelAdapter.size() > 0) {
                        channelAdapter.notifyArrayItemRangeChanged(0, channelAdapter.size())
                    }
                }
            }
        }

        viewModel.loadCategories()

        // Auto-restore preview when coming back from fullscreen
        viewModel.lastPreviewedStreamId?.let { streamId ->
            val channel = viewModel.channels.value.find { it.streamId == streamId }
            if (channel != null) {
                previewingChannel = channel
                val url = viewModel.buildStreamUrl(channel.streamId)
                previewPlayerView.visibility = View.VISIBLE
                previewPlaceholder.visibility = View.GONE
                previewManager?.startPreview(previewPlayerView, url)
            }
        }
    }

    // ---- EPG Adapter ----

    private class EpgAdapter(
        private val programs: List<EpgProgram>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val VIEW_TYPE_PROGRAM = 0
        private val VIEW_TYPE_EMPTY = 1

        override fun getItemCount(): Int = if (programs.isEmpty()) 1 else programs.size

        override fun getItemViewType(position: Int): Int =
            if (programs.isEmpty()) VIEW_TYPE_EMPTY else VIEW_TYPE_PROGRAM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == VIEW_TYPE_EMPTY) {
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    text = "No EPG data available"
                    setTextColor(Color.parseColor("#6B7280"))
                    gravity = android.view.Gravity.CENTER
                    textSize = 13f
                    setPadding(16, 24, 16, 24)
                }
                object : RecyclerView.ViewHolder(tv) {}
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_epg_program, parent, false)
                object : RecyclerView.ViewHolder(view) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (programs.isEmpty()) return

            val program = programs[position]
            val time = holder.itemView.findViewById<TextView>(R.id.epg_time)
            val title = holder.itemView.findViewById<TextView>(R.id.epg_title)
            val description = holder.itemView.findViewById<TextView>(R.id.epg_description)

            title.text = program.title ?: "Unknown"
            description.text = program.description ?: ""
            description.visibility = if (program.description.isNullOrBlank()) View.GONE else View.VISIBLE

            // Format time display
            time.text = formatEpgTime(program.start)

            // Highlight current program with aurora styling
            val isCurrent = isCurrentProgram(program)
            if (isCurrent) {
                (holder.itemView as? ViewGroup)?.setBackgroundResource(R.drawable.bg_epg_current)
            } else {
                (holder.itemView as? ViewGroup)?.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        private fun formatEpgTime(startTime: String?): String {
            if (startTime.isNullOrBlank()) return ""
            return try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                inputFormat.timeZone = TimeZone.getTimeZone("UTC")
                val date = inputFormat.parse(startTime) ?: return startTime
                val outputFormat = SimpleDateFormat("h:mm a", Locale.US)
                outputFormat.timeZone = TimeZone.getDefault()
                outputFormat.format(date)
            } catch (e: Exception) {
                startTime
            }
        }

        private fun isCurrentProgram(program: EpgProgram): Boolean {
            val startTs = program.startTimestamp?.toLongOrNull()
            val stopTs = program.stopTimestamp?.toLongOrNull()
            if (startTs != null && stopTs != null) {
                val now = System.currentTimeMillis() / 1000
                return now in startTs..stopTs
            }
            // Fallback: parse start/end strings
            return try {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                val now = Date()
                val start = program.start?.let { fmt.parse(it) }
                val end = program.end?.let { fmt.parse(it) }
                if (start != null && end != null) {
                    now.after(start) && now.before(end)
                } else false
            } catch (e: Exception) {
                false
            }
        }
    }

    private var filteredChannels: List<LiveStream> = emptyList()

    private fun updateChannelList(channelAdapter: ArrayObjectAdapter) {
        val channels = viewModel.channels.value
        filteredChannels = if (searchFilter.isEmpty()) {
            channels
        } else {
            channels.filter { it.name.lowercase().contains(searchFilter) }
        }
        channelAdapter.clear()
        filteredChannels.forEach { channelAdapter.add(it) }
    }

    private fun updateCategoryList(recyclerView: RecyclerView) {
        // Prepend "Favorites" as a virtual category
        val favoritesCat = CategoryItem(LiveTvViewModel.FAVORITES_ID, "Favorites")
        val apiCats = viewModel.categories.value
            .filter { searchFilter.isEmpty() || it.categoryName.lowercase().contains(searchFilter) }
            .map { CategoryItem(it.categoryId, it.categoryName) }
        val cats = if (searchFilter.isEmpty() || "favorites".contains(searchFilter)) {
            listOf(favoritesCat) + apiCats
        } else {
            apiCats
        }

        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val cat = cats[position]
                val root = holder.itemView as android.widget.LinearLayout
                val name = root.findViewById<TextView>(R.id.category_name)
                val count = root.findViewById<TextView>(R.id.category_count)
                val emoji = root.findViewById<TextView>(R.id.category_emoji)
                name.text = cat.name
                count.text = if (cat.count > 0) cat.count.toString() else ""
                emoji.text = CategoryEmoji.get(cat.name)

                // Special category colored emoji
                if (cat.id == LiveTvViewModel.FAVORITES_ID) {
                    emoji.setTextColor(0xFFEF4444.toInt())
                } else {
                    emoji.setTextColor(0xFFFFFFFF.toInt())
                }

                val isSelected = viewModel.selectedCategoryId.value == cat.id
                if (isSelected) {
                    root.setBackgroundResource(R.drawable.bg_sidebar_item_active)
                    name.setTextColor(0xFFFFC107.toInt())
                } else {
                    root.setBackgroundResource(R.drawable.bg_category_aurora)
                    name.setTextColor(0xFF9CA3AF.toInt())
                }
                root.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.setBackgroundResource(R.drawable.bg_sidebar_item_focused_v2)
                        v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).start()
                        name.setTextColor(0xFFFFC107.toInt())
                    } else {
                        if (isSelected) {
                            v.setBackgroundResource(R.drawable.bg_sidebar_item_active)
                            name.setTextColor(0xFFFFC107.toInt())
                        } else {
                            v.setBackgroundResource(R.drawable.bg_category_aurora)
                            name.setTextColor(0xFF9CA3AF.toInt())
                        }
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    }
                }
                holder.itemView.setOnClickListener {
                    viewModel.selectCategory(cat.id)
                    notifyDataSetChanged()
                }
            }
            override fun getItemCount() = cats.size
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewManager?.release()
        previewingChannel = null
    }

    override fun onPause() {
        super.onPause()
        // Stop preview player when leaving this screen (going to fullscreen, home, etc.)
        stopPreview()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun goFullscreen(channel: LiveStream) {
        val url = viewModel.buildStreamUrl(channel.streamId)
        // Save preview state for restore on back
        viewModel.lastPreviewedStreamId = channel.streamId
        // Release preview player before launching fullscreen player
        stopPreview()

        // Pass channel list for UP/DOWN zapping in fullscreen
        val idx = filteredChannels.indexOf(channel).coerceAtLeast(0)
        ChannelListHolder.channels = filteredChannels
        ChannelListHolder.currentIndex = idx

        val fragment = OoustreamPlaybackFragment.newInstance(
            streamUrl = url,
            contentType = ContentType.LIVE,
            streamId = channel.streamId.toString(),
            streamName = channel.name
        )
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
        tx.replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onKeyEvent(keyCode: Int): Boolean = false

    override fun onDestroyView() {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroyView()
        stopPreview()
        previewManager = null
    }
}
