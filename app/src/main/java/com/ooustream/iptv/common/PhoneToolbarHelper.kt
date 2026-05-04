package com.ooustream.iptv.common

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * v3.7.11 — drops a minimal back-arrow toolbar onto the top of any fragment's
 * root view, but only on phone. TV doesn't get one — Leanback fragments are
 * D-pad navigated, so a touch-only back arrow would just be visual noise.
 *
 * The toolbar is overlay-style (drawn on top of the existing content, with a
 * subtle dark scrim) to avoid restructuring detail layouts that already use
 * full-bleed backdrops. ~48dp tall + status-bar inset.
 *
 * Usage:
 *
 * ```
 * override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *     super.onViewCreated(view, savedInstanceState)
 *     PhoneToolbarHelper.attach(this, view as ViewGroup, title = movie.title)
 * }
 * ```
 *
 * Returns the back-button View so callers can change its visibility/icon if
 * needed; null if the device is TV or attachment failed.
 */
object PhoneToolbarHelper {

    fun attach(
        fragment: Fragment,
        root: ViewGroup,
        title: String? = null
    ): View? {
        if (DeviceUtils.isTV(fragment.requireContext())) return null

        val ctx = fragment.requireContext()
        val density = ctx.resources.displayMetrics.density

        // Read the current status bar height so the toolbar floats below it,
        // not under it. Using the deprecated resource lookup since `WindowInsets`
        // requires hooking onApplyWindowInsets which is overkill here.
        val statusInset = ctx.resources
            .getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) ctx.resources.getDimensionPixelSize(it) else 0 }

        // Background scrim — gradient from dark at top fading down — gives the
        // back arrow contrast against bright movie posters/backdrops.
        val scrim = View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                ((48 * density).toInt() + statusInset + (32 * density).toInt())
            )
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), statusInset, (12 * density).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (48 * density).toInt() + statusInset
            )
        }

        val backButton = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_revert) // ← arrow as fallback
            // Prefer the Material back arrow if available
            ctx.resources.getIdentifier("ic_arrow_back_24", "drawable", ctx.packageName)
                .takeIf { it != 0 }
                ?.let { setImageResource(it) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x55000000)
            }
            setColorFilter(Color.WHITE)
            contentDescription = "Back"
            layoutParams = LinearLayout.LayoutParams(
                (44 * density).toInt(), (44 * density).toInt()
            ).apply { marginStart = (4 * density).toInt() }
            setPadding((10 * density).toInt(), (10 * density).toInt(),
                (10 * density).toInt(), (10 * density).toInt())
            setOnClickListener {
                // Pop back-stack via dispatcher so OnBackPressedCallbacks still fire.
                fragment.requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        bar.addView(backButton)

        if (!title.isNullOrBlank()) {
            val titleView = TextView(ctx).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (12 * density).toInt() }
            }
            bar.addView(titleView)
        }

        // Stack the scrim under the bar so the icons stay legible. Add to the
        // top of the root so they overlay everything (backdrop, poster, etc.).
        root.addView(scrim)
        root.addView(bar)

        // Bring scrim + bar to front in case the fragment adds further views later.
        scrim.bringToFront()
        bar.bringToFront()
        return backButton
    }
}
