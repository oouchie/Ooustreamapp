package com.ooustream.iptv.multiview

import android.content.Context
import androidx.fragment.app.FragmentManager

/**
 * Utility for showing a "locked feature" popup when Basic plan users
 * try to access MultiView functionality.
 *
 * Shows a simple AlertDialog with upgrade CTA that opens the QR upgrade dialog.
 */
object MultiViewLockedPopup {

    /**
     * Shows a locked feature popup with upgrade information.
     * Uses android.app.AlertDialog (NOT AppCompat) for Leanback theme compatibility.
     *
     * @param context The context for the dialog
     * @param fragmentManager The fragment manager to show the QR upgrade dialog
     */
    fun show(context: Context, fragmentManager: FragmentManager) {
        android.app.AlertDialog.Builder(context)
            .setTitle("MultiView — Pro Feature")
            .setMessage("Upgrade to Pro to unlock MultiView.\n\n4+ connections • MultiView Sports Player")
            .setPositiveButton("Learn More") { _, _ ->
                val dialog = QrUpgradeDialogFragment()
                dialog.show(fragmentManager, "qr_upgrade")
            }
            .setNegativeButton("Not Now", null)
            .show()
    }
}
