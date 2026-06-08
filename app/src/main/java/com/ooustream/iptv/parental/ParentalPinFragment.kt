package com.ooustream.iptv.parental

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.R
import com.ooustream.iptv.common.PhoneGuidedStepFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ParentalPinFragment : PhoneGuidedStepFragment() {

    private val viewModel: ParentalViewModel by viewModels()
    private val settingsViewModel: ParentalSettingsViewModel by viewModels()

    /**
     * Track which mode we are displaying so the guidance description
     * and action list can be built accordingly.
     */
    private enum class Mode {
        SETUP,
        ENTER_PIN,
        CHANGE_PIN
    }

    private var mode: Mode = Mode.ENTER_PIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString(ARG_MODE)?.let { modeArg ->
            mode = when (modeArg) {
                MODE_SETUP -> Mode.SETUP
                MODE_CHANGE -> Mode.CHANGE_PIN
                else -> Mode.ENTER_PIN
            }
        }
    }

    // region Guidance

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = getString(R.string.parental_controls)
        val description = when (mode) {
            Mode.SETUP -> getString(R.string.parental_setup_desc)
            Mode.ENTER_PIN -> getString(R.string.parental_enter_pin_desc)
            Mode.CHANGE_PIN -> getString(R.string.parental_change_pin_desc)
        }
        return GuidanceStylist.Guidance(title, description, "", null)
    }

    // endregion

    // region Actions

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        when (mode) {
            Mode.SETUP -> buildSetupActions(actions)
            Mode.ENTER_PIN -> buildEnterPinActions(actions)
            Mode.CHANGE_PIN -> buildChangePinActions(actions)
        }
    }

    private fun buildSetupActions(actions: MutableList<GuidedAction>) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_PIN)
                .title(getString(R.string.parental_new_pin))
                .description("")
                .descriptionEditable(true)
                .descriptionEditInputType(
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CONFIRM)
                .title(getString(R.string.parental_set_pin))
                .build()
        )
    }

    private fun buildEnterPinActions(actions: MutableList<GuidedAction>) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_PIN)
                .title(getString(R.string.parental_pin))
                .description("")
                .descriptionEditable(true)
                .descriptionEditInputType(
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CONFIRM)
                .title(getString(R.string.parental_unlock))
                .build()
        )
    }

    private fun buildChangePinActions(actions: MutableList<GuidedAction>) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_OLD_PIN)
                .title(getString(R.string.parental_current_pin))
                .description("")
                .descriptionEditable(true)
                .descriptionEditInputType(
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_PIN)
                .title(getString(R.string.parental_new_pin))
                .description("")
                .descriptionEditable(true)
                .descriptionEditInputType(
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CONFIRM)
                .title(getString(R.string.parental_change_pin))
                .build()
        )
    }

    override fun onCreateButtonActions(
        actions: MutableList<GuidedAction>,
        savedInstanceState: Bundle?
    ) {
        // "Enable/Disable" toggle -- only relevant when already unlocked.
        // Starts disabled; visibility is controlled by state observation.
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_TOGGLE_ENABLED)
                .title(getString(R.string.parental_disable))
                .enabled(false)
                .build()
        )
    }

    // endregion

    // region Action Handling

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_CONFIRM -> handleConfirm()
            ACTION_TOGGLE_ENABLED -> viewModel.toggleEnabled()
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        // When the user presses Enter/Done on an editable field, advance
        // to the next action in the list.
        return GuidedAction.ACTION_ID_NEXT
    }

    private fun handleConfirm() {
        when (mode) {
            Mode.SETUP -> {
                val pin = getPinText(ACTION_PIN)
                viewModel.setPin(pin)
                // Auto-block adult categories on first PIN setup
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        settingsViewModel.autoBlockAdultOnSetup()
                    }
                }
            }
            Mode.ENTER_PIN -> {
                val pin = getPinText(ACTION_PIN)
                viewModel.verifyPin(pin)
            }
            Mode.CHANGE_PIN -> {
                val oldPin = getPinText(ACTION_OLD_PIN)
                val newPin = getPinText(ACTION_PIN)
                viewModel.changePin(oldPin, newPin)
            }
        }
    }

    private fun getPinText(actionId: Long): String {
        return findActionById(actionId)?.description?.toString()?.trim() ?: ""
    }

    private fun clearPinFields() {
        listOfNotNull(
            findActionById(ACTION_PIN),
            findActionById(ACTION_OLD_PIN)
        ).forEach { action ->
            action.description = ""
            val pos = findActionPositionById(action.id)
            if (pos >= 0) notifyActionChanged(pos)
        }
    }

    // endregion

    // region State Observation

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeState()
        observeToasts()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is ParentalState.Idle -> {
                            // Transitional state, no UI updates needed
                        }
                        is ParentalState.Setup -> {
                            if (mode != Mode.SETUP) {
                                mode = Mode.SETUP
                                recreateFragment()
                            }
                        }
                        is ParentalState.Locked -> {
                            updateConfirmButton(
                                getString(R.string.parental_unlock),
                                enabled = true
                            )
                            updateToggleVisibility(visible = false)
                        }
                        is ParentalState.Unlocked -> {
                            updateToggleVisibility(visible = true)
                            updateToggleLabel()
                            // Navigate to Parental Settings on successful unlock
                            if (mode == Mode.ENTER_PIN || mode == Mode.SETUP) {
                                navigateToSettings()
                            }
                        }
                        is ParentalState.Error -> {
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT)
                                .show()
                            clearPinFields()
                            updateConfirmButton(getConfirmLabel(), enabled = true)
                        }
                        is ParentalState.LockedOut -> {
                            val msg = getString(
                                R.string.parental_too_many_attempts,
                                state.remainingSeconds
                            )
                            updateConfirmButton(msg, enabled = false)
                        }
                    }
                }
            }
        }
    }

    private fun observeToasts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toastEvent.collect { message ->
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // endregion

    // region UI Helpers

    private fun getConfirmLabel(): String = when (mode) {
        Mode.SETUP -> getString(R.string.parental_set_pin)
        Mode.ENTER_PIN -> getString(R.string.parental_unlock)
        Mode.CHANGE_PIN -> getString(R.string.parental_change_pin)
    }

    private fun updateConfirmButton(text: String, enabled: Boolean) {
        findActionById(ACTION_CONFIRM)?.let { action ->
            action.title = text
            action.isEnabled = enabled
            val pos = findActionPositionById(ACTION_CONFIRM)
            if (pos >= 0) notifyActionChanged(pos)
        }
    }

    private fun updateToggleVisibility(visible: Boolean) {
        findButtonActionById(ACTION_TOGGLE_ENABLED)?.let { action ->
            action.isEnabled = visible
            action.title = if (visible) getToggleLabel() else ""
            val pos = findButtonActionPositionById(ACTION_TOGGLE_ENABLED)
            if (pos >= 0) notifyButtonActionChanged(pos)
        }
    }

    private fun updateToggleLabel() {
        findButtonActionById(ACTION_TOGGLE_ENABLED)?.let { action ->
            action.title = getToggleLabel()
            val pos = findButtonActionPositionById(ACTION_TOGGLE_ENABLED)
            if (pos >= 0) notifyButtonActionChanged(pos)
        }
    }

    private fun getToggleLabel(): String {
        return getString(R.string.parental_disable)
    }

    /**
     * Navigate to the full Parental Settings screen after PIN verification.
     * Pop the PIN fragment first so Back from settings returns to Settings menu.
     */
    private fun navigateToSettings() {
        if (!isAdded) return
        val fm = requireActivity().supportFragmentManager
        // Pop this PIN fragment off the back stack
        fm.popBackStack()
        // Then add the settings fragment
        fm.beginTransaction()
            .replace(R.id.main_container, ParentalSettingsFragment())
            .addToBackStack(null)
            .commit()
    }

    /**
     * Replace this fragment with a new instance when the mode changes after
     * initial creation (e.g. ViewModel resolves to Setup when fragment was
     * created in ENTER_PIN mode). GuidedStepSupportFragment does not support
     * rebuilding its action list in-place, so a fresh instance is needed.
     */
    private fun recreateFragment() {
        if (!isAdded) return
        val newFragment = newInstance(
            when (mode) {
                Mode.SETUP -> MODE_SETUP
                Mode.ENTER_PIN -> MODE_ENTER
                Mode.CHANGE_PIN -> MODE_CHANGE
            }
        )
        parentFragmentManager.beginTransaction()
            .replace(id, newFragment)
            .commit()
    }

    // endregion

    companion object {
        private const val ACTION_PIN = 1L
        private const val ACTION_OLD_PIN = 2L
        private const val ACTION_CONFIRM = 3L
        private const val ACTION_TOGGLE_ENABLED = 100L

        private const val ARG_MODE = "arg_mode"
        const val MODE_SETUP = "setup"
        const val MODE_ENTER = "enter"
        const val MODE_CHANGE = "change"

        /**
         * Create a new instance of ParentalPinFragment.
         *
         * @param mode One of [MODE_SETUP], [MODE_ENTER], or [MODE_CHANGE].
         */
        fun newInstance(mode: String = MODE_ENTER): ParentalPinFragment {
            return ParentalPinFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode)
                }
            }
        }
    }
}
