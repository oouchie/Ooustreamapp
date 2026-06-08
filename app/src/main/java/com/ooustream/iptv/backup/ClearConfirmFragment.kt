package com.ooustream.iptv.backup

import android.os.Bundle
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import com.ooustream.iptv.common.PhoneGuidedStepFragment

class ClearConfirmFragment : PhoneGuidedStepFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            "Clear All Data?",
            "This will permanently delete all your favorites, watch progress, search history, and saved credentials. This action cannot be undone.",
            "Confirm",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CONFIRM)
                .title("Yes, Clear Everything")
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

        private var onConfirmCallback: (() -> Unit)? = null

        fun newInstance(onConfirm: () -> Unit): ClearConfirmFragment {
            onConfirmCallback = onConfirm
            return ClearConfirmFragment()
        }
    }
}
