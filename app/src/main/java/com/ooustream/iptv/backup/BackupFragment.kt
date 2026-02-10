package com.ooustream.iptv.backup

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BackupFragment : GuidedStepSupportFragment() {

    private val viewModel: BackupViewModel by viewModels()

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            "Backup & Restore",
            "Export your favorites, watch progress, and credentials to a file, or restore from a previous backup.",
            "Settings",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_EXPORT)
                .title("Export Backup")
                .description("Save all data to a backup file")
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_IMPORT)
                .title("Import Backup")
                .description("Paste backup JSON below")
                .descriptionEditable(true)
                .descriptionEditInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_IMPORT_CONFIRM)
                .title("Restore from Backup")
                .description("Tap after pasting JSON above")
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CLEAR)
                .title("Clear All Data")
                .description("Remove all favorites, watch progress, and credentials")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_EXPORT -> viewModel.exportBackup()

            ACTION_IMPORT_CONFIRM -> {
                val json = findActionById(ACTION_IMPORT)?.description?.toString()
                if (json.isNullOrBlank() || json == "Paste backup JSON below") {
                    Toast.makeText(requireContext(), "Please paste backup JSON first", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.importBackup(json)
                }
            }

            ACTION_CLEAR -> showClearConfirmation()
        }
    }

    private fun showClearConfirmation() {
        add(parentFragmentManager, ClearConfirmFragment.newInstance {
            viewModel.clearAllData()
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.backupState.collect { state ->
                        updateActionsForState(state)
                    }
                }
                launch {
                    viewModel.toastEvent.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateActionsForState(state: BackupState) {
        when (state) {
            is BackupState.Idle -> {
                updateAction(ACTION_EXPORT, "Export Backup", "Save all data to a backup file")
                updateAction(ACTION_IMPORT_CONFIRM, "Restore from Backup", "Tap after pasting JSON above")
            }
            is BackupState.Exporting -> {
                updateAction(ACTION_EXPORT, "Exporting...", "Please wait")
            }
            is BackupState.ExportSuccess -> {
                updateAction(ACTION_EXPORT, "Export Backup", "Last backup: ${state.filePath}")
            }
            is BackupState.Importing -> {
                updateAction(ACTION_IMPORT_CONFIRM, "Restoring...", "Please wait")
            }
            is BackupState.ImportSuccess -> {
                updateAction(ACTION_IMPORT_CONFIRM, "Restore from Backup", "Restored ${state.itemCount} items successfully")
            }
            is BackupState.Error -> {
                updateAction(ACTION_EXPORT, "Export Backup", "Save all data to a backup file")
                updateAction(ACTION_IMPORT_CONFIRM, "Restore from Backup", "Error: ${state.message}")
            }
        }
    }

    private fun updateAction(actionId: Long, title: String, description: String) {
        val position = findActionPositionById(actionId)
        if (position >= 0) {
            findActionById(actionId)?.apply {
                this.title = title
                this.description = description
            }
            notifyActionChanged(position)
        }
    }

    companion object {
        private const val ACTION_EXPORT = 1L
        private const val ACTION_IMPORT = 2L
        private const val ACTION_IMPORT_CONFIRM = 3L
        private const val ACTION_CLEAR = 4L
    }
}
