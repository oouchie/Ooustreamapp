package com.ooustream.iptv.epg.guide

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import com.ooustream.iptv.common.ChannelDisplayHelper
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.epg.ChannelNameParser
import com.ooustream.iptv.player.ChannelListHolder
import com.ooustream.iptv.player.LivePreviewManager
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Premium TV-guide grid (TiviMate-style): a "Now Playing" header with a tiered live preview +
 * program panel, a toolbar (Global Search · Full Screen · Hours · Now · date), and the
 * Canvas-lane grid below. Genre-colored cells, live progress in every row, inline favorite
 * hearts (hold OK), and a selectable/persisted time window. Vertical axis is a Leanback
 * VerticalGridView (real focus); horizontal focus is virtual via [GuideTimelineController].
 */
@AndroidEntryPoint
class EpgGridFragment : Fragment(), KeyEventHandler {

    @Inject lateinit var okHttpClient: OkHttpClient

    private val viewModel: EpgGridViewModel by viewModels()
    private val controller = GuideTimelineController()

    private var gridView: VerticalGridView? = null
    private var categoryChip: TextView? = null
    private var rowAdapter: GuideRowAdapter? = null
    private var viewportJob: Job? = null
    private var initialFetchDone = false
    /** Channel to re-select after returning from playback (view is recreated; instance survives). */
    private var pendingRestoreStreamId = -1

    // Header + preview
    private var guideHeader: LinearLayout? = null
    private var previewPlayerView: PlayerView? = null
    private var previewPlaceholder: TextView? = null
    private var headerLogo: ImageView? = null
    private var headerInitials: TextView? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var headerFavorite: TextView? = null
    private var headerNowTitle: TextView? = null
    private var headerNowTime: TextView? = null
    private var headerProgressContainer: View? = null
    private var headerProgressFill: View? = null
    private var headerNowRemaining: TextView? = null
    private var headerNowDesc: TextView? = null
    private var headerNextTitle: TextView? = null
    private var headerVisible = true

    // Toolbar
    private var btnFullscreen: TextView? = null
    private var btnHours: TextView? = null
    private var dateLabel: TextView? = null

    private var previewManager: LivePreviewManager? = null
    private var previewJob: Job? = null
    private var lowMemoryDevice = false

    private var prefs: android.content.SharedPreferences? = null
    private var hoursIndex = 0

    // Long-press OK → favorite (manual timing; keeps the row focus model intact)
    private var okDownAt = 0L
    private var okLongFired = false
    private var okPressed = false

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    private val canVideoPreview: Boolean
        get() = isAdded && DeviceUtils.isTV(requireContext()) && !lowMemoryDevice

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_epg_grid, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val isTv = DeviceUtils.isTV(requireContext())

        val am = requireContext()
            .getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        lowMemoryDevice = am.memoryClass <= 128
        prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The fragment instance is retained on the back stack across playback, but the view is
        // recreated from XML — header VISIBLE, button at its "Full Screen" default. Reset the
        // retained field so the preview gate + toggle don't desync (back-from-playback fix).
        headerVisible = true

        // Horizon: NOW floored to the half-hour, forward 12h (get_short_epg has no past programs)
        val slot = 30 * 60_000L
        val horizonStart = (System.currentTimeMillis() / slot) * slot
        val horizonEnd = horizonStart + 12 * 60 * 60_000L
        controller.setHorizon(horizonStart, horizonEnd)
        viewModel.setHorizon(horizonStart, horizonEnd)

        // Restore persisted Hours window
        hoursIndex = (prefs?.getInt(KEY_HOURS, 0) ?: 0)
            .coerceIn(0, GuideTimelineController.WINDOW_OPTIONS_MS.size - 1)
        controller.setWindowDuration(GuideTimelineController.WINDOW_OPTIONS_MS[hoursIndex])

        bindHeaderViews(view)
        bindToolbar(view)

