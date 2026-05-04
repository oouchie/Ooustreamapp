package com.ooustream.iptv.common

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar

/**
 * v3.7.11 — phone-vs-TV friendly user feedback helper.
 *
 * On phone, a Snackbar is the modern non-blocking pattern (slides in from bottom,
 * dismissible by swipe, doesn't steal focus from whatever the user was reading).
 * Toasts on phone are Android's anti-pattern — they cover content for 2-3.5s
 * with no way to dismiss and look like 2014 design.
 *
 * On TV, Snackbars don't render correctly (no Material theme, no swipe gesture)
 * and Toast is the established TV affordance for transient feedback.
 *
 * Use this for *user-action acknowledgements* — favorite added, link copied,
 * download started. Continue to use Toast directly for diagnostic / system
 * messages (crash log written, debug event, etc.) where the app is talking
 * to itself, not to the user.
 */
object PhoneFeedback {

    /**
     * Shows a Snackbar (phone) or Toast (TV) using [anchor] for context.
     * On phone, Snackbar will walk up the view hierarchy to find a
     * CoordinatorLayout for the swipe-dismiss animation; without one,
     * it still renders correctly at the bottom of [anchor]'s parent.
     */
    fun show(anchor: View, message: CharSequence, longDuration: Boolean = false) {
        val ctx = anchor.context
        if (DeviceUtils.isTV(ctx)) {
            Toast.makeText(ctx, message, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        val length = if (longDuration) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        try {
            Snackbar.make(anchor, message, length).show()
        } catch (_: Throwable) {
            // Defensive: if Snackbar.make can't find a suitable parent (e.g. anchor not
            // attached yet), fall back to Toast so the user always gets feedback.
            Toast.makeText(ctx, message, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Snackbar with a single action button (e.g. "Undo", "Open"). TV falls back
     * to a plain Toast — actions don't translate to a remote-only context.
     */
    fun showAction(
        anchor: View,
        message: CharSequence,
        actionLabel: CharSequence,
        longDuration: Boolean = false,
        onAction: () -> Unit
    ) {
        val ctx = anchor.context
        if (DeviceUtils.isTV(ctx)) {
            Toast.makeText(ctx, message, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        val length = if (longDuration) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        try {
            Snackbar.make(anchor, message, length)
                .setAction(actionLabel) { onAction() }
                .show()
        } catch (_: Throwable) {
            Toast.makeText(ctx, message, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }
}

/** Convenience extension — `view.snack("Added to favorites")`. */
fun View.snack(message: CharSequence, longDuration: Boolean = false) =
    PhoneFeedback.show(this, message, longDuration)

/** Convenience extension on Fragment — uses requireView() as the anchor. */
fun Fragment.snack(message: CharSequence, longDuration: Boolean = false) {
    val v = view ?: run {
        Toast.makeText(requireContext(), message,
            if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        return
    }
    PhoneFeedback.show(v, message, longDuration)
}

/** Convenience extension on Activity — uses the content view. */
fun Activity.snack(message: CharSequence, longDuration: Boolean = false) {
    val v = findViewById<View>(android.R.id.content) ?: run {
        Toast.makeText(this, message,
            if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        return
    }
    PhoneFeedback.show(v, message, longDuration)
}
