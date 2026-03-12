package com.ooustream.iptv.search

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import com.ooustream.iptv.common.ChannelPresenter
import com.ooustream.iptv.common.ContentInfoHelper
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterPresenter
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.SearchResults
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import com.ooustream.iptv.series.SeriesDetailFragment
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SearchFragment : Fragment(), KeyEventHandler {

    private val viewModel: SearchViewModel by viewModels()

    @Inject lateinit var contentRepository: ContentRepository

    // Views
    private lateinit var searchEditText: EditText
    private lateinit var searchInputBar: LinearLayout
    private lateinit var searchIcon: ImageView
    private lateinit var voiceBtn: ImageView
    private lateinit var clearBtn: ImageView
    private lateinit var filterTabsContainer: LinearLayout
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var recentLabel: TextView
    private lateinit var recentRow: HorizontalGridView
    private lateinit var trendingLabel: TextView
    private lateinit var trendingRow: HorizontalGridView
    private lateinit var resultsScroll: ScrollView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var noResultsContainer: LinearLayout
    private lateinit var noResultsText: TextView
    private lateinit var searchLoading: ProgressBar

    private var searchBarAnimator: SearchBarFocusAnimator? = null
    private var contentInfoHelper: ContentInfoHelper? = null

    // Result section views (pre-created for reuse)
    private var liveSectionHeader: View? = null
    private var liveSectionRow: HorizontalGridView? = null
    private var vodSectionHeader: View? = null
    private var vodSectionRow: HorizontalGridView? = null
    private var seriesSectionHeader: View? = null
    private var seriesSectionRow: HorizontalGridView? = null

    // Adapters
    private val liveAdapter = ArrayObjectAdapter(ChannelPresenter())
    private val vodAdapter = ArrayObjectAdapter(PosterPresenter())
    private val seriesAdapter = ArrayObjectAdapter(PosterPresenter())
    private val recentChipAdapter = ArrayObjectAdapter(SearchChipPresenter())
    private val trendingAdapter = ArrayObjectAdapter(TrendingRankPresenter())

    // Filter tabs
    private val filterNames = listOf("All", "Live TV", "Movies", "Series")
    private val filterTabs = mutableListOf<TextView>()

    // Voice search launcher
    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            matches?.firstOrNull()?.let { query ->
                searchEditText.setText(query)
                searchEditText.setSelection(query.length)
                viewModel.search(query)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_search_aurora, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupSearchInput()
        setupFilterTabs()
        setupVoiceSearch()
        setupEmptyState()
        setupResultSections()
        setupContentInfo(view)
        observeViewModel()

        // Focus search bar on open
        view.post { searchEditText.requestFocus() }
    }

    private fun bindViews(view: View) {
        searchEditText = view.findViewById(R.id.search_edit_text)
        searchInputBar = view.findViewById(R.id.search_input_bar)
        searchIcon = view.findViewById(R.id.search_icon)
        voiceBtn = view.findViewById(R.id.voice_btn)
        clearBtn = view.findViewById(R.id.clear_btn)
        filterTabsContainer = view.findViewById(R.id.filter_tabs_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        recentLabel = view.findViewById(R.id.recent_label)
        recentRow = view.findViewById(R.id.recent_row)
        trendingLabel = view.findViewById(R.id.trending_label)
        trendingRow = view.findViewById(R.id.trending_row)
        resultsScroll = view.findViewById(R.id.results_scroll)
        resultsContainer = view.findViewById(R.id.results_container)
        noResultsContainer = view.findViewById(R.id.no_results_container)
        noResultsText = view.findViewById(R.id.no_results_text)
        searchLoading = view.findViewById(R.id.search_loading)
    }

    private fun setupSearchInput() {
        searchBarAnimator = SearchBarFocusAnimator(searchInputBar, searchIcon)

        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                searchBarAnimator?.onFocusGained()
            } else {
                searchBarAnimator?.onFocusLost()
            }
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                clearBtn.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                viewModel.search(query)
            }
        })

        clearBtn.setOnClickListener {
            searchEditText.text.clear()
            searchEditText.requestFocus()
        }

        clearBtn.setOnFocusChangeListener { v, hasFocus ->
            v.alpha = if (hasFocus) 1f else 0.7f
        }
    }

    private fun setupFilterTabs() {
        val density = resources.displayMetrics.density
        filterTabs.clear()

        filterNames.forEachIndexed { index, name ->
            val tab = TextView(requireContext()).apply {
                text = name
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

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (8 * density).toInt()
                layoutParams = lp
            }

            tab.setOnClickListener {
                selectFilterTab(index)
            }

            tab.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    DpadSoundManager.getInstance()?.playMove()
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
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
        viewModel.setActiveFilter(filterNames[index])
    }

    private fun setupVoiceSearch() {
        val available = SpeechRecognizer.isRecognitionAvailable(requireContext())
        voiceBtn.visibility = if (available) View.VISIBLE else View.GONE

        voiceBtn.setOnClickListener { launchVoiceSearch() }
        voiceBtn.setOnFocusChangeListener { v, hasFocus ->
            v.alpha = if (hasFocus) 1f else 0.7f
        }
    }

    private fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search_prompt))
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (_: Exception) {
            // No speech recognizer available
        }
    }

    private fun setupEmptyState() {
        // Recent chips row
        val chipBridgeAdapter = ItemBridgeAdapter(recentChipAdapter)
        chipBridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < recentChipAdapter.size()) {
                        val query = recentChipAdapter.get(pos) as String
                        searchEditText.setText(query)
                        searchEditText.setSelection(query.length)
                        viewModel.search(query)
                    }
                }
            }
        })
        recentRow.adapter = chipBridgeAdapter

        // Trending row
        val trendingBridgeAdapter = ItemBridgeAdapter(trendingAdapter)
        trendingBridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < trendingAdapter.size()) {
                        val item = trendingAdapter.get(pos) as TrendingRankItem
                        navigateToContent(item.poster)
                    }
                }
                viewHolder.itemView.setOnLongClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < trendingAdapter.size()) {
                        val item = trendingAdapter.get(pos) as TrendingRankItem
                        contentInfoHelper?.onLongPress?.invoke(item.poster)
                    }
                    true
                }
            }
        })
        trendingRow.adapter = trendingBridgeAdapter
    }

    private fun setupResultSections() {
        val inflater = LayoutInflater.from(requireContext())

        // Live TV section
        liveSectionHeader = inflater.inflate(R.layout.item_search_section_header, resultsContainer, false)
        liveSectionRow = createResultRow()
        val liveBridgeAdapter = ItemBridgeAdapter(liveAdapter)
        liveBridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < liveAdapter.size()) {
                        val channel = liveAdapter.get(pos) as LiveStream
                        navigateToLivePlayback(channel)
                    }
                }
            }
        })
        liveSectionRow?.adapter = liveBridgeAdapter
        resultsContainer.addView(liveSectionHeader)
        resultsContainer.addView(liveSectionRow)

        // Movies section
        vodSectionHeader = inflater.inflate(R.layout.item_search_section_header, resultsContainer, false)
        vodSectionRow = createResultRow()
        val vodBridgeAdapter = ItemBridgeAdapter(vodAdapter)
        vodBridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < vodAdapter.size()) {
                        val poster = vodAdapter.get(pos) as PosterItem
                        navigateToContent(poster)
                    }
                }
                viewHolder.itemView.setOnLongClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < vodAdapter.size()) {
                        val poster = vodAdapter.get(pos) as PosterItem
                        contentInfoHelper?.onLongPress?.invoke(poster)
                    }
                    true
                }
            }
        })
        vodSectionRow?.adapter = vodBridgeAdapter
        resultsContainer.addView(vodSectionHeader)
        resultsContainer.addView(vodSectionRow)

        // Series section
        seriesSectionHeader = inflater.inflate(R.layout.item_search_section_header, resultsContainer, false)
        seriesSectionRow = createResultRow()
        val seriesBridgeAdapter = ItemBridgeAdapter(seriesAdapter)
        seriesBridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < seriesAdapter.size()) {
                        val poster = seriesAdapter.get(pos) as PosterItem
                        navigateToContent(poster)
                    }
                }
                viewHolder.itemView.setOnLongClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < seriesAdapter.size()) {
                        val poster = seriesAdapter.get(pos) as PosterItem
                        contentInfoHelper?.onLongPress?.invoke(poster)
                    }
                    true
                }
            }
        })
        seriesSectionRow?.adapter = seriesBridgeAdapter
        resultsContainer.addView(seriesSectionHeader)
        resultsContainer.addView(seriesSectionRow)
    }

    private fun createResultRow(): HorizontalGridView {
        return HorizontalGridView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * resources.displayMetrics.density).toInt()
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }

    private fun setupContentInfo(view: View) {
        val root = view as ViewGroup
        val infoHelper = ContentInfoHelper(this, contentRepository) { item ->
            navigateToContent(item)
        }
        infoHelper.attach(root)
        infoHelper.setOnFavorite { item ->
            viewModel.toggleFavorite(item)
        }
        contentInfoHelper = infoHelper
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Search results
                launch {
                    viewModel.searchResults.collect { results ->
                        if (results != null) {
                            displaySearchResults(results)
                        } else {
                            displayEmptyState()
                        }
                    }
                }

                // Loading state
                launch {
                    viewModel.isSearching.collect { searching ->
                        searchLoading.visibility = if (searching) View.VISIBLE else View.GONE
                    }
                }

                // Recent searches
                launch {
                    viewModel.recentSearches.collect { recent ->
                        recentChipAdapter.clear()
                        recent.forEach { recentChipAdapter.add(it.query) }
                        val hasRecent = recent.isNotEmpty()
                        recentLabel.visibility = if (hasRecent) View.VISIBLE else View.GONE
                        recentRow.visibility = if (hasRecent) View.VISIBLE else View.GONE
                    }
                }

                // Trending content
                launch {
                    viewModel.trendingContent.collect { trending ->
                        trendingAdapter.clear()
                        trending.forEachIndexed { index, vod ->
                            trendingAdapter.add(
                                TrendingRankItem(
                                    rank = index + 1,
                                    poster = PosterItem(
                                        id = vod.streamId,
                                        title = vod.name,
                                        imageUrl = vod.streamIcon,
                                        rating = vod.rating,
                                        extension = vod.containerExtension,
                                        type = "vod"
                                    )
                                )
                            )
                        }
                        val hasTrending = trending.isNotEmpty()
                        trendingLabel.visibility = if (hasTrending) View.VISIBLE else View.GONE
                        trendingRow.visibility = if (hasTrending) View.VISIBLE else View.GONE
                    }
                }

                // Active filter changes
                launch {
                    viewModel.activeFilter.collect {
                        // Re-display results with new filter when results exist
                        viewModel.searchResults.value?.let { displaySearchResults(it) }
                    }
                }

                // Toast events (favorites, errors)
                launch {
                    viewModel.toastEvent.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // --- State display methods ---

    private fun displayEmptyState() {
        emptyStateContainer.visibility = View.VISIBLE
        resultsScroll.visibility = View.GONE
        noResultsContainer.visibility = View.GONE
    }

    private fun displaySearchResults(results: SearchResults) {
        val filter = viewModel.activeFilter.value
        val isEmpty = results.live.isEmpty() && results.vod.isEmpty() && results.series.isEmpty()

        if (isEmpty) {
            displayNoResults()
            return
        }

        emptyStateContainer.visibility = View.GONE
        noResultsContainer.visibility = View.GONE
        resultsScroll.visibility = View.VISIBLE

        // Live TV section
        val showLive = (filter == "All" || filter == "Live TV") && results.live.isNotEmpty()
        liveAdapter.clear()
        if (showLive) {
            results.live.forEach { liveAdapter.add(it) }
        }
        liveSectionHeader?.visibility = if (showLive) View.VISIBLE else View.GONE
        liveSectionRow?.visibility = if (showLive) View.VISIBLE else View.GONE
        if (showLive) {
            liveSectionHeader?.findViewById<TextView>(R.id.section_title)?.text = "Live TV"
            liveSectionHeader?.findViewById<TextView>(R.id.section_count)?.text = "${results.live.size} results"
        }

        // Movies section
        val showVod = (filter == "All" || filter == "Movies") && results.vod.isNotEmpty()
        vodAdapter.clear()
        if (showVod) {
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
        }
        vodSectionHeader?.visibility = if (showVod) View.VISIBLE else View.GONE
        vodSectionRow?.visibility = if (showVod) View.VISIBLE else View.GONE
        if (showVod) {
            vodSectionHeader?.findViewById<TextView>(R.id.section_title)?.text = "Movies"
            vodSectionHeader?.findViewById<TextView>(R.id.section_count)?.text = "${results.vod.size} results"
        }

        // Series section
        val showSeries = (filter == "All" || filter == "Series") && results.series.isNotEmpty()
        seriesAdapter.clear()
        if (showSeries) {
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
        }
        seriesSectionHeader?.visibility = if (showSeries) View.VISIBLE else View.GONE
        seriesSectionRow?.visibility = if (showSeries) View.VISIBLE else View.GONE
        if (showSeries) {
            seriesSectionHeader?.findViewById<TextView>(R.id.section_title)?.text = "Series"
            seriesSectionHeader?.findViewById<TextView>(R.id.section_count)?.text = "${results.series.size} results"
        }

        // If all sections hidden by filter, show no results
        if (!showLive && !showVod && !showSeries) {
            displayNoResults()
        }
    }

    private fun displayNoResults() {
        emptyStateContainer.visibility = View.GONE
        resultsScroll.visibility = View.GONE
        noResultsContainer.visibility = View.VISIBLE
        val query = searchEditText.text.toString()
        noResultsText.text = if (query.isNotEmpty()) {
            getString(R.string.no_results_message, query)
        } else {
            getString(R.string.no_results)
        }
    }

    // --- Navigation ---

    private fun navigateToLivePlayback(channel: LiveStream) {
        val url = viewModel.buildLiveStreamUrl(channel.streamId)
        val fragment = OoustreamPlaybackFragment.newInstance(
            streamUrl = url,
            contentType = ContentType.LIVE,
            streamId = channel.streamId.toString(),
            streamName = channel.name,
            streamIcon = channel.streamIcon ?: ""
        )
        requireActivity().supportFragmentManager.beginTransaction()
            .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.FORWARD) }
            .replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToContent(item: PosterItem) {
        when (item.type) {
            "vod" -> {
                val ext = item.extension ?: "mp4"
                val url = viewModel.buildVodStreamUrl(item.id, ext)
                val fragment = OoustreamPlaybackFragment.newInstance(
                    streamUrl = url,
                    contentType = ContentType.VOD,
                    streamId = item.id.toString(),
                    streamName = item.title,
                    streamIcon = item.imageUrl ?: ""
                )
                requireActivity().supportFragmentManager.beginTransaction()
                    .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.FORWARD) }
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
                    .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.FORWARD) }
                    .replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    // --- Key handling ---

    override fun onKeyEvent(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Dismiss content info overlay first
                if (contentInfoHelper?.dismiss() == true) return true
                // Clear search text before exiting
                if (searchEditText.text.isNotEmpty()) {
                    searchEditText.text.clear()
                    searchEditText.requestFocus()
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> {
                if (voiceBtn.visibility == View.VISIBLE) {
                    launchVoiceSearch()
                    return true
                }
            }
        }
        return false
    }

    override fun onDestroyView() {
        searchBarAnimator?.release()
        searchBarAnimator = null
        contentInfoHelper?.cleanup()
        contentInfoHelper = null
        super.onDestroyView()
    }
}
