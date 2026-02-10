package com.ooustream.iptv.update

import android.graphics.Color
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
        private const val ACTION_CHECK = 1L
        private const val ACTION_DOWNLOAD = 2L
        private const val ACTION_INSTALL = 3L
        private const val ACTION_RETRY = 4L
        private const val ACTION_INFO = 5L

        fun newInstance(): UpdateFragment = UpdateFragment()
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            "Software Update",
            "Manage application updates",
            "OOUStream IPTV",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        buildActionsForState(actions, viewModel.updateState.value)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.updateState.collect { state ->
                    rebuildActions(state)
                }
            }
        }

        // Auto-check for updates when the fragment is opened
        viewModel.checkForUpdate()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_CHECK -> {
                viewModel.checkForUpdate()
            }
            ACTION_DOWNLOAD -> {
                val state = viewModel.updateState.value
                if (state is UpdateViewModel.UpdateState.UpdateAvailable) {
                    viewModel.downloadUpdate(state.manifest.downloadUrl)
                }
            }
            ACTION_INSTALL -> {
                val state = viewModel.updateState.value
                if (state is UpdateViewModel.UpdateState.DownloadComplete) {
                    viewModel.installUpdate(state.file)
                }
            }
            ACTION_RETRY -> {
                viewModel.checkForUpdate()
            }
        }
    }

    private fun rebuildActions(state: UpdateViewModel.UpdateState) {
        val actions = mutableListOf<GuidedAction>()
        buildActionsForState(actions, state)

        // Replace current actions with the new list
        val currentActions = this.actions
        if (currentActions != null) {
            currentActions.clear()
            currentActions.addAll(actions)
            // Notify adapter of changes for each position
            for (i in actions.indices) {
                notifyActionChanged(i)
            }
        }
    }

    private fun buildActionsForState(
        actions: MutableList<GuidedAction>,
        state: UpdateViewModel.UpdateState
    ) {
        val context = context ?: return

        when (state) {
            is UpdateViewModel.UpdateState.Idle -> {
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_CHECK)
                        .title("Check for Updates")
                        .description("Current version: ${updateService.getCurrentVersion()}")
                        .build()
                )
            }

            is UpdateViewModel.UpdateState.Checking -> {
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_INFO)
                        .title("Checking...")
                        .description("Contacting update server")
                        .enabled(false)
                        .build()
                )
            }

            is UpdateViewModel.UpdateState.UpToDate -> {
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_INFO)
                        .title("App is up to date")
                        .description("Current version: ${updateService.getCurrentVersion()}")
                        .enabled(false)
                        .build()
                )
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_CHECK)
                        .title("Check Again")
                        .build()
                )
            }

            is UpdateViewModel.UpdateState.UpdateAvailable -> {
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_INFO)
                        .title("Update Available: v${state.manifest.versionName}")
                        .description(state.manifest.changelog)
                        .multilineDescription(true)
                        .enabled(false)
                        .build()
                )
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_DOWNLOAD)
                        .title("Download Update")
                        .description(
                            "Current: v${updateService.getCurrentVersion()} \u2192 New: v${state.manifest.versionName}" +
                                if (state.manifest.mandatory) " (Required)" else ""
                        )
                        .build()
                )
            }

            is UpdateViewModel.UpdateState.Downloading -> {
                val percent = (state.progress * 100).toInt()
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_INFO)
                        .title("Downloading... $percent%")
                        .description("Please wait while the update is downloaded")
                        .enabled(false)
                        .build()
                )
            }

            is UpdateViewModel.UpdateState.DownloadComplete -> {
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_INSTALL)
                        .title("Install Update")
                        .description("Download complete. Press to install.")
                        .build()
                )
            }

            is UpdateViewModel.UpdateState.Error -> {
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_INFO)
                        .title("Update Error")
                        .description(state.message)
                        .multilineDescription(true)
                        .enabled(false)
                        .build()
                )
                actions.add(
                    GuidedAction.Builder(context)
                        .id(ACTION_RETRY)
                        .title("Retry")
                        .description("Try checking for updates again")
                        .build()
                )
            }
        }
    }
}
