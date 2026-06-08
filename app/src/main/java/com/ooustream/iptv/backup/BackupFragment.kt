package com.ooustream.iptv.backup

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.PhoneGuidedStepFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BackupFragment : PhoneGuidedStepFragment() {

    private val viewModel: BackupViewModel by viewModels()

    // SAF picker: read the bytes of a chosen .ooubackup file and restore (the export is encrypted,
    // so this — not the paste-JSON box — is how a phone user restores an exported backup).
    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val bytes = runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) {
                viewModel.importBackupEncrypted(bytes)
            } else {
                Toast.makeText(requireContext(), "Could not read that file", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            "Backup & Restore",
            "Export your favorites and watch progress to an encrypted backup file, or restore from a previous backup.",
            "Settings",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_EXPORT)
                .title("Export Backup")
                .description("Save favorites and watch progress to an encrypted .ooubackup file")
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

        // Phone: a real file picker for the encrypted .ooubackup export (pasting multi-line JSON
        // into a Leanback inline editor is impractical on a touch keyboard, and the paste path
        // can't decrypt the exported file anyway).
        if (!DeviceUtils.isTV(requireContext())) {
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(ACTION_IMPORT_FILE)
                    .title("Import from File")
                    .description("Restore from a saved .ooubackup file")
                    .build()
            )
        }

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CLEAR)
                .title("Clear All Data")
                .description("Remove all favorites, watch progress, and search history")
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

            ACTION_IMPORT_FILE -> importFileLauncher.launch(arrayOf("*/*"))

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
                updateAction(ACTION_EXPORT, "Export Backup", "Save favorites and watch progress to an encrypted .ooubackup file")
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
                updateAction(ACTION_EXPORT, "Export Backup", "Save favorites and watch progress to an encrypted .ooubackup file")
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
        private const val ACTION_IMPORT_FILE = 5L
    }
}
