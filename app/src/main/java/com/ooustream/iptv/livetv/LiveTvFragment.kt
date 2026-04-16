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
import android.widget.FrameLayout
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
import com.ooustream.iptv.common.CategoryListAdapter
import com.ooustream.iptv.common.ChannelDisplayHelper
import com.ooustream.iptv.common.ChannelPresenter
import com.ooustream.iptv.common.ChannelSkeletonPresenter
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.safeReplaceAll
import com.ooustream.iptv.common.safeSetSelectedPosition
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FocusBracketDrawable
import com.ooustream.iptv.common.GoldGlowFocusDrawable
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.EpgProgram
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.epg.SmartEpgFiller
import com.ooustream.iptv.epg.bindEpgText
import com.ooustream.iptv.MainActivity
import com.ooustream.iptv.data.UserPlanManager
import com.ooustream.iptv.multiview.MultiViewLockedPopup
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
    @Inject lateinit var smartEpgFiller: SmartEpgFiller
    @Inject lateinit var userPlanManager: UserPlanManager

    private val viewModel: LiveTvViewModel by viewModels()
    private var previewManager: LivePreviewManager? = null
    private var previewingChannel: LiveStream? = null
    private var previewJob: Job? = null
    private var epgJob: Job? = null
    private var searchFilter = ""
    private var searchOpen = false
    private var favoriteIds: Set<String> = emptySet()
    private var categoryAdapter: CategoryListAdapter? = null

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
        val previewContainer = view.findViewById<FrameLayout>(R.id.preview_container)
        val previewFocusHint = view.findViewById<TextView>(R.id.preview_focus_hint)
        val navHints = view.findViewById<TextView>(R.id.nav_hints)

        // Header search
        val headerTitle = view.findViewById<TextView>(R.id.header_title)
        val headerClock = view.findViewById<TextClock>(R.id.header_clock)
        val headerSearchIcon = view.findViewById<ImageView>(R.id.header_search_icon)
        val headerSearchInput = view.findViewById<EditText>(R.id.header_search_input)

        // Hide TV-only UI on mobile + enable touch passthrough on Leanback grids
        if (!DeviceUtils.isTV(requireContext())) {
            navHints.visibility = View.GONE
            view.findViewById<View>(R.id.frosted_header)?.visibility = View.GONE
            // Let touch events pass directly to children (Leanback grids intercept first tap for focus)
            channelsList.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            channelsList.isFocusableInTouchMode = false
        }
        navHints.text = "OK: Watch \u2022 Long-press: Favorite \u2022 Back: Home"

        // Preview container — click to go fullscreen (focusable only when previewing)
        previewContainer.setOnClickListener {
            previewingChannel?.let { goFullscreen(it) }
        }
        // Gold border + bracket focus effect on preview panel
        previewContainer.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
                if (DeviceUtils.isTV(requireContext())) {
                    v.overlay.add(GoldGlowFocusDrawable())
                    v.overlay.add(FocusBracketDrawable())
                }
                previewFocusHint.visibility = View.VISIBLE
                navHints.text = "OK: Watch fullscreen \u2022 \u2190: Back to channels"
            } else {
                v.overlay.clear()
                previewFocusHint.visibility = View.GONE
                navHints.text = "OK: Watch \u2022 Long-press: Favorite \u2022 Back: Home"
            }
        }
        // PlayerView should NOT steal focus — container handles it
        previewPlayerView.isFocusable = false

        // Categories RecyclerView — stable adapter for smooth D-pad scrolling
        categoriesList.layoutManager = LinearLayoutManager(requireContext())
        categoriesList.setHasFixedSize(true)
        categoriesList.setItemViewCacheSize(20)
        categoriesList.itemAnimator = null  // No animations during D-pad scroll
        categoryAdapter = CategoryListAdapter { cat ->
            viewModel.selectCategory(cat.id)
            updateCategoryList(categoriesList)
        }
        categoriesList.adapter = categoryAdapter

        // Channels VerticalGridView (Leanback - handles 5000+ items)
        channelsList.setNumColumns(1)
        channelsList.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_BOTH_EDGE)
        channelsList.setWindowAlignmentOffsetPercent(40f)
        channelsList.setItemAlignmentOffsetPercent(50f)
        // Speed up rapid D-pad scrolling: disable child layout animation
        channelsList.setAnimateChildLayout(false)
        channelsList.itemAnimator = null
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

        // MultiView toggle icon in header
        val multiviewIcon = view.findViewById<ImageView>(R.id.header_multiview_icon)
        if (userPlanManager.isDeviceCapable()) {
            multiviewIcon.visibility = View.VISIBLE
            multiviewIcon.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(200).start()
                    v.alpha = 1f
                    v.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setStroke(2, 0xFFFFC107.toInt())
                        setColor(0x14FFD700)
                    }
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    v.alpha = 0.4f
                    v.background = null
                }
            }
            multiviewIcon.setOnClickListener {
                (activity as? com.ooustream.iptv.MainActivity)?.navigateToMultiView()
            }
        } else {
            multiviewIcon.visibility = View.GONE
        }

        // Header search icon toggle
        val centerLogo = view.findViewById<ImageView>(R.id.header_center_logo)
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
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos < 0 || pos >= filteredChannels.size) return@setOnClickListener
                    val channel = filteredChannels[pos]
                    // On mobile: single tap goes straight to fullscreen
                    if (!DeviceUtils.isTV(requireContext())) {
                        goFullscreen(channel)
                        return@setOnClickListener
                    }
                    if (previewingChannel?.streamId == channel.streamId) {
                        // Second tap — go fullscreen
                        goFullscreen(channel)
                    } else {
                        // First tap — start preview
                        previewJob?.cancel()
                        previewingChannel = channel
                        val url = viewModel.buildStreamUrl(channel.streamId)
                        previewPlayerView.visibility = View.VISIBLE
                        previewPlaceholder.visibility = View.GONE
                        previewManager?.startPreview(previewPlayerView, url)
                        // Preview is not D-pad focusable — second OK on the same channel
                        // goes fullscreen. Making it focusable stole focus from the channel
                        // item on the same frame as the click, causing the cursor to disappear.
                        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                // Long press for favorites
                viewHolder.itemView.setOnLongClickListener {
                    val pos = viewHolder.bindingAdapterPosition
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
                viewModel.categories.collect {
                    updateCategoryList(categoriesList)
                    // Restore category scroll position on back navigation
                    if (viewModel.savedCategoryPosition >= 0) {
                        categoriesList.scrollToPosition(viewModel.savedCategoryPosition)
                        viewModel.savedCategoryPosition = -1
                    }
                }
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
                        // Restore focus position on back navigation
                        if (viewModel.savedChannelPosition >= 0) {
                            channelsList.post {
                                channelsList.safeSetSelectedPosition(viewModel.savedChannelPosition, channelAdapter.size())
                                viewModel.savedChannelPosition = -1
                            }
                        }
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

                    // Debounce EPG load — skip during rapid scrolling
                    epgJob?.cancel()
                    epgJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(400)
                        viewModel.loadEpg(channel.streamId)
                    }

                    previewJob?.cancel()
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
                        // Auto-scroll to the currently airing program so user sees "now", not past programs
                        val nowEpoch = System.currentTimeMillis() / 1000
                        // Strategy 1: exact range match via unix timestamps
                        var scrollIndex = programs.indexOfFirst { p ->
                            val start = p.startTimestamp?.toLongOrNull() ?: return@indexOfFirst false
                            val end = p.stopTimestamp?.toLongOrNull() ?: return@indexOfFirst false
                            nowEpoch in start..end
                        }
                        // Strategy 2: exact range match via date strings (parsed as local time)
                        if (scrollIndex < 0) {
                            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            val nowDate = java.util.Date()
                            scrollIndex = programs.indexOfFirst { p ->
                                try {
                                    val start = p.start?.let { fmt.parse(it) } ?: return@indexOfFirst false
                                    val end = p.end?.let { fmt.parse(it) } ?: return@indexOfFirst false
                                    nowDate.after(start) && nowDate.before(end)
                                } catch (_: Exception) { false }
                            }
                        }
                        // Strategy 3: last program that started before now (handles gaps/missing end times)
                        if (scrollIndex < 0) {
                            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            val nowMs = System.currentTimeMillis()
                            scrollIndex = programs.indexOfLast { p ->
                                val startMs = p.startTimestamp?.toLongOrNull()?.let { it * 1000 }
                                    ?: try { p.start?.let { fmt.parse(it)?.time } } catch (_: Exception) { null }
                                startMs != null && startMs <= nowMs
                            }
                        }
                        if (scrollIndex > 0) {
                            epgList.post {
                                (epgList.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(scrollIndex, 0)
                            }
                        }
                    }

                    // Update focused channel card with EPG text (real or inferred)
                    val selectedPos = channelsList.selectedPosition
                    if (selectedPos >= 0 && selectedPos < filteredChannels.size) {
                        val channel = filteredChannels[selectedPos]
                        val itemView = channelsList.findViewHolderForAdapterPosition(selectedPos)?.itemView
                        val epgTextView = itemView?.findViewById<TextView>(R.id.channel_epg)
                        val epgTimeView = itemView?.findViewById<TextView>(R.id.epg_time)
                        val epgContainer = itemView?.findViewById<View>(R.id.epg_container)
                        val progressContainer = itemView?.findViewById<View>(R.id.epg_progress_container)
                        val progressFill = itemView?.findViewById<View>(R.id.epg_progress_fill)

                        if (programs.isNotEmpty()) {
                            val now = System.currentTimeMillis() / 1000
                            val current = programs.find { p ->
                                val start = p.startTimestamp?.toLongOrNull() ?: return@find false
                                val end = p.stopTimestamp?.toLongOrNull() ?: return@find false
                                now in start..end
                            }
                            if (current?.title != null) {
                                epgTextView?.text = current.title
                                epgTimeView?.text = ChannelDisplayHelper.formatEpgTimeCompact(current.start, current.startTimestamp)
                                epgContainer?.visibility = View.VISIBLE
                                smartEpgFiller.learnPattern(channel.streamId, channel.name, current.title!!)

                                // Progress bar
                                val startTs = current.startTimestamp?.toLongOrNull()
                                val stopTs = current.stopTimestamp?.toLongOrNull()
                                val progress = ChannelDisplayHelper.calculateEpgProgress(startTs, stopTs)
                                if (progressContainer != null && progressFill != null) {
                                    ChannelDisplayHelper.setProgressBar(progressFill, progressContainer, progress)
                                }
                            }
                        } else if (epgTextView != null) {
                            val categoryName = viewModel.categories.value
                                .find { it.categoryId == viewModel.selectedCategoryId.value }
                                ?.categoryName
                            val inferred = smartEpgFiller.getSmartEpg(
                                null, channel.streamId, channel.name, categoryName
                            )
                            bindEpgText(epgTextView, inferred)
                            // Show EPG container if inferred text is set
                            if (epgTextView.text.isNotBlank()) {
                                epgContainer?.visibility = View.VISIBLE
                                epgTimeView?.text = ""
                            } else {
                                epgContainer?.visibility = View.GONE
                            }
                            progressContainer?.visibility = View.GONE
                        }
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

            // Format time display — prefer Unix timestamp (timezone-correct)
            time.text = formatEpgTime(program)

            // Highlight current program with aurora styling
            val isCurrent = isCurrentProgram(program)
            if (isCurrent) {
                (holder.itemView as? ViewGroup)?.setBackgroundResource(R.drawable.bg_epg_current)
            } else {
                (holder.itemView as? ViewGroup)?.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        private fun formatEpgTime(program: EpgProgram): String {
            // Prefer Unix timestamp — always timezone-correct
            val epochSec = program.startTimestamp?.toLongOrNull()
            if (epochSec != null) {
                val outputFormat = SimpleDateFormat("h:mm a", Locale.US)
                return outputFormat.format(java.util.Date(epochSec * 1000))
            }
            // Fallback: parse start string as local time (Xtream servers provide local times)
            val startTime = program.start
            if (startTime.isNullOrBlank()) return ""
            return try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val date = inputFormat.parse(startTime) ?: return startTime
                val outputFormat = SimpleDateFormat("h:mm a", Locale.US)
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
            // Fallback: parse start/end strings as local time (Xtream servers provide local times)
            return try {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
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
        channelAdapter.safeReplaceAll(filteredChannels)
    }

    private fun updateCategoryList(recyclerView: RecyclerView) {
        val favoritesCat = CategoryItem(LiveTvViewModel.FAVORITES_ID, "Favorites")
        val apiCats = viewModel.categories.value
            .filter { searchFilter.isEmpty() || it.categoryName.lowercase().contains(searchFilter) }
            .map { CategoryItem(it.categoryId, it.categoryName) }
        val cats = if (searchFilter.isEmpty() || "favorites".contains(searchFilter)) {
            listOf(favoritesCat) + apiCats
        } else {
            apiCats
        }
        val emojiColors = mapOf(LiveTvViewModel.FAVORITES_ID to 0xFFEF4444.toInt())
        categoryAdapter?.updateData(cats, viewModel.selectedCategoryId.value, emojiColors)
    }

    private fun stopPreview() {
        epgJob?.cancel()
        epgJob = null
        previewJob?.cancel()
        previewJob = null
        previewManager?.release()
        previewingChannel = null
        // Disable focus on preview panel when nothing is playing
        view?.findViewById<FrameLayout>(R.id.preview_container)?.let {
            it.isFocusable = false
            it.overlay.clear()
        }
        view?.findViewById<TextView>(R.id.preview_focus_hint)?.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        // Stop preview player when leaving this screen (going to fullscreen, home, etc.)
        stopPreview()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        resumePreviewIfReturning()
    }

    private fun resumePreviewIfReturning() {
        // Priority: player's last channel (user may have switched channels in fullscreen)
        val returnedChannel = ChannelListHolder.lastPlayedChannel
        val returnedIndex = ChannelListHolder.lastPlayedIndex

        val channel: LiveStream
        val url: String

        if (returnedChannel != null && returnedIndex >= 0) {
            channel = returnedChannel
            url = viewModel.buildStreamUrl(channel.streamId)
            ChannelListHolder.lastPlayedChannel = null
            ChannelListHolder.lastPlayedIndex = -1
        } else if (viewModel.lastPreviewedChannel != null) {
            channel = viewModel.lastPreviewedChannel!!
            url = viewModel.lastPreviewedUrl ?: viewModel.buildStreamUrl(channel.streamId)
        } else {
            return // No channel to resume — normal fresh open
        }

        // Clear saved state so we don't re-resume on next onResume (e.g. app background→foreground)
        viewModel.lastPreviewedChannel = null
        viewModel.lastPreviewedUrl = null
        viewModel.lastPreviewedIndex = -1

        // Restart preview
        val previewPlayerView = view?.findViewById<PlayerView>(R.id.preview_player_view) ?: return
        val previewPlaceholder = view?.findViewById<TextView>(R.id.preview_placeholder)
        val previewContainer = view?.findViewById<FrameLayout>(R.id.preview_container)

        previewingChannel = channel
        previewPlayerView.visibility = View.VISIBLE
        previewPlaceholder?.visibility = View.GONE
        previewManager?.startPreview(previewPlayerView, url)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Scroll channel list to the resumed channel and load its EPG
        val channelsList = view?.findViewById<VerticalGridView>(R.id.channels_list)
        channelsList?.post {
            val listIndex = filteredChannels.indexOfFirst { it.streamId == channel.streamId }
            if (listIndex >= 0) {
                channelsList.selectedPosition = listIndex
            }
        }
        viewModel.loadEpg(channel.streamId)
    }

    private fun goFullscreen(channel: LiveStream) {
        val url = viewModel.buildStreamUrl(channel.streamId)
        val idx = filteredChannels.indexOf(channel).coerceAtLeast(0)

        // Save full preview state for restore on return (before stopPreview nulls everything)
        viewModel.lastPreviewedChannel = channel
        viewModel.lastPreviewedUrl = url
        viewModel.lastPreviewedIndex = idx

        // Release preview player before launching fullscreen player
        stopPreview()
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

    override fun onKeyEvent(keyCode: Int): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && searchOpen) {
            closeSearch()
            return true
        }
        return false
    }

    private fun closeSearch() {
        searchOpen = false
        val v = view ?: return
        v.findViewById<android.widget.EditText>(R.id.header_search_input)?.let {
            it.setText("")
            it.visibility = View.GONE
        }
        v.findViewById<android.widget.TextView>(R.id.header_title)?.visibility = View.VISIBLE
        v.findViewById<android.widget.TextClock>(R.id.header_clock)?.visibility = View.VISIBLE
        v.findViewById<android.widget.ImageView>(R.id.header_center_logo)?.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        // Save focus positions for restoration on back navigation
        view?.findViewById<VerticalGridView>(R.id.channels_list)?.let {
            viewModel.savedChannelPosition = it.selectedPosition
        }
        view?.findViewById<RecyclerView>(R.id.categories_list)?.let {
            viewModel.savedCategoryPosition =
                (it.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: -1
        }
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroyView()
        stopPreview()
        previewManager = null
    }
}
