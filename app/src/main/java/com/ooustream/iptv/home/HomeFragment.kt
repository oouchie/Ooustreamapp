package com.ooustream.iptv.home

import android.app.ActivityManager
import android.content.Context
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
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
import com.ooustream.iptv.recommendation.BecauseYouWatchedRow
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
import java.util.Calendar

@AndroidEntryPoint
class HomeFragment : Fragment(), KeyEventHandler {

    @Inject lateinit var userPlanManager: UserPlanManager
    @Inject lateinit var credentialStore: com.ooustream.iptv.data.repository.CredentialStore

    private val viewModel: HomeViewModel by viewModels()

    // Screen pre-warmer: pre-fetches data when user hovers on section cards
    private var screenPreWarmer: ScreenPreWarmer? = null

    // Onboarding overlay (shown once after first login)
    private var onboardingOverlay: OnboardingOverlay? = null

    // Hero rotation
    private var featuredItems: List<FeaturedItem> = emptyList()
    private var heroIndex = 0
    private var heroRotationJob: Job? = null

    // Hero trailer auto-play
    private var heroPreviewPlayer: ExoPlayer? = null
    private var heroPreviewView: PlayerView? = null
    private var heroDwellJob: Job? = null
    private var heroPreviewActive = false
    private var isLowMemoryDevice = false
    private var heroContentOverlay: View? = null // LinearLayout with title/genre/buttons
    private var heroGradientBottom: View? = null
    private var heroGradientLeft: View? = null
    private var heroGradientTop: View? = null
    private var heroAmbientWash: View? = null
    // heroProgress removed — clean hero transitions
    private var heroDescription: TextView? = null
    private var heroMetadataRow: View? = null
    private var heroRating: TextView? = null
    private var heroYear: TextView? = null
    private var heroGenreChip: TextView? = null
    private var heroContainer: View? = null
    private var kenBurnsJob: Job? = null

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
    private lateinit var channelStripLabel: TextView
    private lateinit var channelStripRow: HorizontalGridView
    private lateinit var sectionsLabel: TextView
    private lateinit var sectionsRow: HorizontalGridView
    private lateinit var trendingLabel: TextView
    private lateinit var trendingRow: HorizontalGridView
    private lateinit var trendingSeriesLabel: TextView
    private lateinit var trendingSeriesRow: HorizontalGridView
    private lateinit var auroraBackground: AuroraBackgroundView
    private lateinit var becauseYouWatchedContainer: LinearLayout
    private lateinit var sportsBanner: View
    private lateinit var top10Label: TextView
    private lateinit var top10Row: HorizontalGridView
    private lateinit var genreRowsContainer: LinearLayout
    private lateinit var heroGreeting: TextView

    // Adapters
    private val cwObjectAdapter = ArrayObjectAdapter(ContinueWatchingPresenter())
    private val newEpisodesObjectAdapter = ArrayObjectAdapter(NewEpisodesPresenter())
    private val watchAgainObjectAdapter = ArrayObjectAdapter(WatchItAgainPresenter())
    private val forYouObjectAdapter = ArrayObjectAdapter(ForYouPresenter())
    private val forYouLiveObjectAdapter = ArrayObjectAdapter(ForYouLivePresenter())
    private val channelStripObjectAdapter = ArrayObjectAdapter(ChannelStripPresenter())
    private val sectionPresenterSelector = ClassPresenterSelector().apply {
        addClassPresenter(SectionItem::class.java, SectionCardPresenter())
        addClassPresenter(MultiViewHeroItem::class.java, MultiViewHeroPresenter())
    }
    private val sectionObjectAdapter = ArrayObjectAdapter(sectionPresenterSelector)
    private val trendingObjectAdapter = ArrayObjectAdapter(PosterPresenter())
    private val trendingSeriesObjectAdapter = ArrayObjectAdapter(PosterPresenter())
    private val top10ObjectAdapter = ArrayObjectAdapter(Top10Presenter())

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
        // Hero trailer: check device memory and bind the preview PlayerView
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        isLowMemoryDevice = activityManager.memoryClass <= 128
        heroPreviewView = view.findViewById(R.id.hero_preview_player)
        heroContentOverlay = view.findViewById(R.id.hero_content_overlay)
        heroGradientBottom = view.findViewById(R.id.hero_gradient_bottom)
        heroGradientLeft = view.findViewById(R.id.hero_gradient_left)
        heroGradientTop = view.findViewById(R.id.hero_gradient_top)
        heroAmbientWash = view.findViewById(R.id.hero_ambient_wash)
        // heroProgress removed
        heroDescription = view.findViewById(R.id.hero_description)
        heroMetadataRow = view.findViewById(R.id.hero_metadata_row)
        heroRating = view.findViewById(R.id.hero_rating)
        heroYear = view.findViewById(R.id.hero_year)
        heroGenreChip = view.findViewById(R.id.hero_genre_chip)
        heroContainer = view.findViewById(R.id.hero_container)

