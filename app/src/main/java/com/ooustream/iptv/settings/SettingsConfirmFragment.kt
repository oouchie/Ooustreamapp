package com.ooustream.iptv.settings

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/**
 * A reusable confirmation overlay for settings actions.
 * Uses the GuidedStepSupportFragment overlay pattern consistent
 * with the rest of the Leanback UI.
 */
class SettingsConfirmFragment : GuidedStepSupportFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            arguments?.getString(ARG_TITLE) ?: "Confirm",
            arguments?.getString(ARG_MESSAGE) ?: "",
            "Settings",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CONFIRM)
                .title(arguments?.getString(ARG_CONFIRM_LABEL) ?: "Confirm")
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CANCEL)
                .title("Cancel")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_CONFIRM -> {
                onConfirmCallback?.invoke()
                parentFragmentManager.popBackStack()
            }
            ACTION_CANCEL -> {
                parentFragmentManager.popBackStack()
            }
        }
    }

    companion object {
        private const val ACTION_CONFIRM = 1L
        private const val ACTION_CANCEL = 2L

        private const val ARG_TITLE = "arg_title"
        private const val ARG_MESSAGE = "arg_message"
        private const val ARG_CONFIRM_LABEL = "arg_confirm_label"

        /**
         * Holds the callback reference. This mirrors the pattern used by
         * [com.ooustream.iptv.backup.ClearConfirmFragment].
         */
        private var onConfirmCallback: (() -> Unit)? = null

        fun newInstance(
            title: String,
            message: String,
            confirmLabel: String,
            onConfirm: () -> Unit
        ): SettingsConfirmFragment {
            onConfirmCallback = onConfirm
            return SettingsConfirmFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_MESSAGE, message)
                    putString(ARG_CONFIRM_LABEL, confirmLabel)
                }
            }
        }
    }
}
