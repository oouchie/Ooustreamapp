package com.ooustream.iptv.epg.guide

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.player.ChannelListHolder
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Classic TV-guide grid: fixed channel column + horizontally scrolling shared timeline
 * (2h window, NOW line, 30-min ruler). Vertical axis is a Leanback VerticalGridView
 * (real View focus, recycling); horizontal focus is virtual via GuideTimelineController.
 * OK on a now-airing cell tunes fullscreen (channel list seeded for zapping); OK on a
 * future cell shows program details. Category-scoped — EPG is fetched viewport-driven only.
 */
@AndroidEntryPoint
class EpgGridFragment : Fragment(), KeyEventHandler {

    private val viewModel: EpgGridViewModel by viewModels()
    private val controller = GuideTimelineController()

    private var gridView: VerticalGridView? = null
    private var categoryChip: TextView? = null
    private var rowAdapter: GuideRowAdapter? = null
    private var viewportJob: Job? = null
    private var initialFetchDone = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_epg_grid, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val isTv = DeviceUtils.isTV(requireContext())

        // Horizon: NOW floored to the half-hour, forward 12h (get_short_epg has no past programs)
        val slot = 30 * 60_000L
        val horizonStart = (System.currentTimeMillis() / slot) * slot
        val horizonEnd = horizonStart + 12 * 60 * 60_000L
        controller.setHorizon(horizonStart, horizonEnd)
        viewModel.setHorizon(horizonStart, horizonEnd)

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
                scheduleViewportFetch()
            }
        })

        // Category chip — focusable, opens the in-guide category picker (UP from the grid lands here)
        val categoryLabel = view.findViewById<TextView>(R.id.guide_category_label)
        categoryChip = categoryLabel
        categoryLabel.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f * resources.displayMetrics.density
                    setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFFFC107.toInt())
                    setColor(0x14FFD700)
                }
            } else {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f * resources.displayMetrics.density
                    setStroke((1 * resources.displayMetrics.density).toInt(), 0x33FFFFFF)
                }
            }
        }
        // Trigger the unfocused chip outline
        categoryLabel.onFocusChangeListener.onFocusChange(categoryLabel, false)
        categoryLabel.setOnClickListener { showCategoryPicker() }
        viewModel.loadCategoryOptions()

        // Data
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rows.collect { rows ->
                    adapter.submitList(rows)
                    val name = viewModel.categoryName ?: getString(R.string.guide_all_channels)
                    categoryLabel.text = "$name ▾"
                    if (rows.isNotEmpty() && !initialFetchDone) {
                        initialFetchDone = true
                        viewModel.onVisibleRangeChanged(0, 12)
                        grid.requestFocus()
                    }
                }
            }
        }

        // NOW ticker — keeps the gold line and current-program highlight honest
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                controller.tickNow()
            }
        }

        // Phone hint copy
        if (!isTv) {
            view.findViewById<TextView>(R.id.guide_hints)?.setText(R.string.guide_hints_touch)
        }

        viewModel.loadChannels(
            arguments?.getString(ARG_CATEGORY_ID),
            arguments?.getString(ARG_CATEGORY_NAME)
        )
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

    override fun onKeyEvent(keyCode: Int): Boolean {
        if (gridView?.hasFocus() != true) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveFocusHorizontal(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocusHorizontal(+1)
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Leanback keeps focus inside the grid at the top edge — hand it to the
                // category chip explicitly so the picker is reachable by D-pad.
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
                viewModel.switchCategory(cat.categoryId, cat.categoryName)
            }
            .setNegativeButton(R.string.guide_close, null)
            .show()
    }

    private fun showProgramDetails(row: GuideRowData, program: GuideProgram) {
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
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

    override fun onDestroyView() {
        viewportJob?.cancel()
        viewportJob = null
        gridView = null
        categoryChip = null
        rowAdapter = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_CATEGORY_ID = "category_id"
        private const val ARG_CATEGORY_NAME = "category_name"

        fun newInstance(categoryId: String?, categoryName: String?): EpgGridFragment =
            EpgGridFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CATEGORY_ID, categoryId)
                    putString(ARG_CATEGORY_NAME, categoryName)
                }
            }
    }
}