        // Percentage-based hero height — adapts to any screen/density
        val screenHeight = resources.displayMetrics.heightPixels
        val heroPercent = resources.getInteger(R.integer.hero_height_percent) / 100f
        heroContainer?.layoutParams = heroContainer?.layoutParams?.apply {
            height = (screenHeight * heroPercent).toInt()
        }
        // Size gradient layers relative to hero
        heroContainer?.post {
            val heroH = heroContainer?.height ?: return@post
            // Left gradient: 60% of hero width
            heroGradientLeft?.layoutParams = heroGradientLeft?.layoutParams?.apply {
                width = (resources.displayMetrics.widthPixels * 0.60f).toInt()
            }
        }

        setupAuroraBackground(view)
        setupFrostedHeaderScroll(view)
        setupHeaderIcons(view)
        setupSectionsRow()
        setupTrendingRow()
        setupContinueWatchingRow()
        setupNewEpisodesRow()
        setupForYouRow()
        setupForYouLiveRow()
        setupChannelStripRow()
        observeChannelStrip()
        setupHeroClickListener()
        if (!DeviceUtils.isTV(requireContext())) setupHeroSwipeGesture()
        observeFeaturedContent()
        observeContinueWatching()
        // Sports banner removed — unnecessary UI clutter
        sportsBanner.visibility = View.GONE
        observeNewEpisodes()
        observeForYouContent()
        observeBecauseYouWatched()
        observeForYouLiveContent()
        observeTrendingContent()
        setupTrendingSeriesRow()
        observeTrendingSeries()
        setupTop10Row()
        observeTop10Content()
        observeGenreRows()
        setupWatchAgainRow()
        observeWatchItAgain()
        setupGreeting()
        loadFeatured()
        setupOnboarding(view)

