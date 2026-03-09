package com.ooustream.iptv.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import coil.request.CachePolicy
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import com.ooustream.iptv.common.AuroraBackgroundView
import com.ooustream.iptv.common.BrowseCardFocusHelper
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.safeReplaceAll
import com.ooustream.iptv.common.safeSetSelectedPosition
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.GoldGlowFocusDrawable
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterUrlRewriter
import com.ooustream.iptv.common.PosterPresenter
import com.ooustream.iptv.common.ScreenPreWarmer
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.common.dp
import com.ooustream.iptv.data.UserPlanManager
import com.ooustream.iptv.multiview.MultiViewLockedPopup
import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.model.VodStream
import com.ooustream.iptv.data.local.entity.SeriesTrackingEntity
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.MainActivity
import com.ooustream.iptv.favorites.FavoritesFragment
import com.ooustream.iptv.livetv.LiveTvFragment
import com.ooustream.iptv.onboarding.OnboardingOverlay
import com.ooustream.iptv.player.ChannelListHolder
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import com.ooustream.iptv.recommendation.RecommendedItem
import com.ooustream.iptv.search.SearchFragment
import com.ooustream.iptv.series.SeriesDetailFragment
import com.ooustream.iptv.series.SeriesFragment
import com.ooustream.iptv.settings.SettingsFragment
import com.ooustream.iptv.vod.VodFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment(), KeyEventHandler {

    @Inject lateinit var userPlanManager: UserPlanManager

    private val viewModel: HomeViewModel by viewModels()

    // Screen pre-warmer: pre-fetches data when user hovers on section cards
    private var screenPreWarmer: ScreenPreWarmer? = null

    // Onboarding overlay (shown once after first login)
    private var onboardingOverlay: OnboardingOverlay? = null

    // Hero rotation
    private var featuredItems: List<FeaturedItem> = emptyList()
    private var heroIndex = 0
    private var heroRotationJob: Job? = null

    // Views
    private lateinit var heroBackdrop: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroGenre: TextView
    private lateinit var heroWatchNow: TextView
    private lateinit var heroMoreInfo: TextView
    private lateinit var heroIndicators: LinearLayout
    private lateinit var continueWatchingLabel: TextView
    private lateinit var continueWatchingRow: HorizontalGridView
    private lateinit var newEpisodesLabel: TextView
    private lateinit var newEpisodesRow: HorizontalGridView
    private lateinit var watchAgainLabel: TextView
    private lateinit var watchAgainRow: HorizontalGridView
    private lateinit var forYouLabel: TextView
    private lateinit var forYouRow: HorizontalGridView
    private lateinit var forYouLiveLabel: TextView
    private lateinit var forYouLiveRow: HorizontalGridView
    private lateinit var sectionsLabel: TextView
    private lateinit var sectionsRow: HorizontalGridView
    private lateinit var trendingLabel: TextView
    private lateinit var trendingRow: HorizontalGridView
    private lateinit var trendingSeriesLabel: TextView
    private lateinit var trendingSeriesRow: HorizontalGridView
    private lateinit var auroraBackground: AuroraBackgroundView

    // Adapters
    private val cwObjectAdapter = ArrayObjectAdapter(ContinueWatchingPresenter())
    private val newEpisodesObjectAdapter = ArrayObjectAdapter(NewEpisodesPresenter())
    private val watchAgainObjectAdapter = ArrayObjectAdapter(WatchItAgainPresenter())
    private val forYouObjectAdapter = ArrayObjectAdapter(ForYouPresenter())
    private val forYouLiveObjectAdapter = ArrayObjectAdapter(ForYouLivePresenter())
    private val sectionPresenterSelector = ClassPresenterSelector().apply {
        addClassPresenter(SectionItem::class.java, SectionCardPresenter())
        addClassPresenter(MultiViewHeroItem::class.java, MultiViewHeroPresenter())
    }
    private val sectionObjectAdapter = ArrayObjectAdapter(sectionPresenterSelector)
    private val trendingObjectAdapter = ArrayObjectAdapter(PosterPresenter())
    private val trendingSeriesObjectAdapter = ArrayObjectAdapter(PosterPresenter())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        screenPreWarmer = ScreenPreWarmer(viewLifecycleOwner.lifecycleScope)
        bindViews(view)
        setupAuroraBackground(view)
        setupFrostedHeaderScroll(view)
        setupHeaderIcons(view)
        setupSectionsRow()
        setupTrendingRow()
        setupContinueWatchingRow()
        setupNewEpisodesRow()
        setupForYouRow()
        setupForYouLiveRow()
        setupHeroClickListener()
        observeFeaturedContent()
        observeContinueWatching()
        observeNewEpisodes()
        observeForYouContent()
        observeForYouLiveContent()
        observeTrendingContent()
        setupTrendingSeriesRow()
        observeTrendingSeries()
        setupWatchAgainRow()
        observeWatchItAgain()
        loadFeatured()
        setupOnboarding(view)

        // Restore focus to where user was before navigating away, or default to hero
        restoreFocusState()
    }

    override fun onDestroyView() {
        // Save focus position for restoration on back navigation
        saveFocusState()
        heroRotationJob?.cancel()
        screenPreWarmer?.reset()
        screenPreWarmer = null
        onboardingOverlay = null
        super.onDestroyView()
    }

    private fun saveFocusState() {
        val focused = view?.findFocus() ?: return
        // Check if focus is in a HorizontalGridView row
        var current: View? = focused
        while (current != null) {
            when (current.id) {
                R.id.sections_row -> {
                    viewModel.savedFocusRowId = R.id.sections_row
                    viewModel.savedFocusPosition = sectionsRow.selectedPosition
                    return
                }
                R.id.continue_watching_row -> {
                    viewModel.savedFocusRowId = R.id.continue_watching_row
                    viewModel.savedFocusPosition = continueWatchingRow.selectedPosition
                    return
                }
                R.id.new_episodes_row -> {
                    viewModel.savedFocusRowId = R.id.new_episodes_row
                    viewModel.savedFocusPosition = newEpisodesRow.selectedPosition
                    return
                }
                R.id.watch_again_row -> {
                    viewModel.savedFocusRowId = R.id.watch_again_row
                    viewModel.savedFocusPosition = watchAgainRow.selectedPosition
                    return
                }
                R.id.for_you_row -> {
                    viewModel.savedFocusRowId = R.id.for_you_row
                    viewModel.savedFocusPosition = forYouRow.selectedPosition
                    return
                }
                R.id.for_you_live_row -> {
                    viewModel.savedFocusRowId = R.id.for_you_live_row
                    viewModel.savedFocusPosition = forYouLiveRow.selectedPosition
                    return
                }
                R.id.trending_row -> {
                    viewModel.savedFocusRowId = R.id.trending_row
                    viewModel.savedFocusPosition = trendingRow.selectedPosition
                    return
                }
                R.id.trending_series_row -> {
                    viewModel.savedFocusRowId = R.id.trending_series_row
                    viewModel.savedFocusPosition = trendingSeriesRow.selectedPosition
                    return
                }
            }
            val p = current.parent
            current = if (p is View) p else null
        }
        // Hero buttons
        if (focused.id == R.id.hero_watch_now || focused.id == R.id.hero_more_info) {
            viewModel.savedFocusRowId = focused.id
        }
    }

    private fun restoreFocusState() {
        val rowId = viewModel.savedFocusRowId
        val pos = viewModel.savedFocusPosition
        viewModel.savedFocusRowId = -1
        viewModel.savedFocusPosition = -1

        if (rowId < 0) {
            heroWatchNow.post { heroWatchNow.requestFocus() }
            return
        }

        when (rowId) {
            R.id.sections_row -> sectionsRow.post {
                sectionsRow.safeSetSelectedPosition(pos, sectionObjectAdapter.size())
                sectionsRow.requestFocus()
            }
            R.id.continue_watching_row -> continueWatchingRow.post {
                if (cwObjectAdapter.size() > 0) {
                    continueWatchingRow.safeSetSelectedPosition(pos, cwObjectAdapter.size())
                    continueWatchingRow.requestFocus()
                } else {
                    heroWatchNow.requestFocus()
                }
            }
            R.id.new_episodes_row -> newEpisodesRow.post {
                if (newEpisodesObjectAdapter.size() > 0) {
                    newEpisodesRow.safeSetSelectedPosition(pos, newEpisodesObjectAdapter.size())
                    newEpisodesRow.requestFocus()
                } else {
                    heroWatchNow.requestFocus()
                }
            }
            R.id.watch_again_row -> watchAgainRow.post {
                if (watchAgainObjectAdapter.size() > 0) {
                    watchAgainRow.safeSetSelectedPosition(pos, watchAgainObjectAdapter.size())
                    watchAgainRow.requestFocus()
                } else {
                    heroWatchNow.requestFocus()
                }
            }
            R.id.for_you_row -> forYouRow.post {
                if (forYouObjectAdapter.size() > 0) {
                    forYouRow.safeSetSelectedPosition(pos, forYouObjectAdapter.size())
                    forYouRow.requestFocus()
                } else {
                    heroWatchNow.requestFocus()
                }
            }
            R.id.for_you_live_row -> forYouLiveRow.post {
                if (forYouLiveObjectAdapter.size() > 0) {
                    forYouLiveRow.safeSetSelectedPosition(pos, forYouLiveObjectAdapter.size())
                    forYouLiveRow.requestFocus()
                } else {
                    heroWatchNow.requestFocus()
                }
            }
            R.id.trending_row -> trendingRow.post {
                if (trendingObjectAdapter.size() > 0) {
                    trendingRow.safeSetSelectedPosition(pos, trendingObjectAdapter.size())
                    trendingRow.requestFocus()
                } else {
                    heroWatchNow.requestFocus()
                }
            }
            R.id.trending_series_row -> trendingSeriesRow.post {
                if (trendingSeriesObjectAdapter.size() > 0) {
                    trendingSeriesRow.safeSetSelectedPosition(pos, trendingSeriesObjectAdapter.size())
                    trendingSeriesRow.requestFocus()
                } else {
                    heroWatchNow.requestFocus()
                }
            }
            R.id.hero_watch_now -> heroWatchNow.post { heroWatchNow.requestFocus() }
            R.id.hero_more_info -> heroMoreInfo.post { heroMoreInfo.requestFocus() }
            else -> heroWatchNow.post { heroWatchNow.requestFocus() }
        }
    }

    // ── KeyEventHandler ────────────────────────────────────────────────

    override fun onKeyEvent(keyCode: Int): Boolean {
        val overlay = onboardingOverlay ?: return false
        return overlay.handleKeyEvent(keyCode)
    }

    // ── Onboarding ─────────────────────────────────────────────────────

    private fun setupOnboarding(view: View) {
        val prefs = requireContext().getSharedPreferences("ooustream_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_completed", false)) return

        val overlay = OnboardingOverlay(requireContext())
        onboardingOverlay = overlay

        (view as? ViewGroup)?.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        overlay.onCompleted = {
            prefs.edit().putBoolean("onboarding_completed", true).apply()
            (view as? ViewGroup)?.removeView(overlay)
            onboardingOverlay = null
        }

        overlay.show()
    }

    // ── View Binding ─────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        heroBackdrop = view.findViewById(R.id.hero_backdrop)
        heroTitle = view.findViewById(R.id.hero_title)
        heroGenre = view.findViewById(R.id.hero_genre)
        heroWatchNow = view.findViewById(R.id.hero_watch_now)
        heroMoreInfo = view.findViewById(R.id.hero_more_info)
        heroIndicators = view.findViewById(R.id.hero_indicators)
        continueWatchingLabel = view.findViewById(R.id.continue_watching_label)
        continueWatchingRow = view.findViewById(R.id.continue_watching_row)
        newEpisodesLabel = view.findViewById(R.id.new_episodes_label)
        newEpisodesRow = view.findViewById(R.id.new_episodes_row)
        watchAgainLabel = view.findViewById(R.id.watch_again_label)
        watchAgainRow = view.findViewById(R.id.watch_again_row)
        forYouLabel = view.findViewById(R.id.for_you_label)
        forYouRow = view.findViewById(R.id.for_you_row)
        forYouLiveLabel = view.findViewById(R.id.for_you_live_label)
        forYouLiveRow = view.findViewById(R.id.for_you_live_row)
        sectionsLabel = view.findViewById(R.id.sections_label)
        sectionsRow = view.findViewById(R.id.sections_row)
        trendingLabel = view.findViewById(R.id.trending_label)
        trendingRow = view.findViewById(R.id.trending_row)
        trendingSeriesLabel = view.findViewById(R.id.trending_series_label)
        trendingSeriesRow = view.findViewById(R.id.trending_series_row)
    }

    // ── Aurora Background ──────────────────────────────────────────────────

    private fun setupAuroraBackground(view: View) {
        val auroraPlaceholder = view.findViewById<View>(R.id.aurora_bg)
        val parent = auroraPlaceholder.parent as ViewGroup
        val index = parent.indexOfChild(auroraPlaceholder)
        parent.removeView(auroraPlaceholder)
        auroraBackground = AuroraBackgroundView(requireContext())
        auroraBackground.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        parent.addView(auroraBackground, index)
    }

    // ── Row Focus Dimming ─────────────────────────────────────────────────

    /**
     * Attach neighbor dimming + row header highlighting to a card view.
     * Chains with any existing onFocusChangeListener set by the presenter.
     */
    private fun attachRowDimming(
        gridView: HorizontalGridView,
        label: TextView,
        viewHolder: ItemBridgeAdapter.ViewHolder,
        position: Int
    ) {
        val originalListener = viewHolder.itemView.onFocusChangeListener
        viewHolder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            originalListener?.onFocusChange(v, hasFocus)
            if (hasFocus) {
                BrowseCardFocusHelper.applyNeighborDimming(gridView, position)
                BrowseCardFocusHelper.highlightRowHeader(label, true)
            } else {
                v.postDelayed({
                    if (!gridView.hasFocus()) {
                        BrowseCardFocusHelper.clearDimming(gridView)
                        BrowseCardFocusHelper.highlightRowHeader(label, false)
                    }
                }, 50)
            }
        }
    }

    // ── Frosted Header Scroll ─────────────────────────────────────────────

    private fun setupFrostedHeaderScroll(view: View) {
        val scrollView = view.findViewById<NestedScrollView>(R.id.home_scroll)
        val frostedHeader = view.findViewById<LinearLayout>(R.id.frosted_header)
        val heroContainer = view.findViewById<View>(R.id.hero_container)

        // Hide frosted header on mobile (bottom nav handles navigation)
        if (!DeviceUtils.isTV(requireContext())) {
            frostedHeader.visibility = View.GONE
            return
        }

        scrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                val heroHeight = heroContainer.height.toFloat()
                if (heroHeight > 0) {
                    val progress = (scrollY / heroHeight).coerceIn(0f, 1f)
                    frostedHeader.alpha = progress
                }
            }
        )
    }

    // ── Header Icons ──────────────────────────────────────────────────────

    private fun setupHeaderIcons(view: View) {
        view.findViewById<ImageView>(R.id.header_search).setOnClickListener {
            navigateToSection(SectionItem("search", "Search", R.drawable.ic_search, 0))
        }
        view.findViewById<ImageView>(R.id.header_settings).setOnClickListener {
            navigateToSection(SectionItem("settings", "Settings", R.drawable.ic_settings, 0))
        }

        // Focus scale animation for header icons
        listOf(R.id.header_search, R.id.header_settings).forEach { iconId ->
            view.findViewById<ImageView>(iconId).setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.2f else 1f)
                    .scaleY(if (hasFocus) 1.2f else 1f)
                    .setDuration(200)
                    .start()
            }
        }
    }

    // ── Hero Page Indicators ──────────────────────────────────────────────

    private fun updateHeroIndicators(count: Int, activeIndex: Int) {
        heroIndicators.removeAllViews()
        for (i in 0 until count) {
            val dot = View(requireContext()).apply {
                val dotWidth = if (i == activeIndex) 24.dp else 8.dp
                val dotHeight = 8.dp
                layoutParams = LinearLayout.LayoutParams(dotWidth, dotHeight).apply {
                    marginEnd = 6.dp
                }
                setBackgroundResource(
                    if (i == activeIndex) R.drawable.bg_hero_indicator_active
                    else R.drawable.bg_hero_indicator
                )
            }
            heroIndicators.addView(dot)
        }
    }

    // ── Sections Row ─────────────────────────────────────────────────────

    private fun setupSectionsRow() {
        sectionObjectAdapter.clear()
        viewModel.sections.forEach { section ->
            sectionObjectAdapter.add(section)
            // Add MultiView hero card after Search section card
            if (section.id == "search" && userPlanManager.isDeviceCapable()) {
                sectionObjectAdapter.add(MultiViewHeroItem)
            }
        }

        val bridgeAdapter = ItemBridgeAdapter(sectionObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val item = sectionObjectAdapter.get(position)
                    if (item is SectionItem) {
                        navigateToSection(item)
                    } else if (item is MultiViewHeroItem) {
                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle("MultiView")
                            .setMessage("Coming Soon!")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }

                // Screen pre-warming: when the user hovers on a section card
                // for 500ms, pre-fetch that section's category data
                val originalListener = viewHolder.itemView.onFocusChangeListener
                viewHolder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    // Preserve existing focus animation from SectionCardPresenter
                    originalListener?.onFocusChange(v, hasFocus)

                    if (hasFocus) {
                        val item = sectionObjectAdapter.get(position)
                        if (item is SectionItem) {
                            screenPreWarmer?.onSectionFocused(item.id) {
                                viewModel.preWarmSection(item.id)
                            }
                        }
                    } else {
                        screenPreWarmer?.onSectionBlurred()
                    }
                }
                attachRowDimming(sectionsRow, sectionsLabel, viewHolder, position)
            }
        })

        sectionsRow.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        sectionsRow.adapter = bridgeAdapter
    }

    // ── Trending Row ───────────────────────────────────────────────────

    private fun setupTrendingRow() {
        val bridgeAdapter = ItemBridgeAdapter(trendingObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val item = trendingObjectAdapter.get(position)
                    if (item is PosterItem) {
                        val ext = item.extension ?: "mp4"
                        val url = viewModel.buildVodStreamUrl(item.id, ext)
                        val fragment = OoustreamPlaybackFragment.newInstance(
                            streamUrl = url,
                            contentType = ContentType.VOD,
                            streamId = item.id.toString(),
                            streamName = item.title,
                            streamIcon = item.imageUrl ?: ""
                        )
                        val tx = requireActivity().supportFragmentManager.beginTransaction()
                        FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                        tx.replace(R.id.main_container, fragment)
                            .addToBackStack(null)
                            .commit()
                    }
                }
                attachRowDimming(trendingRow, trendingLabel, viewHolder, position)
            }
        })

        trendingRow.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        trendingRow.adapter = bridgeAdapter
    }

    private fun observeTrendingContent() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.trendingContent.collect { items ->
                    val posterItems = items.map { vod ->
                        PosterItem(
                            id = vod.streamId,
                            title = vod.name,
                            imageUrl = vod.streamIcon,
                            rating = vod.rating,
                            extension = vod.containerExtension,
                            type = "vod",
                            tmdbId = vod.tmdbId
                        )
                    }
                    trendingObjectAdapter.safeReplaceAll(posterItems)
                    if (items.isNotEmpty()) {
                        trendingLabel.visibility = View.VISIBLE
                        trendingRow.visibility = View.VISIBLE
                    } else {
                        trendingLabel.visibility = View.GONE
                        trendingRow.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ── Trending Series Row ──────────────────────────────────────────────

    private fun setupTrendingSeriesRow() {
        val bridgeAdapter = ItemBridgeAdapter(trendingSeriesObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val item = trendingSeriesObjectAdapter.get(position)
                    if (item is PosterItem) {
                        val fragment = SeriesDetailFragment.newInstance(item.id, item.title)
                        val tx = requireActivity().supportFragmentManager.beginTransaction()
                        FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
                        tx.replace(R.id.main_container, fragment)
                            .addToBackStack(null)
                            .commit()
                    }
                }
                attachRowDimming(trendingSeriesRow, trendingSeriesLabel, viewHolder, position)
            }
        })
        trendingSeriesRow.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        trendingSeriesRow.adapter = bridgeAdapter
    }

    private fun observeTrendingSeries() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.trendingSeries.collect { items ->
                    val posterItems = items.map { series ->
                        PosterItem(
                            id = series.seriesId,
                            title = series.name,
                            imageUrl = series.cover,
                            rating = series.rating,
                            extension = null,
                            type = "series",
                            tmdbId = series.tmdbId
                        )
                    }
                    trendingSeriesObjectAdapter.safeReplaceAll(posterItems)
                    if (items.isNotEmpty()) {
                        trendingSeriesLabel.visibility = View.VISIBLE
                        trendingSeriesRow.visibility = View.VISIBLE
                    } else {
                        trendingSeriesLabel.visibility = View.GONE
                        trendingSeriesRow.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ── Continue Watching Row ────────────────────────────────────────────

    private fun setupContinueWatchingRow() {
        val bridgeAdapter = ItemBridgeAdapter(cwObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val item = cwObjectAdapter.get(position)
                    if (item is WatchProgressEntity) {
                        navigateToContinueWatching(item)
                    }
                }
                attachRowDimming(continueWatchingRow, continueWatchingLabel, viewHolder, position)
            }
        })

        continueWatchingRow.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        continueWatchingRow.adapter = bridgeAdapter
    }

    private fun observeContinueWatching() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.continueWatching.collect { items ->
                    updateContinueWatchingRow(items)
                }
            }
        }
    }

    private fun updateContinueWatchingRow(items: List<WatchProgressEntity>) {
        cwObjectAdapter.safeReplaceAll(items)
        if (items.isNotEmpty()) {
            continueWatchingLabel.visibility = View.VISIBLE
            continueWatchingRow.visibility = View.VISIBLE
        } else {
            continueWatchingLabel.visibility = View.GONE
            continueWatchingRow.visibility = View.GONE
        }
    }

    // ── New Episodes Row ──────────────────────────────────────────────

    private fun setupNewEpisodesRow() {
        val bridgeAdapter = ItemBridgeAdapter(newEpisodesObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val item = newEpisodesObjectAdapter.get(position)
                    if (item is SeriesTrackingEntity) {
                        val fragment = SeriesDetailFragment.newInstance(item.seriesId, item.seriesTitle)
                        val tx = requireActivity().supportFragmentManager.beginTransaction()
                        FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
                        tx.replace(R.id.main_container, fragment)
                            .addToBackStack(null)
                            .commit()
                    }
                }
                attachRowDimming(newEpisodesRow, newEpisodesLabel, viewHolder, position)
            }
        })
        newEpisodesRow.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        newEpisodesRow.adapter = bridgeAdapter
    }

    private fun observeNewEpisodes() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.newEpisodes.collect { items ->
                    newEpisodesObjectAdapter.safeReplaceAll(items)
                    if (items.isNotEmpty()) {
                        newEpisodesLabel.visibility = View.VISIBLE
                        newEpisodesRow.visibility = View.VISIBLE
                    } else {
                        newEpisodesLabel.visibility = View.GONE
                        newEpisodesRow.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ── Watch It Again Row ──────────────────────────────────────────────

    private fun setupWatchAgainRow() {
        val bridgeAdapter = ItemBridgeAdapter(watchAgainObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val item = watchAgainObjectAdapter.get(position)
                    if (item is WatchProgressEntity) {
                        if (item.type == "series" && item.seriesId != null) {
                            val fragment = SeriesDetailFragment.newInstance(item.seriesId, item.name)
                            val tx = requireActivity().supportFragmentManager.beginTransaction()
                            FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
                            tx.replace(R.id.main_container, fragment)
                                .addToBackStack(null)
                                .commit()
                        } else {
                            val id = item.streamId.toIntOrNull() ?: return@setOnClickListener
                            val url = viewModel.buildVodStreamUrl(id, "mp4")
                            val fragment = OoustreamPlaybackFragment.newInstance(
                                streamUrl = url,
                                contentType = ContentType.VOD,
                                streamId = item.streamId,
                                streamName = item.name,
                                streamIcon = item.icon ?: ""
                            )
                            val tx = requireActivity().supportFragmentManager.beginTransaction()
                            FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                            tx.replace(R.id.main_container, fragment)
                                .addToBackStack(null)
                                .commit()
                        }
                    }
                }
                attachRowDimming(watchAgainRow, watchAgainLabel, viewHolder, position)
            }
        })
        watchAgainRow.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        watchAgainRow.adapter = bridgeAdapter
    }

    private fun observeWatchItAgain() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.watchItAgain.collect { items ->
                    watchAgainObjectAdapter.safeReplaceAll(items)
                    if (items.isNotEmpty()) {
                        watchAgainLabel.visibility = View.VISIBLE
                        watchAgainRow.visibility = View.VISIBLE
                    } else {
                        watchAgainLabel.visibility = View.GONE
                        watchAgainRow.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ── For You Row ────────────────────────────────────────────────────

    private fun setupForYouRow() {
        val bridgeAdapter = ItemBridgeAdapter(forYouObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val item = forYouObjectAdapter.get(position)
                    if (item is RecommendedItem) {
                        navigateToRecommendation(item)
                    }
                }
                attachRowDimming(forYouRow, forYouLabel, viewHolder, position)
            }
        })

        forYouRow.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        forYouRow.adapter = bridgeAdapter
    }

    private fun observeForYouContent() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.forYouContent.collect { items ->
                    updateForYouRow(items)
                }
            }
        }
    }

    private fun updateForYouRow(items: List<RecommendedItem>) {
        forYouObjectAdapter.safeReplaceAll(items)
        if (items.isNotEmpty()) {
            forYouLabel.visibility = View.VISIBLE
            forYouRow.visibility = View.VISIBLE
        } else {
            forYouLabel.visibility = View.GONE
            forYouRow.visibility = View.GONE
        }
    }

    // ── For You — Live Now Row ─────────────────────────────────────────

    private fun setupForYouLiveRow() {
        val bridgeAdapter = ItemBridgeAdapter(forYouLiveObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val item = forYouLiveObjectAdapter.get(position)
                    if (item is ForYouChannel) {
                        navigateToLiveChannel(item)
                    }
                }
                attachRowDimming(forYouLiveRow, forYouLiveLabel, viewHolder, position)
            }
        })

        forYouLiveRow.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        forYouLiveRow.adapter = bridgeAdapter
    }

    private fun observeForYouLiveContent() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.forYouLiveNow.collect { channels ->
                    forYouLiveObjectAdapter.safeReplaceAll(channels)
                    if (channels.isNotEmpty()) {
                        forYouLiveLabel.visibility = View.VISIBLE
                        forYouLiveRow.visibility = View.VISIBLE
                    } else {
                        forYouLiveLabel.visibility = View.GONE
                        forYouLiveRow.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun navigateToLiveChannel(channel: ForYouChannel) {
        val url = viewModel.buildLiveStreamUrl(channel.channelId)

        // Build channel list from all "For You — Live Now" recommendations
        // so the player supports D-pad channel switching
        val allChannels = viewModel.forYouLiveNow.value
        val liveStreams = allChannels.map { ch ->
            LiveStream(
                num = null, name = ch.channelName, streamType = "live",
                streamId = ch.channelId, streamIcon = ch.channelIcon,
                epgChannelId = null, added = null, categoryId = null,
                customSid = null, tvArchive = null, directSource = null,
                tvArchiveDuration = null
            )
        }
        val idx = allChannels.indexOfFirst { it.channelId == channel.channelId }.coerceAtLeast(0)
        ChannelListHolder.channels = liveStreams
        ChannelListHolder.currentIndex = idx

        val fragment = OoustreamPlaybackFragment.newInstance(
            streamUrl = url,
            contentType = ContentType.LIVE,
            streamId = channel.channelId.toString(),
            streamName = channel.channelName
        )
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
        tx.replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    // ── Hero / Featured Content ──────────────────────────────────────────

    private fun loadFeatured() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadFeaturedContent()
        }
    }

    private fun observeFeaturedContent() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.featuredContent.collect { items ->
                    featuredItems = items
                    heroIndex = 0
                    if (items.isNotEmpty()) {
                        displayHeroItem(items[0], animate = false)
                        startHeroRotation()
                    }
                }
            }
        }
    }

    private fun displayHeroItem(item: FeaturedItem, animate: Boolean) {
        if (animate) {
            heroBackdrop.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    loadHeroImage(item)
                    heroTitle.text = item.title
                    heroGenre.text = item.genre
                    heroBackdrop.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .start()
                }
                .start()
        } else {
            loadHeroImage(item)
            heroTitle.text = item.title
            heroGenre.text = item.genre
        }
        updateHeroIndicators(featuredItems.size, heroIndex)
    }

    private fun loadHeroImage(item: FeaturedItem) {
        if (!item.backdropUrl.isNullOrBlank()) {
            heroBackdrop.load(PosterUrlRewriter.rewriteBackdrop(item.backdropUrl)) {
                crossfade(false)
                memoryCachePolicy(CachePolicy.ENABLED)
                allowHardware(false) // Required for Palette extraction
                listener(onSuccess = { _, result ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                            if (bitmap != null) {
                                val color = PaletteExtractor.extractDominant(bitmap)
                                auroraBackground.setAmbientColor(color)
                            }
                        } catch (_: Exception) { }
                    }
                })
            }
        }
    }

    private fun startHeroRotation() {
        heroRotationJob?.cancel()
        if (featuredItems.size <= 1) return

        heroRotationJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(8_000)
                val items = featuredItems
                val size = items.size
                if (size <= 1) break
                heroIndex = (heroIndex + 1) % size
                val item = items.getOrNull(heroIndex) ?: break
                displayHeroItem(item, animate = true)
            }
        }
    }

    private fun setupHeroClickListener() {
        heroWatchNow.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                if (DeviceUtils.isTV(requireContext())) {
                    v.overlay.add(GoldGlowFocusDrawable())
                }
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
            } else {
                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }

        heroWatchNow.setOnClickListener {
            if (featuredItems.isNotEmpty()) {
                val item = featuredItems[heroIndex]
                val streamId = item.streamId.toIntOrNull() ?: return@setOnClickListener
                val url = viewModel.buildVodStreamUrl(streamId, item.containerExtension)
                val fragment = OoustreamPlaybackFragment.newInstance(
                    streamUrl = url,
                    contentType = ContentType.VOD,
                    streamId = item.streamId,
                    streamName = item.title,
                    streamIcon = item.backdropUrl ?: ""
                )
                val tx = requireActivity().supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                tx.replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        }

        // "More Info" button — same as Watch Now for now
        heroMoreInfo.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                if (DeviceUtils.isTV(requireContext())) {
                    v.overlay.add(GoldGlowFocusDrawable())
                }
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
            } else {
                v.overlay.clear()
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
        heroMoreInfo.setOnClickListener {
            heroWatchNow.performClick()
        }
    }

    // ── Navigation ───────────────────────────────────────────────────────

    private fun navigateToContinueWatching(item: WatchProgressEntity) {
        val contentType = when (item.type.lowercase()) {
            "live" -> ContentType.LIVE
            "vod" -> ContentType.VOD
            "series" -> ContentType.SERIES
            else -> ContentType.VOD
        }

        // Use the saved stream URL if available, otherwise build from streamId
        val streamUrl = if (!item.extra.isNullOrBlank()) {
            item.extra
        } else {
            val id = item.streamId.toIntOrNull() ?: return
            when (contentType) {
                ContentType.LIVE -> viewModel.buildLiveStreamUrl(id)
                ContentType.VOD -> viewModel.buildVodStreamUrl(id, "mp4")
                ContentType.SERIES -> viewModel.buildSeriesStreamUrl(id, "mp4")
            }
        }

        val fragment = OoustreamPlaybackFragment.newInstance(
            streamUrl = streamUrl,
            contentType = contentType,
            streamId = item.streamId,
            streamName = item.name,
            streamIcon = item.icon ?: "",
            seriesId = item.seriesId ?: 0,
            seasonNum = item.seasonNum ?: 0,
            episodeNum = item.episodeNum ?: 0
        )
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
        tx.replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToRecommendation(item: RecommendedItem) {
        when (item.type) {
            "series" -> {
                // Navigate to series detail screen
                val fragment = SeriesDetailFragment.newInstance(item.streamId, item.name)
                val tx = requireActivity().supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
                tx.replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
            "vod" -> {
                // Navigate directly to playback
                val ext = item.containerExtension ?: "mp4"
                val url = viewModel.buildVodStreamUrl(item.streamId, ext)
                val fragment = OoustreamPlaybackFragment.newInstance(
                    streamUrl = url,
                    contentType = ContentType.VOD,
                    streamId = item.streamId.toString(),
                    streamName = item.name,
                    streamIcon = item.icon ?: ""
                )
                val tx = requireActivity().supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                tx.replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
            "live" -> {
                // Navigate to playback for live content
                val fragment = OoustreamPlaybackFragment.newInstance(
                    streamUrl = "",
                    contentType = ContentType.LIVE,
                    streamId = item.streamId.toString(),
                    streamName = item.name
                )
                val tx = requireActivity().supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                tx.replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    private fun navigateToSection(section: SectionItem) {
        val fragment = when (section.id) {
            "live" -> LiveTvFragment()
            "movies" -> VodFragment()
            "series" -> SeriesFragment()
            "favorites" -> FavoritesFragment()
            "search" -> SearchFragment()
            "settings" -> SettingsFragment()
            else -> return
        }
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
        tx.replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}
