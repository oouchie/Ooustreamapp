package com.ooustream.iptv.multiview

import androidx.lifecycle.ViewModel
import com.ooustream.iptv.data.UserPlanManager
import com.ooustream.iptv.data.model.LiveStream
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

enum class LayoutMode {
    QUAD,      // 2x2 grid
    MAIN_3,    // 1 main + 3 sidebar
    DUAL,      // side by side
    TRIPLE     // 3 columns
}

data class SlotState(
    val channel: LiveStream? = null,
    val isPlaying: Boolean = false
)

data class SwapModeState(
    val sourceSlot: Int
)

@HiltViewModel
class MultiViewViewModel @Inject constructor(
    val userPlanManager: UserPlanManager
) : ViewModel() {

    private val _layoutMode = MutableStateFlow(LayoutMode.QUAD)
    val layoutMode: StateFlow<LayoutMode> = _layoutMode

    private val _slots = MutableStateFlow(Array(4) { SlotState() })
    val slots: StateFlow<Array<SlotState>> = _slots

    private val _audioSlot = MutableStateFlow(0)
    val audioSlot: StateFlow<Int> = _audioSlot

    private val _focusedSlot = MutableStateFlow(0)
    val focusedSlot: StateFlow<Int> = _focusedSlot

    private val _controlsVisible = MutableStateFlow(false)
    val controlsVisible: StateFlow<Boolean> = _controlsVisible

    private val _swapMode = MutableStateFlow<SwapModeState?>(null)
    val swapMode: StateFlow<SwapModeState?> = _swapMode

    private val _fullscreenSlot = MutableStateFlow<Int?>(null)
    val fullscreenSlot: StateFlow<Int?> = _fullscreenSlot

    fun setLayoutMode(mode: LayoutMode) {
        _layoutMode.value = mode
    }

    fun setChannel(slotIndex: Int, channel: LiveStream) {
        val current = _slots.value.copyOf()
        current[slotIndex] = SlotState(channel = channel, isPlaying = true)
        _slots.value = current
    }

    fun clearSlot(slotIndex: Int) {
        val current = _slots.value.copyOf()
        current[slotIndex] = SlotState()
        _slots.value = current

        // If cleared slot was the audio slot, move audio to next occupied slot
        if (_audioSlot.value == slotIndex) {
            val nextOccupied = current.indexOfFirst { it.channel != null }
            if (nextOccupied >= 0) {
                _audioSlot.value = nextOccupied
            }
        }
    }

    fun setAudioSlot(index: Int) {
        _audioSlot.value = index
    }

    fun setFocusedSlot(index: Int) {
        _focusedSlot.value = index
    }

    fun setControlsVisible(visible: Boolean) {
        _controlsVisible.value = visible
    }

    fun enterSwapMode(sourceSlot: Int) {
        _swapMode.value = SwapModeState(sourceSlot)
    }

    fun exitSwapMode() {
        _swapMode.value = null
    }

    val isSwapMode: Boolean
        get() = _swapMode.value != null

    /**
     * Swap channel assignments between two slots in the ViewModel state.
     * Also updates audio slot to follow content.
     */
    fun executeSwap(slotA: Int, slotB: Int) {
        val current = _slots.value.copyOf()
        val temp = current[slotA]
        current[slotA] = current[slotB]
        current[slotB] = temp
        _slots.value = current

        // Audio follows content
        val audio = _audioSlot.value
        if (audio == slotA) {
            _audioSlot.value = slotB
        } else if (audio == slotB) {
            _audioSlot.value = slotA
        }

        _swapMode.value = null
    }

    fun enterFullscreen(slot: Int) {
        _fullscreenSlot.value = slot
    }

    fun exitFullscreen() {
        _fullscreenSlot.value = null
    }

    val isFullscreen: Boolean
        get() = _fullscreenSlot.value != null

    /**
     * Number of active slots that have channels assigned.
     */
    val activeSlotCount: Int
        get() = _slots.value.count { it.channel != null }

    /**
     * Whether all visible slots (based on layout mode) are empty.
     */
    val allSlotsEmpty: Boolean
        get() {
            val visibleCount = when (_layoutMode.value) {
                LayoutMode.QUAD -> 4
                LayoutMode.MAIN_3 -> 4
                LayoutMode.DUAL -> 2
                LayoutMode.TRIPLE -> 3
            }
            return (0 until visibleCount).all { _slots.value[it].channel == null }
        }

    /**
     * Number of visible slots for current layout mode.
     */
    val visibleSlotCount: Int
        get() = when (_layoutMode.value) {
            LayoutMode.QUAD, LayoutMode.MAIN_3 -> 4
            LayoutMode.DUAL -> 2
            LayoutMode.TRIPLE -> 3
        }
}