        // Restore focus to where user was before navigating away, or default to hero
        restoreFocusState()
    }

    override fun onDestroyView() {
        // Save focus position for restoration on back navigation
        saveFocusState()
        releaseHeroPreview()
        heroRotationJob?.cancel()
        screenPreWarmer?.reset()
        screenPreWarmer = null
        onboardingOverlay = null
        super.onDestroyView()
    }

    override fun onPause() {
        releaseHeroPreview()
        super.onPause()
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
                R.id.channel_strip_row -> {
                    viewModel.savedFocusRowId = R.id.channel_strip_row
                    viewModel.savedFocusPosition = channelStripRow.selectedPosition
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
        // Sports banner
        if (focused.id == R.id.sports_banner) {
            viewModel.savedFocusRowId = R.id.sports_banner
            return
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
            R.id.channel_strip_row -> channelStripRow.post {
                if (channelStripObjectAdapter.size() > 0) {
                    channelStripRow.safeSetSelectedPosition(pos, channelStripObjectAdapter.size())
                    channelStripRow.requestFocus()
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
            R.id.sports_banner -> sportsBanner.post {
                if (sportsBanner.visibility == View.VISIBLE) {
                    sportsBanner.requestFocus()
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
        val overlay = onboardingOverlay
        if (overlay != null) return overlay.handleKeyEvent(keyCode)

        // D-pad left/right on hero area switches featured movies
        val focused = view?.findFocus()
        val isHeroFocused = focused?.id == R.id.hero_watch_now || focused?.id == R.id.hero_more_info
        if (isHeroFocused && featuredItems.size > 1) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    releaseHeroPreview()
                    heroRotationJob?.cancel()
                    heroIndex = if (heroIndex <= 0) featuredItems.size - 1 else heroIndex - 1
                    displayHeroItem(featuredItems[heroIndex], animate = true)
                    startHeroRotation()
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    releaseHeroPreview()
                    heroRotationJob?.cancel()
                    heroIndex = (heroIndex + 1) % featuredItems.size
                    displayHeroItem(featuredItems[heroIndex], animate = true)
                    startHeroRotation()
                    return true
                }
            }
        }
        return false
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
        // Bulletproof clipping: clipBounds works with hardware-accelerated transforms
        // where clipToOutline/clipChildren fails on Fire TV
        val heroContainerView = view.findViewById<View>(R.id.hero_container)
        heroContainerView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.clipBounds = android.graphics.Rect(0, 0, v.width, v.height)
        }
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
        channelStripLabel = view.findViewById(R.id.channel_strip_label)
        channelStripRow = view.findViewById(R.id.channel_strip_row)
        sectionsLabel = view.findViewById(R.id.sections_label)
        sectionsRow = view.findViewById(R.id.sections_row)
        trendingLabel = view.findViewById(R.id.trending_label)
        trendingRow = view.findViewById(R.id.trending_row)
        trendingSeriesLabel = view.findViewById(R.id.trending_series_label)
        trendingSeriesRow = view.findViewById(R.id.trending_series_row)
        becauseYouWatchedContainer = view.findViewById(R.id.because_you_watched_container)
        sportsBanner = view.findViewById(R.id.sports_banner)
        top10Label = view.findViewById(R.id.top10_label)
        top10Row = view.findViewById(R.id.top10_row)
        genreRowsContainer = view.findViewById(R.id.genre_rows_container)
        heroGreeting = view.findViewById(R.id.hero_greeting)

        // On mobile: let touch events pass directly to grid children (Leanback grids intercept first tap for focus)
        if (!DeviceUtils.isTV(requireContext())) {
            val grids = listOf(continueWatchingRow, newEpisodesRow, watchAgainRow, forYouRow,
                forYouLiveRow, channelStripRow, sectionsRow, trendingRow, trendingSeriesRow, top10Row)
            for (grid in grids) {
                grid.isFocusableInTouchMode = true
            }
        }
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
        // Apply time-of-day aurora theming
        auroraBackground.setTimeOfDayTheme(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
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
        val isTV = DeviceUtils.isTV(requireContext())

        // Hide frosted header on mobile (bottom nav handles navigation)
        if (!isTV) {
            frostedHeader.visibility = View.GONE
        }

        scrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                val heroHeight = heroContainer.height.toFloat()
                if (heroHeight > 0) {
                    val progress = (scrollY / heroHeight).coerceIn(0f, 1f)

                    if (isTV) {
                        // Frosted header: fade in as hero scrolls away
                        frostedHeader.alpha = progress
                    }

                    // Backdrop: fade only (no translationY — Fire TV doesn't clip translated views)
                    heroBackdrop.alpha = 1f - progress

                    // Content overlay: faster parallax (0.6x) + faster fade
                    // Content overlay fades faster than backdrop for depth

                    heroContentOverlay?.alpha = 1f - (progress * 1.3f).coerceAtMost(1f)

                    // Bottom gradient: intensifies on scroll
                    heroGradientBottom?.alpha = 0.95f + (progress * 0.05f)

                    // Ambient wash fades out
                    heroAmbientWash?.alpha = (0.08f * (1f - progress)).coerceAtLeast(0f)

                    // Release hero preview when scrolled past half the hero
                    if (scrollY > heroHeight * 0.5f) {
                        if (heroPreviewActive) releaseHeroPreview()
                        heroDwellJob?.cancel() // Cancel pending dwell timer too
                    }
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
                        (activity as? com.ooustream.iptv.MainActivity)?.navigateToMultiView()
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
                        playVodWithResumeCheck(url, item.id.toString(), item.title, item.imageUrl ?: "")
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

    // ── Greeting ─────────────────────────────────────────────────────────

    private fun setupGreeting() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else -> "Good Night"
        }
        val username = try {
            credentialStore.load()?.username?.replaceFirstChar { it.uppercase() }
        } catch (_: Exception) { null }
        heroGreeting.text = if (!username.isNullOrBlank()) "$greeting, $username" else greeting
        heroGreeting.visibility = View.VISIBLE
    }

    // ── Top 10 Row ──────────────────────────────────────────────────────

    private fun setupTop10Row() {
        val bridgeAdapter = ItemBridgeAdapter(top10ObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val top10 = top10ObjectAdapter.get(position) as? Top10Item ?: return@setOnClickListener
                    val item = top10.poster
                    val fragment = com.ooustream.iptv.vod.VodDetailFragment.newInstance(
                        vodId = item.id,
                        vodName = item.title,
                        coverUrl = item.imageUrl,
                        containerExtension = item.extension
                    )
                    requireActivity().supportFragmentManager.beginTransaction()
                        .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.FORWARD) }
                        .replace(R.id.main_container, fragment)
                        .addToBackStack(null)
                        .commit()
                }
                attachRowDimming(top10Row, top10Label, viewHolder, position)
            }
        })
        top10Row.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        top10Row.adapter = bridgeAdapter
    }

    private fun observeTop10Content() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.top10Content.collect { items ->
                    val top10Items = items.mapIndexed { index, vod ->
                        Top10Item(
                            rank = index + 1,
                            poster = com.ooustream.iptv.common.PosterItem(
                                id = vod.streamId,
                                title = vod.name,
                                imageUrl = vod.streamIcon,
                                rating = vod.rating,
                                extension = vod.containerExtension,
                                type = "vod",
                                tmdbId = vod.tmdbId
                            )
                        )
                    }
                    top10ObjectAdapter.safeReplaceAll(top10Items)
                    if (top10Items.isNotEmpty()) {
                        top10Label.visibility = View.VISIBLE
                        top10Row.visibility = View.VISIBLE
                    } else {
                        top10Label.visibility = View.GONE
                        top10Row.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ── Genre Rows ──────────────────────────────────────────────────────

    private fun observeGenreRows() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.genreRows.collect { rows ->
                    renderGenreRows(rows)
                }
            }
        }
    }

    private fun renderGenreRows(rows: List<HomeViewModel.GenreRow>) {
        // Prevent IllegalArgumentException in ViewRootImpl.performFocusNavigation —
        // if any child of this container currently has focus, removing it mid-navigation
        // crashes the focus system. Clear focus first.
        if (genreRowsContainer.hasFocus()) {
            genreRowsContainer.clearFocus()
        }
        genreRowsContainer.removeAllViews()
        if (rows.isEmpty()) {
            genreRowsContainer.visibility = View.GONE
            return
        }
        genreRowsContainer.visibility = View.VISIBLE

        val spacingMd = resources.getDimensionPixelSize(R.dimen.spacing_md)
        val overscanMargin = resources.getDimensionPixelSize(R.dimen.overscan_margin)
        val rowHeight = resources.getDimensionPixelSize(R.dimen.row_trending_height)

        for (row in rows) {
            val label = TextView(requireContext()).apply {
                text = row.genreName
                setTextColor(resources.getColor(R.color.text_primary, null))
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(overscanMargin, 0, overscanMargin, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 24.dp }
            }

            val gridView = androidx.leanback.widget.HorizontalGridView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight
                ).apply { topMargin = 12.dp }
                setPadding(overscanMargin, 0, 0, 0)
                clipToPadding = false
                clipChildren = false
                isFocusable = true
                isFocusableInTouchMode = !DeviceUtils.isTV(requireContext())
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                setItemSpacing(spacingMd)
            }

            val objectAdapter = ArrayObjectAdapter(PosterPresenter())
            val posterItems = row.items.map { vod ->
                com.ooustream.iptv.common.PosterItem(
                    id = vod.streamId,
                    title = vod.name,
                    imageUrl = vod.streamIcon,
                    rating = vod.rating,
                    extension = vod.containerExtension,
                    type = "vod",
                    tmdbId = vod.tmdbId
                )
            }
            objectAdapter.addAll(0, posterItems)

            val bridge = ItemBridgeAdapter(objectAdapter)
            bridge.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
                override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    val position = viewHolder.adapterPosition
                    viewHolder.itemView.setOnClickListener {
                        val item = objectAdapter.get(position) as? com.ooustream.iptv.common.PosterItem ?: return@setOnClickListener
                        val fragment = com.ooustream.iptv.vod.VodDetailFragment.newInstance(
                            vodId = item.id,
                            vodName = item.title,
                            coverUrl = item.imageUrl,
                            containerExtension = item.extension
                        )
                        requireActivity().supportFragmentManager.beginTransaction()
                            .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.FORWARD) }
                            .replace(R.id.main_container, fragment)
                            .addToBackStack(null)
                            .commit()
                    }
                    attachRowDimming(gridView, label, viewHolder, position)
                }
            })
            gridView.adapter = bridge

            genreRowsContainer.addView(label)
            genreRowsContainer.addView(gridView)
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

    // ── Live Sports Banner ────────────────────────────────────────────

    private fun observeLiveSports() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveSportsEvent.collect { event ->
                    if (event != null) {
                        sportsBanner.visibility = View.VISIBLE
                        val logo = sportsBanner.findViewById<ImageView>(R.id.sports_channel_logo)
                        val initials = sportsBanner.findViewById<TextView>(R.id.sports_channel_initials)
                        val title = sportsBanner.findViewById<TextView>(R.id.sports_event_title)
                        val channelName = sportsBanner.findViewById<TextView>(R.id.sports_channel_name)
                        val liveDot = sportsBanner.findViewById<View>(R.id.sports_live_dot)

                        title.text = event.eventTitle
                        channelName.text = event.channelName

                        if (!event.channelIcon.isNullOrBlank()) {
                            logo.visibility = View.VISIBLE
                            initials.visibility = View.GONE
                            logo.load(event.channelIcon) {
                                crossfade(true)
                                listener(onError = { _, _ ->
                                    logo.visibility = View.GONE
                                    initials.visibility = View.VISIBLE
                                    initials.text = event.channelName.take(3).uppercase()
                                })
                            }
                        } else {
                            logo.visibility = View.GONE
                            initials.visibility = View.VISIBLE
                            initials.text = event.channelName.take(3).uppercase()
                        }

                        // Pulsing LIVE dot animation
                        liveDot.animate().cancel()
                        android.animation.ObjectAnimator.ofFloat(liveDot, "alpha", 1f, 0.2f, 1f).apply {
                            duration = 1500
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            start()
                        }

                        // Focus effect: subtle scale — no SurfaceView inside, scaling is safe
                        sportsBanner.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                            v.animate()
                                .scaleX(if (hasFocus) 1.02f else 1f)
                                .scaleY(if (hasFocus) 1.02f else 1f)
                                .setDuration(200)
                                .start()
                        }

                        sportsBanner.setOnClickListener {
                            val fragment = OoustreamPlaybackFragment.newInstance(
                                streamUrl = event.streamUrl,
                                contentType = ContentType.LIVE,
                                streamId = event.channelId.toString(),
                                streamName = event.channelName
                            )
                            val tx = requireActivity().supportFragmentManager.beginTransaction()
                            FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                            tx.replace(R.id.main_container, fragment)
                                .addToBackStack(null)
                                .commit()
                        }
                    } else {
                        sportsBanner.visibility = View.GONE
                    }
                }
            }
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
                        // Clear new episode badge immediately so it leaves the row
                        viewModel.clearNewEpisodes(item.seriesId)
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
                            playVodWithResumeCheck(url, item.streamId, item.name, item.icon ?: "")
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

    // ── Because You Watched Rows ──────────────────────────────────────

    private fun observeBecauseYouWatched() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.becauseYouWatchedRows.collect { rows ->
                    renderBecauseYouWatchedRows(rows)
                }
            }
        }
    }

    private fun renderBecauseYouWatchedRows(rows: List<BecauseYouWatchedRow>) {
        // Clear focus before rebuild to prevent ViewRootImpl.performFocusNavigation crash
        if (becauseYouWatchedContainer.hasFocus()) {
            becauseYouWatchedContainer.clearFocus()
        }
        becauseYouWatchedContainer.removeAllViews()
        if (rows.isEmpty()) {
            becauseYouWatchedContainer.visibility = View.GONE
            return
        }

        // Hide the generic flat "For You" row — the grouped rows replace it
        forYouLabel.visibility = View.GONE
        forYouRow.visibility = View.GONE
        becauseYouWatchedContainer.visibility = View.VISIBLE

        val spacingMd = resources.getDimensionPixelSize(R.dimen.spacing_md)
        val overscanMargin = resources.getDimensionPixelSize(R.dimen.overscan_margin)
        val rowHeight = resources.getDimensionPixelSize(R.dimen.row_for_you_height)

        for (row in rows) {
            // Row label
            val label = TextView(requireContext()).apply {
                text = "Because You Watched ${row.seedTitle}"
                setTextColor(resources.getColor(R.color.text_primary, null))
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(overscanMargin, 0, overscanMargin, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 24.dp }
            }

            // HorizontalGridView for this row's cards
            val gridView = androidx.leanback.widget.HorizontalGridView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight
                ).apply { topMargin = 12.dp }
                setPadding(overscanMargin, 0, 0, 0)
                clipToPadding = false
                clipChildren = false
                isFocusable = true
                isFocusableInTouchMode = !DeviceUtils.isTV(requireContext())
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                setItemSpacing(spacingMd)
            }

            val objectAdapter = ArrayObjectAdapter(ForYouPresenter())
            objectAdapter.addAll(0, row.recommendations)
            val bridge = ItemBridgeAdapter(objectAdapter)
            bridge.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
                override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                    val position = viewHolder.adapterPosition
                    viewHolder.itemView.setOnClickListener {
                        val item = objectAdapter.get(position)
                        if (item is RecommendedItem) {
                            navigateToRecommendation(item)
                        }
                    }
                    attachRowDimming(gridView, label, viewHolder, position)
                }
            })
            gridView.adapter = bridge

            becauseYouWatchedContainer.addView(label)
            becauseYouWatchedContainer.addView(gridView)
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

    // ── Channel Strip (Quick Tune) Row ────────────────────────────────────

    private fun setupChannelStripRow() {
        val bridgeAdapter = ItemBridgeAdapter(channelStripObjectAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val position = viewHolder.adapterPosition
                viewHolder.itemView.setOnClickListener {
                    val item = channelStripObjectAdapter.get(position)
                    if (item is ChannelStripItem) {
                        val fragment = OoustreamPlaybackFragment.newInstance(
                            streamUrl = item.streamUrl,
                            contentType = ContentType.LIVE,
                            streamId = item.channelId.toString(),
                            streamName = item.channelName
                        )
                        val tx = requireActivity().supportFragmentManager.beginTransaction()
                        FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                        tx.replace(R.id.main_container, fragment)
                            .addToBackStack(null)
                            .commit()
                    }
                }
                attachRowDimming(channelStripRow, channelStripLabel, viewHolder, position)
            }
        })
        channelStripRow.setItemSpacing(resources.getDimensionPixelSize(R.dimen.spacing_md))
        channelStripRow.adapter = bridgeAdapter
    }

    private fun observeChannelStrip() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.channelStripItems.collect { items ->
                    channelStripObjectAdapter.safeReplaceAll(items)
                    if (items.isNotEmpty()) {
                        channelStripLabel.visibility = View.VISIBLE
                        channelStripRow.visibility = View.VISIBLE
                    } else {
                        channelStripLabel.visibility = View.GONE
                        channelStripRow.visibility = View.GONE
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
        releaseHeroPreview()
        kenBurnsJob?.cancel()

        // Reset Ken Burns

        if (animate) {
            // Content slides out left
            heroContentOverlay?.animate()
                ?.translationX(-20f.dp)?.alpha(0f)
                ?.setDuration(250)?.start()

            heroBackdrop.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    loadHeroImage(item)
                    bindHeroContent(item)
                    heroBackdrop.scaleX = 1f
                    heroBackdrop.scaleY = 1f
                    heroBackdrop.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .start()
                    // Content slides in from right
                    heroContentOverlay?.translationX = 20f.dp
                    heroContentOverlay?.animate()
                        ?.translationX(0f)?.alpha(1f)
                        ?.setDuration(350)?.setStartDelay(100)?.start()
                }
                .start()
        } else {
            heroBackdrop.animate().cancel()
            heroBackdrop.alpha = 1f
            heroBackdrop.scaleX = 1f
            heroBackdrop.scaleY = 1f
            heroContentOverlay?.alpha = 1f
            heroContentOverlay?.translationX = 0f
            heroGradientBottom?.alpha = 1f
            heroGradientLeft?.alpha = 1f
            loadHeroImage(item)
            bindHeroContent(item)
        }
        updateHeroIndicators(featuredItems.size, heroIndex)
        startHeroDwellTimer()
        startKenBurns()
    }

    private fun bindHeroContent(item: FeaturedItem) {
        heroTitle.text = item.title

        // Metadata chips
        if (item.rating.isNotBlank() || item.year.isNotBlank() || item.genre.isNotBlank()) {
            heroMetadataRow?.visibility = View.VISIBLE
            heroRating?.text = if (item.rating.isNotBlank()) "\u2605 ${item.rating}" else ""
            heroRating?.visibility = if (item.rating.isNotBlank()) View.VISIBLE else View.GONE
            heroYear?.text = item.year
            heroYear?.visibility = if (item.year.isNotBlank()) View.VISIBLE else View.GONE
            heroGenreChip?.text = item.genre
            heroGenreChip?.visibility = if (item.genre.isNotBlank()) View.VISIBLE else View.GONE
        } else {
            heroMetadataRow?.visibility = View.GONE
        }

        // Description
        if (!item.plot.isNullOrBlank()) {
            heroDescription?.text = item.plot
            heroDescription?.visibility = View.VISIBLE
        } else {
            heroDescription?.visibility = View.GONE
        }

        // Legacy genre field hidden when metadata chips are shown
        heroGenre.visibility = if (heroMetadataRow?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        heroGenre.text = item.genre
    }

    /** Ken Burns disabled — scale transforms overflow hero_container on Fire TV */
    private fun startKenBurns() {
        kenBurnsJob?.cancel()
        // No scale animation — keep heroBackdrop at 1.0x so it stays within container bounds
    }

    private fun loadHeroImage(item: FeaturedItem) {
        if (!item.backdropUrl.isNullOrBlank()) {
            val url = PosterUrlRewriter.rewriteBackdrop(item.backdropUrl)
            android.util.Log.w("OOUSTREAM", "Hero image: loading '${item.title}' url=$url")
            heroBackdrop.load(url) {
                crossfade(false)
                memoryCachePolicy(CachePolicy.ENABLED)
                allowHardware(false) // Required for Palette extraction
                listener(
                    onSuccess = { _, result ->
                        android.util.Log.w("OOUSTREAM", "Hero image: loaded '${item.title}' successfully")
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                                if (bitmap != null) {
                                    val color = PaletteExtractor.extractDominant(bitmap)
                                    auroraBackground.setAmbientColor(color)
                                    // Ambient color wash on hero
                                    heroAmbientWash?.setBackgroundColor(color)
                                    heroAmbientWash?.animate()?.alpha(0.08f)?.setDuration(600)?.start()
                                }
                            } catch (_: Exception) { }
                        }
                    },
                    onError = { _, result ->
                        android.util.Log.w("OOUSTREAM", "Hero image: FAILED '${item.title}' url=$url error=${result.throwable.message}")
                    }
                )
            }
        } else {
            android.util.Log.w("OOUSTREAM", "Hero image: no backdrop URL for '${item.title}'")
        }
    }

    // ── Hero Trailer Auto-Play ──────────────────────────────────────────

    private fun startHeroDwellTimer() {
        if (isLowMemoryDevice) {
            android.util.Log.w("OOUSTREAM", "Hero trailer: skipping — low memory device")
            return
        }
        heroDwellJob?.cancel()
        heroDwellJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(4_000)
            if (!isActive) return@launch
            // Don't start trailer if hero is scrolled out of view
            if (!isHeroVisible()) return@launch
            val items = featuredItems
            val item = items.getOrNull(heroIndex)
            if (item == null) {
                android.util.Log.w("OOUSTREAM", "Hero trailer: no item at index $heroIndex (items=${items.size})")
                return@launch
            }
            val streamId = item.streamId.toIntOrNull()
            if (streamId == null) {
                android.util.Log.w("OOUSTREAM", "Hero trailer: invalid streamId '${item.streamId}'")
                return@launch
            }
            val url = viewModel.buildVodStreamUrl(streamId, item.containerExtension)
            android.util.Log.w("OOUSTREAM", "Hero trailer: starting preview for '${item.title}' url=$url")
            startHeroPreview(url)
        }
    }

    private fun startHeroPreview(url: String) {
        val playerView = heroPreviewView
        if (playerView == null) {
            android.util.Log.w("OOUSTREAM", "Hero trailer: playerView is null — can't start")
            return
        }
        releaseHeroPreview()
        val context = requireContext()
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false) // Audio enabled for trailer
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setMaxVideoSize(854, 480)
            )
        }
        // Use DefaultHttpDataSource with redirect following enabled (server returns 302)
        val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
        }
        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(com.ooustream.iptv.player.BufferConfigs.forContentType(ContentType.VOD))
            .setTrackSelector(trackSelector)
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true // Handle audio focus
                )
                volume = 0.6f // Trailer audio
                repeatMode = ExoPlayer.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(url))
            }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                android.util.Log.w("OOUSTREAM", "Hero trailer: state=$stateName")
            }

            override fun onRenderedFirstFrame() {
                android.util.Log.w("OOUSTREAM", "Hero trailer: first frame rendered, heroPreviewActive=$heroPreviewActive")
                if (!heroPreviewActive) return
                // Crossfade: video in, backdrop + overlays out
                playerView.animate().alpha(1f).setDuration(500).start()
                heroBackdrop.animate().alpha(0f).setDuration(500).start()
                // Fade out title/genre/buttons/indicators and gradients for clean full-bleed video
                heroContentOverlay?.animate()?.alpha(0f)?.setDuration(400)?.start()
                heroGradientBottom?.animate()?.alpha(0f)?.setDuration(400)?.start()
                heroGradientLeft?.animate()?.alpha(0f)?.setDuration(400)?.start()
                heroRotationJob?.cancel()
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("OOUSTREAM", "Hero trailer: player error — ${error.errorCode}: ${error.message}", error.cause)
                releaseHeroPreview()
            }
        })
        heroPreviewPlayer = player
        // Make PlayerView visible but transparent so TextureView creates its Surface
        // (GONE = no Surface = onRenderedFirstFrame never fires)
        playerView.alpha = 0f
        playerView.visibility = View.VISIBLE
        playerView.player = player
        heroPreviewActive = true
        player.prepare()
        player.play()
        // Seek past opening logos/credits to show actual movie content
        player.seekTo(120_000L) // 2 minutes in
        viewLifecycleOwner.lifecycleScope.launch {
            delay(15_000) // 15s preview, not 20
            if (heroPreviewActive) {
                releaseHeroPreview()
                startHeroRotation()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            delay(5_000)
            if (heroPreviewActive && playerView.alpha < 0.5f) {
                android.util.Log.w("OOUSTREAM", "Hero trailer: 5s buffer timeout — no first frame, releasing")
                releaseHeroPreview()
            }
        }
    }

    private fun isHeroVisible(): Boolean {
        val scrollView = view?.findViewById<NestedScrollView>(R.id.home_scroll) ?: return true
        val heroContainer = view?.findViewById<View>(R.id.hero_container) ?: return true
        val heroHeight = heroContainer.height.toFloat()
        return heroHeight <= 0 || scrollView.scrollY < heroHeight * 0.5f
    }

    private fun releaseHeroPreview() {
        heroDwellJob?.cancel()
        heroPreviewActive = false
        heroPreviewPlayer?.let { player ->
            try {
                player.stop()
                player.clearVideoSurface()
                player.release()
            } catch (_: Exception) {}
        }
        heroPreviewPlayer = null
        heroPreviewView?.let { pv ->
            pv.player = null
            if (pv.visibility == View.VISIBLE) {
                pv.animate().alpha(0f).setDuration(300).withEndAction {
                    pv.visibility = View.GONE
                }.start()
                heroBackdrop.animate().alpha(1f).setDuration(300).start()
                // Restore hero content + gradients
                heroContentOverlay?.animate()?.alpha(1f)?.setDuration(300)?.start()
                heroGradientBottom?.animate()?.alpha(1f)?.setDuration(300)?.start()
                heroGradientLeft?.animate()?.alpha(1f)?.setDuration(300)?.start()
            } else {
                pv.visibility = View.GONE
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
                // Refresh aurora theme on rotation (catches time boundary crossings)
                auroraBackground.setTimeOfDayTheme(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
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
                playVodWithResumeCheck(url, item.streamId, item.title, item.backdropUrl ?: "")
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHeroSwipeGesture() {
        val heroContainer = view?.findViewById<View>(R.id.hero_container) ?: return
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val startX = e1?.x ?: return false
                val deltaX = e2.x - startX
                if (kotlin.math.abs(deltaX) < 80 || kotlin.math.abs(velocityX) < 200) return false
                if (featuredItems.size <= 1) return false

                releaseHeroPreview()
                heroRotationJob?.cancel()
                if (deltaX > 0) {
                    heroIndex = if (heroIndex <= 0) featuredItems.size - 1 else heroIndex - 1
                } else {
                    heroIndex = (heroIndex + 1) % featuredItems.size
                }
                displayHeroItem(featuredItems[heroIndex], animate = true)
                startHeroRotation()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })
        heroContainer.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
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
                val ext = item.containerExtension ?: "mp4"
                val url = viewModel.buildVodStreamUrl(item.streamId, ext)
                playVodWithResumeCheck(url, item.streamId.toString(), item.name, item.icon ?: "")
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

    /** Launch VOD playback with Resume/Play from Beginning dialog if progress exists. */
    private fun playVodWithResumeCheck(
        streamUrl: String,
        streamId: String,
        streamName: String,
        streamIcon: String = ""
    ) {
        // Guard against view being destroyed between click and handler invocation.
        // If the fragment's view is already torn down (fast back-press, rapid nav),
        // viewLifecycleOwner throws IllegalStateException when accessed.
        if (view == null || !isAdded || isDetached) return
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = try { viewModel.getWatchProgress(streamId) } catch (_: Exception) { null }
            // Re-check view state after the coroutine resumes — it may have been destroyed
            // while we were awaiting the DB query
            if (view == null || !isAdded || isDetached) return@launch
            val act = activity ?: return@launch
            com.ooustream.iptv.common.ResumePlaybackHelper.showIfNeeded(
                context = requireContext(),
                progress = progress
            ) { forceBeginning ->
                if (view == null || !isAdded || isDetached) return@showIfNeeded
                val fragment = OoustreamPlaybackFragment.newInstance(
                    streamUrl = streamUrl,
                    contentType = ContentType.VOD,
                    streamId = streamId,
                    streamName = streamName,
                    streamIcon = streamIcon,
                    forceStartFromBeginning = forceBeginning
                )
                try {
                    val tx = act.supportFragmentManager.beginTransaction()
                    FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                    tx.replace(R.id.main_container, fragment)
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                } catch (_: IllegalStateException) {
                    // Activity state saved (e.g., backgrounded) — safe to drop
                }
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
