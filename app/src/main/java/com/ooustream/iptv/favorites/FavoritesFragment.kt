package com.ooustream.iptv.favorites

import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.HorizontalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import com.ooustream.iptv.common.ContentInfoHelper
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.livetv.LiveTvFragment
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import com.ooustream.iptv.series.SeriesDetailFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How long a removed card takes to shrink and fade before its row is deleted. */
private const val REMOVE_ANIM_MS = 400L

/** How long the Undo bar stays after a removal, unless the cursor is on UNDO. */
private const val UNDO_SHOW_MS = 5000L

@AndroidEntryPoint
class FavoritesFragment : Fragment(), KeyEventHandler {

    private val viewModel: FavoritesViewModel by viewModels()

    @Inject lateinit var contentRepository: ContentRepository

    // Views
    private lateinit var favoritesGrid: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptyCta: TextView
    private lateinit var filterTabsContainer: LinearLayout
    private lateinit var scoresBarContainer: LinearLayout
    private lateinit var scoresRow: HorizontalGridView
    private lateinit var viewToggleBtn: TextView
    private lateinit var sortBtn: TextView
    private lateinit var editBtn: TextView
    private lateinit var itemCount: TextView
    private lateinit var headerCount: TextView
    private lateinit var undoBar: View
    private lateinit var undoText: TextView
    private lateinit var undoButton: TextView
    private lateinit var remoteHints: View
    private lateinit var okHintLabel: TextView

    // Adapters
    private lateinit var favoritesAdapter: FavoritesAdapter
    private lateinit var scoreCardAdapter: LiveScoreCardAdapter

    // Filter tabs
    private val filterTabs = mutableListOf<TextView>()

    // Sort dropdown
    private var sortDropdown: SortDropdownPopup? = null

    // Undo timer
    private var undoTimer: CountDownTimer? = null
    private var undoShowing = false

