package com.ooustream.iptv.update

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class UpdateFragment : GuidedStepSupportFragment() {

    private val viewModel: UpdateViewModel by viewModels()

    @Inject
    lateinit var updateService: UpdateService

    companion object {
        // Fixed 2-slot layout: info row + action row (GuidedStep doesn't support item count changes)
        private const val ACTION_INFO = 1L
        private const val ACTION_BUTTON = 2L

        fun newInstance(): UpdateFragment = UpdateFragment()
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            "Software Update",
            "Manage application updates",
            "Ooustream IPTV",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        // Always create exactly 2 actions — update their content dynamically
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_INFO)
                .title("Checking...")
                .description("Contacting update server")
                .enabled(false)
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ACTION_BUTTON)
                .title(" ")
                .enabled(false)
                .build()
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.updateState.collect { state ->
                    updateActions(state)
                }
            }
        }

        // Auto-check for updates when the fragment is opened
        viewModel.checkForUpdate()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id != ACTION_BUTTON) return

        when (val state = viewModel.updateState.value) {
            is UpdateViewModel.UpdateState.Idle,
            is UpdateViewModel.UpdateState.UpToDate,
            is UpdateViewModel.UpdateState.Error -> {
                viewModel.checkForUpdate()
            }
            is UpdateViewModel.UpdateState.UpdateAvailable -> {
                viewModel.downloadUpdate(state.manifest.resolveDownloadUrl())
            }
            is UpdateViewModel.UpdateState.DownloadComplete -> {
                viewModel.installUpdate(state.file)
            }
            else -> { /* Checking/Downloading — ignore clicks */ }
        }
    }

    /** Updates the fixed 2-action layout in place — no item count changes, just content. */
    private fun updateActions(state: UpdateViewModel.UpdateState) {
        val infoAction = findActionById(ACTION_INFO) ?: return
        val buttonAction = findActionById(ACTION_BUTTON) ?: return

        when (state) {
            is UpdateViewModel.UpdateState.Idle -> {
                infoAction.title = "Current version: ${updateService.getCurrentVersion()}"
                infoAction.description = "Press below to check for updates"
                buttonAction.title = "Check for Updates"
                buttonAction.description = null
                buttonAction.isEnabled = true
            }

            is UpdateViewModel.UpdateState.Checking -> {
                infoAction.title = "Checking..."
                infoAction.description = "Contacting update server"
                buttonAction.title = "Please wait"
                buttonAction.description = null
                buttonAction.isEnabled = false
            }

            is UpdateViewModel.UpdateState.UpToDate -> {
                infoAction.title = "App is up to date"
                infoAction.description = "Current version: ${updateService.getCurrentVersion()}"
                buttonAction.title = "Check Again"
                buttonAction.description = null
                buttonAction.isEnabled = true
            }

            is UpdateViewModel.UpdateState.UpdateAvailable -> {
                infoAction.title = "Update Available: v${state.manifest.versionName}"
                infoAction.description = state.manifest.changelog
                buttonAction.title = "Download Update"
                buttonAction.description =
                    "Current: v${updateService.getCurrentVersion()} \u2192 New: v${state.manifest.versionName}" +
                        if (state.manifest.mandatory) " (Required)" else ""
                buttonAction.isEnabled = true
            }

            is UpdateViewModel.UpdateState.Downloading -> {
                val percent = (state.progress * 100).toInt()
                infoAction.title = "Downloading... $percent%"
                infoAction.description = "Please wait while the update is downloaded"
                buttonAction.title = "Downloading..."
                buttonAction.description = null
                buttonAction.isEnabled = false
            }

            is UpdateViewModel.UpdateState.DownloadComplete -> {
                infoAction.title = "Download Complete"
                infoAction.description = "Ready to install"
                buttonAction.title = "Install Update"
                buttonAction.description = "Press to install the update"
                buttonAction.isEnabled = true
            }

            is UpdateViewModel.UpdateState.Error -> {
                infoAction.title = "Update Error"
                infoAction.description = state.message
                buttonAction.title = "Retry"
                buttonAction.description = "Try checking for updates again"
                buttonAction.isEnabled = true
            }
        }

        // Safe: always 2 items, no structural change — notifyActionChanged works
        notifyActionChanged(findActionPositionById(ACTION_INFO))
        notifyActionChanged(findActionPositionById(ACTION_BUTTON))
    }
}