        // Time ruler
        val headerContainer = view.findViewById<FrameLayout>(R.id.guide_time_header_container)
        val timeHeader = GuideTimeHeaderView(requireContext()).apply {
            controller = this@EpgGridFragment.controller
            leadingOffsetPx = (180 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        headerContainer.addView(timeHeader)

        // Rows
        val grid = view.findViewById<VerticalGridView>(R.id.guide_rows)
        gridView = grid
        grid.setAnimateChildLayout(false)
        grid.itemAnimator = null
        val adapter = GuideRowAdapter(
            controller = controller,
            onRowFocused = { scheduleViewportFetch() },
            onProgramClicked = { row, program -> onProgramClicked(row, program) },
            onLanePan = if (isTv) null else { deltaMs -> controller.scrollTo(controller.windowStartMs + deltaMs) }
        )
        rowAdapter = adapter
        grid.adapter = adapter

        grid.addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(
                parent: RecyclerView, child: RecyclerView.ViewHolder?, position: Int, subposition: Int
            ) {
                onRowSelected(position)
            }
        })

        // Category chip — opens the in-guide category picker (UP from the grid lands here).
        // Focus visuals come from bg_guide_pill (selector); no programmatic listener needed.
        val categoryLabel = view.findViewById<TextView>(R.id.guide_category_label)
        categoryChip = categoryLabel
        categoryLabel.setOnClickListener { showCategoryPicker() }
        viewModel.loadCategoryOptions()

