package com.ooustream.iptv.multiview

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import com.ooustream.iptv.common.AudioLogger
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.epg.SmartEpgFiller
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class MultiViewFragment : Fragment(), KeyEventHandler {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var contentRepository: ContentRepository
    @Inject lateinit var autoFillUseCase: MultiViewAutoFillUseCase
    @Inject lateinit var smartEpgFiller: SmartEpgFiller

    private val viewModel: MultiViewViewModel by viewModels()

    private var playerManager: MultiViewPlayerManager? = null
    private var stallDetector: MultiViewStallDetector? = null
    private var slotViews: Array<MultiViewSlotView?> = arrayOfNulls(4)
    private var controlsTimer: CountDownTimer? = null
    private var emergencyQualityActive = false

    // EPG data for ticker (slotIndex → epgTitle)
    private val epgData = mutableMapOf<Int, String>()

    // Long-press detection for OK button
    private var okDownTimestamp = 0L
    private val LONG_PRESS_THRESHOLD_MS = 1500L

    // Double-press detection for fullscreen toggle
    private var lastOkUpTimestamp = 0L
    private val DOUBLE_PRESS_WINDOW_MS = 400L

    // Layout references
    private lateinit var gridContainer: ConstraintLayout
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var emptyState: View
    private val slotFrames = arrayOfNulls<FrameLayout>(4)

    // Control bar controllers
    private var topBarController: MultiViewTopBarController? = null
    private var bottomBarController: MultiViewBottomBarController? = null

    // Layout buttons (kept for ConstraintSet logic)
    private var layoutButtons = arrayOfNulls<TextView>(4)

    companion object {
        private const val ARG_SEED_CHANNEL_ID = "seed_channel_id"
        private const val ARG_SEED_CHANNEL_NAME = "seed_channel_name"
        private const val ARG_SEED_STREAM_URL = "seed_stream_url"
        private const val CONTROLS_TIMEOUT_MS = 8000L

        fun newInstance(seedChannel: LiveStream? = null, streamUrl: String? = null): MultiViewFragment {
            return MultiViewFragment().apply {
                arguments = Bundle().apply {
                    seedChannel?.let {
                        putString(ARG_SEED_CHANNEL_ID, it.streamId.toString())
                        putString(ARG_SEED_CHANNEL_NAME, it.name)
                    }
                    streamUrl?.let { putString(ARG_SEED_STREAM_URL, it) }
                }
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Lock to landscape on phones — MultiView is unusable in portrait
        if (!DeviceUtils.isTV(context)) {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onDetach() {
        // Restore default orientation
        if (!DeviceUtils.isTV(requireContext())) {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        super.onDetach()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_multiview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep screen on while MultiView is active
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bindViews(view)
        setupSlotViews()
        setupLayoutButtons()
        setupControlBars()
        setupStallDetector()
        applyLayoutMode(viewModel.layoutMode.value)
        observeState()

        // Load seed channel into slot 1 if provided
        loadSeedChannel()

        // Show empty state if no channels
        updateEmptyState()

        // Default focus on slot 1 (the outer FrameLayout, which is the focusable view)
        slotFrames[0]?.requestFocus()
    }

    private fun bindViews(view: View) {
        gridContainer = view.findViewById(R.id.grid_container)
        topBar = view.findViewById(R.id.top_bar)
        bottomBar = view.findViewById(R.id.bottom_bar)
        emptyState = view.findViewById(R.id.empty_state)

        slotFrames[0] = view.findViewById(R.id.slot_1)
        slotFrames[1] = view.findViewById(R.id.slot_2)
        slotFrames[2] = view.findViewById(R.id.slot_3)
        slotFrames[3] = view.findViewById(R.id.slot_4)

        // Layout selector buttons
        layoutButtons[0] = view.findViewById(R.id.btn_layout_quad)
        layoutButtons[1] = view.findViewById(R.id.btn_layout_main3)
        layoutButtons[2] = view.findViewById(R.id.btn_layout_dual)
        layoutButtons[3] = view.findViewById(R.id.btn_layout_triple)
    }

    private fun setupSlotViews() {
        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLowMemory = am.memoryClass <= 128
        playerManager = MultiViewPlayerManager(requireContext(), okHttpClient, isLowMemory).apply {
            onPlayerError = { slotIndex, _ ->
                // Catch errors that fire before stall detector monitoring starts
                AudioLogger.log("MultiView slot $slotIndex: early error caught, triggering recovery")
                handleRecoveryAction(slotIndex, RecoveryAction.HARD_RESET)
            }
        }
        if (isLowMemory) {
            AudioLogger.log("MultiView: low-memory mode (memoryClass=${am.memoryClass})")
        }

        for (i in 0 until 4) {
            val slotView = MultiViewSlotView(requireContext())
            slotView.slotIndex = i
            slotView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            // Block descendants from stealing focus (e.g. PlayerView/SurfaceView)
            slotFrames[i]?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            slotFrames[i]?.addView(slotView)
            slotViews[i] = slotView

            // Focus tracking on the OUTER FrameLayout (the actual focusable view with the ID)
            slotFrames[i]?.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    viewModel.setFocusedSlot(i)
                    playerManager?.setFocusedSlot(i)
                }
            }

            // Mobile touch: tap to select slot + switch audio, long-press to change channel
            if (!DeviceUtils.isTV(requireContext())) {
                slotFrames[i]?.setOnClickListener {
                    viewModel.setFocusedSlot(i)
                    playerManager?.setFocusedSlot(i)
                    playerManager?.setAudioSlot(i)
                    slotFrames[i]?.requestFocus()
                    showControls()
                }
                slotFrames[i]?.setOnLongClickListener {
                    viewModel.setFocusedSlot(i)
                    openChannelPicker(i)
                    true
                }
            }
        }

        updateFocusTargets()
    }

    private fun setupLayoutButtons() {
        val modes = arrayOf(LayoutMode.QUAD, LayoutMode.MAIN_3, LayoutMode.DUAL, LayoutMode.TRIPLE)
        layoutButtons.forEachIndexed { index, btn ->
            btn?.setOnClickListener {
                viewModel.setLayoutMode(modes[index])
            }
        }
    }

    private fun setupControlBars() {
        topBarController = MultiViewTopBarController(topBar).apply {
            setLayoutClickListener { mode -> viewModel.setLayoutMode(mode) }
        }
        bottomBarController = MultiViewBottomBarController(bottomBar).apply {
            setupAudioButtons(viewModel.visibleSlotCount) { slotIndex ->
                viewModel.setAudioSlot(slotIndex)
                playerManager?.setAudioSlot(slotIndex)
            }
        }
    }

    private fun setupStallDetector() {
        stallDetector = MultiViewStallDetector(viewLifecycleOwner.lifecycleScope).apply {
            onRecoveryAction = { slotIndex, action ->
                handleRecoveryAction(slotIndex, action)
            }
            onHealthChanged = { slotIndex, health ->
                handleHealthChange(slotIndex, health)
            }
            onFirstFrameAfterRecovery = { slotIndex ->
                // Dismiss fade mask when first frame renders after recovery
                slotViews[slotIndex]?.hideRecoveryMask()
            }
        }
    }

    private fun handleRecoveryAction(slotIndex: Int, action: RecoveryAction) {
        if (viewModel.slots.value[slotIndex].channel == null) return
        val slotView = slotViews[slotIndex]

        when (action) {
            RecoveryAction.SOFT_RESET -> {
                // Invisible to user — no overlay needed
                playerManager?.softReset(slotIndex)
            }
            RecoveryAction.HARD_RESET -> {
                // Show brief fade mask to hide visual glitch
                slotView?.showRecoveryMask(withSpinner = false) {
                    playerManager?.hardReset(slotIndex, slotView.getPlayerView())
                    // Re-register monitoring after hard reset
                    playerManager?.getPlayer(slotIndex)?.let {
                        stallDetector?.startMonitoring(slotIndex, it)
                    }
                    // Safety timeout: dismiss mask after 3s even if no first frame
                    slotView.postDelayed({ slotView.hideRecoveryMask() }, 3_000)
                }
            }
            RecoveryAction.NUCLEAR_RESET -> {
                // Full dark + gold spinner during complete rebuild
                slotView?.showRecoveryMask(withSpinner = true) {
                    playerManager?.nuclearReset(slotIndex, slotView.getPlayerView())
                    // Re-register monitoring with the new player
                    playerManager?.getPlayer(slotIndex)?.let {
                        stallDetector?.startMonitoring(slotIndex, it)
                    }
                    // Safety timeout: dismiss mask after 5s
                    slotView.postDelayed({ slotView.hideRecoveryMask(300) }, 5_000)
                }
            }
            RecoveryAction.MARK_SIGNAL_LOST -> {
                slotView?.hideRecoveryMask()
                AudioLogger.log("MultiView slot $slotIndex: marked signal lost")
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleHealthChange(slotIndex: Int, health: PlaybackHealth) {
        // Quality tier escalation: when ANY slot starts chopping, degrade all non-focused slots
        val anyChopping = stallDetector?.healthStates?.value?.any {
            it == PlaybackHealth.CHOPPING || it == PlaybackHealth.FROZEN
        } ?: false

        if (anyChopping && !emergencyQualityActive) {
            emergencyQualityActive = true
            playerManager?.setEmergencyQuality(true)
        } else if (!anyChopping && emergencyQualityActive) {
            emergencyQualityActive = false
            playerManager?.setEmergencyQuality(false)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.layoutMode.collect { applyLayoutMode(it) } }
                launch { viewModel.audioSlot.collect {
                    updateAudioIndicators(it)
                    stallDetector?.audioSlot = it
                } }
                launch { viewModel.focusedSlot.collect { updateSlotFocus(it) } }
                launch { viewModel.controlsVisible.collect { animateControls(it) } }
                launch {
                    viewModel.slots.collect { slots ->
                        updateStreamCount(slots)
                        updateEmptyState()
                    }
                }
                launch {
                    viewModel.swapMode.collect { swap ->
                        if (swap != null) updateSwapVisuals() else clearSwapVisuals()
                    }
                }
                launch {
                    stallDetector?.healthStates?.collect { states ->
                        for (i in 0 until 4) {
                            slotViews[i]?.setHealthIndicator(states[i])
                            slotViews[i]?.setSignalLost(states[i] == PlaybackHealth.DEAD)
                        }
                    }
                }
            }
        }
    }

    // ── Layout Management ──────────────────────────────────────────────

    private fun applyLayoutMode(mode: LayoutMode) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(gridContainer)

        // Reset all slot visibility first
        for (i in 0 until 4) {
            constraintSet.setVisibility(slotFrames[i]!!.id, ConstraintSet.VISIBLE)
            constraintSet.clear(slotFrames[i]!!.id)
        }

        val dividerH = R.id.divider_h
        val dividerV = R.id.divider_v

        when (mode) {
            LayoutMode.QUAD -> applyQuadLayout(constraintSet)
            LayoutMode.MAIN_3 -> applyMain3Layout(constraintSet)
            LayoutMode.DUAL -> {
                applyDualLayout(constraintSet)
                // Shrink to 1×1dp instead of GONE — keeps Surface/MediaCodec alive
                shrinkSlotToMinimal(constraintSet, R.id.slot_3)
                shrinkSlotToMinimal(constraintSet, R.id.slot_4)
                constraintSet.setVisibility(dividerH, ConstraintSet.GONE)
            }
            LayoutMode.TRIPLE -> {
                applyTripleLayout(constraintSet)
                shrinkSlotToMinimal(constraintSet, R.id.slot_4)
                constraintSet.setVisibility(dividerH, ConstraintSet.GONE)
            }
        }

        // Gold dividers only in Quad mode
        if (mode == LayoutMode.QUAD) {
            constraintSet.setVisibility(dividerH, ConstraintSet.VISIBLE)
            constraintSet.setVisibility(dividerV, ConstraintSet.VISIBLE)
        } else if (mode != LayoutMode.DUAL && mode != LayoutMode.TRIPLE) {
            constraintSet.setVisibility(dividerH, ConstraintSet.GONE)
            constraintSet.setVisibility(dividerV, ConstraintSet.GONE)
        }

        // Animate layout transition
        TransitionManager.beginDelayedTransition(
            gridContainer,
            AutoTransition().apply { duration = 300 }
        )
        constraintSet.applyTo(gridContainer)
        updateFocusTargets()
        updateLayoutButtonStyles(mode)
    }

    private fun applyQuadLayout(cs: ConstraintSet) {
        val ids = arrayOf(R.id.slot_1, R.id.slot_2, R.id.slot_3, R.id.slot_4)
        val margin = 2

        // Slot 1: top-left
        cs.connect(ids[0], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
        cs.connect(ids[0], ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, margin)
        cs.connect(ids[0], ConstraintSet.END, ids[1], ConstraintSet.START, margin)
        cs.connect(ids[0], ConstraintSet.BOTTOM, ids[2], ConstraintSet.TOP, margin)
        cs.constrainPercentWidth(ids[0], 0.5f)
        cs.constrainPercentHeight(ids[0], 0.5f)

        // Slot 2: top-right
        cs.connect(ids[1], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
        cs.connect(ids[1], ConstraintSet.START, ids[0], ConstraintSet.END, margin)
        cs.connect(ids[1], ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
        cs.connect(ids[1], ConstraintSet.BOTTOM, ids[3], ConstraintSet.TOP, margin)
        cs.constrainPercentWidth(ids[1], 0.5f)
        cs.constrainPercentHeight(ids[1], 0.5f)

        // Slot 3: bottom-left
        cs.connect(ids[2], ConstraintSet.TOP, ids[0], ConstraintSet.BOTTOM, margin)
        cs.connect(ids[2], ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, margin)
        cs.connect(ids[2], ConstraintSet.END, ids[3], ConstraintSet.START, margin)
        cs.connect(ids[2], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
        cs.constrainPercentWidth(ids[2], 0.5f)
        cs.constrainPercentHeight(ids[2], 0.5f)

        // Slot 4: bottom-right
        cs.connect(ids[3], ConstraintSet.TOP, ids[1], ConstraintSet.BOTTOM, margin)
        cs.connect(ids[3], ConstraintSet.START, ids[2], ConstraintSet.END, margin)
        cs.connect(ids[3], ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
        cs.connect(ids[3], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
        cs.constrainPercentWidth(ids[3], 0.5f)
        cs.constrainPercentHeight(ids[3], 0.5f)
    }

    private fun applyMain3Layout(cs: ConstraintSet) {
        val ids = arrayOf(R.id.slot_1, R.id.slot_2, R.id.slot_3, R.id.slot_4)
        val margin = 2

        // Slot 1: main (left 66%, full height)
        cs.connect(ids[0], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
        cs.connect(ids[0], ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, margin)
        cs.connect(ids[0], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
        cs.constrainPercentWidth(ids[0], 0.66f)
        cs.constrainPercentHeight(ids[0], 1.0f)

        // Slot 2: top sidebar (right 34%, top third)
        cs.connect(ids[1], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
        cs.connect(ids[1], ConstraintSet.START, ids[0], ConstraintSet.END, margin)
        cs.connect(ids[1], ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
        cs.connect(ids[1], ConstraintSet.BOTTOM, ids[2], ConstraintSet.TOP, margin)
        cs.constrainPercentHeight(ids[1], 0.333f)

        // Slot 3: middle sidebar
        cs.connect(ids[2], ConstraintSet.TOP, ids[1], ConstraintSet.BOTTOM, margin)
        cs.connect(ids[2], ConstraintSet.START, ids[0], ConstraintSet.END, margin)
        cs.connect(ids[2], ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
        cs.connect(ids[2], ConstraintSet.BOTTOM, ids[3], ConstraintSet.TOP, margin)
        cs.constrainPercentHeight(ids[2], 0.333f)

        // Slot 4: bottom sidebar
        cs.connect(ids[3], ConstraintSet.TOP, ids[2], ConstraintSet.BOTTOM, margin)
        cs.connect(ids[3], ConstraintSet.START, ids[0], ConstraintSet.END, margin)
        cs.connect(ids[3], ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
        cs.connect(ids[3], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
        cs.constrainPercentHeight(ids[3], 0.333f)
    }

    private fun applyDualLayout(cs: ConstraintSet) {
        val ids = arrayOf(R.id.slot_1, R.id.slot_2)
        val margin = 2

        // Slot 1: left half
        cs.connect(ids[0], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
        cs.connect(ids[0], ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, margin)
        cs.connect(ids[0], ConstraintSet.END, ids[1], ConstraintSet.START, margin)
        cs.connect(ids[0], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
        cs.constrainPercentWidth(ids[0], 0.5f)

        // Slot 2: right half
        cs.connect(ids[1], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
        cs.connect(ids[1], ConstraintSet.START, ids[0], ConstraintSet.END, margin)
        cs.connect(ids[1], ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
        cs.connect(ids[1], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
        cs.constrainPercentWidth(ids[1], 0.5f)
    }

    private fun applyTripleLayout(cs: ConstraintSet) {
        val ids = arrayOf(R.id.slot_1, R.id.slot_2, R.id.slot_3)
        val margin = 2

        // 3 columns, full height each
        cs.connect(ids[0], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
        cs.connect(ids[0], ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, margin)
        cs.connect(ids[0], ConstraintSet.END, ids[1], ConstraintSet.START, margin)
        cs.connect(ids[0], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
        cs.constrainPercentWidth(ids[0], 0.333f)

        cs.connect(ids[1], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
        cs.connect(ids[1], ConstraintSet.START, ids[0], ConstraintSet.END, margin)
        cs.connect(ids[1], ConstraintSet.END, ids[2], ConstraintSet.START, margin)
        cs.connect(ids[1], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
        cs.constrainPercentWidth(ids[1], 0.333f)

        cs.connect(ids[2], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
        cs.connect(ids[2], ConstraintSet.START, ids[1], ConstraintSet.END, margin)
        cs.connect(ids[2], ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
        cs.connect(ids[2], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
        cs.constrainPercentWidth(ids[2], 0.333f)
    }

    // ── Focus Navigation ──────────────────────────────────────────────

    private fun updateFocusTargets() {
        // Focus navigation must be set on slotFrames (the focusable FrameLayouts with IDs),
        // NOT on slotViews (non-focusable rendering children with no IDs)

        // Reset all directional focus targets first
        for (i in 0 until 4) {
            slotFrames[i]?.nextFocusUpId = View.NO_ID
            slotFrames[i]?.nextFocusDownId = View.NO_ID
            slotFrames[i]?.nextFocusLeftId = View.NO_ID
            slotFrames[i]?.nextFocusRightId = View.NO_ID
        }

        when (viewModel.layoutMode.value) {
            LayoutMode.QUAD -> {
                // Slot-to-slot
                slotFrames[0]?.nextFocusRightId = R.id.slot_2
                slotFrames[0]?.nextFocusDownId = R.id.slot_3
                slotFrames[1]?.nextFocusLeftId = R.id.slot_1
                slotFrames[1]?.nextFocusDownId = R.id.slot_4
                slotFrames[2]?.nextFocusRightId = R.id.slot_4
                slotFrames[2]?.nextFocusUpId = R.id.slot_1
                slotFrames[3]?.nextFocusLeftId = R.id.slot_3
                slotFrames[3]?.nextFocusUpId = R.id.slot_2
                // Top-row slots → up → layout buttons (only reachable when controls visible/GONE is skipped)
                slotFrames[0]?.nextFocusUpId = R.id.btn_layout_quad
                slotFrames[1]?.nextFocusUpId = R.id.btn_layout_quad
            }
            LayoutMode.MAIN_3 -> {
                slotFrames[0]?.nextFocusRightId = R.id.slot_2
                slotFrames[1]?.nextFocusLeftId = R.id.slot_1
                slotFrames[1]?.nextFocusDownId = R.id.slot_3
                slotFrames[2]?.nextFocusLeftId = R.id.slot_1
                slotFrames[2]?.nextFocusUpId = R.id.slot_2
                slotFrames[2]?.nextFocusDownId = R.id.slot_4
                slotFrames[3]?.nextFocusLeftId = R.id.slot_1
                slotFrames[3]?.nextFocusUpId = R.id.slot_3
                // Top-row slots → up → layout buttons
                slotFrames[0]?.nextFocusUpId = R.id.btn_layout_quad
                slotFrames[1]?.nextFocusUpId = R.id.btn_layout_quad
            }
            LayoutMode.DUAL -> {
                slotFrames[0]?.nextFocusRightId = R.id.slot_2
                slotFrames[1]?.nextFocusLeftId = R.id.slot_1
                // Both slots → up → layout buttons
                slotFrames[0]?.nextFocusUpId = R.id.btn_layout_quad
                slotFrames[1]?.nextFocusUpId = R.id.btn_layout_quad
            }
            LayoutMode.TRIPLE -> {
                slotFrames[0]?.nextFocusRightId = R.id.slot_2
                slotFrames[1]?.nextFocusLeftId = R.id.slot_1
                slotFrames[1]?.nextFocusRightId = R.id.slot_3
                slotFrames[2]?.nextFocusLeftId = R.id.slot_2
                // All three → up → layout buttons
                slotFrames[0]?.nextFocusUpId = R.id.btn_layout_quad
                slotFrames[1]?.nextFocusUpId = R.id.btn_layout_quad
                slotFrames[2]?.nextFocusUpId = R.id.btn_layout_quad
            }
        }

        // Layout buttons → down → slot 1 (return to grid)
        layoutButtons.forEach { btn ->
            btn?.nextFocusDownId = R.id.slot_1
        }
    }

    // ── Controls Show/Hide ──────────────────────────────────────────────

    private fun showControls() {
        // Set visibility immediately so focus system can find top bar views on this same frame
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        viewModel.setControlsVisible(true)
        resetControlsTimer()
    }

    private fun hideControls() {
        viewModel.setControlsVisible(false)
        controlsTimer?.cancel()
    }

    private fun resetControlsTimer() {
        controlsTimer?.cancel()
        controlsTimer = object : CountDownTimer(CONTROLS_TIMEOUT_MS, CONTROLS_TIMEOUT_MS) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() { hideControls() }
        }.start()
    }

    private fun animateControls(visible: Boolean) {
        val alpha = if (visible) 1f else 0f
        val translationY = if (visible) 0f else -10f * resources.displayMetrics.density

        topBar.animate()
            .alpha(alpha)
            .translationY(if (visible) 0f else translationY)
            .setDuration(400)
            .withStartAction { if (visible) topBar.visibility = View.VISIBLE }
            .withEndAction { if (!visible) topBar.visibility = View.GONE }
            .start()

        bottomBar.animate()
            .alpha(alpha)
            .translationY(if (visible) 0f else -translationY) // Bottom bar translates down
            .setDuration(400)
            .withStartAction { if (visible) bottomBar.visibility = View.VISIBLE }
            .withEndAction { if (!visible) bottomBar.visibility = View.GONE }
            .start()
    }

    // ── Key Handling ──────────────────────────────────────────────────

    /** Check if a slot FrameLayout currently has focus */
    private fun isSlotFocused(): Boolean {
        val focused = requireActivity().currentFocus ?: return false
        return slotFrames.any { it === focused }
    }

    override fun onFullKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Only intercept OK/Enter when a slot is focused (not layout buttons or other controls)
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!isSlotFocused()) return false // Let clicks reach layout buttons etc.

            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                okDownTimestamp = System.currentTimeMillis()
                return true // Consume down, wait for up
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
                return true // Consume repeats while holding
            }
            if (event.action == KeyEvent.ACTION_UP) {
                showControls()
                val holdDuration = System.currentTimeMillis() - okDownTimestamp
                okDownTimestamp = 0L
                val now = System.currentTimeMillis()

                val focused = viewModel.focusedSlot.value
                val slot = viewModel.slots.value[focused]

                // Swap mode: OK confirms target slot
                if (viewModel.isSwapMode) {
                    val source = viewModel.swapMode.value!!.sourceSlot
                    if (focused != source) {
                        executeSwap(source, focused)
                    }
                    exitSwapMode()
                    lastOkUpTimestamp = 0L
                    return true
                }

                if (holdDuration >= LONG_PRESS_THRESHOLD_MS && slot.channel != null) {
                    // Long press on occupied slot: show action menu
                    showSlotActionMenu(focused)
                    lastOkUpTimestamp = 0L
                } else if (slot.channel != null) {
                    // Check for double-press (fullscreen toggle)
                    if (now - lastOkUpTimestamp < DOUBLE_PRESS_WINDOW_MS) {
                        lastOkUpTimestamp = 0L
                        toggleFullscreen(focused)
                    } else {
                        lastOkUpTimestamp = now
                        // Short press on occupied slot: set audio
                        viewModel.setAudioSlot(focused)
                        playerManager?.setAudioSlot(focused)
                    }
                } else {
                    // Empty slot: open channel picker
                    lastOkUpTimestamp = 0L
                    openChannelPicker(focused)
                }
                return true
            }
        }
        return false
    }

    override fun onKeyEvent(keyCode: Int): Boolean {
        // Any key press shows controls and resets timer
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            showControls()
        }

        return when (keyCode) {
            // DPAD_CENTER/ENTER handled in onFullKeyEvent for long-press detection (slots only)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> isSlotFocused()
            KeyEvent.KEYCODE_BACK -> {
                when {
                    // Back cancels swap mode
                    viewModel.isSwapMode -> {
                        exitSwapMode()
                        true
                    }
                    // Back exits fullscreen
                    viewModel.isFullscreen -> {
                        exitFullscreen()
                        true
                    }
                    viewModel.controlsVisible.value -> {
                        hideControls()
                        true
                    }
                    else -> {
                        // Show exit confirmation
                        showExitDialog()
                        true
                    }
                }
            }
            else -> false
        }
    }

    // ── UI Updates ──────────────────────────────────────────────────

    private fun updateAudioIndicators(audioSlot: Int) {
        for (i in 0 until 4) {
            slotViews[i]?.setAudioActive(i == audioSlot)
        }
        bottomBarController?.updateAudioSlot(audioSlot)
    }

    private fun updateSlotFocus(focusedSlot: Int) {
        for (i in 0 until 4) {
            slotViews[i]?.setSlotFocused(i == focusedSlot)
            // No scale animation — SurfaceView renders on a separate hardware layer
            // that doesn't scale with parent transforms, causing visual desync glitches
        }
    }

    private fun updateLayoutButtonStyles(mode: LayoutMode) {
        topBarController?.updateLayoutMode(mode)
    }

    private fun updateStreamCount(slots: Array<SlotState>) {
        val count = slots.count { it.channel != null }
        topBarController?.updateStreamCount(count)
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (viewModel.allSlotsEmpty) View.VISIBLE else View.GONE
    }

    private fun loadSeedChannel() {
        val channelId = arguments?.getString(ARG_SEED_CHANNEL_ID)?.toIntOrNull() ?: return
        val channelName = arguments?.getString(ARG_SEED_CHANNEL_NAME) ?: "Channel $channelId"
        val streamUrl = arguments?.getString(ARG_SEED_STREAM_URL)
            ?: contentRepository.buildLiveStreamUrl(channelId)

        val seedChannel = LiveStream(
            num = null, name = channelName, streamType = "live",
            streamId = channelId, streamIcon = null,
            epgChannelId = null, added = null, categoryId = null,
            customSid = null, tvArchive = null, directSource = null,
            tvArchiveDuration = null
        )

        setSlotChannel(0, seedChannel, streamUrl)

        // Auto-fill remaining slots from same category (staggered to avoid decoder overload)
        viewLifecycleOwner.lifecycleScope.launch {
            val fillChannels = autoFillUseCase.autoFill(seedChannel, viewModel.visibleSlotCount)
            fillChannels.forEachIndexed { index, channel ->
                val slotIndex = index + 1
                if (slotIndex < viewModel.visibleSlotCount) {
                    delay(500L) // Stagger player creation by 500ms
                    val url = contentRepository.buildLiveStreamUrl(channel.streamId)
                    setSlotChannel(slotIndex, channel, url)
                }
            }
            // Enforce correct audio after all players are created
            playerManager?.setAudioSlot(viewModel.audioSlot.value)
        }
    }

    // ── Channel Management ──────────────────────────────────────────

    fun setSlotChannel(slotIndex: Int, channel: LiveStream, streamUrl: String) {
        viewModel.setChannel(slotIndex, channel)
        playerManager?.createPlayer(slotIndex, streamUrl, slotViews[slotIndex]?.getPlayerView())
        slotViews[slotIndex]?.setChannel(channel)

        // Start stall monitoring for this slot
        playerManager?.getPlayer(slotIndex)?.let {
            stallDetector?.startMonitoring(slotIndex, it)
        }

        // If this is the first channel, set audio to it
        if (viewModel.activeSlotCount == 1) {
            viewModel.setAudioSlot(slotIndex)
            playerManager?.setAudioSlot(slotIndex)
        }

        // Fetch EPG for this slot and update ticker
        fetchSlotEpg(slotIndex, channel)

        // Auto-advance focus to next empty slot
        val nextEmpty = (slotIndex + 1 until viewModel.visibleSlotCount).firstOrNull { i ->
            viewModel.slots.value[i].channel == null
        }
        if (nextEmpty != null) {
            slotFrames[nextEmpty]?.requestFocus()
        }
    }

    private fun fetchSlotEpg(slotIndex: Int, channel: LiveStream) {
        viewLifecycleOwner.lifecycleScope.launch {
            val inferred = smartEpgFiller.getSmartEpg(
                null, channel.streamId, channel.name, null
            )
            epgData[slotIndex] = inferred.title
            refreshTicker()
        }
    }

    private fun refreshTicker() {
        val slots = viewModel.slots.value
        val tickerItems = (0 until viewModel.visibleSlotCount).mapNotNull { i ->
            val channel = slots[i].channel ?: return@mapNotNull null
            MultiViewBottomBarController.TickerItem(
                slotNumber = i + 1,
                channelName = channel.name,
                epgTitle = epgData[i] ?: ""
            )
        }
        bottomBarController?.updateTicker(tickerItems)
    }

    // ── Swap Mode ──────────────────────────────────────────────────

    private fun enterSwapMode(sourceSlot: Int) {
        viewModel.enterSwapMode(sourceSlot)
        updateSwapVisuals()
    }

    private fun exitSwapMode() {
        viewModel.exitSwapMode()
        clearSwapVisuals()
    }

    private fun executeSwap(sourceSlot: Int, targetSlot: Int) {
        // Swap players without recreation (avoids rebuffering)
        playerManager?.swapSlots(
            sourceSlot, targetSlot,
            slotViews[sourceSlot]?.getPlayerView(),
            slotViews[targetSlot]?.getPlayerView()
        )

        // Swap ViewModel state (channel assignments + audio follows content)
        viewModel.executeSwap(sourceSlot, targetSlot)

        // Update slot view visuals
        val slots = viewModel.slots.value
        for (i in arrayOf(sourceSlot, targetSlot)) {
            val channel = slots[i].channel
            if (channel != null) {
                slotViews[i]?.setChannel(channel)
            } else {
                slotViews[i]?.clearChannel()
            }
        }

        // Swap EPG data and refresh ticker
        val tempEpg = epgData[sourceSlot]
        epgData[sourceSlot] = epgData[targetSlot] ?: ""
        if (tempEpg != null) epgData[targetSlot] = tempEpg else epgData.remove(targetSlot)
        refreshTicker()
    }

    private fun updateSwapVisuals() {
        val swap = viewModel.swapMode.value ?: return
        for (i in 0 until 4) {
            when {
                i == swap.sourceSlot -> slotViews[i]?.setSwapSource(true)
                viewModel.slots.value[i].channel != null || i < viewModel.visibleSlotCount ->
                    slotViews[i]?.setSwapTarget(true)
                else -> slotViews[i]?.clearSwapVisuals()
            }
        }
    }

    private fun clearSwapVisuals() {
        for (i in 0 until 4) {
            slotViews[i]?.clearSwapVisuals()
            // Restore focus-based background
            slotViews[i]?.setSlotFocused(i == viewModel.focusedSlot.value)
        }
    }

    // ── Fullscreen Toggle ──────────────────────────────────────────

    private fun toggleFullscreen(slotIndex: Int) {
        if (viewModel.isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen(slotIndex)
        }
    }

    private fun enterFullscreen(slotIndex: Int) {
        viewModel.enterFullscreen(slotIndex)

        // Audio follows fullscreen slot
        viewModel.setAudioSlot(slotIndex)
        playerManager?.setAudioSlot(slotIndex)

        // Cap non-fullscreen slots to 240p
        playerManager?.setFullscreenMode(slotIndex)

        // Apply fullscreen ConstraintSet — one slot takes all space, others GONE
        applyFullscreenLayout(slotIndex)

        // Show channel banner briefly
        showFullscreenBanner(slotIndex)
    }

    private fun exitFullscreen() {
        viewModel.exitFullscreen()

        // Restore normal resolution caps
        playerManager?.setFullscreenMode(null)

        // Restore grid layout
        applyLayoutMode(viewModel.layoutMode.value)
    }

    private fun applyFullscreenLayout(slotIndex: Int) {
        val cs = ConstraintSet()
        cs.clone(gridContainer)

        val slotIds = arrayOf(R.id.slot_1, R.id.slot_2, R.id.slot_3, R.id.slot_4)

        for (i in 0 until 4) {
            if (i == slotIndex) {
                cs.clear(slotIds[i])
                cs.connect(slotIds[i], ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                cs.connect(slotIds[i], ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                cs.connect(slotIds[i], ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                cs.connect(slotIds[i], ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                cs.setVisibility(slotIds[i], ConstraintSet.VISIBLE)
            } else {
                // Shrink to 1×1dp instead of GONE — keeps Surface/MediaCodec alive
                shrinkSlotToMinimal(cs, slotIds[i])
            }
        }
        cs.setVisibility(R.id.divider_h, ConstraintSet.GONE)
        cs.setVisibility(R.id.divider_v, ConstraintSet.GONE)

        TransitionManager.beginDelayedTransition(
            gridContainer,
            AutoTransition().apply { duration = 300 }
        )
        cs.applyTo(gridContainer)
    }

    private fun showFullscreenBanner(slotIndex: Int) {
        val channel = viewModel.slots.value[slotIndex].channel ?: return
        val bannerText = TextView(requireContext()).apply {
            text = "[${slotIndex + 1}] ${channel.name}"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            ).also { it.topMargin = (16 * resources.displayMetrics.density).toInt() }
        }

        val root = view as? FrameLayout ?: return
        root.addView(bannerText)

        // Auto-dismiss after 3 seconds
        bannerText.postDelayed({
            bannerText.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction { root.removeView(bannerText) }
                .start()
        }, 3000)
    }

    // ── Slot Action Menu ──────────────────────────────────────────

    private fun showSlotActionMenu(slotIndex: Int) {
        val anchor = slotFrames[slotIndex] ?: return
        SlotActionPopup(requireContext()) { action ->
            when (action) {
                SlotAction.CHANGE -> openChannelPickerForSwap(slotIndex)
                SlotAction.SWAP -> enterSwapMode(slotIndex)
                SlotAction.REMOVE -> removeSlotChannel(slotIndex)
            }
        }.show(anchor)
    }

    private fun removeSlotChannel(slotIndex: Int) {
        stallDetector?.stopMonitoring(slotIndex)
        playerManager?.releaseSlot(slotIndex)
        viewModel.clearSlot(slotIndex)
        slotViews[slotIndex]?.clearChannel()
        epgData.remove(slotIndex)
        refreshTicker()

        // Sync audio slot with ViewModel (clearSlot may have moved it)
        playerManager?.setAudioSlot(viewModel.audioSlot.value)

        // Check if emergency quality can be lifted after slot removal
        if (emergencyQualityActive) {
            val anyChopping = stallDetector?.healthStates?.value?.any {
                it == PlaybackHealth.CHOPPING || it == PlaybackHealth.FROZEN
            } ?: false
            if (!anyChopping) {
                emergencyQualityActive = false
                playerManager?.setEmergencyQuality(false)
            }
        }

        updateEmptyState()
        AudioLogger.log("MultiView slot $slotIndex: removed by user")
    }

    // ── Channel Picker ──────────────────────────────────────────

    private fun openChannelPicker(slotIndex: Int) {
        val dialog = ChannelPickerDialogFragment.newInstance(slotIndex)
        dialog.setOnChannelSelectedListener { channel ->
            val streamUrl = buildStreamUrl(channel)
            setSlotChannel(slotIndex, channel, streamUrl)
        }
        dialog.show(childFragmentManager, "channel_picker")
    }

    private fun openChannelPickerForSwap(slotIndex: Int) {
        val dialog = ChannelPickerDialogFragment.newInstance(slotIndex)
        dialog.setOnChannelSelectedListener { channel ->
            // Release old player before loading new channel
            playerManager?.releaseSlot(slotIndex)
            epgData.remove(slotIndex)
            val streamUrl = buildStreamUrl(channel)
            setSlotChannel(slotIndex, channel, streamUrl)
        }
        dialog.show(childFragmentManager, "channel_picker_swap")
    }

    private fun buildStreamUrl(channel: LiveStream): String {
        return contentRepository.buildLiveStreamUrl(channel.streamId)
    }

    private fun showExitDialog() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Exit MultiView")
            .setMessage("Return to Live TV?")
            .setPositiveButton("Exit") { _, _ ->
                parentFragmentManager.popBackStack()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Layout Helpers ──────────────────────────────────────────────

    /**
     * Shrink a slot to 1×1dp instead of GONE.
     * GONE destroys the SurfaceView's Surface → MediaCodec destroyed → must wait for
     * next keyframe when visible again (up to 15s black screen).
     * 1×1dp keeps the Surface alive with zero visual footprint.
     */
    private fun shrinkSlotToMinimal(cs: ConstraintSet, slotId: Int) {
        cs.clear(slotId)
        cs.constrainWidth(slotId, 1)
        cs.constrainHeight(slotId, 1)
        cs.connect(slotId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        cs.connect(slotId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        cs.setVisibility(slotId, ConstraintSet.VISIBLE)
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onDestroyView() {
        // Allow screen to sleep again
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controlsTimer?.cancel()
        stallDetector?.stopAll()
        stallDetector = null
        bottomBarController?.stopTicker()
        playerManager?.releaseAll()
        playerManager = null
        slotViews = arrayOfNulls(4)
        super.onDestroyView()
    }
}