    // Content info helper for long-press on VOD/series
    private var contentInfoHelper: ContentInfoHelper? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_favorites_aurora, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecyclerView()
        setupFilterTabs()
        setupScoresBar()
        setupHeaderActions()
        setupEmptyState()
        setupContentInfo(view)
        setupRemoteHints()
        observeViewModel()
    }

    private fun bindViews(view: View) {
        favoritesGrid = view.findViewById(R.id.favorites_grid)
        emptyState = view.findViewById(R.id.empty_state)
        emptyCta = view.findViewById(R.id.empty_cta)
        filterTabsContainer = view.findViewById(R.id.filter_tabs_container)
        scoresBarContainer = view.findViewById(R.id.scores_bar_container)
        scoresRow = view.findViewById(R.id.scores_row)
        viewToggleBtn = view.findViewById(R.id.view_toggle_btn)
        sortBtn = view.findViewById(R.id.sort_btn)
        editBtn = view.findViewById(R.id.edit_btn)
        itemCount = view.findViewById(R.id.item_count)
        headerCount = view.findViewById(R.id.header_count)
        undoBar = view.findViewById(R.id.undo_bar)
        undoText = view.findViewById(R.id.undo_text)
        undoButton = view.findViewById(R.id.undo_button)
        remoteHints = view.findViewById(R.id.remote_hints)
        okHintLabel = view.findViewById(R.id.remote_hint_ok_label)
    }

    private fun setupRecyclerView() {
        favoritesAdapter = FavoritesAdapter(
            onClick = { item -> onItemClick(item) },
            onLongClick = { item -> onItemLongClick(item) },
            onRemove = { item -> onRemoveItem(item) }
        )

        val spanCount = getSpanCount()
        val layoutManager = GridLayoutManager(requireContext(), spanCount)
        favoritesGrid.layoutManager = layoutManager
        favoritesGrid.adapter = favoritesAdapter
        favoritesGrid.itemAnimator = null // No flicker on EPG refresh
    }

    private fun getSpanCount(): Int {
        return when {
            DeviceUtils.isTV(requireContext()) -> 4
            resources.configuration.screenWidthDp >= 600 -> 3 // tablet
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE -> 3
            else -> 2
        }
    }

    private fun setupFilterTabs() {
        val density = resources.displayMetrics.density
        filterTabs.clear()
        filterTabsContainer.removeAllViews()

        ChannelCategoryMapper.ALL_GROUPS.forEachIndexed { index, groupName ->
            val tab = TextView(requireContext()).apply {
                text = groupName
                textSize = 13f
                setTextColor(if (index == 0) 0xFFFFC107.toInt() else 0xFF9CA3AF.toInt())
                setBackgroundResource(R.drawable.bg_filter_tab)
                isSelected = index == 0
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(
                    (16 * density).toInt(), (6 * density).toInt(),
                    (16 * density).toInt(), (6 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
            }

            tab.setOnClickListener { selectFilterTab(index) }
            tab.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) DpadSoundManager.getInstance()?.playMove()
                v.animate()
                    .scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(if (hasFocus) 150L else 100L)
                    .start()
            }

            filterTabs.add(tab)
            filterTabsContainer.addView(tab)
        }
    }

    private fun selectFilterTab(index: Int) {
        filterTabs.forEachIndexed { i, tab ->
            tab.isSelected = i == index
            tab.setTextColor(if (i == index) 0xFFFFC107.toInt() else 0xFF9CA3AF.toInt())
        }
        viewModel.setActiveFilter(ChannelCategoryMapper.ALL_GROUPS[index])
    }

    private fun setupScoresBar() {
        scoreCardAdapter = LiveScoreCardAdapter { item ->
            // Scroll to this channel in the grid and focus it
            val items = favoritesAdapter.currentList
            val pos = items.indexOfFirst { it.id == item.id }
            if (pos >= 0) {
                favoritesGrid.scrollToPosition(pos)
                favoritesGrid.post {
                    val vh = favoritesGrid.findViewHolderForAdapterPosition(pos)
                    vh?.itemView?.requestFocus()
                }
            }
        }
        scoresRow.adapter = scoreCardAdapter
    }

    private fun setupHeaderActions() {
        // View toggle
        viewToggleBtn.setOnClickListener {
            viewModel.toggleViewMode()
        }
        viewToggleBtn.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_score_card_focused else R.drawable.bg_score_card)
        }

        // Sort button
        sortBtn.setOnClickListener {
            showSortDropdown()
        }
        sortBtn.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_score_card_focused else R.drawable.bg_score_card)
        }

        // Edit button
        editBtn.setOnClickListener {
            viewModel.toggleEditMode()
        }
        editBtn.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_score_card_focused else R.drawable.bg_score_card)
        }

        // Undo button
        undoButton.setOnClickListener {
            viewModel.undoRemove()
            hideUndoBar()
        }
        undoButton.setOnFocusChangeListener { v, hasFocus ->
            v.alpha = if (hasFocus) 1f else 0.7f
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_score_card_focused else 0)
            // The bar stays while the cursor is on UNDO (a remote takes a few presses to get there);
            // the countdown starts again when the cursor leaves it.
            if (hasFocus) undoTimer?.cancel() else if (undoShowing) startUndoTimer()
        }
    }

    private fun setupEmptyState() {
        emptyCta.setOnClickListener { navigateToLiveTv() }
        emptyCta.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_score_card_focused else R.drawable.bg_score_card)
        }
    }

    private fun setupContentInfo(view: View) {
        val root = view as ViewGroup
        val infoHelper = ContentInfoHelper(this, contentRepository) { item ->
            navigateToContent(item)
        }
        infoHelper.attach(root)
        // Everything on this screen is a favorite, so the card's Favorites button removes it, with
        // the same animation and Undo bar as Edit mode.
        infoHelper.setOnFavorite { item ->
            val favoriteId = "${item.type}_${item.id}"
            val shown = favoritesAdapter.currentList.find { it.id == favoriteId }
            if (shown != null) onRemoveItem(shown) else viewModel.removeFavorite(favoriteId)
        }
        contentInfoHelper = infoHelper
    }

    private fun setupRemoteHints() {
        remoteHints.visibility = if (DeviceUtils.isTV(requireContext())) View.VISIBLE else View.GONE
    }

    // --- Observe ViewModel ---

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Display items (filtered + sorted)
                launch {
                    viewModel.displayItems.collect { items ->
                        favoritesAdapter.submitList(items) {
                            favoritesAdapter.markFirstLoadComplete()
                            // Restore the CURSOR on back-nav: savedFocusPosition was written by
                            // the navigate-away paths but never consumed — focus landed elsewhere
                            // and the gold cursor was invisible (v4.0.1 bug family).
                            val restorePos = viewModel.savedFocusPosition
                            if (restorePos >= 0 && items.isNotEmpty()) {
                                viewModel.savedFocusPosition = -1
                                favoritesGrid.post {
                                    favoritesGrid.scrollToPosition(restorePos.coerceIn(0, items.size - 1))
                                    // Cursor restore is TV-only; on a phone requestFocus would pop
                                    // the keyboard / steal scroll on back-return.
                                    if (DeviceUtils.isTV(requireContext())) favoritesGrid.requestFocus()
                                }
                            }
                        }
                        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                        favoritesGrid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

                        // Update scores bar
                        updateScoresBar(items)
                    }
                }

                // Filter counts → update tab badges
                launch {
                    viewModel.filterCounts.collect { counts ->
                        updateFilterTabCounts(counts)
                    }
                }

                // Total count
                launch {
                    viewModel.totalCount.collect { count ->
                        headerCount.text = "($count)"
                        itemCount.text = "$count items"
                    }
                }

                // View mode
                launch {
                    viewModel.viewMode.collect { mode ->
                        favoritesAdapter.viewMode = mode
                        viewToggleBtn.text = if (mode == ViewMode.GRID) "Grid" else "List"
                        val lm = favoritesGrid.layoutManager as? GridLayoutManager
                        lm?.spanCount = if (mode == ViewMode.GRID) getSpanCount() else 1
                    }
                }

                // Edit mode
                launch {
                    var wasEditing = viewModel.isEditMode.value
                    viewModel.isEditMode.collect { editing ->
                        if (editing && !wasEditing && DeviceUtils.isTV(requireContext())) {
                            Toast.makeText(requireContext(), "Press OK on a card to remove it", Toast.LENGTH_SHORT).show()
                        }
                        wasEditing = editing
                        favoritesAdapter.isEditMode = editing
                        editBtn.text = if (editing) "Done" else "Edit"
                        editBtn.setTextColor(if (editing) 0xFFFFC107.toInt() else 0xFF9CA3AF.toInt())
                        okHintLabel.text = if (editing) "Remove" else "Watch"
                    }
                }

                // Undo events
                launch {
                    viewModel.undoEvent.collect { event ->
                        showUndoBar(event.message)
                    }
                }

                // Toast events
                launch {
                    viewModel.toastEvent.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // --- Scores bar ---

    private fun updateScoresBar(items: List<FavoriteDisplayItem>) {
        val activeFilter = viewModel.activeFilter.value
        if (activeFilter != ChannelCategoryMapper.GROUP_ALL && activeFilter != ChannelCategoryMapper.GROUP_SPORTS) {
            scoresBarContainer.visibility = View.GONE
            return
        }

        val scoreItems = items.filter {
            it.categoryGroup == ChannelCategoryMapper.GROUP_SPORTS && !it.score.isNullOrBlank()
        }

        if (scoreItems.isEmpty()) {
            scoresBarContainer.visibility = View.GONE
        } else {
            scoresBarContainer.visibility = View.VISIBLE
            scoreCardAdapter.submitList(scoreItems)
        }
    }

    // --- Filter tabs ---

    private fun updateFilterTabCounts(counts: Map<String, Int>) {
        ChannelCategoryMapper.ALL_GROUPS.forEachIndexed { index, group ->
            if (index < filterTabs.size) {
                val count = counts[group] ?: 0
                val tab = filterTabs[index]
                tab.text = if (group == ChannelCategoryMapper.GROUP_ALL) group else "$group ($count)"
                // Hide tabs with 0 items (except "All")
                tab.visibility = if (count > 0 || group == ChannelCategoryMapper.GROUP_ALL) View.VISIBLE else View.GONE
            }
        }
    }

    // --- Sort dropdown ---

    private fun showSortDropdown() {
        if (sortDropdown == null) {
            sortDropdown = SortDropdownPopup(requireContext()) { mode ->
                viewModel.setSortMode(mode)
                sortBtn.text = when (mode) {
                    SortMode.RECENTLY_ADDED -> "Recently Added"
                    SortMode.RECENTLY_WATCHED -> "Recently Watched"
                    SortMode.NAME_AZ -> "Name A-Z"
                    SortMode.CATEGORY -> "Category"
                }
            }
        }
        sortDropdown?.show(sortBtn, SortMode.RECENTLY_ADDED)
    }

    // --- Undo bar ---

    private fun showUndoBar(message: String) {
        undoText.text = message
        undoShowing = true
        undoBar.visibility = View.VISIBLE
        undoBar.alpha = 0f
        undoBar.animate().alpha(1f).setDuration(200).start()
        if (!undoButton.hasFocus()) startUndoTimer()
    }

    private fun startUndoTimer() {
        undoTimer?.cancel()
        undoTimer = object : CountDownTimer(UNDO_SHOW_MS, UNDO_SHOW_MS) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() { hideUndoBar() }
        }.start()
    }

    private fun hideUndoBar() {
        undoTimer?.cancel()
        undoShowing = false
        // Hand the cursor back to the cards first: going with the bar, it would land on the Grid
        // button at the top of the screen.
        if (undoBar.hasFocus()) {
            val above = undoButton.focusSearch(View.FOCUS_UP)
            if (above?.requestFocus() != true && !favoritesGrid.requestFocus()) editBtn.requestFocus()
        }
        undoBar.animate().alpha(0f).setDuration(200).withEndAction {
            undoBar.visibility = View.GONE
        }.start()
    }

    // --- Click handlers ---

    private fun onItemClick(item: FavoriteDisplayItem) {
        // Edit mode: OK removes the card. The card's X button is touch-only (not focusable).
        if (viewModel.isEditMode.value) {
            onRemoveItem(item)
            return
        }
        when (item.type) {
            "live" -> {
                val url = viewModel.buildLiveUrl(item.streamId)
                navigateToPlayer(url, ContentType.LIVE, item.streamId.toString(), item.name, item.icon ?: "")
            }
            "vod" -> {
                val ext = item.extra ?: "mp4"
                val url = viewModel.buildVodUrl(item.streamId, ext)
                navigateToPlayer(url, ContentType.VOD, item.streamId.toString(), item.name, item.icon ?: "")
            }
            "series" -> {
                navigateToSeriesDetail(item.streamId, item.name)
            }
        }
    }

    private fun onItemLongClick(item: FavoriteDisplayItem) {
        if (item.type == "vod" || item.type == "series") {
            val posterItem = PosterItem(
                id = item.streamId,
                title = item.name,
                imageUrl = item.icon,
                rating = null,
                extension = item.extra,
                type = item.type
            )
            contentInfoHelper?.onLongPress?.invoke(posterItem)
        } else {
            // For live channels, toggle edit mode
            viewModel.toggleEditMode()
        }
    }

    private fun onRemoveItem(item: FavoriteDisplayItem) {
        val pos = favoritesAdapter.currentList.indexOfFirst { it.id == item.id }
        val card = if (pos >= 0) favoritesGrid.findViewHolderForAdapterPosition(pos)?.itemView else null
        if (card == null) {
            viewModel.removeFavorite(item.id)
            return
        }
        // Animate out, then remove. The removal runs on a timer, not as the animation's end action:
        // recycling the card (a scroll, a filter switch) cancels its animation
        // (FavoritesAdapter.onViewRecycled), and the favorite still has to go.
        card.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(REMOVE_ANIM_MS).start()
        favoritesGrid.postDelayed({
            val now = favoritesAdapter.currentList.indexOfFirst { it.id == item.id }
            val stillFocused = now >= 0 &&
                favoritesGrid.findViewHolderForAdapterPosition(now)?.itemView?.hasFocus() == true
            if (stillFocused) focusNeighbour(now)
            viewModel.removeFavorite(item.id)
        }, REMOVE_ANIM_MS)
    }

    /**
     * Moves the cursor off a card that is about to be removed: to the next card (it slides into this
     * slot) or, at the end of the list, the previous one. Detaching a focused card otherwise drops
     * focus onto the first view in the window (the Grid button), so a remote user removing several
     * favorites was sent back to the top after each one.
     */
    private fun focusNeighbour(pos: Int) {
        val size = favoritesAdapter.currentList.size
        for (n in intArrayOf(pos + 1, pos - 1)) {
            if (n !in 0 until size) continue
            val neighbour = favoritesGrid.findViewHolderForAdapterPosition(n)?.itemView ?: continue
            if (neighbour.requestFocus()) return
        }
    }

    // --- Key event handling ---

    override fun onKeyEvent(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                // Cycle sort mode
                showSortDropdown()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (viewModel.isEditMode.value) {
                    viewModel.exitEditMode()
                    return true
                }
            }
        }
        return false
    }

    // --- Navigation ---

    private fun navigateToContent(item: PosterItem) {
        if (item.type == "series") {
            navigateToSeriesDetail(item.id, item.title)
        } else {
            val ext = item.extension ?: "mp4"
            val url = viewModel.buildVodUrl(item.id, ext)
            navigateToPlayer(url, ContentType.VOD, item.id.toString(), item.title, item.imageUrl ?: "")
        }
    }

    private fun navigateToPlayer(
        streamUrl: String,
        contentType: ContentType,
        streamId: String,
        streamName: String,
        streamIcon: String
    ) {
        viewModel.savedFocusPosition = (favoritesGrid.layoutManager as? GridLayoutManager)
            ?.findFirstVisibleItemPosition() ?: 0

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
        viewModel.savedFocusPosition = (favoritesGrid.layoutManager as? GridLayoutManager)
            ?.findFirstVisibleItemPosition() ?: 0

        val fragment = SeriesDetailFragment.newInstance(seriesId, seriesName)
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
        tx.replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToLiveTv() {
        val fragment = (activity as? com.ooustream.iptv.MainActivity)?.liveTvScreen() ?: LiveTvFragment()
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
        tx.replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        undoTimer?.cancel()
        undoShowing = false
        sortDropdown?.dismiss()
        super.onDestroyView()
    }
}