        // Rows data
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rows.collect { rows ->
                    val isFirst = rows.isNotEmpty() && !initialFetchDone
                    val restoreId = pendingRestoreStreamId
                    adapter.submitList(rows) {
                        // Commit callback: the list is applied to the adapter here, so selection can
                        // be set without the empty→populated focus race (v3.7.13 lesson — never post{}).
                        if (isFirst) {
                            initialFetchDone = true
                            viewModel.onVisibleRangeChanged(0, 12)
                        }
                        if (restoreId >= 0 && rows.isNotEmpty()) {
                            val idx = adapter.currentList.indexOfFirst { it.channel.streamId == restoreId }
                            if (idx >= 0) grid.selectedPosition = idx
                            pendingRestoreStreamId = -1
                        }
                        // TV only: phone scrolls by touch and shouldn't have focus grabbed on load.
                        if ((isFirst || restoreId >= 0) && DeviceUtils.isTV(requireContext())) {
                            grid.requestFocus()
                        }
                        // Re-bind the header so a now/next that just upgraded synthetic→real refreshes.
                        bindHeaderForSelected()
                    }
                    val name = viewModel.categoryName ?: getString(R.string.guide_all_channels)
                    categoryLabel.text = "$name ▾"
                }
            }
        }

        // Favorites — drive the inline hearts + header indicator reactively.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.favoriteIds.collect { ids ->
                    rowAdapter?.favoriteIds = ids
                    updateHeaderFavorite()
                }
            }
        }

        // NOW ticker — keeps the gold line, current-program highlight, and progress honest
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                controller.tickNow()
                bindHeaderForSelected()
            }
        }

        // Phone hint copy
        if (!isTv) {
            view.findViewById<TextView>(R.id.guide_hints)?.setText(R.string.guide_hints_touch)
        }

        dateLabel?.text = dateFmt.format(Date())

        viewModel.loadChannels(
            arguments?.getString(ARG_CATEGORY_ID),
            arguments?.getString(ARG_CATEGORY_NAME)
        )
    }

    // ---------------------------------------------------------------- header

    private fun bindHeaderViews(view: View) {
        guideHeader = view.findViewById(R.id.guide_header)
        previewPlayerView = view.findViewById<PlayerView>(R.id.epg_preview_player_view)?.apply {
            useController = false
            controllerAutoShow = false
            isFocusable = false
        }
        previewPlaceholder = view.findViewById(R.id.epg_preview_placeholder)
        headerLogo = view.findViewById(R.id.epg_header_logo)
        headerInitials = view.findViewById(R.id.epg_header_initials)
        headerTitle = view.findViewById(R.id.epg_header_title)
        headerSubtitle = view.findViewById(R.id.epg_header_subtitle)
        headerFavorite = view.findViewById(R.id.epg_header_favorite)
        headerNowTitle = view.findViewById(R.id.epg_header_now_title)
        headerNowTime = view.findViewById(R.id.epg_header_now_time)
        headerProgressContainer = view.findViewById(R.id.epg_header_progress_container)
        headerProgressFill = view.findViewById(R.id.epg_header_progress_fill)
        headerNowRemaining = view.findViewById(R.id.epg_header_now_remaining)
        headerNowDesc = view.findViewById(R.id.epg_header_now_desc)
        headerNextTitle = view.findViewById(R.id.epg_header_next_title)
        if (canVideoPreview) {
            previewManager = LivePreviewManager(requireContext(), okHttpClient).apply { setLowBitrateMode() }
        }
    }

    private fun bindHeaderForSelected() {
        val pos = gridView?.selectedPosition ?: return
        if (pos < 0) return
        rowAdapter?.currentList?.getOrNull(pos)?.let { bindHeader(it) }
    }

    private fun bindHeader(row: GuideRowData) {
        val ch = row.channel
        val disp = ChannelNameParser.parseForDisplay(ch.name)
        headerTitle?.text = disp.name
        headerSubtitle?.text = listOfNotNull(
            disp.country, viewModel.categoryName, disp.quality
        ).joinToString(" · ")
        headerLogo?.let { logo ->
            headerInitials?.let { initials ->
                ChannelDisplayHelper.loadLogo(logo, initials, ch.streamIcon, ch.name)
            }
        }
        updateHeaderFavorite()

        val now = controller.nowMs
        val programs = row.programs
        // "Now" is ONLY a program actually airing — never a future one (during a sub-5-min real-EPG
        // hole at NOW, leave the now slots blank and surface the upcoming program under "Up next").
        val current = programs.firstOrNull { it.contains(now) }
        val next = if (current != null) {
            programs.firstOrNull { it.startMs >= current.endMs }
        } else {
            programs.firstOrNull { it.startMs >= now }
        }

        if (current != null) {
            headerNowTitle?.text = current.title
            headerNowTitle?.visibility = View.VISIBLE
            headerNowTime?.text = "${timeFmt.format(Date(current.startMs))} – ${timeFmt.format(Date(current.endMs))}"
            headerNowTime?.visibility = View.VISIBLE
            val isLive = current.contains(now)
            if (isLive && current.endMs > current.startMs) {
                val frac = ((now - current.startMs).toFloat() / (current.endMs - current.startMs)).coerceIn(0f, 1f)
                headerProgressFill?.let { fill ->
                    headerProgressContainer?.let { container ->
                        ChannelDisplayHelper.setProgressBar(fill, container, frac)
                    }
                }
                val minsLeft = ((current.endMs - now) / 60_000L).coerceAtLeast(0)
                headerNowRemaining?.text = "$minsLeft min left"
                headerNowRemaining?.visibility = View.VISIBLE
            } else {
                headerProgressContainer?.visibility = View.GONE
                headerNowRemaining?.visibility = View.GONE
            }
            val desc = current.description?.takeIf { it.isNotBlank() } ?: getString(R.string.guide_no_description)
            headerNowDesc?.text = desc
            headerNowDesc?.visibility = View.VISIBLE
        } else {
            headerNowTitle?.visibility = View.GONE
            headerNowTime?.visibility = View.GONE
            headerProgressContainer?.visibility = View.GONE
            headerNowRemaining?.visibility = View.GONE
            headerNowDesc?.visibility = View.GONE
        }

        if (next != null) {
            headerNextTitle?.text = "${getString(R.string.guide_up_next)} · ${next.title} · ${timeFmt.format(Date(next.startMs))}"
            headerNextTitle?.visibility = View.VISIBLE
        } else {
            headerNextTitle?.visibility = View.GONE
        }
    }

    private fun updateHeaderFavorite() {
        val pos = gridView?.selectedPosition ?: return
        if (pos < 0) return
        val streamId = rowAdapter?.currentList?.getOrNull(pos)?.channel?.streamId ?: return
        val fav = viewModel.favoriteIds.value.contains("live_$streamId")
        headerFavorite?.text = (if (fav) "♥ " else "♡ ") + getString(R.string.guide_favorite)
        headerFavorite?.setTextColor(if (fav) 0xFFFFC107.toInt() else resources.getColor(R.color.text_secondary, null))
    }

    // ---------------------------------------------------------------- preview

    private fun onRowSelected(position: Int) {
        scheduleViewportFetch()
        val row = rowAdapter?.currentList?.getOrNull(position) ?: return
        bindHeader(row)
        schedulePreview(row.channel)
    }

    private fun schedulePreview(channel: com.ooustream.iptv.data.model.LiveStream) {
        previewJob?.cancel()
        if (!canVideoPreview || !headerVisible) return
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(1000)
            if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                startPreviewFor(channel)
            }
        }
    }

    private fun startPreviewFor(channel: com.ooustream.iptv.data.model.LiveStream) {
        if (!canVideoPreview || !headerVisible) return
        val pv = previewPlayerView ?: return
        val url = viewModel.buildStreamUrl(channel.streamId)
        previewPlaceholder?.visibility = View.GONE
        pv.animate().cancel()
        pv.alpha = 0f
        pv.visibility = View.VISIBLE
        pv.animate().alpha(1f).setDuration(220).start()
        previewManager?.startPreview(pv, url)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewManager?.release()
        previewPlayerView?.let {
            it.animate().cancel()
            it.visibility = View.GONE
            it.alpha = 1f
        }
        previewPlaceholder?.visibility = View.VISIBLE
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- toolbar

    private fun bindToolbar(view: View) {
        view.findViewById<TextView>(R.id.guide_btn_search).setOnClickListener { openGlobalSearch() }
        btnFullscreen = view.findViewById<TextView>(R.id.guide_btn_fullscreen).apply {
            setOnClickListener { toggleFullScreen() }
        }
        btnHours = view.findViewById<TextView>(R.id.guide_btn_hours).apply {
            text = hoursLabel(hoursIndex)
            setOnClickListener { cycleHours() }
        }
        view.findViewById<TextView>(R.id.guide_btn_now).setOnClickListener { jumpToNow() }
        dateLabel = view.findViewById(R.id.guide_date)
    }

    private fun openGlobalSearch() {
        DpadSoundManager.getInstance()?.playSelect()
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
        tx.replace(R.id.main_container, com.ooustream.iptv.search.SearchFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun toggleFullScreen() {
        headerVisible = !headerVisible
        guideHeader?.visibility = if (headerVisible) View.VISIBLE else View.GONE
        btnFullscreen?.text = getString(
            if (headerVisible) R.string.guide_full_screen else R.string.guide_exit_full_screen
        )
        DpadSoundManager.getInstance()?.playSelect()
        if (!headerVisible) {
            stopPreview()
        } else {
            val pos = gridView?.selectedPosition ?: -1
            rowAdapter?.currentList?.getOrNull(pos)?.let { bindHeader(it); schedulePreview(it.channel) }
        }
    }

    private fun cycleHours() {
        hoursIndex = (hoursIndex + 1) % GuideTimelineController.WINDOW_OPTIONS_MS.size
        controller.setWindowDuration(GuideTimelineController.WINDOW_OPTIONS_MS[hoursIndex])
        btnHours?.text = hoursLabel(hoursIndex)
        prefs?.edit()?.putInt(KEY_HOURS, hoursIndex)?.apply()
        DpadSoundManager.getInstance()?.playSelect()
    }

    private fun hoursLabel(index: Int): String =
        "${GuideTimelineController.WINDOW_OPTIONS_MS[index] / 3_600_000L}h"

    private fun jumpToNow() {
        controller.focusAnchorMs = controller.nowMs.coerceIn(controller.horizonStartMs, controller.horizonEndMs - 1)
        controller.scrollTo(controller.nowMs - controller.windowDurationMs / 6)
        controller.notifyChanged()
        DpadSoundManager.getInstance()?.playMove()
    }

    /** Debounced viewport-driven EPG fetch (400ms — project convention for scroll-settle). */
    private fun scheduleViewportFetch() {
        viewportJob?.cancel()
        viewportJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(400)
            val selected = gridView?.selectedPosition ?: return@launch
            viewModel.onVisibleRangeChanged(selected - 6, selected + 6)
        }
    }

    private fun currentLane(): GuideProgramLaneView? {
        val grid = gridView ?: return null
        val pos = grid.selectedPosition
        if (pos < 0) return null
        return rowAdapter?.laneAt(grid, pos)
    }

    /** Walk the virtual focus one program left/right; scrolls the shared window as needed. */
    private fun moveFocusHorizontal(direction: Int): Boolean {
        val lane = currentLane() ?: return false
        val programs = lane.programs
        if (programs.isEmpty()) return true
        val current = lane.focusedProgram()
        val curIdx = programs.indexOf(current).coerceAtLeast(0)
        val newIdx = curIdx + direction
        if (newIdx < 0 || newIdx >= programs.size) {
            DpadSoundManager.getInstance()?.playBoundary()
            return true
        }
        val target = programs[newIdx]
        // Anchor at the cell start (or NOW for the live cell) so UP/DOWN stays time-aligned
        controller.focusAnchorMs = maxOf(target.startMs, controller.horizonStartMs)
        controller.ensureAnchorVisible()
        controller.notifyChanged()
        DpadSoundManager.getInstance()?.playMove()
        return true
    }

    /** OK long-press → favorite, OK short-press → watch/details. Owns OK while the grid is focused. */
    override fun onFullKeyEvent(event: KeyEvent): Boolean {
        if (gridView?.hasFocus() != true) return false
        val kc = event.keyCode
        if (kc != KeyEvent.KEYCODE_DPAD_CENTER && kc != KeyEvent.KEYCODE_ENTER &&
            kc != KeyEvent.KEYCODE_NUMPAD_ENTER
        ) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    okDownAt = event.eventTime
                    okLongFired = false
                    okPressed = true
                } else if (!okLongFired && event.eventTime - okDownAt >= LONG_PRESS_MS) {
                    okLongFired = true
                    toggleFavoriteForSelectedRow()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (!okPressed) return true
                okPressed = false
                val held = event.eventTime - okDownAt
                if (!okLongFired) {
                    if (held >= LONG_PRESS_MS) toggleFavoriteForSelectedRow()
                    else activateSelectedCell()
                }
                okLongFired = false
                return true
            }
        }
        return false
    }

    private fun activateSelectedCell() {
        val pos = gridView?.selectedPosition ?: return
        val row = rowAdapter?.currentList?.getOrNull(pos) ?: return
        currentLane()?.focusedProgram()?.let { onProgramClicked(row, it) }
    }

    private fun toggleFavoriteForSelectedRow() {
        val pos = gridView?.selectedPosition ?: return
        val row = rowAdapter?.currentList?.getOrNull(pos) ?: return
        val wasFav = viewModel.favoriteIds.value.contains("live_${row.channel.streamId}")
        viewModel.toggleFavorite(row.channel)
        DpadSoundManager.getInstance()?.playSelect()
        Toast.makeText(
            requireContext(),
            getString(if (wasFav) R.string.removed_from_favorites else R.string.added_to_favorites),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onKeyEvent(keyCode: Int): Boolean {
        if (gridView?.hasFocus() != true) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveFocusHorizontal(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocusHorizontal(+1)
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Leanback keeps focus inside the grid at the top edge — hand it to the
                // toolbar (category chip) explicitly so the toolbar is reachable by D-pad.
                if ((gridView?.selectedPosition ?: -1) == 0) {
                    categoryChip?.requestFocus()
                    true
                } else false
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (controller.pageBy(controller.windowDurationMs)) DpadSoundManager.getInstance()?.playMove()
                else DpadSoundManager.getInstance()?.playBoundary()
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (controller.pageBy(-controller.windowDurationMs)) DpadSoundManager.getInstance()?.playMove()
                else DpadSoundManager.getInstance()?.playBoundary()
                true
            }
            else -> false
        }
    }

    private fun onProgramClicked(row: GuideRowData, program: GuideProgram) {
        DpadSoundManager.getInstance()?.playSelect()
        if (program.contains(controller.nowMs) || program.startMs <= controller.nowMs) {
            tuneToChannel(row)
        } else {
            showProgramDetails(row, program)
        }
    }

    /** In-guide category switcher — single-choice list of live categories (Favorites first). */
    private fun showCategoryPicker() {
        val cats = viewModel.categories.value
        if (cats.isEmpty()) {
            viewModel.loadCategoryOptions()
            return
        }
        val names = cats.map { it.categoryName }.toTypedArray()
        // android.app.AlertDialog — Leanback theme is not AppCompat-compatible (project rule)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.guide_pick_category)
            .setItems(names) { _, which ->
                val cat = cats[which]
                DpadSoundManager.getInstance()?.playSelect()
                // Reset the timeline window + virtual focus to NOW and re-arm the initial fetch
                initialFetchDone = false
                controller.setHorizon(controller.horizonStartMs, controller.horizonEndMs)
                stopPreview()
                viewModel.switchCategory(cat.categoryId, cat.categoryName)
            }
            .setNegativeButton(R.string.guide_close, null)
            .show()
    }

    private fun showProgramDetails(row: GuideRowData, program: GuideProgram) {
        val timeRange = "${timeFmt.format(Date(program.startMs))} – ${timeFmt.format(Date(program.endMs))}"
        val body = buildString {
            append(row.channel.name)
            append("\n")
            append(timeRange)
            program.description?.takeIf { it.isNotBlank() }?.let {
                append("\n\n")
                append(it)
            }
        }
        // android.app.AlertDialog — Leanback theme is not AppCompat-compatible (project rule)
        AlertDialog.Builder(requireContext())
            .setTitle(program.title)
            .setMessage(body)
            .setPositiveButton(R.string.guide_watch_channel) { _, _ -> tuneToChannel(row) }
            .setNegativeButton(R.string.guide_close, null)
            .show()
    }

    private fun tuneToChannel(row: GuideRowData) {
        val channels = viewModel.channels.value
        if (channels.isEmpty()) return
        val idx = channels.indexOfFirst { it.streamId == row.channel.streamId }.coerceAtLeast(0)
        // Seed the zap list so CH+/- works in fullscreen, same as LiveTvFragment.goFullscreen
        ChannelListHolder.channels = channels
        ChannelListHolder.currentIndex = idx
        // Re-select this channel on back-return (the view is recreated; the instance survives).
        pendingRestoreStreamId = row.channel.streamId
        stopPreview()
        val fragment = OoustreamPlaybackFragment.newInstance(
            streamUrl = viewModel.buildStreamUrl(row.channel.streamId),
            contentType = ContentType.LIVE,
            streamId = row.channel.streamId.toString(),
            streamName = row.channel.name,
            streamIcon = row.channel.streamIcon ?: ""
        )
        val tx = requireActivity().supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
        tx.replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onResume() {
        super.onResume()
        dateLabel?.text = dateFmt.format(Date())
        // Resume WITHOUT view recreation (e.g. app foregrounded): selection is intact, so just
        // re-take focus and restart the preview that onPause released. Back-from-playback recreates
        // the view and restores position + focus + preview via the rows-collector commit callback.
        // TV only: on a phone there's no cursor and this would steal scroll / pop the keyboard.
        if ((rowAdapter?.itemCount ?: 0) > 0 && DeviceUtils.isTV(requireContext())) {
            gridView?.requestFocus()
            bindHeaderForSelected()
            val pos = gridView?.selectedPosition ?: -1
            rowAdapter?.currentList?.getOrNull(pos)?.let { schedulePreview(it.channel) }
        }
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    override fun onDestroyView() {
        viewportJob?.cancel()
        viewportJob = null
        stopPreview()
        previewManager = null
        gridView = null
        categoryChip = null
        rowAdapter = null
        guideHeader = null
        previewPlayerView = null
        previewPlaceholder = null
        headerLogo = null
        headerInitials = null
        headerTitle = null
        headerSubtitle = null
        headerFavorite = null
        headerNowTitle = null
        headerNowTime = null
        headerProgressContainer = null
        headerProgressFill = null
        headerNowRemaining = null
        headerNowDesc = null
        headerNextTitle = null
        btnFullscreen = null
        btnHours = null
        dateLabel = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_CATEGORY_ID = "category_id"
        private const val ARG_CATEGORY_NAME = "category_name"
        private const val PREFS = "epg_guide_prefs"
        private const val KEY_HOURS = "hours_index"
        private const val LONG_PRESS_MS = 500L

        fun newInstance(categoryId: String?, categoryName: String?): EpgGridFragment =
            EpgGridFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CATEGORY_ID, categoryId)
                    putString(ARG_CATEGORY_NAME, categoryName)
                }
            }
    }
}
