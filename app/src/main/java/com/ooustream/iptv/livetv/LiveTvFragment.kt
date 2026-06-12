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
import com.ooustream.iptv.epg.ChannelNameParser
import com.ooustream.iptv.epg.EpgSource
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
    private var lowMemoryDevice = false
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

        // Device tier: skip auto-preview on low-memory sticks (mt8695), like the hero trailer
        val am = requireContext().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        lowMemoryDevice = am.memoryClass <= 128

        // Header search
        val headerTitle = view.findViewById<TextView>(R.id.header_title)
        val headerClock = view.findViewById<TextClock>(R.id.header_clock)
        val headerSearchIcon = view.findViewById<ImageView>(R.id.header_search_icon)
        val headerSearchInput = view.findViewById<EditText>(R.id.header_search_input)

        // Phone: re-stack the 10-foot 3-panel layout for a portrait screen.
        //  - KEEP the header visible (it carries the channel-filter search + MultiView entry —
        //    the bottom nav has neither). The content already clears it via screen_top_clearance.
        //  - DROP the 45%-wide preview panel: on phone the preview never auto-starts and tapping
        //    it is a no-op, so it was pure dead width.
        //  - Re-weight categories slim / channels wide so the channel list isn't trapped in a
        //    ~33%-width column.
        //  - Touch passthrough on the Leanback grid (intercepts first tap for focus otherwise).
        if (!DeviceUtils.isTV(requireContext())) {
            channelsList.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            channelsList.isFocusableInTouchMode = false

            view.findViewById<View>(R.id.preview_panel)?.visibility = View.GONE
            view.findViewById<View>(R.id.categories_panel)?.let { panel ->
                (panel.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = 32f
                    panel.layoutParams = lp
                }
            }
            view.findViewById<View>(R.id.channels_panel)?.let { panel ->
                (panel.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = 68f
                    panel.layoutParams = lp
                }
            }
        }

        // Preview container — DPAD-Right from channels list focuses it, OK launches fullscreen.
        // v3.7.8: previewContainer is now focusable from boot, NOT toggled on channel click.
        // The v3.6.4 fix removed isFocusable=true at click time because Android's focus
        // framework re-evaluated focus on the same frame as the OK and the cursor jumped
        // off the channel item. Setting it once at view creation has no such side effect —
        // the focus tree is established before any clicks happen and never changes during
        // playback. Trade-off: when no preview is running, DPAD-Right still focuses the
        // empty preview area and shows the "Select a channel to preview" placeholder,
        // which doubles as the hint.
        // Preview is a D-pad/TV affordance only. On phone the panel is hidden (above) and a
        // focusable empty container would just be a dead tab-stop.
        val previewFocusable = DeviceUtils.isTV(requireContext())
        previewContainer.isFocusable = previewFocusable
        previewContainer.isFocusableInTouchMode = previewFocusable
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
            } else {
                v.overlay.clear()
            }
        }
        // PlayerView should NOT steal focus — container handles it
        previewPlayerView.isFocusable = false
        // Disable Media3's built-in transport controls on the preview — without this they pop up over
        // the preview as you move down channels (the preview re-tunes and PlayerView auto-shows its
        // controller). The preview is a silent, control-free thumbnail.
        previewPlayerView.useController = false
        previewPlayerView.controllerAutoShow = false

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
        val channelPresenter = ChannelPresenter(epgResolver = { ch ->
            val categoryName = viewModel.categories.value
                .find { it.categoryId == viewModel.selectedCategoryId.value }?.categoryName
            smartEpgFiller.inferRuleBased(ch.name, categoryName)
        })
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

        // TV Guide icon in header — opens the EPG grid scoped to the selected category
        val guideIcon = view.findViewById<ImageView>(R.id.header_guide_icon)
        guideIcon.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(200).start()
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setStroke(2, 0xFFFFC107.toInt())
                    setColor(0x14FFD700)
                }
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                v.background = null
            }
        }
        guideIcon.setOnClickListener {
            val categoryId = viewModel.selectedCategoryId.value
            val categoryName = viewModel.categories.value
                .find { it.categoryId == categoryId }?.categoryName
            (activity as? com.ooustream.iptv.MainActivity)?.navigateToEpgGuide(categoryId, categoryName)
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
                    // OK always goes fullscreen — preview now auto-starts on focus dwell,
                    // so the old first-OK-preview / second-OK-fullscreen two-press model is gone.
                    goFullscreen(filteredChannels[pos])
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
                                // Restore the CURSOR too — scroll alone leaves focus elsewhere
                                // and the gold cursor invisible (v4.0.1 bug family).
                                channelsList.requestFocus()
                            }
                        }
                    } else if (skeletonSwapped && channels.isNotEmpty()) {
                        // v3.7.8: subsequent emissions = category switch. Reset the channel
                        // list to the top so the user sees the new category from the
                        // beginning instead of whatever scroll position the prior category
                        // happened to be at.
                        channelsList.post {
                            channelsList.safeSetSelectedPosition(0, channelAdapter.size())
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

                    // Auto-preview on focus dwell (TV only, skip on low-memory sticks).
                    // ~1s dwell so fast scrolling doesn't thrash the decoder.
                    previewJob?.cancel()
                    if (DeviceUtils.isTV(requireContext()) && !lowMemoryDevice) {
                        previewJob = viewLifecycleOwner.lifecycleScope.launch {
                            delay(1000)
                            // Don't spin up a decoder if the screen was paused/stopped during
                            // the dwell (lifecycleScope only cancels at DESTROYED, not STOPPED).
                            if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                startPreviewFor(channel)
                            }
                        }
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
                viewModel.epgState.collect { epgState ->
                    val programs = epgState.programs
                    if (programs.isEmpty()) {
                        // No server EPG — ask SmartEpgFiller for an inferred "now on" blurb
                        // for the currently focused channel so the panel isn't just empty.
                        val focusedChannel = filteredChannels.getOrNull(channelsList.selectedPosition)
                        val emptyText = if (focusedChannel != null) {
                            val categoryName = viewModel.categories.value
                                .find { it.categoryId == viewModel.selectedCategoryId.value }
                                ?.categoryName
                            val inferred = smartEpgFiller.getSmartEpg(
                                null, focusedChannel.streamId, focusedChannel.name, categoryName
                            )
                            "Now: ${inferred.title}\n(schedule unavailable from provider)"
                        } else {
                            "No schedule available"
                        }
                        epgList.adapter = EpgAdapter(emptyList(), emptyText)
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
                                // Upgrade the preview overlay to real EPG when it's the previewing channel
                                if (previewingChannel?.streamId == channel.streamId) {
                                    showPreviewOverlay(channel.name, current.title, progress)
                                }
                            }
                        } else if (epgTextView != null) {
                            // No real EPG. The row already shows a rule-based guess from the
                            // presenter (ChannelPresenter.epgResolver). Only UPGRADE it when we
                            // have a more-informed, clearly-hedged "Likely:" learned pattern —
                            // re-binding a bare rule guess would just swap one guess for another
                            // on focus, which reads as a glitch.
                            val categoryName = viewModel.categories.value
                                .find { it.categoryId == viewModel.selectedCategoryId.value }
                                ?.categoryName
                            val inferred = smartEpgFiller.getSmartEpg(
                                null, channel.streamId, channel.name, categoryName
                            )
                            if (inferred.source == EpgSource.PATTERN_CACHE && inferred.title.isNotBlank()) {
                                bindEpgText(epgTextView, inferred)
                                epgContainer?.visibility = View.VISIBLE
                                epgTimeView?.text = ""
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
        private val programs: List<EpgProgram>,
        private val emptyStateText: String = "No schedule available for this channel"
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
                    text = emptyStateText
                    setTextColor(Color.parseColor("#9CA3AF"))
                    gravity = android.view.Gravity.CENTER
                    textSize = 13f
                    setPadding(16, 24, 16, 24)
                    setTypeface(typeface, android.graphics.Typeface.ITALIC)
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
        val grid = view?.findViewById<VerticalGridView>(R.id.channels_list)
        val channels = viewModel.channels.value
        filteredChannels = if (searchFilter.isEmpty()) {
            channels
        } else {
            channels.filter { it.name.lowercase().contains(searchFilter) }
        }
        // Leanback GridLayoutManager crash guard ("Invalid item position -1"), same class
        // as the v3.7.13 VOD/Series fix: setItems(..., null) schedules a deferred layout;
        // on the empty->populated transition (category switch) mFocusPosition can be left at
        // NO_POSITION (-1) and the layout calls createItem(-1). Force a valid, in-bounds focus
        // index SYNCHRONOUSLY after setItems so the channels grid never lays out against -1.
        val savedPos = grid?.selectedPosition ?: -1
        channelAdapter.setItems(filteredChannels, null)
        if (filteredChannels.isNotEmpty() && grid != null) {
            val target = (if (savedPos >= 0) savedPos else 0).coerceIn(0, filteredChannels.size - 1)
            try { grid.selectedPosition = target } catch (_: Exception) { }
        }
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

    /** Start the muted preview for a channel with a crossfade + now-playing overlay. */
    private fun startPreviewFor(channel: LiveStream) {
        val v = view ?: return
        val playerView = v.findViewById<PlayerView>(R.id.preview_player_view) ?: return
        val placeholder = v.findViewById<TextView>(R.id.preview_placeholder)
        previewingChannel = channel
        val url = viewModel.buildStreamUrl(channel.streamId)
        placeholder?.visibility = View.GONE
        playerView.animate().cancel()
        playerView.alpha = 0f
        playerView.visibility = View.VISIBLE
        playerView.animate().alpha(1f).setDuration(220).start()
        previewManager?.startPreview(playerView, url)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Now-playing overlay: channel + inferred on-now (upgraded to real when EPG loads)
        val categoryName = viewModel.categories.value
            .find { it.categoryId == viewModel.selectedCategoryId.value }?.categoryName
        val inferred = smartEpgFiller.inferRuleBased(channel.name, categoryName)
        showPreviewOverlay(channel.name, inferred.title, null)
    }

    /** Update the preview's now-playing overlay (channel name + on-now + optional live progress). */
    private fun showPreviewOverlay(channelName: String, nowTitle: String?, progress: Float?) {
        val v = view ?: return
        val overlay = v.findViewById<View>(R.id.preview_info_overlay) ?: return
        v.findViewById<TextView>(R.id.preview_channel_name)?.text =
            ChannelNameParser.parseForDisplay(channelName).name
        val nowView = v.findViewById<TextView>(R.id.preview_now_playing)
        if (!nowTitle.isNullOrBlank()) {
            nowView?.text = "ON NOW · $nowTitle"
            nowView?.visibility = View.VISIBLE
        } else {
            nowView?.visibility = View.GONE
        }
        val pc = v.findViewById<View>(R.id.preview_progress_container)
        val pf = v.findViewById<View>(R.id.preview_progress_fill)
        if (progress != null && progress > 0f && pc != null && pf != null) {
            ChannelDisplayHelper.setProgressBar(pf, pc, progress)
        } else {
            pc?.visibility = View.GONE
        }
        overlay.visibility = View.VISIBLE
    }

    private fun stopPreview() {
        epgJob?.cancel()
        epgJob = null
        previewJob?.cancel()
        previewJob = null
        previewManager?.release()
        previewingChannel = null
        // Keep preview focusable even when nothing is playing. Clear focus overlay,
        // hide the now-playing overlay, and restore the idle play-glyph placeholder.
        view?.findViewById<FrameLayout>(R.id.preview_container)?.overlay?.clear()
        view?.findViewById<View>(R.id.preview_info_overlay)?.visibility = View.GONE
        view?.findViewById<PlayerView>(R.id.preview_player_view)?.let {
            it.animate().cancel()
            it.visibility = View.GONE
            it.alpha = 1f
        }
        view?.findViewById<TextView>(R.id.preview_placeholder)?.visibility = View.VISIBLE
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
        // Resolve the channel that was actually playing. Three signals, in priority:
        //  1. ChannelListHolder.lastPlayedStreamId — authoritative from PlayerVM.streamId.
        //     Look it up against our current channel list (or full live channel list).
        //  2. ChannelListHolder.lastPlayedChannel — the channel object PlayerVM thought
        //     it was playing (may be stale or null after rebuild edge cases).
        //  3. viewModel.lastPreviewedChannel — the original previewed channel, only
        //     correct if user never switched channels in fullscreen.
        val returnedStreamId = ChannelListHolder.lastPlayedStreamId
        val returnedChannel = ChannelListHolder.lastPlayedChannel

        val channel: LiveStream
        val url: String

        when {
            returnedStreamId > 0 -> {
                // Try filteredChannels first (the visible list); fall back to the
                // full channels list from the ViewModel.
                val resolved = filteredChannels.firstOrNull { it.streamId == returnedStreamId }
                    ?: viewModel.channels.value.firstOrNull { it.streamId == returnedStreamId }
                    ?: returnedChannel
                if (resolved == null) {
                    // Couldn't resolve — clear holders and bail out.
                    ChannelListHolder.lastPlayedStreamId = -1
                    ChannelListHolder.lastPlayedChannel = null
                    ChannelListHolder.lastPlayedIndex = -1
                    return
                }
                channel = resolved
                url = viewModel.buildStreamUrl(channel.streamId)
                ChannelListHolder.lastPlayedStreamId = -1
                ChannelListHolder.lastPlayedChannel = null
                ChannelListHolder.lastPlayedIndex = -1
            }
            returnedChannel != null -> {
                channel = returnedChannel
                url = viewModel.buildStreamUrl(channel.streamId)
                ChannelListHolder.lastPlayedChannel = null
                ChannelListHolder.lastPlayedIndex = -1
            }
            viewModel.lastPreviewedChannel != null -> {
                channel = viewModel.lastPreviewedChannel!!
                url = viewModel.lastPreviewedUrl ?: viewModel.buildStreamUrl(channel.streamId)
            }
            else -> return // No channel to resume — normal fresh open
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
        val resumeCat = viewModel.categories.value
            .find { it.categoryId == viewModel.selectedCategoryId.value }?.categoryName
        showPreviewOverlay(channel.name, smartEpgFiller.inferRuleBased(channel.name, resumeCat).title, null)

        // Scroll channel list to the resumed channel, load its EPG, and put the FOCUS CURSOR
        // back on it. Without the explicit requestFocus, the back-stack re-attach hands focus
        // to the first focusable view (the category list), where the selected category's
        // styling masks the focus ring — the user sees no cursor anywhere and has to blindly
        // press D-pad to find it.
        val channelsList = view?.findViewById<VerticalGridView>(R.id.channels_list)
        channelsList?.post {
            val listIndex = filteredChannels.indexOfFirst { it.streamId == channel.streamId }
            if (listIndex >= 0) {
                channelsList.selectedPosition = listIndex
            }
            channelsList.requestFocus()
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
