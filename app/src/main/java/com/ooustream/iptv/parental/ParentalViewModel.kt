package com.ooustream.iptv.parental

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ParentalState {
    object Idle : ParentalState()
    object Setup : ParentalState()
    object Locked : ParentalState()
    object Unlocked : ParentalState()
    data class Error(val message: String) : ParentalState()
    data class LockedOut(val remainingSeconds: Int) : ParentalState()
}

@HiltViewModel
class ParentalViewModel @Inject constructor(
    private val parentalControlManager: ParentalControlManager
) : BaseViewModel() {

    private val _state = MutableStateFlow<ParentalState>(ParentalState.Idle)
    val state: StateFlow<ParentalState> = _state.asStateFlow()

    init {
        resolveInitialState()
    }

    /**
     * Determine the correct initial state based on current configuration.
     */
    private fun resolveInitialState() {
        _state.value = when {
            parentalControlManager.isLockedOut() -> {
                startLockoutCountdown()
                ParentalState.LockedOut(parentalControlManager.getLockoutRemainingSeconds())
            }
            !parentalControlManager.hasPinSet() -> ParentalState.Setup
            parentalControlManager.isLocked.value -> ParentalState.Locked
            else -> ParentalState.Unlocked
        }
    }

    /**
     * Verify PIN to unlock parental controls.
     */
    fun verifyPin(pin: String) {
        if (pin.length != 4) {
            _state.value = ParentalState.Error("PIN must be 4 digits")
            return
        }

        if (parentalControlManager.isLockedOut()) {
            startLockoutCountdown()
            _state.value = ParentalState.LockedOut(
                parentalControlManager.getLockoutRemainingSeconds()
            )
            return
        }

        _isLoading.value = true

        if (parentalControlManager.unlock(pin)) {
            _isLoading.value = false
            _state.value = ParentalState.Unlocked
            viewModelScope.launch {
                _toastEvent.emit("Parental controls unlocked")
            }
        } else {
            _isLoading.value = false
            if (parentalControlManager.isLockedOut()) {
                startLockoutCountdown()
                _state.value = ParentalState.LockedOut(
                    parentalControlManager.getLockoutRemainingSeconds()
                )
            } else {
                val remaining = MAX_FAILED_ATTEMPTS -
                    parentalControlManager.getFailedAttempts()
                _state.value = ParentalState.Error(
                    "Incorrect PIN. $remaining attempt${if (remaining != 1) "s" else ""} remaining."
                )
            }
        }
    }

    /**
     * Set a new PIN during initial setup.
     */
    fun setPin(pin: String) {
        if (pin.length != 4) {
            _state.value = ParentalState.Error("PIN must be 4 digits")
            return
        }

        parentalControlManager.setPin(pin)
        _state.value = ParentalState.Unlocked
        viewModelScope.launch {
            _toastEvent.emit("Parental PIN set successfully")
        }
    }

    /**
     * Change the existing PIN.
     */
    fun changePin(oldPin: String, newPin: String) {
        if (oldPin.length != 4 || newPin.length != 4) {
            _state.value = ParentalState.Error("PIN must be 4 digits")
            return
        }

        if (parentalControlManager.changePin(oldPin, newPin)) {
            _state.value = ParentalState.Unlocked
            viewModelScope.launch {
                _toastEvent.emit("PIN changed successfully")
            }
        } else {
            if (parentalControlManager.isLockedOut()) {
                startLockoutCountdown()
                _state.value = ParentalState.LockedOut(
                    parentalControlManager.getLockoutRemainingSeconds()
                )
            } else {
                _state.value = ParentalState.Error("Current PIN is incorrect")
            }
        }
    }

    /**
     * Toggle parental controls on/off. Requires PIN to already be verified (unlocked state).
     */
    fun toggleEnabled() {
        val currentlyEnabled = parentalControlManager.isEnabled.value
        parentalControlManager.setEnabled(!currentlyEnabled)

        if (currentlyEnabled) {
            // Disabled parental controls
            _state.value = ParentalState.Unlocked
            viewModelScope.launch {
                _toastEvent.emit("Parental controls disabled")
            }
        } else {
            // Enabled parental controls
            _state.value = ParentalState.Unlocked
            viewModelScope.launch {
                _toastEvent.emit("Parental controls enabled")
            }
        }
    }

    /**
     * Re-lock parental controls.
     */
    fun lockControls() {
        parentalControlManager.lock()
        _state.value = ParentalState.Locked
    }

    /**
     * Start a coroutine that counts down the lockout timer and transitions
     * back to Locked state when the lockout period expires.
     */
    private fun startLockoutCountdown() {
        viewModelScope.launch {
            while (parentalControlManager.isLockedOut()) {
                val remaining = parentalControlManager.getLockoutRemainingSeconds()
                _state.value = ParentalState.LockedOut(remaining)
                delay(1000L)
            }
            parentalControlManager.resetFailedAttempts()
            _state.value = ParentalState.Locked
        }
    }

    companion object {
        /** Max failed PIN attempts before lockout. Matches ParentalControlManager. */
        private const val MAX_FAILED_ATTEMPTS = 5
    }
}
